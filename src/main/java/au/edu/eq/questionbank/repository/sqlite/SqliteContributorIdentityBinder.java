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
		bindNew(bindings, null);
	}

	/**
	 * Restores coordinator-issued identities for a fresh assignment baseline.
	 *
	 * <p>
	 * A previously cache-evicted identity may be reattached only when its durable
	 * eviction evidence is reattachable and matches the incoming master, Subject
	 * and, for Exam-owned records, Exam authority. Authoritatively retired
	 * identities remain unavailable. New identities continue to use the ordinary
	 * binding rules.
	 *
	 * @param bindings             issued identities for newly inserted local rows
	 * @param masterInstallationId issuing coordinator installation identity
	 * @param subjectPortableId    assigned Subject identity
	 * @param examPortableId       assigned Exam identity
	 * @throws SQLException          if identity history or authority does not
	 *                               permit the binding
	 * @throws IllegalStateException if autocommit was enabled
	 * @throws NullPointerException  if an argument is null
	 */
	public void bindAssignment(List<Binding> bindings, UUID masterInstallationId, UUID subjectPortableId,
			UUID examPortableId) throws SQLException {
		bindNew(bindings,
				new ReattachmentScope(Objects.requireNonNull(masterInstallationId, "masterInstallationId"),
						Objects.requireNonNull(subjectPortableId, "subjectPortableId"),
						Objects.requireNonNull(examPortableId, "examPortableId")));
	}

	private void bindNew(List<Binding> bindings, ReattachmentScope scope) throws SQLException {
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
			requireAvailable(binding, scope);
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

	private void requireAvailable(Binding binding, ReattachmentScope scope) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT 1
				FROM portable_entity_retirements
				WHERE portable_id = ? OR revision_id = ?
				LIMIT 1
				""")) {
			statement.setString(1, binding.portableId().toString());
			statement.setString(2, binding.revisionId().toString());
			try (ResultSet result = statement.executeQuery()) {
				if (result.next()) {
					throw new SQLException("Cannot reuse an authoritatively retired portable identity or revision");
				}
			}
		}
		requireRevisionHistory(binding);
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT eviction.entity_type,
				       eviction.authority_installation_id,
				       eviction.reattachable,
				       operation_exam.portable_id AS operation_exam_portable_id,
				       terminal.master_installation_id,
				       terminal.subject_portable_id,
				       terminal.exam_portable_id
				FROM portable_entity_cache_evictions eviction
				JOIN corpus_disposal_operations operation
				  ON operation.operation_id = eviction.operation_id
				JOIN corpus_disposal_entities operation_exam
				  ON operation_exam.operation_id = operation.operation_id
				 AND operation_exam.entity_type = 'EXAM'
				LEFT JOIN contributor_return_packages terminal
				  ON terminal.package_id = operation.terminal_return_package_id
				WHERE eviction.portable_id = ?
				ORDER BY eviction.evicted_at, eviction.operation_id
				""")) {
			statement.setString(1, binding.portableId().toString());
			try (ResultSet rows = statement.executeQuery()) {
				boolean evicted = false;
				while (rows.next()) {
					evicted = true;
					if (scope == null || rows.getInt("reattachable") != 1
							|| !binding.entityType().name().equals(rows.getString("entity_type"))
							|| !scope.masterInstallationId().toString()
									.equals(rows.getString("authority_installation_id"))
							|| !scope.examPortableId().toString().equals(rows.getString("operation_exam_portable_id"))
							|| (rows.getString("master_installation_id") != null && !scope.masterInstallationId()
									.toString().equals(rows.getString("master_installation_id")))
							|| (rows.getString("subject_portable_id") != null && !scope.subjectPortableId().toString()
									.equals(rows.getString("subject_portable_id")))
							|| (rows.getString("exam_portable_id") != null
									&& !scope.examPortableId().toString().equals(rows.getString("exam_portable_id")))) {
						throw new SQLException("Cache-evicted identity authority does not match the assignment");
					}
				}
				if (evicted && scope == null) {
					throw new SQLException("Cannot reuse a cache-evicted identity outside assignment installation");
				}
			}
		}
	}

	private void requireRevisionHistory(Binding binding) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT portable_id, entity_type
				FROM portable_revision_history
				WHERE revision_id = ?
				""")) {
			statement.setString(1, binding.revisionId().toString());
			try (ResultSet row = statement.executeQuery()) {
				if (row.next() && (!binding.portableId().toString().equals(row.getString("portable_id"))
						|| !binding.entityType().name().equals(row.getString("entity_type")))) {
					throw new SQLException("Portable revision belongs to another historical entity");
				}
				if (row.next()) {
					throw new SQLException("Portable revision history is not unique");
				}
			}
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT 1
				FROM portable_revision_history
				WHERE portable_id = ? AND entity_type <> ?
				LIMIT 1
				""")) {
			statement.setString(1, binding.portableId().toString());
			statement.setString(2, binding.entityType().name());
			try (ResultSet row = statement.executeQuery()) {
				if (row.next()) {
					throw new SQLException("Portable identity belongs to another historical entity type");
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

	private record ReattachmentScope(UUID masterInstallationId, UUID subjectPortableId, UUID examPortableId) {
	}
}
