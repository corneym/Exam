package au.edu.eq.questionbank.repository.assessment;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.Answer;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ImageQuestionContentPart;
import au.edu.eq.questionbank.model.PdfQuestionContentPart;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionContentPart;
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
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.retrieval.CurriculumSearchNodeExpansionService;
import au.edu.eq.questionbank.service.retrieval.QuestionRetrievalService;
import au.edu.eq.questionbank.service.revision.RevisionCorpus;
import au.edu.eq.questionbank.service.revision.RevisionCorpusBuilder;
import au.edu.eq.questionbank.service.revision.RevisionPresentationPlan;
import au.edu.eq.questionbank.service.revision.RevisionPresentationPlanner;
import au.edu.eq.questionbank.service.revision.RevisionQuestionPresentation;

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
	void bulkResponseTypeResolutionRejectsKnownQuestionWithoutPartialUpdate() throws Exception {
		SqliteQuestionWriter writer = new SqliteQuestionWriter(database);
		Question unknown = writer.insertQuestion(booklet, "Q42", "", 2, List.of(), historicalSubtopicOne, false, null,
				null, QuestionResponseType.UNKNOWN);
		Question alreadyKnown = writer.insertQuestion(booklet, "Q43", "", 1, List.of(), historicalSubtopicOne, false,
				null, null, QuestionResponseType.MULTIPLE_CHOICE);
		assertThrows(IllegalArgumentException.class, () -> service
				.resolveUnknownResponseTypes(List.of(unknown, alreadyKnown), QuestionResponseType.WRITTEN_RESPONSE));
		Question reloadedUnknown = questionRepository.findById(unknown.getId()).orElseThrow();
		Question reloadedKnown = questionRepository.findById(alreadyKnown.getId()).orElseThrow();
		assertEquals(QuestionResponseType.UNKNOWN, reloadedUnknown.getResponseType());
		assertEquals(QuestionResponseType.MULTIPLE_CHOICE, reloadedKnown.getResponseType());
	}

	@Test
	void bulkResponseTypeResolutionRollsBackWhenLaterQuestionBecomesKnownAfterLoading() throws Exception {
		SqliteQuestionWriter writer = new SqliteQuestionWriter(database);
		Question first = writer.insertQuestion(booklet, "Q44", "", 2, List.of(), historicalSubtopicOne, false, null,
				null, QuestionResponseType.UNKNOWN);
		Question second = writer.insertQuestion(booklet, "Q45", "", 1, List.of(), historicalSubtopicOne, false, null,
				null, QuestionResponseType.UNKNOWN);

		// Simulate another operation resolving the second Question after both Question
		// objects were loaded but before this bulk operation begins.
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.executeUpdate("""
					UPDATE questions
					SET response_type = 'MULTIPLE_CHOICE'
					WHERE id = %d
					""".formatted(second.getId()));
		}
		assertThrows(IllegalStateException.class, () -> service.resolveUnknownResponseTypes(List.of(first, second),
				QuestionResponseType.WRITTEN_RESPONSE));
		Question reloadedFirst = questionRepository.findById(first.getId()).orElseThrow();
		Question reloadedSecond = questionRepository.findById(second.getId()).orElseThrow();

		// The first update occurred before the stale second Question was detected, so
		// this assertion proves that the whole bulk operation rolled back.
		assertEquals(QuestionResponseType.UNKNOWN, reloadedFirst.getResponseType());
		assertEquals(QuestionResponseType.MULTIPLE_CHOICE, reloadedSecond.getResponseType());
	}

	@Test
	void cannotRemoveLegacySharedContextHintFromMultipartQuestionWithSharedContext() {
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
		assertTrue(reloadedFirst.isSharedContextCaptureRequired());
		assertTrue(reloadedFirst.hasSharedContext());
		assertEquals(context.getId(), reloadedFirst.getSharedContext().getId());
		assertEquals(1, reloadedFirst.getRegions().size());
		assertTrue(reloadedSecond.isSharedContextCaptureRequired());
		assertTrue(reloadedSecond.hasSharedContext());
		assertEquals(context.getId(), reloadedSecond.getSharedContext().getId());
		assertEquals(1, contextRepository.findByBooklet(booklet).size());
	}

	@Test
	void changesLegacySharedContextHintInBothDirections() {
		Question question = questionRepository.save(booklet, "Q2", "", 1, List.of(), historicalSubtopicOne, false);
		Question trueVersion = service.updateMetadata(question, "Q2", 1, historicalSubtopicOne, true);
		assertTrue(trueVersion.isSharedContextCaptureRequired());
		Question falseVersion = service.updateMetadata(trueVersion, "Q2", 1, historicalSubtopicOne, false);
		assertFalse(falseVersion.isSharedContextCaptureRequired());
	}

	@Test
	void changingMultipartCodeCanJoinCompatibleGroupAndRemainsPresentableAfterReload() {
		SqliteSourceQuestionRepository sourceRepository = new SqliteSourceQuestionRepository(database);
		SqliteSharedQuestionContextRepository contextRepository = new SqliteSharedQuestionContextRepository(database);
		SourceQuestion sourceTwentyOne = sourceRepository.save(booklet, "Q21");
		SourceQuestion sourceTwentyTwo = sourceRepository.save(booklet, "Q22");
		SharedQuestionContext sharedContext = contextRepository.save(booklet, "Q22 shared material",
				List.of(new SharedQuestionContextRegion(2, 0.10, 0.10, 0.80, 0.20)));
		Question movingQuestion = questionRepository.save(booklet, "Q21a", "", 2,
				List.of(new QuestionRegion(booklet, 2, 0.10, 0.35, 0.80, 0.20)), historicalSubtopicOne, false,
				sourceTwentyOne, sharedContext);
		Question destinationSibling = questionRepository.save(booklet, "Q22b", "", 3,
				List.of(new QuestionRegion(booklet, 3, 0.10, 0.30, 0.80, 0.25)), historicalSubtopicOne, false,
				sourceTwentyTwo, sharedContext);
		service.updateMetadata(movingQuestion, "Q22a", 2, historicalSubtopicOne, false);

		// Reload both questions so the presentation check uses persisted state, not
		// objects returned or retained from the correction operation.
		Question reloadedMoving = questionRepository.findById(movingQuestion.getId()).orElseThrow();
		Question reloadedSibling = questionRepository.findById(destinationSibling.getId()).orElseThrow();
		assertEquals("Q22a", reloadedMoving.getQuestionCode());
		assertTrue(reloadedMoving.hasSourceQuestion());
		assertTrue(reloadedSibling.hasSourceQuestion());
		assertEquals("Q22", reloadedMoving.getSourceQuestion().getSourceQuestionCode());
		assertEquals(reloadedSibling.getSourceQuestion().getId(), reloadedMoving.getSourceQuestion().getId());
		assertTrue(reloadedMoving.hasSharedContext());
		assertTrue(reloadedSibling.hasSharedContext());
		assertEquals(sharedContext.getId(), reloadedMoving.getSharedContext().getId());
		assertEquals(sharedContext.getId(), reloadedSibling.getSharedContext().getId());

		// Build a real revision corpus around the reloaded questions. The retrieval
		// boundary is deliberately small here because this regression is concerned with
		// persistence and presentation consistency, not mapping behaviour.
		SqliteCurriculumRepository curriculumRepository = new SqliteCurriculumRepository(database);
		QuestionRetrievalService retrievalService = new QuestionRetrievalService(currentNodes -> {
			assertTrue(currentNodes.stream().anyMatch(node -> node.getId() == currentSubtopic.getId()));
			return List.of(new QuestionApplicabilityMatch(reloadedMoving, currentSubtopic),
					new QuestionApplicabilityMatch(reloadedSibling, currentSubtopic));
		}, new CurriculumSearchNodeExpansionService(curriculumRepository));
		RevisionCorpus corpus = new RevisionCorpusBuilder(curriculumRepository, retrievalService,
				new SqliteQuestionOutputApplicabilityRepository(database)).build(booklet.getExam().getSubject());
		RevisionPresentationPlan plan = new RevisionPresentationPlanner().plan(corpus);
		RevisionQuestionPresentation presentation = plan.getRootNodes().getFirst().getChildren().getFirst()
				.getChildren().getFirst().getPresentations().getFirst();
		assertTrue(presentation.isMultipart());
		assertEquals(List.of("Q22a", "Q22b"),
				presentation.getMembers().stream().map(Question::getQuestionCode).toList());
		assertEquals(sharedContext.getId(), presentation.getSharedContext().getId());
		assertEquals("Q22", presentation.getSourceQuestion().getSourceQuestionCode());
	}

	@Test
	void changingMultipartCodeCannotJoinContextGroupWithoutSharedContext() {
		SqliteSourceQuestionRepository sourceRepository = new SqliteSourceQuestionRepository(database);
		SqliteSharedQuestionContextRepository contextRepository = new SqliteSharedQuestionContextRepository(database);
		SourceQuestion sourceTwentyOne = sourceRepository.save(booklet, "Q21");
		SourceQuestion sourceTwentyTwo = sourceRepository.save(booklet, "Q22");
		SharedQuestionContext destinationContext = contextRepository.save(booklet, "Q22 shared material",
				List.of(new SharedQuestionContextRegion(3, 0.10, 0.10, 0.80, 0.20)));
		Question movingQuestion = questionRepository.save(booklet, "Q21a", "", 2, List.of(), historicalSubtopicOne,
				false, sourceTwentyOne, null);
		Question destinationSibling = questionRepository.save(booklet, "Q22b", "", 3, List.of(), historicalSubtopicOne,
				false, sourceTwentyTwo, destinationContext);
		assertThrows(IllegalArgumentException.class,
				() -> service.updateMetadata(movingQuestion, "Q22a", 2, historicalSubtopicOne, false));
		Question reloadedMoving = questionRepository.findById(movingQuestion.getId()).orElseThrow();
		Question reloadedSibling = questionRepository.findById(destinationSibling.getId()).orElseThrow();
		assertEquals("Q21a", reloadedMoving.getQuestionCode());
		assertTrue(reloadedMoving.hasSourceQuestion());
		assertEquals("Q21", reloadedMoving.getSourceQuestion().getSourceQuestionCode());
		assertFalse(reloadedMoving.hasSharedContext());
		assertTrue(reloadedSibling.hasSharedContext());
		assertEquals(destinationContext.getId(), reloadedSibling.getSharedContext().getId());
	}

	@Test
	void changingMultipartCodeCannotJoinGroupWithDifferentSharedContext() {
		SqliteSourceQuestionRepository sourceRepository = new SqliteSourceQuestionRepository(database);
		SqliteSharedQuestionContextRepository contextRepository = new SqliteSharedQuestionContextRepository(database);
		SourceQuestion sourceTwentyOne = sourceRepository.save(booklet, "Q21");
		SourceQuestion sourceTwentyTwo = sourceRepository.save(booklet, "Q22");
		SharedQuestionContext contextA = contextRepository.save(booklet, "Q21 shared material",
				List.of(new SharedQuestionContextRegion(2, 0.10, 0.10, 0.80, 0.20)));
		SharedQuestionContext contextB = contextRepository.save(booklet, "Q22 shared material",
				List.of(new SharedQuestionContextRegion(3, 0.10, 0.10, 0.80, 0.20)));
		Question movingQuestion = questionRepository.save(booklet, "Q21a", "", 2, List.of(), historicalSubtopicOne,
				false, sourceTwentyOne, contextA);
		Question destinationSibling = questionRepository.save(booklet, "Q22b", "", 3, List.of(), historicalSubtopicOne,
				false, sourceTwentyTwo, contextB);
		assertThrows(IllegalArgumentException.class,
				() -> service.updateMetadata(movingQuestion, "Q22a", 2, historicalSubtopicOne, false));
		Question reloadedMoving = questionRepository.findById(movingQuestion.getId()).orElseThrow();
		Question reloadedSibling = questionRepository.findById(destinationSibling.getId()).orElseThrow();
		assertEquals("Q21a", reloadedMoving.getQuestionCode());
		assertTrue(reloadedMoving.hasSourceQuestion());
		assertEquals("Q21", reloadedMoving.getSourceQuestion().getSourceQuestionCode());
		assertTrue(reloadedMoving.hasSharedContext());
		assertEquals(contextA.getId(), reloadedMoving.getSharedContext().getId());
		assertEquals("Q22", reloadedSibling.getSourceQuestion().getSourceQuestionCode());
		assertEquals(contextB.getId(), reloadedSibling.getSharedContext().getId());
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
		assertTrue(question.isSharedContextCaptureRequired());
		assertTrue(question.hasSharedContext());
		assertEquals(1, question.getRegions().size());
		Question updated = service.updateMetadata(question, "Q5", 2, historicalSubtopicOne, false);
		assertFalse(updated.isSharedContextCaptureRequired());
		assertFalse(updated.hasSharedContext());

		// The former shared context becomes the first ordinary question region.
		// Existing
		// question material follows it.
		assertEquals(2, updated.getRegions().size());
		QuestionRegion convertedSharedContext = updated.getRegions().get(0);
		assertEquals(2, convertedSharedContext.pageNumber());
		assertEquals(0.10, convertedSharedContext.x(), 0.000001);
		assertEquals(0.10, convertedSharedContext.y(), 0.000001);
		assertEquals(0.80, convertedSharedContext.width(), 0.000001);
		assertEquals(0.25, convertedSharedContext.height(), 0.000001);
		assertEquals(booklet.getId(), convertedSharedContext.booklet().getId());
		QuestionRegion retainedQuestionRegion = updated.getRegions().get(1);
		assertEquals(2, retainedQuestionRegion.pageNumber());
		assertEquals(0.10, retainedQuestionRegion.x(), 0.000001);
		assertEquals(0.40, retainedQuestionRegion.y(), 0.000001);
		assertEquals(0.80, retainedQuestionRegion.width(), 0.000001);
		assertEquals(0.45, retainedQuestionRegion.height(), 0.000001);

		// This context belonged only to Q5, so after conversion it is orphaned and
		// should be removed.
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
		assertFalse(updated.isSharedContextCaptureRequired());
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
	void changingSinglePartLegacyHintToFalsePreservesMixedQuestionContentOrder() throws Exception {
		SqliteSharedQuestionContextRepository contextRepository = new SqliteSharedQuestionContextRepository(database);
		SharedQuestionContext context = contextRepository.save(booklet, "Q17 introductory material",
				List.of(new SharedQuestionContextRegion(2, 0.10, 0.10, 0.80, 0.20)));
		QuestionRegion existingPdfRegion = new QuestionRegion(booklet, 2, 0.10, 0.40, 0.80, 0.30);
		byte[] imageBytes = createTestPng();
		List<QuestionContentPart> originalContent = List.of(new ImageQuestionContentPart(imageBytes),
				new PdfQuestionContentPart(existingPdfRegion));
		SqliteQuestionWriter writer = new SqliteQuestionWriter(database);
		Question question = writer.insertQuestionWithContent(booklet, "Q17", "", 2, originalContent,
				historicalSubtopicOne, true, null, context, QuestionResponseType.WRITTEN_RESPONSE);
		LegacyQuestionMetadataUpdateResult result = service.updateMetadataWithResult(question, "Q17", 2,
				historicalSubtopicOne, false);
		Question updated = result.question();
		assertEquals(
				LegacyQuestionMetadataUpdateResult.SharedContextOutcome.CONVERTED_SHARED_CONTEXT_TO_QUESTION_REGIONS,
				result.sharedContextOutcome());
		assertFalse(updated.hasSharedContext());
		assertFalse(updated.isSharedContextCaptureRequired());
		assertEquals(3, updated.getContentParts().size());

		// Converted Shared Context becomes the leading PDF-backed body content.
		assertTrue(updated.getContentParts().get(0) instanceof PdfQuestionContentPart);
		PdfQuestionContentPart convertedContextPart = (PdfQuestionContentPart) updated.getContentParts().get(0);
		assertEquals(0.10, convertedContextPart.region().y(), 0.000001);

		// The existing image remains immediately before the existing PDF region.
		assertTrue(updated.getContentParts().get(1) instanceof ImageQuestionContentPart);
		ImageQuestionContentPart retainedImage = (ImageQuestionContentPart) updated.getContentParts().get(1);
		assertArrayEquals(imageBytes, retainedImage.pngBytes());
		assertTrue(updated.getContentParts().get(2) instanceof PdfQuestionContentPart);
		PdfQuestionContentPart retainedPdfPart = (PdfQuestionContentPart) updated.getContentParts().get(2);
		assertEquals(0.40, retainedPdfPart.region().y(), 0.000001);

		// The PDF-only compatibility projection also remains in encounter order.
		assertEquals(2, updated.getRegions().size());
		assertEquals(0.10, updated.getRegions().get(0).y(), 0.000001);
		assertEquals(0.40, updated.getRegions().get(1).y(), 0.000001);

		// This context belonged only to Q17, so conversion leaves no orphaned context.
		assertTrue(contextRepository.findByBooklet(booklet).isEmpty());
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
		assertFalse(updated.isSharedContextCaptureRequired());
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

		// The old API must preserve the response type rather than resetting it.
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
		assertFalse(reloaded.isSharedContextCaptureRequired());
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

		// Fail deliberately during orphan shared-context cleanup.
		// By this point the conversion has already replaced question regions and
		// updated the question row. The transaction must restore all of those earlier
		// changes.
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

		// Metadata update rolled back.
		assertEquals("Q5", reloaded.getQuestionCode());
		assertEquals(2, reloaded.getMarks());
		assertEquals(historicalSubtopicOne.getId(), reloaded.getClassification().getId());
		assertTrue(reloaded.isSharedContextCaptureRequired());

		// Shared-context unlink rolled back.
		assertTrue(reloaded.hasSharedContext());
		assertEquals(context.getId(), reloaded.getSharedContext().getId());

		// Region conversion rolled back. Only the original ordinary question region
		// remains.
		assertEquals(1, reloaded.getRegions().size());
		QuestionRegion originalRegion = reloaded.getRegions().getFirst();
		assertEquals(2, originalRegion.pageNumber());
		assertEquals(0.10, originalRegion.x(), 0.000001);
		assertEquals(0.40, originalRegion.y(), 0.000001);
		assertEquals(0.80, originalRegion.width(), 0.000001);
		assertEquals(0.40, originalRegion.height(), 0.000001);

		// The shared context and its source region must also still exist.
		List<SharedQuestionContext> contexts = contextRepository.findByBooklet(booklet);
		assertEquals(1, contexts.size());
		SharedQuestionContext restoredContext = contexts.getFirst();
		assertEquals(context.getId(), restoredContext.getId());
		assertEquals(1, restoredContext.getRegions().size());
		SharedQuestionContextRegion restoredSharedContext = restoredContext.getRegions().getFirst();
		assertEquals(2, restoredSharedContext.pageNumber());
		assertEquals(0.10, restoredSharedContext.x(), 0.000001);
		assertEquals(0.10, restoredSharedContext.y(), 0.000001);
		assertEquals(0.80, restoredSharedContext.width(), 0.000001);
		assertEquals(0.20, restoredSharedContext.height(), 0.000001);
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
		assertFalse(updated.isSharedContextCaptureRequired());
		assertFalse(updated.hasSourceQuestion());
		assertFalse(updated.hasSharedContext());
		assertEquals(2, updated.getRegions().size());
		QuestionRegion convertedSharedContext = updated.getRegions().get(0);
		assertEquals(2, convertedSharedContext.pageNumber());
		assertEquals(0.10, convertedSharedContext.y(), 0.000001);
		QuestionRegion retainedQuestionRegion = updated.getRegions().get(1);
		assertEquals(2, retainedQuestionRegion.pageNumber());
		assertEquals(0.40, retainedQuestionRegion.y(), 0.000001);

		// Neither relationship is needed after the question becomes ordinary.
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
		assertEquals(
				LegacyQuestionMetadataUpdateResult.SharedContextOutcome.CONVERTED_SHARED_CONTEXT_TO_QUESTION_REGIONS,
				result.sharedContextOutcome());
		assertFalse(updated.hasSourceQuestion());
		assertFalse(updated.hasSharedContext());
		assertFalse(updated.isSharedContextCaptureRequired());
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
		assertFalse(updated.isSharedContextCaptureRequired());
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
		assertEquals(
				LegacyQuestionMetadataUpdateResult.SharedContextOutcome.CONVERTED_SHARED_CONTEXT_TO_QUESTION_REGIONS,
				result.sharedContextOutcome());
		assertFalse(result.question().hasSharedContext());
		assertEquals(2, result.question().getRegions().size());
	}

	@Test
	void resolvesMultipleUnknownResponseTypesWithoutChangingCaptureData() throws Exception {
		SqliteQuestionWriter writer = new SqliteQuestionWriter(database);
		SqliteSharedQuestionContextRepository contextRepository = new SqliteSharedQuestionContextRepository(database);
		SharedQuestionContext context = contextRepository.save(booklet, "Q40 shared material",
				List.of(new SharedQuestionContextRegion(2, 0.10, 0.10, 0.80, 0.20)));
		Question first = writer.insertQuestion(booklet, "Q40", "", 2,
				List.of(new QuestionRegion(booklet, 2, 0.10, 0.40, 0.80, 0.25)), historicalSubtopicOne, false, null,
				context, QuestionResponseType.UNKNOWN);
		Question second = writer.insertQuestion(booklet, "Q41", "", 3,
				List.of(new QuestionRegion(booklet, 3, 0.10, 0.20, 0.80, 0.30)), historicalSubtopicOne, false, null,
				null, QuestionResponseType.UNKNOWN);
		List<Question> updated = service.resolveUnknownResponseTypes(List.of(first, second),
				QuestionResponseType.WRITTEN_RESPONSE);
		assertEquals(2, updated.size());
		assertEquals(QuestionResponseType.WRITTEN_RESPONSE, updated.get(0).getResponseType());
		assertEquals(QuestionResponseType.WRITTEN_RESPONSE, updated.get(1).getResponseType());
		assertTrue(updated.get(0).hasSharedContext());
		assertEquals(context.getId(), updated.get(0).getSharedContext().getId());
		assertEquals(1, updated.get(0).getRegions().size());
		assertEquals(1, updated.get(1).getRegions().size());
		assertEquals(1, contextRepository.findByBooklet(booklet).size());
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
		assertEquals(LegacyQuestionMetadataUpdateResult.SharedContextOutcome.NO_CAPTURE_CHANGE,
				result.sharedContextOutcome());
		assertEquals(4, updated.getMarks());
		assertEquals(historicalSubtopicTwo.getId(), updated.getClassification().getId());
		assertEquals(QuestionResponseType.WRITTEN_RESPONSE, updated.getResponseType());
		assertFalse(updated.isSharedContextCaptureRequired());
		assertTrue(updated.hasSharedContext());
		assertEquals(context.getId(), updated.getSharedContext().getId());
		assertEquals(1, updated.getRegions().size());
		List<SharedQuestionContext> contexts = contextRepository.findByBooklet(booklet);
		assertEquals(1, contexts.size());
		assertEquals(context.getId(), contexts.getFirst().getId());
	}

	private byte[] createTestPng() throws Exception {
		BufferedImage image = new BufferedImage(8, 4, BufferedImage.TYPE_INT_RGB);
		try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {

			// Persist genuine PNG data so this regression represents the same image
			// content produced by clipboard capture.
			assertTrue(ImageIO.write(image, "png", output));
			return output.toByteArray();
		}
	}
}
