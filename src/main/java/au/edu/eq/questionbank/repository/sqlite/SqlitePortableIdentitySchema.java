package au.edu.eq.questionbank.repository.sqlite;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Owns the durable portable-identity schema and its database triggers.
 *
 * <p>
 * Existing rows receive random portable and revision UUIDs exactly once during
 * migration. Database triggers then maintain identity membership independently
 * of the Java persistence entry point.
 */
final class SqlitePortableIdentitySchema {

	private static final List<EntityTable> ENTITY_TABLES = List.of(new EntityTable("SUBJECT", "subjects"),
			new EntityTable("SYLLABUS_VERSION", "syllabus_versions"),
			new EntityTable("CURRICULUM_NODE", "curriculum_nodes"), new EntityTable("EXAM_PROVIDER", "exam_providers"),
			new EntityTable("SOURCE_DOCUMENT", "source_documents"), new EntityTable("EXAM", "exams"),
			new EntityTable("EXAM_BOOKLET", "exam_booklets"), new EntityTable("QUESTION", "questions"),
			new EntityTable("ANSWER_FILE", "answer_files"), new EntityTable("ANSWER", "answers"),
			new EntityTable("SOURCE_QUESTION", "source_questions"),
			new EntityTable("SHARED_QUESTION_CONTEXT", "shared_question_contexts"),
			new EntityTable("CURRICULUM_MAPPING", "curriculum_mappings"),
			new EntityTable("QUESTION_IMAGE", "question_images"));
	private static final String UUID_SQL = """
			lower(hex(randomblob(4))) || '-' ||
			lower(hex(randomblob(2))) || '-4' ||
			substr(lower(hex(randomblob(2))), 2) || '-' ||
			substr('89ab', (random() & 3) + 1, 1) ||
			substr(lower(hex(randomblob(2))), 2) || '-' ||
			lower(hex(randomblob(6)))
			""";

	private SqlitePortableIdentitySchema() {
	}

	static void migrate(Connection connection) throws SQLException {
		Objects.requireNonNull(connection, "connection");
		installTables(connection);
		createInstallationIdentity(connection);
		backfillIdentities(connection);
		installTriggers(connection);

		// Schema version changes only after both identity backfill
		// and ongoing identity maintenance have been installed.
		try (PreparedStatement statement = connection.prepareStatement("""
				UPDATE schema_version
				SET version = 25
				WHERE version = 24
				""")) {
			if (statement.executeUpdate() != 1) {
				throw new SQLException("Portable identity migration requires schema v24");
			}
		}
	}

