package au.edu.eq.questionbank.admin;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import au.edu.eq.questionbank.diagnostics.PerformanceOperation;
import au.edu.eq.questionbank.diagnostics.PerformanceRecorder;
import au.edu.eq.questionbank.diagnostics.PerformanceSqlScope;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

/**
 * Compares ordinary disabled and session-recorded diagnostic overhead using
 * existing production read-only corpus benchmark workloads.
 *
 * This tool never starts JavaFX or modifies a database. Timings measure the
 * complete instrumented workload from outside the recorder itself.
 */
public final class PerformanceDiagnosticsOverheadTool {

	private static final int DEFAULT_ROUNDS = 5;
	private static final int DEFAULT_WARMUPS = 2;
	private static final String HEADER = "timestamp_utc,code_label,snapshot_sha256,schema_version,"
			+ "question_count,operation,warmups,rounds,samples_per_mode,result_count,"
			+ "disabled_median_ms,enabled_median_ms,delta_median_ms,delta_percent,"
			+ "disabled_p90_ms,enabled_p90_ms,disabled_max_ms,enabled_max_ms,"
			+ "disabled_samples_ns,enabled_samples_ns";

	private PerformanceDiagnosticsOverheadTool() {
	}

	/**
	 * Runs the standalone overhead comparison.
	 *
	 * @param args command-line options
	 */
	public static void main(String[] args) {
		int status = run(args, System.out, System.err);
		if (status != 0) {
			System.exit(status);
		}
	}

	/**
	 * Performs a balanced read-only ON/OFF comparison and writes a new CSV.
	 *
	 * @param args   command-line options
	 * @param output standard output destination
	 * @param error  diagnostic error destination
	 * @return zero on success, one for execution failure, two for invalid args
	 */
	public static int run(String[] args, PrintStream output, PrintStream error) {
		Objects.requireNonNull(args, "args");
		Objects.requireNonNull(output, "output");
		Objects.requireNonNull(error, "error");
		if (args.length == 1 && "--help".equals(args[0])) {
			usage(output);
			return 0;
		}
		Arguments options = parse(args);
		if (options == null) {
			usage(error);
			return 2;
		}
		try {
			Path snapshot = options.snapshot();
			Path csv = options.csv();
			if (!Files.isRegularFile(snapshot)) {
				throw new IOException("Read-only snapshot does not exist");
			}
			if (Files.exists(snapshot.resolveSibling(snapshot.getFileName() + "-wal"))
					|| Files.exists(snapshot.resolveSibling(snapshot.getFileName() + "-shm"))) {
				throw new IOException("Snapshot has WAL/SHM sidecars");
			}
			if (Files.exists(csv) || !Files.isDirectory(csv.getParent())) {
				throw new IOException("CSV destination must be a new file in an existing directory");
			}
			if (csv.equals(snapshot) || options.diagnostics().equals(snapshot)) {
				throw new IOException("Output and snapshot must be separate");
			}
			if (Files.exists(options.diagnostics()) && !Files.isDirectory(options.diagnostics())) {
				throw new IOException("Diagnostics destination is not a directory");
			}

			// Resolve the same production workload once, outside any timed phase.
			PerformanceBenchmarkWorkloads workloads = new PerformanceBenchmarkWorkloads(snapshot);
			PerformanceBenchmarkCurriculumScopes scopes = new PerformanceBenchmarkCurriculumScopes(snapshot);
			List<NamedWorkload> cases = List.of(new NamedWorkload("question.findAll", workloads.findAll()),
					new NamedWorkload("dashboard.audit.subject", workloads.dashboardAuditSubject(options.subjectId())),
					new NamedWorkload("search.current.subject",
							workloads.findApplicableToNodes(scopes.subject(options.subjectId()))));
			SqliteDatabase database = SqliteDatabase.readOnly(snapshot);
			int schemaVersion = database.schemaVersion();
			String before = PerformanceBenchmarkCsvReport.sha256(snapshot);
			long bytes = Files.size(snapshot);
			long questionCount = workloads.findAll().execute();
			List<Comparison> results = new ArrayList<>();
			PerformanceRecorder recorded = PerformanceRecorder.forSession(options.diagnostics(), true);
			PerformanceRecorder unrecorded = PerformanceRecorder.forSession(options.diagnostics(), false);
			try (recorded; unrecorded) {
				for (NamedWorkload workload : cases) {
					results.add(compare(workload, unrecorded, recorded, options.warmups(), options.rounds()));
				}
			}
			if (recorded.droppedRecordCount() != 0 || recorded.failedWriteCount() != 0) {
				throw new IOException("Diagnostic queue dropped records or failed to write");
			}
			Path session = options.diagnostics().resolve("sessions").resolve(recorded.sessionId().toString());
			if (!Files.isRegularFile(session.resolve("session.complete"))) {
				throw new IOException("Recorded diagnostic session did not finish cleanly");
			}
			if (!before.equals(PerformanceBenchmarkCsvReport.sha256(snapshot)) || Files.size(snapshot) != bytes) {
				throw new IOException("Frozen benchmark snapshot changed during the comparison");
			}
			if (Files.exists(snapshot.resolveSibling(snapshot.getFileName() + "-wal"))
					|| Files.exists(snapshot.resolveSibling(snapshot.getFileName() + "-shm"))) {
				throw new IOException("Benchmark unexpectedly created SQLite sidecars");
			}
			write(csv, options, before, schemaVersion, questionCount, results);
			output.println("Overhead comparison completed (snapshot SHA-256 " + before + ")");
			for (Comparison result : results) {
				double delta = result.recorded().medianMilliseconds() - result.unrecorded().medianMilliseconds();
				output.printf(Locale.ROOT, "%s: OFF %.3f ms, ON %.3f ms, delta %+.3f ms%n", result.operation(),
						result.unrecorded().medianMilliseconds(), result.recorded().medianMilliseconds(), delta);
			}
			output.println("CSV: " + csv);
			output.println("Diagnostic session: " + recorded.sessionId());
			return 0;
		} catch (Exception failure) {
			error.println("Overhead comparison failed: " + failure.getMessage());
			return 1;
		}
	}

