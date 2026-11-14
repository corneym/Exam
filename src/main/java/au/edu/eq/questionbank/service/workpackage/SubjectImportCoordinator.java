package au.edu.eq.questionbank.service.workpackage;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.repository.sqlite.SqliteSubjectImportLedger;
import au.edu.eq.questionbank.repository.sqlite.SqliteSubjectImportLedger.CoordinatorDecision;
import au.edu.eq.questionbank.repository.sqlite.SqliteSubjectImportLedger.EntryKind;
import au.edu.eq.questionbank.repository.sqlite.SqliteSubjectImportLedger.ItemEntry;
import au.edu.eq.questionbank.repository.sqlite.SqliteSubjectImportLedger.ItemOutcome;
import au.edu.eq.questionbank.repository.sqlite.SqliteSubjectImportLedger.PackageEntry;
import au.edu.eq.questionbank.repository.sqlite.SqliteSubjectImportLedger.PackageOutcome;
import au.edu.eq.questionbank.service.backup.BackupException;
import au.edu.eq.questionbank.service.workpackage.SubjectImportApprovalPlan.Action;
import au.edu.eq.questionbank.service.workpackage.SubjectImportAssetStager.StagedPackage;
import au.edu.eq.questionbank.service.workpackage.SubjectImportBackupGate.Certificate;
import au.edu.eq.questionbank.service.workpackage.SubjectImportGraphWriter.DecodedPackage;
import au.edu.eq.questionbank.service.workpackage.SubjectReconciliationReview.Key;

/**
 * Coordinates an approved and recoverable Subject import into the master.
 *
 * <p>
 * All operations run under an exclusive import file lock. Before any
 * publication, the coordinator creates a validated full backup, copies and
 * validates contributor bytes, acquires a SQLite write reservation and
 * re-exports the master Subject to reject a stale preview.
 *
 * <p>
 * Approved managed files are hard-linked from durable staging before the graph
 * and package ledger commit in one SQLite transaction. Restart recovery
 * consults that terminal ledger: committed files are retained; uncommitted
 * files are removed only when provably linked to the original staging files.
 */
public final class SubjectImportCoordinator {

	private final ApplicationConfig config;
	private final SqliteDatabase database;
	private final SqliteSubjectImportLedger ledger;

	/**
	 * Creates a coordinator for an authoritative application data root.
	 *
	 * @param config active master configuration
	 */
	public SubjectImportCoordinator(ApplicationConfig config) {
		this.config = Objects.requireNonNull(config, "config");
		database = new SqliteDatabase(config.databasePath());
		ledger = new SqliteSubjectImportLedger(database);
	}

