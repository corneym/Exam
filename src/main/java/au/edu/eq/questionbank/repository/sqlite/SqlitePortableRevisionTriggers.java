package au.edu.eq.questionbank.repository.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Installs and verifies transactional portable-record revision triggers.
 *
 * <p>
 * Direct changes advance an entity's revision. Mutations to owned child tables
 * advance the owning entity's revision. No-op updates do not advance revisions.
 *
 * <p>
 * Trigger definitions are installed through JDBC because the existing SQL
 * script executor does not parse CREATE TRIGGER bodies.
 */
final class SqlitePortableRevisionTriggers {

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
	private static final List<ChildTable> CHILDREN = List.of(
			new ChildTable("question_regions", "question_id", "QUESTION"),
			new ChildTable("question_content_parts", "question_id", "QUESTION"),
			new ChildTable("question_images", "question_id", "QUESTION"),
			new ChildTable("question_output_exclusions", "question_id", "QUESTION"),
			new ChildTable("answer_regions", "answer_id", "ANSWER"),
			new ChildTable("shared_question_context_regions", "shared_context_id", "SHARED_QUESTION_CONTEXT"),
			new ChildTable("shared_question_context_images", "shared_context_id", "SHARED_QUESTION_CONTEXT"),
			new ChildTable("shared_question_context_content_parts", "shared_context_id", "SHARED_QUESTION_CONTEXT"),
			new ChildTable("booklet_applicability_reviews", "booklet_id", "EXAM_BOOKLET"),
			new ChildTable("curriculum_mapping_reviews", "source_node_id", "CURRICULUM_NODE"));
	private static final String UUID_SQL = """
			lower(hex(randomblob(4))) || '-' ||
			lower(hex(randomblob(2))) || '-4' ||
			substr(lower(hex(randomblob(2))), 2) || '-' ||
			substr('89ab', (random() & 3) + 1, 1) ||
			substr(lower(hex(randomblob(2))), 2) || '-' ||
			lower(hex(randomblob(6)))
			""";

	private SqlitePortableRevisionTriggers() {
	}

	/**
	 * Installs revision triggers inside the caller's migration transaction.
	 *
	 * @param connection migration connection
	 * @throws SQLException if installation fails
	 */
	static void install(Connection connection) throws SQLException {
		Objects.requireNonNull(connection, "connection");
		try (Statement statement = connection.createStatement()) {
			for (EntityTable entity : ENTITIES) {
				statement.executeUpdate(entityUpdateSql(connection, entity));
			}
			for (ChildTable child : CHILDREN) {
				statement.executeUpdate(childInsertSql(child));
				statement.executeUpdate(childUpdateSql(connection, child));
				statement.executeUpdate(childDeleteSql(child));
			}
		}
	}

	/**
	 * Verifies all required revision triggers against the current database table
	 * definitions.
	 *
	 * @param connection verification connection
	 * @throws SQLException if triggers are missing or incorrect
	 */
	static void verify(Connection connection) throws SQLException {
		Objects.requireNonNull(connection, "connection");
		for (EntityTable entity : ENTITIES) {
			verifyTrigger(connection, entityTriggerName(entity), entity.tableName(),
					entityUpdateSql(connection, entity));
		}
		for (ChildTable child : CHILDREN) {
			verifyTrigger(connection, childTriggerName(child, "insert"), child.tableName(), childInsertSql(child));
			verifyTrigger(connection, childTriggerName(child, "update"), child.tableName(),
					childUpdateSql(connection, child));
			verifyTrigger(connection, childTriggerName(child, "delete"), child.tableName(), childDeleteSql(child));
		}
	}

