package au.edu.eq.questionbank.repository.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;

/**
 * Preserves coordinator-issued portable identities during installation of newly
 * inserted contributor assignment records.
 *
 * <p>
 * Construct this binder before inserting any assignment graph rows, using the
 * caller-owned installation transaction. The constructor snapshots existing
 * local identity keys so that previously persisted identities cannot be
 * rebound.
 *
 * <p>
 * The original SQLite identity immutability trigger is temporarily replaced
 * only inside a savepoint. It is restored and verified before a successful
 * operation returns. Failure rolls back that savepoint.
 *
 * <p>
 * The caller retains responsibility for committing or rolling back the complete
 * graph installation and for preserving other transactional invariants.
 *
 * <p>
 * Instances are transaction-scoped and are not thread-safe.
 */
public final class SqliteContributorIdentityBinder {

	private final Set<Key> boundKeys = new HashSet<>();
	private final Connection connection;
	private final Set<Key> originalKeys;

	/**
	 * Captures identities that existed before contributor graph insertion.
	 *
	 * @param connection caller-owned, uncommitted installation transaction
	 * @throws SQLException          if existing identities cannot be inspected
	 * @throws IllegalStateException if the connection is in autocommit mode
	 */
	public SqliteContributorIdentityBinder(Connection connection) throws SQLException {
		this.connection = Objects.requireNonNull(connection, "connection");
		if (connection.getAutoCommit()) {
			throw new IllegalStateException("Contributor identity binding requires a transaction");
		}
		Set<Key> existing = new HashSet<>();
		try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("""
				SELECT entity_type, local_id
				FROM portable_entity_identities
				""")) {
			while (result.next()) {
				EntityType type;
				try {
					type = EntityType.valueOf(result.getString("entity_type"));
				} catch (IllegalArgumentException exception) {
					throw new SQLException("Unknown persisted identity type", exception);
				}
				existing.add(new Key(type, result.getLong("local_id")));
			}
		}
		originalKeys = Set.copyOf(existing);
	}

	/**
	 * Replaces generated identities of newly inserted graph rows with the original
	 * identities and revisions from the assignment.
	 *
	 * <p>
	 * The operation is atomic within a savepoint and never commits the caller's
	 * transaction. Previously existing local identity keys, already-bound keys,
	 * duplicate requested identities and retired identities are rejected.
	 *
	 * @param bindings issued identities for newly inserted local rows
	 * @throws SQLException          if any identity is absent, already existed,
	 *                               conflicts, or cannot be preserved safely
	 * @throws IllegalStateException if autocommit was enabled
	 */
	public void bindNew(List<Binding> bindings) throws SQLException {
		Objects.requireNonNull(bindings, "bindings");
		if (connection.getAutoCommit()) {
			throw new IllegalStateException("Contributor identity binding requires a transaction");
		}
		List<Binding> requested = List.copyOf(bindings);
		if (requested.isEmpty()) {
			return;
		}
		Set<Key> requestedKeys = new HashSet<>();
		Set<UUID> requestedPortableIds = new HashSet<>();
		Set<UUID> requestedRevisions = new HashSet<>();

		// Reject unsafe requests before changing any trigger.
		for (Binding binding : requested) {
			Key key = new Key(binding.entityType(), binding.localId());
			if (originalKeys.contains(key) || boundKeys.contains(key) || !requestedKeys.add(key)) {
				throw new SQLException("Cannot rebind an existing or repeated local identity: " + key);
			}
			if (!requestedPortableIds.add(binding.portableId()) || !requestedRevisions.add(binding.revisionId())) {
				throw new SQLException("Duplicate incoming portable identity or revision");
			}
			requireInsertedIdentity(binding);
			requireNotRetired(binding);
		}
		Savepoint savepoint = connection.setSavepoint();
		try {

			// SQLite schema changes participate in the savepoint.
			// The original trigger will reappear if rollback is needed.
			try (Statement statement = connection.createStatement()) {
				statement.execute("DROP TRIGGER app_portable_identity_immutable");
			}
			try (PreparedStatement update = connection.prepareStatement("""
					UPDATE portable_entity_identities
					SET portable_id = ?,
					    revision_id = ?
					WHERE entity_type = ?
					  AND local_id = ?
					  AND retired = 0
					""")) {
				for (Binding binding : requested) {
					update.setString(1, binding.portableId().toString());
					update.setString(2, binding.revisionId().toString());
					update.setString(3, binding.entityType().name());
					update.setLong(4, binding.localId());
					if (update.executeUpdate() != 1) {
						throw new SQLException(
								"Failed to bind inserted identity: " + binding.entityType() + ":" + binding.localId());
					}
				}
			}
			try (Statement statement = connection.createStatement()) {
				statement.execute(SqlitePortableIdentitySchema.identityImmutableTriggerSql());
			}

			// Verify all identity-maintenance triggers, not merely
			// the name of the trigger just recreated.
			SqlitePortableIdentitySchema.verifyTriggers(connection);
			connection.releaseSavepoint(savepoint);
			boundKeys.addAll(requestedKeys);
		} catch (SQLException | RuntimeException failure) {
			try {
				connection.rollback(savepoint);
			} catch (SQLException rollbackFailure) {
				failure.addSuppressed(rollbackFailure);
			}
			try {
				connection.releaseSavepoint(savepoint);
			} catch (SQLException releaseFailure) {
				failure.addSuppressed(releaseFailure);
			}
			throw failure;
		}
	}

	private void requireInsertedIdentity(Binding binding) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT retired
				FROM portable_entity_identities
				WHERE entity_type = ?
				  AND local_id = ?
				""")) {
			statement.setString(1, binding.entityType().name());
			statement.setLong(2, binding.localId());
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next() || result.getInt("retired") != 0) {
					throw new SQLException("Assignment identity has no newly inserted row: " + binding.entityType()
							+ ":" + binding.localId());
				}
				if (result.next()) {
					throw new SQLException("Duplicate local identity during installation");
				}
			}
		}
	}

	private void requireNotRetired(Binding binding) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT 1
				FROM portable_entity_retirements
				WHERE portable_id = ?
				   OR revision_id = ?
				""")) {
			statement.setString(1, binding.portableId().toString());
			statement.setString(2, binding.revisionId().toString());
			try (ResultSet result = statement.executeQuery()) {
				if (result.next()) {
					throw new SQLException("Cannot reuse a retired portable identity " + "or revision");
				}
			}
		}
	}

	/**
	 * Original portable identity assigned to an imported entity.
	 *
	 * @param entityType supported persistent entity type
	 * @param localId    newly inserted SQLite-local primary key
	 * @param portableId original coordinator-issued portable UUID
	 * @param revisionId original coordinator-issued revision UUID
	 */
	public record Binding(EntityType entityType, long localId, UUID portableId, UUID revisionId) {

		/**
		 * Validates incoming identity metadata.
		 *
		 * @throws NullPointerException     if a required value is absent
		 * @throws IllegalArgumentException if the local ID is invalid
		 */
		public Binding {
			Objects.requireNonNull(entityType, "entityType");
			Objects.requireNonNull(portableId, "portableId");
			Objects.requireNonNull(revisionId, "revisionId");
			if (localId < 1) {
				throw new IllegalArgumentException("Local identity must be positive");
			}
		}
	}

	private record Key(EntityType type, long localId) {
	}
}
