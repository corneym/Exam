package au.edu.eq.questionbank.admin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.diagnostics.PerformanceHistoryPublisher;

class PerformanceSessionAcceptanceToolTest {

	@TempDir
	Path tempDir;

	private static String row(UUID session, long id, long parent, String operation, long count) {
		return "2026-10-25T03:00:00Z," + session + "," + id + "," + parent + "," + operation + ",1.0,true," + count;
	}

	private static int run(Fixture fixture, ByteArrayOutputStream output) {
		return PerformanceSessionAcceptanceTool.run(new String[] { "--diagnostics-root", fixture.root().toString(),
				"--session", fixture.sessionId().toString() }, new PrintStream(output), new PrintStream(output));
	}

	private static int sql(UUID sessionId, List<String> rows, int next, long parent, String prefix) {
		long scope = next++;
		rows.add(row(sessionId, scope, parent, prefix + ".sql.scope", 1));
		rows.add(row(sessionId, next++, scope, prefix + ".sql.connections", 1));
		rows.add(row(sessionId, next++, scope, prefix + ".sql.execute-invocations", 1));
		return next;
	}

	@Test
	void completedEightSaveSessionPassesWithoutModifyingSourceFiles() throws Exception {
		Fixture fixture = fixture(false, true);
		byte[] csvBefore = Files.readAllBytes(fixture.session().resolve("operations.csv"));
		byte[] checkpointsBefore = Files.readAllBytes(fixture.session().resolve("corpus-checkpoints.csv"));
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		int status = run(fixture, output);
		assertEquals(0, status, output.toString(StandardCharsets.UTF_8));
		assertTrue(output.toString(StandardCharsets.UTF_8).contains("PASS:"));
		assertTrue(output.toString(StandardCharsets.UTF_8).contains("Successful Question saves: 8"));
		assertArrayEquals(csvBefore, Files.readAllBytes(fixture.session().resolve("operations.csv")));
		assertArrayEquals(checkpointsBefore, Files.readAllBytes(fixture.session().resolve("corpus-checkpoints.csv")));
	}

	@Test
	void damagedHistoryManifestFailsWithoutChangingFiles() throws Exception {
		Fixture fixture = fixture(false, true);
		Path manifest;
		try (var directories = Files.list(fixture.root().resolve("history"))) {
			Path machine = directories.findFirst().orElseThrow();
			manifest = machine.resolve(fixture.sessionId().toString()).resolve("session.integrity");
		}
		Files.writeString(manifest, "damaged manifest");
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		assertEquals(1, run(fixture, output));
		assertTrue(output.toString(StandardCharsets.UTF_8).contains("integrity"));
		assertEquals("damaged manifest", Files.readString(manifest));
	}

	@Test
	void invalidArgumentsNeverCreateDiagnostics() {
		Path missing = tempDir.resolve("absent");
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		int status = PerformanceSessionAcceptanceTool.run(
				new String[] { "--diagnostics-root", "relative-root", "--session", UUID.randomUUID().toString() },
				new PrintStream(output), new PrintStream(output));
		assertEquals(2, status);
		assertFalse(Files.exists(missing));
	}

	@Test
	void missingIntermediateSaveCheckpointFailsAcceptance() throws Exception {
		Fixture fixture = fixture(false, false);
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		assertEquals(1, run(fixture, output));
		assertTrue(output.toString(StandardCharsets.UTF_8).contains("question-save-8"));
	}

	@Test
	void missingSaveSqlPhaseFailsAcceptance() throws Exception {
		Fixture fixture = fixture(true, true);
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		assertEquals(1, run(fixture, output));
		assertTrue(output.toString(StandardCharsets.UTF_8).contains("Missing SQL scope"));
	}

	@Test
	void partialSessionCannotPass() throws Exception {
		Fixture fixture = fixture(false, true);
		Files.writeString(fixture.session().resolve("session.partial"), "aborted");
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		assertEquals(1, run(fixture, output));
		assertTrue(output.toString(StandardCharsets.UTF_8).contains("not completed cleanly"));
	}

