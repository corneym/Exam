package au.edu.eq.questionbank.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqlScriptExecutorTest {

	@TempDir
	Path tempDirectory;

	@Test
	void executesMultipleStatements() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("script.db"));
		try (Connection connection = database.openConnection()) {
			SqlScriptExecutor.execute(connection, """
					CREATE TABLE example (
					    id INTEGER PRIMARY KEY,
					    name TEXT NOT NULL
					);
					INSERT INTO example (name)
					VALUES ('one');
					INSERT INTO example (name)
					VALUES ('two');
					""");
			try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("""
					SELECT COUNT(*) AS count
					FROM example
					""")) {
				assertTrue(result.next());
				assertEquals(2, result.getInt("count"));
			}
		}
	}

	@Test
	void schemaChangesCanBeRolledBackWhenScriptFails() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("rollback.db"));
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			assertThrows(SQLException.class, () -> {
				try {
					SqlScriptExecutor.execute(connection, """
							CREATE TABLE should_not_survive (
							    id INTEGER PRIMARY KEY
							);
							INSERT INTO table_that_does_not_exist (id)
							VALUES (1);
							""");
					connection.commit();
				} catch (SQLException e) {
					connection.rollback();
					throw e;
				}
			});
			try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("""
					SELECT name
					FROM sqlite_master
					WHERE type = 'table'
					  AND name = 'should_not_survive'
					""")) {
				assertFalse(result.next());
			}
		}
	}
}
