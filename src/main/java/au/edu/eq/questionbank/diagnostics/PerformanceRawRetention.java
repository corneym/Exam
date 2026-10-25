package au.edu.eq.questionbank.diagnostics;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Enforces a soft cap on old raw performance sessions without touching
 * permanent historical summaries, incomplete sessions or active sessions.
 */
public final class PerformanceRawRetention {

	/** Default soft ceiling for all raw session files (200 MiB). */
	public static final long DEFAULT_MAX_RAW_BYTES = 200L * 1024 * 1024;
	private static final Set<String> SAFE_FILES = Set.of("operations.csv", "corpus-checkpoints.csv",
			"session.properties", "session.complete");
	private final PerformanceHistoryPublisher publisher;

	/**
	 * Creates a retention controller backed by the history publisher.
	 */
	public PerformanceRawRetention() {
		this(new PerformanceHistoryPublisher());
	}

	PerformanceRawRetention(PerformanceHistoryPublisher publisher) {
		this.publisher = Objects.requireNonNull(publisher, "publisher");
	}

	private static List<Session> inspect(Path sessionsRoot) throws IOException {
		List<Session> result = new ArrayList<>();
		try (Stream<Path> entries = Files.list(sessionsRoot)) {
			for (Path path : entries.toList()) {
				result.add(inspectOne(path));
			}
		}
		return result;
	}

	private static Session inspectOne(Path directory) throws IOException {
		if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
			return new Session(null, directory, 0, Instant.MAX, false);
		}
		UUID id = null;
		try {
			String name = directory.getFileName().toString();
			UUID parsed = UUID.fromString(name);
			if (parsed.toString().equals(name)) {
				id = parsed;
			}
		} catch (IllegalArgumentException invalid) {

			// Unknown directories are never pruned.
		}
		boolean onlySafeFiles = id != null;
		long bytes = 0;
		try (Stream<Path> contents = Files.list(directory)) {
			for (Path file : contents.toList()) {
				if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
					onlySafeFiles = false;
					continue;
				}
				bytes = Math.addExact(bytes, Files.size(file));
				String filename = file.getFileName().toString();
				if (!SAFE_FILES.contains(filename) && !filename.matches("operations-[0-9]{6}\\.csv")) {
					onlySafeFiles = false;
				}
			}
		}
		Path completion = directory.resolve("session.complete");
		boolean complete = Files.isRegularFile(completion, LinkOption.NOFOLLOW_LINKS)
				&& Files.isRegularFile(directory.resolve("operations.csv"), LinkOption.NOFOLLOW_LINKS)
				&& Files.isRegularFile(directory.resolve("session.properties"), LinkOption.NOFOLLOW_LINKS)
				&& Files.isRegularFile(directory.resolve("corpus-checkpoints.csv"), LinkOption.NOFOLLOW_LINKS)
				&& Files.notExists(directory.resolve("session.partial"));
		if (complete) {
			try {

				// The directory must have a contiguous, recognised
				// sequence of operations CSV files.
				PerformanceCsvReader.sessionOperationFiles(directory);
			} catch (IOException invalidSegments) {
				complete = false;
			}
		}
		Instant date = complete ? Files.getLastModifiedTime(completion).toInstant() : Instant.MAX;
		return new Session(id, directory, bytes, date, complete && onlySafeFiles);
	}

	/**
	 * Inspects or prunes old raw session directories.
	 *
	 * Only four approved raw files inside a completed, fully republished session
	 * can be deleted. An interprocess lock prevents two retention controllers from
	 * pruning concurrently.
	 *
	 * @param diagnosticsRoot  diagnostics directory, not the data root
	 * @param maxRawBytes      desired maximum raw-session bytes
	 * @param protectedSession session ID to preserve, or null
	 * @param deleteVerified   true to permit verified raw deletion
	 * @return observed sizes and deletion results
	 * @throws IOException if scanning or lock acquisition fails
	 */
	public Result enforce(Path diagnosticsRoot, long maxRawBytes, UUID protectedSession, boolean deleteVerified)
			throws IOException {
		Objects.requireNonNull(diagnosticsRoot, "diagnosticsRoot");
		if (maxRawBytes < 1) {
			throw new IllegalArgumentException("maxRawBytes must be positive");
		}
		Path sessionsRoot = diagnosticsRoot.resolve("sessions");
		if (Files.notExists(sessionsRoot)) {
			return new Result(0, 0, 0, 0, true);
		}
		if (Files.isSymbolicLink(sessionsRoot) || !Files.isDirectory(sessionsRoot, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Unsafe raw-session root");
		}
		Path lockFile = diagnosticsRoot.resolve(".raw-retention.lock");
		try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
			FileLock lock;
			try {
				lock = channel.tryLock();
			} catch (OverlappingFileLockException busy) {
				return new Result(-1, -1, 0, 0, false);
			}
			if (lock == null) {
				return new Result(-1, -1, 0, 0, false);
			}
			try (lock) {
				List<Session> sessions = inspect(sessionsRoot);
				long initialBytes = 0;
				for (Session session : sessions) {
					initialBytes = Math.addExact(initialBytes, session.bytes());
				}
				long remaining = initialBytes;
				int deleted = 0;
				int protectedCount = 0;

				// Prioritise the oldest completed sessions.
				// Incomplete sessions sort last and are never deleted.
				sessions.sort(Comparator.comparing(Session::completedAt));
				for (Session candidate : sessions) {
					if (remaining <= maxRawBytes || !deleteVerified) {
						break;
					}
					if (candidate.id() == null || candidate.id().equals(protectedSession)
							|| !candidate.safeToDelete()) {
						protectedCount++;
						continue;
					}
					try {

						// Recreate every summary from the authoritative
						// raw session and compare immutable history files.
						if (!publisher.publishSession(diagnosticsRoot, candidate.id(), "unknown")) {
							protectedCount++;
							continue;
						}

						// Reject sessions whose contents have changed
						// since initial inspection.
						Session fresh = inspectOne(candidate.directory());
						if (!fresh.safeToDelete() || fresh.bytes() != candidate.bytes()
								|| !fresh.id().equals(candidate.id())) {
							protectedCount++;
							continue;
						}
						List<Path> operations = PerformanceCsvReader.sessionOperationFiles(fresh.directory());

						// The publisher has successfully reconstructed and verified
						// the complete multi-segment session before deletion begins.
						for (Path file : operations) {
							Files.delete(file);
						}
						for (String name : SAFE_FILES) {
							if (!"operations.csv".equals(name)) {
								Files.delete(fresh.directory().resolve(name));
							}
						}
						Files.delete(fresh.directory());
						remaining -= candidate.bytes();
						deleted++;
					} catch (IOException | RuntimeException failure) {

						// Uncertain sessions must not be pruned.
						// Their permanent historical records remain.
						protectedCount++;
					}
				}
				return new Result(initialBytes, remaining, deleted, protectedCount, true);
			}
		}
	}

	/**
	 * Summary of one retention attempt.
	 *
	 * @param beforeBytes     observed bytes before cleanup, or -1 if locked
	 * @param afterBytes      observed bytes after cleanup, or -1 if locked
	 * @param deletedSessions verified directories removed
	 * @param skippedSessions ineligible candidates encountered
	 * @param lockAcquired    false when another instance holds the lock
	 */
	public record Result(long beforeBytes, long afterBytes, int deletedSessions, int skippedSessions,
			boolean lockAcquired) {
	}

	private record Session(UUID id, Path directory, long bytes, Instant completedAt, boolean safeToDelete) {
	}
}
