package au.edu.eq.questionbank.diagnostics;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Samples lightweight JVM resource indicators while diagnostics are enabled. No
 * external telemetry or Question data is collected.
 */
public final class PerformanceRuntimeSampler implements AutoCloseable {

	/**
	 * Interval between routine JVM resource samples, in seconds.
	 */
	public static final long SAMPLE_INTERVAL_SECONDS = 60;
	private final AtomicLong failures = new AtomicLong();
	private final PerformanceRecorder recorder;
	private final ScheduledExecutorService scheduler;
	private long previousGcCount = -1;
	private long previousGcTimeMillis = -1;
	private boolean started;

	/**
	 * Creates a resource sampler for one recorder.
	 *
	 * @param recorder performance recorder
	 */
	public PerformanceRuntimeSampler(PerformanceRecorder recorder) {
		this.recorder = Objects.requireNonNull(recorder, "recorder");
		scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
			Thread thread = new Thread(task, "eqb-performance-resources");
			thread.setDaemon(true);
			return thread;
		});
	}

	/**
	 * Stops periodic sampling. Repeated closure is harmless.
	 */
	@Override
	public synchronized void close() {
		scheduler.shutdown();
		try {
			if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
				scheduler.shutdownNow();

				// The sampler may be stalled; never wait indefinitely
				// during application shutdown.
				if (!scheduler.awaitTermination(1, TimeUnit.SECONDS)) {
					failures.incrementAndGet();
				}
			}
		} catch (InterruptedException interrupted) {
			scheduler.shutdownNow();
			Thread.currentThread().interrupt();
			failures.incrementAndGet();
		}
	}

	/**
	 * Returns the number of unsuccessful resource samples.
	 *
	 * @return failure count
	 */
	public long failureCount() {
		return failures.get();
	}

	/**
	 * Records one resource sample synchronously on the calling thread. Normal
	 * application usage invokes this method on the scheduler.
	 */
	public synchronized void sampleNow() {
		if (!recorder.isEnabled() || scheduler.isShutdown()) {
			return;
		}
		try {
			Runtime runtime = Runtime.getRuntime();
			long usedBytes = runtime.totalMemory() - runtime.freeMemory();
			metric("runtime.heap.used-kib", usedBytes / 1024);
			metric("runtime.heap.committed-kib", runtime.totalMemory() / 1024);
			long gcCount = 0;
			long gcTimeMillis = 0;
			List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
			for (GarbageCollectorMXBean collector : collectors) {
				long count = collector.getCollectionCount();
				long time = collector.getCollectionTime();
				if (count >= 0) {
					gcCount += count;
				}
				if (time >= 0) {
					gcTimeMillis += time;
				}
			}
			if (previousGcCount >= 0) {
				metric("runtime.gc.collection-count-delta", Math.max(0, gcCount - previousGcCount));
			}
			if (previousGcTimeMillis >= 0) {
				metric("runtime.gc.collection-time-ms-delta", Math.max(0, gcTimeMillis - previousGcTimeMillis));
			}
			previousGcCount = gcCount;
			previousGcTimeMillis = gcTimeMillis;
		} catch (RuntimeException failure) {
			failures.incrementAndGet();
		}
	}

	/**
	 * Starts periodic collection once.
	 */
	public synchronized void start() {
		if (started || !recorder.isEnabled() || scheduler.isShutdown()) {
			return;
		}
		started = true;
		scheduler.scheduleAtFixedRate(this::sampleNow, 0, SAMPLE_INTERVAL_SECONDS, TimeUnit.SECONDS);
	}

	private void metric(String name, long value) {
		try (PerformanceOperation operation = recorder.start(name)) {
			operation.resultCount(value);
		}
	}
}
