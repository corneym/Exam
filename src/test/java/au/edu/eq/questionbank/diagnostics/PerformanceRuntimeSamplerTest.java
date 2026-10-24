package au.edu.eq.questionbank.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PerformanceRuntimeSamplerTest {

	@TempDir
	Path tempDir;

	@Test
	void disabledRecordingCreatesNoResourceFiles() throws Exception {
		Path csv = tempDir.resolve("disabled.csv");
		try (PerformanceRecorder recorder = new PerformanceRecorder(false, csv);
				PerformanceRuntimeSampler sampler = new PerformanceRuntimeSampler(recorder)) {
			sampler.start();
			sampler.sampleNow();
		}
		assertFalse(Files.exists(csv));
	}

	@Test
	void recordsHeapAndGarbageCollectionDeltas() throws Exception {
		Path csv = tempDir.resolve("performance.csv");
		try (PerformanceRecorder recorder = new PerformanceRecorder(true, csv);
				PerformanceRuntimeSampler sampler = new PerformanceRuntimeSampler(recorder)) {
			sampler.sampleNow();
			sampler.sampleNow();
			assertEquals(0, sampler.failureCount());
		}
		String contents = Files.readString(csv);
		assertTrue(contents.contains("runtime.heap.used-kib"));
		assertTrue(contents.contains("runtime.heap.committed-kib"));
		assertTrue(contents.contains("runtime.gc.collection-count-delta"));
		assertTrue(contents.contains("runtime.gc.collection-time-ms-delta"));
	}

	@Test
	void stoppingSamplerIsIdempotent() {
		Path csv = tempDir.resolve("unused.csv");
		try (PerformanceRecorder recorder = new PerformanceRecorder(false, csv);
				PerformanceRuntimeSampler sampler = new PerformanceRuntimeSampler(recorder)) {
			sampler.close();
			sampler.close();
			sampler.start();
		}
		assertFalse(Files.exists(csv));
	}
}
