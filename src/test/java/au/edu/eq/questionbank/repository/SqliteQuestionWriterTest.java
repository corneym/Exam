package au.edu.eq.questionbank.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;

class SqliteQuestionWriterTest {

	@TempDir
	Path tempDirectory;

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
		Question question = writer.insertQuestion(booklet.getExam(), "Q6", "", regions, subtopic);
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
}