package au.edu.eq.questionbank.repository.identity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

/**
 * Resolves portable entity identities without changing the application's
 * existing SQLite-local integer primary keys.
 *
 * <p>
 * Creation and retirement of identities are enforced by database triggers. This
 * repository provides read-only identity resolution for export, reconciliation
 * and related services.
 */
public final class SqlitePortableIdentityRepository {

	private final SqliteDatabase database;

	/**
	 * Creates the identity repository.
	 *
	 * @param database initialised question-bank database
	 */
	public SqlitePortableIdentityRepository(SqliteDatabase database) {
		this.database = Objects.requireNonNull(database, "database");
	}

	/**
	 * Returns every active identity of a particular entity type.
	 *
	 * @param entityType entity type to retrieve
	 * @return immutable map keyed by SQLite-local integer ID
	 * @throws SQLException if retrieval fails
	 */
	public Map<Long, Identity> findAllByLocalId(EntityType entityType) throws SQLException {
		Objects.requireNonNull(entityType, "entityType");
		Map<Long, Identity> identities = new LinkedHashMap<>();
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT
						    entity_type,
						    local_id,
						    portable_id,
						    revision_id
						FROM portable_entity_identities
						WHERE entity_type = ?
						  AND retired = 0
						ORDER BY local_id
						""")) {
			statement.setString(1, entityType.name());
			try (ResultSet result = statement.executeQuery()) {
				while (result.next()) {
					Identity identity = readIdentity(result);
					if (identities.put(identity.localId(), identity) != null) {
						throw new SQLException("Duplicate local portable identity");
					}
				}
			}
		}
		return Collections.unmodifiableMap(identities);
	}

	/**
	 * Finds the portable identity of an existing local entity.
	 *
	 * @param entityType entity type
	 * @param localId    positive SQLite-local primary key
	 * @return matching identity, or empty when absent
	 * @throws SQLException if retrieval fails
	 */
	public Optional<Identity> findByLocalId(EntityType entityType, long localId) throws SQLException {
		Objects.requireNonNull(entityType, "entityType");
		if (localId < 1) {
			throw new IllegalArgumentException("localId must be positive");
		}
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT
						    entity_type,
						    local_id,
						    portable_id,
						    revision_id
						FROM portable_entity_identities
						WHERE entity_type = ?
						  AND local_id = ?
						  AND retired = 0
						""")) {
			statement.setString(1, entityType.name());
			statement.setLong(2, localId);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					return Optional.empty();
				}
				Identity identity = readIdentity(result);
				if (result.next()) {
					throw new SQLException("Duplicate identity for local record");
				}
				return Optional.of(identity);
			}
		}
	}

	/**
	 * Resolves a canonical portable UUID to its current local entity.
	 *
	 * @param entityType required entity type
	 * @param portableId portable entity UUID
	 * @return active entity identity, or empty if not active
	 * @throws SQLException if retrieval fails
	 */
	public Optional<Identity> findByPortableId(EntityType entityType, UUID portableId) throws SQLException {
		Objects.requireNonNull(entityType, "entityType");
		Objects.requireNonNull(portableId, "portableId");
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT
						    entity_type,
						    local_id,
						    portable_id,
						    revision_id
						FROM portable_entity_identities
						WHERE entity_type = ?
						  AND portable_id = ?
						  AND retired = 0
						""")) {
			statement.setString(1, entityType.name());
			statement.setString(2, portableId.toString());
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					return Optional.empty();
				}
				Identity identity = readIdentity(result);
				if (result.next()) {
					throw new SQLException("Duplicate portable UUID");
				}
				return Optional.of(identity);
			}
		}
	}

	/**
	 * Returns the durable installation identity stored in this database.
	 *
	 * @return installation UUID
	 * @throws SQLException if the stored identity is invalid or missing
	 */
	public UUID installationId() throws SQLException {
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT installation_id
						FROM installation_identity
						WHERE singleton_id = 1
						""")) {
			if (!result.next()) {
				throw new SQLException("Database has no installation identity");
			}
			UUID installationId = parseUuid(result.getString("installation_id"));
			if (result.next()) {
				throw new SQLException("Database contains multiple installation identities");
			}
			return installationId;
		}
	}

	/**
	 * Requires an active identity for a persisted local entity.
	 *
	 * @param entityType entity type
	 * @param localId    positive local primary key
	 * @return corresponding portable identity
	 * @throws SQLException if no identity exists or retrieval fails
	 */
	public Identity requireByLocalId(EntityType entityType, long localId) throws SQLException {
		return findByLocalId(entityType, localId)
				.orElseThrow(() -> new SQLException("Missing portable identity: " + entityType + ":" + localId));
	}

	private UUID parseUuid(String value) throws SQLException {
		try {
			UUID uuid = UUID.fromString(value);
			if (!uuid.toString().equals(value)) {
				throw new IllegalArgumentException("Noncanonical UUID");
			}
			return uuid;
		} catch (IllegalArgumentException exception) {
			throw new SQLException("Invalid persisted portable UUID", exception);
		}
	}

	private Identity readIdentity(ResultSet result) throws SQLException {
		EntityType type;
		try {
			type = EntityType.valueOf(result.getString("entity_type"));
		} catch (IllegalArgumentException exception) {
			throw new SQLException("Unknown persisted portable entity type", exception);
		}
		return new Identity(type, result.getLong("local_id"), parseUuid(result.getString("portable_id")),
				parseUuid(result.getString("revision_id")));
	}

	/**
	 * Types of persistent entities with portable identities.
	 */
	public enum EntityType {
		/** An answer associated with a question. */
		ANSWER,
		/** A source document containing examination answers. */
		ANSWER_FILE,
		/** A mapping between curriculum nodes. */
		CURRICULUM_MAPPING,
		/** A unit, topic, subtopic or descriptor. */
		CURRICULUM_NODE,
		/** An examination belonging to a subject. */
		EXAM,
		/** A question booklet belonging to an examination. */
		EXAM_BOOKLET,
		/** An organisation providing examinations. */
		EXAM_PROVIDER,
		/** An individual examination question. */
		QUESTION,
		/** An image belonging to a question. */
		QUESTION_IMAGE,
		/** Shared source content used by multiple questions. */
		SHARED_QUESTION_CONTEXT,
		/** A persistent reference to a source document. */
		SOURCE_DOCUMENT,
		/** A source question grouping related question parts. */
		SOURCE_QUESTION,
		/** An academic subject. */
		SUBJECT,
		/** A specific version of a subject syllabus. */
		SYLLABUS_VERSION
	}

	/**
	 * An active portable identity and its current revision.
	 *
	 * @param entityType portable entity type
	 * @param localId    SQLite-local integer ID
	 * @param portableId durable entity UUID
	 * @param revisionId current causal revision UUID
	 */
	public record Identity(EntityType entityType, long localId, UUID portableId, UUID revisionId) {

		/**
		 * Validates identity data.
		 */
		public Identity {
			Objects.requireNonNull(entityType, "entityType");
			Objects.requireNonNull(portableId, "portableId");
			Objects.requireNonNull(revisionId, "revisionId");
			if (localId < 1) {
				throw new IllegalArgumentException("localId must be positive");
			}
		}
	}
}
