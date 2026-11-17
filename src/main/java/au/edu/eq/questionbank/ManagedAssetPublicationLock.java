package au.edu.eq.questionbank;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;

/**
 * Exclusive data-root boundary shared by package publication, recovery and
 * ordinary managed-PDF reuse. Acquire this lock before reserving a SQLite
 * writer. Locks are non-reentrant and must remain held until publication or
 * recovery ends.
 */
public final class ManagedAssetPublicationLock implements AutoCloseable {

	private final FileChannel channel;
	private final Path dataRoot;
	private final FileLock lock;
	private final Thread owner;

	private ManagedAssetPublicationLock(Path dataRoot, FileChannel channel, FileLock lock) {
		this.dataRoot = dataRoot;
		this.channel = channel;
		this.lock = lock;
		owner = Thread.currentThread();
	}

	/**
	 * Acquires the shared publication lock without waiting for another operation.
	 *
	 * @param dataRoot authoritative managed-data root
	 * @return owned lock, to be closed by the acquiring thread
	 * @throws IOException          if the root/lock is unsafe or another operation
	 *                              owns it
	 * @throws NullPointerException if dataRoot is null
	 */
	public static ManagedAssetPublicationLock acquire(Path dataRoot) throws IOException {
		Path root = Objects.requireNonNull(dataRoot, "dataRoot").toAbsolutePath().normalize();
		Files.createDirectories(root);
		if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Unsafe managed publication root: " + root);
		}
		FileChannel channel = FileChannel.open(root.resolve(".managed-asset-publication.lock"),
				StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
		try {
			FileLock lock = channel.tryLock();
			if (lock == null) {
				throw new IOException("Another managed-asset publication or recovery is running");
			}
			return new ManagedAssetPublicationLock(root, channel, lock);
		} catch (IOException | RuntimeException failure) {
			try {
				channel.close();
			} catch (IOException closeFailure) {
				failure.addSuppressed(closeFailure);
			}
			if (failure instanceof OverlappingFileLockException) {
				throw new IOException("Another managed-asset publication or recovery is running", failure);
			}
			throw failure;
		}
	}

	/**
	 * Releases the publication lock. The lock file itself is never removed.
	 *
	 * @throws IOException if the lock cannot be released
	 */
	@Override
	public void close() throws IOException {
		try {
			lock.release();
		} finally {
			channel.close();
		}
	}

	/**
	 * Verifies that this thread holds the lock for the supplied data root.
	 *
	 * @param expectedRoot data root whose assets will be accessed
	 * @throws IOException if the lock is closed, belongs to another root or thread
	 */
	public void requireHeld(Path expectedRoot) throws IOException {
		if (!lock.isValid() || owner != Thread.currentThread()
				|| !dataRoot.equals(expectedRoot.toAbsolutePath().normalize())) {
			throw new IOException("Managed-asset publication requires the owning data-root lock");
		}
	}

	/**
	 * Blocks ordinary file adoption until both package staging areas are resolved.
	 * This conservative check does not infer ownership from existing file bytes.
	 *
	 * @throws IOException if any pending or unsafe staging evidence remains
	 */
	public void requireNoPendingPublications() throws IOException {
		requireHeld(dataRoot);
		for (String name : List.of(".subject-import-staging", ".assignment-install-staging")) {
			Path root = dataRoot.resolve(name);
			if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
				continue;
			}
			if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
				throw new IOException("Unsafe package staging root: " + root);
			}
			try (var entries = Files.list(root)) {
				for (Path entry : entries.toList()) {
					String file = entry.getFileName().toString();
					if ((file.equals(".import.lock") || file.equals(".install.lock"))
							&& Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
						continue;
					}
					throw new IOException(
							"Recover pending Subject/assignment publications before importing PDFs: " + entry);
				}
			}
		}
	}
}
