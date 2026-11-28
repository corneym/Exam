package au.edu.eq.questionbank.service.workpackage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.ManagedAssetPublicationLock;
import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

/**
 * Resolves Contribution publication, contributor corpus disposal,
 * Subject-import and assignment-install recovery before managed corpus use.
 */
public final class ManagedAssetPublicationRecovery {

	private ManagedAssetPublicationRecovery() {
	}

	/**
	 * Resolves every pending managed-asset operation under the shared data-root
	 * lock.
	 *
	 * @param config authoritative application configuration
	 * @throws IOException          if recovery evidence or a managed path is unsafe
	 * @throws SQLException         if recovery state cannot be read or committed
	 * @throws NullPointerException if config is null
	 */
	public static void recoverPending(ApplicationConfig config) throws IOException, SQLException {
		try (ManagedAssetPublicationLock lock = ManagedAssetPublicationLock.acquire(config.dataRoot())) {
			recoverPending(config, lock);
		}
	}

	static void recoverPending(ApplicationConfig config, ManagedAssetPublicationLock lock)
			throws IOException, SQLException {
		recoverPending(config, lock, true, null);
	}

	static void recoverPendingForDisposal(ApplicationConfig config, ManagedAssetPublicationLock lock)
			throws IOException, SQLException {
		recoverPending(config, lock, false, null);
	}

	static void recoverPendingForDisposal(ApplicationConfig config, ManagedAssetPublicationLock lock, UUID assignmentId)
			throws IOException, SQLException {
		recoverPending(config, lock, true, Objects.requireNonNull(assignmentId, "assignmentId"));
	}

	private static void recoverPending(ApplicationConfig config, ManagedAssetPublicationLock lock,
			boolean requireNoPlannedDisposal, UUID allowedPlannedAssignmentId) throws IOException, SQLException {
		lock.requireHeld(config.dataRoot());
		new ContributionPublicationJournal(new SqliteDatabase(config.databasePath()),
				new ManagedDataLayout(config.dataRoot())).recoverPending(lock);
		var disposalRecovery = new ContributorAssignmentCorpusDisposalRecovery(config);
		disposalRecovery.recoverPending(lock);
		for (String name : List.of(".subject-import-staging", ".assignment-install-staging")) {
			Path root = config.dataRoot().resolve(name);
			if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
				continue;
			}
			if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
				throw new IOException("Unsafe publication staging root: " + root);
			}
			try (var entries = Files.list(root)) {
				for (Path entry : entries.sorted().toList()) {
					recoverEntry(config, lock, name, entry);
				}
			}
		}
		if (requireNoPlannedDisposal) {
			disposalRecovery.requireNoPlanned(lock, allowedPlannedAssignmentId);
		}
	}

	private static void recoverEntry(ApplicationConfig config, ManagedAssetPublicationLock lock, String name,
			Path entry) throws IOException, SQLException {
		String filename = entry.getFileName().toString();
		if ((filename.equals(".import.lock") || filename.equals(".install.lock"))
				&& Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Unsafe publication staging entry: " + entry);
		}
		UUID packageId;
		try {
			packageId = UUID.fromString(filename);
			if (!packageId.toString().equals(filename)) {
				throw new IllegalArgumentException("Noncanonical package UUID");
			}
		} catch (IllegalArgumentException failure) {
			throw new IOException("Unknown publication staging directory: " + entry, failure);
		}
		if (!Files.exists(entry.resolve("publication.properties"), LinkOption.NOFOLLOW_LINKS)) {

			// Publication requires a durable journal. A stage without one is either
			// unpublished or the remainder of already-resolved staging cleanup.
			discardPrivateStage(entry);
		} else if (name.equals(".subject-import-staging")) {
			new SubjectImportPublicationJournal(config).recover(packageId, lock);
		} else {
			new WorkAssignmentPublicationJournal(config).recover(packageId, lock);
		}
	}

	private static void discardPrivateStage(Path directory) throws IOException {
		try (var paths = Files.walk(directory)) {
			List<Path> entries = paths.sorted(Comparator.reverseOrder()).toList();
			for (Path entry : entries) {
				if (Files.isSymbolicLink(entry)) {
					throw new IOException("Symbolic link in private publication stage");
				}
			}
			for (Path entry : entries) {
				Files.delete(entry);
			}
		}
	}
}
