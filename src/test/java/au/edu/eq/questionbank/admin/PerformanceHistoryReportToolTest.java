package au.edu.eq.questionbank.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.diagnostics.PerformanceCsvReader;
import au.edu.eq.questionbank.diagnostics.PerformanceHistoryStore;

class PerformanceHistoryReportToolTest {

	@TempDir
	Path tempDir;

	@Test
	void existingFileCannotBeUsedAsAnOutputDirectory() throws Exception {
		Path history = createHistory();
		Path alreadyFile = tempDir.resolve("not-a-directory.html");
		Files.writeString(alreadyFile, "do not replace");
		Clock clock = Clock.systemUTC();
		assertThrows(Exception.class,
				() -> PerformanceHistoryReportTool.generateInDirectory(history, alreadyFile, clock));
		assertEquals("do not replace", Files.readString(alreadyFile));
	}

	@Test
	void generatesUniqueTimestampedReportsWithoutReplacingExistingFile() throws Exception {
		Path history = createHistory();
		Path directory = tempDir.resolve("Exam Performance Reports");
		Clock clock = Clock.fixed(Instant.parse("2026-10-24T07:24:00Z"), ZoneId.of("Australia/Brisbane"));
		Path first = PerformanceHistoryReportTool.generateInDirectory(history, directory, clock);
		String firstContent = Files.readString(first);
		Path second = PerformanceHistoryReportTool.generateInDirectory(history, directory, clock);
		Path third = PerformanceHistoryReportTool.generateInDirectory(history, directory, clock);
		assertEquals("performance-history-2026-10-24-17-24-00.html", first.getFileName().toString());
		assertEquals("performance-history-2026-10-24-17-24-00-001.html", second.getFileName().toString());
		assertEquals("performance-history-2026-10-24-17-24-00-002.html", third.getFileName().toString());
		assertEquals(firstContent, Files.readString(first));
		assertTrue(Files.readString(second).contains("dashboard.refresh"));
		assertTrue(Files.readString(third).contains("<svg"));
	}

	@Test
	void legacyOnlyReportUsesTimestampedOutputWithoutOverwritingCsv() throws Exception {
		Path original = tempDir.resolve("performance.csv");
		String csv = "timestamp,operation_id,parent_id,operation,elapsed_ms,success,result_count\n"
				+ "2026-09-01T00:00:00Z,1,0,dashboard.refresh,70.0,true,1\n";
		Files.writeString(original, csv);
		Clock clock = Clock.fixed(Instant.parse("2026-10-25T00:00:00Z"), ZoneId.of("Australia/Brisbane"));
		Path output = PerformanceHistoryReportTool.generateInDirectory(tempDir.resolve("missing-modern-history"),
				tempDir.resolve("reports"), clock, original);
		assertEquals("performance-history-2026-10-25-10-00-00.html", output.getFileName().toString());
		assertTrue(Files.readString(output).contains("Legacy performance.csv (unattributed)"));
		assertEquals(csv, Files.readString(original));
	}

	@Test
	void missingHistoryNeverCreatesAReportFile() throws Exception {
		Path directory = tempDir.resolve("output");
		Clock clock = Clock.fixed(Instant.parse("2026-10-24T07:24:00Z"), ZoneId.of("UTC"));
		assertThrows(java.io.IOException.class,
				() -> PerformanceHistoryReportTool.generateInDirectory(tempDir.resolve("missing"), directory, clock));
		try (var files = Files.list(directory)) {
			assertFalse(files.anyMatch(Files::isRegularFile));
		}
	}

	private Path createHistory() throws Exception {
		Path history = tempDir.resolve("history");
		UUID machine = UUID.randomUUID();
		UUID session = UUID.randomUUID();
		new PerformanceHistoryStore().writeCheckpoint(history,
				new PerformanceHistoryStore.Checkpoint(machine, session, 0, Instant.parse("2026-10-24T00:00:00Z"),
						"0.6", "eclipse", 24, 800, 819200),
				List.of(new PerformanceCsvReader.Entry(Instant.parse("2026-10-24T00:00:01Z"), session, 1, 0,
						"dashboard.refresh", 120.0, true, 1)));
		return history;
	}
}
