package au.edu.eq.questionbank.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class PerformanceCorpusCheckpointRecorderTest {

	@TempDir
	Path tempDir;

	@Test
	void collectorNotStartedCreatesNoDiagnosticFiles() throws Exception {
		Path diagnostics = tempDir.resolve("diagnostics");
		try (PerformanceCorpusCheckpointRecorder recorder = new PerformanceCorpusCheckpointRecorder(diagnostics,
				tempDir.resolve("questionbank.db"), UUID.randomUUID())) {
			recorder.questionSaved();
		}
		assertFalse(Files.exists(diagnostics));
	}

	@Test
	void eightCommittedSavesProduceAnIntermediateCheckpoint() throws Exception {
		Path databasePath = tempDir.resolve("questionbank.db");
		new SqliteDatabase(databasePath).initialiseSchema();
		Path diagnostics = tempDir.resolve("diagnostics");
		UUID sessionId = UUID.randomUUID();
		PerformanceCorpusCheckpointRecorder recorder = new PerformanceCorpusCheckpointRecorder(diagnostics,
				databasePath, sessionId);
		try (recorder) {
			recorder.start();
			for (int i = 0; i < 7; i++) {
				recorder.questionSaved();
			}
			recorder.questionSaved();
		}
		Path file = diagnostics.resolve("sessions").resolve(sessionId.toString()).resolve("corpus-checkpoints.csv");
		List<String> lines = Files.readAllLines(file);
		assertEquals(4, lines.size());
		assertTrue(lines.get(0).contains("machine_id"));
		assertTrue(lines.get(1).contains(",0,startup,0,"));
		assertTrue(lines.get(2).contains(",1,question-save-8,8,"));
		assertTrue(lines.get(3).contains(",2,shutdown,8,"));
		assertEquals(0, recorder.failedCheckpointCount());
		UUID machineId = PerformanceMachineIdentity.loadOrCreate(diagnostics);
		for (int i = 1; i < lines.size(); i++) {
			assertTrue(lines.get(i).contains("," + machineId + "," + sessionId + ","));
		}
	}

	@Test
	void fewerThanEightSavesProduceOnlyBoundaryCheckpoints() throws Exception {
		Path databasePath = tempDir.resolve("questionbank.db");
		new SqliteDatabase(databasePath).initialiseSchema();
		Path diagnostics = tempDir.resolve("diagnostics");
		UUID sessionId = UUID.randomUUID();
		try (PerformanceCorpusCheckpointRecorder recorder = new PerformanceCorpusCheckpointRecorder(diagnostics,
				databasePath, sessionId)) {
			recorder.start();
			for (int i = 0; i < 7; i++) {
				recorder.questionSaved();
			}
		}
		Path file = diagnostics.resolve("sessions").resolve(sessionId.toString()).resolve("corpus-checkpoints.csv");
		List<String> lines = Files.readAllLines(file);
		assertEquals(3, lines.size());
		assertTrue(lines.get(1).contains(",startup,0,"));
		assertTrue(lines.get(2).contains(",shutdown,7,"));
	}

	@Test
	void missingDatabaseDoesNotPreventShutdown() throws Exception {
		Path missingDatabase = tempDir.resolve("missing.db");
		Path diagnostics = tempDir.resolve("diagnostics");
		PerformanceCorpusCheckpointRecorder recorder = new PerformanceCorpusCheckpointRecorder(diagnostics,
				missingDatabase, UUID.randomUUID());
		try (recorder) {
			recorder.start();
		}
		assertEquals(2, recorder.failedCheckpointCount());
		assertFalse(Files.exists(missingDatabase));
	}

	@Test
	void restoreExitDoesNotSampleTheReplacementDatabase() throws Exception {
		Path databasePath = tempDir.resolve("questionbank.db");
		new SqliteDatabase(databasePath).initialiseSchema();
		Path diagnostics = tempDir.resolve("diagnostics");
		UUID sessionId = UUID.randomUUID();
		try (PerformanceCorpusCheckpointRecorder recorder = new PerformanceCorpusCheckpointRecorder(diagnostics,
				databasePath, sessionId)) {
			recorder.start();
			recorder.closeWithoutFinalSample();
		}
		Path file = diagnostics.resolve("sessions").resolve(sessionId.toString()).resolve("corpus-checkpoints.csv");
		List<String> lines = Files.readAllLines(file);
		assertEquals(2, lines.size());
		assertTrue(lines.get(1).contains(",startup,0,"));
		assertFalse(Files.readString(file).contains(",shutdown,"));
	}
}
