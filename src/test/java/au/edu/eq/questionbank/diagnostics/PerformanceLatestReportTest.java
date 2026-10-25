package au.edu.eq.questionbank.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PerformanceLatestReportTest {

	@TempDir
	Path tempDir;

	@Test
	void badLegacyCsvDoesNotPreventModernReport() throws Exception {
		Path root = tempDir.resolve("diagnostics");
		addCheckpoint(root, "dashboard.refresh", 100.0);
		Files.writeString(root.resolve("performance.csv"), "invalid header\n");
		assertTrue(new PerformanceLatestReport().refresh(root));
		String html = Files.readString(PerformanceLatestReport.latestReportPath(root));
		assertTrue(html.contains("dashboard.refresh"));
		assertTrue(html.contains("const legacyObservations = [];"));
	}

	@Test
	void busyLockSkipsRefreshWithoutChangingReport() throws Exception {
		Path root = tempDir.resolve("diagnostics");
		addCheckpoint(root, "dashboard.refresh", 100.0);
		PerformanceLatestReport reports = new PerformanceLatestReport();
		assertTrue(reports.refresh(root));
		Path latest = PerformanceLatestReport.latestReportPath(root);
		String old = Files.readString(latest);
		addCheckpoint(root, "question.save", 200.0);
		Path lockPath = latest.getParent().resolve(".latest-report.lock");
		try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.WRITE);
				FileLock held = channel.lock()) {
			assertTrue(held.isValid());
			assertFalse(reports.refresh(root));
		}
		assertEquals(old, Files.readString(latest));
		assertTrue(reports.refresh(root));
		assertTrue(Files.readString(latest).contains("question.save"));
	}

	@Test
	void failedGenerationPreservesLastGoodReport() throws Exception {
		Path root = tempDir.resolve("diagnostics");
		addCheckpoint(root, "dashboard.refresh", 100.0);
		PerformanceLatestReport reports = new PerformanceLatestReport();
		assertTrue(reports.refresh(root));
		Path latest = PerformanceLatestReport.latestReportPath(root);
		String previous = Files.readString(latest);
		Path malformed = root.resolve("history").resolve(UUID.randomUUID().toString())
				.resolve(UUID.randomUUID().toString()).resolve("checkpoint-000000.csv");
		Files.createDirectories(malformed.getParent());
		Files.writeString(malformed, "invalid checkpoint");
		assertThrows(IOException.class, () -> reports.refresh(root));
		assertEquals(previous, Files.readString(latest));
		try (var files = Files.list(latest.getParent())) {
			assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith(".performance-latest-")));
		}
	}

	@Test
	void legacyDataIsDiscoveredAutomaticallyWithoutAttribution() throws Exception {
		Path root = tempDir.resolve("diagnostics");
		Files.createDirectories(root);
		Files.writeString(root.resolve("performance.csv"),
				PerformanceRecorder.HEADER + "\n" + "2026-10-01T00:00:00Z,1,0,search.refresh,50.0,true,1\n");
		assertTrue(new PerformanceLatestReport().refresh(root));
		String html = Files.readString(PerformanceLatestReport.latestReportPath(root));
		assertTrue(html.contains("Legacy performance.csv (unattributed)"));
		assertTrue(html.contains("\"operation\":\"search.refresh\""));
		assertTrue(html.contains("const observations = [];"));
	}

	@Test
	void noDataDoesNotCreateAReportOrDirectory() throws Exception {
		Path root = tempDir.resolve("diagnostics");
		assertFalse(new PerformanceLatestReport().refresh(root));
		assertFalse(Files.exists(root));
	}

	@Test
	void refreshCreatesAndAtomicallyReplacesAStableReport() throws Exception {
		Path root = tempDir.resolve("diagnostics");
		addCheckpoint(root, "dashboard.refresh", 100.0);
		PerformanceLatestReport reports = new PerformanceLatestReport();
		assertTrue(reports.refresh(root));
		Path latest = PerformanceLatestReport.latestReportPath(root);
		String first = Files.readString(latest);
		assertTrue(first.contains("dashboard.refresh"));
		assertTrue(first.contains("\"median\":100.0"));
		addCheckpoint(root, "search.current.subject", 50.0);
		assertTrue(reports.refresh(root));
		String second = Files.readString(latest);
		assertTrue(second.contains("dashboard.refresh"));
		assertTrue(second.contains("search.current.subject"));
		assertEquals("performance-latest.html", latest.getFileName().toString());
		try (var files = Files.list(latest.getParent())) {
			assertEquals(1L, files.filter(path -> path.getFileName().toString().endsWith(".html")).count());
		}
	}

	@Test
	void retriesRemoveOrphanedStagingHtmlAfterForcedExit() throws Exception {
		Path root = tempDir.resolve("diagnostics");
		addCheckpoint(root, "dashboard.refresh", 100.0);
		PerformanceLatestReport reports = new PerformanceLatestReport();
		assertTrue(reports.refresh(root));
		Path staged = PerformanceLatestReport.latestReportPath(root).getParent()
				.resolve(".performance-latest-12345678-1234-1234-1234-123456789abc.html");
		Files.writeString(staged, "orphan temporary report");
		assertTrue(reports.refresh(root));
		assertFalse(Files.exists(staged));
	}

	private void addCheckpoint(Path root, String operation, double elapsedMillis) throws Exception {
		UUID machine = UUID.randomUUID();
		UUID session = UUID.randomUUID();
		new PerformanceHistoryStore().writeCheckpoint(root.resolve("history"),
				new PerformanceHistoryStore.Checkpoint(machine, session, 0, Instant.parse("2026-10-24T00:00:00Z"),
						"0.6", "eclipse", 24, 626, 819200),
				List.of(new PerformanceCsvReader.Entry(Instant.parse("2026-10-24T00:00:01Z"), session, 1, 0, operation,
						elapsedMillis, true, 1)));
	}
}
