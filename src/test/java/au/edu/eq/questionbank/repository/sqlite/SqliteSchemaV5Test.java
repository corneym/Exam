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

import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.SharedContextStatus;
import au.edu.eq.questionbank.repository.assessment.SqliteQuestionRepository;

class SqliteSchemaV5Test {

	@TempDir
	Path tempDir;

	@Test
	void enforcesVersionFiveRelationshipsAndNaturalKeys() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("version-five.db"));
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("INSERT INTO subjects (id, subject_name) VALUES (1, 'Chemistry')");
			statement.execute("""
					INSERT INTO syllabus_versions
					    (id, subject_id, syllabus_name, is_current)
					VALUES
					    (1, 1, 'Chemistry 2025', 1)
					""");
			statement.execute("""
					INSERT INTO curriculum_nodes
					    (id, syllabus_version_id, parent_id,
					     curriculum_code, curriculum_name,
					     curriculum_level, display_order)
					VALUES
					    (10, 1, NULL, '1', 'Unit 1', 'UNIT', 0),
					    (11, 1, 10, '1.1', 'Topic 1', 'TOPIC', 0),
					    (12, 1, 11, '1.1.1', 'Descriptor 1', 'DESCRIPTOR', 0)
					""");
			statement.execute("INSERT INTO exam_providers (id, provider_name) VALUES (1, 'QCAA')");
			statement.execute("""
					INSERT INTO source_documents (id, relative_path)
					VALUES (1, 'Chemistry/paper-1.pdf')
					""");
			statement.execute("""
					INSERT INTO exams
					    (id, subject_id, provider_id, exam_year, exam_name)
					VALUES
					    (1, 1, 1, 2025, 'External assessment')
					""");
			statement.execute("""
					INSERT INTO exam_booklets
					    (id, exam_id, source_document_id, booklet_name)
					VALUES
					    (1, 1, 1, 'Paper 1')
					""");
			statement.execute("""
					INSERT INTO source_questions
					    (id, booklet_id, source_question_code)
					VALUES
					    (20, 1, '21')
					""");
			assertThrows(SQLException.class, () -> statement.execute("""
					INSERT INTO source_questions
					    (id, booklet_id, source_question_code)
					VALUES
					    (21, 1, '21')
					"""));
			statement.execute("""
					INSERT INTO shared_question_contexts
					    (id, booklet_id, context_label)
					VALUES
					    (30, 1, 'Question 21 shared context')
					""");
			statement.execute("""
					INSERT INTO shared_question_context_regions
					    (shared_context_id, region_order,
					     page_number, x, y, width, height)
					VALUES
					    (30, 0, 4, 0.1, 0.2, 0.8, 0.3)
					""");
			statement.execute("""
					INSERT INTO questions
					    (id, booklet_id, classification_node_id,
					     question_code, question_text, marks,
					     preamble_capture_required,
					     source_question_id, shared_context_id)
					VALUES
					    (100, 1, 12, '21a', '', 2, 1, 20, 30)
					""");
			assertThrows(SQLException.class, () -> statement.execute("""
					INSERT INTO questions
					    (id, booklet_id, classification_node_id,
					     question_code, question_text, marks,
					     preamble_capture_required,
					     source_question_id)
					VALUES
					    (101, 1, 12, '22a', '', 2, 0, 999)
					"""));
			assertThrows(SQLException.class, () -> statement.execute("""
					INSERT INTO shared_question_context_regions
					    (shared_context_id, region_order,
					     page_number, x, y, width, height)
					VALUES
					    (999, 0, 4, 0.1, 0.2, 0.8, 0.3)
					"""));
		}
	}

	@Test
	void migratesPopulatedVersion05QuestionBankToLatestSchemaWithoutDataLoss() throws Exception {
		Path databasePath = tempDir.resolve("populated-version-five-to-six.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		createVersion05Schema(database);
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			connection.setAutoCommit(false);
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
					    (1, 1, 'Chemistry 2025', 1)
					""");
			statement.execute("""
					INSERT INTO curriculum_nodes
					    (id, syllabus_version_id, parent_id,
					     curriculum_code, curriculum_name,
					     curriculum_level, display_order)
					VALUES
					    (10, 1, NULL, '1', 'Unit 1', 'UNIT', 0),
					    (11, 1, 10, '1.1', 'Topic 1', 'TOPIC', 0),
					    (12, 1, 11, '1.1.1', 'Descriptor 1', 'DESCRIPTOR', 0)
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
					    (1, 'Chemistry/2025/paper-1.pdf'),
					    (2, 'Chemistry/2025/marking-guide.pdf')
					""");
			statement.execute("""
					INSERT INTO exams
					    (id, subject_id, provider_id, exam_year, exam_name)
					VALUES
					    (1, 1, 1, 2025, 'External assessment')
					""");
			statement.execute("""
					INSERT INTO exam_booklets
					    (id, exam_id, source_document_id, booklet_name)
					VALUES
					    (1, 1, 1, 'Paper 1')
					""");
			statement.execute("""
					INSERT INTO answer_files
					    (id, exam_id, source_document_id, answer_file_name)
					VALUES
					    (40, 1, 2, 'Marking guide')
					""");
			statement.execute("""
					INSERT INTO source_questions
					    (id, booklet_id, source_question_code)
					VALUES
					    (20, 1, '24')
					""");
			statement.execute("""
					INSERT INTO shared_question_contexts
					    (id, booklet_id, context_label)
					VALUES
					    (30, 1, 'Question 24 shared context')
					""");

			// Insert these deliberately out of physical insertion order. Repository
			// reconstruction must continue to honour region_order after migration.
			statement.execute("""
					INSERT INTO shared_question_context_regions
					    (shared_context_id, region_order,
					     page_number, x, y, width, height)
					VALUES
					    (30, 1, 3, 0.15, 0.20, 0.70, 0.20),
					    (30, 0, 2, 0.10, 0.10, 0.80, 0.15)
					""");
			statement.execute("""
					INSERT INTO questions
					    (id, booklet_id, classification_node_id,
					     question_code, question_text, marks,
					     preamble_capture_required,
					     source_question_id, shared_context_id)
					VALUES
					    (100, 1, 12,
					     '24a', 'Stored question text', 3,
					     1, 20, 30)
					""");
			statement.execute("""
					INSERT INTO question_regions
					    (question_id, region_order, booklet_id,
					     page_number, x, y, width, height)
					VALUES
					    (100, 1, 1, 5, 0.20, 0.40, 0.60, 0.20),
					    (100, 0, 1, 4, 0.10, 0.20, 0.70, 0.15)
					""");
			statement.execute("""
					INSERT INTO answers
					    (id, question_id, answer_text)
					VALUES
					    (50, 100, 'B')
					""");
			statement.execute("""
					INSERT INTO answer_regions
					    (answer_id, region_order, answer_file_id,
					     page_number, x, y, width, height)
					VALUES
					    (50, 1, 40, 7, 0.20, 0.30, 0.60, 0.20),
					    (50, 0, 40, 6, 0.10, 0.15, 0.70, 0.15)
					""");
			connection.commit();
		}
		assertEquals(5, database.schemaVersion());
		database.initialiseSchema();

		// initialiseSchema() migrates the database all the way to the application's
		// current supported schema.
		assertEquals(SqliteDatabase.LATEST_SCHEMA_VERSION, database.schemaVersion());

		// Use a fresh database object after migration to simulate reopening the
		// application against the upgraded database.
		SqliteDatabase reopenedDatabase = new SqliteDatabase(databasePath);
		reopenedDatabase.initialiseSchema();
		assertEquals(SqliteDatabase.LATEST_SCHEMA_VERSION, reopenedDatabase.schemaVersion());
		Question reloaded = new SqliteQuestionRepository(reopenedDatabase).findById(100).orElseThrow();
		assertEquals("24a", reloaded.getQuestionCode());
		assertEquals("Stored question text", reloaded.getQuestionText());
		assertEquals(3, reloaded.getMarks());
		assertEquals("1.1.1", reloaded.getClassification().getCode());
		assertEquals(2, reloaded.getRegions().size());
		assertEquals(4, reloaded.getRegions().get(0).pageNumber());
		assertEquals(5, reloaded.getRegions().get(1).pageNumber());
		assertTrue(reloaded.hasSourceQuestion());
		assertEquals(20, reloaded.getSourceQuestion().getId());
		assertEquals("24", reloaded.getSourceQuestion().getSourceQuestionCode());
		assertEquals(SharedContextStatus.UNKNOWN, reloaded.getSourceQuestion().getSharedContextStatus());
		assertTrue(reloaded.hasSharedContext());
		assertEquals(30, reloaded.getSharedContext().getId());
		assertEquals("Question 24 shared context", reloaded.getSharedContext().getLabel());
		assertEquals(2, reloaded.getSharedContext().getRegions().size());
		assertEquals(2, reloaded.getSharedContext().getRegions().get(0).pageNumber());
		assertEquals(3, reloaded.getSharedContext().getRegions().get(1).pageNumber());
		assertTrue(reloaded.hasAnswer());
		assertEquals(50, reloaded.getAnswer().getId());
		assertEquals("B", reloaded.getAnswer().getAnswerText());
		assertEquals(2, reloaded.getAnswer().getRegions().size());
		assertEquals(6, reloaded.getAnswer().getRegions().get(0).pageNumber());
		assertEquals(7, reloaded.getAnswer().getRegions().get(1).pageNumber());
		assertEquals(40, reloaded.getAnswer().getRegions().get(0).answerFile().getId());
		assertEquals("Marking guide", reloaded.getAnswer().getRegions().get(0).answerFile().getName());
		assertEquals("Chemistry/2025/marking-guide.pdf",
				reloaded.getAnswer().getRegions().get(0).answerFile().getSourceDocument().getRelativePath());
	}

	@Test
	void migratesPopulatedVersion11DatabaseToVersion12WithoutChangingQuestionApplicability() throws Exception {
		Path databasePath = tempDir.resolve("version-eleven-to-latest.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		createVersion11Schema(database);
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
					    (1, 1, 'Chemistry 2025', 1)
					""");
			statement.execute("""
					INSERT INTO curriculum_nodes
					    (id, syllabus_version_id, parent_id,
					     curriculum_code, curriculum_name,
					     curriculum_level, display_order)
					VALUES
					    (10, 1, NULL,
					     '1', 'Unit 1',
					     'UNIT', 1),
					    (11, 1, 10,
					     '1.1', 'Topic 1',
					     'TOPIC', 1),
					    (12, 1, 11,
					     '1.1.1', 'Descriptor 1',
					     'DESCRIPTOR', 1)
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
					    (1, 'Chemistry/2025/paper1.pdf')
					""");
			statement.execute("""
					INSERT INTO exams
					    (id, subject_id, provider_id,
					     exam_year, exam_name)
					VALUES
					    (1, 1, 1,
					     2025, 'External Assessment')
					""");
			statement.execute("""
					INSERT INTO exam_booklets
					    (id, exam_id, source_document_id,
					     booklet_name)
					VALUES
					    (1, 1, 1,
					     'Paper 1')
					""");
			statement.execute("""
					INSERT INTO questions
					    (id, booklet_id, classification_node_id,
					     question_code, question_text,
					     marks, preamble_capture_required,
					     response_type)
					VALUES
					    (100, 1, 12,
					     'Q7', 'Stored question text',
					     3, 0,
					     'WRITTEN_RESPONSE')
					""");
		}
		assertEquals(11, database.schemaVersion());

		// Exercise the production upgrade path from version 11 through every later
		// migration, including applicability exclusions and the terminology rename.
		database.initialiseSchema();
		assertEquals(12, database.schemaVersion());
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			try (ResultSet result = statement.executeQuery("""
					SELECT question_code,
					       question_text,
					       marks,
					       classification_node_id,
					       response_type
					FROM questions
					WHERE id = 100
					""")) {
				assertTrue(result.next());

				// Migration must not rewrite any existing Question metadata or
				// classification while introducing the new exclusion mechanism.
				assertEquals("Q7", result.getString("question_code"));
				assertEquals("Stored question text", result.getString("question_text"));
				assertEquals(3, result.getInt("marks"));
				assertEquals(12, result.getLong("classification_node_id"));
				assertEquals("WRITTEN_RESPONSE", result.getString("response_type"));
				assertFalse(result.next());
			}
			try (ResultSet result = statement.executeQuery("""
					SELECT COUNT(*)
					FROM question_output_exclusions
					""")) {
				assertTrue(result.next());

				// No exclusion data can be inferred from a v11 database. Every existing
				// Question therefore retains all of its derived applicability.
				assertEquals(0, result.getInt(1));
			}
		}

		// Reopening verifies that the migrated structure passes normal startup schema
		// validation rather than merely surviving the migration transaction itself.
		SqliteDatabase reopened = new SqliteDatabase(databasePath);
		reopened.initialiseSchema();
		assertEquals(12, reopened.schemaVersion());
		Question reloaded = new SqliteQuestionRepository(reopened).findById(100).orElseThrow();
		assertEquals("Q7", reloaded.getQuestionCode());
		assertEquals(12, reloaded.getClassification().getId());
		assertEquals(3, reloaded.getMarks());
	}

	@Test
	void migratesVersion04QuestionsWithoutInferringRelationships() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("version-four.db"));
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/schema-v01.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v01-to-v02.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v02-to-v03.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v03-to-v04.sql"));
			try (Statement statement = connection.createStatement()) {
				statement.execute("INSERT INTO subjects (id, subject_name) VALUES (1, 'Chemistry')");
				statement.execute("""
						INSERT INTO syllabus_versions
						    (id, subject_id, syllabus_name, is_current)
						VALUES
						    (1, 1, 'Chemistry 2025', 1)
						""");
				statement.execute("""
						INSERT INTO curriculum_nodes
						    (id, syllabus_version_id, parent_id,
						     curriculum_code, curriculum_name,
						     curriculum_level, display_order)
						VALUES
						    (10, 1, NULL, '1', 'Unit 1', 'UNIT', 0),
						    (11, 1, 10, '1.1', 'Topic 1', 'TOPIC', 0),
						    (12, 1, 11, '1.1.1', 'Subtopic 1', 'SUBTOPIC', 0)
						""");
				statement.execute("INSERT INTO exam_providers (id, provider_name) VALUES (1, 'QCAA')");
				statement.execute("""
						INSERT INTO source_documents (id, relative_path)
						VALUES (1, 'Chemistry/paper-1.pdf')
						""");
				statement.execute("""
						INSERT INTO exams
						    (id, subject_id, provider_id, exam_year, exam_name)
						VALUES
						    (1, 1, 1, 2025, 'External assessment')
						""");
				statement.execute("""
						INSERT INTO exam_booklets
						    (id, exam_id, source_document_id, booklet_name)
						VALUES
						    (1, 1, 1, 'Paper 1')
						""");
				statement.execute("""
						INSERT INTO questions
						    (id, booklet_id, classification_node_id,
						     question_code, question_text, marks,
						     preamble_capture_required)
						VALUES
						    (100, 1, 12, '21a', '', 2, 1)
						""");
				statement.execute("""
						INSERT INTO question_regions
						    (question_id, region_order, booklet_id,
						     page_number, x, y, width, height)
						VALUES
						    (100, 0, 1, 5, 0.1, 0.2, 0.8, 0.3)
						""");
			}
			connection.commit();
		}
		assertEquals(4, database.schemaVersion());
		database.initialiseSchema();
		assertEquals(SqliteDatabase.LATEST_SCHEMA_VERSION, database.schemaVersion());
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			try (ResultSet result = statement.executeQuery("""
					SELECT question_code, marks, preamble_capture_required,
					       source_question_id, shared_context_id
					FROM questions
					WHERE id = 100
					""")) {
				assertTrue(result.next());
				assertEquals("21a", result.getString("question_code"));
				assertEquals(2, result.getInt("marks"));
				assertEquals(1, result.getInt("preamble_capture_required"));
				assertEquals(null, result.getObject("source_question_id"));
				assertEquals(null, result.getObject("shared_context_id"));
				assertFalse(result.next());
			}
			try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM source_questions")) {
				assertTrue(result.next());
				assertEquals(0, result.getInt(1));
			}
			try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM shared_question_contexts")) {
				assertTrue(result.next());
				assertEquals(0, result.getInt(1));
			}
		}
	}

	@Test
	void rejectsVersion06SchemaWithNullableSharedContextStatusColumn() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("version-six-nullable-status.db"));
		createVersion05Schema(database);
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("ALTER TABLE source_questions ADD COLUMN preamble_status TEXT DEFAULT 'UNKNOWN'");
			statement.execute("UPDATE schema_version SET version = 6");
		}
		SQLException failure = assertThrows(SQLException.class, database::initialiseSchema);
		assertTrue(failure.getMessage().contains("column must be NOT NULL: preamble_status"));
		assertEquals(6, database.schemaVersion());
	}

	@Test
	void rejectsVersion06SchemaWithoutSharedContextStatusColumn() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("version-six-missing-status.db"));
		createVersion05Schema(database);
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("UPDATE schema_version SET version = 6");
		}
		SQLException failure = assertThrows(SQLException.class, database::initialiseSchema);
		assertTrue(failure.getMessage().contains("missing required column preamble_status"));
		assertEquals(6, database.schemaVersion());
	}

	@Test
	void version06SharedContextStatusDefaultsAndConstraintAreEnforced() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("version-six-status-constraint.db"));
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("INSERT INTO subjects (id, subject_name) VALUES (1, 'Chemistry')");
			statement.execute("INSERT INTO exam_providers (id, provider_name) VALUES (1, 'QCAA')");
			statement.execute("INSERT INTO source_documents (id, relative_path) VALUES (1, 'paper.pdf')");
			statement.execute("""
					INSERT INTO exams (id, subject_id, provider_id, exam_year, exam_name)
					VALUES (1, 1, 1, 2025, 'External assessment')
					""");
			statement.execute("""
					INSERT INTO exam_booklets (id, exam_id, source_document_id, booklet_name)
					VALUES (1, 1, 1, 'Paper 1')
					""");
			statement.execute("""
					INSERT INTO source_questions (id, booklet_id, source_question_code)
					VALUES (1, 1, '21')
					""");
			try (ResultSet result = statement
					.executeQuery("SELECT preamble_status FROM source_questions WHERE id = 1")) {
				assertTrue(result.next());
				assertEquals("UNKNOWN", result.getString(1));
			}
			assertThrows(SQLException.class, () -> statement.execute("""
					INSERT INTO source_questions
					    (id, booklet_id, source_question_code, preamble_status)
					VALUES (2, 1, '22', 'INVALID')
					"""));
			assertThrows(SQLException.class, () -> statement.execute("""
					INSERT INTO source_questions
					    (id, booklet_id, source_question_code, preamble_status)
					VALUES (3, 1, '23', NULL)
					"""));
		}
	}

	private void createVersion05Schema(SqliteDatabase database) throws Exception {
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/schema-v01.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v01-to-v02.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v02-to-v03.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v03-to-v04.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v04-to-v05.sql"));
			connection.commit();
		}
	}

	private void createVersion11Schema(SqliteDatabase database) throws Exception {
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);

			// Build exactly the schema that existed immediately before version 12.
			// This prevents the migration test from accidentally starting with any
			// structures introduced by the code under test.
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/schema-v01.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v01-to-v02.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v02-to-v03.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v03-to-v04.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v04-to-v05.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v05-to-v06.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v06-to-v07.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v07-to-v08.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v08-to-v09.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v09-to-v10.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v10-to-v11.sql"));
			connection.commit();
		}

		// Guard the fixture itself. If this fails, the test is no longer exercising
		// the intended v11 -> v12 production migration boundary.
		assertEquals(11, database.schemaVersion());
	}
}
