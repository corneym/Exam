package au.edu.eq.questionbank.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PerformanceHistoryPublicationTest {

	@TempDir
	Path tempDir;

	@Test
	void archiveRejectsTraversalEntries() throws Exception {
		Path archive = tempDir.resolve("invalid.zip");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
			zip.putNextEntry(new ZipEntry("../../outside.txt"));
			zip.write("invalid".getBytes());
			zip.closeEntry();
		}
		Path history = tempDir.resolve("history");
		assertThrows(IOException.class, () -> new PerformanceHistoryTransfer().importArchive(history, archive));
		assertFalse(Files.exists(tempDir.resolve("outside.txt")));
	}

	@Test
	void completedSessionPublishesAndCanBeRetried() throws Exception {
		Path diagnostics = tempDir.resolve("diagnostics");
		UUID machineId = UUID.randomUUID();
		UUID sessionId = createCompletedSession(diagnostics, machineId);
		PerformanceHistoryPublisher publisher = new PerformanceHistoryPublisher();
		assertTrue(publisher.publishSession(diagnostics, sessionId, "eclipse"));
		Path history = diagnostics.resolve("history").resolve(machineId.toString()).resolve(sessionId.toString());
		assertTrue(Files.isRegularFile(history.resolve("checkpoint-000000.csv")));
		assertTrue(Files.isRegularFile(history.resolve("checkpoint-000001.csv")));
		assertTrue(Files.isRegularFile(history.resolve("corpus-checkpoints.csv")));
		assertTrue(Files.isRegularFile(history.resolve("session.summary-complete")));
		String firstSummary = Files.readString(history.resolve("checkpoint-000000.csv"));
		assertTrue(firstSummary.contains("dashboard.refresh"));
		assertTrue(firstSummary.contains(machineId.toString()));
		assertTrue(firstSummary.contains(sessionId.toString()));
		assertTrue(firstSummary.contains(",800,819200,"));

		// Repeated publication must not duplicate or change history.
		assertTrue(publisher.publishSession(diagnostics, sessionId, "eclipse"));
		assertEquals(firstSummary, Files.readString(history.resolve("checkpoint-000000.csv")));

		// Raw measurements are retained, not replaced or deleted.
		assertTrue(Files.isRegularFile(
				diagnostics.resolve("sessions").resolve(sessionId.toString()).resolve("operations.csv")));
	}

	@Test
	void conflictingHistoryIsNeverOverwritten() throws Exception {
		Path diagnostics = tempDir.resolve("diagnostics");
		UUID machineId = UUID.randomUUID();
		UUID sessionId = createCompletedSession(diagnostics, machineId);
		PerformanceHistoryPublisher publisher = new PerformanceHistoryPublisher();
		assertTrue(publisher.publishSession(diagnostics, sessionId, "eclipse"));
		Path summary = diagnostics.resolve("history").resolve(machineId.toString()).resolve(sessionId.toString())
				.resolve("checkpoint-000000.csv");
		Files.writeString(summary, "different content");
		assertThrows(IOException.class, () -> publisher.publishSession(diagnostics, sessionId, "eclipse"));
		assertEquals("different content", Files.readString(summary));
		assertTrue(Files.isRegularFile(
				diagnostics.resolve("sessions").resolve(sessionId.toString()).resolve("operations.csv")));
	}

	@Test
	void existingExportArchiveIsNeverDeleted() throws Exception {
		Path diagnostics = tempDir.resolve("diagnostics");
		UUID machineId = UUID.randomUUID();
		UUID sessionId = createCompletedSession(diagnostics, machineId);
		new PerformanceHistoryPublisher().publishSession(diagnostics, sessionId, "eclipse");
		Path archive = tempDir.resolve("existing.zip");
		Files.writeString(archive, "keep this archive");
		assertThrows(FileAlreadyExistsException.class,
				() -> new PerformanceHistoryTransfer().exportArchive(diagnostics.resolve("history"), archive));
		assertEquals("keep this archive", Files.readString(archive));
	}

	@Test
	void historyArchiveMergesAndIgnoresDuplicates() throws Exception {
		Path diagnostics = tempDir.resolve("home-diagnostics");
		UUID homeMachine = UUID.randomUUID();
		UUID homeSession = createCompletedSession(diagnostics, homeMachine);
		assertTrue(new PerformanceHistoryPublisher().publishSession(diagnostics, homeSession, "eclipse"));
		Path archive = tempDir.resolve("history.zip");
		PerformanceHistoryTransfer transfer = new PerformanceHistoryTransfer();
		int exported = transfer.exportArchive(diagnostics.resolve("history"), archive);
		assertEquals(4, exported);
		assertTrue(Files.isRegularFile(archive));
		Path workHistory = tempDir.resolve("work-history");
		assertEquals(4, transfer.importArchive(workHistory, archive));

		// Importing the same home history again is non-destructive.
		assertEquals(0, transfer.importArchive(workHistory, archive));
		Path imported = workHistory.resolve(homeMachine.toString()).resolve(homeSession.toString());
		assertTrue(Files.isRegularFile(imported.resolve("checkpoint-000000.csv")));
		assertTrue(Files.isRegularFile(imported.resolve("session.summary-complete")));
	}

	@Test
	void incompleteSessionIsNotPublished() throws Exception {
		Path diagnostics = tempDir.resolve("diagnostics");
		UUID machineId = UUID.randomUUID();
		UUID sessionId = createCompletedSession(diagnostics, machineId);
		Files.delete(diagnostics.resolve("sessions").resolve(sessionId.toString()).resolve("session.complete"));
		assertFalse(new PerformanceHistoryPublisher().publishSession(diagnostics, sessionId, "eclipse"));
		assertFalse(Files.exists(diagnostics.resolve("history")));
	}

	@Test
	void recoveryDoesNotReclassifyAnAlreadyPublishedSession() throws Exception {
		Path diagnostics = tempDir.resolve("diagnostics");
		UUID machineId = UUID.randomUUID();
		UUID sessionId = createCompletedSession(diagnostics, machineId);
		PerformanceHistoryPublisher publisher = new PerformanceHistoryPublisher();
		assertTrue(publisher.publishSession(diagnostics, sessionId, "eclipse"));
		Path published = diagnostics.resolve("history").resolve(machineId.toString()).resolve(sessionId.toString())
				.resolve("checkpoint-000000.csv");
		String original = Files.readString(published);

		// Recovery provides a different fallback label, but the
		// completed session must not be republished differently.
		assertTrue(publisher.publishSession(diagnostics, sessionId, "unknown"));
		assertEquals(original, Files.readString(published));
	}

	@Test
	void recoveryRejectsCorruptPreviouslyPublishedSummary() throws Exception {
		Path diagnostics = tempDir.resolve("diagnostics");
		UUID machineId = UUID.randomUUID();
		UUID sessionId = createCompletedSession(diagnostics, machineId);
		PerformanceHistoryPublisher publisher = new PerformanceHistoryPublisher();
		assertTrue(publisher.publishSession(diagnostics, sessionId, "eclipse"));
		Path published = diagnostics.resolve("history").resolve(machineId.toString()).resolve(sessionId.toString())
				.resolve("checkpoint-000000.csv");
		Files.writeString(published, "corrupted summary");

		// Recovery with an unknown runtime must not mistake the
		// existing receipt for proof of valid summary contents.
		assertThrows(IOException.class, () -> publisher.publishSession(diagnostics, sessionId, "unknown"));
		assertEquals("corrupted summary", Files.readString(published));

		// The original session measurements remain recoverable.
		assertTrue(Files.isRegularFile(
				diagnostics.resolve("sessions").resolve(sessionId.toString()).resolve("operations.csv")));
	}

	private UUID createCompletedSession(Path diagnostics, UUID machineId) throws Exception {
		UUID sessionId = UUID.randomUUID();
		Path session = diagnostics.resolve("sessions").resolve(sessionId.toString());
		Files.createDirectories(session);
		Files.writeString(session.resolve("session.properties"),
				"session.id=" + sessionId + "\n" + "application.version=0.4\n");
		Files.writeString(session.resolve("session.complete"),
				"endedAt=2026-10-24T00:02:00Z\n" + "droppedRecords=0\n" + "failedWrites=0\n");
		Files.writeString(session.resolve("operations.csv"),
				"timestamp,session_id,operation_id,parent_id," + "operation,elapsed_ms,success,result_count\n"
						+ "2026-10-24T00:00:02Z," + sessionId + ",1,0,dashboard.refresh,100.0,true,1\n"
						+ "2026-10-24T00:01:02Z," + sessionId + ",2,0,question.save,200.0,true,1\n");
		String header = "requested_at,captured_at,machine_id,session_id," + "checkpoint_index,reason,"
				+ "successful_question_saves," + "schema_version,question_count,answer_count,"
				+ "question_content_parts," + "shared_context_content_parts,database_bytes\n";
		String startup = "2026-10-24T00:00:00Z,2026-10-24T00:00:01Z," + machineId + "," + sessionId
				+ ",0,startup,0,24,800,100,1000,200,819200\n";
		String shutdown = "2026-10-24T00:01:00Z,2026-10-24T00:01:01Z," + machineId + "," + sessionId
				+ ",1,shutdown,0,24,800,100,1000,200,819200\n";
		Files.writeString(session.resolve("corpus-checkpoints.csv"), header + startup + shutdown);
		return sessionId;
	}
}