	static void verifyInstallationIdentity(Connection connection) throws SQLException {
		Objects.requireNonNull(connection, "connection");
		try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("""
				SELECT singleton_id, installation_id
				FROM installation_identity
				""")) {
			if (!result.next()) {
				throw new SQLException("Missing installation identity");
			}
			if (result.getInt("singleton_id") != 1) {
				throw new SQLException("Invalid installation identity singleton");
			}
			String stored = result.getString("installation_id");
			try {
				UUID parsed = UUID.fromString(stored);
				if (!parsed.toString().equals(stored)) {
					throw new IllegalArgumentException("Noncanonical UUID");
				}
			} catch (IllegalArgumentException exception) {
				throw new SQLException("Invalid installation identity UUID", exception);
			}
			if (result.next()) {
				throw new SQLException("Multiple installation identities");
			}
		}
	}

	static void verifyTriggers(Connection connection) throws SQLException {
		for (EntityTable entity : ENTITY_TABLES) {
			verifyTrigger(connection, insertTriggerName(entity), entity.tableName(), insertTriggerSql(entity));
			verifyTrigger(connection, deleteTriggerName(entity), entity.tableName(), deleteTriggerSql(entity));
			verifyTrigger(connection, idUpdateTriggerName(entity), entity.tableName(), idUpdateTriggerSql(entity));
		}
		verifyTrigger(connection, "app_portable_identity_immutable", "portable_entity_identities",
				identityImmutableTriggerSql());
	}

	private static void backfillIdentities(Connection connection) throws SQLException {
		try (PreparedStatement insert = connection.prepareStatement("""
				INSERT INTO portable_entity_identities (
				    entity_type,
				    local_id,
				    portable_id,
				    revision_id
				)
				VALUES (?, ?, ?, ?)
				""")) {
			for (EntityTable entity : ENTITY_TABLES) {

				// Names come exclusively from the fixed internal
				// entity registry, never from package input.
				String query = "SELECT id FROM " + entity.tableName() + " ORDER BY id";
				try (Statement select = connection.createStatement(); ResultSet rows = select.executeQuery(query)) {
					int pending = 0;
					while (rows.next()) {
						insert.setString(1, entity.entityType());
						insert.setLong(2, rows.getLong("id"));
						insert.setString(3, UUID.randomUUID().toString());
						insert.setString(4, UUID.randomUUID().toString());
						insert.addBatch();
						if (++pending == 256) {
							insert.executeBatch();
							insert.clearBatch();
							pending = 0;
						}
					}
					if (pending > 0) {
						insert.executeBatch();
						insert.clearBatch();
					}
				}
			}
		}
	}

	private static void createInstallationIdentity(Connection connection) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO installation_identity (
				    singleton_id,
				    installation_id
				)
				VALUES (1, ?)
				""")) {
			statement.setString(1, UUID.randomUUID().toString());
			if (statement.executeUpdate() != 1) {
				throw new SQLException("Could not create installation identity");
			}
		}
	}

	private static String deleteTriggerName(EntityTable entity) {
		return "app_portable_" + entity.tableName() + "_delete";
	}

	private static String deleteTriggerSql(EntityTable entity) {
		return """
				CREATE TRIGGER %s
				AFTER DELETE ON %s
				BEGIN
				    INSERT INTO portable_entity_retirements (
				        portable_id,
				        entity_type,
				        local_id,
				        revision_id
				    )
				    SELECT
				        portable_id,
				        entity_type,
				        local_id,
				        revision_id
				    FROM portable_entity_identities
				    WHERE entity_type = '%s'
				      AND local_id = OLD.id;

				    DELETE FROM portable_entity_identities
				    WHERE entity_type = '%s'
				      AND local_id = OLD.id;
				END
				""".formatted(deleteTriggerName(entity), entity.tableName(), entity.entityType(), entity.entityType());
	}

	private static String identityImmutableTriggerSql() {
		return """
				CREATE TRIGGER app_portable_identity_immutable
				BEFORE UPDATE OF
				    entity_type,
				    local_id,
				    portable_id
				ON portable_entity_identities
				WHEN OLD.entity_type IS NOT NEW.entity_type
				  OR OLD.local_id IS NOT NEW.local_id
				  OR OLD.portable_id IS NOT NEW.portable_id
				BEGIN
				    SELECT RAISE(
				        ABORT,
				        'Portable entity identity is immutable'
				    );
				END
				""";
	}

	private static String idUpdateTriggerName(EntityTable entity) {
		return "app_portable_" + entity.tableName() + "_id_update";
	}

	private static String idUpdateTriggerSql(EntityTable entity) {
		return """
				CREATE TRIGGER %s
				BEFORE UPDATE OF id ON %s
				WHEN NEW.id IS NOT OLD.id
				BEGIN
				    SELECT RAISE(
				        ABORT,
				        'Persistent local identity cannot be changed'
				    );
				END
				""".formatted(idUpdateTriggerName(entity), entity.tableName());
	}

	private static String insertTriggerName(EntityTable entity) {
		return "app_portable_" + entity.tableName() + "_insert";
	}

	private static String insertTriggerSql(EntityTable entity) {
		return """
				CREATE TRIGGER %s
				AFTER INSERT ON %s
				BEGIN
				    INSERT INTO portable_entity_identities (
				        entity_type,
				        local_id,
				        portable_id,
				        revision_id
				    )
				    VALUES (
				        '%s',
				        NEW.id,
				        %s,
				        %s
				    );
				END
				""".formatted(insertTriggerName(entity), entity.tableName(), entity.entityType(), UUID_SQL, UUID_SQL);
	}

	private static void installTables(Connection connection) throws SQLException {
		String sql;
		try {
			sql = SqlResourceLoader.load("/db/migration-v24-to-v25.sql");
		} catch (IOException exception) {
			throw new SQLException("Unable to load portable identity migration", exception);
		}
		SqlScriptExecutor.execute(connection, sql);
	}

	private static void installTriggers(Connection connection) throws SQLException {
		try (Statement statement = connection.createStatement()) {
			for (EntityTable entity : ENTITY_TABLES) {
				statement.executeUpdate(insertTriggerSql(entity));
				statement.executeUpdate(deleteTriggerSql(entity));
				statement.executeUpdate(idUpdateTriggerSql(entity));
			}
			statement.executeUpdate(identityImmutableTriggerSql());
		}
	}

	private static String normaliseSql(String sql) {
		return sql.replaceAll("\\s+", " ").trim();
	}

	private static void verifyTrigger(Connection connection, String triggerName, String tableName, String expectedSql)
			throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT sql
				FROM sqlite_master
				WHERE type = 'trigger'
				  AND name = ?
				  AND tbl_name = ?
				""")) {
			statement.setString(1, triggerName);
			statement.setString(2, tableName);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new SQLException("Missing portable identity trigger: " + triggerName);
				}
				String actualSql = result.getString("sql");
				if (actualSql == null || !normaliseSql(actualSql).equals(normaliseSql(expectedSql))) {
					throw new SQLException("Invalid portable identity trigger: " + triggerName);
				}
				if (result.next()) {
					throw new SQLException("Duplicate portable identity trigger: " + triggerName);
				}
			}
		}
	}

	private record EntityTable(String entityType, String tableName) {
	}
}
