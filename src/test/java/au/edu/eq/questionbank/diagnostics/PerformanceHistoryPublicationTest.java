package au.edu.eq.questionbank.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
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
		assertTrue(Files.isRegularFile(history.resolve(PerformanceHistoryIntegrity.MANIFEST_NAME)));
		new PerformanceHistoryIntegrity().verify(history);
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
	void corruptedHistoricalManifestIsDetectedAndNeverReplaced() throws Exception {
		Path diagnostics = tempDir.resolve("manifest-corruption");
		UUID machine = UUID.randomUUID();
		UUID session = createCompletedSession(diagnostics, machine);
		PerformanceHistoryPublisher publisher = new PerformanceHistoryPublisher();
		assertTrue(publisher.publishSession(diagnostics, session, "eclipse"));
		Path history = diagnostics.resolve("history").resolve(machine.toString()).resolve(session.toString());
		Path integrity = history.resolve(PerformanceHistoryIntegrity.MANIFEST_NAME);
		String original = Files.readString(integrity);
		Files.writeString(integrity, "invalid\n");
		assertThrows(IOException.class, () -> publisher.publishSession(diagnostics, session, "unknown"));
		assertEquals("invalid\n", Files.readString(integrity));
		assertTrue(Files
				.isRegularFile(diagnostics.resolve("sessions").resolve(session.toString()).resolve("operations.csv")));
		assertTrue(original.contains("sha256.checkpoint-000000.csv="));
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
		assertEquals(5, exported);
		assertTrue(Files.isRegularFile(archive));
		Path workHistory = tempDir.resolve("work-history");
		assertEquals(5, transfer.importArchive(workHistory, archive));

		// Importing the same home history again is non-destructive.
		assertEquals(0, transfer.importArchive(workHistory, archive));
		Path imported = workHistory.resolve(homeMachine.toString()).resolve(homeSession.toString());
		assertTrue(Files.isRegularFile(imported.resolve("checkpoint-000000.csv")));
		assertTrue(Files.isRegularFile(imported.resolve("session.summary-complete")));
	}

	@Test
	void importRejectsArchiveWithCorruptedManifestBeforeWritingHistory() throws Exception {
		Path diagnostics = tempDir.resolve("bad-archive-source");
		UUID machine = UUID.randomUUID();
		UUID session = createCompletedSession(diagnostics, machine);
		assertTrue(new PerformanceHistoryPublisher().publishSession(diagnostics, session, "eclipse"));
		Path history = diagnostics.resolve("history").resolve(machine.toString()).resolve(session.toString());
		Path manifest = history.resolve(PerformanceHistoryIntegrity.MANIFEST_NAME);
		Files.writeString(manifest, "changed and incorrect\n");

		// Build the archive explicitly: exportArchive is not a verifier.
		Path zipFile = tempDir.resolve("bad-history.zip");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(zipFile));
				var paths = Files.list(history)) {
			for (Path file : paths.filter(Files::isRegularFile).toList()) {
				String entry = machine + "/" + session + "/" + file.getFileName();
				zip.putNextEntry(new ZipEntry(entry));
				Files.copy(file, zip);
				zip.closeEntry();
			}
		}
		Path imported = tempDir.resolve("would-be-imported-history");
		assertThrows(IOException.class, () -> new PerformanceHistoryTransfer().importArchive(imported, zipFile));
		assertFalse(Files.exists(imported));
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
	void missingLastSegmentIsNotTreatedAsComplete() throws Exception {
		Path diagnostics = tempDir.resolve("missing-segment");
		UUID machineId = UUID.randomUUID();
		UUID sessionId = createCompletedSession(diagnostics, machineId);
		Path session = diagnostics.resolve("sessions").resolve(sessionId.toString());

		// The marker claims two files, but only operations.csv exists.
		Path marker = session.resolve("session.complete");
		Files.writeString(marker, Files.readString(marker) + "segments=2\n");
		assertFalse(new PerformanceHistoryPublisher().publishSession(diagnostics, sessionId, "eclipse"));
		assertFalse(Files.exists(diagnostics.resolve("history")));
	}

	@Test
	void missingMiddleSegmentIsRejected() throws Exception {
		Path diagnostics = tempDir.resolve("missing-middle");
		UUID machineId = UUID.randomUUID();
		UUID sessionId = createCompletedSession(diagnostics, machineId);
		Path session = diagnostics.resolve("sessions").resolve(sessionId.toString());
		Files.copy(session.resolve("operations.csv"), session.resolve("operations-000002.csv"));
		Path marker = session.resolve("session.complete");
		Files.writeString(marker, Files.readString(marker) + "segments=3\n");
		assertThrows(IOException.class,
				() -> new PerformanceHistoryPublisher().publishSession(diagnostics, sessionId, "eclipse"));
		assertFalse(Files.exists(diagnostics.resolve("history")));
	}

	@Test
	void previouslyPublishedLegacyCheckpointIsRegeneratedWithoutSchemaChange() throws Exception {
		Path diagnostics = tempDir.resolve("old-publisher");
		UUID machine = UUID.randomUUID();
		UUID session = createCompletedSession(diagnostics, machine);
		PerformanceHistoryPublisher publisher = new PerformanceHistoryPublisher();
		assertTrue(publisher.publishSession(diagnostics, session, "eclipse"));
		Path history = diagnostics.resolve("history").resolve(machine.toString()).resolve(session.toString());

		// Recreate a pre-upgrade 15-column fixture without changing the receipt.
		for (int index = 0; index < 2; index++) {
			Path summary = history.resolve("checkpoint-%06d.csv".formatted(index));
			List<String> lines = Files.readAllLines(summary);
			StringBuilder old = new StringBuilder();
			for (String line : lines) {
				String[] cells = line.split(",", -1);
				old.append(String.join(",", Arrays.copyOf(cells, 15))).append('\n');
			}
			Files.writeString(summary, old.toString());
		}
		Files.delete(history.resolve(PerformanceHistoryIntegrity.MANIFEST_NAME));
		new PerformanceHistoryIntegrity().publish(history);
		Path previous = history.resolve("checkpoint-000000.csv");
		String before = Files.readString(previous);
		assertTrue(PerformanceHistoryStore.isLegacySummary(previous));
		assertTrue(publisher.publishSession(diagnostics, session, "unknown"));
		assertEquals(before, Files.readString(previous));
		assertTrue(PerformanceHistoryStore.isLegacySummary(previous));
		new PerformanceHistoryIntegrity().verify(history);
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

	@Test
	void segmentedSessionPublishesEveryOperationAndCanBeRetried() throws Exception {
		Path diagnostics = tempDir.resolve("segmented-diagnostics");
		UUID machineId = UUID.randomUUID();
		UUID sessionId = createCompletedSession(diagnostics, machineId);
		Path session = diagnostics.resolve("sessions").resolve(sessionId.toString());
		Path original = session.resolve("operations.csv");
		List<String> lines = Files.readAllLines(original);

		// Split the existing two-operation fixture across two files.
		Files.writeString(original, lines.get(0) + "\n" + lines.get(1) + "\n");
		Path second = session.resolve("operations-000001.csv");
		Files.writeString(second, lines.get(0) + "\n" + lines.get(2) + "\n");
		Path completion = session.resolve("session.complete");
		Files.writeString(completion, Files.readString(completion) + "segments=2\n");
		PerformanceHistoryPublisher publisher = new PerformanceHistoryPublisher();
		assertTrue(publisher.publishSession(diagnostics, sessionId, "eclipse"));
		Path historical = diagnostics.resolve("history").resolve(machineId.toString()).resolve(sessionId.toString());
		String firstSummary = Files.readString(historical.resolve("checkpoint-000000.csv"));
		String secondSummary = Files.readString(historical.resolve("checkpoint-000001.csv"));
		assertTrue(firstSummary.contains("dashboard.refresh"));
		assertTrue(secondSummary.contains("question.save"));
		String receipt = Files.readString(historical.resolve("session.summary-complete"));
		assertTrue(receipt.contains("rawSegments=2"));
		assertTrue(receipt.contains("measurements=2"));
		assertTrue(publisher.publishSession(diagnostics, sessionId, "unknown"));
		assertEquals(firstSummary, Files.readString(historical.resolve("checkpoint-000000.csv")));
		assertEquals(secondSummary, Files.readString(historical.resolve("checkpoint-000001.csv")));

		// Changing any segment invalidates the published fingerprint.
		Files.writeString(second, Files.readString(second).replace("200.0,true", "999.0,true"));
		assertThrows(IOException.class, () -> publisher.publishSession(diagnostics, sessionId, "unknown"));
		assertEquals(receipt, Files.readString(historical.resolve("session.summary-complete")));
	}

	@Test
	void sqlMetricHistoryIsImmutableAndRepublishesWithTypedValues() throws Exception {
		Path diagnostics = tempDir.resolve("sql-publisher");
		UUID machine = UUID.randomUUID();
		UUID session = createCompletedSession(diagnostics, machine);
		Path raw = diagnostics.resolve("sessions").resolve(session.toString()).resolve("operations.csv");
		Files.writeString(raw,
				"2026-10-24T00:00:03Z," + session
						+ ",3,1,dashboard.audit.subject.sql.execute-invocations,0.001,true,7\n",
				StandardOpenOption.APPEND);
		PerformanceHistoryPublisher publisher = new PerformanceHistoryPublisher();
		assertTrue(publisher.publishSession(diagnostics, session, "eclipse"));
		Path history = diagnostics.resolve("history").resolve(machine.toString()).resolve(session.toString());
		Path first = history.resolve("checkpoint-000000.csv");
		String original = Files.readString(first);
		assertTrue(original.contains("metric_unit,value_median,value_p90,value_max"));
		assertTrue(original
				.contains("\"dashboard.audit.subject.sql.execute-invocations\",1,0,0.001,0.001,0.001,count,7.0,7,7"));
		new PerformanceHistoryIntegrity().verify(history);
		assertTrue(publisher.publishSession(diagnostics, session, "unknown"));
		assertEquals(original, Files.readString(first));
		new PerformanceHistoryIntegrity().verify(history);
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
