package au.edu.eq.questionbank.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javafx.animation.AnimationTimer;

/**
 * Measures the JavaFX pulse interval spanning completion of a synchronous
 * imported-Question capture entry.
 *
 * The probe runs only while performance diagnostics are enabled. Its
 * result_count is a duration in microseconds, not a row count.
 *
 * All lifecycle methods other than hasObservedPulse() are used on the JavaFX
 * Application Thread.
 */
public final class FxPulseGapProbe extends AnimationTimer {

	private static final String OPERATION = "capture.question.imported.fx.pulse-gap-us";
	private final List<Long> pendingEntryIds = new ArrayList<>();
	private final PerformanceRecorder recorder;
	private volatile long previousPulseNanos;

	/**
	 * Creates a pulse monitor using the supplied recorder.
	 *
	 * @param recorder enabled performance recorder
	 */
	public FxPulseGapProbe(PerformanceRecorder recorder) {
		this.recorder = Objects.requireNonNull(recorder, "recorder");
	}

	/**
	 * Processes an actual JavaFX animation pulse.
	 *
	 * @param now JavaFX monotonic pulse timestamp in nanoseconds
	 */
	@Override
	public void handle(long now) {
		long previous = previousPulseNanos;
		previousPulseNanos = now;
		if (pendingEntryIds.isEmpty()) {
			return;
		}
		if (previous > 0) {

			// The synchronous entry prevented intervening JavaFX
			// pulses. Measure the actual preceding-to-current gap.
			long gapMicros = Math.max(0, (now - previous) / 1_000);
			for (Long parentId : pendingEntryIds) {
				try (PerformanceOperation measurement = recorder.start(OPERATION, parentId)) {
					measurement.resultCount(gapMicros);
				}
			}
		}

		// If no preceding pulse exists, omit the sample rather
		// than recording a misleading zero-duration interval.
		pendingEntryIds.clear();
	}

	/**
	 * Reports whether at least one JavaFX pulse has been observed.
	 *
	 * @return true after the initial pulse
	 */
	public boolean hasObservedPulse() {
		return previousPulseNanos != 0;
	}

	/**
	 * Marks an imported-capture entry for measurement at the next pulse.
	 *
	 * @param parentOperationId imported-entry diagnostic operation ID
	 */
	public void recordEntryCompletion(long parentOperationId) {
		if (parentOperationId > 0) {
			pendingEntryIds.add(parentOperationId);
		}
	}
}
