package au.edu.eq.questionbank.diagnostics;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
		PerformanceRecorder recorder = new PerformanceRecorder(true, output);
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

	@Test
	void closingTwiceDoesNotDuplicateMeasurement() throws Exception {
		Path output = tempDir.resolve("performance.csv");
		PerformanceRecorder recorder = new PerformanceRecorder(true, output);
		PerformanceOperation operation = recorder.start("question.findById");
		operation.close();
		operation.close();
		assertEquals(2, Files.readAllLines(output).size());
	}

	@Test
	void completionPreservesFinalResultCount() throws Exception {
		Path output = tempDir.resolve("performance.csv");
		PerformanceRecorder recorder = new PerformanceRecorder(true, output);
		PerformanceOperation operation = recorder.start("question.search");
		operation.resultCount(20);
		operation.close();

		// Late updates must not modify an already recorded result.
		operation.resultCount(50);
		operation.failed();
		operation.close();
		List<String> lines = Files.readAllLines(output);
		assertEquals(2, lines.size());
		assertTrue(lines.get(1).contains(",true,20"));
	}

	@Test
	void concurrentCompletionProducesOneRecord() throws Exception {
		Path output = tempDir.resolve("performance.csv");
		PerformanceRecorder recorder = new PerformanceRecorder(true, output);
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

	@Test
	void diagnosticsAreDisabledByDefault() throws Exception {
		Path outputDirectory = tempDir.resolve("diagnostics");
		String previous = System.getProperty("eqb.performance.enabled");
		try {
			System.clearProperty("eqb.performance.enabled");
			PerformanceRecorder recorder = PerformanceRecorder.fromSystemProperties(outputDirectory);
			assertFalse(recorder.isEnabled());
			try (PerformanceOperation operation = recorder.start("application.startup")) {

				// Disabled recording must not produce output.
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
			assertTrue(recorder.isEnabled());
			try (PerformanceOperation operation = recorder.start("application.startup")) {
				operation.resultCount(1);
			}
			Path csv = outputDirectory.resolve("performance.csv");
			assertTrue(Files.isRegularFile(csv));
			assertTrue(Files.readString(csv).contains("application.startup"));
		} finally {
			restoreProperty(previous);
		}
	}

	@Test
	void diagnosticsWriteFailureDoesNotInterruptOperation() throws Exception {

		// An existing directory cannot be written as a CSV file.
		Path output = Files.createDirectory(tempDir.resolve("performance.csv"));
		PerformanceRecorder recorder = new PerformanceRecorder(true, output);
		String result = assertDoesNotThrow(() -> recorder.measure("question.findAll", () -> "operation completed"));
		assertEquals("operation completed", result);
		assertTrue(Files.isDirectory(output));
	}

	@Test
	void disabledRecorderCreatesNoFile() {
		Path output = tempDir.resolve("performance.csv");
		PerformanceRecorder recorder = new PerformanceRecorder(false, output);
		try (PerformanceOperation operation = recorder.start("question.findAll")) {
			operation.resultCount(100);
		}
		assertFalse(Files.exists(output));
	}

	@Test
	void enabledRecorderWritesSuccessfulMeasurement() throws Exception {
		Path output = tempDir.resolve("performance.csv");
		PerformanceRecorder recorder = new PerformanceRecorder(true, output);
		try (PerformanceOperation operation = recorder.start("question.findAll")) {
			operation.resultCount(42);
		}
		List<String> lines = Files.readAllLines(output);
		assertEquals(2, lines.size());
		assertTrue(lines.get(0).contains("operation_id"));
		assertTrue(lines.get(1).contains("question.findAll"));
		assertTrue(lines.get(1).contains(",true,42"));
	}

	@Test
	void failureIsRecorded() throws Exception {
		Path output = tempDir.resolve("performance.csv");
		PerformanceRecorder recorder = new PerformanceRecorder(true, output);
		try (PerformanceOperation operation = recorder.start("question.save")) {
			operation.failed();
		}
		String contents = Files.readString(output);
		assertTrue(contents.contains(",false,-1"));
	}

	@Test
	void measurePreservesOriginalException() throws Exception {
		Path output = tempDir.resolve("performance.csv");
		PerformanceRecorder recorder = new PerformanceRecorder(true, output);
		IllegalStateException original = new IllegalStateException("Repository failed");
		IllegalStateException actual = assertThrows(IllegalStateException.class,
				() -> recorder.measure("question.findAll", () -> {
					throw original;
				}));
		assertSame(original, actual);
		String contents = Files.readString(output);
		assertTrue(contents.contains(",false,-1"));
	}

	@Test
	void measureReturnsResultAndRecordsSuccess() throws Exception {
		Path output = tempDir.resolve("performance.csv");
		PerformanceRecorder recorder = new PerformanceRecorder(true, output);
		String result = recorder.measure("question.findById", () -> "found");
		assertEquals("found", result);
		List<String> lines = Files.readAllLines(output);
		assertEquals(2, lines.size());
		assertTrue(lines.get(1).contains("question.findById"));
		assertTrue(lines.get(1).contains(",true,-1"));
	}
}
