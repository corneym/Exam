package au.edu.eq.questionbank.diagnostics;

import java.util.Objects;

import javafx.animation.AnimationTimer;

/**
 * Observes long intervals between JavaFX animation pulses.
 *
 * A pulse gap is a responsiveness indicator, not proof that a particular
 * application method blocked the JavaFX thread.
 */
public final class PerformanceFxStallProbe extends AnimationTimer {

	/**
	 * Minimum JavaFX pulse interval considered a responsiveness gap: 100
	 * milliseconds, expressed in nanoseconds.
	 */
	public static final long GAP_THRESHOLD_NANOS = 100_000_000L;
	private static final long MIN_REPORT_INTERVAL_NANOS = 5_000_000_000L;
	private final PerformanceRecorder recorder;
	private long lastRecordedNanos;
	private long previousPulseNanos;

	/**
	 * Creates a pulse-gap observer for an enabled application recorder.
	 *
	 * @param recorder application performance recorder
	 */
	public PerformanceFxStallProbe(PerformanceRecorder recorder) {
		this.recorder = Objects.requireNonNull(recorder, "recorder");
	}

	/**
	 * Processes one animation pulse.
	 *
	 * @param now JavaFX monotonic pulse timestamp, nanoseconds
	 */
	@Override
	public void handle(long now) {
		long previous = previousPulseNanos;
		previousPulseNanos = now;
		if (!recorder.isEnabled() || previous == 0 || now <= previous) {
			return;
		}
		long gap = now - previous;
		if (gap < GAP_THRESHOLD_NANOS) {
			return;
		}
		if (lastRecordedNanos != 0 && now - lastRecordedNanos < MIN_REPORT_INTERVAL_NANOS) {
			return;
		}
		lastRecordedNanos = now;
		try (PerformanceOperation operation = recorder.start("runtime.fx.pulse-gap-us")) {
			operation.resultCount(gap / 1_000);
		}
	}
}
