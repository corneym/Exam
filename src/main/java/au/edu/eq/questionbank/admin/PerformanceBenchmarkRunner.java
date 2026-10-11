package au.edu.eq.questionbank.admin;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Executes repeatable application-level benchmark workloads.
 *
 * Warm-up executions are excluded from measurements. Each workload returns its
 * result count so inconsistent results can be detected.
 */
public final class PerformanceBenchmarkRunner {

	private final LongSupplier nanoClock;

	/**
	 * Creates a benchmark runner using the monotonic system clock.
	 */
	public PerformanceBenchmarkRunner() {
		this(System::nanoTime);
	}

	PerformanceBenchmarkRunner(LongSupplier nanoClock) {
		this.nanoClock = Objects.requireNonNull(nanoClock);
	}

	/**
	 * Executes warm-up and measured iterations of a workload.
	 *
	 * @param operation          stable benchmark identifier
	 * @param warmupIterations   number of unmeasured executions, at least one
	 * @param measuredIterations number of measured executions, at least one
	 * @param workload           operation returning the number of results produced
	 * @return benchmark summary and individual measured durations
	 * @throws Exception if the workload fails
	 */
	public PerformanceBenchmarkResult run(String operation, int warmupIterations, int measuredIterations,
			Workload workload) throws Exception {

		if (operation == null || !operation.matches("[a-zA-Z0-9._-]+")) {
			throw new IllegalArgumentException("Invalid benchmark operation");
		}
		if (warmupIterations < 1) {
			throw new IllegalArgumentException("At least one warm-up iteration is required");
		}
		if (measuredIterations < 1) {
			throw new IllegalArgumentException("At least one measured iteration is required");
		}

		Objects.requireNonNull(workload, "workload");

		// Warm-up exercises the real workload without starting the timer.
		for (int iteration = 0; iteration < warmupIterations; iteration++) {
			workload.execute();
		}

		List<Long> measurements = new ArrayList<>(measuredIterations);
		long expectedResultCount = -1;

		for (int iteration = 0; iteration < measuredIterations; iteration++) {
			long started = nanoClock.getAsLong();

			// Preserve the original workload exception. A failed execution
			// cannot produce a valid completed benchmark summary.
			long resultCount = workload.execute();

			long elapsed = nanoClock.getAsLong() - started;

			if (elapsed < 0) {
				throw new IllegalStateException("Benchmark clock produced a negative duration");
			}
			if (resultCount < 0) {
				throw new IllegalStateException("Benchmark returned a negative result count");
			}

			if (expectedResultCount == -1) {
				expectedResultCount = resultCount;
			} else if (resultCount != expectedResultCount) {

				// A changing result set invalidates a like-for-like
				// performance comparison.
				throw new IllegalStateException(
						"Benchmark result count changed from " + expectedResultCount + " to " + resultCount);
			}

			measurements.add(elapsed);
		}

		return PerformanceBenchmarkResult.fromMeasurements(operation, warmupIterations, expectedResultCount,
				measurements);
	}

	/**
	 * A benchmark operation that returns the number of results produced.
	 *
	 * Implementations may use production repositories and services.
	 */
	@FunctionalInterface
	public interface Workload {

		/**
		 * Executes one complete workload.
		 *
		 * @return non-negative result count
		 * @throws Exception if the workload fails
		 */
		long execute() throws Exception;
	}
}
