package au.edu.eq.questionbank.repository;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Opens connections to the question-bank SQLite database.
 */
public final class SqliteDatabase {

	private final Path databasePath;

	public SqliteDatabase(Path databasePath) {
		if (databasePath == null) {
			throw new NullPointerException("databasePath");
		}
		this.databasePath = databasePath.toAbsolutePath().normalize();
	}

	public void initialiseSchema() throws SQLException {
		try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					CREATE TABLE IF NOT EXISTS schema_version (
					    version INTEGER NOT NULL
					)
					""");

			statement.execute("""
					INSERT INTO schema_version (version)
					SELECT 1
					WHERE NOT EXISTS (
					    SELECT 1 FROM schema_version
					)
					""");

			statement.execute("""
					CREATE TABLE IF NOT EXISTS subjects (
					   id INTEGER PRIMARY KEY,
					   subject_name TEXT NOT NULL UNIQUE
					)
					""");

			statement.execute("""
					CREATE TABLE IF NOT EXISTS syllabus_versions (
						id INTEGER PRIMARY KEY,
						subject_id INTEGER NOT NULL,
						syllabus_name TEXT NOT NULL,
						is_current INTEGER NOT NULL CHECK (is_current IN (0, 1)),
						FOREIGN KEY (subject_id) REFERENCES subjects(id),
						UNIQUE (subject_id, syllabus_name)
					)
					""");

			statement.execute("""
					CREATE TABLE IF NOT EXISTS curriculum_nodes (
					    id INTEGER PRIMARY KEY,
					    syllabus_version_id INTEGER NOT NULL,
					    parent_id INTEGER,
					    curriculum_code TEXT NOT NULL,
					    curriculum_name TEXT NOT NULL,
					    curriculum_level TEXT NOT NULL
					        CHECK (curriculum_level IN ('UNIT', 'TOPIC', 'SUBTOPIC', 'DESCRIPTOR')),
					    display_order INTEGER NOT NULL
					        CHECK (display_order >= 0),

					    FOREIGN KEY (syllabus_version_id)
					        REFERENCES syllabus_versions(id),

					    FOREIGN KEY (parent_id)
					        REFERENCES curriculum_nodes(id),

					    UNIQUE (syllabus_version_id, curriculum_code)
					)
					""");
		}
	}

	public Connection openConnection() throws SQLException {
		Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);

		try {
			connection.createStatement().execute("PRAGMA foreign_keys = ON");
			return connection;
		} catch (SQLException e) {
			connection.close();
			throw e;
		}
	}
}
