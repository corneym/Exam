package au.edu.eq.questionbank.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PerformanceRecorderTest {

	@TempDir
	Path tempDir;

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
}
