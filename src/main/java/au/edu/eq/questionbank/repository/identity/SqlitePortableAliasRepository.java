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
	 * Approves a durable origin-to-canonical identity mapping.
	 *
	 * <p>
	 * Reapproving an identical mapping is idempotent. Reassigning an existing
	 * origin identity to another canonical entity is rejected.
	 *
	 * @param originInstallationId originating installation UUID
	 * @param entityType           record type
	 * @param originPortableId     record UUID supplied by origin
	 * @param canonicalPortableId  approved canonical master UUID
	 * @param approvalNote         non-blank approval rationale
	 * @return approved canonical UUID
	 * @throws SQLException             if persistence fails
	 * @throws IllegalArgumentException if the proposed canonical identity is
	 *                                  absent, of another type, or approval is
	 *                                  invalid
	 * @throws IllegalStateException    if an existing alias conflicts
	 */
	public UUID approveAlias(UUID originInstallationId, EntityType entityType, UUID originPortableId,
			UUID canonicalPortableId, String approvalNote) throws SQLException {
		Objects.requireNonNull(originInstallationId, "originInstallationId");
		Objects.requireNonNull(entityType, "entityType");
		Objects.requireNonNull(originPortableId, "originPortableId");
		Objects.requireNonNull(canonicalPortableId, "canonicalPortableId");
		if (approvalNote == null || approvalNote.isBlank() || approvalNote.strip().length() > 1000) {
			throw new IllegalArgumentException("approvalNote must contain 1 to 1000 characters");
		}
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {

				// The same originating entity cannot be approved
				// under two different domain types.
				rejectConflictingOriginType(connection, originInstallationId, entityType, originPortableId);
				Optional<UUID> existing = findAlias(connection, originInstallationId, entityType, originPortableId);
				if (existing.isPresent()) {
					if (!existing.get().equals(canonicalPortableId)) {
						throw new IllegalStateException(
								"Origin identity already maps to a " + "different canonical entity");
					}

					// Preserve the original approval even if its
					// canonical target has subsequently been retired.
					connection.commit();
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
				connection.commit();
				return canonicalPortableId;
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

	private Optional<UUID> findAlias(Connection connection, UUID originInstallationId, EntityType entityType,
			UUID originPortableId) throws SQLException {
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
}
