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
		assertEquals(13, database.schemaVersion());
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
}
