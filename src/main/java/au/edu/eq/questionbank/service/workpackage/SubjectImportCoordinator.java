package au.edu.eq.questionbank.service.workpackage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.ManagedAssetPublicationLock;
import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.repository.sqlite.SqliteImportedSubjectAuthorityRepository;
import au.edu.eq.questionbank.repository.sqlite.SqliteSubjectImportLedger;
import au.edu.eq.questionbank.repository.sqlite.SqliteSubjectImportLedger.CoordinatorDecision;
import au.edu.eq.questionbank.repository.sqlite.SqliteSubjectImportLedger.EntryKind;
import au.edu.eq.questionbank.repository.sqlite.SqliteSubjectImportLedger.ItemEntry;
import au.edu.eq.questionbank.repository.sqlite.SqliteSubjectImportLedger.ItemOutcome;
import au.edu.eq.questionbank.repository.sqlite.SqliteSubjectImportLedger.OriginatingSnapshotEvidence;
import au.edu.eq.questionbank.repository.sqlite.SqliteSubjectImportLedger.PackageEntry;
import au.edu.eq.questionbank.repository.sqlite.SqliteSubjectImportLedger.PackageOutcome;
import au.edu.eq.questionbank.service.backup.BackupException;
import au.edu.eq.questionbank.service.workpackage.SubjectImportApprovalPlan.Action;
import au.edu.eq.questionbank.service.workpackage.SubjectImportAssetStager.StagedPackage;
import au.edu.eq.questionbank.service.workpackage.SubjectImportBackupGate.Certificate;
import au.edu.eq.questionbank.service.workpackage.SubjectImportGraphWriter.DecodedPackage;
import au.edu.eq.questionbank.service.workpackage.SubjectImportSnapshotFingerprint.Fingerprint;
import au.edu.eq.questionbank.service.workpackage.SubjectReconciliationReview.Key;

