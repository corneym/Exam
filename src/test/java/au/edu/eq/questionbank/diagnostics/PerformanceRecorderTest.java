package au.edu.eq.questionbank.diagnostics;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PerformanceRecorderTest {

	@TempDir
	Path tempDir;

	private static void restoreProperty(String previous) {
		if (previous == null) {
			System.clearProperty("eqb.performance.enabled");
		} else {
			System.setProperty("eqb.performance.enabled", previous);
		}
	}

	@Test
	void childOperationRetainsParentIdentifier() throws Exception {
		Path output = tempDir.resolve("performance.csv");
		try (PerformanceRecorder recorder = new PerformanceRecorder(true, output)) {
			try (PerformanceOperation parent = recorder.start("dashboard.refresh")) {
				try (PerformanceOperation child = recorder.start("question.findAll", parent.id())) {
					child.resultCount(10);
				}
			}
			List<String> lines = Files.readAllLines(output);
			assertEquals(3, lines.size());
			String[] child = lines.get(1).split(",");
			String[] parent = lines.get(2).split(",");
			assertEquals(parent[1], child[2]);
		}
	}

	@Test
	void closingEnabledRecorderFlushesQueuedMeasurements() throws Exception {
		Path outputDirectory = tempDir.resolve("diagnostics");
		String previous = System.getProperty("eqb.performance.enabled");
		try {
			System.setProperty("eqb.performance.enabled", "true");
			PerformanceRecorder recorder = PerformanceRecorder.fromSystemProperties(outputDirectory);
			int measurements = 128;
			for (int i = 0; i < measurements; i++) {
				try (PerformanceOperation operation = recorder.start("question.save")) {
					operation.resultCount(i);
				}
			}
			recorder.close();
			recorder.close();
			Path sessionDirectory = outputDirectory.resolve("sessions").resolve(recorder.sessionId().toString());
			List<PerformanceCsvReader.Entry> entries = PerformanceCsvReader
					.read(sessionDirectory.resolve("operations.csv"));
			assertEquals(measurements, entries.size());
			assertEquals(0L, recorder.droppedRecordCount());
			assertEquals(0L, recorder.failedWriteCount());
			assertTrue(Files.isRegularFile(sessionDirectory.resolve("session.complete")));
		} finally {
			restoreProperty(previous);
		}
	}

	@Test
	void closingTwiceDoesNotDuplicateMeasurement() throws Exception {
		Path output = tempDir.resolve("performance.csv");
		try (PerformanceRecorder recorder = new PerformanceRecorder(true, output)) {
			PerformanceOperation operation = recorder.start("question.findById");
			operation.close();
			operation.close();
			assertEquals(2, Files.readAllLines(output).size());
		}
	}

	@Test
	void completionPreservesFinalResultCount() throws Exception {
		Path output = tempDir.resolve("performance.csv");
		try (PerformanceRecorder recorder = new PerformanceRecorder(true, output)) {
			PerformanceOperation operation = recorder.start("question.search");
			operation.resultCount(20);
			operation.close();

			// Updates after completion must not change the recorded result.
			operation.resultCount(50);
			operation.failed();
			operation.close();
			List<String> lines = Files.readAllLines(output);
			assertEquals(2, lines.size());
			assertTrue(lines.get(1).contains(",true,20"));
		}
	}

	@Test
	void concurrentCompletionProducesOneRecord() throws Exception {
		Path output = tempDir.resolve("performance.csv");
		try (PerformanceRecorder recorder = new PerformanceRecorder(true, output)) {
			PerformanceOperation operation = recorder.start("dashboard.refresh");
			int workers = 12;
			ExecutorService executor = Executors.newFixedThreadPool(workers);
			try {
				CountDownLatch ready = new CountDownLatch(workers);
				CountDownLatch start = new CountDownLatch(1);
				List<Future<?>> futures = new ArrayList<>();
				for (int i = 0; i < workers; i++) {
					futures.add(executor.submit(() -> {
						ready.countDown();
						start.await();
						operation.close();
						return null;
					}));
				}
				assertTrue(ready.await(5, TimeUnit.SECONDS));
				start.countDown();
				for (Future<?> future : futures) {
					future.get(5, TimeUnit.SECONDS);
				}
				List<String> lines = Files.readAllLines(output);
				assertEquals(2, lines.size());
			} finally {
				executor.shutdownNow();
			}
		}
	}

	@Test
	void diagnosticsAreDisabledByDefault() throws Exception {
		Path outputDirectory = tempDir.resolve("diagnostics");
		String previous = System.getProperty("eqb.performance.enabled");
		try {
			System.clearProperty("eqb.performance.enabled");
			try (PerformanceRecorder recorder = PerformanceRecorder.fromSystemProperties(outputDirectory)) {
				assertFalse(recorder.isEnabled());
				try (PerformanceOperation operation = recorder.start("application.startup")) {

					// Disabled diagnostics must return the no-op operation.
					assertEquals(0L, operation.id());
				}
			}
			assertFalse(Files.exists(outputDirectory));
		} finally {
			restoreProperty(previous);
		}
	}

	@Test
	void diagnosticsCanBeEnabledBySystemProperty() throws Exception {
		Path outputDirectory = tempDir.resolve("diagnostics");
		String previous = System.getProperty("eqb.performance.enabled");
		try {
			System.setProperty("eqb.performance.enabled", "true");
			PerformanceRecorder recorder = PerformanceRecorder.fromSystemProperties(outputDirectory);
			UUID sessionId = recorder.sessionId();
			try {
				assertTrue(recorder.isEnabled());
				assertNotNull(sessionId);
				try (PerformanceOperation operation = recorder.start("application.startup")) {
					operation.resultCount(1);
				}
			} finally {
				recorder.close();
			}
			Path sessionDirectory = outputDirectory.resolve("sessions").resolve(sessionId.toString());
			Path csv = sessionDirectory.resolve("operations.csv");
			Path metadataFile = sessionDirectory.resolve("session.properties");
			assertTrue(Files.isRegularFile(csv));
			assertTrue(Files.isRegularFile(metadataFile));
			assertTrue(Files.isRegularFile(sessionDirectory.resolve("session.complete")));
			assertFalse(Files.exists(outputDirectory.resolve("performance.csv")));
			List<String> lines = Files.readAllLines(csv);
			assertEquals(2, lines.size());
			assertTrue(lines.get(0).contains("session_id"));
			PerformanceCsvReader.Entry entry = PerformanceCsvReader.read(csv).get(0);
			assertEquals(sessionId, entry.sessionId());
			assertEquals("application.startup", entry.operation());
			assertEquals(1, entry.resultCount());
			Properties metadata = new Properties();
			try (InputStream input = Files.newInputStream(metadataFile)) {
				metadata.load(input);
			}
			assertEquals(sessionId.toString(), metadata.getProperty("session.id"));
			assertNotNull(metadata.getProperty("session.startedAt"));
			assertNotNull(metadata.getProperty("application.version"));
		} finally {
			restoreProperty(previous);
		}
	}

	@Test
	void diagnosticsWriteFailureDoesNotInterruptOperation() throws Exception {

		// A directory cannot be written as a CSV file.
		Path output = Files.createDirectory(tempDir.resolve("performance.csv"));
		try (PerformanceRecorder recorder = new PerformanceRecorder(true, output)) {
			String result = assertDoesNotThrow(() -> recorder.measure("question.findAll", () -> "operation completed"));
			assertEquals("operation completed", result);
			assertTrue(Files.isDirectory(output));
		}
	}

	@Test
	void disabledRecorderCreatesNoFile() {
		Path output = tempDir.resolve("performance.csv");
		try (PerformanceRecorder recorder = new PerformanceRecorder(false, output)) {
			try (PerformanceOperation operation = recorder.start("question.findAll")) {
				operation.resultCount(100);
			}
		}
		assertFalse(Files.exists(output));
	}

	@Test
	void enabledRecorderWritesSuccessfulMeasurement() throws Exception {
		Path output = tempDir.resolve("performance.csv");
		try (PerformanceRecorder recorder = new PerformanceRecorder(true, output)) {
			try (PerformanceOperation operation = recorder.start("question.findAll")) {
				operation.resultCount(42);
			}
			List<String> lines = Files.readAllLines(output);
			assertEquals(2, lines.size());
			assertTrue(lines.get(0).contains("operation_id"));
			assertTrue(lines.get(1).contains("question.findAll"));
			assertTrue(lines.get(1).contains(",true,42"));
		}
	}

	@Test
	void explicitSessionFactoryDoesNotDependOnJvmOverride() throws Exception {
		Path diagnostics = tempDir.resolve("explicit-diagnostics");
		String previous = System.getProperty("eqb.performance.enabled");
		try {
			System.setProperty("eqb.performance.enabled", "false");
			try (PerformanceRecorder disabled = PerformanceRecorder.forSession(diagnostics, false)) {
				assertFalse(disabled.isEnabled());
				try (PerformanceOperation operation = disabled.start("overhead.test")) {
					assertEquals(0, operation.id());

					// Disabled recording must not create diagnostic files.
				}
			}
			assertFalse(Files.exists(diagnostics));
			PerformanceRecorder enabled = PerformanceRecorder.forSession(diagnostics, true);
			try (enabled) {
				assertTrue(enabled.isEnabled());
				try (PerformanceOperation operation = enabled.start("overhead.test")) {
					operation.resultCount(1);
				}
			}
			assertEquals(0, enabled.droppedRecordCount());
			assertEquals(0, enabled.failedWriteCount());
			Path session = diagnostics.resolve("sessions").resolve(enabled.sessionId().toString());
			assertTrue(Files.isRegularFile(session.resolve("session.complete")));
			assertEquals(1, PerformanceCsvReader.readSession(session).size());
		} finally {
			restoreProperty(previous);
		}
	}

	@Test
	void failureIsRecorded() throws Exception {
		Path output = tempDir.resolve("performance.csv");
		try (PerformanceRecorder recorder = new PerformanceRecorder(true, output)) {
			try (PerformanceOperation operation = recorder.start("question.save")) {
				operation.failed();
			}
			String contents = Files.readString(output);
			assertTrue(contents.contains(",false,-1"));
		}
	}

	@Test
	void legacyRecorderRemainsReadable() throws Exception {
		Path output = tempDir.resolve("performance.csv");
		try (PerformanceRecorder recorder = new PerformanceRecorder(true, output)) {
			try (PerformanceOperation operation = recorder.start("question.findAll")) {
				operation.resultCount(42);
			}
		}
		List<PerformanceCsvReader.Entry> entries = PerformanceCsvReader.read(output);
		assertEquals(1, entries.size());
		PerformanceCsvReader.Entry entry = entries.get(0);
		assertNull(entry.sessionId());
		assertEquals("question.findAll", entry.operation());
		assertEquals(42, entry.resultCount());
		assertTrue(entry.success());
		assertEquals(PerformanceRecorder.HEADER, Files.readAllLines(output).get(0));
	}

	@Test
	void measurePreservesOriginalException() throws Exception {
		Path output = tempDir.resolve("performance.csv");
		try (PerformanceRecorder recorder = new PerformanceRecorder(true, output)) {
			IllegalStateException original = new IllegalStateException("Repository failed");
			IllegalStateException actual = assertThrows(IllegalStateException.class,
					() -> recorder.measure("question.findAll", () -> {
						throw original;
					}));
			assertSame(original, actual);
			String contents = Files.readString(output);
			assertTrue(contents.contains(",false,-1"));
		}
	}

	@Test
	void measureReturnsResultAndRecordsSuccess() throws Exception {
		Path output = tempDir.resolve("performance.csv");
		try (PerformanceRecorder recorder = new PerformanceRecorder(true, output)) {
			String result = recorder.measure("question.findById", () -> "found");
			assertEquals("found", result);
			List<String> lines = Files.readAllLines(output);
			assertEquals(2, lines.size());
			assertTrue(lines.get(1).contains("question.findById"));
			assertTrue(lines.get(1).contains(",true,-1"));
		}
	}

	@Test
	void packagedRuntimePropertyIsRecordedAsInstalled() throws Exception {
		String previousEnabled = System.getProperty("eqb.performance.enabled");
		String previousRuntime = System.getProperty("eqb.performance.runtimeKind");
		Path diagnostics = tempDir.resolve("packaged-runtime");
		try {
			System.setProperty("eqb.performance.enabled", "true");
			System.setProperty("eqb.performance.runtimeKind", "installed");
			UUID session;
			try (PerformanceRecorder recorder = PerformanceRecorder.fromSystemProperties(diagnostics)) {
				session = recorder.sessionId();
				try (PerformanceOperation operation = recorder.start("application.startup")) {
					operation.resultCount(1);
				}
			}
			Path metadata = diagnostics.resolve("sessions").resolve(session.toString()).resolve("session.properties");
			Properties properties = new Properties();
			try (InputStream in = Files.newInputStream(metadata)) {
				properties.load(in);
			}
			assertEquals("installed", properties.getProperty("runtime.kind"));
			assertEquals(session.toString(), properties.getProperty("session.id"));
		} finally {
			if (previousEnabled == null) {
				System.clearProperty("eqb.performance.enabled");
			} else {
				System.setProperty("eqb.performance.enabled", previousEnabled);
			}
			if (previousRuntime == null) {
				System.clearProperty("eqb.performance.runtimeKind");
			} else {
				System.setProperty("eqb.performance.runtimeKind", previousRuntime);
			}
		}
	}

	@Test
	void unsupportedCsvHeaderIsRejected() throws Exception {
		Path output = tempDir.resolve("invalid.csv");
		Files.writeString(output, "unknown,header" + System.lineSeparator());
		assertThrows(IOException.class, () -> PerformanceCsvReader.read(output));
	}
}
