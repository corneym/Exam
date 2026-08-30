package au.edu.eq.questionbank.repository;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Owns the SQLite database location, initialises the question-bank schema, and
 * opens connections with foreign-key enforcement enabled.
 */
public final class SqliteDatabase {

	private final Path databasePath;

	/**
	 * Creates a database boundary for the supplied SQLite file.
	 *
	 * @param databasePath the SQLite database file; converted to a normalized
	 *                     absolute path
	 * @throws NullPointerException if {@code databasePath} is {@code null}
	 */
	public SqliteDatabase(Path databasePath) {
		if (databasePath == null) {
			throw new NullPointerException("databasePath");
		}
		this.databasePath = databasePath.toAbsolutePath().normalize();
	}

	/**
	 * Creates any missing tables required by the current application schema and
	 * records the initial schema version when the database is new. Existing tables
	 * and data are retained.
	 *
	 * @throws SQLException if a connection cannot be opened or a schema statement
	 *                      fails
	 */
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

			statement.execute("""
					CREATE TABLE IF NOT EXISTS exam_providers (
					    id INTEGER PRIMARY KEY,
					    provider_name TEXT NOT NULL UNIQUE
					)
					""");

			statement.execute("""
					CREATE TABLE IF NOT EXISTS source_documents (
					    id INTEGER PRIMARY KEY,
					    relative_path TEXT NOT NULL UNIQUE
					)
					""");

			statement.execute("""
					CREATE TABLE IF NOT EXISTS exams (
					    id INTEGER PRIMARY KEY,
					    subject_id INTEGER NOT NULL,
					    provider_id INTEGER NOT NULL,
					    exam_year INTEGER NOT NULL
					        CHECK (exam_year > 0),
					    exam_name TEXT NOT NULL,

					    FOREIGN KEY (subject_id)
					        REFERENCES subjects(id),

					    FOREIGN KEY (provider_id)
					        REFERENCES exam_providers(id),

					    UNIQUE (
					        subject_id,
					        provider_id,
					        exam_year,
					        exam_name
					    )
					)
					""");

			statement.execute("""
					CREATE TABLE IF NOT EXISTS exam_booklets (
					    id INTEGER PRIMARY KEY,
					    exam_id INTEGER NOT NULL,
					    source_document_id INTEGER NOT NULL,
					    booklet_name TEXT NOT NULL,

					    FOREIGN KEY (exam_id)
					        REFERENCES exams(id),

					    FOREIGN KEY (source_document_id)
					        REFERENCES source_documents(id),

					    UNIQUE (
					        exam_id,
					        booklet_name
					    )
					)
					""");
			statement.execute("""
					CREATE TABLE IF NOT EXISTS questions (
					    id INTEGER PRIMARY KEY,
					    exam_id INTEGER NOT NULL,
					    classification_node_id INTEGER NOT NULL,
					    question_code TEXT NOT NULL,
					    question_text TEXT NOT NULL,

					    FOREIGN KEY (exam_id)
					        REFERENCES exams(id),

					    FOREIGN KEY (classification_node_id)
					        REFERENCES curriculum_nodes(id),

					    UNIQUE (exam_id, question_code)
					)
					""");

			statement.execute("""
					CREATE TABLE IF NOT EXISTS question_regions (
					    question_id INTEGER NOT NULL,
					    region_order INTEGER NOT NULL
					        CHECK (region_order >= 0),
					    booklet_id INTEGER NOT NULL,
					    page_number INTEGER NOT NULL
					        CHECK (page_number >= 1),
					    x REAL NOT NULL
					        CHECK (x >= 0.0 AND x < 1.0),
					    y REAL NOT NULL
					        CHECK (y >= 0.0 AND y < 1.0),
					    width REAL NOT NULL
					        CHECK (width > 0.0 AND width <= 1.0),
					    height REAL NOT NULL
					        CHECK (height > 0.0 AND height <= 1.0),

					    PRIMARY KEY (question_id, region_order),

					    FOREIGN KEY (question_id)
					        REFERENCES questions(id),

					    FOREIGN KEY (booklet_id)
					        REFERENCES exam_booklets(id),

					    CHECK (x + width <= 1.0),
					    CHECK (y + height <= 1.0)
					)
					""");

			statement.execute("""
					CREATE TABLE IF NOT EXISTS answer_files (
					    id INTEGER PRIMARY KEY,
					    exam_id INTEGER NOT NULL,
					    source_document_id INTEGER NOT NULL,
					    answer_file_name TEXT NOT NULL,
					    FOREIGN KEY (exam_id)
					        REFERENCES exams(id),
					    FOREIGN KEY (source_document_id)
					        REFERENCES source_documents(id),
					    UNIQUE (exam_id, answer_file_name)
					)
					""");
			statement.execute("""
					CREATE TABLE IF NOT EXISTS answers (
					    id INTEGER PRIMARY KEY,
					    question_id INTEGER NOT NULL UNIQUE,
					    answer_text TEXT,
					    FOREIGN KEY (question_id)
					        REFERENCES questions(id)
					)
					""");
			statement.execute("""
					CREATE TABLE IF NOT EXISTS answer_regions (
					    answer_id INTEGER NOT NULL,
					    region_order INTEGER NOT NULL
					        CHECK (region_order >= 0),
					    answer_file_id INTEGER NOT NULL,
					    page_number INTEGER NOT NULL
					        CHECK (page_number >= 1),
					    x REAL NOT NULL
					        CHECK (x >= 0.0 AND x < 1.0),
					    y REAL NOT NULL
					        CHECK (y >= 0.0 AND y < 1.0),
					    width REAL NOT NULL
					        CHECK (width > 0.0 AND width <= 1.0),
					    height REAL NOT NULL
					        CHECK (height > 0.0 AND height <= 1.0),
					    PRIMARY KEY (answer_id, region_order),
					    FOREIGN KEY (answer_id)
					        REFERENCES answers(id),
					    FOREIGN KEY (answer_file_id)
					        REFERENCES answer_files(id),
					    CHECK (x + width <= 1.0),
					    CHECK (y + height <= 1.0)
					)
					""");
		}
	}

	/**
	 * Opens a caller-owned connection with SQLite foreign-key enforcement enabled.
	 *
	 * @return an open connection that the caller must close
	 * @throws SQLException if the connection cannot be opened or configured
	 */
	public Connection openConnection() throws SQLException {
		Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);

		try {
			try (Statement statement = connection.createStatement()) {
				statement.execute("PRAGMA foreign_keys = ON");
			}
			return connection;
		} catch (SQLException e) {
			connection.close();
			throw e;
		}
	}
}
