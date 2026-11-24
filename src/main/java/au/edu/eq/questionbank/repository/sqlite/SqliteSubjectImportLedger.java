package au.edu.eq.questionbank.repository.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Persists durable, coordinator-approved Subject import history.
 *
 * <p>
 * Package headers and individual outcomes are written through a caller-owned
 * SQLite transaction. This allows later import services to commit the Subject
 * graph, aliases and ledger atomically.
 *
 * <p>
 * A duplicate package identity with unchanged archive content is an idempotent
 * no-op. Reusing an identity for different archive bytes is rejected. The
 * ledger does not perform domain-record imports.
 */
public final class SqliteSubjectImportLedger {

	private static final Set<String> CLASSIFICATIONS = Set.of("NEW", "IDENTICAL", "COMPATIBLE_ADDITION", "CONFLICT");
	private final SqliteDatabase database;

	/**
	 * Creates a ledger repository for the initialised application database.
	 *
	 * @param database initialised SQLite database
	 * @throws NullPointerException if database is null
	 */
	public SqliteSubjectImportLedger(SqliteDatabase database) {
		this.database = Objects.requireNonNull(database, "database");
	}

	/**
	 * Checks that audit text is meaningful and bounded.
	 */
	private static void requireNote(String note, String field) {
		if (note == null || note.isBlank() || note.length() > 4000) {
			throw new IllegalArgumentException(field + " must contain 1 to 4000 characters");
		}
	}

	/**
	 * Checks that a SHA-256 digest is in canonical hexadecimal form.
	 */
	private static void requireSha256(String digest, String field) {
		if (digest == null || !digest.matches("[0-9a-f]{64}")) {
			throw new IllegalArgumentException(field + " must be a SHA-256 digest");
		}
	}

