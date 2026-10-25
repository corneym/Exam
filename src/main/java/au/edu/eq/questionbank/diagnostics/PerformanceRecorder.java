package au.edu.eq.questionbank.diagnostics;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;

import au.edu.eq.questionbank.ApplicationVersion;

/**
 * Records opt-in application performance measurements.
 *
 * Diagnostics are disabled unless explicitly enabled. No files are created
 * while disabled.
 */
public final class PerformanceRecorder implements AutoCloseable {

	static final String HEADER = "timestamp,operation_id,parent_id,operation," + "elapsed_ms,success,result_count";
	static final String SESSION_HEADER = "timestamp,session_id,operation_id,"
			+ "parent_id,operation,elapsed_ms,success,result_count";
	private final boolean enabled;
	private final AtomicLong nextId = new AtomicLong();
	private final Path outputFile;
	private final boolean sessionFormat;
	private final UUID sessionId;
	private final Instant startedAt;
	private final PerformanceSessionWriter sessionWriter;

	/**
	 * Creates a performance recorder using the legacy CSV format.
	 *
	 * @param enabled    whether measurements are recorded
	 * @param outputFile destination CSV file
	 */
	public PerformanceRecorder(boolean enabled, Path outputFile) {
		this(enabled, outputFile, enabled ? UUID.randomUUID() : null, false);
	}

	private PerformanceRecorder(boolean enabled, Path outputFile, UUID sessionId, boolean sessionFormat) {
		this.enabled = enabled;
		this.outputFile = outputFile;
		this.sessionId = sessionId;
		this.sessionFormat = sessionFormat;
		this.startedAt = enabled ? Instant.now() : null;
		this.sessionWriter = enabled && sessionFormat
				? new PerformanceSessionWriter(outputFile, SESSION_HEADER, this::writeSessionMetadata)
				: null;
	}

	/**
	 * Creates a session-aware diagnostic recorder for an explicitly selected
	 * diagnostics directory, without reading or changing JVM configuration.
	 *
	 * The enabled variant uses the ordinary bounded background session writer.
	 * Disabled recording neither starts a writer nor creates files. This factory is
	 * intended for isolated comparison tools and focused tests.
	 *
	 * @param diagnosticsDirectory isolated destination for diagnostic sessions
	 * @param enabled              whether collection is enabled
	 * @return a new session-aware or disabled performance recorder
	 * @throws NullPointerException if diagnosticsDirectory is null
	 */
	public static PerformanceRecorder forSession(Path diagnosticsDirectory, boolean enabled) {
		Objects.requireNonNull(diagnosticsDirectory, "diagnosticsDirectory");
		if (!enabled) {
			return new PerformanceRecorder(false, diagnosticsDirectory.resolve("performance.csv"));
		}
		UUID sessionId = UUID.randomUUID();
		Path outputFile = diagnosticsDirectory.resolve("sessions").resolve(sessionId.toString())
				.resolve("operations.csv");
		return new PerformanceRecorder(true, outputFile, sessionId, true);
	}

	/**
	 * Creates the application recorder using JVM properties.
	 *
	 * Enabled application recorders write to a uniquely named session directory.
	 *
	 * @param diagnosticsDirectory directory for diagnostic output
	 * @return configured recorder
	 */
	public static PerformanceRecorder fromSystemProperties(Path diagnosticsDirectory) {
		return fromSystemProperties(diagnosticsDirectory, false);
	}

	/**
	 * Creates the application recorder using a saved preference, overridden by an
	 * explicitly supplied JVM system property.
	 *
	 * @param diagnosticsDirectory diagnostic output directory
	 * @param preferenceEnabled    persisted collection preference
	 * @return enabled or disabled recorder
	 */
	public static PerformanceRecorder fromSystemProperties(Path diagnosticsDirectory, boolean preferenceEnabled) {
		String override = System.getProperty("eqb.performance.enabled");
		boolean enabled = override == null ? preferenceEnabled : Boolean.parseBoolean(override);
		return forSession(diagnosticsDirectory, enabled);
	}

	/**
	 * Flushes and closes session-aware diagnostic recording.
	 *
	 * Legacy and disabled recorders require no shutdown action.
	 */
	@Override
	public void close() {
		if (sessionWriter != null) {
			sessionWriter.close();
		}
	}

	/**
	 * Returns the number of session measurements rejected by the bounded queue or
	 * after recording was closed.
	 *
	 * @return dropped measurement count
	 */
	public long droppedRecordCount() {
		return sessionWriter == null ? 0 : sessionWriter.droppedRecordCount();
	}

