package au.edu.eq.questionbank.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteConnectionTest {

	@TempDir
	Path tempDir;

	@Test
	void createsSubjectsTable() throws Exception {
		Path databasePath = tempDir.resolve("questionbank.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);

		database.initialiseSchema();

		try (Connection connection = database.openConnection();
				PreparedStatement insert = connection.prepareStatement("""
						INSERT INTO subjects (id, subject_name)
						VALUES (?, ?)
						""")) {

			insert.setLong(1, 1);
			insert.setString(2, "Chemistry");
			assertEquals(1, insert.executeUpdate());
		}

		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT id, subject_name
						FROM subjects
						""")) {

			assertTrue(result.next());
			assertEquals(1, result.getLong("id"));
			assertEquals("Chemistry", result.getString("subject_name"));
			assertFalse(result.next());
		}
	}

	@Test
	void enablesForeignKeyEnforcement() throws Exception {
		Path databasePath = tempDir.resolve("questionbank.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);

		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("PRAGMA foreign_keys")) {

			assertTrue(result.next());
			assertEquals(1, result.getInt(1));
		}
	}

	@Test
	void generatesSubjectId() throws Exception {
		Path databasePath = tempDir.resolve("questionbank.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);

		database.initialiseSchema();

		try (Connection connection = database.openConnection();
				PreparedStatement insert = connection.prepareStatement("""
						INSERT INTO subjects (subject_name)
						VALUES (?)
						RETURNING id
						""")) {

			insert.setString(1, "Chemistry");

			try (ResultSet result = insert.executeQuery()) {
				assertTrue(result.next());
				assertTrue(result.getLong("id") > 0);
				assertFalse(result.next());
			}
		}
	}

	@Test
	void initialisesSchemaVersionTable() throws Exception {
		Path databasePath = tempDir.resolve("questionbank.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);

		database.initialiseSchema();

		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT name
						FROM sqlite_master
						WHERE type = 'table' AND name = 'schema_version'
						""");
				ResultSet result = statement.executeQuery()) {

			assertTrue(result.next());
			assertEquals("schema_version", result.getString("name"));
		}
	}

	@Test
	void initializesSchemaAtVersionOneOnlyOnce() throws Exception {
		Path databasePath = tempDir.resolve("questionbank.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);

		database.initialiseSchema();
		database.initialiseSchema();

		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT version
						FROM schema_version
						""")) {

			assertTrue(result.next());
			assertEquals(1, result.getInt("version"));
			assertFalse(result.next());
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

	@Test
	void rejectsCurriculumNodeWithUnknownParent() throws Exception {
		Path databasePath = tempDir.resolve("questionbank.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);

		database.initialiseSchema();

		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {

			statement.execute("""
					INSERT INTO subjects (id, subject_name)
					VALUES (1, 'Chemistry')
					""");

			statement.execute("""
					INSERT INTO syllabus_versions
						(id, subject_id, syllabus_name, is_current)
					VALUES (1, 1, '2025', 1)
					""");

			assertThrows(SQLException.class, () -> statement.execute("""
					INSERT INTO curriculum_nodes
						(id, syllabus_version_id, parent_id,
						 curriculum_code, curriculum_name,
						 curriculum_level, display_order)
					VALUES
						(1, 1, 999,
						 '1.1', 'Atomic structure',
						 'TOPIC', 0)
					"""));
		}
	}

	@Test
	void rejectsSyllabusVersionForUnknownSubject() throws Exception {
		Path databasePath = tempDir.resolve("questionbank.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);

		database.initialiseSchema();

		try (Connection connection = database.openConnection();
				PreparedStatement insert = connection.prepareStatement("""
						INSERT INTO syllabus_versions
							(id, subject_id, syllabus_name, is_current)
						VALUES (?, ?, ?, ?)
						""")) {

			insert.setLong(1, 1);
			insert.setLong(2, 999);
			insert.setString(3, "2025");
			insert.setInt(4, 1);

			assertThrows(SQLException.class, insert::executeUpdate);
		}
	}

	@Test
	void storesCompleteCurriculumHierarchy() throws Exception {
		Path databasePath = tempDir.resolve("questionbank.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);

		database.initialiseSchema();

		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {

			statement.execute("""
					INSERT INTO subjects (id, subject_name)
					VALUES (1, 'Chemistry')
					""");

			statement.execute("""
					INSERT INTO syllabus_versions
						(id, subject_id, syllabus_name, is_current)
					VALUES (1, 1, '2025', 1)
					""");

			statement.execute("""
					INSERT INTO curriculum_nodes
						(id, syllabus_version_id, parent_id,
						 curriculum_code, curriculum_name,
						 curriculum_level, display_order)
					VALUES
						(1, 1, NULL, '1', 'Unit 1', 'UNIT', 0),
						(2, 1, 1, '1.1', 'Topic 1', 'TOPIC', 0),
						(3, 1, 2, '1.1.1', 'Subtopic 1', 'SUBTOPIC', 0),
						(4, 1, 3, '1.1.1.1', 'Descriptor text', 'DESCRIPTOR', 0)
					""");

			try (ResultSet result = statement.executeQuery("""
					SELECT parent_id, curriculum_level
					FROM curriculum_nodes
					WHERE id = 4
					""")) {

				assertTrue(result.next());
				assertEquals(3, result.getLong("parent_id"));
				assertEquals("DESCRIPTOR", result.getString("curriculum_level"));
			}
		}
	}
}