	/**
	 * Executes an approved Subject import, or reports an idempotent repeat.
	 *
	 * <p>
	 * The caller must ensure other application instances are not modifying managed
	 * files outside the import protocol. This method only locks other Subject
	 * import operations and SQLite writers.
	 *
	 * @param masterSubjectId existing master Subject local ID reviewed in preview
	 * @param plan            complete coordinator-approved import plan
	 * @param incomingArchive original contributor Subject archive
	 * @param backupDirectory safe full-backup destination
	 * @param approvalNote    non-blank coordinator audit rationale
	 * @return committed or previously applied import result
	 * @throws SQLException    if master data, identity or graph persistence fails
	 * @throws IOException     if validation, publication or recovery fails
	 * @throws BackupException if a verified full backup cannot be prepared
	 */
	public Result execute(long masterSubjectId, SubjectImportApprovalPlan plan, Path incomingArchive,
			Path backupDirectory, String approvalNote) throws SQLException, IOException, BackupException {
		if (masterSubjectId < 1) {
			throw new IllegalArgumentException("masterSubjectId must be positive");
		}
		Objects.requireNonNull(plan, "plan");
		Objects.requireNonNull(incomingArchive, "incomingArchive");
		Objects.requireNonNull(backupDirectory, "backupDirectory");
		if (approvalNote == null || approvalNote.isBlank() || approvalNote.length() > 4000) {
			throw new IllegalArgumentException("Import approval needs 1–4000 characters of rationale");
		}
		UUID packageId = plan.reviewedIncoming().packageId();
		Path lockRoot = config.dataRoot().resolve(".subject-import-staging");
		Files.createDirectories(lockRoot);
		if (!Files.isDirectory(lockRoot, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Unsafe import staging root");
		}
		try (FileChannel lockChannel = FileChannel.open(lockRoot.resolve(".import.lock"), StandardOpenOption.CREATE,
				StandardOpenOption.WRITE); FileLock lock = lockChannel.tryLock()) {
			if (lock == null) {
				throw new IOException("Another Subject import currently owns the publication lock");
			}
			recoverPending(lockRoot);
			var existing = ledger.find(packageId);
			if (existing.isPresent()) {
				PackageEntry previous = existing.get();
				if (!previous.archiveSha256().equals(plan.reviewedIncoming().archiveSha256())
						|| !previous.approvalSha256().equals(plan.approvalSha256())) {
					throw new IOException("Package identity already recorded with different approval or bytes");
				}
				return new Result(packageId, previous.outcome() == PackageOutcome.APPLIED, true,
						previous.backupArchivePath());
			}

			// A verified full backup always precedes staging and publication.
			SubjectImportBackupGate gate = new SubjectImportBackupGate(config);
			Certificate backup = gate.createVerifiedFullBackup(packageId, backupDirectory);
			StagedPackage staged = new SubjectImportAssetStager(config).stage(plan, backup, incomingArchive);
			SubjectImportPublicationJournal journal = new SubjectImportPublicationJournal(config);
			boolean prepared = false;
			boolean committed = false;
			try {
				SubjectImportGraphWriter writer = new SubjectImportGraphWriter(database);
				DecodedPackage decoded = writer.decode(staged, plan);
				journal.prepare(staged, backup);
				prepared = true;
				try (Connection connection = database.openConnection()) {
					connection.setAutoCommit(false);
					try {
						reserveWriter(connection);

						// Re-export under the SQLite write reservation to prevent
						// any intervening SQLite writer changing reviewed state.
						requireFreshMaster(masterSubjectId, plan);
						gate.requireIntact(packageId, backup);
						journal.publishFiles(packageId);
						Map<Key, UUID> canonical = writer.apply(connection, plan, staged, decoded, approvalNote);
						requireForeignKeyIntegrity(connection);
						journal.verifyPublished(packageId);
						gate.requireIntact(packageId, backup);
						recordPackage(connection, plan, backup, approvalNote);
						recordItems(connection, plan, canonical);
						connection.commit();
						committed = true;
					} catch (SQLException | IOException | RuntimeException failure) {
						try {
							connection.rollback();
						} catch (SQLException rollbackFailure) {
							failure.addSuppressed(rollbackFailure);
						}
						throw failure;
					}
				}

				// A cleanup failure after commit is not an import rollback.
				// The next invocation will recover the committed journal.
				try {
					journal.recover(packageId);
				} catch (SQLException | IOException cleanupFailure) {
					return new Result(packageId, true, false, backup.backupPath().toString());
				}
				return new Result(packageId, true, false, backup.backupPath().toString());
			} catch (SQLException | IOException | RuntimeException failure) {
				if (!committed) {
					try {
						if (prepared) {
							journal.recover(packageId);
						} else {
							discardUnpublishedStaging(staged.directory());
						}
					} catch (SQLException | IOException recoveryFailure) {
						failure.addSuppressed(recoveryFailure);
					}
				}
				throw failure;
			}
		} catch (java.nio.channels.OverlappingFileLockException exception) {
			throw new IOException("Another Subject import holds the publication lock", exception);
		}
	}

	/**
	 * Restores every previously interrupted import under the caller's lock.
	 *
	 * @param stagingRoot private Subject import staging directory
	 * @throws IOException  if unresolved unsafe publication evidence remains
	 * @throws SQLException if package-ledger inspection fails
	 */
	public void recoverPending(Path stagingRoot) throws IOException, SQLException {
		Objects.requireNonNull(stagingRoot, "stagingRoot");
		Path expected = config.dataRoot().resolve(".subject-import-staging");
		if (!stagingRoot.toAbsolutePath().normalize().equals(expected)) {
			throw new IOException("Recovery cannot inspect another data root");
		}
		if (!Files.isDirectory(expected, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		List<Path> directories;
		try (Stream<Path> stream = Files.list(expected)) {
			directories = stream.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)).sorted().toList();
		}
		SubjectImportPublicationJournal journal = new SubjectImportPublicationJournal(config);
		for (Path directory : directories) {
			UUID packageId;
			try {
				packageId = UUID.fromString(directory.getFileName().toString());
			} catch (IllegalArgumentException exception) {
				throw new IOException("Unexpected import staging directory: " + directory, exception);
			}
			if (!Files.exists(directory.resolve("publication.properties"), LinkOption.NOFOLLOW_LINKS)) {

				// No journal exists, so no file publication was authorised.
				discardUnpublishedStaging(directory);
			} else {
				journal.recover(packageId);
			}
		}
	}

	/**
	 * Removes only a private staging directory that never had a journal.
	 */
	private void discardUnpublishedStaging(Path directory) throws IOException {
		if (Files.exists(directory.resolve("publication.properties"), LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Journalled import cannot be discarded without recovery");
		}
		try (Stream<Path> stream = Files.walk(directory)) {
			for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
				if (Files.isSymbolicLink(path)) {
					throw new IOException("Symbolic link in unpublished import staging");
				}
				Files.delete(path);
			}
		}
	}

	/**
	 * Stores decisions, resolved identities and current canonical revisions.
	 */
	private void recordItems(Connection connection, SubjectImportApprovalPlan plan, Map<Key, UUID> canonical)
			throws SQLException {
		SqlitePortableIdentityRepository identities = new SqlitePortableIdentityRepository(database);
		UUID packageId = plan.reviewedIncoming().packageId();
		for (Action action : plan.actions()) {
			ItemOutcome outcome = switch (action.operation()) {
			case INSERT_RECORD -> ItemOutcome.INSERTED;
			case LINK_ALIAS -> ItemOutcome.ALIAS_LINKED;
			case COPY_ASSET -> ItemOutcome.COPIED;
			case KEEP_ASSET, PRESERVE_CONFLICT -> ItemOutcome.PRESERVED;
			case SKIP -> ItemOutcome.REJECTED;
			};
			UUID canonicalId = canonical.get(action.key());
			UUID revision = null;
			if (canonicalId != null && "RECORD".equals(action.key().kind())) {
				String type = action.key().value().split(":", 2)[0];
				revision = identities.findByPortableId(connection, EntityType.valueOf(type), canonicalId)
						.orElseThrow(() -> new SQLException("Missing imported canonical revision")).revisionId();
			}
			ledger.recordItem(connection, new ItemEntry(packageId, EntryKind.valueOf(action.key().kind()),
					action.key().value(), action.classification(),
					action.decision() == SubjectReconciliationReview.Decision.REJECT ? CoordinatorDecision.REJECT
							: CoordinatorDecision.ACCEPT,
					outcome, canonicalId, revision,
					action.justification().isBlank() ? action.reason() : action.justification()));
		}
	}

	/**
	 * Records the terminal package result in the same graph transaction.
	 */
	private void recordPackage(Connection connection, SubjectImportApprovalPlan plan, Certificate backup, String note)
			throws SQLException {
		var incoming = plan.reviewedIncoming();
		PackageEntry entry = new PackageEntry(incoming.packageId(), incoming.archiveSha256(),
				incoming.originInstallationId(), incoming.subjectId(), plan.reviewedMaster().contentSha256(),
				plan.approvalSha256(), PackageOutcome.APPLIED, backup.backupPath().toString(), note);
		if (!ledger.recordPackage(connection, entry)) {
			throw new SQLException("Package was recorded concurrently with import");
		}
	}

	/**
	 * Confirms SQLite relationships before committing the imported graph.
	 */
	private void requireForeignKeyIntegrity(Connection connection) throws SQLException {
		try (Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("PRAGMA foreign_key_check")) {
			if (result.next()) {
				throw new SQLException("Subject import would commit broken foreign-key relationships");
			}
		}
	}

	/**
	 * Exports a fresh master view while SQLite writers are blocked.
	 */
	private void requireFreshMaster(long masterSubjectId, SubjectImportApprovalPlan plan)
			throws SQLException, IOException {
		Path temporary = Files.createTempDirectory(config.dataRoot(), ".subject-freshness-");
		try {
			Path snapshot = temporary.resolve("reviewed-master.examwork");
			new SubjectSnapshotExporter(database, new ManagedDataLayout(config.dataRoot())).export(masterSubjectId,
					snapshot);
			plan.requireFreshMaster(snapshot);
		} finally {
			try (Stream<Path> stream = Files.walk(temporary)) {
				for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
					Files.deleteIfExists(path);
				}
			}
		}
	}

	/**
	 * Binds an SQLite write reservation to the existing transaction.
	 */
	private void reserveWriter(Connection connection) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				UPDATE installation_identity
				SET installation_id = installation_id
				WHERE singleton_id = 1
				""")) {
			if (statement.executeUpdate() != 1) {
				throw new SQLException("Cannot reserve authoritative master writer");
			}
		}
	}

	/**
	 * Result of an approved Subject import or an idempotent replay.
	 *
	 * @param packageId         immutable incoming package ID
	 * @param applied           true when the ledger confirms successful publication
	 * @param replay            true when the package had already been recorded
	 * @param backupArchivePath published full backup, or null for rejected work
	 */
	public record Result(UUID packageId, boolean applied, boolean replay, String backupArchivePath) {

		/** Checks required outcome fields. */
		public Result {
			Objects.requireNonNull(packageId, "packageId");
		}
	}
}
