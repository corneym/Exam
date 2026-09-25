package au.edu.eq.questionbank.repository.assessment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

	@Test
	void assignsSharedAndSeparateAnswerFilesToBooklets() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("booklet-answer-files.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		SqliteExamImporter examImporter = new SqliteExamImporter(database, examWriter);
		ExamBooklet mcq = examImporter.importExam(chemistry, "QCAA", 2025, "External Assessment", "MCQ booklet",
				"Chemistry/2025/mcq.pdf");
		ExamBooklet paper1 = examImporter.importExam(chemistry, "QCAA", 2025, "External Assessment", "Paper 1",
				"Chemistry/2025/paper1.pdf");
		ExamBooklet paper2 = examImporter.importExam(chemistry, "QCAA", 2025, "External Assessment", "Paper 2",
				"Chemistry/2025/paper2.pdf");
		SqliteAnswerWriter answerWriter = new SqliteAnswerWriter(database, examWriter);
		AnswerFile answersA = answerWriter.findOrCreateAnswerFile(mcq.getExam(), "Answers A",
				"Chemistry/2025/answers-a.pdf");
		AnswerFile answersB = answerWriter.findOrCreateAnswerFile(mcq.getExam(), "Answers B",
				"Chemistry/2025/answers-b.pdf");
		assertNull(answerWriter.findAnswerFile(mcq));
		assertNull(answerWriter.findAnswerFile(paper1));
		assertNull(answerWriter.findAnswerFile(paper2));

		// One answer PDF may serve several question booklets.
		answerWriter.assignAnswerFile(mcq, answersA);
		answerWriter.assignAnswerFile(paper1, answersA);

		// Another booklet from the same Exam may use a different answer PDF.
		answerWriter.assignAnswerFile(paper2, answersB);
		assertEquals(answersA.getId(), answerWriter.findAnswerFile(mcq).getId());
		assertEquals(answersA.getId(), answerWriter.findAnswerFile(paper1).getId());
		assertEquals(answersB.getId(), answerWriter.findAnswerFile(paper2).getId());
	}

	@Test
	void findOrCreateAnswerFileForBookletsSharesOneFileAcrossBooklets() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("shared-booklet-answer-file.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		SqliteExamImporter examImporter = new SqliteExamImporter(database, examWriter);
		ExamBooklet mcqBooklet = examImporter.importExam(chemistry, "QCAA", 2025, "External Assessment", "MCQ booklet",
				"Chemistry/2025/mcq.pdf");
		ExamBooklet paper1 = examImporter.importExam(chemistry, "QCAA", 2025, "External Assessment", "Paper 1",
				"Chemistry/2025/paper1.pdf");
		SqliteAnswerWriter answerWriter = new SqliteAnswerWriter(database, examWriter);

		// Register the answer document through the first booklet. The booklet-aware
		// operation must both create the AnswerFile and persist the booklet mapping.
		AnswerFile mcqAnswers = answerWriter.findOrCreateAnswerFile(mcqBooklet, "Answers A",
				"Chemistry/2025/answers-a.pdf");

		// Paper 1 uses the same physical answer document. The existing AnswerFile must
		// be reused rather than duplicated, while Paper 1 receives its own mapping.
		AnswerFile paper1Answers = answerWriter.findOrCreateAnswerFile(paper1, "Answers A",
				"Chemistry/2025/answers-a.pdf");
		assertEquals(mcqAnswers.getId(), paper1Answers.getId());
		assertEquals(mcqAnswers.getId(), answerWriter.findAnswerFile(mcqBooklet).getId());
		assertEquals(mcqAnswers.getId(), answerWriter.findAnswerFile(paper1).getId());
	}

	@Test
	void findsRegisteredAnswerFilesForExam() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("answer-files.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		SqliteExamImporter examImporter = new SqliteExamImporter(database, examWriter);
		ExamBooklet booklet = examImporter.importExam(chemistry, "QCAA", 2025, "External Assessment", "Paper 1",
				"Chemistry/QCAA/2025/paper1.pdf");
		SqliteAnswerWriter answerWriter = new SqliteAnswerWriter(database, examWriter);
		answerWriter.findOrCreateAnswerFile(booklet.getExam(), "Marking scheme",
				"Chemistry/QCAA/2025/marking-scheme.pdf");
		List<AnswerFile> answerFiles = answerWriter.findAnswerFiles(booklet.getExam());
		assertEquals(1, answerFiles.size());
		assertEquals("Marking scheme", answerFiles.get(0).getName());
		assertEquals("Chemistry/QCAA/2025/marking-scheme.pdf",
				answerFiles.get(0).getSourceDocument().getRelativePath());
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

		// Existing capture code still registers an AnswerFile at Exam level. The first
		// unambiguous region capture upgrades that information to the new booklet-level
		// relationship without requiring UI changes in this persistence slice.
		AnswerFile assignedAnswerFile = answerWriter.findAnswerFile(booklet);
		assertEquals(answerFile.getId(), assignedAnswerFile.getId());
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
	void rejectsAnswerFileAssignmentFromAnotherExam() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("cross-exam-booklet-answer-file.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		SqliteExamImporter examImporter = new SqliteExamImporter(database, examWriter);
		ExamBooklet booklet2025 = examImporter.importExam(chemistry, "QCAA", 2025, "External Assessment", "Paper 1",
				"Chemistry/2025/paper1.pdf");
		ExamBooklet booklet2024 = examImporter.importExam(chemistry, "QCAA", 2024, "External Assessment", "Paper 1",
				"Chemistry/2024/paper1.pdf");
		SqliteAnswerWriter answerWriter = new SqliteAnswerWriter(database, examWriter);
		AnswerFile answers2024 = answerWriter.findOrCreateAnswerFile(booklet2024.getExam(), "Answers",
				"Chemistry/2024/answers.pdf");

		// A booklet can only point at an AnswerFile owned by the same Exam.
		assertThrows(IllegalArgumentException.class, () -> answerWriter.assignAnswerFile(booklet2025, answers2024));
		assertNull(answerWriter.findAnswerFile(booklet2025));
	}

	@Test
	void rejectsAnswerRegionsFromDifferentFileThanBookletAssignment() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("wrong-booklet-answer-file.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SyllabusVersion syllabus = curriculumWriter.insertSyllabusVersion(chemistry, "2025", true);
		Unit unit = curriculumWriter.insertUnit(syllabus, "1", "Unit 1", 1);
		Topic topic = curriculumWriter.insertTopic(unit, "1.1", "Topic 1", 1);
		Subtopic subtopic = curriculumWriter.insertSubtopic(topic, "1.1.1", "Subtopic 1", 1);
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		ExamBooklet booklet = new SqliteExamImporter(database, examWriter).importExam(chemistry, "QCAA", 2025,
				"External Assessment", "Paper 1", "Chemistry/2025/paper1.pdf");
		Question question = new SqliteQuestionRepository(database).save(booklet, "Q1", "", 2,
				List.of(new QuestionRegion(booklet, 1, 0.10, 0.10, 0.50, 0.20)), subtopic, false);
		SqliteAnswerWriter answerWriter = new SqliteAnswerWriter(database, examWriter);
		AnswerFile assignedFile = answerWriter.findOrCreateAnswerFile(booklet.getExam(), "Answers A",
				"Chemistry/2025/answers-a.pdf");
		AnswerFile otherFile = answerWriter.findOrCreateAnswerFile(booklet.getExam(), "Answers B",
				"Chemistry/2025/answers-b.pdf");
		answerWriter.assignAnswerFile(booklet, assignedFile);
		AnswerRegion wrongRegion = new AnswerRegion(otherFile, 1, 0.10, 0.10, 0.50, 0.20);

		// The file belongs to the correct Exam, but not to this booklet.
		assertThrows(IllegalArgumentException.class,
				() -> answerWriter.insertAnswer(question, null, List.of(wrongRegion)));
		assertEquals(0, countRows(database, "answers"));
		assertEquals(0, countRows(database, "answer_regions"));
	}

	@Test
	void rejectsOneAnswerUsingRegionsFromTwoAnswerFiles() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("split-answer-files.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SyllabusVersion syllabus = curriculumWriter.insertSyllabusVersion(chemistry, "2025", true);
		Unit unit = curriculumWriter.insertUnit(syllabus, "1", "Unit 1", 1);
		Topic topic = curriculumWriter.insertTopic(unit, "1.1", "Topic 1", 1);
		Subtopic subtopic = curriculumWriter.insertSubtopic(topic, "1.1.1", "Subtopic 1", 1);
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		ExamBooklet booklet = new SqliteExamImporter(database, examWriter).importExam(chemistry, "QCAA", 2025,
				"External Assessment", "Paper 1", "Chemistry/2025/paper1.pdf");
		Question question = new SqliteQuestionRepository(database).save(booklet, "Q1", "", 2,
				List.of(new QuestionRegion(booklet, 1, 0.10, 0.10, 0.50, 0.20)), subtopic, false);
		SqliteAnswerWriter answerWriter = new SqliteAnswerWriter(database, examWriter);
		AnswerFile answersA = answerWriter.findOrCreateAnswerFile(booklet.getExam(), "Answers A",
				"Chemistry/2025/answers-a.pdf");
		AnswerFile answersB = answerWriter.findOrCreateAnswerFile(booklet.getExam(), "Answers B",
				"Chemistry/2025/answers-b.pdf");
		List<AnswerRegion> regions = List.of(new AnswerRegion(answersA, 1, 0.10, 0.10, 0.50, 0.20),
				new AnswerRegion(answersB, 2, 0.10, 0.10, 0.50, 0.20));

		// A Question's Answer always comes from one answer document.
		assertThrows(IllegalArgumentException.class, () -> answerWriter.insertAnswer(question, null, regions));
		assertEquals(0, countRows(database, "answers"));
		assertEquals(0, countRows(database, "answer_regions"));
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

	@Test
	void updatesAnswerWithoutChangingIdentity() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("update-answer.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SyllabusVersion syllabus = curriculumWriter.insertSyllabusVersion(chemistry, "2025", true);
		Unit unit = curriculumWriter.insertUnit(syllabus, "1", "Unit 1", 1);
		Topic topic = curriculumWriter.insertTopic(unit, "1.1", "Topic 1", 1);
		Subtopic subtopic = curriculumWriter.insertSubtopic(topic, "1.1.1", "Subtopic 1", 1);
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		ExamBooklet booklet = new SqliteExamImporter(database, examWriter).importExam(chemistry, "QCAA", 2025,
				"External Assessment", "Paper 1", "Chemistry/2025/questions.pdf");
		SqliteQuestionRepository questionRepository = new SqliteQuestionRepository(database);
		Question question = questionRepository.save(booklet, "Q1", "", 2,
				List.of(new QuestionRegion(booklet, 1, 0.10, 0.10, 0.50, 0.20)), subtopic, false);
		SqliteAnswerWriter answerWriter = new SqliteAnswerWriter(database, examWriter);
		AnswerFile answerFile = answerWriter.findOrCreateAnswerFile(booklet.getExam(), "Answers",
				"Chemistry/2025/answers.pdf");
		Answer original = answerWriter.insertAnswer(question, "B",
				List.of(new AnswerRegion(answerFile, 4, 0.10, 0.20, 0.40, 0.10)));
		Answer updated = answerWriter.updateAnswer(question, original.getId(), "C",
				List.of(new AnswerRegion(answerFile, 7, 0.15, 0.25, 0.45, 0.12)));
		assertEquals(original.getId(), updated.getId());
		assertEquals("C", updated.getAnswerText());
		assertEquals(1, updated.getRegions().size());
		assertEquals(7, updated.getRegions().getFirst().pageNumber());
		assertEquals(1, countRows(database, "answers"));
		assertEquals(1, countRows(database, "answer_regions"));
		Question reloaded = new SqliteQuestionRepository(database).findById(question.getId()).orElseThrow();
		assertTrue(reloaded.hasAnswer());
		assertEquals(original.getId(), reloaded.getAnswer().getId());
		assertEquals("C", reloaded.getAnswer().getAnswerText());
		assertEquals(7, reloaded.getAnswer().getRegions().getFirst().pageNumber());
	}

	private int countRows(SqliteDatabase database, String tableName) throws Exception {
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
			assertTrue(result.next());
			return result.getInt(1);
		}
	}
}
