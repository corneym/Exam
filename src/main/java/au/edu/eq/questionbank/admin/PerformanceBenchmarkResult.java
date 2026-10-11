package au.edu.eq.questionbank.admin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable timing summary for a repeatable benchmark workload.
 *
 * @param operation          stable benchmark identifier
 * @param warmupIterations   number of unmeasured warm-up executions
 * @param resultCount        results returned by each measured execution
 * @param elapsedNanos       individual measured durations in execution order
 * @param minMilliseconds    minimum measured duration
 * @param medianMilliseconds median measured duration
 * @param p90Milliseconds    nearest-rank 90th-percentile duration
 * @param maxMilliseconds    maximum measured duration
 */
public record PerformanceBenchmarkResult(String operation, int warmupIterations, long resultCount,
		List<Long> elapsedNanos, double minMilliseconds, double medianMilliseconds, double p90Milliseconds,
		double maxMilliseconds) {

	public PerformanceBenchmarkResult {
		if (operation == null || !operation.matches("[a-zA-Z0-9._-]+")) {
			throw new IllegalArgumentException("Invalid benchmark operation");
		}
		if (warmupIterations < 1) {
			throw new IllegalArgumentException("At least one warm-up iteration is required");
		}
		if (resultCount < 0) {
			throw new IllegalArgumentException("Result count must be non-negative");
		}

		elapsedNanos = List.copyOf(elapsedNanos);

		if (elapsedNanos.isEmpty()) {
			throw new IllegalArgumentException("At least one measured iteration is required");
		}

		for (long elapsed : elapsedNanos) {
			if (elapsed < 0) {
				throw new IllegalArgumentException("Elapsed durations must be non-negative");
			}
		}

		if (!Double.isFinite(minMilliseconds) || !Double.isFinite(medianMilliseconds)
				|| !Double.isFinite(p90Milliseconds) || !Double.isFinite(maxMilliseconds) || minMilliseconds < 0
				|| minMilliseconds > medianMilliseconds || medianMilliseconds > p90Milliseconds
				|| p90Milliseconds > maxMilliseconds) {
			throw new IllegalArgumentException("Invalid benchmark timing statistics");
		}
	}

	/**
	 * Constructs a summary from individual monotonic-clock measurements.
	 *
	 * Median is the mean of the central pair for an even sample count. P90 uses the
	 * nearest-rank definition.
	 *
	 * @param operation        stable benchmark identifier
	 * @param warmupIterations unmeasured iteration count
	 * @param resultCount      number of results per measured iteration
	 * @param measuredNanos    measured durations in nanoseconds
	 * @return immutable benchmark result
	 */
	public static PerformanceBenchmarkResult fromMeasurements(String operation, int warmupIterations, long resultCount,
			List<Long> measuredNanos) {

		List<Long> samples = List.copyOf(measuredNanos);

		if (samples.isEmpty()) {
			throw new IllegalArgumentException("At least one measured iteration is required");
		}

		for (long sample : samples) {
			if (sample < 0) {
				throw new IllegalArgumentException("Measured durations must be non-negative");
			}
		}

		List<Long> sorted = new ArrayList<>(samples);
		Collections.sort(sorted);

		int size = sorted.size();

		double minimum = sorted.getFirst() / 1_000_000.0;
		double maximum = sorted.getLast() / 1_000_000.0;

		// Convert to floating point before adding the middle values
		// to avoid overflow when calculating an even-sample median.
		double middle = ((double) sorted.get((size - 1) / 2) + sorted.get(size / 2)) / 2.0;

		double median = middle / 1_000_000.0;

		// Nearest-rank p90 selects an observed measurement.
		int p90Index = (int) Math.ceil(size * 0.90) - 1;
		double p90 = sorted.get(p90Index) / 1_000_000.0;

		return new PerformanceBenchmarkResult(operation, warmupIterations, resultCount, samples, minimum, median, p90,
				maximum);
	}

	/**
	 * Returns the number of measured executions.
	 *
	 * @return measured iteration count
	 */
	public int measuredIterations() {
		return elapsedNanos.size();
	}
}