	private static String changedColumns(Connection connection, String tableName, boolean excludeId)
			throws SQLException {
		List<String> changes = new ArrayList<>();
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT name
				FROM pragma_table_info(?)
				ORDER BY cid
				""")) {
			statement.setString(1, tableName);
			try (ResultSet result = statement.executeQuery()) {
				while (result.next()) {
					String column = result.getString("name");
					if (!column.matches("[a-z][a-z0-9_]*")) {
						throw new SQLException("Unexpected revision column: " + column);
					}
					if (excludeId && column.equals("id")) {
						continue;
					}
					changes.add("OLD." + column + " IS NOT NEW." + column);
				}
			}
		}
		if (changes.isEmpty()) {
			throw new SQLException("No revision columns for " + tableName);
		}

		// IS NOT handles SQL NULL and BLOB values without converting
		// them into lossy text representations.
		return String.join(" OR ", changes);
	}

	private static String childDeleteSql(ChildTable child) {
		return """
				CREATE TRIGGER %s
				AFTER DELETE ON %s
				BEGIN
				    UPDATE portable_entity_identities
				    SET revision_id = %s
				    WHERE entity_type = '%s'
				      AND local_id = OLD.%s;
				END
				""".formatted(childTriggerName(child, "delete"), child.tableName(), UUID_SQL, child.ownerType(),
				child.ownerColumn());
	}

	private static String childInsertSql(ChildTable child) {
		return """
				CREATE TRIGGER %s
				AFTER INSERT ON %s
				BEGIN
				    UPDATE portable_entity_identities
				    SET revision_id = %s
				    WHERE entity_type = '%s'
				      AND local_id = NEW.%s;
				END
				""".formatted(childTriggerName(child, "insert"), child.tableName(), UUID_SQL, child.ownerType(),
				child.ownerColumn());
	}

	private static String childTriggerName(ChildTable child, String event) {
		return "app_revision_" + child.tableName() + "_" + child.ownerType().toLowerCase() + "_" + event;
	}

	private static String childUpdateSql(Connection connection, ChildTable child) throws SQLException {
		String changed = changedColumns(connection, child.tableName(), false);
		return """
				CREATE TRIGGER %s
				AFTER UPDATE ON %s
				WHEN %s
				BEGIN
				    UPDATE portable_entity_identities
				    SET revision_id = %s
				    WHERE entity_type = '%s'
				      AND local_id = OLD.%s;

				    UPDATE portable_entity_identities
				    SET revision_id = %s
				    WHERE entity_type = '%s'
				      AND local_id = NEW.%s
				      AND NEW.%s IS NOT OLD.%s;
				END
				""".formatted(childTriggerName(child, "update"), child.tableName(), changed, UUID_SQL,
				child.ownerType(), child.ownerColumn(), UUID_SQL, child.ownerType(), child.ownerColumn(),
				child.ownerColumn(), child.ownerColumn());
	}

	private static String entityTriggerName(EntityTable entity) {
		return "app_revision_" + entity.tableName() + "_update";
	}

	private static String entityUpdateSql(Connection connection, EntityTable entity) throws SQLException {
		String changed = changedColumns(connection, entity.tableName(), true);
		return """
				CREATE TRIGGER %s
				AFTER UPDATE ON %s
				WHEN %s
				BEGIN
				    UPDATE portable_entity_identities
				    SET revision_id = %s
				    WHERE entity_type = '%s'
				      AND local_id = NEW.id;
				END
				""".formatted(entityTriggerName(entity), entity.tableName(), changed, UUID_SQL, entity.entityType());
	}

	private static String normaliseSql(String sql) {
		return sql.replaceAll("\\s+", " ").trim();
	}

	private static void verifyTrigger(Connection connection, String name, String tableName, String expectedSql)
			throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT sql
				FROM sqlite_master
				WHERE type = 'trigger'
				  AND name = ?
				  AND tbl_name = ?
				""")) {
			statement.setString(1, name);
			statement.setString(2, tableName);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new SQLException("Missing revision trigger: " + name);
				}
				if (!normaliseSql(expectedSql).equals(normaliseSql(result.getString("sql")))) {
					throw new SQLException("Incorrect revision trigger: " + name);
				}
				if (result.next()) {
					throw new SQLException("Duplicate revision trigger: " + name);
				}
			}
		}
	}

	private record ChildTable(String tableName, String ownerColumn, String ownerType) {
	}

	private record EntityTable(String entityType, String tableName) {
	}
}
