package au.edu.eq.questionbank.admin;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.repository.curriculum.CurriculumMappingRepository;
import au.edu.eq.questionbank.repository.curriculum.CurriculumMappingReviewRepository;
import au.edu.eq.questionbank.repository.curriculum.CurriculumRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumMappingRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumMappingReviewRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumRepository;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.repository.sqlite.SqliteReadProbe;
import au.edu.eq.questionbank.service.curriculum.CurriculumMappingCoverage;
import au.edu.eq.questionbank.service.curriculum.CurriculumMappingCoverageService;

/**
 * Read-only diagnostic for the curriculum coverage portion of the Corpus
 * Dashboard snapshot.
 *
 * Measures production repository calls without modifying coverage calculations
 * or persistence.
 */
public final class SqliteDashboardCurriculumInvestigationTool {

	private SqliteDashboardCurriculumInvestigationTool() {
	}

	/**
	 * Runs a diagnostic against a frozen SQLite snapshot.
	 *
	 * @param args --snapshot absolute-path --subject positive-subject-id
	 */
	public static void main(String[] args) {
		int status = run(args, System.out, System.err);
		if (status != 0) {
			System.exit(status);
		}
	}

	static int run(String[] args, PrintStream out, PrintStream err) {
		try {
			Arguments arguments = parseArguments(args);
			investigate(arguments, out);
			return 0;
		} catch (Exception failure) {
			err.println("Curriculum investigation failed: " + failure.getMessage());
			return 2;
		}
	}

	private static <T> T instrument(Class<T> interfaceType, T delegate, String category,
			Map<String, CallStatistics> statistics) {
		Object proxy = Proxy.newProxyInstance(interfaceType.getClassLoader(), new Class<?>[] { interfaceType },
				(_, method, args) -> {
					if (method.getDeclaringClass() == Object.class) {
						return invoke(delegate, method, args);
					}
					long started = System.nanoTime();
					try {
						return invoke(delegate, method, args);
					} finally {
						String operation = category + "." + method.getName();
						statistics.computeIfAbsent(operation, _ -> new CallStatistics())
								.record(System.nanoTime() - started);
					}
				});
		return interfaceType.cast(proxy);
	}

	private static void investigate(Arguments arguments, PrintStream out) throws Exception {
		Path snapshot = arguments.snapshot();
		if (!Files.isRegularFile(snapshot)) {
			throw new IllegalArgumentException("Snapshot does not exist: " + snapshot);
		}
		rejectSidecars(snapshot);
		String beforeHash = sha256(snapshot);
		SqliteDatabase database = SqliteDatabase.readOnly(snapshot);
		database.verifySchema();
		database.verifyIntegrity();
		out.println("SQLite Dashboard curriculum investigation");
		out.println("Snapshot: " + snapshot);
		out.println("Snapshot SHA-256: " + beforeHash);
		out.println("Schema version: " + database.schemaVersion());
		out.println("Subject ID: " + arguments.subjectId());
		out.println();
		SqliteCurriculumRepository curriculum = new SqliteCurriculumRepository(database);
		Subject subject;
		List<SyllabusVersion> versions;
		SqliteReadProbe.Snapshot discovery;
		long discoveryStarted = System.nanoTime();
		try (SqliteReadProbe probe = SqliteReadProbe.begin()) {
			subject = curriculum.findSubjectById(arguments.subjectId()).orElseThrow(
					() -> new IllegalArgumentException("Subject does not exist: " + arguments.subjectId()));
			versions = curriculum.findVersionsForSubject(subject);
			discovery = probe.snapshot();
		}
		printSqlCounts(out, "Subject/version discovery", discovery, System.nanoTime() - discoveryStarted);
		List<SyllabusVersion> currentVersions = versions.stream().filter(SyllabusVersion::isCurrent).toList();
		if (currentVersions.size() > 1) {
			throw new IllegalStateException("Subject has more than one current syllabus");
		}
		if (currentVersions.isEmpty()) {
			out.println("No current syllabus; no mapping coverage required.");
		} else {
			SyllabusVersion target = currentVersions.getFirst();
			out.println("Current syllabus: " + target.getId() + " - " + target.getName());
			int pairCount = 0;
			long totalConnections = discovery.connectionsOpened();
			long totalExecutions = discovery.executions();
			long totalNanos = System.nanoTime() - discoveryStarted;
			for (SyllabusVersion source : versions) {
				if (source.isCurrent()) {
					continue;
				}
				pairCount++;
				Map<String, CallStatistics> calls = new TreeMap<>();
				CurriculumRepository measuredCurriculum = instrument(CurriculumRepository.class,
						new SqliteCurriculumRepository(database), "curriculum", calls);
				CurriculumMappingRepository measuredMappings = instrument(CurriculumMappingRepository.class,
						new SqliteCurriculumMappingRepository(database), "mappings", calls);
				CurriculumMappingReviewRepository measuredReviews = instrument(CurriculumMappingReviewRepository.class,
						new SqliteCurriculumMappingReviewRepository(database), "reviews", calls);
				CurriculumMappingCoverageService service = new CurriculumMappingCoverageService(measuredCurriculum,
						measuredMappings, measuredReviews);
				CurriculumMappingCoverage coverage;
				SqliteReadProbe.Snapshot sqlCounts;
				long started = System.nanoTime();
				try (SqliteReadProbe probe = SqliteReadProbe.begin()) {
					coverage = service.calculateCoverage(source, target);
					sqlCounts = probe.snapshot();
				}
				long elapsed = System.nanoTime() - started;
				totalNanos += elapsed;
				totalConnections += sqlCounts.connectionsOpened();
				totalExecutions += sqlCounts.executions();
				out.println();
				out.println("=== Historical syllabus " + source.getId() + " -> current " + target.getId() + " ===");
				out.println("Coverage status: " + coverage.status());
				out.println("Source descriptors: " + coverage.descriptorCoverage().total());
				out.println("Source subtopics: " + coverage.subtopicCoverage().total());
				printSqlCounts(out, "Coverage calculation", sqlCounts, elapsed);
				out.println("Repository method calls:");
				for (Map.Entry<String, CallStatistics> entry : calls.entrySet()) {
					CallStatistics statistics = entry.getValue();
					out.printf(Locale.ROOT, "  %s: calls=%d elapsed=%.3f ms%n", entry.getKey(), statistics.calls,
							statistics.nanos / 1_000_000.0);
				}
			}
			out.println();
			out.println("=== Subject summary ===");
			out.println("Historical syllabus pairs: " + pairCount);
			out.println("Total connections opened: " + totalConnections);
			out.println("Total SQL executions: " + totalExecutions);
			out.printf(Locale.ROOT, "Total measured diagnostic time: %.3f ms%n", totalNanos / 1_000_000.0);
		}
		String afterHash = sha256(snapshot);
		if (!beforeHash.equals(afterHash)) {
			throw new IllegalStateException("Snapshot changed during investigation");
		}
		out.println();
		out.println("Snapshot unchanged: true");
		out.println("Read-only investigation completed.");
		out.println("Diagnostic timings include instrumentation overhead.");
	}

