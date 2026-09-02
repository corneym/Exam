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

import au.edu.eq.questionbank.model.Answer;
import au.edu.eq.questionbank.model.AnswerFile;
import au.edu.eq.questionbank.model.AnswerRegion;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class SqliteAnswerWriterTest {

	@TempDir
	Path tempDirectory;

	private int countRows(SqliteDatabase database, String tableName) throws Exception {
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
			assertTrue(result.next());
			return result.getInt(1);
		}
	}

	@Test
	void insertsAnswerAndOrderedRegions() throws Exception {
		Path databasePath = tempDirectory.resolve("questionbank.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SyllabusVersion syllabus = curriculumWriter.insertSyllabusVersion(chemistry, "2025", true);
		Unit unit = curriculumWriter.insertUnit(syllabus, "1", "Unit 1", 1);
		Topic topic = curriculumWriter.insertTopic(unit, "1.1", "Topic 1", 1);
		Subtopic subtopic = curriculumWriter.insertSubtopic(topic, "1.1.1", "Subtopic 1", 1);
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		SqliteExamImporter examImporter = new SqliteExamImporter(database, examWriter);
		ExamBooklet booklet = examImporter.importExam(chemistry, "QCAA", 2025, "External Assessment",
				"Question booklet", "Chemistry/2025/questions.pdf");
		SqliteQuestionRepository questionRepository = new SqliteQuestionRepository(database);
		Question question = questionRepository.save(booklet, "Q1", "", 1,
				List.of(new QuestionRegion(booklet, 1, 0.10, 0.10, 0.50, 0.20)), subtopic, false);
		SqliteAnswerWriter answerWriter = new SqliteAnswerWriter(database, examWriter);
		AnswerFile answerFile = answerWriter.findOrCreateAnswerFile(booklet.getExam(), "Answers",
				"Chemistry/2025/answers.pdf");
		List<AnswerRegion> regions = List.of(new AnswerRegion(answerFile, 4, 0.10, 0.20, 0.40, 0.10),
				new AnswerRegion(answerFile, 5, 0.10, 0.15, 0.40, 0.12));
		Answer answer = answerWriter.insertAnswer(question, "B", regions);
		assertTrue(answer.getId() > 0);
		assertEquals("B", answer.getAnswerText());
		assertEquals(2, answer.getRegions().size());
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT
						    answers.answer_text,
						    answer_regions.region_order,
						    answer_regions.page_number
						FROM answers
						JOIN answer_regions
						    ON answer_regions.answer_id = answers.id
						WHERE answers.id = %d
						ORDER BY answer_regions.region_order
						""".formatted(answer.getId()))) {
			assertTrue(result.next());
			assertEquals("B", result.getString("answer_text"));
			assertEquals(0, result.getInt("region_order"));
			assertEquals(4, result.getInt("page_number"));
			assertTrue(result.next());
			assertEquals(1, result.getInt("region_order"));
			assertEquals(5, result.getInt("page_number"));
			assertFalse(result.next());
		}
	}

	@Test
	void rejectsRegionsFromAnotherExamWithoutPersistingAnAnswer() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("cross-exam-answer.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SyllabusVersion syllabus = curriculumWriter.insertSyllabusVersion(chemistry, "2025", true);
		Unit unit = curriculumWriter.insertUnit(syllabus, "1", "Unit 1", 1);
		Topic topic = curriculumWriter.insertTopic(unit, "1.1", "Topic 1", 1);
		Subtopic subtopic = curriculumWriter.insertSubtopic(topic, "1.1.1", "Subtopic 1", 1);
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		SqliteExamImporter examImporter = new SqliteExamImporter(database, examWriter);
		ExamBooklet questionBooklet = examImporter.importExam(chemistry, "QCAA", 2025, "External Assessment",
				"Question booklet", "Chemistry/2025/questions.pdf");
		ExamBooklet otherBooklet = examImporter.importExam(chemistry, "QCAA", 2024, "External Assessment",
				"Question booklet", "Chemistry/2024/questions.pdf");
		Question question = new SqliteQuestionRepository(database).save(questionBooklet, "Q1", "", 1,
				List.of(new QuestionRegion(questionBooklet, 1, 0.10, 0.10, 0.50, 0.20)), subtopic, false);
		SqliteAnswerWriter answerWriter = new SqliteAnswerWriter(database, examWriter);
		Exam otherExam = otherBooklet.getExam();
		AnswerFile answerFile = answerWriter.findOrCreateAnswerFile(otherExam, "Answers", "Chemistry/2024/answers.pdf");
		AnswerRegion wrongExamRegion = new AnswerRegion(answerFile, 1, 0.10, 0.10, 0.50, 0.20);

		assertThrows(IllegalArgumentException.class,
				() -> answerWriter.insertAnswer(question, null, List.of(wrongExamRegion)));
		assertEquals(0, countRows(database, "answers"));
		assertEquals(0, countRows(database, "answer_regions"));
	}
}
