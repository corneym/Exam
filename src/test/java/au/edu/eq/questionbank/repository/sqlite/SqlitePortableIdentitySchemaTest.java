package au.edu.eq.questionbank.repository.sqlite;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class SqlitePortableIdentitySchemaTest {

	@TempDir
	Path tempDirectory;

	@Test
	void independentDatabasesWithCollidingLocalIdsKeepDistinctIdentities() throws Exception {
		SqliteDatabase first = createVersion24Database("first.db");
		SqliteDatabase second = createVersion24Database("second.db");
		seedTransferGraph(first);
		seedTransferGraph(second);
		first.initialiseSchema();
		second.initialiseSchema();
		Map<String, String> firstIds = readPortableIds(first);
		Map<String, String> secondIds = readPortableIds(second);
		assertNotEquals(readInstallationId(first), readInstallationId(second));

		// Identical local IDs and domain content do not prove that
		// independently created records have the same identity.
		assertNotEquals(firstIds.get("SUBJECT:1"), secondIds.get("SUBJECT:1"));
		assertNotEquals(firstIds.get("EXAM:1"), secondIds.get("EXAM:1"));
		assertNotEquals(firstIds.get("QUESTION:1"), secondIds.get("QUESTION:1"));
		assertNotEquals(firstIds.get("SOURCE_DOCUMENT:1"), secondIds.get("SOURCE_DOCUMENT:1"));
		assertEquals(firstIds.keySet(), secondIds.keySet());
		assertEquals(17, firstIds.size());
		assertEquals(17, secondIds.size());
	}

	@Test
	void migrationFailureRollsBackIdentityTablesAndCanRetry() throws Exception {
		SqliteDatabase database = createVersion24Database("rollback.db");
		seedTransferGraph(database);
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					CREATE TRIGGER reject_v25_migration
					BEFORE UPDATE OF version ON schema_version
					WHEN NEW.version = 25
					BEGIN
					    SELECT RAISE(
					        ABORT,
					        'simulated schema v25 migration failure'
					    );
					END
					""");
		}

		// Failure occurs after the tables and UUID backfill have been
		// created, but before the schema version can advance.
		assertThrows(SQLException.class, database::initialiseSchema);
		assertEquals(24, database.schemaVersion());
		assertFalse(tableExists(database, "installation_identity"));
		assertFalse(tableExists(database, "portable_entity_identities"));

		// The original domain graph must remain available.
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT COUNT(*)
						FROM questions
						WHERE id = 1
						""")) {
			assertTrue(result.next());
			assertEquals(1, result.getInt(1));
		}
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("DROP TRIGGER reject_v25_migration");
		}
		database.initialiseSchema();
		assertEquals(SqliteDatabase.latestSchemaVersion(), database.schemaVersion());
		assertEquals(17, readPortableIds(database).size());
		assertDoesNotThrow(database::verifySchema);
		assertDoesNotThrow(database::verifyIntegrity);
	}

	@Test
	void migrationPreservesExistingRowsAndPortableIdsAcrossRestartAndBackup() throws Exception {
		SqliteDatabase database = createVersion24Database("migration.db");
		seedTransferGraph(database);
		assertEquals(24, database.schemaVersion());
		database.initialiseSchema();
		assertEquals(SqliteDatabase.latestSchemaVersion(), database.schemaVersion());
		assertDoesNotThrow(database::verifySchema);
		assertDoesNotThrow(database::verifyIntegrity);
		String installationId = readInstallationId(database);
		Map<String, String> originalIds = readPortableIds(database);
		assertEquals(17, originalIds.size());

		// Identical source bytes must not collapse two source-document
		// records that have different database roles and relationships.
		assertNotEquals(originalIds.get("SOURCE_DOCUMENT:1"), originalIds.get("SOURCE_DOCUMENT:2"));

		// Both the original Question and its associated Answer must
		// retain their existing integer-key relationships.
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT
						    q.booklet_id,
						    q.source_question_id,
						    q.shared_context_id,
						    a.question_id
						FROM questions q
						JOIN answers a ON a.question_id = q.id
						WHERE q.id = 1
						""")) {
			assertTrue(result.next());
			assertEquals(1, result.getLong("booklet_id"));
			assertEquals(1, result.getLong("source_question_id"));
			assertEquals(1, result.getLong("shared_context_id"));
			assertEquals(1, result.getLong("question_id"));
			assertFalse(result.next());
		}

		// A second startup must not reassign any identity or revision.
		database.initialiseSchema();
		assertEquals(installationId, readInstallationId(database));
		assertEquals(originalIds, readPortableIds(database));
		Path snapshotPath = tempDirectory.resolve("snapshot.db");
		database.createConsistentSnapshot(snapshotPath);
		SqliteDatabase snapshot = SqliteDatabase.readOnly(snapshotPath);
		assertEquals(installationId, readInstallationId(snapshot));
		assertEquals(originalIds, readPortableIds(snapshot));
		assertDoesNotThrow(snapshot::verifySchema);
		assertDoesNotThrow(snapshot::verifyIntegrity);
	}

	@Test
	void portableIdentityUniquenessIsEnforced() throws Exception {
		SqliteDatabase database = createVersion24Database("uniqueness.db");
		seedTransferGraph(database);
		database.initialiseSchema();
		String existingId = readPortableIds(database).get("SUBJECT:1");
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						INSERT INTO portable_entity_identities (
						    entity_type,
						    local_id,
						    portable_id,
						    revision_id
						)
						VALUES (?, ?, ?, ?)
						""")) {

			// A different entity type cannot claim the same portable UUID.
			statement.setString(1, "QUESTION");
			statement.setLong(2, 999);
			statement.setString(3, existingId);
			statement.setString(4, UUID.randomUUID().toString());
			assertThrows(SQLException.class, statement::executeUpdate);

			// The same type/local-ID pair cannot be reassigned either.
			statement.clearParameters();
			statement.setString(1, "SUBJECT");
			statement.setLong(2, 1);
			statement.setString(3, UUID.randomUUID().toString());
			statement.setString(4, UUID.randomUUID().toString());
			assertThrows(SQLException.class, statement::executeUpdate);
		}
		assertEquals(17, readPortableIds(database).size());
		assertDoesNotThrow(database::verifyIntegrity);
	}

	private SqliteDatabase createVersion24Database(String filename) throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve(filename));
		database.initialiseSchema();

		// Reconstruct only the immediately preceding schema version.
		// All work occurs in an isolated, disposable test database.
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			HistoricalSchemaFixture.removeVersion25Objects(statement);
			statement.execute("UPDATE schema_version SET version = 24");
		}
		assertEquals(24, database.schemaVersion());
		assertDoesNotThrow(database::verifySchema);
		return database;
	}

	private String readInstallationId(SqliteDatabase database) throws SQLException {
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT installation_id
						FROM installation_identity
						""")) {
			assertTrue(result.next());
			String id = result.getString("installation_id");
			assertEquals(id, UUID.fromString(id).toString());
			assertFalse(result.next());
			return id;
		}
	}

	private Map<String, String> readPortableIds(SqliteDatabase database) throws SQLException {
		Map<String, String> identities = new LinkedHashMap<>();
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT
						    entity_type,
						    local_id,
						    portable_id,
						    revision_id
						FROM portable_entity_identities
						ORDER BY entity_type, local_id
						""")) {
			while (result.next()) {
				String key = result.getString("entity_type") + ":" + result.getLong("local_id");
				String portableId = result.getString("portable_id");
				String revisionId = result.getString("revision_id");
				assertEquals(portableId, UUID.fromString(portableId).toString());
				assertEquals(revisionId, UUID.fromString(revisionId).toString());
				assertNull(identities.put(key, portableId));
			}
		}
		assertEquals(identities.size(), new HashSet<>(identities.values()).size());
		return Map.copyOf(identities);
	}

	private void seedTransferGraph(SqliteDatabase database) throws SQLException {
		String hash = "a".repeat(64);
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					INSERT INTO subjects (id, subject_name)
					VALUES (1, 'Biology')
					""");
			statement.execute("""
					INSERT INTO syllabus_versions (
					    id, subject_id, syllabus_name, is_current
					)
					VALUES
					    (1, 1, 'Biology 2019', 0),
					    (2, 1, 'Biology 2025', 1)
					""");
			statement.execute("""
					INSERT INTO curriculum_nodes (
					    id, syllabus_version_id, parent_id,
					    curriculum_code, curriculum_name,
					    curriculum_level, display_order
					)
					VALUES
					    (1, 1, NULL, 'U1', 'Unit 1', 'UNIT', 0),
					    (2, 2, NULL, 'U1', 'Unit 1', 'UNIT', 0)
					""");
			statement.execute("""
					INSERT INTO curriculum_mappings (
					    id, source_node_id, target_node_id,
					    mapping_status
					)
					VALUES (1, 1, 2, 'CONFIRMED')
					""");
			statement.execute("""
					INSERT INTO exam_providers (id, provider_name)
					VALUES (1, 'QCAA')
					""");
			try (PreparedStatement documents = connection.prepareStatement("""
					INSERT INTO source_documents (
					    id, relative_path, content_sha256
					)
					VALUES (?, ?, ?)
					""")) {
				documents.setLong(1, 1);
				documents.setString(2, "subjects/Biology/exams/questions.pdf");
				documents.setString(3, hash);
				documents.executeUpdate();
				documents.setLong(1, 2);
				documents.setString(2, "subjects/Biology/exams/answers.pdf");
				documents.setString(3, hash);
				documents.executeUpdate();
			}
			statement.execute("""
					INSERT INTO exams (
					    id, subject_id, provider_id,
					    exam_year, exam_name
					)
					VALUES (1, 1, 1, 2025, 'External Assessment')
					""");
			statement.execute("""
					INSERT INTO exam_booklets (
					    id, exam_id, source_document_id, booklet_name
					)
					VALUES (1, 1, 1, 'Paper 1')
					""");
			statement.execute("""
					INSERT INTO source_questions (
					    id, booklet_id, source_question_code
					)
					VALUES (1, 1, '1')
					""");
			statement.execute("""
					INSERT INTO shared_question_contexts (
					    id, booklet_id, context_label
					)
					VALUES (1, 1, 'Shared stimulus')
					""");
			statement.execute("""
					INSERT INTO questions (
					    id, booklet_id, classification_node_id,
					    question_code, question_text, marks,
					    shared_context_capture_required,
					    source_question_id, shared_context_id
					)
					VALUES (
					    1, 1, 2,
					    '1', 'Example question', 1,
					    0, 1, 1
					)
					""");
			statement.execute("""
					INSERT INTO answer_files (
					    id, exam_id, source_document_id,
					    answer_file_name
					)
					VALUES (1, 1, 2, 'Marking guide')
					""");
			statement.execute("""
					INSERT INTO answers (
					    id, question_id, answer_text
					)
					VALUES (1, 1, 'Example answer')
					""");
			statement.execute("""
					INSERT INTO question_images (
					    id, question_id, image_png
					)
					VALUES (1, 1, X'010203')
					""");
		}
	}

	private boolean tableExists(SqliteDatabase database, String tableName) throws SQLException {
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT COUNT(*)
						FROM sqlite_master
						WHERE type = 'table'
						  AND name = ?
						""")) {
			statement.setString(1, tableName);
			try (ResultSet result = statement.executeQuery()) {
				assertTrue(result.next());
				return result.getInt(1) == 1;
			}
		}
	}
}
