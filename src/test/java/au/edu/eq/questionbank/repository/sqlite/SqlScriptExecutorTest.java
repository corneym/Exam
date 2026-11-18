package au.edu.eq.questionbank.repository.sqlite;

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
	void executesTriggerBodiesContainingMultipleStatements() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("trigger-script.db"));
		try (Connection connection = database.openConnection()) {
			SqlScriptExecutor.execute(connection, """
					CREATE TABLE items (
					    id INTEGER PRIMARY KEY,
					    item_text TEXT NOT NULL
					);

					CREATE TABLE audit (
					    item_id INTEGER NOT NULL,
					    event_text TEXT NOT NULL
					);

					-- Semicolons inside a trigger must not split it.
					CREATE TRIGGER audit_item_insert
					AFTER INSERT ON items
					BEGIN
					    INSERT INTO audit (item_id, event_text)
					    VALUES (NEW.id, 'insert;captured');

					    UPDATE items
					    SET item_text = 'updated;by trigger'
					    WHERE id = NEW.id;
					END;

					INSERT INTO items (id, item_text)
					VALUES (1, 'initial;value');
					""");
			try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("""
					SELECT item_text
					FROM items
					WHERE id = 1
					""")) {
				assertTrue(result.next());
				assertEquals("updated;by trigger", result.getString(1));
			}
			try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("""
					SELECT item_id, event_text
					FROM audit
					""")) {
				assertTrue(result.next());
				assertEquals(1, result.getInt("item_id"));
				assertEquals("insert;captured", result.getString("event_text"));
				assertFalse(result.next());
			}
		}
	}

	@Test
	void identifiesTheStatementThatFailed() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("script-diagnostic.db"));
		try (Connection connection = database.openConnection()) {
			SQLException failure = assertThrows(SQLException.class, () -> SqlScriptExecutor.execute(connection, """
					CREATE TABLE example (
					    id INTEGER PRIMARY KEY
					);
					INSERT INTO table_that_does_not_exist (id)
					VALUES (1);
					"""));

			// Migration failures should identify which statement failed while retaining
			// the SQLite exception as the underlying cause.
			assertTrue(failure.getMessage().contains("statement 2"));
			assertTrue(failure.getCause() instanceof SQLException);
			assertTrue(failure.getCause().getMessage().contains("table_that_does_not_exist"));
		}
	}

	@Test
	void recognisesCommentsWithoutDiscardingSqlLiterals() throws Exception {
		String sql = """
				-- Leading comment.
				/* Another leading comment. */

				SELECT '/* literal */', '-- literal';

				-- Trailing comment.
				""";
		var statements = SqlScriptExecutor.splitStatements(sql);
		assertEquals(1, statements.size());
		assertTrue(statements.getFirst().contains("'/* literal */', '-- literal'"));
	}

	@Test
	void recognisesTriggerAfterManyLeadingComments() throws Exception {

		// Exercise the repeated block-comment sequence that caused
		// excessive backtracking in the original regular expression.
		String comments = "/* */".repeat(5_000);
		String sql = comments + """

				-- Leading line comment.
				CREATE TEMPORARY TRIGGER test_trigger
				AFTER INSERT ON example
				BEGIN
				    SELECT 1;
				    SELECT 2;
				END;

				INSERT INTO example (id) VALUES (1);
				""";
		var statements = SqlScriptExecutor.splitStatements(sql);
		assertEquals(2, statements.size());
		assertTrue(statements.getFirst().contains("CREATE TEMPORARY TRIGGER test_trigger"));
		assertTrue(statements.getFirst().contains("SELECT 1;"));
		assertTrue(statements.getFirst().contains("SELECT 2;"));
		assertTrue(statements.get(1).startsWith("INSERT INTO example"));
	}

	@Test
	void rejectsUnterminatedTriggerScripts() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("incomplete-trigger.db"));
		try (Connection connection = database.openConnection()) {
			SQLException failure = assertThrows(SQLException.class, () -> SqlScriptExecutor.execute(connection, """
					CREATE TABLE items (id INTEGER PRIMARY KEY);

					CREATE TRIGGER incomplete_trigger
					AFTER INSERT ON items
					BEGIN
					    SELECT 1;
					"""));
			assertTrue(failure.getMessage().contains("Unterminated CREATE TRIGGER"));
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