	private Fixture fixture(boolean omitFirstSqlPhase, boolean intermediateCheckpoint) throws Exception {
		UUID sessionId = UUID.randomUUID();
		UUID machineId = UUID.randomUUID();
		Path root = tempDir.resolve("diagnostics-" + sessionId);
		Path session = root.resolve("sessions").resolve(sessionId.toString());
		Files.createDirectories(session);
		Files.writeString(session.resolve("session.properties"),
				"session.id=" + sessionId + "\nsession.startedAt=2026-10-25T02:59:00Z\n"
						+ "application.version=0.6\nruntime.kind=eclipse\njava.version=25\nos.name=Windows 11\n");
		Files.writeString(session.resolve("session.complete"),
				"endedAt=2026-10-25T03:20:00Z\ndroppedRecords=0\nfailedWrites=0\nsegments=1\n");
		String header = "requested_at,captured_at,machine_id,session_id,checkpoint_index,reason,"
				+ "successful_question_saves,schema_version,question_count,answer_count,"
				+ "question_content_parts,shared_context_content_parts,database_bytes\n";
		String prefix = "2026-10-25T02:59:00Z,2026-10-25T02:59:01Z," + machineId + "," + sessionId;
		String midReason = intermediateCheckpoint ? "question-save-8" : "manual";
		Files.writeString(session.resolve("corpus-checkpoints.csv"),
				header + prefix + ",0,startup,0,24,100,80,120,12,819200\n"
						+ "2026-10-25T03:10:00Z,2026-10-25T03:10:01Z," + machineId + "," + sessionId + ",1," + midReason
						+ ",8,24,108,80,128,12,819200\n" + "2026-10-25T03:20:00Z,2026-10-25T03:20:01Z," + machineId
						+ "," + sessionId + ",2,shutdown,8,24,108,80,128,12,819200\n");
		List<String> operations = new ArrayList<>();
		operations.add("timestamp,session_id,operation_id,parent_id,operation,elapsed_ms,success,result_count");
		int next = 1;
		for (String name : List.of("application.startup", "dashboard.refresh", "search.refresh", "search.preview.load",
				"search.preview.render", "examAssets.open.selected", "capture.answer.save", "runtime.heap.used-kib",
				"runtime.gc.collection-time-ms-delta")) {
			operations.add(row(sessionId, next++, 0, name, 1));
		}
		long dashboard = next++;
		operations.add(row(sessionId, dashboard, 0, "dashboard.snapshot", 1));
		operations.add(row(sessionId, next++, dashboard, "dashboard.snapshot.sql.scope", 1));
		long assets = next++;
		operations.add(row(sessionId, assets, 0, "examAssets.open.selected.load", 1));
		operations.add(row(sessionId, next++, assets, "examAssets.open.selected.load.sql.scope", 1));
		for (int i = 0; i < 8; i++) {
			long save = next++;
			operations.add(row(sessionId, save, 0, "capture.question.save", 1));
			long read = next++;
			operations.add(row(sessionId, read, save, "capture.question.save.read-before", 1));
			if (!(omitFirstSqlPhase && i == 0)) {
				next = sql(sessionId, operations, next, read, "capture.question.save.validate");
			}
			long persist = next++;
			operations.add(row(sessionId, persist, save, "capture.question.save.persist", 1));
			next = sql(sessionId, operations, next, persist, "capture.question.save.persist");
			long refresh = next++;
			operations.add(row(sessionId, refresh, save, "capture.question.save.refresh", 1));
			next = sql(sessionId, operations, next, refresh, "capture.question.save.refresh");
		}
		Files.writeString(session.resolve("operations.csv"), String.join("\n", operations) + "\n");
		assertTrue(new PerformanceHistoryPublisher().publishSession(root, sessionId, "eclipse"));
		return new Fixture(root, session, sessionId);
	}

	private record Fixture(Path root, Path session, UUID sessionId) {
	}
}
