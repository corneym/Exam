package au.edu.eq.questionbank.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.Test;

class PerformanceBenchmarkRunnerTest {

	@Test
	void calculatesMedianAndNearestRankP90() {
		List<Long> samples = new ArrayList<>();

		for (long milliseconds = 1; milliseconds <= 10; milliseconds++) {
			samples.add(milliseconds * 1_000_000L);
		}

		PerformanceBenchmarkResult result = PerformanceBenchmarkResult.fromMeasurements("question.findAll", 1, 624,
				samples);

		assertEquals(10, result.measuredIterations());
		assertEquals(624, result.resultCount());
		assertEquals(1.0, result.minMilliseconds());
		assertEquals(5.5, result.medianMilliseconds());
		assertEquals(9.0, result.p90Milliseconds());
		assertEquals(10.0, result.maxMilliseconds());

		// A caller cannot modify recorded measurements afterwards.
		samples.clear();
		assertEquals(10, result.elapsedNanos().size());

		assertThrows(UnsupportedOperationException.class, () -> result.elapsedNanos().add(1L));
	}

	@Test
	void preservesOriginalWorkloadFailure() {
		IllegalStateException failure = new IllegalStateException("Repository read failed");

		AtomicInteger executions = new AtomicInteger();

		PerformanceBenchmarkRunner runner = new PerformanceBenchmarkRunner();

		IllegalStateException actual = assertThrows(IllegalStateException.class,
				() -> runner.run("question.findAll", 1, 3, () -> {
					if (executions.incrementAndGet() == 2) {
						throw failure;
					}
					return 10;
				}));

		assertSame(failure, actual);
		assertEquals(2, executions.get());
	}

	@Test
	void rejectsChangingResultCounts() {
		AtomicInteger executions = new AtomicInteger();

		PerformanceBenchmarkRunner runner = new PerformanceBenchmarkRunner(System::nanoTime);

		assertThrows(IllegalStateException.class, () -> runner.run("question.findAll", 1, 2, () -> {
			int execution = executions.incrementAndGet();

			// Warm-up and the first measured run return ten.
			// The second measured run returns eleven.
			return execution == 3 ? 11 : 10;
		}));

		assertEquals(3, executions.get());
	}

	@Test
	void rejectsInvalidMeasurementSamples() {
		assertThrows(IllegalArgumentException.class,
				() -> PerformanceBenchmarkResult.fromMeasurements("question.findAll", 1, 10, List.of()));

		assertThrows(IllegalArgumentException.class,
				() -> PerformanceBenchmarkResult.fromMeasurements("question.findAll", 1, 10, List.of(-1L)));
	}

	@Test
	void rejectsInvalidRunnerArgumentsBeforeRunningWorkload() {
		PerformanceBenchmarkRunner runner = new PerformanceBenchmarkRunner();

		AtomicInteger executions = new AtomicInteger();
		PerformanceBenchmarkRunner.Workload workload = () -> {
			executions.incrementAndGet();
			return 1;
		};

		assertThrows(IllegalArgumentException.class, () -> runner.run("invalid operation", 1, 1, workload));

		assertThrows(IllegalArgumentException.class, () -> runner.run("question.findAll", 0, 1, workload));

		assertThrows(IllegalArgumentException.class, () -> runner.run("question.findAll", 1, 0, workload));

		assertThrows(NullPointerException.class, () -> runner.run("question.findAll", 1, 1, null));

		assertEquals(0, executions.get());
	}

	@Test
	void rejectsNegativeResultCount() {
		AtomicInteger executions = new AtomicInteger();

		PerformanceBenchmarkRunner runner = new PerformanceBenchmarkRunner();

		assertThrows(IllegalStateException.class, () -> runner.run("question.findAll", 1, 1, () -> {
			return executions.incrementAndGet() == 1 ? 0 : -1;
		}));
	}

	@Test
	void singleMeasuredIterationHasIdenticalStatistics() {
		PerformanceBenchmarkResult result = PerformanceBenchmarkResult.fromMeasurements("question.findById", 1, 1,
				List.of(2_500_000L));

		assertEquals(2.5, result.minMilliseconds());
		assertEquals(2.5, result.medianMilliseconds());
		assertEquals(2.5, result.p90Milliseconds());
		assertEquals(2.5, result.maxMilliseconds());
	}

	@Test
	void warmupsAreExcludedFromMeasuredDurations() throws Exception {
		long[] clockValues = { 100L, 1_000_100L, 5_000_000L, 8_000_000L, 10_000_000L, 18_000_000L };

		AtomicInteger clockCalls = new AtomicInteger();

		LongSupplier clock = () -> clockValues[clockCalls.getAndIncrement()];

		AtomicInteger executions = new AtomicInteger();

		PerformanceBenchmarkRunner runner = new PerformanceBenchmarkRunner(clock);

		PerformanceBenchmarkResult result = runner.run("question.findAll", 1, 3, () -> {
			executions.incrementAndGet();
			return 624;
		});

		assertEquals(4, executions.get());
		assertEquals(6, clockCalls.get());
		assertEquals(1, result.warmupIterations());
		assertEquals(3, result.measuredIterations());
		assertEquals(624, result.resultCount());

		assertEquals(List.of(1_000_000L, 3_000_000L, 8_000_000L), result.elapsedNanos());

		assertEquals(1.0, result.minMilliseconds());
		assertEquals(3.0, result.medianMilliseconds());
		assertEquals(8.0, result.p90Milliseconds());
		assertEquals(8.0, result.maxMilliseconds());
	}
}
