package au.edu.eq.questionbank.repository.sqlite;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
		assertEquals(11, database.schemaVersion());
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
