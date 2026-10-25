package au.edu.eq.questionbank.diagnostics;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Maintains one replaceable offline performance report from immutable history.
 * The report is derived data; neither raw sessions nor history are modified.
 */
public final class PerformanceLatestReport {

	private static final String LOCK_NAME = ".latest-report.lock";
	private static final String REPORT_NAME = "performance-latest.html";

	/**
	 * Resolves the stable report path without creating directories or files.
	 *
	 * @param diagnosticsRoot optional diagnostics root used by the application
	 * @return path to the current HTML report
	 */
	public static Path latestReportPath(Path diagnosticsRoot) {
		return Objects.requireNonNull(diagnosticsRoot, "diagnosticsRoot").resolve("reports").resolve(REPORT_NAME)
				.toAbsolutePath().normalize();
	}

	/**
	 * Rebuilds the latest report from completed historical checkpoints. At most one
	 * instance updates the shared report at a time; a busy lock skips this attempt.
	 * The previous report is left intact if generation or replacement fails.
	 *
	 * @param diagnosticsRoot root containing history and optional legacy CSV
	 * @return true when the report was refreshed, false when no input is available
	 *         or another process owns the update lock
	 * @throws IOException if history, output or atomic replacement is invalid
	 */
	public boolean refresh(Path diagnosticsRoot) throws IOException {
		Objects.requireNonNull(diagnosticsRoot, "diagnosticsRoot");
		Path root = diagnosticsRoot.toAbsolutePath().normalize();
		Path history = root.resolve("history");
		Path legacyFile = root.resolve("performance.csv");
		Path legacy = Files.isRegularFile(legacyFile, LinkOption.NOFOLLOW_LINKS) ? legacyFile : null;
		if (!Files.isDirectory(history, LinkOption.NOFOLLOW_LINKS) && legacy == null) {
			return false;
		}
		Path latest = latestReportPath(root);
		Path reports = latest.getParent();
		if (Files.isSymbolicLink(reports) || Files.isSymbolicLink(latest)) {
			throw new IOException("Unsafe performance report output path");
		}
		Files.createDirectories(reports);
		Path lockFile = reports.resolve(LOCK_NAME);
		if (Files.isSymbolicLink(lockFile)) {
			throw new IOException("Unsafe performance report lock path");
		}
		try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
			FileLock acquired;
			try {
				acquired = channel.tryLock();
			} catch (OverlappingFileLockException busyInThisProcess) {
				return false;
			}
			if (acquired == null) {
				return false;
			}
			try (FileLock ignored = acquired) {

				// Clean up unique staging files abandoned by an earlier forced exit.
				// The lock proves no other valid writer is using them now.
				try (Stream<Path> files = Files.list(reports)) {
					for (Path candidate : files.toList()) {
						String name = candidate.getFileName().toString();
						if (name.matches("\\.performance-latest-[0-9a-f-]{36}\\.html")
								&& Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
							Files.deleteIfExists(candidate);
						}
					}
				}

				// Hold the interprocess lock while reading history and replacing the report.
				Path staged = reports.resolve(".performance-latest-" + UUID.randomUUID() + ".html");
				try {
					PerformanceHistoryHtmlReport generator = new PerformanceHistoryHtmlReport();
					try {
						generator.generate(history, staged, legacy);
					} catch (IOException legacyOrHistoryFailure) {
						if (legacy == null) {
							throw legacyOrHistoryFailure;
						}

						// A malformed old CSV must not prevent a good modern report.
						Files.deleteIfExists(staged);
						generator.generate(history, staged);
						System.err.println("Legacy performance CSV omitted from automatic report: "
								+ legacyOrHistoryFailure.getClass().getSimpleName());
					}

					// Do not degrade to a non-atomic replacement of the last good report.
					Files.move(staged, latest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
					return true;
				} finally {
					Files.deleteIfExists(staged);
				}
			}
		}
	}

	/**
	 * Gives a normal application shutdown a short, best-effort opportunity to
	 * refresh the report. An unfinished daemon cannot hold the JVM open; the next
	 * startup will retry from permanent history.
	 *
	 * @param diagnosticsRoot root containing the historical measurements
	 */
	public void refreshBeforeExit(Path diagnosticsRoot) {
		Objects.requireNonNull(diagnosticsRoot, "diagnosticsRoot");
		Thread task = new Thread(() -> {
			try {
				refresh(diagnosticsRoot);
			} catch (IOException | RuntimeException failure) {
				System.err
						.println("Automatic performance report refresh failed: " + failure.getClass().getSimpleName());
			}
		}, "eqb-performance-report-shutdown");
		task.setDaemon(true);
		task.start();
		try {
			task.join(2_000L);
			if (task.isAlive()) {
				System.err.println("Performance report refresh deferred to next application startup");
			}
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
	}
}
