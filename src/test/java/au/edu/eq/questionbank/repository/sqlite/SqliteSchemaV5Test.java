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

import au.edu.eq.questionbank.model.PreambleStatus;
import au.edu.eq.questionbank.model.Question;
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
					    (30, 1, 'Question 21 preamble')
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
	void migratesPopulatedVersionFiveQuestionBankToLatestSchemaWithoutDataLoss() throws Exception {
		Path databasePath = tempDir.resolve("populated-version-five-to-six.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		createVersionFiveSchema(database);
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
					    (30, 1, 'Question 24 preamble')
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
		assertEquals(8, database.schemaVersion());

		// Use a fresh database object after migration to simulate reopening the
		// application against the upgraded database.
		SqliteDatabase reopenedDatabase = new SqliteDatabase(databasePath);
		reopenedDatabase.initialiseSchema();
		assertEquals(8, reopenedDatabase.schemaVersion());
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
		assertEquals(PreambleStatus.UNKNOWN, reloaded.getSourceQuestion().getPreambleStatus());
		assertTrue(reloaded.hasSharedContext());
		assertEquals(30, reloaded.getSharedContext().getId());
		assertEquals("Question 24 preamble", reloaded.getSharedContext().getLabel());
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
	void migratesVersionFourQuestionsWithoutInferringRelationships() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("version-four.db"));
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/schema-v1.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v1-to-v2.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v2-to-v3.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v3-to-v4.sql"));
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
		assertEquals(8, database.schemaVersion());
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
	void rejectsVersionSixSchemaWithNullablePreambleStatusColumn() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("version-six-nullable-status.db"));
		createVersionFiveSchema(database);
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("ALTER TABLE source_questions ADD COLUMN preamble_status TEXT DEFAULT 'UNKNOWN'");
			statement.execute("UPDATE schema_version SET version = 6");
		}
		SQLException failure = assertThrows(SQLException.class, database::initialiseSchema);
		assertTrue(failure.getMessage().contains("column must be NOT NULL: preamble_status"));
		assertEquals(6, database.schemaVersion());
	}

	@Test
	void rejectsVersionSixSchemaWithoutPreambleStatusColumn() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("version-six-missing-status.db"));
		createVersionFiveSchema(database);
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("UPDATE schema_version SET version = 6");
		}
		SQLException failure = assertThrows(SQLException.class, database::initialiseSchema);
		assertTrue(failure.getMessage().contains("missing required column preamble_status"));
		assertEquals(6, database.schemaVersion());
	}

	@Test
	void versionSixPreambleStatusDefaultsAndConstraintAreEnforced() throws Exception {
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

	private void createVersionFiveSchema(SqliteDatabase database) throws Exception {
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/schema-v1.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v1-to-v2.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v2-to-v3.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v3-to-v4.sql"));
			SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v4-to-v5.sql"));
			connection.commit();
		}
	}
}
