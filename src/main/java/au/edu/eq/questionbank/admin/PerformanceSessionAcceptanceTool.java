package au.edu.eq.questionbank.admin;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import au.edu.eq.questionbank.diagnostics.PerformanceCsvReader;
import au.edu.eq.questionbank.diagnostics.PerformanceCsvReader.Entry;
import au.edu.eq.questionbank.diagnostics.PerformanceHistoryIntegrity;

/**
 * Read-only final acceptance audit for one completed diagnostic session.
 *
 * This checks the recorded evidence, not the functional correctness of the
 * capture workflow or the numerical cost of enabling diagnostics.
 */
public final class PerformanceSessionAcceptanceTool {

	private static final String CHECKPOINT_HEADER = "requested_at,captured_at,machine_id,session_id,"
			+ "checkpoint_index,reason,successful_question_saves,schema_version,question_count,answer_count,"
			+ "question_content_parts,shared_context_content_parts,database_bytes";
	private static final int REQUIRED_SAVES = 8;
	private static final List<String> REQUIRED_WORKFLOWS = List.of("application.startup", "dashboard.refresh",
			"search.refresh", "search.preview.load", "search.preview.render", "examAssets.open.selected",
			"capture.answer.save", "runtime.heap.used-kib");

	private PerformanceSessionAcceptanceTool() {
	}

	/**
	 * Reads the diagnostics root and session UUID from command-line arguments.
	 *
	 * @param args exactly --diagnostics-root ABSOLUTE_PATH --session UUID, in
	 *             either order
	 */
	public static void main(String[] args) {
		int result = run(args, System.out, System.err);
		if (result != 0) {
			System.exit(result);
		}
	}

	/**
	 * Audits a completed real session without modifying any source files.
	 *
	 * @param args   command-line arguments
	 * @param output normal report stream
	 * @param error  validation/error stream
	 * @return 0 for acceptance, 1 for failed/incomplete evidence, 2 for invalid
	 *         arguments
	 */
	public static int run(String[] args, PrintStream output, PrintStream error) {
		Objects.requireNonNull(args, "args");
		Objects.requireNonNull(output, "output");
		Objects.requireNonNull(error, "error");
		if (args.length == 1 && "--help".equals(args[0])) {
			usage(output);
			return 0;
		}
		Arguments arguments = parseArguments(args);
		if (arguments == null) {
			usage(error);
			return 2;
		}
		try {
			Audit audit = inspect(arguments.root(), arguments.sessionId());
			output.println("Performance session acceptance audit");
			output.println("Session: " + arguments.sessionId());
			output.println("Measurements: " + audit.measurements());
			output.println("Successful Question saves: " + audit.questionSaves());
			output.println("Checkpoint-recorded saves: " + audit.checkpointSaves());
			output.println("Question count change: " + audit.questionGrowth());
			output.println("Operation failures: " + audit.operationFailures());
			output.println("JavaFX long pulse events: " + audit.pulseEvents()
					+ " (zero means no threshold crossing was recorded)");
			if (audit.problems().isEmpty()) {
				output.println("PASS: Complete session evidence meets the Batch 16 checks");
				return 0;
			}
			for (String problem : audit.problems()) {
				output.println("FAIL: " + problem);
			}
			return 1;
		} catch (IOException | RuntimeException invalid) {

			// Do not print filesystem paths, record IDs or supplied content.
			error.println("FAIL: Session evidence is missing, unsafe or malformed ("
					+ invalid.getClass().getSimpleName() + ")");
			return 1;
		}
	}

	private static void checkSqlPhase(String prefix, long saveId, Map<Long, Entry> byId,
			Map<String, List<Entry>> byName, List<String> problems) {
		String scopeName = prefix + ".sql.scope";
		boolean matched = false;
		for (Entry scope : byName.getOrDefault(scopeName, List.of())) {
			if (!descendsFrom(scope, saveId, byId)) {
				continue;
			}
			matched = true;
			Entry executions = byName.getOrDefault(prefix + ".sql.execute-invocations", List.of()).stream()
					.filter(metric -> metric.parentId() == scope.operationId()).findFirst().orElse(null);
			Entry connections = byName.getOrDefault(prefix + ".sql.connections", List.of()).stream()
					.filter(metric -> metric.parentId() == scope.operationId()).findFirst().orElse(null);
			if (!scope.success() || executions == null || connections == null || !executions.success()
					|| !connections.success() || executions.resultCount() < 0 || connections.resultCount() < 0) {
				problems.add("Incomplete SQL counters for " + prefix);
			}
			break;
		}
		if (!matched) {
			problems.add("Missing SQL scope for " + prefix + " under a successful Question save");
		}
	}

