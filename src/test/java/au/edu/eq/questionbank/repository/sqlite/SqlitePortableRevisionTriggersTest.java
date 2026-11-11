package au.edu.eq.questionbank.repository.sqlite;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.Identity;

class SqlitePortableRevisionTriggersTest {

	@TempDir
	Path tempDirectory;

	@Test
	void failedTransactionRestoresOriginalRevision() throws Exception {
		SqliteDatabase database = createDatabase("transaction.db");
		seedGraph(database);
		UUID original = revision(database, EntityType.QUESTION, 1);
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try (Statement statement = connection.createStatement()) {
				statement.execute("""
						UPDATE questions
						SET marks = 2
						WHERE id = 1
						""");
			}
			UUID changed = revision(connection, EntityType.QUESTION, 1);
			assertNotEquals(original, changed);
			connection.rollback();
		}
		assertEquals(original, revision(database, EntityType.QUESTION, 1));
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT marks
						FROM questions
						WHERE id = 1
						""")) {
			assertTrue(result.next());
			assertEquals(1, result.getInt("marks"));
		}
	}

	@Test
	void failedVersion26MigrationRollsBackAndCanRetry() throws Exception {
		SqliteDatabase database = createDatabase("v26-rollback.db");
		seedGraph(database);
		UUID original = revision(database, EntityType.QUESTION, 1);

		// Construct a genuine version-25 fixture in a disposable DB.
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			HistoricalSchemaFixture.removeVersion26Objects(statement);
			statement.execute("UPDATE schema_version SET version = 25");
			statement.execute("""
					CREATE TRIGGER reject_version26
					BEFORE UPDATE OF version ON schema_version
					WHEN NEW.version = 26
					BEGIN
					    SELECT RAISE(
					        ABORT,
					        'simulated version 26 failure'
					    );
					END
					""");
		}
		assertEquals(25, database.schemaVersion());
		assertDoesNotThrow(database::verifySchema);
		assertThrows(SQLException.class, database::initialiseSchema);

		// No partially installed revision triggers may remain.
		assertEquals(25, database.schemaVersion());
		assertEquals(0, revisionTriggerCount(database));
		assertEquals(original, revision(database, EntityType.QUESTION, 1));
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("DROP TRIGGER reject_version26");
		}
		database.initialiseSchema();
		assertEquals(SqliteDatabase.latestSchemaVersion(), database.schemaVersion());
		assertTrue(revisionTriggerCount(database) > 0);

		// Existing UUIDs are preserved through the migration.
		assertEquals(original, revision(database, EntityType.QUESTION, 1));
		assertDoesNotThrow(database::verifySchema);
		assertDoesNotThrow(database::verifyIntegrity);
	}

	@Test
	void materialEntityUpdatesAdvanceRevisionButNotPortableIdentity() throws Exception {
		SqliteDatabase database = createDatabase("entity-update.db");
		seedGraph(database);
		SqlitePortableIdentityRepository identities = new SqlitePortableIdentityRepository(database);
		Identity original = identities.requireByLocalId(EntityType.QUESTION, 1);
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {

			// Repeating the stored value is not a new content revision.
			statement.execute("""
					UPDATE questions
					SET question_text = question_text
					WHERE id = 1
					""");
		}
		assertEquals(original.revisionId(), revision(database, EntityType.QUESTION, 1));
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					UPDATE questions
					SET question_text = 'Corrected wording'
					WHERE id = 1
					""");
		}
		Identity changed = identities.requireByLocalId(EntityType.QUESTION, 1);
		assertEquals(original.portableId(), changed.portableId());
		assertNotEquals(original.revisionId(), changed.revisionId());

		// A repeated write of the same final value is a no-op.
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					UPDATE questions
					SET question_text = 'Corrected wording'
					WHERE id = 1
					""");
		}
		assertEquals(changed.revisionId(), revision(database, EntityType.QUESTION, 1));
	}

	@Test
	void orderedQuestionAndAnswerChildrenAdvanceOwningRevisions() throws Exception {
		SqliteDatabase database = createDatabase("children.db");
		seedGraph(database);
		UUID questionBefore = revision(database, EntityType.QUESTION, 1);
		UUID answerBefore = revision(database, EntityType.ANSWER, 1);
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					INSERT INTO question_regions (
					    question_id, region_order, booklet_id,
					    page_number, x, y, width, height
					)
					VALUES (
					    1, 0, 1,
					    2, 0.10, 0.20, 0.50, 0.30
					)
					""");
		}
		UUID afterRegion = revision(database, EntityType.QUESTION, 1);
		assertNotEquals(questionBefore, afterRegion);
		assertEquals(answerBefore, revision(database, EntityType.ANSWER, 1));
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					UPDATE question_regions
					SET x = x
					WHERE question_id = 1 AND region_order = 0
					""");
		}
		assertEquals(afterRegion, revision(database, EntityType.QUESTION, 1));
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					UPDATE question_regions
					SET x = 0.15
					WHERE question_id = 1 AND region_order = 0
					""");
		}
		assertNotEquals(afterRegion, revision(database, EntityType.QUESTION, 1));
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					INSERT INTO answer_regions (
					    answer_id, region_order, answer_file_id,
					    page_number, x, y, width, height
					)
					VALUES (
					    1, 0, 1,
					    3, 0.10, 0.10, 0.60, 0.40
					)
					""");
		}
		assertNotEquals(answerBefore, revision(database, EntityType.ANSWER, 1));
	}

	@Test
	void sharedContextAndQuestionImagesAdvanceOwnerRevision() throws Exception {
		SqliteDatabase database = createDatabase("mixed-content.db");
		seedGraph(database);
		UUID contextBefore = revision(database, EntityType.SHARED_QUESTION_CONTEXT, 1);
		UUID questionBefore = revision(database, EntityType.QUESTION, 1);
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					INSERT INTO shared_question_context_images (
					    shared_context_id, image_order, image_png
					)
					VALUES (1, 0, X'010203')
					""");
			statement.execute("""
					INSERT INTO shared_question_context_content_parts (
					    shared_context_id, content_order,
					    content_type, image_order
					)
					VALUES (1, 0, 'IMAGE', 0)
					""");
			statement.execute("""
					INSERT INTO question_images (
					    id, question_id, image_png
					)
					VALUES (1, 1, X'010203')
					""");
			statement.execute("""
					INSERT INTO question_content_parts (
					    question_id, content_order,
					    content_type, image_id
					)
					VALUES (1, 0, 'IMAGE', 1)
					""");
		}
		assertNotEquals(contextBefore, revision(database, EntityType.SHARED_QUESTION_CONTEXT, 1));
		assertNotEquals(questionBefore, revision(database, EntityType.QUESTION, 1));
		UUID imageBefore = revision(database, EntityType.QUESTION_IMAGE, 1);
		UUID ownerBefore = revision(database, EntityType.QUESTION, 1);
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					UPDATE question_images
					SET image_png = X'040506'
					WHERE id = 1
					""");
		}
		assertNotEquals(imageBefore, revision(database, EntityType.QUESTION_IMAGE, 1));
		assertNotEquals(ownerBefore, revision(database, EntityType.QUESTION, 1));
	}

	private SqliteDatabase createDatabase(String filename) throws SQLException {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve(filename));
		database.initialiseSchema();
		return database;
	}

	private UUID revision(Connection connection, EntityType type, long localId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT revision_id
				FROM portable_entity_identities
				WHERE entity_type = ?
				  AND local_id = ?
				""")) {
			statement.setString(1, type.name());
			statement.setLong(2, localId);
			try (ResultSet result = statement.executeQuery()) {
				assertTrue(result.next());
				UUID revision = UUID.fromString(result.getString("revision_id"));
				assertFalse(result.next());
				return revision;
			}
		}
	}

	private UUID revision(SqliteDatabase database, EntityType type, long localId) throws SQLException {
		try (Connection connection = database.openConnection()) {
			return revision(connection, type, localId);
		}
	}

	private int revisionTriggerCount(SqliteDatabase database) throws SQLException {
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT COUNT(*)
						FROM sqlite_master
						WHERE type = 'trigger'
						  AND name GLOB 'app_revision_*'
						""")) {
			assertTrue(result.next());
			return result.getInt(1);
		}
	}

	private void seedGraph(SqliteDatabase database) throws SQLException {
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					INSERT INTO subjects (id, subject_name)
					VALUES (1, 'Biology')
					""");
			statement.execute("""
					INSERT INTO syllabus_versions (
					    id, subject_id, syllabus_name, is_current
					)
					VALUES (1, 1, '2025', 1)
					""");
			statement.execute("""
					INSERT INTO curriculum_nodes (
					    id, syllabus_version_id, parent_id,
					    curriculum_code, curriculum_name,
					    curriculum_level, display_order
					)
					VALUES (
					    1, 1, NULL, '1', 'Unit 1', 'UNIT', 0
					)
					""");
			statement.execute("""
					INSERT INTO exam_providers (id, provider_name)
					VALUES (1, 'QCAA')
					""");
			statement.execute("""
					INSERT INTO source_documents (id, relative_path)
					VALUES (
					    1, 'subjects/Biology/questions.pdf'
					)
					""");
			statement.execute("""
					INSERT INTO exams (
					    id, subject_id, provider_id,
					    exam_year, exam_name
					)
					VALUES (
					    1, 1, 1, 2025, 'External Assessment'
					)
					""");
			statement.execute("""
					INSERT INTO exam_booklets (
					    id, exam_id, source_document_id, booklet_name
					)
					VALUES (1, 1, 1, 'Paper 1')
					""");
			statement.execute("""
					INSERT INTO questions (
					    id, booklet_id, classification_node_id,
					    question_code, question_text, marks,
					    shared_context_capture_required
					)
					VALUES (
					    1, 1, 1, '1', 'Original wording', 1, 0
					)
					""");
			statement.execute("""
					INSERT INTO answer_files (
					    id, exam_id, source_document_id,
					    answer_file_name
					)
					VALUES (1, 1, 1, 'Marking guide')
					""");
			statement.execute("""
					INSERT INTO answers (
					    id, question_id, answer_text
					)
					VALUES (1, 1, 'Answer')
					""");
			statement.execute("""
					INSERT INTO shared_question_contexts (
					    id, booklet_id, context_label
					)
					VALUES (1, 1, 'Shared stimulus')
					""");
		}
	}
}
