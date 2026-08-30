package au.edu.eq.questionbank.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.AnswerFile;
import au.edu.eq.questionbank.model.AnswerRegion;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;

class SqliteQuestionRepositoryTest {

	@TempDir
	Path tempDirectory;

	@Test
	void reloadsPersistedAnswer() throws Exception {
		Path databasePath = tempDirectory.resolve("answers.db");
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
		SqliteQuestionRepository repository = new SqliteQuestionRepository(database);
		Question question = repository.save(booklet.getExam(), "Q1", "",
				List.of(new QuestionRegion(booklet, 1, 0.10, 0.10, 0.50, 0.20)), subtopic);
		SqliteAnswerWriter answerWriter = new SqliteAnswerWriter(database, examWriter);
		AnswerFile answerFile = answerWriter.findOrCreateAnswerFile(booklet.getExam(), "Answers",
				"Chemistry/2025/answers.pdf");
		answerWriter.insertAnswer(question, "B", List.of(new AnswerRegion(answerFile, 4, 0.10, 0.20, 0.40, 0.10)));
		SqliteQuestionRepository secondRepository = new SqliteQuestionRepository(database);
		Question loaded = secondRepository.findById(question.getId()).orElseThrow();
		assertTrue(loaded.hasAnswer());
		assertEquals("B", loaded.getAnswer().getAnswerText());
		assertEquals(1, loaded.getAnswer().getRegions().size());
		assertEquals(4, loaded.getAnswer().getRegions().getFirst().pageNumber());
	}

	@Test
	void savesAndReloadsQuestion() throws Exception {
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
		SqliteQuestionRepository repository = new SqliteQuestionRepository(database);
		Question saved = repository.save(booklet.getExam(), "Q6", "", regions, subtopic);
		assertTrue(saved.getId() > 0);
		SqliteQuestionRepository secondRepository = new SqliteQuestionRepository(database);
		Question loaded = secondRepository.findById(saved.getId()).orElseThrow();
		assertEquals(saved.getId(), loaded.getId());
		assertEquals("Q6", loaded.getQuestionCode());
		assertEquals(2, loaded.getRegions().size());
		assertEquals(4, loaded.getRegions().get(0).pageNumber());
		assertEquals(5, loaded.getRegions().get(1).pageNumber());
		assertEquals(subtopic.getId(), loaded.getClassification().getId());
		assertEquals(1, secondRepository.findAll().size());
	}
}