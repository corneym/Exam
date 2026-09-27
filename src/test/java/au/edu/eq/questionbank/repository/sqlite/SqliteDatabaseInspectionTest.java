package au.edu.eq.questionbank.repository.sqlite;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteDatabaseInspectionTest {

	@TempDir
	Path tempDir;

	@Test
	void latestSchemaContainsNullablePendingMcqSharedContextReference() throws SQLException {
		Path databasePath = tempDir.resolve("pending-mcq-context.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		boolean columnFound = false;
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				var result = statement.executeQuery("PRAGMA table_info(exam_booklets)")) {
			while (result.next()) {
				if (!"pending_mcq_shared_context_id".equals(result.getString("name"))) {
					continue;
				}
				columnFound = true;

				// A booklet normally has no unfinished MCQ continuation, so the column
				// must permit NULL.
				assertEquals(0, result.getInt("notnull"));
			}
		}
		assertTrue(columnFound);
		boolean foreignKeyFound = false;
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				var result = statement.executeQuery("PRAGMA foreign_key_list(exam_booklets)")) {
			while (result.next()) {
				if ("pending_mcq_shared_context_id".equals(result.getString("from"))
						&& "shared_question_contexts".equals(result.getString("table"))
						&& "id".equals(result.getString("to"))) {
					foreignKeyFound = true;
				}
			}
		}

		// The workflow state must never reference a context row that does not exist.
		assertTrue(foreignKeyFound);

		// Later migrations retain the version-11 pending-context relationship.
		assertEquals(SqliteDatabase.latestSchemaVersion(), database.schemaVersion());
	}

	@Test
	void latestSchemaContainsQuestionOutputExclusions() throws SQLException {
		Path databasePath = tempDir.resolve("question-output-exclusions.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		int questionPrimaryKeyPosition = 0;
		int nodePrimaryKeyPosition = 0;
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				var result = statement.executeQuery("PRAGMA table_info(question_output_exclusions)")) {
			while (result.next()) {
				String columnName = result.getString("name");
				if ("question_id".equals(columnName)) {
					questionPrimaryKeyPosition = result.getInt("pk");

					// Every exclusion must identify a persisted Question.
					assertEquals(1, result.getInt("notnull"));
				} else if ("current_curriculum_node_id".equals(columnName)) {
					nodePrimaryKeyPosition = result.getInt("pk");

					// Every exclusion must identify one concrete current placement node.
					assertEquals(1, result.getInt("notnull"));
				}
			}
		}
		assertEquals(1, questionPrimaryKeyPosition);
		assertEquals(2, nodePrimaryKeyPosition);
		boolean questionForeignKeyFound = false;
		boolean curriculumForeignKeyFound = false;
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				var result = statement.executeQuery("PRAGMA foreign_key_list(question_output_exclusions)")) {
			while (result.next()) {
				String from = result.getString("from");
				String table = result.getString("table");
				String to = result.getString("to");
				if ("question_id".equals(from) && "questions".equals(table) && "id".equals(to)) {
					questionForeignKeyFound = true;
				}
				if ("current_curriculum_node_id".equals(from) && "curriculum_nodes".equals(table) && "id".equals(to)) {
					curriculumForeignKeyFound = true;
				}
			}
		}

		// Both sides of the exclusion are durable domain identities rather than
		// free-standing numeric preferences.
		assertTrue(questionForeignKeyFound);
		assertTrue(curriculumForeignKeyFound);
		assertEquals(SqliteDatabase.latestSchemaVersion(), database.schemaVersion());
		assertDoesNotThrow(database::verifySchema);
	}

	@Test
	void latestSchemaUsesSharedContextColumnNames() throws SQLException {
		Path databasePath = tempDir.resolve("shared-context-column-names.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		boolean hasSharedContextCaptureRequired = false;
		boolean hasLegacyPreambleCaptureRequired = false;
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				var result = statement.executeQuery("PRAGMA table_info(questions)")) {
			while (result.next()) {
				String name = result.getString("name");
				if ("shared_context_capture_required".equals(name)) {
					hasSharedContextCaptureRequired = true;
				} else if ("preamble_capture_required".equals(name)) {
					hasLegacyPreambleCaptureRequired = true;
				}
			}
		}
		assertTrue(hasSharedContextCaptureRequired);
		assertFalse(hasLegacyPreambleCaptureRequired);
		boolean hasSharedContextStatus = false;
		boolean hasLegacyPreambleStatus = false;
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				var result = statement.executeQuery("PRAGMA table_info(source_questions)")) {
			while (result.next()) {
				String name = result.getString("name");
				if ("shared_context_status".equals(name)) {
					hasSharedContextStatus = true;
				} else if ("preamble_status".equals(name)) {
					hasLegacyPreambleStatus = true;
				}
			}
		}
		assertTrue(hasSharedContextStatus);
		assertFalse(hasLegacyPreambleStatus);
		assertEquals(SqliteDatabase.latestSchemaVersion(), database.schemaVersion());
		assertDoesNotThrow(database::verifySchema);
	}

	@Test
	void schemaVersionReturnsLatestVersionForInitialisedDatabase() throws SQLException {
		Path databasePath = tempDir.resolve("questionbank.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		assertEquals(SqliteDatabase.latestSchemaVersion(), database.schemaVersion());
	}

	@Test
	void schemaVersionReturnsZeroWhenDatabaseHasNoQuestionBankSchema() throws SQLException {
		Path databasePath = tempDir.resolve("empty.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		try (Connection _ = database.openConnection()) {

			// Opening the connection creates the empty SQLite file.
		}
		assertEquals(0, database.schemaVersion());
	}

	@Test
	void verifyIntegrityAcceptsValidInitialisedDatabase() throws SQLException {
		Path databasePath = tempDir.resolve("questionbank.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		assertDoesNotThrow(database::verifyIntegrity);
	}

	@Test
	void verifyIntegrityRejectsForeignKeyViolation() throws SQLException {
		Path databasePath = tempDir.resolve("foreign-key-failure.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("PRAGMA foreign_keys = OFF");
			statement.execute("""
					CREATE TABLE parent (
					    id INTEGER PRIMARY KEY
					)
					""");
			statement.execute("""
					CREATE TABLE child (
					    id INTEGER PRIMARY KEY,
					    parent_id INTEGER NOT NULL,
					    FOREIGN KEY (parent_id) REFERENCES parent(id)
					)
					""");
			statement.execute("""
					INSERT INTO child (id, parent_id)
					VALUES (1, 999)
					""");
		}
		SQLException exception = assertThrows(SQLException.class, database::verifyIntegrity);
		assertTrue(exception.getMessage().contains("SQLite foreign-key check failed"));
	}

	@Test
	void version13MigrationCreatesOrderedPdfContentParts() throws Exception {
		Path databasePath = tempDir.resolve("version-13-question-content.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {

			// Build enough real relational data to retain two legacy PDF regions across
			// the version-13 to version-14 migration.
			statement.execute("""
					INSERT INTO subjects (id, subject_name)
					VALUES (1, 'Chemistry')
					""");
			statement.execute("""
					INSERT INTO syllabus_versions (
					    id, subject_id, syllabus_name, is_current, curriculum_status
					)
					VALUES (1, 1, '2019', 0, 'FINAL')
					""");
			statement.execute("""
					INSERT INTO curriculum_nodes (
					    id, syllabus_version_id, parent_id,
					    curriculum_code, curriculum_name,
					    curriculum_level, display_order
					)
					VALUES (
					    1, 1, NULL,
					    '1.1.1', 'Electrochemistry',
					    'SUBTOPIC', 0
					)
					""");
			statement.execute("""
					INSERT INTO exam_providers (id, provider_name)
					VALUES (1, 'QCAA')
					""");
			statement.execute("""
					INSERT INTO source_documents (id, relative_path)
					VALUES (1, 'Chemistry/2019/paper1.pdf')
					""");
			statement.execute("""
					INSERT INTO exams (
					    id, subject_id, provider_id, exam_year, exam_name
					)
					VALUES (1, 1, 1, 2019, 'External Assessment')
					""");
			statement.execute("""
					INSERT INTO exam_booklets (
					    id, exam_id, source_document_id,
					    booklet_name, question_format
					)
					VALUES (1, 1, 1, 'Paper 1', 'UNSPECIFIED')
					""");
			statement.execute("""
					INSERT INTO questions (
					    id, booklet_id, classification_node_id,
					    question_code, question_text, marks,
					    shared_context_capture_required, response_type
					)
					VALUES (
					    1, 1, 1,
					    'Q5', '', 1,
					    0, 'MULTIPLE_CHOICE'
					)
					""");
			statement.execute("""
					INSERT INTO question_regions (
					    question_id, region_order, booklet_id,
					    page_number, x, y, width, height
					)
					VALUES
					    (1, 0, 1, 4, 0.10, 0.20, 0.70, 0.20),
					    (1, 1, 1, 5, 0.10, 0.10, 0.70, 0.25)
					""");

			// Remove only structures introduced by version 14, leaving a structurally
			// valid version-13 database containing legacy Question regions.
			statement.execute("DROP TABLE question_content_parts");
			statement.execute("DROP TABLE question_images");
			statement.execute("UPDATE schema_version SET version = 13");
		}
		assertEquals(13, database.schemaVersion());
		assertDoesNotThrow(database::verifySchema);
		database.initialiseSchema();
		assertEquals(14, database.schemaVersion());
		assertDoesNotThrow(database::verifySchema);
		assertDoesNotThrow(database::verifyIntegrity);
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				var result = statement.executeQuery("""
						SELECT content_order, content_type, region_order, image_id
						FROM question_content_parts
						WHERE question_id = 1
						ORDER BY content_order
						""")) {

			// Migration preserves the exact existing PDF-region assembly order.
			assertTrue(result.next());
			assertEquals(0, result.getInt("content_order"));
			assertEquals("PDF_REGION", result.getString("content_type"));
			assertEquals(0, result.getInt("region_order"));
			assertEquals(null, result.getObject("image_id"));
			assertTrue(result.next());
			assertEquals(1, result.getInt("content_order"));
			assertEquals("PDF_REGION", result.getString("content_type"));
			assertEquals(1, result.getInt("region_order"));
			assertEquals(null, result.getObject("image_id"));
			assertFalse(result.next());
		}
	}
}
