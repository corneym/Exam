package au.edu.eq.questionbank.diagnostics;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Writes one diagnostic session using a bounded background queue.
 *
 * Producers never perform file I/O or wait for space in the queue.
 */
final class PerformanceSessionWriter implements AutoCloseable {

	static final int DEFAULT_CAPACITY = 2048;
	private static final long CLOSE_WAIT_MILLIS = 2000;
	private final AtomicLong droppedRecords = new AtomicLong();
	private final AtomicLong failedWrites = new AtomicLong();
	private final String header;
	private final SessionInitialiser initialiser;
	private final Object lifecycleLock = new Object();
	private final Path outputFile;
	private final BlockingQueue<String> queue;
	private final Thread worker;
	private volatile boolean closing;
	private boolean started;

	PerformanceSessionWriter(Path outputFile, String header, SessionInitialiser initialiser) {
		this(outputFile, header, initialiser, DEFAULT_CAPACITY);
	}

	PerformanceSessionWriter(Path outputFile, String header, SessionInitialiser initialiser, int capacity) {
		if (outputFile == null || header == null || initialiser == null || capacity < 1) {
			throw new IllegalArgumentException("Invalid session writer configuration");
		}
		this.outputFile = outputFile;
		this.header = header;
		this.initialiser = initialiser;
		this.queue = new ArrayBlockingQueue<>(capacity);
		this.worker = new Thread(this::runWriter, "eqb-performance-writer");
		this.worker.setDaemon(true);
	}

	@Override
	public void close() {
		synchronized (lifecycleLock) {
			closing = true;
			if (!started) {
				return;
			}
		}
		if (Thread.currentThread() == worker) {
			return;
		}
		try {
			worker.join(CLOSE_WAIT_MILLIS);
			if (worker.isAlive()) {
				warn("Performance diagnostic writer did not flush " + "before shutdown deadline");
			}
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			warn("Performance diagnostic writer shutdown " + "was interrupted");
		}
	}

	long droppedRecordCount() {
		return droppedRecords.get();
	}

	long failedWriteCount() {
		return failedWrites.get();
	}

	void submit(String line) {
		synchronized (lifecycleLock) {

			// Never block the JavaFX thread when recording is saturated.
			if (closing || !queue.offer(line)) {
				droppedRecords.incrementAndGet();
				return;
			}
			if (!started) {
				started = true;
				worker.start();
			}
		}
	}

	private BufferedWriter openWriter() throws IOException {
		Path parent = outputFile.toAbsolutePath().getParent();
		Files.createDirectories(parent);
		initialiser.initialise(parent);
		if (Files.notExists(outputFile)) {
			Files.writeString(outputFile, header + System.lineSeparator(), StandardCharsets.UTF_8,
					StandardOpenOption.CREATE_NEW);
		}
		return Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
	}

	private void runWriter() {
		BufferedWriter writer = null;
		try {
			while (!closing || !queue.isEmpty()) {
				String row = queue.poll(50, TimeUnit.MILLISECONDS);
				if (row == null) {
					continue;
				}
				try {
					if (writer == null) {
						writer = openWriter();
					}
					writer.write(row);
					writer.flush();
				} catch (IOException | RuntimeException failure) {
					failedWrites.incrementAndGet();
					warnOnFirstFailures(failure);
					if (writer != null) {
						try {
							writer.close();
						} catch (IOException closeFailure) {
							failedWrites.incrementAndGet();
						}
						writer = null;
					}
				}
			}
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			droppedRecords.addAndGet(queue.size());
			queue.clear();
		} finally {
			if (writer != null) {
				try {
					writer.close();
				} catch (IOException failure) {
					failedWrites.incrementAndGet();
					warnOnFirstFailures(failure);
				}
			}
			writeCompletionMarker();
		}
	}

	private void warn(String message) {
		try {
			System.err.println(message);
		} catch (RuntimeException ignored) {

			// Diagnostic reporting must remain non-fatal.
		}
	}

	private void warnOnFirstFailures(Exception failure) {
		if (failedWrites.get() <= 3) {
			warn("Performance diagnostic write failed: " + failure.getMessage());
		}
	}

	private void writeCompletionMarker() {

		// Absence of a completion marker identifies a potentially
		// interrupted session.
		if (!Files.isRegularFile(outputFile)) {
			return;
		}
		boolean complete = droppedRecordCount() == 0 && failedWriteCount() == 0;
		String marker = complete ? "session.complete" : "session.partial";
		String status = "endedAt=" + Instant.now() + System.lineSeparator() + "droppedRecords=" + droppedRecordCount()
				+ System.lineSeparator() + "failedWrites=" + failedWriteCount() + System.lineSeparator();
		try {
			Files.writeString(outputFile.getParent().resolve(marker), status, StandardCharsets.UTF_8,
					StandardOpenOption.CREATE_NEW);
		} catch (IOException | RuntimeException failure) {
			warn("Performance session finalisation failed: " + failure.getMessage());
		}
	}

	@FunctionalInterface
	interface SessionInitialiser {

		void initialise(Path sessionDirectory) throws IOException;
	}
}