	private static Comparison compare(NamedWorkload workload, PerformanceRecorder unrecorded,
			PerformanceRecorder recorded, int warmups, int rounds) throws Exception {

		// Warm both modes before measuring to avoid making cold start an ON/OFF effect.
		for (int i = 0; i < warmups; i++) {
			execute(workload, unrecorded);
			execute(workload, recorded);
		}
		List<Long> disabledNanos = new ArrayList<>(2 * rounds);
		List<Long> enabledNanos = new ArrayList<>(2 * rounds);
		long expected = -1;
		for (int round = 0; round < rounds; round++) {

			// ABBA with alternating initial mode counters simple time drift.
			boolean startEnabled = round % 2 != 0;
			for (int step = 0; step < 4; step++) {
				boolean enabled = (step == 1 || step == 2) != startEnabled;
				PerformanceRecorder recorder = enabled ? recorded : unrecorded;
				long started = System.nanoTime();
				long result = execute(workload, recorder);
				long elapsed = System.nanoTime() - started;
				if (result < 0 || elapsed < 0 || (expected >= 0 && result != expected)) {
					throw new IllegalStateException("Changing benchmark result or invalid monotonic timing");
				}
				expected = result;
				(enabled ? enabledNanos : disabledNanos).add(elapsed);
			}
		}
		PerformanceBenchmarkResult off = PerformanceBenchmarkResult.fromMeasurements(workload.name() + ".disabled",
				warmups, expected, disabledNanos);
		PerformanceBenchmarkResult on = PerformanceBenchmarkResult.fromMeasurements(workload.name() + ".enabled",
				warmups, expected, enabledNanos);
		return new Comparison(workload.name(), off, on);
	}

	private static long execute(NamedWorkload workload, PerformanceRecorder recorder) throws Exception {
		String name = "overhead." + workload.name();
		try (PerformanceOperation operation = recorder.start(name)) {
			try {
				long count = PerformanceSqlScope.measure(recorder, name, operation.id(), workload.action()::execute);
				operation.resultCount(count);
				return count;
			} catch (Exception | Error failure) {
				operation.failed();
				throw failure;
			}
		}
	}

