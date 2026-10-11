package au.edu.eq.questionbank.admin;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

/**
 * Command-line entry point for repeatable, read-only corpus benchmarks.
 *
 * Requires an explicitly supplied, consistent SQLite snapshot. Benchmark
 * preparation, schema validation and corpus metadata collection take place
 * outside the timed iterations.
 */
public final class PerformanceBenchmarkTool {

	private static final int DEFAULT_ITERATIONS = 5;
	private static final int DEFAULT_WARMUPS = 1;

	private PerformanceBenchmarkTool() {
	}

	/**
	 * Runs the command-line benchmark application.
	 *
	 * @param args benchmark options
	 */
	public static void main(String[] args) {
		int status = run(args, System.out, System.err);
		if (status != 0) {
			System.exit(status);
		}
	}

	public static int run(String[] args, PrintStream output, PrintStream error) {
		Objects.requireNonNull(args, "args");
		Objects.requireNonNull(output, "output");
		Objects.requireNonNull(error, "error");
		if (args.length == 1 && "--help".equals(args[0])) {
			printUsage(output);
			return 0;
		}
		Arguments arguments = parseArguments(args);
		if (arguments == null) {
			printUsage(error);
			return 2;
		}
		try {
			Path snapshot = arguments.snapshot();
			Path csvPath = arguments.csv();
			if (!Files.isRegularFile(snapshot)) {
				throw new IOException("Benchmark snapshot does not exist: " + snapshot);
			}
			Path wal = snapshot.resolveSibling(snapshot.getFileName() + "-wal");
			Path shm = snapshot.resolveSibling(snapshot.getFileName() + "-shm");
			if (Files.exists(wal) || Files.exists(shm)) {
				throw new IOException(
						"Benchmark snapshot has SQLite WAL/SHM sidecars; " + "create a consistent standalone snapshot");
			}
			if (csvPath != null) {

				// Fail before executing expensive benchmarks if their
				// output would overwrite a previous report.
				if (Files.exists(csvPath)) {
					throw new IOException("CSV report already exists: " + csvPath);
				}
				if (!Files.isDirectory(csvPath.getParent())) {
					throw new IOException("CSV output directory does not exist: " + csvPath.getParent());
				}
			}
			SqliteDatabase database = SqliteDatabase.readOnly(snapshot);
			PerformanceBenchmarkWorkloads workloads = new PerformanceBenchmarkWorkloads(snapshot);
			PerformanceBenchmarkCurriculumScopes scopes = new PerformanceBenchmarkCurriculumScopes(snapshot);
			long databaseBytes = Files.size(snapshot);
			long questionCount = countQuestions(database);
			int schemaVersion = database.schemaVersion();
			String sqliteVersion = database.sqliteVersion();
			String javaVersion = System.getProperty("java.version", "unknown");

			// Calculate the snapshot identity outside the timed work.
			String snapshotSha256 = PerformanceBenchmarkCsvReport.sha256(snapshot);
			PerformanceBenchmarkRunner runner = new PerformanceBenchmarkRunner();
			List<BenchmarkRun> completed = new ArrayList<>();
			for (Selection selection : arguments.selections()) {

				// Resolve curriculum scopes before entering the
				// measured workload.
				PerformanceBenchmarkRunner.Workload workload = selectWorkload(selection, workloads, scopes);
				PerformanceBenchmarkResult result = runner.run(selection.operation(), arguments.warmups(),
						arguments.iterations(), workload);
				completed.add(new BenchmarkRun(selection, result));
			}

			// Detect an unexpectedly changing snapshot before producing
			// results that would falsely claim a stable corpus.
			String finalFingerprint = PerformanceBenchmarkCsvReport.sha256(snapshot);
			if (!snapshotSha256.equals(finalFingerprint)) {
				throw new IOException("Benchmark snapshot changed during execution");
			}
			if (Files.size(snapshot) != databaseBytes) {
				throw new IOException("Benchmark database size changed during execution");
			}
			if (csvPath != null) {
				List<PerformanceBenchmarkCsvReport.Measurement> measurements = completed.stream()
						.map(run -> new PerformanceBenchmarkCsvReport.Measurement(run.selection().displayName(),
								run.result()))
						.toList();
				PerformanceBenchmarkCsvReport.Context context = new PerformanceBenchmarkCsvReport.Context(Instant.now(),
						arguments.label(), snapshot, snapshotSha256, databaseBytes, questionCount, schemaVersion,
						sqliteVersion, javaVersion);

				// No CSV is produced until every workload succeeds.
				PerformanceBenchmarkCsvReport.write(csvPath, context, measurements);
			}
			output.println("Exam Question Bank performance benchmark");
			output.println("Snapshot: " + snapshot);
			output.println("Snapshot SHA-256: " + snapshotSha256);
			output.println("Code label: " + arguments.label());
			output.println("Database bytes: " + databaseBytes);
			output.println("Questions in bank: " + questionCount);
			output.println("Schema version: " + schemaVersion);
			output.println("SQLite version: " + sqliteVersion);
			output.println("Java version: " + javaVersion);
			for (BenchmarkRun completedRun : completed) {
				printResult(output, completedRun);
			}
			if (csvPath != null) {
				output.println();
				output.println("CSV report: " + csvPath);
			}
			return 0;
		} catch (Exception failure) {
			error.println("Benchmark failed: " + failure.getMessage());
			return 1;
		}
	}

