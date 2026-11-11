package au.edu.eq.questionbank.repository.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteDirectChangesTest {

	@TempDir
	Path tempDirectory;

	@Test
	void countsDomainRowsRatherThanIdentityTriggerWrites() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("direct-counts.db"));
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			assertEquals(1, SqliteDirectChanges.executeUpdate(statement, """
					INSERT INTO subjects (
					    id, subject_name
					)
					VALUES (1, 'Biology')
					"""));
			assertEquals(1, SqliteDirectChanges.executeUpdate(statement, """
					INSERT INTO subjects (
					    id, subject_name
					)
					VALUES (2, 'Chemistry')
					"""));
			assertEquals(2, SqliteDirectChanges.executeUpdate(statement, """
					UPDATE subjects
					SET subject_name = subject_name || ' Updated'
					WHERE id IN (1, 2)
					"""));
			assertEquals(0, SqliteDirectChanges.executeUpdate(statement, """
					DELETE FROM subjects
					WHERE id = 999
					"""));
			assertEquals(1, SqliteDirectChanges.executeUpdate(statement, """
					DELETE FROM subjects
					WHERE id = 1
					"""));
		}
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						UPDATE subjects
						SET subject_name = ?
						WHERE id = ?
						""")) {
			statement.setString(1, "Chemistry");
			statement.setLong(2, 2);
			assertEquals(1, SqliteDirectChanges.executeUpdate(statement));
			statement.setLong(2, 999);
			assertEquals(0, SqliteDirectChanges.executeUpdate(statement));
		}
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT COUNT(*)
						FROM subjects
						WHERE id = 2
						  AND subject_name = 'Chemistry'
						""")) {
			assertTrue(result.next());
			assertEquals(1, result.getInt(1));
		}
		database.verifySchema();
		database.verifyIntegrity();
	}
}
