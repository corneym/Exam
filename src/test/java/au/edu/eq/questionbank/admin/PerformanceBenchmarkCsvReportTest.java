package au.edu.eq.questionbank.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PerformanceBenchmarkCsvReportTest {

	@TempDir
	Path tempDirectory;

	@Test
	void refusesToOverwriteExistingReport() throws Exception {
		Path destination = tempDirectory.resolve("existing.csv");
		Files.writeString(destination, "original data");
		PerformanceBenchmarkResult result = PerformanceBenchmarkResult.fromMeasurements("question.findAll", 1, 0,
				List.of(1_000_000L));
		PerformanceBenchmarkCsvReport.Context context = new PerformanceBenchmarkCsvReport.Context(Instant.now(), "test",
				tempDirectory.resolve("snapshot.db"), "fingerprint", 100, 0, 19, "3.53.2", "25");
		assertThrows(FileAlreadyExistsException.class, () -> PerformanceBenchmarkCsvReport.write(destination, context,
				List.of(new PerformanceBenchmarkCsvReport.Measurement("question.findAll", result))));
		assertEquals("original data", Files.readString(destination));
	}

	@Test
	void writesDocumentedHeaderAndMeasuredSamples() throws Exception {
		Path destination = tempDirectory.resolve("benchmark.csv");
		PerformanceBenchmarkResult result = PerformanceBenchmarkResult.fromMeasurements("question.findAll", 2, 624,
				List.of(1_000_000_000L, 1_100_000_000L, 1_200_000_000L));
		PerformanceBenchmarkCsvReport.Context context = new PerformanceBenchmarkCsvReport.Context(
				Instant.parse("2026-10-11T03:00:00Z"), "pre94-abc123", tempDirectory.resolve("snapshot,baseline.db"),
				"abc123def456", 819200, 624, 19, "3.53.2", "25");
		PerformanceBenchmarkCsvReport.write(destination, context,
				List.of(new PerformanceBenchmarkCsvReport.Measurement("question.findAll", result)));
		List<String> lines = Files.readAllLines(destination);
		assertEquals(2, lines.size());
		assertEquals(PerformanceBenchmarkCsvReport.HEADER, lines.getFirst());
		String row = lines.get(1);
		assertTrue(row.startsWith("2026-10-11T03:00:00Z,pre94-abc123,"));

		// A comma in a filesystem path must not split the CSV field.
		assertTrue(row.contains("\"" + context.snapshotPath() + "\""));
		assertTrue(row.contains(",abc123def456,819200,624,19,3.53.2,25,"));
		assertTrue(row.contains(",question.findAll,2,3,624,"));
		assertTrue(row.contains(",1000.000,1100.000,1200.000,1200.000,"));
		assertTrue(row.endsWith("1000000000;1100000000;1200000000"));
	}

	@Test
	void writesOneRowPerCompletedOperation() throws Exception {
		Path destination = tempDirectory.resolve("multiple.csv");
		PerformanceBenchmarkResult result = PerformanceBenchmarkResult.fromMeasurements("question.findById", 1, 1,
				List.of(2_000_000L, 3_000_000L));
		PerformanceBenchmarkCsvReport.Context context = new PerformanceBenchmarkCsvReport.Context(
				Instant.parse("2026-10-11T03:00:00Z"), "post94-abc123", tempDirectory.resolve("snapshot.db"),
				"fingerprint", 1000, 10, 19, "3.53.2", "25");
		PerformanceBenchmarkCsvReport.write(destination, context,
				List.of(new PerformanceBenchmarkCsvReport.Measurement("question.findById:10", result),
						new PerformanceBenchmarkCsvReport.Measurement("question.findById:11", result)));
		List<String> lines = Files.readAllLines(destination);
		assertEquals(3, lines.size());
		assertTrue(lines.get(1).contains("question.findById:10"));
		assertTrue(lines.get(2).contains("question.findById:11"));
	}
}
