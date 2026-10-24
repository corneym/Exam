package au.edu.eq.questionbank.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PerformanceSessionWriterTest {

	@TempDir
	Path tempDir;

	@Test
	void boundedQueueDropsRecordsWithoutBlockingProducer() throws Exception {
		Path directory = tempDir.resolve("bounded");
		Path output = directory.resolve("operations.csv");
		CountDownLatch initialiserEntered = new CountDownLatch(1);
		CountDownLatch releaseInitialiser = new CountDownLatch(1);
		PerformanceSessionWriter writer = new PerformanceSessionWriter(output, "operation", _ -> {
			initialiserEntered.countDown();
			try {
				if (!releaseInitialiser.await(5, TimeUnit.SECONDS)) {
					throw new IOException("Initialiser was not released");
				}
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				throw new IOException("Initialiser interrupted", interrupted);
			}
		}, 2);
		try {
			writer.submit("first" + System.lineSeparator());
			assertTrue(initialiserEntered.await(5, TimeUnit.SECONDS));

			// The worker is blocked while opening the first file.
			// The bounded queue can therefore hold only two more rows.
			writer.submit("second" + System.lineSeparator());
			writer.submit("third" + System.lineSeparator());
			writer.submit("fourth" + System.lineSeparator());
			assertEquals(1L, writer.droppedRecordCount());
		} finally {
			releaseInitialiser.countDown();
			writer.close();
		}
		List<String> lines = Files.readAllLines(output);
		assertEquals(4, lines.size());
		assertEquals("first", lines.get(1));
		assertEquals("second", lines.get(2));
		assertEquals("third", lines.get(3));
		assertTrue(Files.isRegularFile(directory.resolve("session.partial")));
		assertEquals(1L, writer.droppedRecordCount());
		assertEquals(0L, writer.failedWriteCount());
	}

	@Test
	void noMeasurementsCreateNoFiles() {
		Path directory = tempDir.resolve("empty");
		Path output = directory.resolve("operations.csv");
		PerformanceSessionWriter writer = new PerformanceSessionWriter(output, "operation", _ -> {
		});
		writer.close();
		assertFalse(Files.exists(directory));
		assertEquals(0L, writer.droppedRecordCount());
		assertEquals(0L, writer.failedWriteCount());
	}

	@Test
	void shutdownDrainsAcceptedMeasurements() throws Exception {
		Path directory = tempDir.resolve("complete");
		Path output = directory.resolve("operations.csv");
		PerformanceSessionWriter writer = new PerformanceSessionWriter(output, "operation", _ -> {
		});
		for (int i = 0; i < 128; i++) {
			writer.submit("record-" + i + System.lineSeparator());
		}
		writer.close();
		List<String> lines = Files.readAllLines(output);
		assertEquals(129, lines.size());
		assertEquals("operation", lines.get(0));
		assertEquals("record-0", lines.get(1));
		assertEquals("record-127", lines.get(128));
		assertEquals(0L, writer.droppedRecordCount());
		assertEquals(0L, writer.failedWriteCount());
		assertTrue(Files.isRegularFile(directory.resolve("session.complete")));
	}

	@Test
	void writeFailureIsCountedWithoutPropagatingToProducer() throws Exception {
		Path directory = Files.createDirectory(tempDir.resolve("failure"));

		// A directory at the CSV path prevents opening it as a file.
		Path output = Files.createDirectory(directory.resolve("operations.csv"));
		PerformanceSessionWriter writer = new PerformanceSessionWriter(output, "operation", _ -> {
		});
		writer.submit("measurement" + System.lineSeparator());
		writer.close();
		assertEquals(1L, writer.failedWriteCount());
		assertEquals(0L, writer.droppedRecordCount());
		assertTrue(Files.isDirectory(output));
	}
}
