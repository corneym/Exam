package au.edu.eq.questionbank.diagnostics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Records opt-in application performance measurements.
 *
 * Diagnostics are disabled unless explicitly enabled. No files are created
 * while disabled.
 */
public final class PerformanceRecorder {

	private static final String HEADER = "timestamp,operation_id,parent_id,operation,"
			+ "elapsed_ms,success,result_count";
	private final boolean enabled;
	private final Path outputFile;
	private final AtomicLong nextId = new AtomicLong();

	/**
	 * Creates a performance recorder.
	 *
	 * @param enabled    whether measurements are recorded
	 * @param outputFile destination CSV file
	 */
	public PerformanceRecorder(boolean enabled, Path outputFile) {
		this.enabled = enabled;
		this.outputFile = outputFile;
	}

	/**
	 * Creates the application recorder using JVM properties.
	 *
	 * @param diagnosticsDirectory directory for diagnostic output
	 * @return configured recorder
	 */
	public static PerformanceRecorder fromSystemProperties(Path diagnosticsDirectory) {
		boolean enabled = Boolean.parseBoolean(System.getProperty("eqb.performance.enabled", "false"));
		return new PerformanceRecorder(enabled, diagnosticsDirectory.resolve("performance.csv"));
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
		String line = Instant.now() + "," + id + "," + parentId + "," + name + "," + (elapsedNanos / 1_000_000.0) + ","
				+ success + "," + resultCount + System.lineSeparator();
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
		} catch (IOException e) {

			// Diagnostics must not replace an application failure.
			System.err.println("Performance diagnostics write failed: " + e.getMessage());
		}
	}
}