	private static boolean descendsFrom(Entry child, long ancestorId, Map<Long, Entry> byId) {
		Set<Long> visited = new HashSet<>();
		long parentId = child.parentId();
		while (parentId != 0 && visited.add(parentId)) {
			if (parentId == ancestorId) {
				return true;
			}
			Entry parent = byId.get(parentId);
			if (parent == null) {
				return false;
			}
			parentId = parent.parentId();
		}
		return false;
	}

	private static Audit inspect(Path root, UUID sessionId) throws IOException {
		List<String> problems = new ArrayList<>();
		Path session = root.resolve("sessions").resolve(sessionId.toString());
		if (Files.isSymbolicLink(root) || Files.isSymbolicLink(session)
				|| !Files.isDirectory(session, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Unsafe or absent session directory");
		}
		if (!Files.isRegularFile(session.resolve("session.complete"), LinkOption.NOFOLLOW_LINKS)
				|| Files.exists(session.resolve("session.partial"), LinkOption.NOFOLLOW_LINKS)) {
			problems.add("Session has not completed cleanly");
		}
		Properties completion = properties(session.resolve("session.complete"));
		if (!"0".equals(completion.getProperty("droppedRecords"))
				|| !"0".equals(completion.getProperty("failedWrites"))) {
			problems.add("Dropped measurements or failed diagnostic writes");
		}
		List<Path> segments = PerformanceCsvReader.sessionOperationFiles(session);
		if (!Integer.toString(segments.size()).equals(completion.getProperty("segments"))) {
			problems.add("Completion marker and segment count disagree");
		}
		Properties metadata = properties(session.resolve("session.properties"));
		if (!sessionId.toString().equals(metadata.getProperty("session.id"))) {
			problems.add("Session metadata identity mismatch");
		}
		String runtime = metadata.getProperty("runtime.kind", "unknown");
		if (!List.of("eclipse", "installed").contains(runtime)) {
			problems.add("Runtime must be identified as eclipse or installed");
		}
		if (metadata.getProperty("application.version", "").isBlank()) {
			problems.add("Missing application version");
		}
		if (metadata.getProperty("session.startedAt", "").isBlank()) {
			problems.add("Missing session start timestamp");
		}
		if (metadata.getProperty("java.version", "").isBlank() || metadata.getProperty("os.name", "").isBlank()) {
			problems.add("Missing Java or operating-system metadata");
		}
		List<Entry> entries = PerformanceCsvReader.readSession(session);
		if (entries.isEmpty()) {
			problems.add("Session contains no measurements");
		}
		Map<Long, Entry> byId = new HashMap<>();
		Map<String, List<Entry>> byName = new HashMap<>();
		long failures = 0;
		for (Entry entry : entries) {
			if (entry.operationId() <= 0 || byId.putIfAbsent(entry.operationId(), entry) != null) {
				problems.add("Duplicate or invalid operation ID");
			}
			if (!entry.operation().matches("[A-Za-z0-9._-]+")) {
				problems.add("Invalid operation name");
			}
			byName.computeIfAbsent(entry.operation(), _ -> new ArrayList<>()).add(entry);
			if (!entry.success()) {
				failures++;
			}
		}
		for (Entry entry : entries) {
			if (entry.parentId() < 0 || entry.parentId() >= entry.operationId()
					|| (entry.parentId() != 0 && !byId.containsKey(entry.parentId()))) {
				problems.add("Missing, future or invalid operation parent");
			}
		}
		for (String required : REQUIRED_WORKFLOWS) {
			if (!byName.containsKey(required)) {
				problems.add("Missing exercised workflow: " + required);
			}
		}
		for (String prefix : List.of("dashboard.snapshot", "examAssets.open.selected.load")) {
			boolean correlated = byName.getOrDefault(prefix + ".sql.scope", List.of()).stream()
					.anyMatch(scope -> scope.success() && byId.containsKey(scope.parentId())
							&& prefix.equals(byId.get(scope.parentId()).operation()));
			if (!correlated) {
				problems.add("Missing workflow SQL correlation: " + prefix);
			}
		}
		if (!byName.containsKey("runtime.gc.collection-time-ms-delta")) {
			problems.add("Missing periodic GC sampling (allow at least one minute of runtime)");
		}
		List<Entry> saved = byName.getOrDefault("capture.question.save", List.of()).stream().filter(Entry::success)
				.toList();
		if (saved.size() < REQUIRED_SAVES) {
			problems.add("Fewer than eight successful Question Save spans");
		}
		for (Entry save : saved) {
			checkSqlPhase("capture.question.save.validate", save.operationId(), byId, byName, problems);
			checkSqlPhase("capture.question.save.persist", save.operationId(), byId, byName, problems);
			checkSqlPhase("capture.question.save.refresh", save.operationId(), byId, byName, problems);
		}
		Checkpoints checkpoints = readCheckpoints(session.resolve("corpus-checkpoints.csv"), sessionId);
		if (!checkpoints.hasSaveEight()) {
			problems.add("No intermediate question-save-8 checkpoint");
		}
		if (checkpoints.saves() < REQUIRED_SAVES) {
			problems.add("Shutdown checkpoint records fewer than eight saves");
		}
		if (checkpoints.questionsAtEnd() - checkpoints.questionsAtStart() < REQUIRED_SAVES) {
			problems.add("Scripted session must grow the corpus by at least eight new Questions");
		}
		try {
			new PerformanceHistoryIntegrity().verifySession(root, sessionId);
		} catch (IOException invalidHistory) {
			problems.add("Permanent history is missing or fails integrity verification");
		}
		return new Audit(entries.size(), saved.size(), checkpoints.saves(),
				checkpoints.questionsAtEnd() - checkpoints.questionsAtStart(), failures,
				byName.getOrDefault("runtime.fx.pulse-gap-us", List.of()).size(), List.copyOf(problems));
	}

	private static Arguments parseArguments(String[] args) {
		if (args.length != 4) {
			return null;
		}
		Path root = null;
		UUID id = null;
		try {
			for (int index = 0; index < args.length; index += 2) {
				switch (args[index]) {
				case "--diagnostics-root" -> {
					if (root != null) {
						return null;
					}
					root = Path.of(args[index + 1]);
					if (!root.isAbsolute()) {
						return null;
					}
					root = root.normalize();
				}
				case "--session" -> {
					if (id != null) {
						return null;
					}
					id = UUID.fromString(args[index + 1]);
					if (!id.toString().equals(args[index + 1])) {
						return null;
					}
				}
				default -> {
					return null;
				}
				}
			}
		} catch (IllegalArgumentException | NullPointerException invalid) {
			return null;
		}
		return root == null || id == null ? null : new Arguments(root, id);
	}

	private static Properties properties(Path file) throws IOException {
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Missing properties file");
		}
		Properties result = new Properties();
		try (InputStream stream = Files.newInputStream(file)) {
			result.load(stream);
		}
		return result;
	}