	private static long countQuestions(SqliteDatabase database) throws SQLException {
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM questions")) {
			if (!result.next()) {
				throw new SQLException("Question count query returned no result");
			}
			return result.getLong(1);
		}
	}

	private static Arguments parseArguments(String[] args) {
		Path snapshot = null;
		Path csv = null;
		String label = null;
		Integer warmups = null;
		Integer iterations = null;
		List<Selection> selections = new ArrayList<>();
		try {
			for (int index = 0; index < args.length; index++) {
				String option = args[index];
				if (option == null || index + 1 >= args.length) {
					return null;
				}
				String value = args[++index];
				if (value == null) {
					return null;
				}
				switch (option) {
				case "--snapshot" -> {
					if (snapshot != null) {
						return null;
					}
					Path supplied = Path.of(value);
					if (!supplied.isAbsolute()) {
						return null;
					}
					snapshot = supplied.normalize();
				}
				case "--csv" -> {
					if (csv != null) {
						return null;
					}
					Path supplied = Path.of(value);
					if (!supplied.isAbsolute()) {
						return null;
					}
					csv = supplied.normalize();
				}
				case "--label" -> {
					if (label != null || !value.matches("[A-Za-z0-9._-]{1,80}")) {
						return null;
					}
					label = value;
				}
				case "--operation" -> {
					Selection selection = parseSelection(value);
					if (selections.contains(selection)) {
						return null;
					}
					selections.add(selection);
				}
				case "--warmups" -> {
					if (warmups != null) {
						return null;
					}
					warmups = positiveInteger(value);
				}
				case "--iterations" -> {
					if (iterations != null) {
						return null;
					}
					iterations = positiveInteger(value);
				}
				default -> {
					return null;
				}
				}
			}
		} catch (IllegalArgumentException failure) {
			return null;
		}
		if (snapshot == null || selections.isEmpty()) {
			return null;
		}

		// The snapshot itself must never become the CSV destination.
		if (csv != null && csv.equals(snapshot)) {
			return null;
		}
		return new Arguments(snapshot, csv, label == null ? "unlabelled" : label, selections,
				warmups == null ? DEFAULT_WARMUPS : warmups, iterations == null ? DEFAULT_ITERATIONS : iterations);
	}

	private static Selection parseSelection(String value) {
		int separator = value.indexOf(':');
		if (separator < 0) {
			if ("question.findAll".equals(value)) {
				return new Selection(value, 0);
			}
			throw new IllegalArgumentException("Unknown benchmark operation: " + value);
		}
		String operation = value.substring(0, separator);
		String identifier = value.substring(separator + 1);
		long targetId = Long.parseLong(identifier);
		if (targetId <= 0) {
			throw new IllegalArgumentException("Benchmark target ID must be positive");
		}
		return switch (operation) {
		case "question.findById", "dashboard.audit.subject", "search.current.subject", "search.current.unit",
				"search.current.topic", "search.current.descriptor" ->
			new Selection(operation, targetId);
		default -> throw new IllegalArgumentException("Unknown benchmark operation: " + operation);
		};
	}

	private static int positiveInteger(String value) {
		int parsed = Integer.parseInt(value);
		if (parsed < 1) {
			throw new IllegalArgumentException("Iteration count must be positive");
		}
		return parsed;
	}

	private static void printResult(PrintStream output, BenchmarkRun completedRun) {
		Selection selection = completedRun.selection();
		PerformanceBenchmarkResult result = completedRun.result();
		output.println();
		output.println("Operation: " + selection.displayName());
		output.println("Warm-ups: " + result.warmupIterations());
		output.println("Measured iterations: " + result.measuredIterations());
		output.println("Result count: " + result.resultCount());
		output.printf(Locale.ROOT, "Min: %.3f ms%n", result.minMilliseconds());
		output.printf(Locale.ROOT, "Median: %.3f ms%n", result.medianMilliseconds());
		output.printf(Locale.ROOT, "P90: %.3f ms%n", result.p90Milliseconds());
		output.printf(Locale.ROOT, "Max: %.3f ms%n", result.maxMilliseconds());
	}

	private static void printUsage(PrintStream output) {
		output.println("""
				Usage:
				  PerformanceBenchmarkTool
				    --snapshot <absolute-sqlite-snapshot-path>
				    --operation <benchmark>
				    [--operation <benchmark>]...
				    [--warmups <count>]
				    [--iterations <count>]
				    [--label <code-version-label>]
				    [--csv <absolute-new-file-path>]

				Benchmarks:
				  dashboard.audit.subject:<subject-id>
				  question.findAll
				  question.findById:<question-id>
				  search.current.subject:<subject-id>
				  search.current.unit:<unit-id>
				  search.current.topic:<topic-id>
				  search.current.descriptor:<descriptor-id>

				Defaults:
				  warmups = 1
				  iterations = 5
				  label = unlabelled

				Label:
				  1-80 letters, digits, dots, underscores or hyphens.
				  Supply the Git commit identifier explicitly.

				CSV:
				  Optional. Destination must be an absolute path.
				  The output directory must already exist.
				  Existing files are never overwritten.
				  One row is written per completed benchmark workload.

				Dashboard audit:
				  Runs production corpus-audit preparation for one Subject.
				  Reports the number of Exams audited.
				  Physical PDF assets are treated as unavailable.
				  This is not an end-to-end JavaFX Dashboard benchmark.

				A separate, consistent SQLite snapshot is required.
				Never benchmark a live database with concurrent writers.
				Use --help to display these instructions.
				""");
	}

	private static PerformanceBenchmarkRunner.Workload selectWorkload(Selection selection,
			PerformanceBenchmarkWorkloads workloads, PerformanceBenchmarkCurriculumScopes scopes) {
		long id = selection.targetId();
		return switch (selection.operation()) {
		case "dashboard.audit.subject" -> workloads.dashboardAuditSubject(id);
		case "question.findAll" -> workloads.findAll();
		case "question.findById" -> workloads.findById(id);
		case "search.current.subject" -> workloads.findApplicableToNodes(scopes.subject(id));
		case "search.current.unit" -> workloads.findApplicableToNodes(scopes.unit(id));
		case "search.current.topic" -> workloads.findApplicableToNodes(scopes.topic(id));
		case "search.current.descriptor" -> workloads.findApplicableToNodes(scopes.descriptor(id));
		default -> throw new IllegalArgumentException("Unsupported benchmark: " + selection.operation());
		};
	}

	private record Arguments(Path snapshot, Path csv, String label, List<Selection> selections, int warmups,
			int iterations) {

		private Arguments {
			selections = List.copyOf(selections);
		}
	}

	private record BenchmarkRun(Selection selection, PerformanceBenchmarkResult result) {
	}

	private record Selection(String operation, long targetId) {

		private String displayName() {
			return targetId == 0 ? operation : operation + ":" + targetId;
		}
	}
}
