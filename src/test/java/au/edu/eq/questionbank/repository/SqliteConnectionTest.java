package au.edu.eq.questionbank.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteConnectionTest {

	@TempDir
	Path tempDir;

	@Test
	void enablesForeignKeyEnforcement() throws Exception {
		Path databasePath = tempDir.resolve("questionbank.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);

		try (Connection connection = database.openConnection();
				var statement = connection.createStatement();
				var result = statement.executeQuery("PRAGMA foreign_keys")) {

			assertTrue(result.next());
			assertEquals(1, result.getInt(1));
		}
	}

	@Test
	void opensFileBackedSqliteDatabase() throws Exception {
		Path databasePath = tempDir.resolve("questionbank.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);

		try (Connection connection = database.openConnection()) {
			assertFalse(connection.isClosed());
		}

		assertTrue(Files.exists(databasePath));
	}
}