	/**
	 * Retrieves a previously recorded package outcome using the caller's existing
	 * database connection.
	 *
	 * <p>
	 * The connection remains owned by the caller. This method does not commit, roll
	 * back or modify package history.
	 *
	 * @param connection caller-owned database connection
	 * @param packageId  immutable package identity
	 * @return the recorded package, or an empty result
	 * @throws SQLException         if reading the ledger fails
	 * @throws NullPointerException if either argument is null
	 */
	public Optional<PackageEntry> find(Connection connection, UUID packageId) throws SQLException {
		Objects.requireNonNull(connection, "connection");
		Objects.requireNonNull(packageId, "packageId");
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT package_id, archive_sha256,
				       origin_installation_id, subject_portable_id,
				       master_state_sha256, approval_sha256, outcome,
				       backup_archive_path, approval_note
				FROM subject_import_packages
				WHERE package_id = ?
				""")) {
			statement.setString(1, packageId.toString());
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					return Optional.empty();
				}
				return Optional.of(new PackageEntry(UUID.fromString(result.getString("package_id")),
						result.getString("archive_sha256"), UUID.fromString(result.getString("origin_installation_id")),
						UUID.fromString(result.getString("subject_portable_id")),
						result.getString("master_state_sha256"), result.getString("approval_sha256"),
						PackageOutcome.valueOf(result.getString("outcome")), result.getString("backup_archive_path"),
						result.getString("approval_note")));
			}
		}
	}

	/**
	 * Retrieves a previously recorded package outcome.
	 *
	 * @param packageId immutable package identity
	 * @return the existing package or an empty result
	 * @throws SQLException if reading the ledger fails
	 */
	public Optional<PackageEntry> find(UUID packageId) throws SQLException {
		try (Connection connection = database.openConnection()) {
			return find(connection, packageId);
		}
	}

	/**
	 * Retrieves all recorded decisions for a package.
	 *
	 * @param packageId immutable package identity
	 * @return outcomes ordered by entry kind and key
	 * @throws SQLException if reading the ledger fails
	 */
	public List<ItemEntry> findItems(UUID packageId) throws SQLException {
		Objects.requireNonNull(packageId, "packageId");
		List<ItemEntry> items = new ArrayList<>();
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT entry_kind, entry_key, classification,
						       coordinator_decision, result,
						       canonical_portable_id, master_revision_id,
						       explanation
						FROM subject_import_decisions
						WHERE package_id = ?
						ORDER BY entry_kind, entry_key
						""")) {
			statement.setString(1, packageId.toString());
			try (ResultSet result = statement.executeQuery()) {
				while (result.next()) {
					items.add(new ItemEntry(packageId, EntryKind.valueOf(result.getString("entry_kind")),
							result.getString("entry_key"), result.getString("classification"),
							CoordinatorDecision.valueOf(result.getString("coordinator_decision")),
							ItemOutcome.valueOf(result.getString("result")),
							optionalUuid(result.getString("canonical_portable_id")),
							optionalUuid(result.getString("master_revision_id")), result.getString("explanation")));
				}
			}
		}
		return List.copyOf(items);
	}

	/**
	 * Retrieves originating freshness evidence in the caller's database view.
	 *
	 * <p>
	 * Older ledger entries have no such evidence. The local completion time must
	 * never be substituted for the originating creation timestamp.
	 *
	 * @param connection caller-owned database connection
	 * @param packageId  immutable package identity
	 * @return recorded evidence, or empty for an unknown package or legacy entry
	 * @throws SQLException         if evidence cannot be read or is invalid
	 * @throws NullPointerException if either argument is null
	 */
	public Optional<OriginatingSnapshotEvidence> findOriginatingSnapshotEvidence(Connection connection, UUID packageId)
			throws SQLException {
		Objects.requireNonNull(connection, "connection");
		Objects.requireNonNull(packageId, "packageId");
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT originating_snapshot_created_at,
				       originating_snapshot_content_sha256
				FROM subject_import_packages
				WHERE package_id = ?
				""")) {
			statement.setString(1, packageId.toString());
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					return Optional.empty();
				}
				String timestamp = result.getString(1);
				String digest = result.getString(2);
				if (timestamp == null && digest == null) {
					return Optional.empty();
				}
				if (timestamp == null || digest == null) {
					throw new SQLException("Incomplete originating snapshot freshness evidence");
				}
				try {
					return Optional.of(new OriginatingSnapshotEvidence(Instant.parse(timestamp), digest));
				} catch (DateTimeParseException | IllegalArgumentException failure) {
					throw new SQLException("Invalid originating snapshot freshness evidence", failure);
				}
			}
		}
	}

	/**
	 * Retrieves the originating freshness evidence for a package.
	 *
	 * @param packageId immutable package identity
	 * @return recorded evidence, or empty for an unknown package or legacy entry
	 * @throws SQLException         if evidence cannot be read or is invalid
	 * @throws NullPointerException if packageId is null
	 */
	public Optional<OriginatingSnapshotEvidence> findOriginatingSnapshotEvidence(UUID packageId) throws SQLException {
		try (Connection connection = database.openConnection()) {
			return findOriginatingSnapshotEvidence(connection, packageId);
		}
	}

	/**
	 * Adds one reviewed record or asset outcome to the caller's transaction.
	 *
	 * <p>
	 * A corresponding package header must exist in the same transaction. Duplicate
	 * outcome keys are rejected by the database primary key.
	 *
	 * @param connection caller-owned transactional connection
	 * @param item       immutable decision and result
	 * @throws SQLException if persistence fails
	 */
	public void recordItem(Connection connection, ItemEntry item) throws SQLException {
		requireTransaction(connection);
		Objects.requireNonNull(item, "item");
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO subject_import_decisions (
				    package_id, entry_kind, entry_key,
				    classification, coordinator_decision, result,
				    canonical_portable_id, master_revision_id, explanation
				)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
				""")) {
			statement.setString(1, item.packageId().toString());
			statement.setString(2, item.kind().name());
			statement.setString(3, item.key());
			statement.setString(4, item.classification());
			statement.setString(5, item.decision().name());
			statement.setString(6, item.outcome().name());
			statement.setString(7, nullableUuid(item.canonicalId()));
			statement.setString(8, nullableUuid(item.masterRevisionId()));
			statement.setString(9, item.explanation());
			statement.executeUpdate();
		}
	}

	/**
	 * Records a package outcome without originating freshness evidence.
	 *
	 * <p>
	 * Retains the legacy ledger contract. Production Subject snapshot imports use
	 * the overload that records verified originating evidence. An identical
	 * previously recorded package is not changed.
	 *
	 * @param connection caller-owned transactional connection
	 * @param entry      package outcome
	 * @return true if inserted, false if identical archive bytes were recorded
	 * @throws SQLException if the identity was reused or persistence fails
	 */
	public boolean recordPackage(Connection connection, PackageEntry entry) throws SQLException {
		return recordPackage(connection, entry, null);
	}

	/**
	 * Records a package outcome and verified originating freshness evidence in one
	 * insert within the caller's transaction.
	 *
	 * <p>
	 * Only the caller that has validated the exact archive may supply freshness
	 * evidence. A null evidence value represents unknown legacy evidence. Replaying
	 * an existing package never changes its history, including unknown evidence.
	 *
	 * @param connection caller-owned transactional connection
	 * @param entry      package outcome
	 * @param evidence   verified originating evidence, or null for legacy entries
	 * @return true if inserted, false if the identical archive was already recorded
	 * @throws SQLException          if package identity or known evidence
	 *                               conflicts, or persistence fails
	 * @throws IllegalStateException if the connection is in auto-commit mode
	 * @throws NullPointerException  if connection or entry is null
	 */
	public boolean recordPackage(Connection connection, PackageEntry entry, OriginatingSnapshotEvidence evidence)
			throws SQLException {
		requireTransaction(connection);
		Objects.requireNonNull(entry, "entry");
		Optional<PackageEntry> existing = find(connection, entry.packageId());
		if (existing.isPresent()) {
			PackageEntry previous = existing.orElseThrow();
			if (!previous.archiveSha256().equals(entry.archiveSha256())) {
				throw new SQLException("Package ID already belongs to different archive content");
			}
			if (evidence != null) {
				var recorded = findOriginatingSnapshotEvidence(connection, entry.packageId());
				if (recorded.isPresent() && !recorded.orElseThrow().equals(evidence)) {
					throw new SQLException("Package ID already has different originating freshness evidence");
				}
			}
			return false;
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO subject_import_packages (
				    package_id, archive_sha256, origin_installation_id,
				    subject_portable_id, master_state_sha256,
				    approval_sha256, outcome, backup_archive_path,
				    approval_note, originating_snapshot_created_at,
				    originating_snapshot_content_sha256
				)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""")) {
			statement.setString(1, entry.packageId().toString());
			statement.setString(2, entry.archiveSha256());
			statement.setString(3, entry.originInstallationId().toString());
			statement.setString(4, entry.subjectPortableId().toString());
			statement.setString(5, entry.masterStateSha256());
			statement.setString(6, entry.approvalSha256());
			statement.setString(7, entry.outcome().name());
			statement.setString(8, entry.backupArchivePath());
			statement.setString(9, entry.approvalNote());
			statement.setString(10, evidence == null ? null : evidence.createdAt().toString());
			statement.setString(11, evidence == null ? null : evidence.contentSha256());
			statement.executeUpdate();
		}
		return true;
	}

	/**
	 * Converts an optional UUID to its canonical persistence form.
	 */
	private String nullableUuid(UUID value) {
		return value == null ? null : value.toString();
	}

	/**
	 * Converts a nullable database UUID into its domain representation.
	 */
	private UUID optionalUuid(String value) {
		return value == null ? null : UUID.fromString(value);
	}

	/**
	 * Ensures the caller controls commit and rollback.
	 */
	private void requireTransaction(Connection connection) throws SQLException {
		Objects.requireNonNull(connection, "connection");
		if (connection.getAutoCommit()) {
			throw new IllegalStateException("Subject import ledger writes require an explicit transaction");
		}
	}

	/**
	 * Records the coordinator's explicit review decision.
	 */
	public enum CoordinatorDecision {
		/** Proposal accepted for a supported operation. */
		ACCEPT,
		/** Proposal explicitly rejected. */
		REJECT
	}

	/**
	 * Identifies the type of reviewed entry.
	 */
	public enum EntryKind {
		/** One portable domain record. */
		RECORD,
		/** One logical packaged asset reference. */
		ASSET
	}

	/**
	 * Identifies the result of one reviewed record or asset.
	 */
	public enum ItemOutcome {
		/** New canonical master record inserted. */
		INSERTED,
		/** Origin identity linked to an existing master identity. */
		ALIAS_LINKED,
		/** Existing master record or asset retained unchanged. */
		PRESERVED,
		/** Verified packaged asset published in managed storage. */
		COPIED,
		/** Coordinator rejected the proposed change. */
		REJECTED,
		/** An unsafe or unresolved operation was blocked. */
		BLOCKED
	}

	/**
	 * Originating evidence retained for an accepted or reviewed Subject snapshot.
	 *
	 * @param createdAt     creation instant from the validated originating manifest
	 * @param contentSha256 verified semantic snapshot SHA-256 digest
	 */
	public record OriginatingSnapshotEvidence(Instant createdAt, String contentSha256) {

		/**
		 * Validates complete originating freshness evidence.
		 *
		 * @throws NullPointerException     if createdAt is null
		 * @throws IllegalArgumentException if the content digest is invalid
		 */
		public OriginatingSnapshotEvidence {
			Objects.requireNonNull(createdAt, "createdAt");
			requireSha256(contentSha256, "contentSha256");
		}
	}

	/**
	 * Identifies the terminal package outcome.
	 */
	public enum PackageOutcome {
		/** Approved changes were committed to the master. */
		APPLIED,
		/** The reviewed package was rejected without graph mutation. */
		REJECTED
	}

	/**
	 * Immutable terminal package provenance.
	 *
	 * @param packageId            incoming immutable package UUID
	 * @param archiveSha256        digest of the original archive bytes
	 * @param originInstallationId originating installation UUID
	 * @param subjectPortableId    originating Subject UUID
	 * @param masterStateSha256    reviewed master-state fingerprint
	 * @param approvalSha256       approved decision-plan fingerprint
	 * @param outcome              terminal package outcome
	 * @param backupArchivePath    verified full-backup location for applied work
	 * @param approvalNote         coordinator's audit explanation
	 */
	public record PackageEntry(UUID packageId, String archiveSha256, UUID originInstallationId, UUID subjectPortableId,
			String masterStateSha256, String approvalSha256, PackageOutcome outcome, String backupArchivePath,
			String approvalNote) {

		/**
		 * Validates immutable provenance and required backup evidence.
		 */
		public PackageEntry {
			Objects.requireNonNull(packageId, "packageId");
			Objects.requireNonNull(originInstallationId, "originInstallationId");
			Objects.requireNonNull(subjectPortableId, "subjectPortableId");
			Objects.requireNonNull(outcome, "outcome");
			requireSha256(archiveSha256, "archiveSha256");
			requireSha256(masterStateSha256, "masterStateSha256");
			requireSha256(approvalSha256, "approvalSha256");
			requireNote(approvalNote, "approvalNote");
			if (outcome == PackageOutcome.APPLIED && (backupArchivePath == null || backupArchivePath.isBlank())) {
				throw new IllegalArgumentException("Applied imports require a verified full-backup path");
			}
			if (outcome == PackageOutcome.REJECTED && backupArchivePath != null) {
				throw new IllegalArgumentException("Rejected imports must not claim a publication backup");
			}
		}
	}

	/**
	 * Immutable audit result for a reviewed record or asset.
	 *
	 * @param packageId        owning package UUID
	 * @param kind             record or asset
	 * @param key              original review key
	 * @param classification   original reconciliation classification
	 * @param decision         coordinator's decision
	 * @param outcome          actual processing result
	 * @param canonicalId      resulting master UUID, where applicable
	 * @param masterRevisionId resulting master revision, where applicable
	 * @param explanation      durable justification or result explanation
	 */
	public record ItemEntry(UUID packageId, EntryKind kind, String key, String classification,
			CoordinatorDecision decision, ItemOutcome outcome, UUID canonicalId, UUID masterRevisionId,
			String explanation) {

		/**
		 * Validates an individual recorded import outcome.
		 */
		public ItemEntry {
			Objects.requireNonNull(packageId, "packageId");
			Objects.requireNonNull(kind, "kind");
			Objects.requireNonNull(decision, "decision");
			Objects.requireNonNull(outcome, "outcome");
			if (key == null || key.isBlank() || key.length() > 1024) {
				throw new IllegalArgumentException("Invalid review entry key");
			}
			if (!CLASSIFICATIONS.contains(classification)) {
				throw new IllegalArgumentException("Unsupported classification: " + classification);
			}
			requireNote(explanation, "explanation");
		}
	}
}
