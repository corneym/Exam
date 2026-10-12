package au.edu.eq.questionbank.admin;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import au.edu.eq.questionbank.repository.sqlite.SqliteReadProbe;

/**
 * Runs a production retrieval workload against an immutable SQLite snapshot and
 * reports JDBC activity and complete Question reconstructions.
 */
public final class SqliteQuestionInvestigationTool {

	private SqliteQuestionInvestigationTool() {
	}

	/**
	 * Runs the SQLite Question investigation tool from the command line.
	 *
	 * @param args command-line arguments specifying the database snapshot and
	 *             investigation operation
	 */
	public static void main(String[] args) {
		int status = run(args, System.out, System.err);
		if (status != 0) {
			System.exit(status);
		}
	}

	/**
	 * Executes one read-only diagnostic operation.
	 *
	 * @param args   command-line arguments
	 * @param output normal output
	 * @param error  error output
	 * @return zero for success, one for execution failure, two for invalid input
	 */
	public static int run(String[] args, PrintStream output, PrintStream error) {
		if (args.length == 1 && "--help".equals(args[0])) {
			printUsage(output);
			return 0;
		}
		if (args.length != 4 || !"--snapshot".equals(args[0]) || !"--operation".equals(args[2])) {
			printUsage(error);
			return 2;
		}
		try {
			Path snapshot = Path.of(args[1]);
			if (!snapshot.isAbsolute()) {
				throw new IllegalArgumentException("Snapshot path must be absolute");
			}
			snapshot = snapshot.normalize();
			if (!Files.isRegularFile(snapshot)) {
				throw new IllegalStateException("Snapshot does not exist: " + snapshot);
			}
			requireStandaloneSnapshot(snapshot);
			long originalSize = Files.size(snapshot);
			String fingerprint = PerformanceBenchmarkCsvReport.sha256(snapshot);

			// Prepare repositories and resolve curriculum scopes before
			// the diagnostic scope begins.
			PerformanceBenchmarkWorkloads workloads = new PerformanceBenchmarkWorkloads(snapshot);
			PerformanceBenchmarkCurriculumScopes scopes = new PerformanceBenchmarkCurriculumScopes(snapshot);
			String operation = args[3];
			PerformanceBenchmarkRunner.Workload workload = selectWorkload(operation, workloads, scopes);
			long resultCount;
			SqliteReadProbe.Snapshot measurements;
			try (SqliteReadProbe probe = SqliteReadProbe.begin()) {
				resultCount = workload.execute();
				measurements = probe.snapshot();
			}
			if (resultCount < 0) {
				throw new IllegalStateException("Negative workload result count");
			}

			// The investigation must never alter its input database.
			if (Files.size(snapshot) != originalSize
					|| !fingerprint.equals(PerformanceBenchmarkCsvReport.sha256(snapshot))) {
				throw new IllegalStateException("Snapshot changed during investigation");
			}
			requireStandaloneSnapshot(snapshot);
			printReport(output, snapshot, fingerprint, operation, resultCount, measurements);
			return 0;
		} catch (IllegalArgumentException failure) {
			error.println("Invalid arguments: " + failure.getMessage());
			return 2;
		} catch (Exception failure) {
			error.println("Investigation failed: " + failure.getMessage());
			return 1;
		}
	}

	private static void printMilliseconds(PrintStream output, String label, long nanoseconds) {
		output.printf(Locale.ROOT, "%s: %.3f ms%n", label, nanoseconds / 1_000_000.0);
	}

	private static void printReport(PrintStream output, Path snapshot, String fingerprint, String operation,
			long resultCount, SqliteReadProbe.Snapshot measurements) {
		output.println("SQLite Question investigation");
		output.println("Snapshot: " + snapshot);
		output.println("Snapshot SHA-256: " + fingerprint);
		output.println("Operation: " + operation);
		output.println("Result count: " + resultCount);
		output.println();
		output.println("Connections opened: " + measurements.connectionsOpened());
		output.println("SQL executions: " + measurements.executions());
		output.println("Rows read: " + measurements.rowsRead());
		output.println("Questions reconstructed: " + measurements.questionsReconstructed());
		output.println();
		printMilliseconds(output, "Total elapsed", measurements.elapsedNanos());
		printMilliseconds(output, "Connection setup", measurements.connectionOpenNanos());
		printMilliseconds(output, "JDBC execution", measurements.executionNanos());
		printMilliseconds(output, "ResultSet.next()", measurements.rowNextNanos());
		output.println();
		output.println("Diagnostic times include profiling overhead.");
		output.println("Use PerformanceBenchmarkTool for before/after comparisons.");
	}

	private static void printUsage(PrintStream output) {
		output.println("Usage: --snapshot <absolute-path> " + "--operation <selector>");
		output.println("Supported selectors:");
		output.println("  question.findAll");
		output.println("  question.findById:<id>");
		output.println("  search.current.subject:<id>");
		output.println("  search.current.unit:<id>");
		output.println("  search.current.topic:<id>");
		output.println("  search.current.descriptor:<id>");
		output.println("  dashboard.audit.subject:<id>");
	}

	private static void requireStandaloneSnapshot(Path snapshot) {
		Path wal = snapshot.resolveSibling(snapshot.getFileName() + "-wal");
		Path shm = snapshot.resolveSibling(snapshot.getFileName() + "-shm");
		if (Files.exists(wal) || Files.exists(shm)) {
			throw new IllegalStateException("Snapshot has WAL/SHM sidecars");
		}
	}

	private static PerformanceBenchmarkRunner.Workload selectWorkload(String selector,
			PerformanceBenchmarkWorkloads workloads, PerformanceBenchmarkCurriculumScopes scopes) {
		if ("question.findAll".equals(selector)) {
			return workloads.findAll();
		}
		String[] parts = selector.split(":", -1);
		if (parts.length != 2 || parts[1].isBlank()) {
			throw new IllegalArgumentException("Unsupported operation: " + selector);
		}
		long id;
		try {
			id = Long.parseLong(parts[1]);
		} catch (NumberFormatException failure) {
			throw new IllegalArgumentException("Invalid identifier: " + parts[1], failure);
		}
		if (id <= 0) {
			throw new IllegalArgumentException("Identifier must be positive");
		}
		return switch (parts[0]) {
		case "question.findById" -> workloads.findById(id);
		case "search.current.subject" -> workloads.findApplicableToNodes(scopes.subject(id));
		case "search.current.unit" -> workloads.findApplicableToNodes(scopes.unit(id));
		case "search.current.topic" -> workloads.findApplicableToNodes(scopes.topic(id));
		case "search.current.descriptor" -> workloads.findApplicableToNodes(scopes.descriptor(id));
		case "dashboard.audit.subject" -> workloads.dashboardAuditSubject(id);
		default -> throw new IllegalArgumentException("Unsupported operation: " + selector);
		};
	}
}
