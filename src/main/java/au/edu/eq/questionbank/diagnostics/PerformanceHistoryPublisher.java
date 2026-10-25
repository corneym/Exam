package au.edu.eq.questionbank.diagnostics;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Publishes immutable historical summaries after a completely recorded
 * application session.
 *
 * Raw diagnostic files are never deleted or modified.
 */
public final class PerformanceHistoryPublisher {

	private static final String CHECKPOINT_HEADER = "requested_at,captured_at,machine_id,session_id,"
			+ "checkpoint_index,reason,successful_question_saves," + "schema_version,question_count,answer_count,"
			+ "question_content_parts,shared_context_content_parts," + "database_bytes";

	/**
	 * Creates a publisher for completed performance diagnostic sessions.
	 */
	public PerformanceHistoryPublisher() {
	}

	private static List<String> csvCells(String row) throws IOException {
		List<String> cells = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		boolean quoted = false;
		for (int index = 0; index < row.length(); index++) {
			char character = row.charAt(index);
			if (character == '"') {
				if (quoted && index + 1 < row.length() && row.charAt(index + 1) == '"') {
					current.append('"');
					index++;
				} else {
					quoted = !quoted;
				}
			} else if (character == ',' && !quoted) {
				cells.add(current.toString());
				current.setLength(0);
			} else {
				current.append(character);
			}
		}
		if (quoted) {
			throw new IOException("Unterminated historical CSV field");
		}
		cells.add(current.toString());
		return cells;
	}

