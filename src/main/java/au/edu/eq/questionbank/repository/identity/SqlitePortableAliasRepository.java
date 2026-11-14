package au.edu.eq.questionbank.repository.identity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.repository.sqlite.SqliteDirectChanges;

/**
 * Persists explicitly approved cross-installation entity aliases.
 *
 * <p>
 * An origin identity consists of its installation UUID, entity type and
 * original portable UUID. It may map to one canonical master UUID.
 *
 * <p>
 * This repository does not discover equivalent records. Approval must follow
 * independent identity reconciliation.
 */
public final class SqlitePortableAliasRepository {

	private final SqliteDatabase database;

	/**
	 * Creates an alias repository.
	 *
	 * @param database initialised master database
	 */
	public SqlitePortableAliasRepository(SqliteDatabase database) {
		this.database = Objects.requireNonNull(database, "database");
	}

	/**
	 * Approves an origin alias in a caller-owned SQLite transaction.
	 *
	 * <p>
	 * The caller retains responsibility for commit and rollback. Existing identical
	 * aliases remain unchanged. Local-origin identities cannot be redirected to
	 * unrelated master entities.
	 *
	 * @param connection           open transactional master connection
	 * @param originInstallationId originating installation UUID
	 * @param entityType           portable entity type
	 * @param originPortableId     incoming record UUID
	 * @param canonicalPortableId  approved canonical master UUID
	 * @param approvalNote         coordinator's approval rationale
	 * @return approved canonical UUID
	 * @throws SQLException             if persistence or validation fails
	 * @throws IllegalArgumentException if an identity or approval is invalid
	 * @throws IllegalStateException    if the transaction or identity mapping
	 *                                  violates the import constraints
	 */
	public UUID approveAlias(Connection connection, UUID originInstallationId, EntityType entityType,
			UUID originPortableId, UUID canonicalPortableId, String approvalNote) throws SQLException {
		Objects.requireNonNull(connection, "connection");
		Objects.requireNonNull(originInstallationId, "originInstallationId");
		Objects.requireNonNull(entityType, "entityType");
		Objects.requireNonNull(originPortableId, "originPortableId");
		Objects.requireNonNull(canonicalPortableId, "canonicalPortableId");
		if (connection.getAutoCommit()) {
			throw new IllegalStateException("Import alias approval requires a caller-owned transaction");
		}
		if (approvalNote == null || approvalNote.isBlank() || approvalNote.strip().length() > 1000) {
			throw new IllegalArgumentException("approvalNote must contain 1 to 1000 characters");
		}
		rejectConflictingOriginType(connection, originInstallationId, entityType, originPortableId);

		// Preserve the Codex hardening introduced before Issue #272.
		// An installation may not redirect its own portable identity
		// to a different canonical entity.
		requireConsistentLocalOrigin(connection, originInstallationId, entityType, originPortableId,
				canonicalPortableId);
		Optional<UUID> existing = findAlias(connection, originInstallationId, entityType, originPortableId);
		if (existing.isPresent()) {
			if (!existing.get().equals(canonicalPortableId)) {
				throw new IllegalStateException("Origin identity already maps to a different canonical entity");
			}
			return existing.get();
		}
		requireActiveCanonicalEntity(connection, entityType, canonicalPortableId);
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO portable_identity_aliases (
				    origin_installation_id,
				    entity_type,
				    origin_portable_id,
				    canonical_portable_id,
				    approval_note
				)
				VALUES (?, ?, ?, ?, ?)
				""")) {
			statement.setString(1, originInstallationId.toString());
			statement.setString(2, entityType.name());
			statement.setString(3, originPortableId.toString());
			statement.setString(4, canonicalPortableId.toString());
			statement.setString(5, approvalNote.strip());
			if (SqliteDirectChanges.executeUpdate(statement) != 1) {
				throw new SQLException("Could not persist approved identity alias");
			}
		}
		return canonicalPortableId;
	}

	/**
	 * Approves a durable origin-to-canonical identity mapping.
	 *
	 * <p>
	 * This convenience overload owns its transaction. Import services that must
	 * combine aliases with domain and ledger writes should use the overload
	 * accepting a caller-owned connection.
	 *
	 * @param originInstallationId originating installation UUID
	 * @param entityType           record type
	 * @param originPortableId     originating portable UUID
	 * @param canonicalPortableId  approved canonical master UUID
	 * @param approvalNote         non-blank approval rationale
	 * @return approved canonical UUID
	 * @throws SQLException             if persistence fails
	 * @throws IllegalArgumentException if an identity or approval is invalid
	 * @throws IllegalStateException    if an existing alias conflicts
	 */
	public UUID approveAlias(UUID originInstallationId, EntityType entityType, UUID originPortableId,
			UUID canonicalPortableId, String approvalNote) throws SQLException {
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				UUID approved = approveAlias(connection, originInstallationId, entityType, originPortableId,
						canonicalPortableId, approvalNote);
				connection.commit();
				return approved;
			} catch (SQLException | RuntimeException exception) {
				try {
					connection.rollback();
				} catch (SQLException rollbackFailure) {
					exception.addSuppressed(rollbackFailure);
				}
				throw exception;
			}
		}
	}

	/**
	 * Resolves an approved alias through a supplied master connection.
	 *
	 * <p>
	 * The caller may use the same transaction for alias resolution and subsequent
	 * Subject graph persistence.
	 *
	 * @param connection           open master database connection
	 * @param originInstallationId originating installation UUID
	 * @param entityType           portable entity type
	 * @param originPortableId     originating record UUID
	 * @return approved canonical identity, if present
	 * @throws SQLException if lookup fails or stored identity is invalid
	 */
	public Optional<UUID> findAlias(Connection connection, UUID originInstallationId, EntityType entityType,
			UUID originPortableId) throws SQLException {
		Objects.requireNonNull(connection, "connection");
		Objects.requireNonNull(originInstallationId, "originInstallationId");
		Objects.requireNonNull(entityType, "entityType");
		Objects.requireNonNull(originPortableId, "originPortableId");
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT canonical_portable_id
				FROM portable_identity_aliases
				WHERE origin_installation_id = ?
				  AND entity_type = ?
				  AND origin_portable_id = ?
				""")) {
			statement.setString(1, originInstallationId.toString());
			statement.setString(2, entityType.name());
			statement.setString(3, originPortableId.toString());
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					return Optional.empty();
				}
				UUID canonical;
				try {
					String stored = result.getString("canonical_portable_id");
					canonical = UUID.fromString(stored);
					if (!canonical.toString().equals(stored)) {
						throw new IllegalArgumentException("Noncanonical UUID");
					}
				} catch (IllegalArgumentException exception) {
					throw new SQLException("Invalid persisted canonical UUID", exception);
				}
				if (result.next()) {
					throw new SQLException("Duplicate origin identity alias");
				}
				return Optional.of(canonical);
			}
		}
	}

	/**
	 * Finds the canonical identity of a previously approved origin record.
	 *
	 * @param originInstallationId originating installation UUID
	 * @param entityType           origin record type
	 * @param originPortableId     origin record UUID
	 * @return approved canonical UUID, or empty if not mapped
	 * @throws SQLException if retrieval fails
	 */
	public Optional<UUID> findAlias(UUID originInstallationId, EntityType entityType, UUID originPortableId)
			throws SQLException {
		Objects.requireNonNull(originInstallationId, "originInstallationId");
		Objects.requireNonNull(entityType, "entityType");
		Objects.requireNonNull(originPortableId, "originPortableId");
		try (Connection connection = database.openConnection()) {
			return findAlias(connection, originInstallationId, entityType, originPortableId);
		}
	}

	private void rejectConflictingOriginType(Connection connection, UUID originInstallationId, EntityType entityType,
			UUID originPortableId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT entity_type
				FROM portable_identity_aliases
				WHERE origin_installation_id = ?
				  AND origin_portable_id = ?
				  AND entity_type <> ?
				LIMIT 1
				""")) {
			statement.setString(1, originInstallationId.toString());
			statement.setString(2, originPortableId.toString());
			statement.setString(3, entityType.name());
			try (ResultSet result = statement.executeQuery()) {
				if (result.next()) {
					throw new IllegalStateException("Origin UUID is already approved as "
							+ result.getString("entity_type") + " and cannot be approved as " + entityType.name());
				}
			}
		}
	}

	private void requireActiveCanonicalEntity(Connection connection, EntityType entityType, UUID canonicalPortableId)
			throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT entity_type
				FROM portable_entity_identities
				WHERE portable_id = ?
				  AND retired = 0
				""")) {
			statement.setString(1, canonicalPortableId.toString());
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new IllegalArgumentException("Canonical identity is not an active master entity");
				}
				if (!entityType.name().equals(result.getString("entity_type"))) {
					throw new IllegalArgumentException("Canonical entity type does not match origin type");
				}
				if (result.next()) {
					throw new SQLException("Duplicate canonical portable UUID");
				}
			}
		}
	}

	private void requireConsistentLocalOrigin(Connection connection, UUID originInstallationId, EntityType entityType,
			UUID originPortableId, UUID canonicalPortableId) throws SQLException {
		UUID localInstallationId;
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT installation_id
				FROM installation_identity
				WHERE singleton_id = 1
				"""); ResultSet result = statement.executeQuery()) {
			if (!result.next()) {
				throw new SQLException("Database has no installation identity");
			}
			String storedId = result.getString("installation_id");
			try {
				localInstallationId = UUID.fromString(storedId);
			} catch (IllegalArgumentException exception) {
				throw new SQLException("Invalid local installation identity", exception);
			}
			if (!localInstallationId.toString().equals(storedId)) {
				throw new SQLException("Noncanonical local installation identity");
			}
			if (result.next()) {
				throw new SQLException("Multiple local installation identities");
			}
		}
		if (!localInstallationId.equals(originInstallationId)) {
			return;
		}

		// An installation cannot claim that its own portable identity
		// belongs to a different canonical entity.
		if (!originPortableId.equals(canonicalPortableId)) {
			throw new IllegalStateException("Local-origin identity cannot alias a different canonical entity");
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT entity_type, retired
				FROM portable_entity_identities
				WHERE portable_id = ?
				""")) {
			statement.setString(1, originPortableId.toString());
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new IllegalStateException("Local-origin portable identity does not exist");
				}
				if (!entityType.name().equals(result.getString("entity_type")) || result.getInt("retired") != 0) {
					throw new IllegalStateException(
							"Local-origin identity is not an active entity of the requested type");
				}
				if (result.next()) {
					throw new SQLException("Duplicate local portable identity");
				}
			}
		}
	}
}