/**
 * Coordinates an approved and recoverable Subject import into the master.
 *
 * <p>
 * All operations run under the shared managed-asset publication lock. Before
 * any publication, the coordinator creates a validated full backup, copies and
 * validates contributor bytes, acquires a SQLite write reservation and
 * re-exports the master Subject to reject a stale preview.
 *
 * <p>
 * Approved managed files are hard-linked from durable staging before the graph
 * and package ledger commit in one SQLite transaction. Restart recovery
 * consults that terminal ledger and authoritative file references: committed or
 * adopted files are retained; unreferenced files are removed only when provably
 * linked to the original staging files.
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
	 * Subject and assignment recovery runs before publication under their common
	 * data-root lock. Ordinary managed-PDF adoption also honours this boundary.
	 * Filesystem modifications outside the managed publication protocol must not
	 * run concurrently.
	 *
	 * <p>
	 * New Subject installations require an absent destination Subject by name and
	 * originating identity, and register imported authority within the graph
	 * transaction.
	 *
	 * @param masterSubjectId existing master Subject local ID reviewed in preview,
	 *                        or zero for a new Subject
	 * @param plan            complete coordinator-approved import plan
	 * @param incomingArchive original Subject archive
	 * @param backupDirectory safe full-backup destination
	 * @param approvalNote    non-blank coordinator audit rationale
	 * @return committed or previously applied import result
	 * @throws SQLException    if master data, identity or graph persistence fails
	 * @throws IOException     if validation, publication or recovery fails
	 * @throws BackupException if a verified full backup cannot be prepared
	 */
	public Result execute(long masterSubjectId, SubjectImportApprovalPlan plan, Path incomingArchive,
			Path backupDirectory, String approvalNote) throws SQLException, IOException, BackupException {
		Objects.requireNonNull(plan, "plan");
		Objects.requireNonNull(incomingArchive, "incomingArchive");
		Objects.requireNonNull(backupDirectory, "backupDirectory");
		boolean newSubject = plan.isNewSubjectInstallation();
		if (newSubject ? masterSubjectId != 0 : masterSubjectId < 1) {
			throw new IllegalArgumentException(
					"Destination Subject ID does not match " + "the approved import operation");
		}
		if (approvalNote == null || approvalNote.isBlank() || approvalNote.length() > 4000) {
			throw new IllegalArgumentException("Import approval needs 1–4000 characters of rationale");
		}
		UUID packageId = plan.reviewedIncoming().packageId();
		try (var lock = ManagedAssetPublicationLock.acquire(config.dataRoot())) {
			ManagedAssetPublicationRecovery.recoverPending(config, lock);

			// Even an idempotent replay must identify the exact
			// archive that was originally reviewed.
			plan.requireFreshIncoming(incomingArchive);
			var existing = ledger.find(packageId);
			if (existing.isPresent()) {
				PackageEntry previous = existing.get();
				if (!previous.archiveSha256().equals(plan.reviewedIncoming().archiveSha256())
						|| (!newSubject && !previous.approvalSha256().equals(plan.approvalSha256()))) {
					throw new IOException("Package identity already recorded " + "with different approval or bytes");
				}
				return new Result(packageId, previous.outcome() == PackageOutcome.APPLIED, true,
						previous.backupArchivePath());
			}
			if (newSubject) {
				UUID localInstallationId = new SqlitePortableIdentityRepository(database).installationId();
				if (localInstallationId.equals(plan.reviewedIncoming().originInstallationId())) {
					throw new IOException("Cannot import a Subject snapshot " + "from the current installation");
				}
			}

			// A verified full backup always precedes staging
			// and managed-file publication.
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
						if (newSubject) {

							// Revalidate destination absence by both
							// name and originating Subject identity.
							// A renamed Subject must not bypass this
							// transactional installation boundary.
							requireNewSubjectDestination(connection, plan.reviewedIncoming());
							requireNewSubjectProviders(connection, plan, decoded);
						} else {

							// Preserve existing reconciliation
							// stale-master protection.
							requireFreshMaster(masterSubjectId, plan);
						}
						gate.requireIntact(packageId, backup);
						journal.publishFiles(packageId);
						Map<Key, UUID> canonical = writer.apply(connection, plan, staged, decoded, approvalNote);
						requireForeignKeyIntegrity(connection);
						journal.verifyPublished(packageId);
						gate.requireIntact(packageId, backup);
						recordPackage(connection, plan, backup, approvalNote, decoded.manifest());
						recordItems(connection, plan, canonical);
						if (newSubject) {

							// Authority and terminal package history
							// commit atomically with the imported graph.
							recordNewSubjectAuthority(connection, plan, canonical);
						}
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

				// Cleanup failure after commit is not a rollback.
				// The next invocation can recover the committed journal.
				try {
					journal.recover(packageId, lock);
				} catch (SQLException | IOException cleanupFailure) {
					return new Result(packageId, true, false, backup.backupPath().toString());
				}
				return new Result(packageId, true, false, backup.backupPath().toString());
			} catch (SQLException | IOException | RuntimeException failure) {
				if (!committed) {
					try {
						if (prepared) {
							journal.recover(packageId, lock);
						} else {
							discardUnpublishedStaging(staged.directory());
						}
					} catch (SQLException | IOException recoveryFailure) {
						failure.addSuppressed(recoveryFailure);
					}
				}
				throw failure;
			}
		}
	}

	/**
	 * Installs an approved complete snapshot of a previously absent Subject.
	 *
	 * <p>
	 * Shared examination providers are resolved without overwriting their existing
	 * values. A verified full backup is created automatically beneath the managed
	 * pre-import backup directory.
	 *
	 * <p>
	 * An identical previously applied package is reported as a replay.
	 *
	 * @param inspection      previously validated and approved snapshot
	 * @param incomingArchive archive selected during preview
	 * @return committed installation or idempotent replay result
	 * @throws SQLException    if destination inspection or persistence fails
	 * @throws IOException     if validation or publication fails
	 * @throws BackupException if the required full backup fails
	 */
	public Result executeNewSubject(SubjectSnapshotIntakePreview.Inspection inspection, Path incomingArchive)
			throws SQLException, IOException, BackupException {
		Objects.requireNonNull(inspection, "inspection");
		Objects.requireNonNull(incomingArchive, "incomingArchive");

		// Revalidate the exact approved archive before deriving
		// provider resolutions or inspecting the destination.
		new SubjectImportSnapshotFingerprint().requireSamePackage(inspection.fingerprint(), incomingArchive);
		var existing = ledger.find(inspection.fingerprint().packageId());
		if (existing.isPresent()) {
			PackageEntry previous = existing.orElseThrow();
			if (!previous.archiveSha256().equals(inspection.fingerprint().archiveSha256())) {
				throw new IOException("Package identity already recorded " + "with different archive bytes");
			}
			if (previous.outcome() != PackageOutcome.APPLIED) {
				throw new IOException("This package was previously rejected");
			}
			return new Result(previous.packageId(), true, true, previous.backupArchivePath());
		}
		if (inspection.state() != SubjectSnapshotIntakePreview.State.NEW_SUBJECT) {
			throw new IOException("The approved operation is not " + "a new Subject installation");
		}
		Map<UUID, UUID> providers;
		try (Connection connection = database.openConnection()) {
			providers = resolveNewSubjectProviders(connection, inspection.records());
		}
		SubjectImportApprovalPlan plan = SubjectImportApprovalPlan.prepareNewSubject(inspection, providers);
		Path backupDirectory = config.dataRoot().resolve("backups").resolve("pre-import");
		Files.createDirectories(backupDirectory);
		return execute(0L, plan, incomingArchive, backupDirectory,
				"Approved complete authoritative Subject installation");
	}

	/**
	 * Executes an explicitly approved refresh of an externally authoritative
	 * Subject, or reports an exact previously applied refresh as a replay.
	 *
	 * <p>
	 * The complete operation runs under the shared managed-asset publication lock.
	 * A verified full backup precedes all graph and managed-file changes. The
	 * reviewed comparison is regenerated under a SQLite writer reservation before
	 * any mutation is permitted.
	 *
	 * <p>
	 * Graph changes, identity aliases and retirements, package audit records and
	 * imported authority advancement commit in one SQLite transaction. Durable
	 * publication evidence supports filesystem recovery if the transaction fails or
	 * the application is interrupted.
	 *
	 * @param approval        exact reviewed and explicitly approved refresh
	 * @param incomingArchive originating snapshot selected during review
	 * @return committed refresh result or exact idempotent replay
	 * @throws SQLException         if destination validation or persistence fails
	 * @throws IOException          if archive, approval, publication or recovery
	 *                              fails
	 * @throws BackupException      if a verified full backup cannot be created
	 * @throws NullPointerException if either argument is null
	 */
	public Result executeRefresh(SubjectRefreshPlan.Approval approval, Path incomingArchive)
			throws SQLException, IOException, BackupException {
		Objects.requireNonNull(approval, "approval");
		Objects.requireNonNull(incomingArchive, "incomingArchive");
		SubjectRefreshPlan plan = approval.plan();
		var incoming = plan.evidence().incoming().fingerprint();
		UUID packageId = incoming.packageId();
		ManagedDataLayout layout = new ManagedDataLayout(config.dataRoot());
		try (var lock = ManagedAssetPublicationLock.acquire(config.dataRoot())) {

			// Resolve interrupted Subject and assignment publication
			// before inspecting the current authoritative destination.
			ManagedAssetPublicationRecovery.recoverPending(config, lock);
			approval.requireFreshIncoming(incomingArchive);
			var existing = ledger.find(packageId);
			if (existing.isPresent()) {
				return requireRefreshReplay(approval, existing.orElseThrow());
			}
			SubjectSnapshotIntakePreview preview = new SubjectSnapshotIntakePreview(database);

			// Reject already stale approvals before creating an unnecessary
			// backup. The decisive revalidation occurs again under reservation.
			approval.requireUnchanged(preview.previewRefresh(incomingArchive, layout));
			Path backupDirectory = config.dataRoot().resolve("backups").resolve("pre-import");
			Files.createDirectories(backupDirectory);
			SubjectImportBackupGate gate = new SubjectImportBackupGate(config);
			Certificate backup = gate.createVerifiedFullBackup(packageId, backupDirectory);
			SubjectImportAssetStager stager = new SubjectImportAssetStager(config);
			StagedPackage staged = stager.stageRefresh(approval, backup, incomingArchive, lock);
			SubjectImportPublicationJournal journal = new SubjectImportPublicationJournal(config);
			boolean committed = false;
			try {
				SubjectImportGraphWriter writer = new SubjectImportGraphWriter(database);
				try (Connection connection = database.openConnection()) {
					connection.setAutoCommit(false);
					try {
						reserveWriter(connection);
						approval.requireUnchanged(preview.previewRefresh(staged.archive(), layout));
						var physicalChanges = stager.managedRefreshChanges(plan);
						StagedPackage publication = stager.publicationStage(staged, approval);
						gate.requireIntact(packageId, backup);

						// Establish durable original-file recovery evidence
						// before exposing any filesystem changes.
						journal.prepareRefresh(publication, backup, physicalChanges, lock);
						var applied = writer.applyRefreshGraph(connection, approval, staged, layout, lock);
						requireRemovedRefreshPathsUnreferenced(connection, layout, physicalChanges);
						requireForeignKeyIntegrity(connection);
						gate.requireIntact(packageId, backup);

						// The database changes remain uncommitted while
						// the approved managed files are published.
						journal.publishFiles(packageId);
						journal.verifyPublished(packageId);
						gate.requireIntact(packageId, backup);
						recordRefreshPackage(connection, approval, backup);
						recordRefreshItems(connection, plan, applied);
						new SqliteImportedSubjectAuthorityRepository(database).recordImported(connection,
								plan.evidence().authority().subjectLocalId(), packageId);
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

				// Once committed, cleanup failure must never be represented
				// as a rolled-back import. Retain the recoverable journal.
				try {
					journal.recover(packageId, lock);
				} catch (SQLException | IOException cleanupFailure) {
					return new Result(packageId, true, false, backup.backupPath().toString());
				}
				return new Result(packageId, true, false, backup.backupPath().toString());
			} catch (SQLException | IOException | RuntimeException failure) {
				if (!committed) {
					try {

						// Preparation can itself fail after writing some
						// private evidence. Inspect the durable journal
						// rather than relying on an in-memory prepared flag.
						Path intent = staged.directory().resolve("publication.properties");
						if (Files.exists(intent, LinkOption.NOFOLLOW_LINKS)) {
							journal.recover(packageId, lock);
						} else if (Files.exists(staged.directory(), LinkOption.NOFOLLOW_LINKS)) {
							discardUnpublishedStaging(staged.directory());
						}
					} catch (SQLException | IOException recoveryFailure) {
						failure.addSuppressed(recoveryFailure);
					}
				}
				throw failure;
			}
		}
	}

	/**
	 * Acquires the shared publication lock and restores both kinds of interrupted
	 * package publication. The path argument must identify this root's Subject
	 * stage.
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
		try (var lock = ManagedAssetPublicationLock.acquire(config.dataRoot())) {
			ManagedAssetPublicationRecovery.recoverPending(config, lock);
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

	private void recordNewSubjectAuthority(Connection connection, SubjectImportApprovalPlan plan,
			Map<Key, UUID> canonical) throws SQLException {
		Key subjectKey = SubjectReconciliationReview.recordKey("SUBJECT", plan.reviewedIncoming().subjectId());
		UUID canonicalSubjectId = canonical.get(subjectKey);
		if (canonicalSubjectId == null) {
			throw new SQLException("Imported Subject has no canonical identity");
		}
		long subjectLocalId = new SqlitePortableIdentityRepository(database)
				.findByPortableId(connection, EntityType.SUBJECT, canonicalSubjectId)
				.orElseThrow(() -> new SQLException("Imported Subject identity is missing")).localId();
		new SqliteImportedSubjectAuthorityRepository(database).recordImported(connection, subjectLocalId,
				plan.reviewedIncoming().packageId());
	}

	/**
	 * Records the terminal package result and originating freshness evidence in the
	 * same graph transaction.
	 */
	private void recordPackage(Connection connection, SubjectImportApprovalPlan plan, Certificate backup, String note,
			ExamWorkManifest manifest) throws SQLException {
		var incoming = plan.reviewedIncoming();
		if (!manifest.packageId().equals(incoming.packageId())
				|| !manifest.originInstallationId().equals(incoming.originInstallationId())
				|| !manifest.subjectId().equals(incoming.subjectId())
				|| !manifest.subjectName().equals(incoming.subjectName())) {
			throw new SQLException("Decoded Subject snapshot differs from approved package");
		}
		String destinationFingerprint = plan.isNewSubjectInstallation() ? "0".repeat(64)
				: plan.reviewedMaster().contentSha256();

		// An absent destination has no previous Subject content.
		// The zero digest records that condition without fabricating
		// an existing master snapshot.
		PackageEntry entry = new PackageEntry(incoming.packageId(), incoming.archiveSha256(),
				incoming.originInstallationId(), incoming.subjectId(), destinationFingerprint, plan.approvalSha256(),
				PackageOutcome.APPLIED, backup.backupPath().toString(), note);

		// The decoded manifest belongs to the already verified private archive.
		// The approved fingerprint is bound to those exact bytes.
		OriginatingSnapshotEvidence evidence = new OriginatingSnapshotEvidence(manifest.createdAt(),
				incoming.contentSha256());
		if (!ledger.recordPackage(connection, entry, evidence)) {
			throw new SQLException("Package was recorded concurrently with import");
		}
	}

	/**
	 * Records the actual approved refresh operations and their canonical identity
	 * or retirement evidence.
	 */
	private void recordRefreshItems(Connection connection, SubjectRefreshPlan plan,
			Map<SubjectRefreshChangeClassifier.RecordKey, Long> applied) throws SQLException {
		UUID packageId = plan.evidence().incoming().fingerprint().packageId();
		SqlitePortableIdentityRepository identities = new SqlitePortableIdentityRepository(database);
		for (var change : plan.recordChanges()) {
			EntityType type = EntityType.valueOf(change.key().recordType());
			UUID canonicalId = change.key().recordId();
			UUID revision;
			String classification = switch (change.kind()) {
			case ADD -> "NEW";
			case UNCHANGED -> "IDENTICAL";
			case UPDATE, REMOVE, REVISION_ONLY -> "CONFLICT";
			};
			ItemOutcome outcome = switch (change.kind()) {
			case ADD -> ItemOutcome.INSERTED;
			case UNCHANGED -> ItemOutcome.PRESERVED;
			case UPDATE, REMOVE, REVISION_ONLY -> ItemOutcome.COPIED;
			};
			if (change.kind() == SubjectRefreshChangeClassifier.ChangeKind.REMOVE) {

				// The graph writer has verified the corresponding durable
				// retirement. Record the retired canonical revision.
				if (identities.findByPortableId(connection, type, canonicalId).isPresent()) {
					throw new SQLException("Removed refresh identity remains active");
				}
				revision = change.current().revisionId();
			} else {
				var identity = identities.findByPortableId(connection, type, canonicalId)
						.orElseThrow(() -> new SQLException("Missing applied refresh canonical identity"));
				Long appliedLocalId = applied.get(change.key());
				if (appliedLocalId == null) {

					// An unchanged global provider may no longer be
					// referenced by the refreshed Subject.
					if (type != EntityType.EXAM_PROVIDER
							|| change.kind() != SubjectRefreshChangeClassifier.ChangeKind.UNCHANGED) {
						throw new SQLException("Missing applied refresh record mapping");
					}
				} else if (identity.localId() != appliedLocalId.longValue()) {
					throw new SQLException("Applied refresh identity mapping differs");
				}
				revision = identity.revisionId();
			}
			String explanation = "Refresh operation: " + change.kind().name() + "; previous revision: "
					+ (change.current() == null ? "none" : change.current().revisionId()) + "; resulting revision: "
					+ (change.kind() == SubjectRefreshChangeClassifier.ChangeKind.REMOVE ? "retired" : revision);
			ledger.recordItem(connection, new ItemEntry(packageId, EntryKind.RECORD, type.name() + ":" + canonicalId,
					classification, CoordinatorDecision.ACCEPT, outcome, canonicalId, revision, explanation));
		}
		for (var change : plan.assetChanges()) {
			String classification = switch (change.kind()) {
			case ADD -> "NEW";
			case UNCHANGED -> "IDENTICAL";
			case UPDATE, REMOVE -> "CONFLICT";
			case REVISION_ONLY -> throw new SQLException("Invalid asset revision-only operation");
			};
			ItemOutcome outcome = change.kind() == SubjectRefreshChangeClassifier.ChangeKind.UNCHANGED
					? ItemOutcome.PRESERVED
					: ItemOutcome.COPIED;
			String previous = change.current() == null ? "none"
					: change.current().sha256() + " (" + change.current().size() + " bytes)";
			String incoming = change.incoming() == null ? "none"
					: change.incoming().sha256() + " (" + change.incoming().size() + " bytes)";
			String explanation = "Refresh operation: " + change.kind().name() + "; previous content: " + previous
					+ "; resulting content: " + incoming;
			ledger.recordItem(connection, new ItemEntry(packageId, EntryKind.ASSET, change.managedPath(),
					classification, CoordinatorDecision.ACCEPT, outcome, null, null, explanation));
		}
	}

	/**
	 * Records terminal refresh approval and originating freshness evidence within
	 * the graph transaction.
	 */
	private void recordRefreshPackage(Connection connection, SubjectRefreshPlan.Approval approval, Certificate backup)
			throws SQLException, IOException {
		SubjectRefreshPlan plan = approval.plan();
		var incomingEvidence = plan.evidence().incoming();
		var incoming = incomingEvidence.fingerprint();
		PackageEntry entry = new PackageEntry(incoming.packageId(), incoming.archiveSha256(),
				incoming.originInstallationId(), incoming.subjectId(), plan.destinationContentSha256(),
				approval.approvalSha256(), PackageOutcome.APPLIED, backup.backupPath().toString(),
				approval.approvalNote());
		OriginatingSnapshotEvidence evidence = new OriginatingSnapshotEvidence(incomingEvidence.createdAt(),
				incoming.contentSha256());
		if (!ledger.recordPackage(connection, entry, evidence)) {
			throw new SQLException("Refresh package was recorded concurrently");
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

	private void requireNewSubjectDestination(Connection connection, Fingerprint incoming) throws SQLException {
		Objects.requireNonNull(connection, "connection");
		Objects.requireNonNull(incoming, "incoming");
		if (connection.getAutoCommit()) {
			throw new IllegalStateException("New Subject installation requires " + "a caller-owned transaction");
		}
		Long existingSubjectId = SubjectSnapshotIntakePreview.findExistingSubject(connection, incoming);
		if (existingSubjectId != null) {

			// Neither a local name collision nor an existing
			// originating identity can be resolved through the
			// new-Subject installation route.
			throw new SQLException("A Subject matching " + incoming.subjectName()
					+ " or its originating identity already exists. " + "The new Subject import cannot proceed.");
		}
	}

	private void requireNewSubjectProviders(Connection connection, SubjectImportApprovalPlan plan,
			DecodedPackage decoded) throws SQLException {
		Map<UUID, UUID> current = resolveNewSubjectProviders(connection, decoded.records());
		Map<UUID, UUID> approved = new HashMap<>();
		for (Action action : plan.actions()) {
			if (!"RECORD".equals(action.key().kind()) || !action.key().value().startsWith("EXAM_PROVIDER:")) {
				continue;
			}
			if (action.operation() == SubjectImportApprovalPlan.Operation.LINK_ALIAS) {
				approved.put(action.incomingId(), action.masterId());
			} else if (action.operation() != SubjectImportApprovalPlan.Operation.INSERT_RECORD) {
				throw new SQLException("Unsupported new Subject provider operation");
			}
		}

		// Reject provider state that changed between approval
		// planning and the reserved installation transaction.
		if (!approved.equals(current)) {
			throw new SQLException("Shared examination providers changed " + "since import preparation");
		}
	}

	/**
	 * Recognises an exact previously committed refresh without changing authority,
	 * graph records, assets or backup history.
	 */
	private Result requireRefreshReplay(SubjectRefreshPlan.Approval approval, PackageEntry previous)
			throws SQLException, IOException {
		SubjectRefreshPlan plan = approval.plan();
		var incomingEvidence = plan.evidence().incoming();
		var incoming = incomingEvidence.fingerprint();
		if (!previous.packageId().equals(incoming.packageId())
				|| !previous.archiveSha256().equals(incoming.archiveSha256())
				|| !previous.originInstallationId().equals(incoming.originInstallationId())
				|| !previous.subjectPortableId().equals(incoming.subjectId())
				|| !previous.masterStateSha256().equals(plan.destinationContentSha256())
				|| !previous.approvalSha256().equals(approval.approvalSha256())) {
			throw new IOException(
					"Refresh package identity already recorded " + "with different approval or archive evidence");
		}
		if (previous.outcome() != PackageOutcome.APPLIED) {
			throw new IOException("This refresh package was previously rejected");
		}
		var recorded = ledger.findOriginatingSnapshotEvidence(previous.packageId())
				.orElseThrow(() -> new IOException("Recorded refresh has no originating freshness evidence"));
		var expected = new OriginatingSnapshotEvidence(incomingEvidence.createdAt(), incoming.contentSha256());
		if (!recorded.equals(expected)) {
			throw new IOException("Recorded refresh freshness evidence differs from approval");
		}

		// Replaying an older, already accepted package must not replace
		// the latest imported authority with that older package.
		return new Result(previous.packageId(), true, true, previous.backupArchivePath());
	}

	/**
	 * Prevents publication of a managed-file removal while any surviving
	 * authoritative database record still references that file.
	 */
	private void requireRemovedRefreshPathsUnreferenced(Connection connection, ManagedDataLayout layout,
			List<SubjectRefreshChangeClassifier.AssetChange> physicalChanges) throws SQLException, IOException {
		Set<Path> removed = new HashSet<>();
		for (var change : physicalChanges) {
			if (change.kind() == SubjectRefreshChangeClassifier.ChangeKind.REMOVE) {
				removed.add(layout.resolve(change.managedPath()));
			}
		}
		if (removed.isEmpty()) {
			return;
		}
		try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("""
				SELECT relative_path AS managed_path
				FROM source_documents

				UNION

				SELECT source_pdf_path AS managed_path
				FROM syllabus_versions
				WHERE source_pdf_path IS NOT NULL
				""")) {
			while (rows.next()) {
				String relativePath = rows.getString("managed_path");
				Path reference;
				try {
					reference = layout.resolve(relativePath);
				} catch (IllegalArgumentException | NullPointerException failure) {
					throw new IOException("Cannot verify surviving managed-file references", failure);
				}
				for (Path destination : removed) {
					boolean same = reference.equals(destination);
					if (!same && Files.isRegularFile(reference, LinkOption.NOFOLLOW_LINKS)
							&& Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)) {
						same = Files.isSameFile(reference, destination);
					}
					if (same) {
						throw new IOException("Approved refresh removal still has "
								+ "an authoritative database reference: " + relativePath);
					}
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

	private Map<UUID, UUID> resolveNewSubjectProviders(Connection connection, List<ExchangeRecord> records)
			throws SQLException {
		Map<UUID, UUID> resolved = new HashMap<>();
		SqlitePortableIdentityRepository identities = new SqlitePortableIdentityRepository(database);
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT id, provider_name
				FROM exam_providers
				WHERE provider_name = ? COLLATE NOCASE
				""")) {
			for (ExchangeRecord record : records) {
				if (!"EXAM_PROVIDER".equals(record.recordType())) {
					continue;
				}
				String providerName = record.values().path("provider_name").asText();
				if (providerName.isBlank()) {
					throw new SQLException("Incoming examination provider has no name");
				}
				statement.setString(1, providerName);
				try (ResultSet result = statement.executeQuery()) {
					if (!result.next()) {
						continue;
					}
					long localId = result.getLong("id");
					String existingName = result.getString("provider_name");

					// Do not silently treat a differing name as
					// identical provider content.
					if (!providerName.equals(existingName)) {
						throw new SQLException("Examination provider naming conflict: " + providerName);
					}
					if (result.next()) {
						throw new SQLException("Ambiguous shared examination provider: " + providerName);
					}
					UUID canonicalId = identities.findByLocalId(connection, EntityType.EXAM_PROVIDER, localId)
							.orElseThrow(() -> new SQLException("Shared provider identity is missing")).portableId();
					resolved.put(record.recordId(), canonicalId);
				}
			}
		}
		return Map.copyOf(resolved);
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
