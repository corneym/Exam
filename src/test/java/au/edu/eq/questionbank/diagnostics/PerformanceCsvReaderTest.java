package au.edu.eq.questionbank.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PerformanceCsvReaderTest {

	@TempDir
	Path tempDir;

	@Test
	void originalSingleFileSessionRemainsReadable() throws Exception {
		UUID sessionId = UUID.randomUUID();
		Path session = tempDir.resolve(sessionId.toString());
		Files.createDirectories(session);
		Files.writeString(session.resolve("operations.csv"), PerformanceRecorder.SESSION_HEADER + "\n"
				+ "2026-10-24T00:00:01Z," + sessionId + ",1,0,question.save,25.0,true,1\n");
		List<PerformanceCsvReader.Entry> entries = PerformanceCsvReader.readSession(session);
		assertEquals(1, entries.size());
		assertEquals(sessionId, entries.getFirst().sessionId());
		assertEquals("question.save", entries.getFirst().operation());
	}

	@Test
	void readsAllRotatedSegmentsInTheirOriginalOrder() throws Exception {
		UUID sessionId = UUID.randomUUID();
		Path session = tempDir.resolve(sessionId.toString());
		Path output = session.resolve("operations.csv");
		PerformanceSessionWriter writer = new PerformanceSessionWriter(output, PerformanceRecorder.SESSION_HEADER,
				_ -> {
				}, 32, 220);
		for (int i = 1; i <= 7; i++) {
			String row = "2026-10-24T00:00:01Z," + sessionId + "," + i + ",0,dashboard.refresh,100.0,true,1\n";
			writer.submit(row);
		}
		writer.close();
		assertEquals(0, writer.failedWriteCount());
		List<Path> files = PerformanceCsvReader.sessionOperationFiles(session);
		assertTrue(files.size() > 1);
		List<PerformanceCsvReader.Entry> measurements = PerformanceCsvReader.readSession(session);
		assertEquals(7, measurements.size());
		for (int i = 0; i < measurements.size(); i++) {
			assertEquals(sessionId, measurements.get(i).sessionId());
			assertEquals(i + 1, measurements.get(i).operationId());
			assertEquals("dashboard.refresh", measurements.get(i).operation());
		}
	}
}
