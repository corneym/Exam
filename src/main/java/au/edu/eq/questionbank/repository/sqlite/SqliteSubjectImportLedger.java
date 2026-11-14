package au.edu.eq.questionbank.repository.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
	 * Creates a ledger repository.
	 *
	 * @param database initialised SQLite database at schema v27
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
	 * Records an immutable package outcome in the caller's transaction.
	 *
	 * <p>
	 * The caller must check the returned value before writing individual outcomes.
	 * An already recorded package must not be applied again.
	 *
	 * @param connection caller-owned transactional connection
	 * @param entry      approved package result
	 * @return true if inserted, false if the identical package was recorded
	 * @throws SQLException if the identifier is reused for different bytes or
	 *                      persistence otherwise fails
	 */
	public boolean recordPackage(Connection connection, PackageEntry entry) throws SQLException {
		requireTransaction(connection);
		Objects.requireNonNull(entry, "entry");
		Optional<PackageEntry> existing = find(connection, entry.packageId());
		if (existing.isPresent()) {
			PackageEntry previous = existing.get();
			if (!previous.archiveSha256().equals(entry.archiveSha256())) {
				throw new SQLException("Package ID already belongs to different archive content");
			}
			return false;
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO subject_import_packages (
				    package_id, archive_sha256, origin_installation_id,
				    subject_portable_id, master_state_sha256,
				    approval_sha256, outcome, backup_archive_path,
				    approval_note
				)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
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
			statement.executeUpdate();
		}
		return true;
	}

	/**
	 * Finds a package using the caller's existing database connection.
	 */
	private Optional<PackageEntry> find(Connection connection, UUID packageId) throws SQLException {
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
