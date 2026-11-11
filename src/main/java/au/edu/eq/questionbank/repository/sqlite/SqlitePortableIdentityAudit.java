package au.edu.eq.questionbank.repository.sqlite;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Verifies the live portable-identity graph against its SQLite domain rows.
 *
 * <p>
 * This audit is read-only. Missing identities and conflicting historical claims
 * must be reported, not reconstructed or silently resolved.
 */
final class SqlitePortableIdentityAudit {

	private static final List<EntityTable> ENTITIES = List.of(new EntityTable("SUBJECT", "subjects"),
			new EntityTable("SYLLABUS_VERSION", "syllabus_versions"),
			new EntityTable("CURRICULUM_NODE", "curriculum_nodes"), new EntityTable("EXAM_PROVIDER", "exam_providers"),
			new EntityTable("SOURCE_DOCUMENT", "source_documents"), new EntityTable("EXAM", "exams"),
			new EntityTable("EXAM_BOOKLET", "exam_booklets"), new EntityTable("QUESTION", "questions"),
			new EntityTable("ANSWER_FILE", "answer_files"), new EntityTable("ANSWER", "answers"),
			new EntityTable("SOURCE_QUESTION", "source_questions"),
			new EntityTable("SHARED_QUESTION_CONTEXT", "shared_question_contexts"),
			new EntityTable("CURRICULUM_MAPPING", "curriculum_mappings"),
			new EntityTable("QUESTION_IMAGE", "question_images"));

	private SqlitePortableIdentityAudit() {
	}

	static void verify(Connection connection) throws SQLException {
		Objects.requireNonNull(connection, "connection");
		for (EntityTable entity : ENTITIES) {
			verifyEntity(connection, entity);
		}
		verifyAliasTargets(connection);
		verifyCrossTableUniqueness(connection);
	}

	private static boolean exists(Connection connection, String sql) throws SQLException {
		try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
			return result.next();
		}
	}

	private static void requireUuid(String value, String description) throws SQLException {
		try {
			UUID uuid = UUID.fromString(value);
			if (!uuid.toString().equals(value)) {
				throw new IllegalArgumentException("Noncanonical UUID");
			}
		} catch (IllegalArgumentException exception) {
			throw new SQLException("Invalid " + description + ": " + value, exception);
		}
	}

	private static void verifyAliasTargets(Connection connection) throws SQLException {
		if (exists(connection, """
				SELECT 1
				FROM portable_identity_aliases a
				LEFT JOIN portable_entity_identities live
				    ON live.portable_id = a.canonical_portable_id
				LEFT JOIN portable_entity_retirements retired
				    ON retired.portable_id = a.canonical_portable_id
				WHERE (
				    live.portable_id IS NULL
				    AND retired.portable_id IS NULL
				)
				   OR (
				    live.portable_id IS NOT NULL
				    AND live.entity_type <> a.entity_type
				)
				   OR (
				    retired.portable_id IS NOT NULL
				    AND retired.entity_type <> a.entity_type
				)
				LIMIT 1
				""")) {
			throw new SQLException("Alias targets a missing or incorrect canonical entity");
		}
		if (exists(connection, """
				SELECT 1
				FROM portable_identity_aliases
				GROUP BY origin_installation_id, origin_portable_id
				HAVING COUNT(DISTINCT entity_type) > 1
				LIMIT 1
				""")) {
			throw new SQLException("An origin UUID has conflicting entity types");
		}
		try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("""
				SELECT
				    origin_installation_id,
				    origin_portable_id,
				    canonical_portable_id
				FROM portable_identity_aliases
				""")) {
			while (result.next()) {
				requireUuid(result.getString("origin_installation_id"), "origin installation UUID");
				requireUuid(result.getString("origin_portable_id"), "origin entity UUID");
				requireUuid(result.getString("canonical_portable_id"), "canonical entity UUID");
			}
		}
	}

	private static void verifyCrossTableUniqueness(Connection connection) throws SQLException {
		if (exists(connection, """
				SELECT 1
				FROM portable_entity_identities live
				JOIN portable_entity_retirements retired
				    ON retired.portable_id = live.portable_id
				    OR retired.revision_id = live.revision_id
				LIMIT 1
				""")) {
			throw new SQLException("Active identity conflicts with retired identity history");
		}
	}

	private static void verifyEntity(Connection connection, EntityTable entity) throws SQLException {

		// Entity and table names come only from this fixed list.
		String missing = """
				SELECT 1
				FROM %s domain_row
				LEFT JOIN portable_entity_identities identity_row
				    ON identity_row.entity_type = '%s'
				   AND identity_row.local_id = domain_row.id
				   AND identity_row.retired = 0
				WHERE identity_row.local_id IS NULL
				LIMIT 1
				""".formatted(entity.tableName(), entity.entityType());
		if (exists(connection, missing)) {
			throw new SQLException("Missing active portable identity for " + entity.tableName());
		}
		String orphan = """
				SELECT 1
				FROM portable_entity_identities identity_row
				WHERE identity_row.entity_type = '%s'
				  AND NOT EXISTS (
				      SELECT 1
				      FROM %s domain_row
				      WHERE domain_row.id = identity_row.local_id
				  )
				LIMIT 1
				""".formatted(entity.entityType(), entity.tableName());
		if (exists(connection, orphan)) {
			throw new SQLException("Orphan portable identity for " + entity.tableName());
		}
	}

	private record EntityTable(String entityType, String tableName) {
	}
}
