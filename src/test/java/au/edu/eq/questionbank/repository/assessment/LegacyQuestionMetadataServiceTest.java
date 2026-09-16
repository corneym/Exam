package au.edu.eq.questionbank.repository.assessment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.Answer;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.QuestionResponseType;
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
	void cannotRemoveLegacyPreambleHintFromMultipartQuestionWithSharedContext() {
		SqliteSourceQuestionRepository sourceRepository = new SqliteSourceQuestionRepository(database);
		SourceQuestion sourceQuestion = sourceRepository.save(booklet, "Q7");
		SqliteSharedQuestionContextRepository contextRepository = new SqliteSharedQuestionContextRepository(database);
		SharedQuestionContext context = contextRepository.save(booklet, "Q7 shared introduction",
				List.of(new SharedQuestionContextRegion(2, 0.10, 0.10, 0.80, 0.20)));
		Question firstPart = questionRepository.save(booklet, "Q7a", "", 2,
				List.of(new QuestionRegion(booklet, 2, 0.10, 0.35, 0.80, 0.20)), historicalSubtopicOne, true,
				sourceQuestion, context);
		Question secondPart = questionRepository.save(booklet, "Q7b", "", 3,
				List.of(new QuestionRegion(booklet, 2, 0.10, 0.60, 0.80, 0.20)), historicalSubtopicOne, true,
				sourceQuestion, context);
		assertThrows(IllegalArgumentException.class,
				() -> service.updateMetadata(firstPart, "Q7a", 2, historicalSubtopicOne, false));
		Question reloadedFirst = questionRepository.findById(firstPart.getId()).orElseThrow();
		Question reloadedSecond = questionRepository.findById(secondPart.getId()).orElseThrow();
		assertTrue(reloadedFirst.isPreambleCaptureRequired());
		assertTrue(reloadedFirst.hasSharedContext());
		assertEquals(context.getId(), reloadedFirst.getSharedContext().getId());
		assertEquals(1, reloadedFirst.getRegions().size());
		assertTrue(reloadedSecond.isPreambleCaptureRequired());
		assertTrue(reloadedSecond.hasSharedContext());
		assertEquals(context.getId(), reloadedSecond.getSharedContext().getId());
		assertEquals(1, contextRepository.findByBooklet(booklet).size());
	}

	@Test
	void changesLegacyPreambleHintInBothDirections() {
		Question question = questionRepository.save(booklet, "Q2", "", 1, List.of(), historicalSubtopicOne, false);
		Question trueVersion = service.updateMetadata(question, "Q2", 1, historicalSubtopicOne, true);
		assertTrue(trueVersion.isPreambleCaptureRequired());
		Question falseVersion = service.updateMetadata(trueVersion, "Q2", 1, historicalSubtopicOne, false);
		assertFalse(falseVersion.isPreambleCaptureRequired());
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
	void changingSinglePartLegacyHintToFalseConvertsSharedContextToQuestionRegions() {
		SqliteSharedQuestionContextRepository contextRepository = new SqliteSharedQuestionContextRepository(database);
		SharedQuestionContext context = contextRepository.save(booklet, "Q5 introductory material",
				List.of(new SharedQuestionContextRegion(2, 0.10, 0.10, 0.80, 0.25)));
		QuestionRegion existingQuestionRegion = new QuestionRegion(booklet, 2, 0.10, 0.40, 0.80, 0.45);
		Question question = questionRepository.save(booklet, "Q5", "", 2, List.of(existingQuestionRegion),
				historicalSubtopicOne, true, null, context);
		assertTrue(question.isPreambleCaptureRequired());
		assertTrue(question.hasSharedContext());
		assertEquals(1, question.getRegions().size());
		Question updated = service.updateMetadata(question, "Q5", 2, historicalSubtopicOne, false);
		assertFalse(updated.isPreambleCaptureRequired());
		assertFalse(updated.hasSharedContext());
		/*
		 * The former preamble becomes the first ordinary question region. Existing
		 * question material follows it.
		 */
		assertEquals(2, updated.getRegions().size());
		QuestionRegion convertedPreamble = updated.getRegions().get(0);
		assertEquals(2, convertedPreamble.pageNumber());
		assertEquals(0.10, convertedPreamble.x(), 0.000001);
		assertEquals(0.10, convertedPreamble.y(), 0.000001);
		assertEquals(0.80, convertedPreamble.width(), 0.000001);
		assertEquals(0.25, convertedPreamble.height(), 0.000001);
		assertEquals(booklet.getId(), convertedPreamble.booklet().getId());
		QuestionRegion retainedQuestionRegion = updated.getRegions().get(1);
		assertEquals(2, retainedQuestionRegion.pageNumber());
		assertEquals(0.10, retainedQuestionRegion.x(), 0.000001);
		assertEquals(0.40, retainedQuestionRegion.y(), 0.000001);
		assertEquals(0.80, retainedQuestionRegion.width(), 0.000001);
		assertEquals(0.45, retainedQuestionRegion.height(), 0.000001);
		/*
		 * This context belonged only to Q5, so after conversion it is orphaned and
		 * should be removed.
		 */
		assertTrue(contextRepository.findByBooklet(booklet).isEmpty());
	}

	@Test
	void changingSinglePartLegacyHintToFalseKeepsSharedContextUsedByAnotherQuestion() {
		SqliteSharedQuestionContextRepository contextRepository = new SqliteSharedQuestionContextRepository(database);
		SharedQuestionContext context = contextRepository.save(booklet, "Shared introduction",
				List.of(new SharedQuestionContextRegion(2, 0.10, 0.10, 0.80, 0.20)));
		Question first = questionRepository.save(booklet, "Q5", "", 2,
				List.of(new QuestionRegion(booklet, 2, 0.10, 0.35, 0.80, 0.30)), historicalSubtopicOne, true, null,
				context);
		Question second = questionRepository.save(booklet, "Q6", "", 1,
				List.of(new QuestionRegion(booklet, 3, 0.10, 0.20, 0.80, 0.30)), historicalSubtopicOne, true, null,
				context);
		Question updated = service.updateMetadata(first, "Q5", 2, historicalSubtopicOne, false);
		assertFalse(updated.isPreambleCaptureRequired());
		assertFalse(updated.hasSharedContext());
		assertEquals(2, updated.getRegions().size());
		Question reloadedSecond = questionRepository.findById(second.getId()).orElseThrow();
		assertTrue(reloadedSecond.hasSharedContext());
		assertEquals(context.getId(), reloadedSecond.getSharedContext().getId());
		List<SharedQuestionContext> contexts = contextRepository.findByBooklet(booklet);
		assertEquals(1, contexts.size());
		assertEquals(context.getId(), contexts.getFirst().getId());
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
	void correctsQuestionResponseTypeWithoutChangingCaptureData() throws Exception {
		SqliteQuestionWriter writer = new SqliteQuestionWriter(database);
		Question question = writer.insertQuestion(booklet, "Q30", "", 2,
				List.of(new QuestionRegion(booklet, 2, 0.10, 0.20, 0.80, 0.30)), historicalSubtopicOne, false, null,
				null, QuestionResponseType.UNKNOWN);
		Question updated = service.updateMetadata(question, "Q30", 2, historicalSubtopicOne, false,
				QuestionResponseType.WRITTEN_RESPONSE);
		assertEquals(QuestionResponseType.WRITTEN_RESPONSE, updated.getResponseType());
		assertEquals(1, updated.getRegions().size());
		QuestionRegion originalRegion = question.getRegions().getFirst();
		QuestionRegion updatedRegion = updated.getRegions().getFirst();
		assertEquals(originalRegion.booklet().getId(), updatedRegion.booklet().getId());
		assertEquals(originalRegion.pageNumber(), updatedRegion.pageNumber());
		assertEquals(originalRegion.x(), updatedRegion.x(), 0.000001);
		assertEquals(originalRegion.y(), updatedRegion.y(), 0.000001);
		assertEquals(originalRegion.width(), updatedRegion.width(), 0.000001);
		assertEquals(originalRegion.height(), updatedRegion.height(), 0.000001);
		/*
		 * The old API must preserve the response type rather than resetting it.
		 */
		Question correctedAgain = service.updateMetadata(updated, "Q30", 3, historicalSubtopicOne, false);
		assertEquals(QuestionResponseType.WRITTEN_RESPONSE, correctedAgain.getResponseType());
		assertEquals(3, correctedAgain.getMarks());
		assertEquals(1, correctedAgain.getRegions().size());
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
	void failedSharedContextCleanupRollsBackEntireMetadataConversion() throws Exception {
		SqliteSharedQuestionContextRepository contextRepository = new SqliteSharedQuestionContextRepository(database);
		SharedQuestionContext context = contextRepository.save(booklet, "Q5 introductory material",
				List.of(new SharedQuestionContextRegion(2, 0.10, 0.10, 0.80, 0.20)));
		Question question = questionRepository.save(booklet, "Q5", "", 2,
				List.of(new QuestionRegion(booklet, 2, 0.10, 0.40, 0.80, 0.40)), historicalSubtopicOne, true, null,
				context);
		/*
		 * Fail deliberately during orphan shared-context cleanup.
		 *
		 * By this point the conversion has already replaced question regions and
		 * updated the question row. The transaction must restore all of those earlier
		 * changes.
		 */
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					CREATE TRIGGER reject_shared_context_region_delete
					BEFORE DELETE ON shared_question_context_regions
					BEGIN
					    SELECT RAISE(
					        ABORT,
					        'deliberate shared-context cleanup failure');
					END
					""");
		}
		assertThrows(IllegalStateException.class,
				() -> service.updateMetadata(question, "Q15", 7, historicalSubtopicTwo, false));
		Question reloaded = questionRepository.findById(question.getId()).orElseThrow();
		/*
		 * Metadata update rolled back.
		 */
		assertEquals("Q5", reloaded.getQuestionCode());
		assertEquals(2, reloaded.getMarks());
		assertEquals(historicalSubtopicOne.getId(), reloaded.getClassification().getId());
		assertTrue(reloaded.isPreambleCaptureRequired());
		/*
		 * Shared-context unlink rolled back.
		 */
		assertTrue(reloaded.hasSharedContext());
		assertEquals(context.getId(), reloaded.getSharedContext().getId());
		/*
		 * Region conversion rolled back. Only the original ordinary question region
		 * remains.
		 */
		assertEquals(1, reloaded.getRegions().size());
		QuestionRegion originalRegion = reloaded.getRegions().getFirst();
		assertEquals(2, originalRegion.pageNumber());
		assertEquals(0.10, originalRegion.x(), 0.000001);
		assertEquals(0.40, originalRegion.y(), 0.000001);
		assertEquals(0.80, originalRegion.width(), 0.000001);
		assertEquals(0.40, originalRegion.height(), 0.000001);
		/*
		 * The shared context and its source region must also still exist.
		 */
		List<SharedQuestionContext> contexts = contextRepository.findByBooklet(booklet);
		assertEquals(1, contexts.size());
		SharedQuestionContext restoredContext = contexts.getFirst();
		assertEquals(context.getId(), restoredContext.getId());
		assertEquals(1, restoredContext.getRegions().size());
		SharedQuestionContextRegion restoredPreamble = restoredContext.getRegions().getFirst();
		assertEquals(2, restoredPreamble.pageNumber());
		assertEquals(0.10, restoredPreamble.x(), 0.000001);
		assertEquals(0.10, restoredPreamble.y(), 0.000001);
		assertEquals(0.80, restoredPreamble.width(), 0.000001);
		assertEquals(0.20, restoredPreamble.height(), 0.000001);
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
	void multipartQuestionBecomingSinglePartConvertsSharedContextToQuestionRegions() {
		SqliteSourceQuestionRepository sourceRepository = new SqliteSourceQuestionRepository(database);
		SourceQuestion sourceQuestion = sourceRepository.save(booklet, "Q9");
		SqliteSharedQuestionContextRepository contextRepository = new SqliteSharedQuestionContextRepository(database);
		SharedQuestionContext context = contextRepository.save(booklet, "Q9 introductory material",
				List.of(new SharedQuestionContextRegion(2, 0.10, 0.10, 0.80, 0.20)));
		Question question = questionRepository.save(booklet, "Q9a", "", 3,
				List.of(new QuestionRegion(booklet, 2, 0.10, 0.40, 0.80, 0.35)), historicalSubtopicOne, true,
				sourceQuestion, context);
		Question updated = service.updateMetadata(question, "Q9", 3, historicalSubtopicOne, false);
		assertEquals("Q9", updated.getQuestionCode());
		assertFalse(updated.isPreambleCaptureRequired());
		assertFalse(updated.hasSourceQuestion());
		assertFalse(updated.hasSharedContext());
		assertEquals(2, updated.getRegions().size());
		QuestionRegion convertedPreamble = updated.getRegions().get(0);
		assertEquals(2, convertedPreamble.pageNumber());
		assertEquals(0.10, convertedPreamble.y(), 0.000001);
		QuestionRegion retainedQuestionRegion = updated.getRegions().get(1);
		assertEquals(2, retainedQuestionRegion.pageNumber());
		assertEquals(0.40, retainedQuestionRegion.y(), 0.000001);
		/*
		 * Neither relationship is needed after the question becomes ordinary.
		 */
		assertTrue(sourceRepository.findByBooklet(booklet).isEmpty());
		assertTrue(contextRepository.findByBooklet(booklet).isEmpty());
	}

	@Test
	void multipartQuestionWithFalseLegacyHintBecomingSinglePartConvertsSharedContextToQuestionRegions() {
		SqliteSourceQuestionRepository sourceRepository = new SqliteSourceQuestionRepository(database);
		SourceQuestion sourceQuestion = sourceRepository.save(booklet, "Q16");
		SqliteSharedQuestionContextRepository contextRepository = new SqliteSharedQuestionContextRepository(database);
		SharedQuestionContext context = contextRepository.save(booklet, "Q16 shared introduction",
				List.of(new SharedQuestionContextRegion(2, 0.10, 0.10, 0.80, 0.20)));
		Question question = questionRepository.save(booklet, "Q16a", "", 2,
				List.of(new QuestionRegion(booklet, 2, 0.10, 0.40, 0.80, 0.30)), historicalSubtopicOne, false,
				sourceQuestion, context);
		LegacyQuestionMetadataUpdateResult result = service.updateMetadataWithResult(question, "Q16", 2,
				historicalSubtopicOne, false);
		Question updated = result.question();
		assertEquals(LegacyQuestionMetadataUpdateResult.PreambleOutcome.CONVERTED_SHARED_CONTEXT_TO_QUESTION_REGIONS,
				result.preambleOutcome());
		assertFalse(updated.hasSourceQuestion());
		assertFalse(updated.hasSharedContext());
		assertFalse(updated.isPreambleCaptureRequired());
		assertEquals(2, updated.getRegions().size());
		assertTrue(sourceRepository.findByBooklet(booklet).isEmpty());
		assertTrue(contextRepository.findByBooklet(booklet).isEmpty());
	}

	@Test
	void multipartQuestionWithFalseLegacyHintCanStillCorrectOtherMetadata() {
		SqliteSourceQuestionRepository sourceRepository = new SqliteSourceQuestionRepository(database);
		SourceQuestion sourceQuestion = sourceRepository.save(booklet, "Q8");
		SqliteSharedQuestionContextRepository contextRepository = new SqliteSharedQuestionContextRepository(database);
		SharedQuestionContext context = contextRepository.save(booklet, "Q8 shared introduction",
				List.of(new SharedQuestionContextRegion(2, 0.10, 0.10, 0.80, 0.20)));
		Question question = questionRepository.save(booklet, "Q8a", "", 2,
				List.of(new QuestionRegion(booklet, 2, 0.10, 0.35, 0.80, 0.25)), historicalSubtopicOne, false,
				sourceQuestion, context);
		Question updated = service.updateMetadata(question, "Q8a", 4, historicalSubtopicTwo, false);
		assertEquals("Q8a", updated.getQuestionCode());
		assertEquals(4, updated.getMarks());
		assertEquals(historicalSubtopicTwo.getId(), updated.getClassification().getId());
		assertFalse(updated.isPreambleCaptureRequired());
		assertTrue(updated.hasSharedContext());
		assertEquals(context.getId(), updated.getSharedContext().getId());
		assertTrue(updated.hasSourceQuestion());
		assertEquals(sourceQuestion.getId(), updated.getSourceQuestion().getId());
		assertEquals(1, updated.getRegions().size());
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

	@Test
	void reportsWhenSharedContextWasConvertedToQuestionRegions() {
		SqliteSharedQuestionContextRepository contextRepository = new SqliteSharedQuestionContextRepository(database);
		SharedQuestionContext context = contextRepository.save(booklet, "Q13 introduction",
				List.of(new SharedQuestionContextRegion(2, 0.10, 0.10, 0.80, 0.20)));
		Question question = questionRepository.save(booklet, "Q13", "", 2,
				List.of(new QuestionRegion(booklet, 2, 0.10, 0.40, 0.80, 0.30)), historicalSubtopicOne, true, null,
				context);
		LegacyQuestionMetadataUpdateResult result = service.updateMetadataWithResult(question, "Q13", 2,
				historicalSubtopicOne, false);
		assertEquals(LegacyQuestionMetadataUpdateResult.PreambleOutcome.CONVERTED_SHARED_CONTEXT_TO_QUESTION_REGIONS,
				result.preambleOutcome());
		assertFalse(result.question().hasSharedContext());
		assertEquals(2, result.question().getRegions().size());
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

	@Test
	void singlePartQuestionWithFalseLegacyHintCanCorrectOtherMetadataWithoutConvertingSharedContext() {
		SqliteSharedQuestionContextRepository contextRepository = new SqliteSharedQuestionContextRepository(database);
		SharedQuestionContext context = contextRepository.save(booklet, "Q14 existing shared material",
				List.of(new SharedQuestionContextRegion(2, 0.10, 0.10, 0.80, 0.20)));
		Question question = questionRepository.save(booklet, "Q14", "", 2,
				List.of(new QuestionRegion(booklet, 2, 0.10, 0.40, 0.80, 0.30)), historicalSubtopicOne, false, null,
				context);
		LegacyQuestionMetadataUpdateResult result = service.updateMetadataWithResult(question, "Q14", 4,
				historicalSubtopicTwo, false, QuestionResponseType.WRITTEN_RESPONSE);
		Question updated = result.question();
		assertEquals(LegacyQuestionMetadataUpdateResult.PreambleOutcome.NO_CAPTURE_CHANGE, result.preambleOutcome());
		assertEquals(4, updated.getMarks());
		assertEquals(historicalSubtopicTwo.getId(), updated.getClassification().getId());
		assertEquals(QuestionResponseType.WRITTEN_RESPONSE, updated.getResponseType());
		assertFalse(updated.isPreambleCaptureRequired());
		assertTrue(updated.hasSharedContext());
		assertEquals(context.getId(), updated.getSharedContext().getId());
		assertEquals(1, updated.getRegions().size());
		List<SharedQuestionContext> contexts = contextRepository.findByBooklet(booklet);
		assertEquals(1, contexts.size());
		assertEquals(context.getId(), contexts.getFirst().getId());
	}
}