	private static Arguments parse(String[] args) {
		Path snapshot = null;
		Path csv = null;
		Path diagnostics = null;
		Long subject = null;
		Integer warmups = null;
		Integer rounds = null;
		String label = "unlabelled";
		boolean labelSeen = false;
		try {
			if (args.length % 2 != 0) {
				return null;
			}
			for (int index = 0; index < args.length; index += 2) {
				String option = args[index];
				String value = args[index + 1];
				if (option == null || value == null) {
					return null;
				}
				switch (option) {
				case "--snapshot" -> {
					if (snapshot != null) {
						return null;
					}
					snapshot = Path.of(value).toAbsolutePath().normalize();
					if (!Path.of(value).isAbsolute()) {
						return null;
					}
				}
				case "--csv" -> {
					if (csv != null) {
						return null;
					}
					csv = Path.of(value).toAbsolutePath().normalize();
					if (!Path.of(value).isAbsolute()) {
						return null;
					}
				}
				case "--diagnostics" -> {
					if (diagnostics != null) {
						return null;
					}
					diagnostics = Path.of(value).toAbsolutePath().normalize();
					if (!Path.of(value).isAbsolute()) {
						return null;
					}
				}
				case "--subject-id" -> {
					if (subject != null) {
						return null;
					}
					subject = Long.parseLong(value);
				}
				case "--rounds" -> {
					if (rounds != null) {
						return null;
					}
					rounds = Integer.parseInt(value);
				}
				case "--warmups" -> {
					if (warmups != null) {
						return null;
					}
					warmups = Integer.parseInt(value);
				}
				case "--label" -> {
					if (labelSeen || !value.matches("[A-Za-z0-9._-]{1,80}")) {
						return null;
					}
					labelSeen = true;
					label = value;
				}
				default -> {
					return null;
				}
				}
			}
			if (snapshot == null || csv == null || diagnostics == null || subject == null || subject <= 0
					|| (rounds != null && (rounds < 1 || rounds > 100))
					|| (warmups != null && (warmups < 1 || warmups > 100))) {
				return null;
			}
			return new Arguments(snapshot, csv, diagnostics, subject, label,
					warmups == null ? DEFAULT_WARMUPS : warmups, rounds == null ? DEFAULT_ROUNDS : rounds);
		} catch (IllegalArgumentException invalid) {
			return null;
		}
	}

	private static void usage(PrintStream output) {
		output.println(
				"Usage: PerformanceDiagnosticsOverheadTool " + "--snapshot <absolute-read-only-db> --subject-id <id> "
						+ "--csv <absolute-new-file> --diagnostics <absolute-isolated-directory> "
						+ "[--label <code-label>] [--warmups 2] [--rounds 5]");
	}

	private static void write(Path csv, Arguments options, String fingerprint, int schemaVersion, long questionCount,
			List<Comparison> results) throws IOException {
		StringBuilder text = new StringBuilder(HEADER).append('\n');
		Instant timestamp = Instant.now();
		for (Comparison result : results) {
			PerformanceBenchmarkResult off = result.unrecorded();
			PerformanceBenchmarkResult on = result.recorded();
			double delta = on.medianMilliseconds() - off.medianMilliseconds();
			String percent = off.medianMilliseconds() == 0 ? "n/a"
					: String.format(Locale.ROOT, "%.3f", 100.0 * delta / off.medianMilliseconds());
			text.append(timestamp).append(',').append(options.label()).append(',').append(fingerprint).append(',')
					.append(schemaVersion).append(',').append(questionCount).append(',').append(result.operation())
					.append(',').append(options.warmups()).append(',').append(options.rounds()).append(',')
					.append(2 * options.rounds()).append(',').append(off.resultCount()).append(',')
					.append(String.format(Locale.ROOT, "%.3f,%.3f,%+.3f,", off.medianMilliseconds(),
							on.medianMilliseconds(), delta))
					.append(percent).append(',').append(String.format(Locale.ROOT, "%.3f,%.3f,%.3f,%.3f,",
							off.p90Milliseconds(), on.p90Milliseconds(), off.maxMilliseconds(), on.maxMilliseconds()));
			text.append(String.join(";", off.elapsedNanos().stream().map(String::valueOf).toList())).append(',')
					.append(String.join(";", on.elapsedNanos().stream().map(String::valueOf).toList())).append('\n');
		}
		Files.writeString(csv, text.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
				StandardOpenOption.WRITE);
	}

	private record Arguments(Path snapshot, Path csv, Path diagnostics, long subjectId, String label, int warmups,
			int rounds) {
	}

	private record Comparison(String operation, PerformanceBenchmarkResult unrecorded,
			PerformanceBenchmarkResult recorded) {
	}

	private record NamedWorkload(String name, PerformanceBenchmarkRunner.Workload action) {
	}
}
