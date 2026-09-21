package au.edu.eq.questionbank.repository.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
	void configuresBusyTimeout() throws Exception {
		Path databasePath = tempDir.resolve("busy-timeout.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("PRAGMA busy_timeout")) {

			// UI reads and asynchronous persistence may briefly overlap. Every
			// application connection must therefore wait for ordinary short-lived SQLite
			// locks rather than failing immediately with SQLITE_BUSY.
			assertTrue(result.next());
			assertEquals(5000, result.getInt(1));
			assertFalse(result.next());
		}
	}

	@Test
	void createsNewDatabaseAtLatestSchemaVersion() throws Exception {
		Path databasePath = tempDir.resolve("questionbank.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT version
						FROM schema_version
						""");
				ResultSet result = statement.executeQuery()) {
			assertTrue(result.next());
			assertEquals(SqliteDatabase.LATEST_SCHEMA_VERSION, result.getInt("version"));
			assertFalse(result.next());
		}
		assertTrue(tableExists(database, "subjects"));
		assertTrue(tableExists(database, "curriculum_mapping_reviews"));
	}

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
	void enforcesExamMetadataNaturalKeys() throws Exception {
		Path databasePath = tempDir.resolve("exam-natural-keys.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("INSERT INTO subjects (id, subject_name) VALUES (1, 'Chemistry')");
			statement.execute("INSERT INTO exam_providers (id, provider_name) VALUES (1, 'QCAA')");
			statement.execute("INSERT INTO source_documents (id, relative_path) VALUES (1, 'paper-1.pdf')");
			statement.execute("INSERT INTO source_documents (id, relative_path) VALUES (2, 'paper-2.pdf')");
			statement.execute("""
					INSERT INTO exams (id, subject_id, provider_id, exam_year, exam_name)
					VALUES (1, 1, 1, 2025, 'External assessment')
					""");
			statement.execute("""
					INSERT INTO exam_booklets (id, exam_id, source_document_id, booklet_name)
					VALUES (1, 1, 1, 'Paper 1')
					""");
			assertThrows(SQLException.class,
					() -> statement.execute("INSERT INTO exam_providers (provider_name) VALUES ('QCAA')"));
			assertThrows(SQLException.class,
					() -> statement.execute("INSERT INTO source_documents (relative_path) VALUES ('paper-1.pdf')"));
			assertThrows(SQLException.class, () -> statement.execute("""
					INSERT INTO exams (subject_id, provider_id, exam_year, exam_name)
					VALUES (1, 1, 2025, 'External assessment')
					"""));
			assertThrows(SQLException.class, () -> statement.execute("""
					INSERT INTO exam_booklets (exam_id, source_document_id, booklet_name)
					VALUES (1, 2, 'Paper 1')
					"""));
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
	void leavesExistingLatestDatabaseUnchanged() throws Exception {
		Path databasePath = tempDir.resolve("questionbank.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("INSERT INTO subjects (subject_name) VALUES ('Chemistry')");
		}
		database.initialiseSchema();
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT version
						FROM schema_version
						""")) {
			assertTrue(result.next());
			assertEquals(SqliteDatabase.LATEST_SCHEMA_VERSION, result.getInt("version"));
			assertFalse(result.next());
		}
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("SELECT subject_name FROM subjects")) {
			assertTrue(result.next());
			assertEquals("Chemistry", result.getString("subject_name"));
			assertFalse(result.next());
		}
	}

	@Test
	void migratesVersion01DatabaseToLatestVersionWithoutLosingData() throws Exception {
		Path databasePath = tempDir.resolve("migration.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/schema-v1.sql"));
			try (Statement statement = connection.createStatement()) {
				statement.execute("""
						INSERT INTO subjects (subject_name)
						VALUES ('Chemistry')
						""");
			}
			connection.commit();
		}
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			try (ResultSet result = statement.executeQuery("""
					SELECT version
					FROM schema_version
					""")) {
				assertTrue(result.next());
				assertEquals(SqliteDatabase.LATEST_SCHEMA_VERSION, result.getInt("version"));
			}
			try (ResultSet result = statement.executeQuery("""
					SELECT subject_name
					FROM subjects
					""")) {
				assertTrue(result.next());
				assertEquals("Chemistry", result.getString("subject_name"));
			}
			try (ResultSet result = statement.executeQuery("""
					SELECT name
					FROM sqlite_master
					WHERE type = 'table'
					  AND name = 'curriculum_mapping_reviews'
					""")) {
				assertTrue(result.next());
			}
		}
	}

	@Test
	void migratesVersion02MappingsAndBackfillsOnlyConfirmedReviews() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("version-two-migration.db"));
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/schema-v1.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v1-to-v2.sql"));
			try (Statement statement = connection.createStatement()) {
				statement.execute("INSERT INTO subjects (id, subject_name) VALUES (1, 'Chemistry')");
				statement.execute("""
						INSERT INTO syllabus_versions
						    (id, subject_id, syllabus_name, is_current)
						VALUES
						    (1, 1, 'Old syllabus', 0),
						    (2, 1, 'Current syllabus', 1),
						    (3, 1, 'Other target syllabus', 0)
						""");
				statement.execute("""
						INSERT INTO curriculum_nodes
						    (id, syllabus_version_id, parent_id, curriculum_code,
						     curriculum_name, curriculum_level, display_order)
						VALUES
						    (10, 1, NULL, '1', 'Old unit', 'UNIT', 0),
						    (11, 1, 10, '1.1', 'Old topic', 'TOPIC', 0),
						    (12, 1, 11, '1.1.1', 'Confirmed source', 'DESCRIPTOR', 0),
						    (13, 1, 11, '1.1.2', 'Suggested source', 'DESCRIPTOR', 1),
						    (20, 2, NULL, '1', 'Current unit', 'UNIT', 0),
						    (21, 2, 20, '1.1', 'Current topic', 'TOPIC', 0),
						    (22, 2, 21, '1.1.1', 'Confirmed target', 'DESCRIPTOR', 0),
						    (30, 3, NULL, '1', 'Other unit', 'UNIT', 0),
						    (31, 3, 30, '1.1', 'Other topic', 'TOPIC', 0),
						    (32, 3, 31, '1.1.1', 'Suggested target', 'DESCRIPTOR', 0)
						""");
				statement.execute("""
						INSERT INTO curriculum_mappings
						    (id, source_node_id, target_node_id, mapping_status)
						VALUES
						    (1, 12, 22, 'CONFIRMED'),
						    (2, 13, 32, 'SUGGESTED')
						""");
			}
			connection.commit();
		}
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			try (ResultSet result = statement.executeQuery("SELECT version FROM schema_version")) {
				assertTrue(result.next());
				assertEquals(SqliteDatabase.LATEST_SCHEMA_VERSION, result.getInt("version"));
				assertFalse(result.next());
			}
			try (ResultSet result = statement.executeQuery("""
					SELECT source_node_id, target_syllabus_version_id, review_outcome
					FROM curriculum_mapping_reviews
					ORDER BY source_node_id
					""")) {
				assertTrue(result.next());
				assertEquals(12, result.getLong("source_node_id"));
				assertEquals(2, result.getLong("target_syllabus_version_id"));
				assertEquals("MATCHED", result.getString("review_outcome"));
				assertFalse(result.next(), "SUGGESTED mappings must remain unreviewed");
			}
			try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM curriculum_mappings")) {
				assertTrue(result.next());
				assertEquals(2, result.getInt(1));
			}
		}
	}

	@Test
	void migratesVersion03DatabaseToLatestVersion() throws Exception {
		Path databasePath = tempDir.resolve("version-three-to-four.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/schema-v1.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v1-to-v2.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v2-to-v3.sql"));
			connection.commit();
		}
		database.verifyMigrationCompatibility();
		assertEquals(3, database.schemaVersion());
		database.initialiseSchema();
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("SELECT version FROM schema_version")) {
			assertTrue(result.next());
			assertEquals(SqliteDatabase.LATEST_SCHEMA_VERSION, result.getInt("version"));
			assertFalse(result.next());
		}
	}

	@Test
	void migratesVersion06CurriculumDataToLatestWithoutLosingIdentity() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("version-six-curriculum-migration.db"));
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/schema-v1.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v1-to-v2.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v2-to-v3.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v3-to-v4.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v4-to-v5.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v5-to-v6.sql"));
			try (Statement statement = connection.createStatement()) {
				statement.execute("""
						INSERT INTO subjects
						    (id, subject_name)
						VALUES
						    (1, 'Engineering')
						""");
				statement.execute("""
						INSERT INTO syllabus_versions
						    (id, subject_id, syllabus_name, is_current)
						VALUES
						    (7, 1, '2025', 1)
						""");
				statement.execute("""
						INSERT INTO curriculum_nodes
						    (id,
						     syllabus_version_id,
						     parent_id,
						     curriculum_code,
						     curriculum_name,
						     curriculum_level,
						     display_order)
						VALUES
						    (42,
						     7,
						     NULL,
						     '1',
						     'Engineering fundamentals',
						     'UNIT',
						     0)
						""");
			}
			connection.commit();
		}
		assertEquals(6, database.schemaVersion());
		database.initialiseSchema();
		assertEquals(SqliteDatabase.LATEST_SCHEMA_VERSION, database.schemaVersion());
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			try (ResultSet result = statement.executeQuery("""
					SELECT
					    id,
					    curriculum_status,
					    curriculum_finalised_at,
					    source_pdf_path
					FROM syllabus_versions
					WHERE id = 7
					""")) {
				assertTrue(result.next());
				assertEquals(7, result.getLong("id"));
				assertEquals("IN_PROGRESS", result.getString("curriculum_status"));
				assertNull(result.getString("curriculum_finalised_at"));
				assertNull(result.getString("source_pdf_path"));
				assertFalse(result.next());
			}
			try (ResultSet result = statement.executeQuery("""
					SELECT
					    id,
					    syllabus_version_id,
					    curriculum_name,
					    source_page_number
					FROM curriculum_nodes
					WHERE id = 42
					""")) {
				assertTrue(result.next());

				// The imported node identity is deliberately preserved by migration.
				assertEquals(42, result.getLong("id"));
				assertEquals(7, result.getLong("syllabus_version_id"));
				assertEquals("Engineering fundamentals", result.getString("curriculum_name"));
				result.getInt("source_page_number");
				assertTrue(result.wasNull());
				assertFalse(result.next());
			}
		}
	}

	@Test
	void migratesVersion07QuestionResponseTypesConservatively() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("version-seven-response-type.db"));
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/schema-v1.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v1-to-v2.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v2-to-v3.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v3-to-v4.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v4-to-v5.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v5-to-v6.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v6-to-v7.sql"));
			try (Statement statement = connection.createStatement()) {
				statement.execute("""
						INSERT INTO subjects
						    (id, subject_name)
						VALUES
						    (1, 'Chemistry')
						""");
				statement.execute("""
						INSERT INTO syllabus_versions
						    (id,
						     subject_id,
						     syllabus_name,
						     is_current)
						VALUES
						    (1, 1, '2019', 0)
						""");
				statement.execute("""
						INSERT INTO curriculum_nodes
						    (id,
						     syllabus_version_id,
						     parent_id,
						     curriculum_code,
						     curriculum_name,
						     curriculum_level,
						     display_order)
						VALUES
						    (1, 1, NULL,
						     '1', 'Unit 1',
						     'UNIT', 0),
						    (2, 1, 1,
						     '1.1', 'Topic 1',
						     'TOPIC', 0),
						    (3, 1, 2,
						     '1.1.1', 'Subtopic 1',
						     'SUBTOPIC', 0)
						""");
				statement.execute("""
						INSERT INTO exam_providers
						    (id, provider_name)
						VALUES
						    (1, 'QCAA')
						""");
				statement.execute("""
						INSERT INTO source_documents
						    (id, relative_path)
						VALUES
						    (1, 'mcq.pdf'),
						    (2, 'paper1.pdf'),
						    (3, 'mixed.pdf')
						""");
				statement.execute("""
						INSERT INTO exams
						    (id,
						     subject_id,
						     provider_id,
						     exam_year,
						     exam_name)
						VALUES
						    (1, 1, 1, 2019,
						     'External Assessment')
						""");
				statement.execute("""
						INSERT INTO exam_booklets
						    (id,
						     exam_id,
						     source_document_id,
						     booklet_name)
						VALUES
						    (1, 1, 1, 'MCQ booklet'),
						    (2, 1, 2, 'Paper 1'),
						    (3, 1, 3, 'Mixed booklet')
						""");
				statement.execute("""
						INSERT INTO questions
						    (id,
						     booklet_id,
						     classification_node_id,
						     question_code,
						     question_text,
						     marks,
						     preamble_capture_required)
						VALUES
						    (1, 1, 3, '1', '', 1, 0),
						    (2, 2, 3, '2', '', 2, 0),
						    (3, 3, 3, '3', '', 2, 0)
						""");
			}
			connection.commit();
		}
		assertEquals(7, database.schemaVersion());
		database.initialiseSchema();
		assertEquals(SqliteDatabase.LATEST_SCHEMA_VERSION, database.schemaVersion());
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT
						    question_code,
						    response_type
						FROM questions
						ORDER BY id
						""")) {
			assertTrue(result.next());
			assertEquals("1", result.getString("question_code"));
			assertEquals("MULTIPLE_CHOICE", result.getString("response_type"));
			assertTrue(result.next());
			assertEquals("2", result.getString("question_code"));
			assertEquals("UNKNOWN", result.getString("response_type"));
			assertTrue(result.next());
			assertEquals("3", result.getString("question_code"));
			assertEquals("UNKNOWN", result.getString("response_type"));
			assertFalse(result.next());
		}
	}

	@Test
	void migratesVersion08BookletsWithUnspecifiedQuestionFormat() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("version-eight-booklet-format.db"));
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);

			// Construct a genuine version-eight database so this test exercises only
			// the new booklet-format migration.
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/schema-v1.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v1-to-v2.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v2-to-v3.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v3-to-v4.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v4-to-v5.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v5-to-v6.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v6-to-v7.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v7-to-v8.sql"));
			try (Statement statement = connection.createStatement()) {
				statement.execute("INSERT INTO subjects (id, subject_name) VALUES (1, 'Chemistry')");
				statement.execute("INSERT INTO exam_providers (id, provider_name) VALUES (1, 'QCAA')");
				statement.execute("INSERT INTO source_documents (id, relative_path) VALUES (1, 'paper.pdf')");
				statement.execute("""
						INSERT INTO exams
						    (id, subject_id, provider_id, exam_year, exam_name)
						VALUES
						    (1, 1, 1, 2025, 'External Assessment')
						""");
				statement.execute("""
						INSERT INTO exam_booklets
						    (id, exam_id, source_document_id, booklet_name)
						VALUES
						    (1, 1, 1, 'Paper 1')
						""");
			}
			connection.commit();
		}
		assertEquals(8, database.schemaVersion());

		// A pre-existing booklet contains no reliable response-format metadata, so
		// migration must preserve that uncertainty rather than infer from its name.
		database.initialiseSchema();
		assertEquals(SqliteDatabase.LATEST_SCHEMA_VERSION, database.schemaVersion());
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT question_format
						FROM exam_booklets
						WHERE id = 1
						""")) {
			assertTrue(result.next());
			assertEquals("UNSPECIFIED", result.getString("question_format"));
			assertFalse(result.next());
		}
	}

	@Test
	void migratesVersion09BookletAnswerFilesConservatively() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("version-nine-answer-files.db"));
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);

			// Construct a genuine version-nine database so only the new booklet-answer
			// relationship is exercised by this migration test.
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/schema-v1.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v1-to-v2.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v2-to-v3.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v3-to-v4.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v4-to-v5.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v5-to-v6.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v6-to-v7.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v7-to-v8.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v8-to-v9.sql"));
			try (Statement statement = connection.createStatement()) {
				statement.execute("INSERT INTO subjects (id, subject_name) VALUES (1, 'Chemistry')");
				statement.execute("""
						INSERT INTO syllabus_versions
						    (id, subject_id, syllabus_name, is_current)
						VALUES
						    (1, 1, '2025', 1)
						""");
				statement.execute("""
						INSERT INTO curriculum_nodes
						    (id, syllabus_version_id, parent_id,
						     curriculum_code, curriculum_name,
						     curriculum_level, display_order)
						VALUES
						    (1, 1, NULL, '1', 'Unit 1', 'UNIT', 0),
						    (2, 1, 1, '1.1', 'Topic 1', 'TOPIC', 0),
						    (3, 1, 2, '1.1.1', 'Subtopic 1', 'SUBTOPIC', 0)
						""");
				statement.execute("INSERT INTO exam_providers (id, provider_name) VALUES (1, 'QCAA')");
				statement.execute("""
						INSERT INTO source_documents
						    (id, relative_path)
						VALUES
						    (1, 'mcq.pdf'),
						    (2, 'paper1.pdf'),
						    (3, 'paper2.pdf'),
						    (4, 'legacy-ambiguous.pdf'),
						    (10, 'answers-a.pdf'),
						    (11, 'answers-b.pdf')
						""");
				statement.execute("""
						INSERT INTO exams
						    (id, subject_id, provider_id, exam_year, exam_name)
						VALUES
						    (1, 1, 1, 2025, 'External Assessment')
						""");
				statement.execute("""
						INSERT INTO exam_booklets
						    (id, exam_id, source_document_id, booklet_name, question_format)
						VALUES
						    (1, 1, 1, 'MCQ booklet', 'MULTIPLE_CHOICE'),
						    (2, 1, 2, 'Paper 1', 'WRITTEN_RESPONSE'),
						    (3, 1, 3, 'Paper 2', 'WRITTEN_RESPONSE'),
						    (4, 1, 4, 'Legacy ambiguous', 'WRITTEN_RESPONSE')
						""");
				statement.execute("""
						INSERT INTO answer_files
						    (id, exam_id, source_document_id, answer_file_name)
						VALUES
						    (10, 1, 10, 'Answers A'),
						    (11, 1, 11, 'Answers B')
						""");
				statement.execute("""
						INSERT INTO questions
						    (id, booklet_id, classification_node_id,
						     question_code, question_text, marks,
						     preamble_capture_required, response_type)
						VALUES
						    (100, 2, 3, '1', '', 2, 0, 'WRITTEN_RESPONSE'),
						    (101, 3, 3, '2', '', 2, 0, 'WRITTEN_RESPONSE'),
						    (102, 4, 3, '3', '', 2, 0, 'WRITTEN_RESPONSE'),
						    (103, 4, 3, '4', '', 2, 0, 'WRITTEN_RESPONSE')
						""");
				statement.execute("""
						INSERT INTO answers
						    (id, question_id, answer_text)
						VALUES
						    (200, 100, NULL),
						    (201, 101, NULL),
						    (202, 102, NULL),
						    (203, 103, NULL)
						""");
				statement.execute("""
						INSERT INTO answer_regions
						    (answer_id, region_order, answer_file_id,
						     page_number, x, y, width, height)
						VALUES
						    (200, 0, 10, 1, 0.10, 0.10, 0.50, 0.20),
						    (201, 0, 11, 1, 0.10, 0.10, 0.50, 0.20),
						    (202, 0, 10, 1, 0.10, 0.10, 0.50, 0.20),
						    (203, 0, 11, 1, 0.10, 0.10, 0.50, 0.20)
						""");
			}
			connection.commit();
		}
		assertEquals(9, database.schemaVersion());
		database.initialiseSchema();
		assertEquals(10, database.schemaVersion());
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT id, answer_file_id
						FROM exam_booklets
						ORDER BY id
						""")) {

			// The MCQ booklet has no region evidence, so migration must not guess that it
			// shares Answers A with Paper 1.
			assertTrue(result.next());
			assertEquals(1, result.getLong("id"));
			assertNull(result.getObject("answer_file_id"));

			// Paper 1 has one unambiguous historical source.
			assertTrue(result.next());
			assertEquals(2, result.getLong("id"));
			assertEquals(10, result.getLong("answer_file_id"));

			// Paper 2 independently resolves to the second answer document.
			assertTrue(result.next());
			assertEquals(3, result.getLong("id"));
			assertEquals(11, result.getLong("answer_file_id"));

			// Conflicting legacy evidence is preserved as unresolved rather than guessed.
			assertTrue(result.next());
			assertEquals(4, result.getLong("id"));
			assertNull(result.getObject("answer_file_id"));
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
	void rejectsAnswersWithoutQuestionForeignKey() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("answer-missing-foreign-key.db"));
		database.initialiseSchema();
		replaceTable(database, "answers", """
				CREATE TABLE answers (
				    id INTEGER PRIMARY KEY,
				    question_id INTEGER NOT NULL UNIQUE,
				    answer_text TEXT
				)
				""");
		SQLException exception = assertThrows(SQLException.class, database::verifySchema);
		assertTrue(
				exception.getMessage().contains("answers is missing exact foreign key question_id -> questions(id)"));
	}

	@Test
	void rejectsCompositeSourceForeignKeyImpostor() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("composite-review-source-foreign-key.db"));
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("DROP TABLE curriculum_mapping_reviews");
			statement.execute("""
					CREATE TABLE curriculum_mapping_reviews (
					    source_node_id INTEGER NOT NULL,
					    source_syllabus_version_id INTEGER NOT NULL,
					    target_syllabus_version_id INTEGER NOT NULL,
					    review_outcome TEXT NOT NULL,
					    PRIMARY KEY (source_node_id, target_syllabus_version_id),
					    FOREIGN KEY (source_node_id, source_syllabus_version_id)
					        REFERENCES curriculum_nodes(id, syllabus_version_id),
					    FOREIGN KEY (target_syllabus_version_id) REFERENCES syllabus_versions(id)
					)
					""");
		}
		SQLException exception = assertThrows(SQLException.class, database::initialiseSchema);
		assertTrue(exception.getMessage().contains("missing exact foreign key source_node_id"));
	}

	@Test
	void rejectsCompositeTargetVersionForeignKeyImpostor() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("composite-review-target-foreign-key.db"));
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("DROP TABLE curriculum_mapping_reviews");
			statement.execute("""
					CREATE TABLE curriculum_mapping_reviews (
					    source_node_id INTEGER NOT NULL,
					    target_syllabus_version_id INTEGER NOT NULL,
					    target_subject_id INTEGER NOT NULL,
					    review_outcome TEXT NOT NULL,
					    PRIMARY KEY (source_node_id, target_syllabus_version_id),
					    FOREIGN KEY (source_node_id) REFERENCES curriculum_nodes(id),
					    FOREIGN KEY (target_syllabus_version_id, target_subject_id)
					        REFERENCES syllabus_versions(id, subject_id)
					)
					""");
		}
		SQLException exception = assertThrows(SQLException.class, database::initialiseSchema);
		assertTrue(exception.getMessage().contains("missing exact foreign key target_syllabus_version_id"));
	}

	@Test
	void rejectsCurriculumNodesWithoutSyllabusForeignKey() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("curriculum-node-missing-foreign-key.db"));
		database.initialiseSchema();
		replaceTable(database, "curriculum_nodes", """
				CREATE TABLE curriculum_nodes (
				    id INTEGER PRIMARY KEY,
				    syllabus_version_id INTEGER NOT NULL,
				    parent_id INTEGER,
				    curriculum_code TEXT NOT NULL,
				    curriculum_name TEXT NOT NULL,
				    curriculum_level TEXT NOT NULL,
				    display_order INTEGER NOT NULL,
				    FOREIGN KEY (parent_id)
				        REFERENCES curriculum_nodes(id),
				    UNIQUE (
				        syllabus_version_id,
				        curriculum_code
				    )
				)
				""");
		SQLException exception = assertThrows(SQLException.class, database::verifySchema);
		assertTrue(exception.getMessage().contains(
				"curriculum_nodes is missing exact foreign key syllabus_version_id -> syllabus_versions(id)"));
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
	void rejectsDatabaseWithEmptySchemaVersionTable() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("empty-version.db"));
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("CREATE TABLE schema_version (version INTEGER NOT NULL)");
		}
		SQLException exception = assertThrows(SQLException.class, database::initialiseSchema);
		assertTrue(exception.getMessage().contains("schema_version table is empty"));
		assertFalse(tableExists(database, "curriculum_mappings"));
	}

	@Test
	void rejectsDatabaseWithMultipleSchemaVersionRows() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("multiple-versions.db"));
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("CREATE TABLE schema_version (version INTEGER NOT NULL)");
			statement.execute("INSERT INTO schema_version (version) VALUES (1), (1)");
		}
		SQLException exception = assertThrows(SQLException.class, database::initialiseSchema);
		assertTrue(exception.getMessage().contains("more than one row"));
		assertFalse(tableExists(database, "curriculum_mappings"));
	}

	@Test
	void rejectsDatabaseWithNewerSchemaVersion() throws Exception {
		Path databasePath = tempDir.resolve("newer-schema.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					CREATE TABLE schema_version (
					    version INTEGER NOT NULL
					)
					""");
			statement.execute(String.format("INSERT INTO schema_version (version) VALUES (%d)",
					SqliteDatabase.LATEST_SCHEMA_VERSION + 1));
		}
		SQLException exception = assertThrows(SQLException.class, database::initialiseSchema);
		assertTrue(exception.getMessage()
				.contains("Unsupported database schema version " + (SqliteDatabase.LATEST_SCHEMA_VERSION + 1)));
	}

	@Test
	void rejectsExamBookletsWithoutNaturalKey() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("booklet-missing-key.db"));
		database.initialiseSchema();
		replaceTable(database, "exam_booklets", """
				CREATE TABLE exam_booklets (
				    id INTEGER PRIMARY KEY,
				    exam_id INTEGER NOT NULL,
				    source_document_id INTEGER NOT NULL,
				    booklet_name TEXT NOT NULL,
				    FOREIGN KEY (exam_id)
				        REFERENCES exams(id),
				    FOREIGN KEY (source_document_id)
				        REFERENCES source_documents(id)
				)
				""");
		SQLException exception = assertThrows(SQLException.class, database::verifySchema);
		assertTrue(
				exception.getMessage().contains("exam_booklets is missing exact unique key (exam_id, booklet_name)"));
	}

	@Test
	void rejectsExamMetadataWithInvalidForeignKeys() throws Exception {
		Path databasePath = tempDir.resolve("invalid-exam-relationships.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("INSERT INTO subjects (id, subject_name) VALUES (1, 'Chemistry')");
			statement.execute("INSERT INTO exam_providers (id, provider_name) VALUES (1, 'QCAA')");
			statement.execute("INSERT INTO source_documents (id, relative_path) VALUES (1, 'exam.pdf')");
			assertThrows(SQLException.class, () -> statement.execute("""
					INSERT INTO exams (id, subject_id, provider_id, exam_year, exam_name)
					VALUES (1, 999, 1, 2025, 'Unknown subject')
					"""));
			assertThrows(SQLException.class, () -> statement.execute("""
					INSERT INTO exams (id, subject_id, provider_id, exam_year, exam_name)
					VALUES (1, 1, 999, 2025, 'Unknown provider')
					"""));
			statement.execute("""
					INSERT INTO exams (id, subject_id, provider_id, exam_year, exam_name)
					VALUES (1, 1, 1, 2025, 'External assessment')
					""");
			assertThrows(SQLException.class, () -> statement.execute("""
					INSERT INTO exam_booklets (id, exam_id, source_document_id, booklet_name)
					VALUES (1, 999, 1, 'Unknown exam')
					"""));
			assertThrows(SQLException.class, () -> statement.execute("""
					INSERT INTO exam_booklets (id, exam_id, source_document_id, booklet_name)
					VALUES (1, 1, 999, 'Unknown document')
					"""));
		}
	}

	@Test
	void rejectsInvalidQuestionResponseType() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("response-type-constraint.db"));
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					INSERT INTO subjects
					    (id, subject_name)
					VALUES
					    (1, 'Chemistry')
					""");
			statement.execute("""
					INSERT INTO syllabus_versions
					    (id,
					     subject_id,
					     syllabus_name,
					     is_current)
					VALUES
					    (1, 1, '2025', 1)
					""");
			statement.execute("""
					INSERT INTO curriculum_nodes
					    (id,
					     syllabus_version_id,
					     parent_id,
					     curriculum_code,
					     curriculum_name,
					     curriculum_level,
					     display_order)
					VALUES
					    (1, 1, NULL,
					     '1', 'Unit 1',
					     'UNIT', 0),
					    (2, 1, 1,
					     '1.1', 'Topic 1',
					     'TOPIC', 0),
					    (3, 1, 2,
					     '1.1.1', 'Subtopic 1',
					     'SUBTOPIC', 0)
					""");
			statement.execute("""
					INSERT INTO exam_providers
					    (id, provider_name)
					VALUES
					    (1, 'QCAA')
					""");
			statement.execute("""
					INSERT INTO source_documents
					    (id, relative_path)
					VALUES
					    (1, 'questions.pdf')
					""");
			statement.execute("""
					INSERT INTO exams
					    (id,
					     subject_id,
					     provider_id,
					     exam_year,
					     exam_name)
					VALUES
					    (1, 1, 1, 2025,
					     'External Assessment')
					""");
			statement.execute("""
					INSERT INTO exam_booklets
					    (id,
					     exam_id,
					     source_document_id,
					     booklet_name)
					VALUES
					    (1, 1, 1, 'Mixed booklet')
					""");
			assertThrows(SQLException.class, () -> statement.execute("""
					INSERT INTO questions
					    (id,
					     booklet_id,
					     classification_node_id,
					     question_code,
					     question_text,
					     marks,
					     preamble_capture_required,
					     response_type)
					VALUES
					    (1, 1, 3,
					     'Q1', '', 1, 0,
					     'ESSAY')
					"""));
		}
	}

	@Test
	void rejectsInvalidSchemaVersionValues() throws Exception {
		String[] invalidValues = { "0", "-1", "1.5" };
		for (int index = 0; index < invalidValues.length; index++) {
			SqliteDatabase database = new SqliteDatabase(tempDir.resolve("invalid-version-" + index + ".db"));
			try (Connection connection = database.openConnection();
					Statement statement = connection.createStatement()) {
				statement.execute("CREATE TABLE schema_version (version)");
				statement.execute("INSERT INTO schema_version (version) VALUES (" + invalidValues[index] + ")");
			}
			SQLException exception = assertThrows(SQLException.class, database::initialiseSchema);
			assertTrue(exception.getMessage().contains("Invalid database schema version"));
			assertFalse(tableExists(database, "curriculum_mappings"));
		}
	}

	@Test
	void rejectsNonEmptyDatabaseWithoutSchemaVersion() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("unversioned-partial.db"));
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("CREATE TABLE subjects (id INTEGER PRIMARY KEY, subject_name TEXT NOT NULL UNIQUE)");
		}
		SQLException exception = assertThrows(SQLException.class, database::initialiseSchema);
		assertTrue(exception.getMessage().contains("has no schema_version table"));
		assertTrue(tableExists(database, "subjects"));
		assertFalse(tableExists(database, "schema_version"));
	}

	@Test
	void rejectsNonPositiveExamYears() throws Exception {
		Path databasePath = tempDir.resolve("invalid-exam-year.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("INSERT INTO subjects (id, subject_name) VALUES (1, 'Chemistry')");
			statement.execute("INSERT INTO exam_providers (id, provider_name) VALUES (1, 'QCAA')");
			assertThrows(SQLException.class, () -> statement.execute("""
					INSERT INTO exams (subject_id, provider_id, exam_year, exam_name)
					VALUES (1, 1, 0, 'Invalid year')
					"""));
		}
	}

	@Test
	void rejectsQuestionRegionsMissingPageNumber() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("missing-region-page.db"));
		database.initialiseSchema();
		replaceTable(database, "question_regions", """
				CREATE TABLE question_regions (
				    question_id INTEGER NOT NULL,
				    region_order INTEGER NOT NULL,
				    booklet_id INTEGER NOT NULL,
				    x REAL NOT NULL,
				    y REAL NOT NULL,
				    width REAL NOT NULL,
				    height REAL NOT NULL,
				    PRIMARY KEY (
				        question_id,
				        region_order
				    ),
				    FOREIGN KEY (question_id)
				        REFERENCES questions(id),
				    FOREIGN KEY (booklet_id)
				        REFERENCES exam_booklets(id)
				)
				""");
		SQLException exception = assertThrows(SQLException.class, database::verifySchema);
		assertTrue(exception.getMessage().contains("question_regions is missing required column page_number"));
	}

	@Test
	void rejectsSupportedVersionWhenRequiredSchemaIsMissing() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("missing-v1-schema.db"));
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("CREATE TABLE schema_version (version INTEGER NOT NULL)");
			statement.execute("INSERT INTO schema_version (version) VALUES (1)");
		}
		SQLException exception = assertThrows(SQLException.class, database::initialiseSchema);
		assertTrue(exception.getMessage().contains("missing required table subjects"));
		assertFalse(tableExists(database, "curriculum_mappings"));
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
	void rejectsVersion02DatabaseWithoutItsMigrationTable() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("missing-v2-schema.db"));
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/schema-v1.sql"));
			try (Statement statement = connection.createStatement()) {
				statement.execute("UPDATE schema_version SET version = 2");
			}
			connection.commit();
		}
		SQLException exception = assertThrows(SQLException.class, database::initialiseSchema);
		assertTrue(exception.getMessage().contains("missing required table curriculum_mappings"));
	}

	@Test
	void rejectsVersion03DatabaseContainingQuestions() throws Exception {
		Path databasePath = tempDir.resolve("populated-version-three.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/schema-v1.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v1-to-v2.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v2-to-v3.sql"));
			try (Statement statement = connection.createStatement()) {
				statement.execute("INSERT INTO subjects (id, subject_name) VALUES (1, 'Chemistry')");
				statement.execute("""
						INSERT INTO syllabus_versions
						    (id, subject_id, syllabus_name, is_current)
						VALUES (1, 1, '2019', 0)
						""");
				statement.execute("""
						INSERT INTO curriculum_nodes
						    (id, syllabus_version_id, parent_id, curriculum_code,
						     curriculum_name, curriculum_level, display_order)
						VALUES
						    (1, 1, NULL, '1', 'Unit 1', 'UNIT', 1),
						    (2, 1, 1, '1.1', 'Topic 1', 'TOPIC', 1),
						    (3, 1, 2, '1.1.1', 'Subtopic 1', 'SUBTOPIC', 1)
						""");
				statement.execute("INSERT INTO exam_providers (id, provider_name) VALUES (1, 'QCAA')");
				statement.execute("INSERT INTO source_documents (id, relative_path) VALUES (1, 'paper.pdf')");
				statement.execute("""
						INSERT INTO exams
						    (id, subject_id, provider_id, exam_year, exam_name)
						VALUES (1, 1, 1, 2020, 'External Assessment')
						""");
				statement.execute("""
						INSERT INTO exam_booklets
						    (id, exam_id, source_document_id, booklet_name)
						VALUES (1, 1, 1, 'Paper 1')
						""");
				statement.execute("""
						INSERT INTO questions
						    (id, exam_id, classification_node_id, question_code, question_text)
						VALUES (1, 1, 3, 'Q1', '')
						""");
			}
			connection.commit();
		}
		IncompatibleDatabaseException compatibilityException = assertThrows(IncompatibleDatabaseException.class,
				database::verifyMigrationCompatibility);
		assertEquals("The existing database contains question data that cannot be migrated safely.",
				compatibilityException.getMessage());
		assertEquals(3, database.schemaVersion());
		IncompatibleDatabaseException exception = assertThrows(IncompatibleDatabaseException.class,
				database::initialiseSchema);
		assertEquals("The existing database contains question data that cannot be migrated safely.",
				exception.getMessage());
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("SELECT version FROM schema_version")) {
			assertTrue(result.next());
			assertEquals(3, result.getInt("version"));
		}
	}

	@Test
	void rejectsVersion03DatabaseWithoutReviewTable() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("missing-v3-schema.db"));
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/schema-v1.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v1-to-v2.sql"));
			try (Statement statement = connection.createStatement()) {
				statement.execute("UPDATE schema_version SET version = 3");
			}
			connection.commit();
		}
		SQLException exception = assertThrows(SQLException.class, database::initialiseSchema);
		assertTrue(exception.getMessage().contains("missing required table curriculum_mapping_reviews"));
		assertFalse(tableExists(database, "curriculum_mapping_reviews"));
	}

	@Test
	void rejectsVersion03ReviewTableWithExtraPrimaryKeyColumn() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("extra-review-primary-key-column.db"));
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("DROP TABLE curriculum_mapping_reviews");
			statement.execute("""
					CREATE TABLE curriculum_mapping_reviews (
					    source_node_id INTEGER NOT NULL,
					    target_syllabus_version_id INTEGER NOT NULL,
					    review_outcome TEXT NOT NULL,
					    review_scope INTEGER NOT NULL,
					    PRIMARY KEY (source_node_id, target_syllabus_version_id, review_scope),
					    FOREIGN KEY (source_node_id) REFERENCES curriculum_nodes(id),
					    FOREIGN KEY (target_syllabus_version_id) REFERENCES syllabus_versions(id)
					)
					""");
		}
		SQLException exception = assertThrows(SQLException.class, database::initialiseSchema);
		assertTrue(exception.getMessage().contains("expected exactly"));
	}

	@Test
	void rejectsVersion03ReviewTableWithInvalidPrimaryKey() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("invalid-review-primary-key.db"));
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("DROP TABLE curriculum_mapping_reviews");
			statement.execute("""
					CREATE TABLE curriculum_mapping_reviews (
					    source_node_id INTEGER NOT NULL PRIMARY KEY,
					    target_syllabus_version_id INTEGER NOT NULL,
					    review_outcome TEXT NOT NULL,
					    FOREIGN KEY (source_node_id) REFERENCES curriculum_nodes(id),
					    FOREIGN KEY (target_syllabus_version_id) REFERENCES syllabus_versions(id)
					)
					""");
		}
		SQLException exception = assertThrows(SQLException.class, database::initialiseSchema);
		assertTrue(exception.getMessage().contains("invalid primary key"));
	}

	@Test
	void rejectsVersion03ReviewTableWithMissingColumn() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("missing-review-column.db"));
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("DROP TABLE curriculum_mapping_reviews");
			statement.execute("""
					CREATE TABLE curriculum_mapping_reviews (
					    source_node_id INTEGER NOT NULL,
					    target_syllabus_version_id INTEGER NOT NULL,
					    PRIMARY KEY (source_node_id, target_syllabus_version_id),
					    FOREIGN KEY (source_node_id) REFERENCES curriculum_nodes(id),
					    FOREIGN KEY (target_syllabus_version_id) REFERENCES syllabus_versions(id)
					)
					""");
		}
		SQLException exception = assertThrows(SQLException.class, database::initialiseSchema);
		assertTrue(exception.getMessage().contains("missing required column review_outcome"));
	}

	@Test
	void rejectsVersion03ReviewTableWithMissingSourceForeignKey() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("missing-review-source-foreign-key.db"));
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("DROP TABLE curriculum_mapping_reviews");
			statement.execute("""
					CREATE TABLE curriculum_mapping_reviews (
					    source_node_id INTEGER NOT NULL,
					    target_syllabus_version_id INTEGER NOT NULL,
					    review_outcome TEXT NOT NULL,
					    PRIMARY KEY (source_node_id, target_syllabus_version_id),
					    FOREIGN KEY (target_syllabus_version_id) REFERENCES syllabus_versions(id)
					)
					""");
		}
		SQLException exception = assertThrows(SQLException.class, database::initialiseSchema);
		assertTrue(exception.getMessage().contains("missing exact foreign key source_node_id"));
	}

	@Test
	void rejectsVersion03ReviewTableWithMissingTargetVersionForeignKey() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("missing-review-target-foreign-key.db"));
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("DROP TABLE curriculum_mapping_reviews");
			statement.execute("""
					CREATE TABLE curriculum_mapping_reviews (
					    source_node_id INTEGER NOT NULL,
					    target_syllabus_version_id INTEGER NOT NULL,
					    review_outcome TEXT NOT NULL,
					    PRIMARY KEY (source_node_id, target_syllabus_version_id),
					    FOREIGN KEY (source_node_id) REFERENCES curriculum_nodes(id)
					)
					""");
		}
		SQLException exception = assertThrows(SQLException.class, database::initialiseSchema);
		assertTrue(exception.getMessage().contains("missing exact foreign key target_syllabus_version_id"));
	}

	@Test
	void rejectsVersion04QuestionTableMissingMarks() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("v4-questions-missing-marks.db"));
		database.initialiseSchema();
		replaceQuestionsTable(database, """
				CREATE TABLE questions (
				    id INTEGER PRIMARY KEY,
				    booklet_id INTEGER NOT NULL,
				    classification_node_id INTEGER NOT NULL,
				    question_code TEXT NOT NULL,
				    question_text TEXT NOT NULL,
				    preamble_capture_required INTEGER NOT NULL,
				    FOREIGN KEY (booklet_id) REFERENCES exam_booklets(id),
				    FOREIGN KEY (classification_node_id) REFERENCES curriculum_nodes(id),
				    UNIQUE (booklet_id, question_code)
				)
				""");
		SQLException exception = assertThrows(SQLException.class, database::initialiseSchema);
		assertTrue(exception.getMessage().contains("missing required column marks"));
	}

	@Test
	void rejectsVersion04QuestionTableWithoutItsNaturalKey() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("v4-questions-missing-natural-key.db"));
		database.initialiseSchema();
		replaceQuestionsTable(database, """
				CREATE TABLE questions (
				    id INTEGER PRIMARY KEY,
				    booklet_id INTEGER NOT NULL,
				    classification_node_id INTEGER NOT NULL,
				    question_code TEXT NOT NULL,
				    question_text TEXT NOT NULL,
				    marks INTEGER NOT NULL,
				    preamble_capture_required INTEGER NOT NULL,
				    FOREIGN KEY (booklet_id) REFERENCES exam_booklets(id),
				    FOREIGN KEY (classification_node_id) REFERENCES curriculum_nodes(id)
				)
				""");
		SQLException exception = assertThrows(SQLException.class, database::initialiseSchema);
		assertTrue(exception.getMessage().contains("missing exact unique key"));
	}

	@Test
	void rejectsVersion05DatabaseWithoutSharedQuestionSchema() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("version-five-missing-shared-question-schema.db"));
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);

			// Build a genuine version-four database, then falsely claim version five
			// without applying the migration that introduces shared-question structure.
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/schema-v1.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v1-to-v2.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v2-to-v3.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v3-to-v4.sql"));
			try (Statement statement = connection.createStatement()) {
				statement.execute("UPDATE schema_version SET version = 5");
			}
			connection.commit();
		}

		// A database claiming version five must contain the source-question and shared
		// context structures introduced by the v4-to-v5 migration.
		SQLException exception = assertThrows(SQLException.class, database::verifySchema);

		// The version-five verifier reaches the absent source_questions structure
		// through its required-column checks rather than a separate table-exists check.
		assertTrue(exception.getMessage().contains("source_questions is missing required column"));
		assertEquals(5, database.schemaVersion());
	}

	@Test
	void rejectsVersion07DatabaseWithoutCurriculumAuthoringSchema() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("version-seven-missing-authoring-schema.db"));
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);

			// Build a genuine version-six database, then falsely claim version seven
			// without applying the curriculum-authoring migration.
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/schema-v1.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v1-to-v2.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v2-to-v3.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v3-to-v4.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v4-to-v5.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v5-to-v6.sql"));
			try (Statement statement = connection.createStatement()) {
				statement.execute("UPDATE schema_version SET version = 7");
			}
			connection.commit();
		}

		// Version seven requires the curriculum-authoring columns added to
		// syllabus_versions and curriculum_nodes.
		SQLException exception = assertThrows(SQLException.class, database::verifySchema);

		// Several version-seven columns are absent from the deliberately falsified
		// version-six schema, so do not depend on HashSet iteration choosing one name.
		assertTrue(exception.getMessage().contains("syllabus_versions is missing required column"));
		assertEquals(7, database.schemaVersion());
	}

	@Test
	void rejectsVersion08DatabaseWithoutQuestionResponseType() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("version-eight-missing-response-type.db"));
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);

			// Build a genuine version-seven database, then falsely claim version eight
			// without applying the response-type migration.
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/schema-v1.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v1-to-v2.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v2-to-v3.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v3-to-v4.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v4-to-v5.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v5-to-v6.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v6-to-v7.sql"));
			try (Statement statement = connection.createStatement()) {
				statement.execute("UPDATE schema_version SET version = 8");
			}
			connection.commit();
		}

		// Version eight requires every Question table to contain the persisted
		// response_type column introduced by the v7-to-v8 migration.
		SQLException exception = assertThrows(SQLException.class, database::verifySchema);
		assertTrue(exception.getMessage().contains("questions is missing required column response_type"));
		assertEquals(8, database.schemaVersion());
	}

	@Test
	void rejectsVersion09DatabaseWithoutBookletQuestionFormat() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("booklet-missing-question-format.db"));
		database.initialiseSchema();

		// Preserve the older valid ExamBooklet structure while deliberately removing
		// only the column introduced by schema version 9.
		replaceTable(database, "exam_booklets", """
				CREATE TABLE exam_booklets (
				    id INTEGER PRIMARY KEY,
				    exam_id INTEGER NOT NULL,
				    source_document_id INTEGER NOT NULL,
				    booklet_name TEXT NOT NULL,
				    FOREIGN KEY (exam_id)
				        REFERENCES exams(id),
				    FOREIGN KEY (source_document_id)
				        REFERENCES source_documents(id),
				    UNIQUE (exam_id, booklet_name)
				)
				""");

		// A database declaring version 9 must be rejected when its version-9
		// booklet-format structure is absent.
		SQLException exception = assertThrows(SQLException.class, database::verifySchema);
		assertTrue(exception.getMessage().contains("exam_booklets is missing required column question_format"));
	}

	@Test
	void rejectsVersion10DatabaseWithoutBookletAnswerFileColumn() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("booklet-missing-answer-file.db"));
		database.initialiseSchema();

		// Preserve all booklet structure through version nine while deliberately
		// omitting only the relationship introduced by version ten.
		replaceTable(database, "exam_booklets", """
				CREATE TABLE exam_booklets (
				    id INTEGER PRIMARY KEY,
				    exam_id INTEGER NOT NULL,
				    source_document_id INTEGER NOT NULL,
				    booklet_name TEXT NOT NULL,
				    question_format TEXT NOT NULL DEFAULT 'UNSPECIFIED',
				    FOREIGN KEY (exam_id)
				        REFERENCES exams(id),
				    FOREIGN KEY (source_document_id)
				        REFERENCES source_documents(id),
				    UNIQUE (exam_id, booklet_name)
				)
				""");
		SQLException exception = assertThrows(SQLException.class, database::verifySchema);
		assertTrue(exception.getMessage().contains("exam_booklets is missing required column answer_file_id"));
	}

	@Test
	void rejectsVersion10DatabaseWithoutBookletAnswerFileForeignKey() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("booklet-answer-file-missing-foreign-key.db"));
		database.initialiseSchema();

		// Include the version-ten column but remove only its required relationship to
		// answer_files.
		replaceTable(database, "exam_booklets", """
				CREATE TABLE exam_booklets (
				    id INTEGER PRIMARY KEY,
				    exam_id INTEGER NOT NULL,
				    source_document_id INTEGER NOT NULL,
				    booklet_name TEXT NOT NULL,
				    question_format TEXT NOT NULL DEFAULT 'UNSPECIFIED',
				    answer_file_id INTEGER,
				    FOREIGN KEY (exam_id)
				        REFERENCES exams(id),
				    FOREIGN KEY (source_document_id)
				        REFERENCES source_documents(id),
				    UNIQUE (exam_id, booklet_name)
				)
				""");
		SQLException exception = assertThrows(SQLException.class, database::verifySchema);
		assertTrue(exception.getMessage()
				.contains("exam_booklets is missing exact foreign key answer_file_id -> answer_files(id)"));
	}

	@Test
	void rollsBackStructuralMigrationWhenVersionUpdateFails() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("migration-rollback.db"));
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/schema-v1.sql"));
			try (Statement statement = connection.createStatement()) {
				statement.execute("""
						CREATE TRIGGER reject_schema_version_update
						BEFORE UPDATE ON schema_version
						BEGIN
						    SELECT RAISE(ABORT, 'version update rejected');
						END
						""");
			}
			connection.commit();
		}
		assertThrows(SQLException.class, database::initialiseSchema);
		assertFalse(tableExists(database, "curriculum_mappings"));
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("SELECT version FROM schema_version")) {
			assertTrue(result.next());
			assertEquals(1, result.getInt("version"));
			assertFalse(result.next());
		}
	}

	@Test
	void rollsBackVersion02MigrationWhenVersionUpdateFails() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("version-two-rollback.db"));
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/schema-v1.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v1-to-v2.sql"));
			try (Statement statement = connection.createStatement()) {
				statement.execute("""
						CREATE TRIGGER reject_version_three
						BEFORE UPDATE ON schema_version
						WHEN OLD.version = 2
						BEGIN
						    SELECT RAISE(ABORT, 'version three rejected');
						END
						""");
			}
			connection.commit();
		}
		assertThrows(SQLException.class, database::initialiseSchema);
		assertFalse(tableExists(database, "curriculum_mapping_reviews"));
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("SELECT version FROM schema_version")) {
			assertTrue(result.next());
			assertEquals(2, result.getInt("version"));
			assertFalse(result.next());
		}
	}

	@Test
	void storesAnswerWithTextAndOrderedRegions() throws Exception {
		Path databasePath = tempDir.resolve("answer.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					INSERT INTO subjects
					    (id, subject_name)
					VALUES
					    (1, 'Chemistry')
					""");
			statement.execute("""
					INSERT INTO syllabus_versions
					    (id, subject_id, syllabus_name, is_current)
					VALUES
					    (1, 1, '2025', 1)
					""");
			statement.execute("""
					INSERT INTO curriculum_nodes
					    (id, syllabus_version_id, parent_id,
					     curriculum_code, curriculum_name,
					     curriculum_level, display_order)
					VALUES
					    (1, 1, NULL, '1', 'Unit 1', 'UNIT', 1),
					    (2, 1, 1, '1.1', 'Topic 1', 'TOPIC', 1),
					    (3, 1, 2, '1.1.1', 'Subtopic 1', 'SUBTOPIC', 1)
					""");
			statement.execute("""
					INSERT INTO exam_providers
					    (id, provider_name)
					VALUES
					    (1, 'QCAA')
					""");
			statement.execute("""
					INSERT INTO source_documents
					    (id, relative_path)
					VALUES
					    (1, 'Chemistry/2025/questions.pdf'),
					    (2, 'Chemistry/2025/answers.pdf')
					""");
			statement.execute("""
					INSERT INTO exams
					    (id, subject_id, provider_id, exam_year, exam_name)
					VALUES
					    (1, 1, 1, 2025, 'External Assessment')
					""");
			statement.execute("""
					INSERT INTO exam_booklets
					    (id, exam_id, source_document_id, booklet_name)
					VALUES
					    (1, 1, 1, 'Question booklet')
					""");
			statement.execute("""
					INSERT INTO questions
					    (id, booklet_id,
					     classification_node_id,
					     question_code,
					     question_text,
					     marks,
					     preamble_capture_required)
					VALUES
					    (1, 1, 3, 'Q1', '', 1, 0)
					""");
			statement.execute("""
					INSERT INTO question_regions
					    (question_id, region_order, booklet_id,
					     page_number, x, y, width, height)
					VALUES
					    (1, 0, 1, 1, 0.10, 0.10, 0.50, 0.20)
					""");
			statement.execute("""
					INSERT INTO answer_files
					    (id, exam_id, source_document_id, answer_file_name)
					VALUES
					    (1, 1, 2, 'Answers')
					""");
			statement.execute("""
					INSERT INTO answers
					    (id, question_id, answer_text)
					VALUES
					    (1, 1, 'B')
					""");
			statement.execute("""
					INSERT INTO answer_regions
					    (answer_id, region_order, answer_file_id,
					     page_number, x, y, width, height)
					VALUES
					    (1, 0, 1, 4, 0.10, 0.20, 0.40, 0.10),
					    (1, 1, 1, 5, 0.10, 0.15, 0.40, 0.12)
					""");
			try (ResultSet result = statement.executeQuery("""
					SELECT
					    answers.answer_text,
					    answer_regions.region_order,
					    answer_regions.page_number,
					    answer_files.answer_file_name
					FROM answers
					JOIN answer_regions
					    ON answer_regions.answer_id = answers.id
					JOIN answer_files
					    ON answer_files.id = answer_regions.answer_file_id
					WHERE answers.question_id = 1
					ORDER BY answer_regions.region_order
					""")) {
				assertTrue(result.next());
				assertEquals("B", result.getString("answer_text"));
				assertEquals(0, result.getInt("region_order"));
				assertEquals(4, result.getInt("page_number"));
				assertEquals("Answers", result.getString("answer_file_name"));
				assertTrue(result.next());
				assertEquals(1, result.getInt("region_order"));
				assertEquals(5, result.getInt("page_number"));
				assertFalse(result.next());
			}
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

	@Test
	void storesExamMetadataHierarchy() throws Exception {
		Path databasePath = tempDir.resolve("questionbank.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					INSERT INTO subjects
					    (id, subject_name)
					VALUES
					    (1, 'Chemistry')
					""");
			statement.execute("""
					INSERT INTO exam_providers
					    (id, provider_name)
					VALUES
					    (1, 'QCAA')
					""");
			statement.execute("""
					INSERT INTO source_documents
					    (id, relative_path)
					VALUES
					    (1, 'Chemistry/2019/paper1.pdf')
					""");
			statement.execute("""
					INSERT INTO exams
					    (id, subject_id, provider_id,
					     exam_year, exam_name)
					VALUES
					    (1, 1, 1,
					     2019, 'External Assessment')
					""");
			statement.execute("""
					INSERT INTO exam_booklets
					    (id, exam_id, source_document_id,
					     booklet_name)
					VALUES
					    (1, 1, 1,
					     'Paper 1')
					""");
			try (ResultSet result = statement.executeQuery("""
					SELECT
					    subjects.subject_name,
					    exam_providers.provider_name,
					    exams.exam_year,
					    exams.exam_name,
					    exam_booklets.booklet_name,
					    source_documents.relative_path
					FROM exam_booklets
					JOIN exams
					    ON exams.id =
					       exam_booklets.exam_id
					JOIN subjects
					    ON subjects.id =
					       exams.subject_id
					JOIN exam_providers
					    ON exam_providers.id =
					       exams.provider_id
					JOIN source_documents
					    ON source_documents.id =
					       exam_booklets.source_document_id
					""")) {
				assertTrue(result.next());
				assertEquals("Chemistry", result.getString("subject_name"));
				assertEquals("QCAA", result.getString("provider_name"));
				assertEquals(2019, result.getInt("exam_year"));
				assertEquals("External Assessment", result.getString("exam_name"));
				assertEquals("Paper 1", result.getString("booklet_name"));
				assertEquals("Chemistry/2019/paper1.pdf", result.getString("relative_path"));
				assertFalse(result.next());
			}
		}
	}

	@Test
	void storesQuestionWithOrderedRegions() throws Exception {
		Path databasePath = tempDir.resolve("questionbank.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					INSERT INTO subjects
					    (id, subject_name)
					VALUES
					    (1, 'Chemistry')
					""");
			statement.execute("""
					INSERT INTO syllabus_versions
					    (id, subject_id,
					     syllabus_name, is_current)
					VALUES
					    (1, 1, '2019', 0)
					""");
			statement.execute("""
					INSERT INTO curriculum_nodes
					    (id, syllabus_version_id,
					     parent_id,
					     curriculum_code,
					     curriculum_name,
					     curriculum_level,
					     display_order)
					VALUES
					    (1, 1, NULL,
					     '1',
					     'Unit 1',
					     'UNIT',
					     1),
					    (2, 1, 1,
					     '1.1',
					     'Topic 1',
					     'TOPIC',
					     1),
					    (3, 1, 2,
					     '1.1.1',
					     'Subtopic 1',
					     'SUBTOPIC',
					     1)
					""");
			statement.execute("""
					INSERT INTO exam_providers
					    (id, provider_name)
					VALUES
					    (1, 'QCAA')
					""");
			statement.execute("""
					INSERT INTO source_documents
					    (id, relative_path)
					VALUES
					    (1, 'Chemistry/2019/paper1.pdf')
					""");
			statement.execute("""
					INSERT INTO exams
					    (id, subject_id, provider_id,
					     exam_year, exam_name)
					VALUES
					    (1, 1, 1,
					     2019, 'External Assessment')
					""");
			statement.execute("""
					INSERT INTO exam_booklets
					    (id, exam_id,
					     source_document_id,
					     booklet_name)
					VALUES
					    (1, 1, 1, 'Paper 1')
					""");
			statement.execute("""
					INSERT INTO questions
					    (id, booklet_id,
					     classification_node_id,
					     question_code,
					     question_text,
					     marks,
					     preamble_capture_required)
					VALUES
					    (1, 1, 3, 'Q6', '', 3, 0)
					""");
			statement.execute("""
					INSERT INTO question_regions
					    (question_id,
					     region_order,
					     booklet_id,
					     page_number,
					     x, y, width, height)
					VALUES
					    (1, 0, 1, 4,
					     0.10, 0.20, 0.50, 0.15),
					    (1, 1, 1, 5,
					     0.10, 0.10, 0.50, 0.20)
					""");
			try (ResultSet result = statement.executeQuery("""
					SELECT
					    questions.question_code,
					    questions.classification_node_id,
					    question_regions.region_order,
					    question_regions.page_number
					FROM questions
					JOIN question_regions
					    ON question_regions.question_id =
					       questions.id
					WHERE questions.id = 1
					ORDER BY question_regions.region_order
					""")) {
				assertTrue(result.next());
				assertEquals("Q6", result.getString("question_code"));
				assertEquals(3, result.getLong("classification_node_id"));
				assertEquals(0, result.getInt("region_order"));
				assertEquals(4, result.getInt("page_number"));
				assertTrue(result.next());
				assertEquals(1, result.getInt("region_order"));
				assertEquals(5, result.getInt("page_number"));
				assertFalse(result.next());
			}
		}
	}

	private void replaceQuestionsTable(SqliteDatabase database, String createTableSql) throws Exception {
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("PRAGMA foreign_keys = OFF");
			statement.execute("DROP TABLE questions");
			statement.execute(createTableSql);
		}
	}

	private void replaceTable(SqliteDatabase database, String tableName, String createTableSql) throws Exception {
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("PRAGMA foreign_keys = OFF");
			statement.execute("DROP TABLE " + tableName);
			statement.execute(createTableSql);
		}
	}

	private boolean tableExists(SqliteDatabase database, String tableName) throws Exception {
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT 1
						FROM sqlite_master
						WHERE type = 'table'
						  AND name = ?
						""")) {
			statement.setString(1, tableName);
			try (ResultSet result = statement.executeQuery()) {
				return result.next();
			}
		}
	}
}