	private static String operationFingerprint(List<Path> operationFiles) throws IOException {
		if (operationFiles.isEmpty()) {
			throw new IOException("No operation files to fingerprint");
		}

		// Preserve exact compatibility with previously published
		// single-file session receipts.
		if (operationFiles.size() == 1) {
			return sha256(operationFiles.getFirst());
		}
		StringBuilder manifest = new StringBuilder();
		for (Path file : operationFiles) {
			manifest.append(file.getFileName()).append('=').append(sha256(file)).append('\n');
		}
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] fingerprint = digest.digest(manifest.toString().getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(fingerprint);
		} catch (NoSuchAlgorithmException unavailable) {
			throw new IllegalStateException("SHA-256 unavailable", unavailable);
		}
	}

	private static void publishIdenticalOrNew(Path source, Path destination) throws IOException {
		Files.createDirectories(destination.getParent());
		if (!Files.exists(destination)) {
			try {
				Files.copy(source, destination);
			} catch (java.nio.file.FileAlreadyExistsException concurrent) {

				// Another publication may have won the race.
				// Its contents must still agree with ours.
			}
		}
		if (!Files.isRegularFile(destination) || Files.mismatch(source, destination) != -1) {
			throw new IOException("Conflicting or incomplete history file: " + destination.getFileName());
		}
	}

	private static List<CorpusCheckpoint> readCheckpoints(Path file, UUID sessionId) throws IOException {
		List<String> rows = Files.readAllLines(file, StandardCharsets.UTF_8);
		if (rows.isEmpty() || !CHECKPOINT_HEADER.equals(rows.getFirst())) {
			throw new IOException("Unsupported corpus checkpoint format");
		}
		List<CorpusCheckpoint> checkpoints = new ArrayList<>();
		UUID machineId = null;
		Instant previous = null;
		for (int line = 1; line < rows.size(); line++) {
			if (rows.get(line).isBlank()) {
				throw new IOException("Empty checkpoint row");
			}
			String[] fields = rows.get(line).split(",", -1);
			if (fields.length != 13) {
				throw new IOException("Malformed checkpoint row " + (line + 1));
			}
			try {
				Instant captured = Instant.parse(fields[1]);
				UUID machine = UUID.fromString(fields[2]);
				UUID rowSession = UUID.fromString(fields[3]);
				int index = Integer.parseInt(fields[4]);
				int schema = Integer.parseInt(fields[7]);
				long questions = Long.parseLong(fields[8]);
				long bytes = Long.parseLong(fields[12]);
				if (!sessionId.equals(rowSession) || (machineId != null && !machineId.equals(machine))
						|| index != checkpoints.size() || schema < 1 || questions < 0 || bytes < 0
						|| (previous != null && captured.isBefore(previous))) {
					throw new IllegalArgumentException("Checkpoint identity or order mismatch");
				}
				machineId = machine;
				previous = captured;
				checkpoints.add(new CorpusCheckpoint(machine, index, captured, fields[5], schema, questions, bytes));
			} catch (RuntimeException invalid) {
				throw new IOException("Invalid checkpoint row " + (line + 1), invalid);
			}
		}
		return List.copyOf(checkpoints);
	}

	private static Properties readProperties(Path file) throws IOException {
		Properties result = new Properties();
		try (InputStream input = Files.newInputStream(file)) {
			result.load(input);
		}
		return result;
	}

	private static String resolveOriginalRuntimeKind(Path diagnosticsRoot, UUID machineId, UUID sessionId,
			String savedRuntimeKind, String fallbackRuntimeKind) throws IOException {
		if (savedRuntimeKind != null && !savedRuntimeKind.isBlank()) {
			return savedRuntimeKind;
		}
		if (!"unknown".equals(fallbackRuntimeKind)) {
			return fallbackRuntimeKind;
		}

		// Older completed sessions did not have runtime.kind in
		// session.properties. Use their published runtime label
		// solely to reconstruct and verify existing summaries.
		Path published = diagnosticsRoot.resolve("history").resolve(machineId.toString()).resolve(sessionId.toString());
		if (!Files.isRegularFile(published.resolve("session.summary-complete"))) {
			return fallbackRuntimeKind;
		}
		List<Path> summaries;
		try (Stream<Path> files = Files.list(published)) {
			summaries = files.filter(Files::isRegularFile)
					.filter(path -> path.getFileName().toString().matches("checkpoint-[0-9]{6}\\.csv")).sorted()
					.toList();
		}
		if (summaries.isEmpty()) {
			return fallbackRuntimeKind;
		}
		try (BufferedReader reader = Files.newBufferedReader(summaries.getFirst(), StandardCharsets.UTF_8)) {
			String header = reader.readLine();
			String firstRow = reader.readLine();
			if (header == null || !header.startsWith("checkpoint_at,machine_id,") || firstRow == null) {
				throw new IOException("Invalid previously published summary");
			}
			List<String> cells = csvCells(firstRow);
			if (cells.size() != 15 && cells.size() != 19) {
				throw new IOException("Invalid previously published summary columns");
			}
			String originalRuntime = cells.get(5);
			if (!List.of("eclipse", "installed", "unknown").contains(originalRuntime)) {
				throw new IOException("Invalid previously published runtime");
			}
			return originalRuntime;
		}
	}

	private static String sha256(Path file) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			try (InputStream input = new DigestInputStream(Files.newInputStream(file), digest)) {
				input.transferTo(java.io.OutputStream.nullOutputStream());
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 unavailable", impossible);
		}
	}

	/**
	 * Archives one completed session, preserving its machine identity and
	 * associating operation timings with observed corpus sizes.
	 *
	 * @param diagnosticsRoot diagnostics directory
	 * @param sessionId       session to archive
	 * @param runtimeKind     verified runtime label, or "unknown"
	 * @return true when publication is complete
	 * @throws IOException if input is malformed or publication fails
	 */
	public boolean publishSession(Path diagnosticsRoot, UUID sessionId, String runtimeKind) throws IOException {
		Objects.requireNonNull(diagnosticsRoot, "diagnosticsRoot");
		Objects.requireNonNull(sessionId, "sessionId");
		if (runtimeKind == null || runtimeKind.isBlank()) {
			throw new IllegalArgumentException("Missing runtimeKind");
		}
		Path session = diagnosticsRoot.resolve("sessions").resolve(sessionId.toString());
		Path completed = session.resolve("session.complete");
		if (!Files.isRegularFile(completed) || Files.exists(session.resolve("session.partial"))) {
			return false;
		}
		Properties completion = readProperties(completed);
		if (!"0".equals(completion.getProperty("droppedRecords"))
				|| !"0".equals(completion.getProperty("failedWrites"))) {
			return false;
		}
		Path operationsFile = session.resolve("operations.csv");
		Path checkpointsFile = session.resolve("corpus-checkpoints.csv");
		Path metadataFile = session.resolve("session.properties");
		if (!Files.isRegularFile(operationsFile) || !Files.isRegularFile(checkpointsFile)
				|| !Files.isRegularFile(metadataFile)) {
			return false;
		}
		List<Path> operationFiles = PerformanceCsvReader.sessionOperationFiles(session);

		// Older sessions did not store the segment count. They are
		// valid only when they contain the original single CSV file.
		String segmentsProperty = completion.getProperty("segments");
		if (segmentsProperty == null) {
			if (operationFiles.size() != 1) {
				return false;
			}
		} else {
			int expectedSegments;
			try {
				expectedSegments = Integer.parseInt(segmentsProperty);
			} catch (NumberFormatException invalid) {
				throw new IOException("Invalid completed-session segment count", invalid);
			}
			if (expectedSegments < 1 || expectedSegments != operationFiles.size()) {
				return false;
			}
		}
		Properties metadata = readProperties(metadataFile);
		if (!sessionId.toString().equals(metadata.getProperty("session.id"))) {
			throw new IOException("Session metadata identity mismatch");
		}
		String version = metadata.getProperty("application.version", "unknown");
		List<CorpusCheckpoint> checkpoints = readCheckpoints(checkpointsFile, sessionId);
		if (checkpoints.isEmpty() || !"startup".equals(checkpoints.getFirst().reason())
				|| !"shutdown".equals(checkpoints.getLast().reason())) {
			return false;
		}
		UUID machineId = checkpoints.getFirst().machineId();

		// Use the original runtime identity, including when recovering
		// historical sessions created before runtime.kind was persisted.
		String originalRuntimeKind = resolveOriginalRuntimeKind(diagnosticsRoot, machineId, sessionId,
				metadata.getProperty("runtime.kind"), runtimeKind);
		List<PerformanceCsvReader.Entry> measurements = PerformanceCsvReader.readSession(session);
		List<List<PerformanceCsvReader.Entry>> windows = new ArrayList<>();
		for (int i = 0; i < checkpoints.size(); i++) {
			windows.add(new ArrayList<>());
		}
		for (PerformanceCsvReader.Entry entry : measurements) {
			if (!sessionId.equals(entry.sessionId())) {
				throw new IOException("Operation belongs to another session");
			}
			int window = 0;

			// Attribute completed operations to the most recent
			// observed corpus checkpoint.
			while (window + 1 < checkpoints.size()
					&& !entry.timestamp().isBefore(checkpoints.get(window + 1).capturedAt())) {
				window++;
			}
			windows.get(window).add(entry);
		}
		Path staging = Files.createTempDirectory(diagnosticsRoot, ".history-stage-");
		try {
			Path destination = diagnosticsRoot.resolve("history").resolve(machineId.toString())
					.resolve(sessionId.toString());
			PerformanceHistoryStore store = new PerformanceHistoryStore();

			// Existing checkpoint summaries are immutable. Recovery must reproduce their
			// original schema byte-for-byte, including partially published sessions.
			Boolean existingLegacy = null;
			if (Files.isDirectory(destination)) {
				try (Stream<Path> files = Files.list(destination)) {
					for (Path file : files.filter(Files::isRegularFile)
							.filter(path -> path.getFileName().toString().matches("checkpoint-[0-9]{6}\\.csv"))
							.toList()) {
						boolean legacy = PerformanceHistoryStore.isLegacySummary(file);
						if (existingLegacy != null && existingLegacy.booleanValue() != legacy) {
							throw new IOException("Mixed checkpoint formats in one historical session");
						}
						existingLegacy = legacy;
					}
				}
			}
			boolean typedSql = existingLegacy == null || !existingLegacy.booleanValue();
			int summaries = 0;
			for (int i = 0; i < checkpoints.size(); i++) {
				if (windows.get(i).isEmpty()) {
					continue;
				}
				CorpusCheckpoint observed = checkpoints.get(i);
				PerformanceHistoryStore.Checkpoint checkpoint = new PerformanceHistoryStore.Checkpoint(machineId,
						sessionId, observed.index(), observed.capturedAt(), version, originalRuntimeKind,
						observed.schemaVersion(), observed.questionCount(), observed.databaseBytes());
				Path prepared = store.writeCheckpoint(staging, checkpoint, windows.get(i), typedSql);

				// Compare the complete generated file with any existing
				// history. A matching receipt alone is insufficient.
				publishIdenticalOrNew(prepared, destination.resolve("checkpoint-%06d.csv".formatted(observed.index())));
				summaries++;
			}

			// Retain every corpus checkpoint, including windows
			// containing no completed operations.
			publishIdenticalOrNew(checkpointsFile, destination.resolve("corpus-checkpoints.csv"));
			String receipt = "sessionId=" + sessionId + "\n" + "machineId=" + machineId + "\n" + "rawSha256="
					+ operationFingerprint(operationFiles) + "\n" + "checkpointSha256=" + sha256(checkpointsFile) + "\n"
					+ "checkpoints=" + checkpoints.size() + "\n" + "summaries=" + summaries + "\n" + "measurements="
					+ measurements.size() + "\n";
			if (operationFiles.size() > 1) {
				receipt += "rawSegments=" + operationFiles.size() + "\n";
			}
			Path preparedReceipt = staging.resolve("receipt.properties");
			Files.writeString(preparedReceipt, receipt, StandardCharsets.UTF_8);

			// Publish completion last, after every summary has been
			// regenerated and verified against existing history.
			publishIdenticalOrNew(preparedReceipt, destination.resolve("session.summary-complete"));

			// The independently verifiable historical record is created last.
			// An existing manifest must agree with every regenerated summary.
			new PerformanceHistoryIntegrity().publish(destination);
			return true;
		} finally {
			try (Stream<Path> files = Files.walk(staging)) {
				for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
					Files.deleteIfExists(file);
				}
			}
		}
	}

	/**
	 * Reattempts publication of earlier completed sessions. Incomplete and active
	 * sessions are left unchanged.
	 *
	 * @param diagnosticsRoot diagnostics directory
	 * @param runtimeKind     runtime label for recovered records
	 * @return number of completed sessions successfully processed
	 * @throws IOException if the session directory cannot be listed
	 */
	public int recoverCompletedSessions(Path diagnosticsRoot, String runtimeKind) throws IOException {
		Path sessions = diagnosticsRoot.resolve("sessions");
		if (!Files.isDirectory(sessions)) {
			return 0;
		}
		int recovered = 0;
		try (Stream<Path> directories = Files.list(sessions)) {
			for (Path directory : directories.filter(Files::isDirectory).toList()) {
				UUID sessionId;
				try {
					sessionId = UUID.fromString(directory.getFileName().toString());
				} catch (IllegalArgumentException invalid) {
					continue;
				}
				try {
					if (publishSession(diagnosticsRoot, sessionId, runtimeKind)) {
						recovered++;
					}
				} catch (IOException failure) {

					// A damaged old session cannot prevent recovery
					// of other completed sessions.
					System.err.println("Performance history recovery failed for " + sessionId);
				}
			}
		}
		return recovered;
	}

	private record CorpusCheckpoint(UUID machineId, int index, Instant capturedAt, String reason, int schemaVersion,
			long questionCount, long databaseBytes) {
	}
}
