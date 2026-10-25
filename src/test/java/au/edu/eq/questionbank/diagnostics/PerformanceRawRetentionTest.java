package au.edu.eq.questionbank.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PerformanceRawRetentionTest {

	@TempDir
	Path tempDir;

	private static UUID completed(Path root, UUID machine, int number) throws Exception {
		UUID sessionId = UUID.randomUUID();
		Path directory = raw(root, sessionId);
		Files.createDirectories(directory);
		Files.writeString(directory.resolve("session.properties"),
				"session.id=" + sessionId + "\n" + "application.version=0.6\n" + "runtime.kind=eclipse\n");
		Files.writeString(directory.resolve("session.complete"),
				"endedAt=2026-02-01T00:02:00Z\n" + "droppedRecords=0\n" + "failedWrites=0\n");
		Files.writeString(directory.resolve("operations.csv"),
				"timestamp,session_id,operation_id,parent_id," + "operation,elapsed_ms,success,result_count\n"
						+ "2026-02-01T00:00:02Z," + sessionId + ",1,0,dashboard.refresh," + (number * 100)
						+ ".0,true,1\n");
		String header = "requested_at,captured_at,machine_id,session_id," + "checkpoint_index,reason,"
				+ "successful_question_saves," + "schema_version,question_count,answer_count,"
				+ "question_content_parts," + "shared_context_content_parts,database_bytes\n";
		String prefix = "2026-02-01T00:00:00Z,2026-02-01T00:00:01Z," + machine + "," + sessionId;
		Files.writeString(directory.resolve("corpus-checkpoints.csv"),
				header + prefix + ",0,startup,0,24,800,100,1000,200,819200\n" + "2026-02-01T00:01:00Z,"
						+ "2026-02-01T00:01:01Z," + machine + "," + sessionId + ",1,shutdown,0,24,800,100,"
						+ "1000,200,819200\n");
		assertTrue(new PerformanceHistoryPublisher().publishSession(root, sessionId, "eclipse"));
		return sessionId;
	}

	private static Path raw(Path root, UUID sessionId) {
		return root.resolve("sessions").resolve(sessionId.toString());
	}

	private static long size(Path directory) throws Exception {
		try (Stream<Path> files = Files.list(directory)) {
			return files.filter(Files::isRegularFile).mapToLong(path -> {
				try {
					return Files.size(path);
				} catch (java.io.IOException failure) {
					throw new java.io.UncheckedIOException(failure);
				}
			}).sum();
		}
	}

	@Test
	void activeAndUnexpectedFilesAreProtected() throws Exception {
		Path root = tempDir.resolve("diagnostics");
		UUID machine = UUID.randomUUID();
		UUID active = completed(root, machine, 1);
		UUID unexpected = completed(root, machine, 2);
		Files.writeString(raw(root, unexpected).resolve("unexpected.txt"), "preserve");
		PerformanceRawRetention.Result result = new PerformanceRawRetention().enforce(root, 1, active, true);
		assertEquals(0, result.deletedSessions());
		assertTrue(Files.isDirectory(raw(root, active)));
		assertTrue(Files.isRegularFile(raw(root, unexpected).resolve("unexpected.txt")));
	}

	@Test
	void corruptIntegrityManifestPreventsDeletion() throws Exception {
		Path root = tempDir.resolve("corrupt-integrity");
		UUID machine = UUID.randomUUID();
		UUID session = completed(root, machine, 1);
		Path rawSession = raw(root, session);
		Path integrity = root.resolve("history").resolve(machine.toString()).resolve(session.toString())
				.resolve(PerformanceHistoryIntegrity.MANIFEST_NAME);
		assertTrue(Files.isRegularFile(integrity));
		Files.writeString(integrity, "corrupt\n");
		PerformanceRawRetention.Result result = new PerformanceRawRetention().enforce(root, 1, null, true);
		assertTrue(result.lockAcquired());
		assertEquals(0, result.deletedSessions());
		assertTrue(Files.isDirectory(rawSession));
		assertEquals("corrupt\n", Files.readString(integrity));
	}

	@Test
	void dryRunNeverDeletesSessions() throws Exception {
		Path root = tempDir.resolve("diagnostics");
		UUID machine = UUID.randomUUID();
		UUID session = completed(root, machine, 1);
		PerformanceRawRetention.Result result = new PerformanceRawRetention().enforce(root, 1, null, false);
		assertTrue(result.lockAcquired());
		assertEquals(0, result.deletedSessions());
		assertEquals(result.beforeBytes(), result.afterBytes());
		assertTrue(Files.isDirectory(raw(root, session)));
	}

	@Test
	void expiryDeletesVerifiedOldRawSessionsEvenWhenUnderByteLimit() throws Exception {
		Path root = tempDir.resolve("age-expired");
		UUID machine = UUID.randomUUID();
		UUID older = completed(root, machine, 1);
		UUID recent = completed(root, machine, 2);
		Path olderRaw = raw(root, older);
		Path recentRaw = raw(root, recent);
		Files.setLastModifiedTime(olderRaw.resolve("session.complete"),
				FileTime.from(Instant.now().minus(Duration.ofDays(120))));
		Files.setLastModifiedTime(recentRaw.resolve("session.complete"),
				FileTime.from(Instant.now().minus(Duration.ofDays(1))));

		// The combined raw size is below the cap. Age alone must remove the old
		// verified session, while preserving its immutable historical summary.
		long generousCap = size(olderRaw) + size(recentRaw) + 1024;
		PerformanceRawRetention.Result result = new PerformanceRawRetention().enforce(root, generousCap,
				Duration.ofDays(90), null, true);
		assertTrue(result.lockAcquired());
		assertEquals(1, result.deletedSessions());
		assertFalse(Files.exists(olderRaw));
		assertTrue(Files.isDirectory(recentRaw));
		Path oldHistory = root.resolve("history").resolve(machine.toString()).resolve(older.toString());
		new PerformanceHistoryIntegrity().verify(oldHistory);
	}

	@Test
	void expiryDryRunNeverDeletesRawSessions() throws Exception {
		Path root = tempDir.resolve("age-dry-run");
		UUID machine = UUID.randomUUID();
		UUID old = completed(root, machine, 1);
		Path session = raw(root, old);
		Files.setLastModifiedTime(session.resolve("session.complete"),
				FileTime.from(Instant.now().minus(Duration.ofDays(120))));
		long generousCap = size(session) + 1024;
		PerformanceRawRetention.Result result = new PerformanceRawRetention().enforce(root, generousCap,
				Duration.ofDays(90), null, false);
		assertTrue(result.lockAcquired());
		assertEquals(0, result.deletedSessions());
		assertEquals(result.beforeBytes(), result.afterBytes());
		assertTrue(Files.isDirectory(session));
	}

	@Test
	void incompleteAndCorruptHistoricalSessionsAreProtected() throws Exception {
		Path root = tempDir.resolve("diagnostics");
		UUID machine = UUID.randomUUID();
		UUID incomplete = completed(root, machine, 1);
		UUID corrupt = completed(root, machine, 2);
		Files.delete(raw(root, incomplete).resolve("session.complete"));
		Path summary = root.resolve("history").resolve(machine.toString()).resolve(corrupt.toString())
				.resolve("checkpoint-000000.csv");
		Files.writeString(summary, "corrupt historical summary");
		PerformanceRawRetention.Result result = new PerformanceRawRetention().enforce(root, 1, null, true);
		assertEquals(0, result.deletedSessions());
		assertTrue(Files.isDirectory(raw(root, incomplete)));
		assertTrue(Files.isDirectory(raw(root, corrupt)));
		assertEquals("corrupt historical summary", Files.readString(summary));
	}

	@Test
	void invalidMaximumAgeIsRejected() throws Exception {
		Path root = tempDir.resolve("age-invalid");
		PerformanceRawRetention retention = new PerformanceRawRetention();
		assertThrows(IllegalArgumentException.class, () -> retention.enforce(root, 1024, Duration.ZERO, null, true));
		assertThrows(IllegalArgumentException.class,
				() -> retention.enforce(root, 1024, Duration.ofDays(-1), null, true));
	}

	@Test
	void oldestVerifiedRawSessionIsDeletedButHistoryRemains() throws Exception {
		Path root = tempDir.resolve("diagnostics");
		UUID machine = UUID.randomUUID();
		UUID older = completed(root, machine, 1);
		UUID newer = completed(root, machine, 2);
		Path olderRaw = raw(root, older);
		Path newerRaw = raw(root, newer);
		Files.setLastModifiedTime(olderRaw.resolve("session.complete"),
				FileTime.from(Instant.now().minus(Duration.ofDays(120))));
		Files.setLastModifiedTime(newerRaw.resolve("session.complete"),
				FileTime.from(Instant.now().minus(Duration.ofDays(1))));
		long ceiling = size(newerRaw) + 1;
		PerformanceRawRetention.Result result = new PerformanceRawRetention().enforce(root, ceiling, null, true);
		assertTrue(result.lockAcquired());
		assertEquals(1, result.deletedSessions());
		assertFalse(Files.exists(olderRaw));
		assertTrue(Files.isDirectory(newerRaw));
		Path oldHistory = root.resolve("history").resolve(machine.toString()).resolve(older.toString());
		assertTrue(Files.isRegularFile(oldHistory.resolve("session.summary-complete")));
		assertTrue(Files.isRegularFile(oldHistory.resolve("checkpoint-000000.csv")));
	}

	@Test
	void retainedHistoryVerifiesAfterRawSessionIsPruned() throws Exception {
		Path root = tempDir.resolve("verified-retention");
		UUID machine = UUID.randomUUID();
		UUID session = completed(root, machine, 1);
		Path historical = root.resolve("history").resolve(machine.toString()).resolve(session.toString());
		new PerformanceHistoryIntegrity().verify(historical);
		PerformanceRawRetention.Result result = new PerformanceRawRetention().enforce(root, 1, null, true);
		assertEquals(1, result.deletedSessions());
		assertFalse(Files.exists(raw(root, session)));
		new PerformanceHistoryIntegrity().verify(historical);
	}

	@Test
	void secondInstanceCannotPruneDuringExistingLock() throws Exception {
		Path root = tempDir.resolve("diagnostics");
		UUID machine = UUID.randomUUID();
		UUID session = completed(root, machine, 1);
		Path lockFile = root.resolve(".raw-retention.lock");
		try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
				FileLock lock = channel.lock()) {
			assertTrue(lock.isValid());
			PerformanceRawRetention.Result result = new PerformanceRawRetention().enforce(root, 1, null, true);
			assertFalse(result.lockAcquired());
			assertEquals(0, result.deletedSessions());
			assertTrue(Files.isDirectory(raw(root, session)));
		}
	}

	@Test
	void verifiedSegmentedSessionCanBePrunedWithoutDeletingHistory() throws Exception {
		Path root = tempDir.resolve("segmented-retention");
		UUID machineId = UUID.randomUUID();
		UUID sessionId = completed(root, machineId, 1);
		Path session = raw(root, sessionId);
		Path historical = root.resolve("history").resolve(machineId.toString()).resolve(sessionId.toString());

		// Rebuild the disposable fixture as a segmented session.
		try (Stream<Path> paths = Files.walk(historical)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(path);
			}
		}
		Path original = session.resolve("operations.csv");
		List<String> lines = Files.readAllLines(original);
		Files.writeString(original, lines.getFirst() + "\n");
		Files.writeString(session.resolve("operations-000001.csv"), lines.getFirst() + "\n" + lines.get(1) + "\n");
		Path marker = session.resolve("session.complete");
		Files.writeString(marker, Files.readString(marker) + "segments=2\n");
		assertTrue(new PerformanceHistoryPublisher().publishSession(root, sessionId, "eclipse"));
		PerformanceRawRetention.Result result = new PerformanceRawRetention().enforce(root, 1, null, true);
		assertTrue(result.lockAcquired());
		assertEquals(1, result.deletedSessions());
		assertFalse(Files.exists(session));
		assertTrue(Files.isRegularFile(historical.resolve("checkpoint-000000.csv")));
		assertTrue(Files.isRegularFile(historical.resolve("session.summary-complete")));
	}
}
