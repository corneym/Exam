package au.edu.eq.questionbank.repository.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.repository.assessment.SqliteQuestionRepository;

class SqliteDatabaseReadOnlyTest {

	@TempDir
	Path tempDirectory;

	@Test
	void ordinaryDatabaseConnectionRemainsWritable() throws Exception {
		Path databasePath = tempDirectory.resolve("writable.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			assertEquals(1, statement.executeUpdate("INSERT INTO subjects (subject_name) " + "VALUES ('Chemistry')"));
		}
	}

	@Test
	void readOnlyConnectionEnablesQueryOnlyMode() throws Exception {
		Path snapshotPath = createSnapshot();
		SqliteDatabase database = SqliteDatabase.readOnly(snapshotPath);
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("PRAGMA query_only")) {
			assertTrue(result.next());
			assertEquals(1, result.getInt(1));
		}
	}

	@Test
	void readsExistingSnapshotThroughProductionRepository() throws Exception {
		Path snapshotPath = createSnapshot();
		SqliteDatabase database = SqliteDatabase.readOnly(snapshotPath);
		assertEquals(SqliteDatabase.latestSchemaVersion(), database.schemaVersion());
		SqliteQuestionRepository repository = new SqliteQuestionRepository(database);
		assertTrue(repository.findAll().isEmpty());
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("SELECT subject_name FROM subjects")) {
			assertTrue(result.next());
			assertEquals("Chemistry", result.getString(1));
			assertFalse(result.next());
		}
	}

	@Test
	void rejectsInsertAndSchemaChanges() throws Exception {
		Path snapshotPath = createSnapshot();
		SqliteDatabase database = SqliteDatabase.readOnly(snapshotPath);
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			assertThrows(SQLException.class,
					() -> statement.executeUpdate("INSERT INTO subjects (subject_name) " + "VALUES ('Physics')"));
			assertThrows(SQLException.class, () -> statement.execute("CREATE TABLE benchmark_mutation (id INTEGER)"));
		}
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM subjects")) {
			assertTrue(result.next());
			assertEquals(1, result.getInt(1));
		}
		assertFalse(Files.exists(snapshotPath.resolveSibling(snapshotPath.getFileName() + "-wal")));
		assertFalse(Files.exists(snapshotPath.resolveSibling(snapshotPath.getFileName() + "-shm")));
	}

	@Test
	void rejectsMissingDatabaseWithoutCreatingIt() {
		Path missing = tempDirectory.resolve("missing.db");
		SqliteDatabase database = SqliteDatabase.readOnly(missing);
		assertThrows(SQLException.class, database::openConnection);
		assertFalse(Files.exists(missing));
	}

	private Path createSnapshot() throws Exception {
		Path sourcePath = tempDirectory.resolve("source.db");

		// Spaces and a URI-sensitive character exercise correct
		// filesystem-to-SQLite-URI conversion.
		Path snapshotPath = tempDirectory.resolve("benchmark copy #1.db");
		SqliteDatabase source = new SqliteDatabase(sourcePath);
		source.initialiseSchema();
		try (Connection connection = source.openConnection(); Statement statement = connection.createStatement()) {
			statement.executeUpdate("INSERT INTO subjects (subject_name) " + "VALUES ('Chemistry')");
		}
		source.createConsistentSnapshot(snapshotPath);
		assertTrue(Files.isRegularFile(snapshotPath));
		return snapshotPath;
	}
}
