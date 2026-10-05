package au.edu.eq.questionbank.repository.assessment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class SqliteQuestionWriterTest {

	@TempDir
	Path tempDirectory;

	@Test
	void completeExamAllowsQuestionCorrectionButRejectsQuestionCodeChange() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("complete-exam-question-update.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SyllabusVersion syllabus = curriculumWriter.insertSyllabusVersion(chemistry, "2019", false);
		Unit unit = curriculumWriter.insertUnit(syllabus, "1", "Unit 1", 1);
		Topic topic = curriculumWriter.insertTopic(unit, "1.1", "Topic 1", 1);
		Subtopic subtopic = curriculumWriter.insertSubtopic(topic, "1.1.1", "Subtopic 1", 1);
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		ExamBooklet booklet = new SqliteExamImporter(database, examWriter).importExam(chemistry, "QCAA", 2019,
				"External Assessment", "Paper 1", "Chemistry/2019/paper1.pdf");
		SqliteQuestionWriter writer = new SqliteQuestionWriter(database);
		QuestionRegion originalRegion = new QuestionRegion(booklet, 4, 0.10, 0.20, 0.50, 0.15);
		Question question = writer.insertQuestion(booklet, "Q6", "", 3, List.of(originalRegion), subtopic, false);
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.executeUpdate("""
					UPDATE exams
					SET capture_state = 'COMPLETE'
					""");
		}
		QuestionRegion correctedRegion = new QuestionRegion(booklet, 5, 0.10, 0.10, 0.60, 0.20);

		// Marks and captured content are ordinary corrections, so retaining the same
		// Question identity remains permitted on a COMPLETE Exam.
		writer.updateQuestion(question.getId(), booklet, "Q6", 4, List.of(correctedRegion), subtopic, null, null);
		QuestionRegion attemptedStructuralRegion = new QuestionRegion(booklet, 6, 0.10, 0.10, 0.60, 0.20);

		// Changing Q6 to Q7 changes structural Question identity and therefore requires
		// explicit Exam reactivation.
		assertThrows(IllegalStateException.class, () -> writer.updateQuestion(question.getId(), booklet, "Q7", 5,
				List.of(attemptedStructuralRegion), subtopic, null, null));
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT
						    q.question_code,
						    q.marks,
						    qr.page_number
						FROM questions q
						JOIN question_regions qr
						    ON qr.question_id = q.id
						WHERE q.id = %d
						ORDER BY qr.region_order
						""".formatted(question.getId()))) {
			assertTrue(result.next());

			// The permitted correction remains committed, while the rejected structural
			// change leaves the Question identity untouched.
			assertEquals("Q6", result.getString("question_code"));
			assertEquals(4, result.getInt("marks"));
			assertEquals(5, result.getInt("page_number"));
			assertFalse(result.next());
		}
	}

	@Test
	void completeExamRejectsNewQuestionInsertion() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("complete-exam-new-question.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SyllabusVersion syllabus = curriculumWriter.insertSyllabusVersion(chemistry, "2019", false);
		Unit unit = curriculumWriter.insertUnit(syllabus, "1", "Unit 1", 1);
		Topic topic = curriculumWriter.insertTopic(unit, "1.1", "Topic 1", 1);
		Subtopic subtopic = curriculumWriter.insertSubtopic(topic, "1.1.1", "Subtopic 1", 1);
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		ExamBooklet booklet = new SqliteExamImporter(database, examWriter).importExam(chemistry, "QCAA", 2019,
				"External Assessment", "Paper 1", "Chemistry/2019/paper1.pdf");
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.executeUpdate("""
					UPDATE exams
					SET capture_state = 'COMPLETE'
					""");
		}
		QuestionRegion region = new QuestionRegion(booklet, 1, 0.10, 0.10, 0.60, 0.20);
		SqliteQuestionWriter writer = new SqliteQuestionWriter(database);

		// Question creation changes Exam structure and therefore requires an ACTIVE
		// persisted Exam, regardless of the state held by the caller's domain object.
		assertThrows(IllegalStateException.class,
				() -> writer.insertQuestion(booklet, "Q1", "", 2, List.of(region), subtopic, false));
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM questions")) {
			assertTrue(result.next());
			assertEquals(0, result.getInt(1));
		}
	}

	@Test
	void insertsQuestionAndOrderedRegions() throws Exception {
		Path databasePath = tempDirectory.resolve("questionbank.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SyllabusVersion syllabus = curriculumWriter.insertSyllabusVersion(chemistry, "2019", false);
		Unit unit = curriculumWriter.insertUnit(syllabus, "1", "Unit 1", 1);
		Topic topic = curriculumWriter.insertTopic(unit, "1.1", "Topic 1", 1);
		Subtopic subtopic = curriculumWriter.insertSubtopic(topic, "1.1.1", "Subtopic 1", 1);
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		SqliteExamImporter examImporter = new SqliteExamImporter(database, examWriter);
		ExamBooklet booklet = examImporter.importExam(chemistry, "QCAA", 2019, "External Assessment", "Paper 1",
				"Chemistry/2019/paper1.pdf");
		List<QuestionRegion> regions = List.of(new QuestionRegion(booklet, 4, 0.10, 0.20, 0.50, 0.15),
				new QuestionRegion(booklet, 5, 0.10, 0.10, 0.50, 0.20));
		SqliteQuestionWriter writer = new SqliteQuestionWriter(database);
		Question question = writer.insertQuestion(booklet, "Q6", "", 3, regions, subtopic, false);
		assertTrue(question.getId() > 0);
		assertEquals("Q6", question.getQuestionCode());
		assertEquals(2, question.getRegions().size());
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT
						    region_order,
						    page_number
						FROM question_regions
						WHERE question_id =
						      %d
						ORDER BY region_order
						""".formatted(question.getId()))) {
			assertTrue(result.next());
			assertEquals(0, result.getInt("region_order"));
			assertEquals(4, result.getInt("page_number"));
			assertTrue(result.next());
			assertEquals(1, result.getInt("region_order"));
			assertEquals(5, result.getInt("page_number"));
			assertFalse(result.next());
		}
	}

	@Test
	void rejectsMultipleChoiceWithMoreThanOneMarkBeforePersistence() throws Exception {
		Path databasePath = tempDirectory.resolve("reject-multi-mark-mcq.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SyllabusVersion syllabus = curriculumWriter.insertSyllabusVersion(chemistry, "2019", false);
		Unit unit = curriculumWriter.insertUnit(syllabus, "1", "Unit 1", 1);
		Topic topic = curriculumWriter.insertTopic(unit, "1.1", "Topic 1", 1);
		Subtopic subtopic = curriculumWriter.insertSubtopic(topic, "1.1.1", "Subtopic 1", 1);
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		ExamBooklet booklet = new SqliteExamImporter(database, examWriter).importExam(chemistry, "QCAA", 2019,
				"External Assessment", "Paper 1", "Chemistry/2019/paper1.pdf");
		List<QuestionRegion> regions = List.of(new QuestionRegion(booklet, 1, 0.10, 0.10, 0.70, 0.20));
		SqliteQuestionWriter writer = new SqliteQuestionWriter(database);

		// A persistence caller cannot bypass the JavaFX one-mark MCQ rule.
		assertThrows(IllegalArgumentException.class, () -> writer.insertQuestion(booklet, "Q1", "", 2, regions,
				subtopic, false, null, null, QuestionResponseType.MULTIPLE_CHOICE));
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM questions")) {

			// Rejection must occur without leaving a partial Question row behind.
			assertTrue(result.next());
			assertEquals(0, result.getInt(1));
		}
	}

	@Test
	void rollsBackQuestionWhoseClassificationBelongsToAnotherSubject() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("wrong-subject.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		Subject physics = curriculumWriter.insertSubject("Physics");
		SyllabusVersion physicsSyllabus = curriculumWriter.insertSyllabusVersion(physics, "2025", true);
		Unit unit = curriculumWriter.insertUnit(physicsSyllabus, "1", "Unit 1", 1);
		Topic topic = curriculumWriter.insertTopic(unit, "1.1", "Topic 1", 1);
		Subtopic physicsSubtopic = curriculumWriter.insertSubtopic(topic, "1.1.1", "Subtopic 1", 1);
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		ExamBooklet booklet = new SqliteExamImporter(database, examWriter).importExam(chemistry, "QCAA", 2025,
				"External Assessment", "Question booklet", "Chemistry/2025/questions.pdf");
		QuestionRegion region = new QuestionRegion(booklet, 1, 0.10, 0.10, 0.50, 0.20);
		assertThrows(IllegalArgumentException.class, () -> new SqliteQuestionWriter(database).insertQuestion(booklet,
				"Q1", "", 1, List.of(region), physicsSubtopic, false));
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet questions = statement.executeQuery("SELECT COUNT(*) FROM questions")) {
			assertTrue(questions.next());
			assertEquals(0, questions.getInt(1));
		}
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet regions = statement.executeQuery("SELECT COUNT(*) FROM question_regions")) {
			assertTrue(regions.next());
			assertEquals(0, regions.getInt(1));
		}
	}
}
