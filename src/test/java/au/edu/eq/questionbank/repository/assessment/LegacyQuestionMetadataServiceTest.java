package au.edu.eq.questionbank.repository.assessment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.Answer;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.SharedQuestionContext;
import au.edu.eq.questionbank.model.SharedQuestionContextRegion;
import au.edu.eq.questionbank.model.SourceQuestion;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class LegacyQuestionMetadataServiceTest {

	@TempDir
	Path tempDirectory;
	private SqliteDatabase database;
	private SqliteQuestionRepository questionRepository;
	private LegacyQuestionMetadataService service;
	private ExamBooklet booklet;
	private Subtopic historicalSubtopicOne;
	private Subtopic historicalSubtopicTwo;
	private Subtopic currentSubtopic;

	@Test
	void changesLegacyPreambleHintInBothDirections() {
		Question question = questionRepository.save(booklet, "Q2", "", 1, List.of(), historicalSubtopicOne, false);
		Question trueVersion = service.updateMetadata(question, "Q2", 1, historicalSubtopicOne, true);
		assertTrue(trueVersion.isPreambleCaptureRequired());
		Question falseVersion = service.updateMetadata(trueVersion, "Q2", 1, historicalSubtopicOne, false);
		assertFalse(falseVersion.isPreambleCaptureRequired());
	}

	@Test
	void changingLegacyHintDoesNotRemoveRealSharedContext() {
		SqliteSourceQuestionRepository sourceRepository = new SqliteSourceQuestionRepository(database);
		SourceQuestion sourceQuestion = sourceRepository.save(booklet, "Q5");
		SqliteSharedQuestionContextRepository contextRepository = new SqliteSharedQuestionContextRepository(database);
		SharedQuestionContext context = contextRepository.save(booklet, "Q5 shared introduction",
				List.of(new SharedQuestionContextRegion(2, 0.10, 0.10, 0.80, 0.20)));
		Question question = questionRepository.save(booklet, "Q5a", "", 2, List.of(), historicalSubtopicOne, true,
				sourceQuestion, context);
		Question updated = service.updateMetadata(question, "Q5a", 2, historicalSubtopicOne, false);
		assertFalse(updated.isPreambleCaptureRequired());
		assertTrue(updated.hasSharedContext());
		assertEquals(context.getId(), updated.getSharedContext().getId());
		assertFalse(updated.isSharedContextUnresolved());
	}

	@Test
	void changingMultipartCodeReplacesUnusedSourceIdentity() {
		SqliteSourceQuestionRepository sourceRepository = new SqliteSourceQuestionRepository(database);
		SourceQuestion sourceQuestion = sourceRepository.save(booklet, "Q8");
		Question question = questionRepository.save(booklet, "Q8a", "", 1, List.of(), historicalSubtopicOne, false,
				sourceQuestion, null);
		Question updated = service.updateMetadata(question, "Q9a", 1, historicalSubtopicOne, false);
		assertTrue(updated.hasSourceQuestion());
		assertEquals("Q9", updated.getSourceQuestion().getSourceQuestionCode());
		List<SourceQuestion> sources = sourceRepository.findByBooklet(booklet);
		assertEquals(1, sources.size());
		assertEquals("Q9", sources.getFirst().getSourceQuestionCode());
	}

	@Test
	void correctsMetadataOnlyImportedQuestionWithoutRegions() {
		Question question = questionRepository.save(booklet, "Q1", "Imported legacy text", 2, List.of(),
				historicalSubtopicOne, true);
		Question updated = service.updateMetadata(question, "Q1a", 4, historicalSubtopicTwo, false);
		assertEquals(question.getId(), updated.getId());
		assertEquals(booklet.getId(), updated.getBooklet().getId());
		assertEquals("Q1a", updated.getQuestionCode());
		assertEquals("Imported legacy text", updated.getQuestionText());
		assertEquals(4, updated.getMarks());
		assertEquals(historicalSubtopicTwo.getId(), updated.getClassification().getId());
		assertFalse(updated.isPreambleCaptureRequired());
		assertTrue(updated.getRegions().isEmpty());
		assertTrue(updated.hasSourceQuestion());
		assertEquals("Q1", updated.getSourceQuestion().getSourceQuestionCode());
	}

	@Test
	void duplicateQuestionCodeRollsBackAllMetadataChanges() {
		Question first = questionRepository.save(booklet, "Q11", "", 1, List.of(), historicalSubtopicOne, false);
		questionRepository.save(booklet, "Q12", "", 1, List.of(), historicalSubtopicOne, false);
		assertThrows(IllegalStateException.class,
				() -> service.updateMetadata(first, "Q12", 9, historicalSubtopicTwo, true));
		Question reloaded = questionRepository.findById(first.getId()).orElseThrow();
		assertEquals("Q11", reloaded.getQuestionCode());
		assertEquals(1, reloaded.getMarks());
		assertEquals(historicalSubtopicOne.getId(), reloaded.getClassification().getId());
		assertFalse(reloaded.isPreambleCaptureRequired());
		assertFalse(reloaded.hasSourceQuestion());
	}

	@Test
	void multipartQuestionBecomesOrdinaryAndRemovesUnusedSourceIdentity() {
		SqliteSourceQuestionRepository sourceRepository = new SqliteSourceQuestionRepository(database);
		SourceQuestion sourceQuestion = sourceRepository.save(booklet, "Q7");
		Question question = questionRepository.save(booklet, "Q7a", "", 1, List.of(), historicalSubtopicOne, false,
				sourceQuestion, null);
		Question updated = service.updateMetadata(question, "Q7", 1, historicalSubtopicOne, false);
		assertFalse(updated.hasSourceQuestion());
		assertTrue(sourceRepository.findByBooklet(booklet).isEmpty());
	}

	@Test
	void ordinaryQuestionBecomesMultipartAndCreatesSourceIdentity() {
		Question question = questionRepository.save(booklet, "Q6", "", 1, List.of(), historicalSubtopicOne, false);
		assertFalse(question.hasSourceQuestion());
		Question updated = service.updateMetadata(question, "Q6a", 1, historicalSubtopicOne, false);
		assertTrue(updated.hasSourceQuestion());
		assertEquals("Q6", updated.getSourceQuestion().getSourceQuestionCode());
		assertEquals(1, new SqliteSourceQuestionRepository(database).findByBooklet(booklet).size());
	}

	@Test
	void preservesExistingAnswer() throws Exception {
		Question question = questionRepository.save(booklet, "Q4", "", 1, List.of(), historicalSubtopicOne, false);
		SqliteAnswerWriter answerWriter = new SqliteAnswerWriter(database, new SqliteExamWriter(database));
		Answer answer = answerWriter.insertAnswer(question, "B", List.of());
		Question updated = service.updateMetadata(question, "Q4", 3, historicalSubtopicTwo, true);
		assertTrue(updated.hasAnswer());
		assertEquals(answer.getId(), updated.getAnswer().getId());
		assertEquals("B", updated.getAnswer().getAnswerText());
	}

	@Test
	void preservesExistingQuestionRegions() {
		List<QuestionRegion> regions = List.of(new QuestionRegion(booklet, 3, 0.10, 0.20, 0.50, 0.15),
				new QuestionRegion(booklet, 4, 0.15, 0.25, 0.45, 0.20));
		Question question = questionRepository.save(booklet, "Q3", "", 2, regions, historicalSubtopicOne, false);
		Question updated = service.updateMetadata(question, "Q3", 5, historicalSubtopicTwo, true);
		assertEquals(2, updated.getRegions().size());
		assertEquals(3, updated.getRegions().get(0).pageNumber());
		assertEquals(4, updated.getRegions().get(1).pageNumber());
		assertEquals(0.10, updated.getRegions().get(0).x(), 0.000001);
		assertEquals(0.45, updated.getRegions().get(1).width(), 0.000001);
		assertEquals(booklet.getId(), updated.getRegions().get(0).booklet().getId());
	}

	@Test
	void rejectsClassificationFromAnotherSyllabusVersion() {
		Question question = questionRepository.save(booklet, "Q10", "", 1, List.of(), historicalSubtopicOne, false);
		assertThrows(IllegalArgumentException.class,
				() -> service.updateMetadata(question, "Q10", 1, currentSubtopic, false));
		Question reloaded = questionRepository.findById(question.getId()).orElseThrow();
		assertEquals(historicalSubtopicOne.getId(), reloaded.getClassification().getId());
	}

	@BeforeEach
	void setUp() throws Exception {
		database = new SqliteDatabase(tempDirectory.resolve("questionbank.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SyllabusVersion historical = curriculumWriter.insertSyllabusVersion(chemistry, "2019", false);
		Unit historicalUnit = curriculumWriter.insertUnit(historical, "1", "Historical unit", 0);
		Topic historicalTopic = curriculumWriter.insertTopic(historicalUnit, "1.1", "Historical topic", 0);
		historicalSubtopicOne = curriculumWriter.insertSubtopic(historicalTopic, "1.1.1", "Historical subtopic one", 0);
		historicalSubtopicTwo = curriculumWriter.insertSubtopic(historicalTopic, "1.1.2", "Historical subtopic two", 1);
		SyllabusVersion current = curriculumWriter.insertSyllabusVersion(chemistry, "2025", true);
		Unit currentUnit = curriculumWriter.insertUnit(current, "1", "Current unit", 0);
		Topic currentTopic = curriculumWriter.insertTopic(currentUnit, "1.1", "Current topic", 0);
		currentSubtopic = curriculumWriter.insertSubtopic(currentTopic, "1.1.1", "Current subtopic", 0);
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		booklet = new SqliteExamImporter(database, examWriter).importExam(chemistry, "QCAA", 2019,
				"External Assessment", "Paper 1", "Chemistry/2019/paper1.pdf");
		questionRepository = new SqliteQuestionRepository(database);
		service = new LegacyQuestionMetadataService(database);
	}
}
