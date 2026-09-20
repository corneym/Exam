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