	/**
	 * Returns the number of session diagnostic write failures observed.
	 *
	 * @return write failure count
	 */
	public long failedWriteCount() {
		return sessionWriter == null ? 0 : sessionWriter.failedWriteCount();
	}

	/**
	 * Returns whether diagnostics are enabled.
	 *
	 * @return true when enabled
	 */
	public boolean isEnabled() {
		return enabled;
	}

	/**
	 * Executes and measures an operation, recording both success and failure.
	 *
	 * The original exception or error is propagated unchanged.
	 *
	 * @param <T>    result type
	 * @param name   stable operation identifier
	 * @param action operation to execute
	 * @return the operation result
	 * @throws Exception if the operation fails
	 */
	public <T> T measure(String name, Callable<T> action) throws Exception {
		try (PerformanceOperation operation = start(name)) {
			try {
				return action.call();
			} catch (Exception | Error failure) {
				operation.failed();
				throw failure;
			}
		}
	}

	/**
	 * Returns the unique identifier of this recorder's session.
	 *
	 * @return session identifier, or null when diagnostics are disabled
	 */
	public UUID sessionId() {
		return sessionId;
	}

	/**
	 * Begins an independent operation.
	 *
	 * @param name stable operation identifier
	 * @return operation handle
	 */
	public PerformanceOperation start(String name) {
		return start(name, 0);
	}

	/**
	 * Begins an operation linked to an existing parent operation.
	 *
	 * The explicit parent ID supports work crossing thread boundaries.
	 *
	 * @param name     stable operation identifier
	 * @param parentId parent operation ID, or zero
	 * @return operation handle
	 */
	public PerformanceOperation start(String name, long parentId) {
		if (!enabled) {
			return PerformanceOperation.disabled();
		}
		if (name == null || !name.matches("[a-zA-Z0-9._-]+")) {
			throw new IllegalArgumentException("Invalid performance operation name");
		}
		return new PerformanceOperation(this, nextId.incrementAndGet(), parentId, name, System.nanoTime());
	}

	synchronized void record(long id, long parentId, String name, long elapsedNanos, boolean success,
			long resultCount) {
		if (!enabled) {
			return;
		}
		String line = Instant.now() + "," + (sessionFormat ? sessionId + "," : "") + id + "," + parentId + "," + name
				+ "," + (elapsedNanos / 1_000_000.0) + "," + success + "," + resultCount + System.lineSeparator();
		if (sessionWriter != null) {

			// Normal application sessions queue measurements rather than
			// performing filesystem work on the calling thread.
			sessionWriter.submit(line);
			return;
		}

		// Preserve the synchronous legacy recorder contract.
		try {
			Path parent = outputFile.toAbsolutePath().getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			if (Files.notExists(outputFile)) {
				Files.writeString(outputFile, HEADER + System.lineSeparator(), StandardCharsets.UTF_8,
						StandardOpenOption.CREATE_NEW);
			}
			Files.writeString(outputFile, line, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
		} catch (IOException | RuntimeException failure) {
			try {
				System.err.println("Performance diagnostics write failed: " + failure.getMessage());
			} catch (RuntimeException ignored) {

				// Reporting failures must also remain non-fatal.
			}
		}
	}

	private String safeApplicationVersion() {
		try {
			return ApplicationVersion.current();
		} catch (RuntimeException failure) {

			// Missing diagnostic metadata must never prevent application work.
			return "unknown";
		}
	}

	private void writeSessionMetadata(Path sessionDirectory) throws IOException {
		Path metadataFile = sessionDirectory.resolve("session.properties");
		if (Files.exists(metadataFile)) {
			return;
		}
		Properties metadata = new Properties();
		metadata.setProperty("session.id", sessionId.toString());
		metadata.setProperty("session.startedAt", startedAt.toString());
		metadata.setProperty("application.version", safeApplicationVersion());
		String runtime = System.getProperty("eqb.performance.runtimeKind", "unknown");
		if (!"eclipse".equals(runtime) && !"installed".equals(runtime)) {
			runtime = "unknown";
		}
		metadata.setProperty("runtime.kind", runtime);
		metadata.setProperty("java.version", System.getProperty("java.version", "unknown"));
		metadata.setProperty("os.name", System.getProperty("os.name", "unknown"));
		try (OutputStream output = Files.newOutputStream(metadataFile, StandardOpenOption.CREATE_NEW)) {
			metadata.store(output, "Exam Question Bank performance session");
		}
	}
}