	private static Checkpoints readCheckpoints(Path file, UUID id) throws IOException {
		List<String> rows = Files.readAllLines(file, StandardCharsets.UTF_8);
		if (rows.size() < 3 || !CHECKPOINT_HEADER.equals(rows.getFirst())) {
			throw new IOException("Missing or invalid checkpoints");
		}
		boolean hasEight = false;
		long lastSaves = -1;
		long firstCount = -1;
		long lastCount = -1;
		Instant lastTime = null;
		UUID machineId = null;
		for (int index = 1; index < rows.size(); index++) {
			String[] cells = rows.get(index).split(",", -1);
			if (cells.length != 13) {
				throw new IOException("Invalid checkpoint field count");
			}
			try {
				Instant captured = Instant.parse(cells[1]);
				UUID machine = UUID.fromString(cells[2]);
				UUID recordedId = UUID.fromString(cells[3]);
				int checkpointIndex = Integer.parseInt(cells[4]);
				long saves = Long.parseLong(cells[6]);
				long count = Long.parseLong(cells[8]);
				if (!id.equals(recordedId) || checkpointIndex != index - 1
						|| (machineId != null && !machineId.equals(machine))
						|| (lastTime != null && captured.isBefore(lastTime)) || saves < lastSaves || count < 0) {
					throw new IOException("Checkpoint identity, order or count invalid");
				}
				if (index == 1 && (!"startup".equals(cells[5]) || saves != 0)) {
					throw new IOException("Missing startup checkpoint");
				}
				if (index == rows.size() - 1 && !"shutdown".equals(cells[5])) {
					throw new IOException("Missing shutdown checkpoint");
				}
				if ("question-save-8".equals(cells[5]) && saves >= REQUIRED_SAVES) {
					hasEight = true;
				}
				if (index == 1) {
					firstCount = count;
				}
				lastCount = count;
				lastSaves = saves;
				lastTime = captured;
				machineId = machine;
			} catch (RuntimeException invalid) {
				throw new IOException("Invalid checkpoint values", invalid);
			}
		}
		return new Checkpoints(lastSaves, firstCount, lastCount, hasEight);
	}

	private static void usage(PrintStream output) {
		output.println("Usage: PerformanceSessionAcceptanceTool "
				+ "--diagnostics-root <absolute-directory> --session <UUID>");
	}

	private record Arguments(Path root, UUID sessionId) {
	}

	private record Audit(int measurements, int questionSaves, long checkpointSaves, long questionGrowth,
			long operationFailures, int pulseEvents, List<String> problems) {
	}

	private record Checkpoints(long saves, long questionsAtStart, long questionsAtEnd, boolean hasSaveEight) {
	}
}
