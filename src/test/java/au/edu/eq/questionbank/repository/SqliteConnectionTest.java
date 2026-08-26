package au.edu.eq.questionbank.repository;

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
	void opensFileBackedSqliteDatabase() throws Exception {
		Path databasePath = tempDir.resolve("questionbank.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);

		try (Connection connection = database.openConnection()) {
			assertFalse(connection.isClosed());
		}

		assertTrue(Files.exists(databasePath));
	}
}