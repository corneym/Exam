package au.edu.eq.questionbank.diagnostics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Samples routine corpus growth on a bounded background worker.
 *
 * Checkpoints are raw session metadata, not permanent performance summaries.
 * They must not be pruned until successful historical aggregation.
 */
public final class PerformanceCorpusCheckpointRecorder implements AutoCloseable {

	/**
	 * Number of successful Question saves between routine corpus checkpoints.
	 */
	public static final int QUESTION_SAVES_PER_CHECKPOINT = 8;
	private static final String HEADER = "requested_at,captured_at,machine_id,session_id,"
			+ "checkpoint_index,reason,successful_question_saves," + "schema_version,question_count,answer_count,"
			+ "question_content_parts,shared_context_content_parts," + "database_bytes";
	private final Path databasePath;
	private final Path diagnosticsDirectory;
	private final AtomicLong failedCheckpoints = new AtomicLong();
	private final Object lifecycleLock = new Object();
	private final UUID sessionId;
	private final ThreadPoolExecutor worker;
	private boolean closed;
	private int nextCheckpointIndex;
	private boolean started;
	private long successfulQuestionSaves;

	/**
	 * Creates a checkpoint collector without performing file I/O.
	 *
	 * @param diagnosticsDirectory diagnostics root, outside the Question data root
	 * @param databasePath         existing SQLite database path
	 * @param sessionId            unique enabled-recorder session ID
	 */
	public PerformanceCorpusCheckpointRecorder(Path diagnosticsDirectory, Path databasePath, UUID sessionId) {
		this.diagnosticsDirectory = Objects.requireNonNull(diagnosticsDirectory, "diagnosticsDirectory");
		this.databasePath = Objects.requireNonNull(databasePath, "databasePath");
		this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
		this.worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16), task -> {
			Thread thread = new Thread(task, "eqb-corpus-checkpoints");
			thread.setDaemon(true);
			return thread;
		}, new ThreadPoolExecutor.AbortPolicy());
	}

	/**
	 * Attempts to drain pending checkpoints and capture a final sample.
	 */
	@Override
	public void close() {
		finish(true);
	}

	/**
	 * Closes without attributing a newly restored database to the preceding
	 * application session.
	 */
	public void closeWithoutFinalSample() {
		finish(false);
	}

	/**
	 * Returns the number of rejected or failed checkpoint requests.
	 *
	 * @return checkpoint failure count
	 */
	public long failedCheckpointCount() {
		return failedCheckpoints.get();
	}

	/**
	 * Observes a successfully committed Question save.
	 *
	 * This method performs no database or filesystem I/O on the calling thread.
	 */
	public void questionSaved() {
		synchronized (lifecycleLock) {
			if (!started || closed) {
				return;
			}
			long saves = ++successfulQuestionSaves;
			if (saves % QUESTION_SAVES_PER_CHECKPOINT == 0) {
				enqueue("question-save-8", saves);
			}
		}
	}

	/**
	 * Starts the session and queues its baseline checkpoint once.
	 */
	public void start() {
		synchronized (lifecycleLock) {
			if (started || closed) {
				return;
			}
			started = true;
			enqueue("startup", 0);
		}
	}

	private void enqueue(String reason, long saves) {
		int index = nextCheckpointIndex++;
		Instant requestedAt = Instant.now();
		try {
			worker.execute(() -> writeCheckpoint(index, reason, saves, requestedAt));
		} catch (RejectedExecutionException failure) {
			failedCheckpoints.incrementAndGet();
			warn("Performance corpus checkpoint queue is full");
		}
	}

	private void finish(boolean includeFinalSample) {
		synchronized (lifecycleLock) {
			if (closed) {
				return;
			}
			closed = true;
			if (started && includeFinalSample) {
				enqueue("shutdown", successfulQuestionSaves);
			}
			worker.shutdown();
		}
		try {
			if (!worker.awaitTermination(8, TimeUnit.SECONDS)) {
				failedCheckpoints.incrementAndGet();
				warn("Performance corpus checkpoints did not finish " + "at shutdown");
			}
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			failedCheckpoints.incrementAndGet();
			warn("Performance corpus checkpoint shutdown was interrupted");
		}
	}

	private void warn(String message) {
		try {
			System.err.println(message);
		} catch (RuntimeException ignored) {

			// Diagnostic reporting must not affect application work.
		}
	}

	private void writeCheckpoint(int index, String reason, long saves, Instant requestedAt) {
		try {
			UUID machineId = PerformanceMachineIdentity.loadOrCreate(diagnosticsDirectory);
			PerformanceCorpusSampler.Sample sample = PerformanceCorpusSampler.capture(databasePath);
			Instant capturedAt = Instant.now();
			Path file = diagnosticsDirectory.resolve("sessions").resolve(sessionId.toString())
					.resolve("corpus-checkpoints.csv");
			Files.createDirectories(file.getParent());
			if (Files.notExists(file)) {
				Files.writeString(file, HEADER + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
			}
			String row = requestedAt + "," + capturedAt + "," + machineId + "," + sessionId + "," + index + "," + reason
					+ "," + saves + "," + sample.schemaVersion() + "," + sample.questionCount() + ","
					+ sample.answerCount() + "," + sample.questionContentPartCount() + ","
					+ sample.sharedContextContentPartCount() + "," + sample.databaseBytes() + "\n";
			Files.writeString(file, row, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
		} catch (IOException | SQLException | RuntimeException failure) {
			failedCheckpoints.incrementAndGet();
			warn("Performance corpus checkpoint failed: " + failure.getClass().getSimpleName());
		}
	}
}
