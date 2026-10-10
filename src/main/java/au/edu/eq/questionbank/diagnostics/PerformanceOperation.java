package au.edu.eq.questionbank.diagnostics;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A timed application operation.
 *
 * An operation may be completed on a different thread from the one that started
 * it, allowing end-to-end asynchronous UI measurements.
 */
public final class PerformanceOperation implements AutoCloseable {

	private static final PerformanceOperation DISABLED = new PerformanceOperation();
	private final PerformanceRecorder recorder;
	private final long id;
	private final long parentId;
	private final String name;
	private final long startNanos;
	private final AtomicBoolean completed;
	private volatile long resultCount = -1;
	private volatile boolean success = true;

	PerformanceOperation(PerformanceRecorder recorder, long id, long parentId, String name, long startNanos) {
		this.recorder = recorder;
		this.id = id;
		this.parentId = parentId;
		this.name = name;
		this.startNanos = startNanos;
		this.completed = new AtomicBoolean();
	}

	private PerformanceOperation() {
		recorder = null;
		id = 0;
		parentId = 0;
		name = "";
		startNanos = 0;
		completed = new AtomicBoolean(true);
	}

	static PerformanceOperation disabled() {
		return DISABLED;
	}

	/**
	 * Completes the measurement. Multiple calls are harmless.
	 */
	@Override
	public synchronized void close() {
		if (recorder == null || !completed.compareAndSet(false, true)) {
			return;
		}
		recorder.record(id, parentId, name, System.nanoTime() - startNanos, success, resultCount);
	}

	/**
	 * Marks this operation unsuccessful.
	 */
	public synchronized void failed() {
		if (recorder != null && !completed.get()) {
			success = false;
		}
	}

	/**
	 * Gets the operation correlation identifier.
	 *
	 * @return identifier, or zero when disabled
	 */
	public long id() {
		return id;
	}

	/**
	 * Records the number of results produced.
	 *
	 * @param count non-negative result count
	 * @return this operation
	 */
	public synchronized PerformanceOperation resultCount(long count) {
		if (count < 0) {
			throw new IllegalArgumentException("Result count must be non-negative");
		}
		if (recorder != null && !completed.get()) {
			resultCount = count;
		}
		return this;
	}
}