	private static Object invoke(Object delegate, Method method, Object[] arguments) throws Throwable {
		try {
			return method.invoke(delegate, arguments);
		} catch (InvocationTargetException failure) {
			throw failure.getCause();
		}
	}

	private static Arguments parseArguments(String[] args) {
		Path snapshot = null;
		long subjectId = 0;
		for (int index = 0; index < args.length; index++) {
			String option = args[index];
			if (index + 1 >= args.length) {
				throw new IllegalArgumentException("Missing value for " + option);
			}
			String value = args[++index];
			switch (option) {
			case "--snapshot":
				if (snapshot != null) {
					throw new IllegalArgumentException("Duplicate --snapshot");
				}
				Path supplied = Path.of(value);
				if (!supplied.isAbsolute()) {
					throw new IllegalArgumentException("Snapshot path must be absolute");
				}
				snapshot = supplied.toAbsolutePath().normalize();
				break;
			case "--subject":
				if (subjectId != 0) {
					throw new IllegalArgumentException("Duplicate --subject");
				}
				subjectId = Long.parseLong(value);
				if (subjectId <= 0) {
					throw new IllegalArgumentException("Subject ID must be positive");
				}
				break;
			default:
				throw new IllegalArgumentException("Unknown argument: " + option);
			}
		}
		if (snapshot == null || subjectId <= 0) {
			throw new IllegalArgumentException("Required: --snapshot ABSOLUTE_PATH --subject ID");
		}
		return new Arguments(snapshot, subjectId);
	}

	private static void printSqlCounts(PrintStream out, String label, SqliteReadProbe.Snapshot counts,
			long elapsedNanos) {
		out.println(label + ":");
		out.println("  Connections opened: " + counts.connectionsOpened());
		out.println("  SQL executions: " + counts.executions());
		out.printf(Locale.ROOT, "  Elapsed: %.3f ms%n", elapsedNanos / 1_000_000.0);
	}

	private static void rejectSidecars(Path snapshot) {
		String filename = snapshot.getFileName().toString();
		for (String suffix : List.of("-wal", "-shm")) {
			Path sidecar = snapshot.resolveSibling(filename + suffix);
			if (Files.exists(sidecar)) {
				throw new IllegalArgumentException("Snapshot has SQLite sidecar: " + sidecar);
			}
		}
	}

	private static String sha256(Path path) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		try (InputStream input = Files.newInputStream(path)) {
			byte[] buffer = new byte[65536];
			int count;
			while ((count = input.read(buffer)) != -1) {
				digest.update(buffer, 0, count);
			}
		} catch (IOException failure) {
			throw new IOException("Could not read snapshot: " + path, failure);
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	private record Arguments(Path snapshot, long subjectId) {
	}

	private static final class CallStatistics {

		private long calls;
		private long nanos;

		private void record(long elapsedNanos) {
			calls++;
			nanos += elapsedNanos;
		}
	}
}
