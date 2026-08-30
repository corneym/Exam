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
					    (id, exam_id, classification_node_id,
					     question_code, question_text)
					VALUES
					    (1, 1, 3, 'Q1', '')
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
					    (id, exam_id,
					     classification_node_id,
					     question_code,
					     question_text)
					VALUES
					    (1, 1, 3, 'Q6', '')
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
}
