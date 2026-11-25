package au.edu.eq.questionbank.repository.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Records which Subjects are read-only snapshots owned by another installation.
 *
 * <p>
 * An existing Subject without an imported-authority row remains locally
 * authoritative. An imported Subject retains the same origin installation and
 * origin Subject UUID across subsequent accepted snapshots.
 *
 * <p>
 * Registration must be performed within the approved Subject import
 * transaction. This repository does not itself authorise an import.
 */
public final class SqliteImportedSubjectAuthorityRepository {

	private final SqliteDatabase database;

	/**
	 * Creates a repository for the active application database.
	 *
	 * @param database initialised SQLite database
	 */
	public SqliteImportedSubjectAuthorityRepository(SqliteDatabase database) {
		this.database = Objects.requireNonNull(database, "database");
	}

	/**
	 * Reads imported Subject authority using the caller's existing database
	 * connection.
	 *
	 * <p>
	 * This allows refresh eligibility and transactional revalidation to inspect
	 * authority, package history and canonical identities within the same database
	 * view. The caller retains ownership of the connection.
	 *
	 * @param connection     caller-owned database connection
	 * @param subjectLocalId positive SQLite-local Subject ID
	 * @return imported authority, or empty for a locally authoritative Subject
	 * @throws SQLException             if reading authority fails
	 * @throws NullPointerException     if connection is null
	 * @throws IllegalArgumentException if the Subject ID is not positive
	 */
	public Optional<ImportedAuthority> find(Connection connection, long subjectLocalId) throws SQLException {
		Objects.requireNonNull(connection, "connection");
		requireSubjectId(subjectLocalId);
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT origin_installation_id,
				       origin_subject_portable_id,
				       latest_package_id,
				       archive_sha256,
				       imported_at
				FROM imported_subject_authorities
				WHERE subject_local_id = ?
				""")) {
			statement.setLong(1, subjectLocalId);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					return Optional.empty();
				}
				ImportedAuthority authority = new ImportedAuthority(subjectLocalId,
						UUID.fromString(result.getString("origin_installation_id")),
						UUID.fromString(result.getString("origin_subject_portable_id")),
						UUID.fromString(result.getString("latest_package_id")), result.getString("archive_sha256"),
						Instant.parse(result.getString("imported_at")));
				if (result.next()) {
					throw new SQLException("Duplicate imported authority for Subject " + subjectLocalId);
				}
				return Optional.of(authority);
			}
		}
	}

	/**
	 * Returns the imported authority for one local Subject.
	 *
	 * @param subjectLocalId positive SQLite-local Subject ID
	 * @return imported authority, or empty for a locally authoritative Subject
	 * @throws SQLException if reading authority fails
	 */
	public Optional<ImportedAuthority> find(long subjectLocalId) throws SQLException {
		try (Connection connection = database.openConnection()) {
			return find(connection, subjectLocalId);
		}
	}

	/**
	 * Reports whether another installation owns the selected Subject.
	 *
	 * @param subjectLocalId positive local Subject ID
	 * @return true when this Subject was imported from another authority
	 * @throws SQLException if the database cannot be inspected
	 */
	public boolean isImported(long subjectLocalId) throws SQLException {
		return find(subjectLocalId).isPresent();
	}

	/**
	 * Records a successful imported Subject snapshot inside the caller's existing
	 * transaction.
	 *
	 * <p>
	 * The corresponding package must already have an APPLIED entry in the Subject
	 * import ledger. Repeated imports from the same original Subject may advance
	 * the latest package, but ownership cannot change.
	 *
	 * <p>
	 * The caller must establish that this Subject is either newly imported or is
	 * already recorded as imported from the same authority. It must not use this
	 * operation to convert an ordinary local Subject implicitly.
	 *
	 * @param connection     caller-owned transactional connection
	 * @param subjectLocalId local Subject receiving the imported snapshot
	 * @param packageId      committed Subject import package UUID
	 * @throws SQLException if the package is not applied, provenance differs, or
	 *                      recording fails
	 */
	public void recordImported(Connection connection, long subjectLocalId, UUID packageId) throws SQLException {
		Objects.requireNonNull(connection, "connection");
		Objects.requireNonNull(packageId, "packageId");
		requireSubjectId(subjectLocalId);
		if (connection.getAutoCommit()) {
			throw new IllegalStateException("Imported authority must be recorded in the import transaction");
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO imported_subject_authorities (
				    subject_local_id,
				    origin_installation_id,
				    origin_subject_portable_id,
				    latest_package_id,
				    archive_sha256,
				    imported_at
				)
				SELECT ?,
				       packet.origin_installation_id,
				       packet.subject_portable_id,
				       packet.package_id,
				       packet.archive_sha256,
				       strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
				FROM subject_import_packages packet
				WHERE packet.package_id = ?
				  AND packet.outcome = 'APPLIED'
				ON CONFLICT(subject_local_id) DO UPDATE SET
				    latest_package_id = excluded.latest_package_id,
				    archive_sha256 = excluded.archive_sha256,
				    imported_at = excluded.imported_at
				WHERE imported_subject_authorities.origin_installation_id =
				          excluded.origin_installation_id
				  AND imported_subject_authorities.origin_subject_portable_id =
				          excluded.origin_subject_portable_id
				""")) {
			statement.setLong(1, subjectLocalId);
			statement.setString(2, packageId.toString());
			if (statement.executeUpdate() != 1) {
				throw new SQLException("Subject import has no approved package or its authority differs");
			}
		}
	}

	/**
	 * Rejects ordinary editing of a Subject imported from another installation.
	 *
	 * @param subjectLocalId positive local Subject ID
	 * @throws SQLException          if authority cannot be inspected
	 * @throws IllegalStateException if the Subject is externally authoritative
	 */
	public void requireLocallyAuthoritative(long subjectLocalId) throws SQLException {
		try (Connection connection = database.openConnection()) {
			requireLocallyAuthoritative(connection, subjectLocalId);
		}
	}

	/**
	 * Rejects ordinary editing of an imported Subject using the caller's current
	 * database view.
	 *
	 * <p>
	 * Mutation repositories use this overload immediately before their first write
	 * so authority validation participates in the same transaction as the proposed
	 * change. The caller retains ownership of the connection.
	 *
	 * @param connection     caller-owned database connection
	 * @param subjectLocalId positive local Subject ID
	 * @throws SQLException          if authority cannot be inspected
	 * @throws IllegalStateException if the Subject is externally authoritative
	 * @throws IllegalArgumentException if the Subject ID is not positive
	 * @throws NullPointerException  if the connection is null
	 */
	public void requireLocallyAuthoritative(Connection connection, long subjectLocalId) throws SQLException {
		if (find(connection, subjectLocalId).isPresent()) {
			throw new IllegalStateException("Subject " + subjectLocalId + " is an imported snapshot and is read-only. "
					+ "Edit the authoritative Subject on its originating installation.");
		}
	}

	/**
	 * Rejects ordinary editing of a syllabus version owned by an imported Subject.
	 *
	 * <p>
	 * The owning Subject is resolved from the persisted syllabus row so a stale or
	 * inconsistent domain object cannot bypass the authority boundary.
	 *
	 * @param connection        caller-owned database connection
	 * @param syllabusVersionId positive local syllabus-version ID
	 * @throws SQLException             if ownership or authority cannot be inspected
	 * @throws IllegalStateException    if the syllabus version does not exist or its
	 *                                  Subject is externally authoritative
	 * @throws IllegalArgumentException if the syllabus-version ID is not positive
	 * @throws NullPointerException     if the connection is null
	 */
	public void requireLocallyAuthoritativeSyllabusVersion(Connection connection, long syllabusVersionId)
			throws SQLException {
		Objects.requireNonNull(connection, "connection");
		if (syllabusVersionId < 1) {
			throw new IllegalArgumentException("Syllabus version ID must be positive");
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT subject_id
				FROM syllabus_versions
				WHERE id = ?
				""")) {
			statement.setLong(1, syllabusVersionId);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new IllegalStateException("Syllabus version does not exist: " + syllabusVersionId);
				}
				requireLocallyAuthoritative(connection, result.getLong("subject_id"));
			}
		}
	}

	private void requireSubjectId(long subjectLocalId) {
		if (subjectLocalId < 1) {
			throw new IllegalArgumentException("Subject ID must be positive");
		}
	}

	/**
	 * Persisted ownership and latest accepted snapshot information.
	 *
	 * @param subjectLocalId          destination SQLite Subject ID
	 * @param originInstallationId    authoritative installation UUID
	 * @param originSubjectPortableId originating Subject UUID
	 * @param latestPackageId         latest accepted Subject snapshot package
	 * @param archiveSha256           SHA-256 of the accepted snapshot
	 * @param importedAt              time the latest snapshot was accepted
	 */
	public record ImportedAuthority(long subjectLocalId, UUID originInstallationId, UUID originSubjectPortableId,
			UUID latestPackageId, String archiveSha256, Instant importedAt) {

		/**
		 * Validates imported provenance.
		 */
		public ImportedAuthority {
			if (subjectLocalId < 1) {
				throw new IllegalArgumentException("Subject ID must be positive");
			}
			Objects.requireNonNull(originInstallationId, "originInstallationId");
			Objects.requireNonNull(originSubjectPortableId, "originSubjectPortableId");
			Objects.requireNonNull(latestPackageId, "latestPackageId");
			Objects.requireNonNull(importedAt, "importedAt");
			if (archiveSha256 == null || !archiveSha256.matches("[0-9a-f]{64}")) {
				throw new IllegalArgumentException("Invalid snapshot archive digest");
			}
		}
	}
}
