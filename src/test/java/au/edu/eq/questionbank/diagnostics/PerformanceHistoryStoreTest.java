package au.edu.eq.questionbank.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PerformanceHistoryStoreTest {

	@TempDir
	Path tempDir;

	@Test
	void checkpointAggregatesOperationsWithoutChangingRawData() throws Exception {
		UUID machineId = UUID.randomUUID();
		UUID sessionId = UUID.randomUUID();
		Path diagnostics = tempDir.resolve("diagnostics");
		Path raw = diagnostics.resolve("sessions").resolve(sessionId.toString()).resolve("operations.csv");
		Files.createDirectories(raw.getParent());
		Files.writeString(raw, "original raw observations");
		PerformanceHistoryStore.Checkpoint checkpoint = checkpoint(machineId, sessionId, 0);
		List<PerformanceCsvReader.Entry> entries = List.of(entry(sessionId, "dashboard.refresh", 100, true),
				entry(sessionId, "dashboard.refresh", 120, true), entry(sessionId, "dashboard.refresh", 200, false),
				entry(sessionId, "dashboard.refresh", 400, true), entry(sessionId, "question.search", 50, true));
		Path summary = new PerformanceHistoryStore().writeCheckpoint(diagnostics.resolve("history"), checkpoint,
				entries);
		List<String> lines = Files.readAllLines(summary);
		assertEquals(3, lines.size());
		assertTrue(lines.get(0).contains("question_count"));
		assertTrue(lines.get(1).contains("\"dashboard.refresh\",4,1,160.0,400.0,400.0"));
		assertTrue(lines.get(2).contains("\"question.search\",1,0,50.0,50.0,50.0"));
		assertTrue(lines.get(1).contains("," + machineId + "," + sessionId + ","));
		assertTrue(lines.get(1).contains(",800,819200,"));

		// Publishing the permanent history must not touch source data.
		assertEquals("original raw observations", Files.readString(raw));
	}

	@Test
	void differentMachinesRetainIndependentHistories() throws Exception {
		UUID homeMachine = UUID.randomUUID();
		UUID workMachine = UUID.randomUUID();
		UUID sessionId = UUID.randomUUID();
		PerformanceHistoryStore store = new PerformanceHistoryStore();
		Path history = tempDir.resolve("history");
		List<PerformanceCsvReader.Entry> entries = List.of(entry(sessionId, "dashboard.refresh", 500, true));
		Path home = store.writeCheckpoint(history, checkpoint(homeMachine, sessionId, 0), entries);
		Path work = store.writeCheckpoint(history, checkpoint(workMachine, sessionId, 0), entries);
		assertNotEquals(home, work);
		assertTrue(Files.isRegularFile(home));
		assertTrue(Files.isRegularFile(work));
		assertTrue(Files.readString(home).contains(homeMachine.toString()));
		assertTrue(Files.readString(work).contains(workMachine.toString()));
	}

	@Test
	void emptyCheckpointDoesNotPublishMisleadingSummary() {
		UUID machineId = UUID.randomUUID();
		UUID sessionId = UUID.randomUUID();
		Path history = tempDir.resolve("history");
		assertThrows(IllegalArgumentException.class, () -> new PerformanceHistoryStore().writeCheckpoint(history,
				checkpoint(machineId, sessionId, 0), List.of()));
		assertFalse(Files.exists(history));
	}

	@Test
	void existingCheckpointCannotBeOverwritten() throws Exception {
		UUID machineId = UUID.randomUUID();
		UUID sessionId = UUID.randomUUID();
		PerformanceHistoryStore store = new PerformanceHistoryStore();
		Path root = tempDir.resolve("history");
		PerformanceHistoryStore.Checkpoint checkpoint = checkpoint(machineId, sessionId, 1);
		List<PerformanceCsvReader.Entry> entries = List.of(entry(sessionId, "question.save", 100, true));
		Path saved = store.writeCheckpoint(root, checkpoint, entries);
		String original = Files.readString(saved);
		assertThrows(FileAlreadyExistsException.class, () -> store.writeCheckpoint(root, checkpoint, entries));
		assertEquals(original, Files.readString(saved));
	}

	@Test
	void rejectsMeasurementsFromAnotherSession() {
		UUID machineId = UUID.randomUUID();
		UUID sessionId = UUID.randomUUID();
		Path history = tempDir.resolve("history");
		List<PerformanceCsvReader.Entry> entries = List.of(entry(UUID.randomUUID(), "question.save", 80, true));
		assertThrows(IllegalArgumentException.class, () -> new PerformanceHistoryStore().writeCheckpoint(history,
				checkpoint(machineId, sessionId, 0), entries));
		assertFalse(Files.exists(history));
	}

	private PerformanceHistoryStore.Checkpoint checkpoint(UUID machineId, UUID sessionId, int index) {
		return new PerformanceHistoryStore.Checkpoint(machineId, sessionId, index,
				Instant.parse("2026-10-24T00:00:00Z"), "0.4", "eclipse", 24, 800, 819200);
	}

	private PerformanceCsvReader.Entry entry(UUID sessionId, String operation, double elapsedMillis, boolean success) {
		return new PerformanceCsvReader.Entry(Instant.parse("2026-10-24T00:00:00Z"), sessionId, 1, 0, operation,
				elapsedMillis, success, -1);
	}
}
