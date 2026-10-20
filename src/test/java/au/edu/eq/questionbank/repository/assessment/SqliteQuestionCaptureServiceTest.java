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
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ImageQuestionContentPart;
import au.edu.eq.questionbank.model.PdfQuestionContentPart;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionContentPart;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.SharedContextStatus;
import au.edu.eq.questionbank.model.SharedQuestionContextRegion;
import au.edu.eq.questionbank.model.SourceQuestion;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class SqliteQuestionCaptureServiceTest {

	@TempDir
	Path tempDirectory;

	@Test
	void changedStoredStateIsDetectedByScopedLookup() throws Exception {
		Fixture fixture = createFixture("scoped-state-change.db");

		SqliteQuestionCaptureService service = new SqliteQuestionCaptureService(fixture.database());

		SqliteQuestionRepository repository = new SqliteQuestionRepository(fixture.database());

		// The Question code is initially available.
		assertFalse(service.hasConflictingQuestionCode(fixture.booklet(), "Q17", null));

		// Another database writer changes the stored state.
		Question inserted = repository.save(fixture.booklet(), "Q17", "", 1,
				List.of(new QuestionRegion(fixture.booklet(), 1, 0.10, 0.10, 0.60, 0.20)), fixture.classification(),
				false, null, null, QuestionResponseType.WRITTEN_RESPONSE);

		// A new scoped read must see the persisted change;
		// it must not rely on a previous corpus snapshot.
		assertTrue(service.hasConflictingQuestionCode(fixture.booklet(), "Q17", null));

		// Excluding the same Question identity remains valid
		// when the existing Question is being edited.
		assertFalse(service.hasConflictingQuestionCode(fixture.booklet(), "Q17", inserted.getId()));

		// Excluding an unrelated identity cannot conceal
		// the newly persisted duplicate.
		assertTrue(service.hasConflictingQuestionCode(fixture.booklet(), "Q17", inserted.getId() + 1));

		assertEquals(1, repository.findAll().size());
	}

	@Test
	void compatibilityRequestPreservesExistingResponseType() throws Exception {
		Fixture fixture = createFixture("preserve-response-type.db");
		SqliteQuestionRepository repository = new SqliteQuestionRepository(fixture.database());
		Question original = repository.save(fixture.booklet(), "Q3", "", 1,
				List.of(new QuestionRegion(fixture.booklet(), 1, 0.10, 0.10, 0.70, 0.20)), fixture.classification(),
				false, null, null, QuestionResponseType.MULTIPLE_CHOICE);
		SqliteQuestionCaptureService service = new SqliteQuestionCaptureService(fixture.database());

		// The compatibility request still preserves the existing response type, while
		// retaining the mandatory one-mark value for an MCQ.
		Question edited = service.save(
				new SqliteQuestionCaptureService.Request(SqliteQuestionCaptureService.Operation.EDIT, fixture.booklet(),
						original, "Q3", 1, original.getRegions(), fixture.classification(), null, null));
		assertEquals(QuestionResponseType.MULTIPLE_CHOICE, edited.getResponseType());
		Question reloaded = repository.findById(original.getId()).orElseThrow();
		assertEquals(QuestionResponseType.MULTIPLE_CHOICE, reloaded.getResponseType());
		assertEquals(1, reloaded.getMarks());
	}

	@Test
	void concurrentInsertAfterValidationCannotDuplicateQuestionCode() throws Exception {

		Fixture fixture = createFixture("concurrent-code-conflict.db");

		SqliteQuestionCaptureService service = new SqliteQuestionCaptureService(fixture.database());

		SqliteQuestionRepository repository = new SqliteQuestionRepository(fixture.database());

		// The first operation checks that the code is free.
		assertFalse(service.hasConflictingQuestionCode(fixture.booklet(), "Q18", null));

		// Simulate a competing writer committing the same
		// Question code before the original operation saves.
		Question winner = repository.save(fixture.booklet(), "Q18", "", 2,
				List.of(new QuestionRegion(fixture.booklet(), 1, 0.10, 0.20, 0.60, 0.20)), fixture.classification(),
				false, null, null, QuestionResponseType.WRITTEN_RESPONSE);

		// The earlier successful validation is now stale.
		// SQLite must still prevent the second insertion.
		assertThrows(RuntimeException.class, () -> service.save(SqliteQuestionCaptureService.Request.withContent(
				SqliteQuestionCaptureService.Operation.NEW, fixture.booklet(), null, "Q18", 3,
				List.of(new PdfQuestionContentPart(new QuestionRegion(fixture.booklet(), 1, 0.20, 0.40, 0.50, 0.15))),
				fixture.classification(), QuestionResponseType.WRITTEN_RESPONSE, null, null, false)));

		// The unsuccessful transaction must neither replace
		// the winning Question nor create another Question.
		List<Question> persisted = repository.findAll();

		assertEquals(1, persisted.size());
		assertEquals(winner.getId(), persisted.getFirst().getId());
		assertEquals("Q18", persisted.getFirst().getQuestionCode());
		assertEquals(2, persisted.getFirst().getMarks());
		assertEquals(1, persisted.getFirst().getRegions().size());

		// The authoritative scoped lookup must still report
		// the code as occupied after the failed transaction.
		assertTrue(service.hasConflictingQuestionCode(fixture.booklet(), "Q18", null));
	}

	@Test
	void editCapturePreservesAndReordersMixedQuestionContent() throws Exception {
		Fixture fixture = createFixture("mixed-question-edit.db");
		QuestionRegion region = new QuestionRegion(fixture.booklet(), 1, 0.10, 0.20, 0.70, 0.20);
		byte[] pngBytes = createTestPng();
		SqliteQuestionCaptureService service = new SqliteQuestionCaptureService(fixture.database());
		Question original = service.save(SqliteQuestionCaptureService.Request.withContent(
				SqliteQuestionCaptureService.Operation.NEW, fixture.booklet(), null, "Q10", 2,
				List.of(new ImageQuestionContentPart(pngBytes), new PdfQuestionContentPart(region)),
				fixture.classification(), QuestionResponseType.WRITTEN_RESPONSE, null, null, false));
		Question updated = service.save(SqliteQuestionCaptureService.Request.withContent(
				SqliteQuestionCaptureService.Operation.EDIT, fixture.booklet(), original, "Q10", 2,
				List.of(new PdfQuestionContentPart(region), new ImageQuestionContentPart(pngBytes)),
				fixture.classification(), QuestionResponseType.WRITTEN_RESPONSE, null, null, false));
		assertTrue(updated.getContentParts().get(0) instanceof PdfQuestionContentPart);
		assertTrue(updated.getContentParts().get(1) instanceof ImageQuestionContentPart);
		Question reloaded = new SqliteQuestionRepository(fixture.database()).findById(original.getId()).orElseThrow();
		assertTrue(reloaded.getContentParts().get(0) instanceof PdfQuestionContentPart);
		assertTrue(reloaded.getContentParts().get(1) instanceof ImageQuestionContentPart);
		ImageQuestionContentPart imagePart = (ImageQuestionContentPart) reloaded.getContentParts().get(1);
		assertArrayEquals(pngBytes, imagePart.pngBytes());
	}

	@Test
	void editingLastMultipartPartToNonMultipartRemovesUnreferencedSourceQuestionButRetainsContext() throws Exception {
		Fixture fixture = createFixture("edit-last-part-to-non-multipart.db");
		SqliteSourceQuestionRepository sourceRepository = new SqliteSourceQuestionRepository(fixture.database());
		SourceQuestion sourceQuestion = sourceRepository.save(fixture.booklet(), "24");
		sourceQuestion = sourceRepository.updateSharedContextStatus(sourceQuestion, SharedContextStatus.PRESENT);
		SqliteSharedQuestionContextRepository sharedContextRepository = new SqliteSharedQuestionContextRepository(
				fixture.database());
		var sharedContext = sharedContextRepository.save(fixture.booklet(), "Question 24 shared context",
				List.of(new SharedQuestionContextRegion(1, 0.10, 0.10, 0.80, 0.15)));
		SqliteQuestionRepository questionRepository = new SqliteQuestionRepository(fixture.database());
		Question original = questionRepository.save(fixture.booklet(), "24a", "", 2,
				List.of(new QuestionRegion(fixture.booklet(), 2, 0.10, 0.20, 0.60, 0.15)), fixture.classification(),
				false, sourceQuestion, sharedContext);
		SqliteQuestionCaptureService service = new SqliteQuestionCaptureService(fixture.database());
		SqliteQuestionCaptureService.Request request = new SqliteQuestionCaptureService.Request(
				SqliteQuestionCaptureService.Operation.EDIT, fixture.booklet(), original, "25", 2,
				original.getRegions(), fixture.classification(), null, null);
		Question updated = service.save(request);
		assertEquals("25", updated.getQuestionCode());
		assertFalse(updated.hasSourceQuestion());
		assertFalse(updated.hasSharedContext());
		Question reloaded = questionRepository.findById(original.getId()).orElseThrow();
		assertEquals("25", reloaded.getQuestionCode());
		assertFalse(reloaded.hasSourceQuestion());
		assertFalse(reloaded.hasSharedContext());
		assertTrue(sourceRepository.findByBookletAndCode(fixture.booklet(), "24").isEmpty());
		assertEquals(1, sharedContextRepository.findByBooklet(fixture.booklet()).size());
		assertEquals(sharedContext.getId(),
				sharedContextRepository.findByBooklet(fixture.booklet()).getFirst().getId());
	}

	@Test
	void editingOneMultipartPartAwayRetainsSourceQuestionUsedBySibling() throws Exception {
		Fixture fixture = createFixture("edit-one-multipart-part.db");
		SqliteSourceQuestionRepository sourceRepository = new SqliteSourceQuestionRepository(fixture.database());
		SourceQuestion sourceQuestion = sourceRepository.save(fixture.booklet(), "24");
		sourceQuestion = sourceRepository.updateSharedContextStatus(sourceQuestion, SharedContextStatus.PRESENT);
		SqliteSharedQuestionContextRepository sharedContextRepository = new SqliteSharedQuestionContextRepository(
				fixture.database());
		var sharedContext = sharedContextRepository.save(fixture.booklet(), "Question 24 shared context",
				List.of(new SharedQuestionContextRegion(1, 0.10, 0.10, 0.80, 0.15)));
		SqliteQuestionRepository questionRepository = new SqliteQuestionRepository(fixture.database());
		Question firstPart = questionRepository.save(fixture.booklet(), "24a", "", 2,
				List.of(new QuestionRegion(fixture.booklet(), 2, 0.10, 0.20, 0.60, 0.15)), fixture.classification(),
				false, sourceQuestion, sharedContext);
		Question sibling = questionRepository.save(fixture.booklet(), "24b", "", 3,
				List.of(new QuestionRegion(fixture.booklet(), 2, 0.10, 0.40, 0.60, 0.15)), fixture.classification(),
				false, sourceQuestion, sharedContext);
		SqliteQuestionCaptureService service = new SqliteQuestionCaptureService(fixture.database());
		Question updated = service.save(new SqliteQuestionCaptureService.Request(
				SqliteQuestionCaptureService.Operation.EDIT, fixture.booklet(), firstPart, "25", firstPart.getMarks(),
				firstPart.getRegions(), fixture.classification(), null, null));
		assertFalse(updated.hasSourceQuestion());
		assertFalse(updated.hasSharedContext());
		SourceQuestion retainedSource = sourceRepository.findByBookletAndCode(fixture.booklet(), "24").orElseThrow();
		assertEquals(sourceQuestion.getId(), retainedSource.getId());
		assertEquals(SharedContextStatus.PRESENT, retainedSource.getSharedContextStatus());
		Question reloadedSibling = questionRepository.findById(sibling.getId()).orElseThrow();
		assertTrue(reloadedSibling.hasSourceQuestion());
		assertTrue(reloadedSibling.hasSharedContext());
		assertEquals(sourceQuestion.getId(), reloadedSibling.getSourceQuestion().getId());
		assertEquals(sharedContext.getId(), reloadedSibling.getSharedContext().getId());
	}

	@Test
	void failedImportedCaptureRollsBackContextPropagationAndSharedContextStatus() throws Exception {
		Fixture fixture = createFixture("capture-rollback.db");
		SqliteSourceQuestionRepository sourceRepository = new SqliteSourceQuestionRepository(fixture.database());
		SourceQuestion sourceQuestion = sourceRepository.save(fixture.booklet(), "24");
		SqliteQuestionRepository questionRepository = new SqliteQuestionRepository(fixture.database());
		Question first = questionRepository.save(fixture.booklet(), "24a", "", 2, List.of(), fixture.classification(),
				true, sourceQuestion, null);
		Question sibling = questionRepository.save(fixture.booklet(), "24b", "", 3, List.of(), fixture.classification(),
				true, sourceQuestion, null);
		try (Connection connection = fixture.database().openConnection();
				Statement statement = connection.createStatement()) {
			statement.execute("""
					CREATE TRIGGER reject_question_capture
					BEFORE INSERT ON question_regions
					WHEN NEW.question_id = %d
					BEGIN
					    SELECT RAISE(
					        ABORT,
					        'forced question capture failure');
					END
					""".formatted(first.getId()));
		}
		SqliteQuestionCaptureService service = new SqliteQuestionCaptureService(fixture.database());
		SqliteQuestionCaptureService.Request request = new SqliteQuestionCaptureService.Request(
				SqliteQuestionCaptureService.Operation.IMPORTED, fixture.booklet(), first, first.getQuestionCode(),
				first.getMarks(), List.of(new QuestionRegion(fixture.booklet(), 2, 0.10, 0.20, 0.60, 0.15)),
				fixture.classification(), null,
				new SqliteQuestionCaptureService.PendingSharedContext("Question 24 shared context",
						List.of(new SharedQuestionContextRegion(1, 0.10, 0.10, 0.80, 0.15))));
		assertThrows(IllegalStateException.class, () -> service.save(request));
		SourceQuestion reloadedSource = new SqliteSourceQuestionRepository(fixture.database())
				.findByBookletAndCode(fixture.booklet(), "24").orElseThrow();
		assertEquals(SharedContextStatus.UNKNOWN, reloadedSource.getSharedContextStatus());
		assertTrue(new SqliteSharedQuestionContextRepository(fixture.database()).findByBooklet(fixture.booklet())
				.isEmpty());
		Question reloadedFirst = new SqliteQuestionRepository(fixture.database()).findById(first.getId()).orElseThrow();
		Question reloadedSibling = new SqliteQuestionRepository(fixture.database()).findById(sibling.getId())
				.orElseThrow();
		assertTrue(reloadedFirst.getRegions().isEmpty());
		assertFalse(reloadedFirst.hasSharedContext());
		assertFalse(reloadedSibling.hasSharedContext());
	}

	@Test
	void failedNewQuestionRollsBackNewSourceQuestionAndContext() throws Exception {
		Fixture fixture = createFixture("new-question-rollback.db");
		try (Connection connection = fixture.database().openConnection();
				Statement statement = connection.createStatement()) {
			statement.execute("""
					CREATE TRIGGER reject_new_question
					BEFORE INSERT ON questions
					WHEN NEW.question_code = '25a'
					BEGIN
					    SELECT RAISE(
					        ABORT,
					        'forced new question failure');
					END
					""");
		}
		SqliteQuestionCaptureService service = new SqliteQuestionCaptureService(fixture.database());
		SqliteQuestionCaptureService.Request request = new SqliteQuestionCaptureService.Request(
				SqliteQuestionCaptureService.Operation.NEW, fixture.booklet(), null, "25a", 2,
				List.of(new QuestionRegion(fixture.booklet(), 2, 0.10, 0.30, 0.70, 0.15)), fixture.classification(),
				null, new SqliteQuestionCaptureService.PendingSharedContext("Question 25 shared context",
						List.of(new SharedQuestionContextRegion(1, 0.10, 0.10, 0.80, 0.15))));
		assertThrows(IllegalStateException.class, () -> service.save(request));
		assertTrue(new SqliteSourceQuestionRepository(fixture.database()).findByBookletAndCode(fixture.booklet(), "25")
				.isEmpty());
		assertTrue(new SqliteSharedQuestionContextRepository(fixture.database()).findByBooklet(fixture.booklet())
				.isEmpty());
		assertTrue(new SqliteQuestionRepository(fixture.database()).findAll().isEmpty());
	}

	@Test
	void failedPendingMcqContinuationRollsBackQuestionAndNewContext() throws Exception {
		Fixture fixture = createFixture("independent-mcq-continuation-rollback.db");
		try (Connection connection = fixture.database().openConnection();
				Statement statement = connection.createStatement()) {
			statement.execute("""
					CREATE TRIGGER reject_pending_mcq_context
					BEFORE UPDATE OF pending_mcq_shared_context_id ON exam_booklets
					WHEN NEW.pending_mcq_shared_context_id IS NOT NULL
					BEGIN
					    SELECT RAISE(
					        ABORT,
					        'forced pending MCQ context failure');
					END
					""");
		}
		SqliteQuestionCaptureService service = new SqliteQuestionCaptureService(fixture.database());
		SqliteQuestionCaptureService.Request request = new SqliteQuestionCaptureService.Request(
				SqliteQuestionCaptureService.Operation.NEW, fixture.booklet(), null, "Q5", 1,
				List.of(new QuestionRegion(fixture.booklet(), 1, 0.10, 0.30, 0.70, 0.12)), fixture.classification(),
				QuestionResponseType.MULTIPLE_CHOICE, null, new SqliteQuestionCaptureService.PendingSharedContext(
						"CTX-Q5", List.of(new SharedQuestionContextRegion(1, 0.10, 0.10, 0.80, 0.15))),
				true);
		assertThrows(IllegalStateException.class, () -> service.save(request));

		// Question, context and continuation are one transaction. A failure while
		// recording the continuation must leave none of them partially committed.
		assertTrue(new SqliteQuestionRepository(fixture.database()).findAll().isEmpty());
		assertTrue(new SqliteSharedQuestionContextRepository(fixture.database()).findByBooklet(fixture.booklet())
				.isEmpty());
		assertTrue(service.findPendingMcqSharedContext(fixture.booklet()).isEmpty());
	}

	@Test
	void findStoredSharedContextRejectsConflictingParts() throws Exception {
		Fixture fixture = createFixture("scoped-context-conflict.db");
		SqliteDatabase database = fixture.database();
		SqliteQuestionRepository questions = new SqliteQuestionRepository(database);
		SourceQuestion source = new SqliteSourceQuestionRepository(database).save(fixture.booklet(), "35");
		SqliteSharedQuestionContextRepository contexts = new SqliteSharedQuestionContextRepository(database);
		var firstContext = contexts.save(fixture.booklet(), "First context",
				List.of(new SharedQuestionContextRegion(1, 0.10, 0.10, 0.70, 0.15)));
		var conflictingContext = contexts.save(fixture.booklet(), "Conflicting context",
				List.of(new SharedQuestionContextRegion(1, 0.10, 0.35, 0.70, 0.15)));
		Question first = questions.save(fixture.booklet(), "35a", "", 2, List.of(), fixture.classification(), true,
				source, firstContext, QuestionResponseType.WRITTEN_RESPONSE);
		Question second = questions.save(fixture.booklet(), "35b", "", 2, List.of(), fixture.classification(), true,
				source, null, QuestionResponseType.WRITTEN_RESPONSE);

		// Simulate inconsistent legacy persistence. Normal application
		// capture must not be weakened to permit conflicting links.
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection
						.prepareStatement("UPDATE questions SET shared_context_id = ? WHERE id = ?")) {
			statement.setLong(1, conflictingContext.getId());
			statement.setLong(2, second.getId());
			assertEquals(1, statement.executeUpdate());
		}
		SqliteQuestionCaptureService service = new SqliteQuestionCaptureService(database);
		IllegalStateException failure = assertThrows(IllegalStateException.class,
				() -> service.findStoredSharedContext(source));
		assertTrue(failure.getMessage().contains("inconsistent shared context links"));

		// A read-only inference failure must not repair, replace or
		// otherwise modify either persisted relationship.
		Question reloadedFirst = questions.findById(first.getId()).orElseThrow();
		Question reloadedSecond = questions.findById(second.getId()).orElseThrow();
		assertEquals(firstContext.getId(), reloadedFirst.getSharedContext().getId());
		assertEquals(conflictingContext.getId(), reloadedSecond.getSharedContext().getId());
		assertEquals(source.getId(), reloadedFirst.getSourceQuestion().getId());
		assertEquals(source.getId(), reloadedSecond.getSourceQuestion().getId());
	}

	@Test
	void findStoredSharedContextUsesExactBookletAndFreshPersistence() throws Exception {
		Fixture fixture = createFixture("scoped-context-freshness.db");
		SqliteDatabase database = fixture.database();
		SqliteQuestionRepository questions = new SqliteQuestionRepository(database);
		SqliteSourceQuestionRepository sources = new SqliteSourceQuestionRepository(database);
		SqliteSharedQuestionContextRepository contexts = new SqliteSharedQuestionContextRepository(database);
		SqliteQuestionCaptureService service = new SqliteQuestionCaptureService(database);
		SourceQuestion source = sources.save(fixture.booklet(), "24");
		var firstContext = contexts.save(fixture.booklet(), "Question 24 context",
				List.of(new SharedQuestionContextRegion(1, 0.10, 0.10, 0.70, 0.15)));
		Question firstPart = questions.save(fixture.booklet(), "24a", "", 2, List.of(), fixture.classification(), true,
				source, firstContext, QuestionResponseType.WRITTEN_RESPONSE);
		Question secondPart = questions.save(fixture.booklet(), "24b", "", 2, List.of(), fixture.classification(), true,
				source, null, QuestionResponseType.WRITTEN_RESPONSE);

		// A missing sibling link does not prevent another part's
		// already-persisted Shared Context from being inherited.
		assertEquals(firstContext.getId(), service.findStoredSharedContext(source).orElseThrow().getId());

		// The same source code in another booklet is a different
		// persistent source identity, even within the same Exam.
		SqliteExamWriter exams = new SqliteExamWriter(database);
		var otherDocument = exams.insertSourceDocument("Chemistry/2019/paper2.pdf");
		ExamBooklet otherBooklet = exams.insertExamBooklet(fixture.booklet().getExam(), otherDocument, "Paper 2");
		SourceQuestion otherSource = sources.save(otherBooklet, "24");
		var otherContext = contexts.save(otherBooklet, "Paper 2 Question 24 context",
				List.of(new SharedQuestionContextRegion(1, 0.10, 0.40, 0.70, 0.15)));
		questions.save(otherBooklet, "24a", "", 2, List.of(), fixture.classification(), true, otherSource, otherContext,
				QuestionResponseType.WRITTEN_RESPONSE);
		assertEquals(firstContext.getId(), service.findStoredSharedContext(source).orElseThrow().getId());
		assertEquals(otherContext.getId(), service.findStoredSharedContext(otherSource).orElseThrow().getId());

		// Simulate a persisted edit that removes the first part's
		// context association. No other part currently has one.
		questions.updateCaptureRelationships(firstPart.getId(), fixture.classification(), source, null);
		assertTrue(service.findStoredSharedContext(source).isEmpty());

		// A subsequent committed edit must be visible immediately.
		// No cached corpus or cached previous context may be reused.
		questions.updateCaptureRelationships(secondPart.getId(), fixture.classification(), source, firstContext);
		assertEquals(firstContext.getId(), service.findStoredSharedContext(source).orElseThrow().getId());
		assertEquals(otherContext.getId(), service.findStoredSharedContext(otherSource).orElseThrow().getId());

		// Inference itself must not have modified source relationships.
		assertEquals(source.getId(), questions.findById(firstPart.getId()).orElseThrow().getSourceQuestion().getId());
		assertEquals(source.getId(), questions.findById(secondPart.getId()).orElseThrow().getSourceQuestion().getId());
	}

	@Test
	void importedCaptureCanResolveUnknownResponseType() throws Exception {
		Fixture fixture = createFixture("imported-response-type.db");
		SqliteQuestionRepository repository = new SqliteQuestionRepository(fixture.database());
		Question imported = repository.save(fixture.booklet(), "Q2", "", 2, List.of(), fixture.classification(), false);
		assertEquals(QuestionResponseType.UNKNOWN, imported.getResponseType());
		SqliteQuestionCaptureService service = new SqliteQuestionCaptureService(fixture.database());
		Question captured = service
				.save(new SqliteQuestionCaptureService.Request(SqliteQuestionCaptureService.Operation.IMPORTED,
						fixture.booklet(), imported, imported.getQuestionCode(), imported.getMarks(),
						List.of(new QuestionRegion(fixture.booklet(), 2, 0.10, 0.20, 0.70, 0.20)),
						fixture.classification(), QuestionResponseType.WRITTEN_RESPONSE, null, null));
		assertEquals(QuestionResponseType.WRITTEN_RESPONSE, captured.getResponseType());
		Question reloaded = repository.findById(imported.getId()).orElseThrow();
		assertEquals(QuestionResponseType.WRITTEN_RESPONSE, reloaded.getResponseType());
		assertEquals(1, reloaded.getRegions().size());
	}

	@Test
	void independentMcqSharedContextSurvivesRestartContinuesAndIsConsumed() throws Exception {
		Fixture fixture = createFixture("independent-mcq-context.db");
		SqliteQuestionCaptureService firstService = new SqliteQuestionCaptureService(fixture.database());
		Question questionFive = firstService.save(
				new SqliteQuestionCaptureService.Request(SqliteQuestionCaptureService.Operation.NEW, fixture.booklet(),
						null, "Q5", 1, List.of(new QuestionRegion(fixture.booklet(), 1, 0.10, 0.30, 0.70, 0.12)),
						fixture.classification(), QuestionResponseType.MULTIPLE_CHOICE, null,
						new SqliteQuestionCaptureService.PendingSharedContext("CTX-Q5",
								List.of(new SharedQuestionContextRegion(1, 0.10, 0.10, 0.80, 0.15))),
						true));

		// Independent MCQs share only SharedQuestionContext. They must never acquire
		// multipart SourceQuestion identity merely because their stimulus is shared.
		assertFalse(questionFive.hasSourceQuestion());
		assertTrue(questionFive.hasSharedContext());
		long sharedContextId = questionFive.getSharedContext().getId();

		// Constructing a new service represents reopening the application. The
		// continuation must come from SQLite rather than transient Java state.
		SqliteQuestionCaptureService reopenedService = new SqliteQuestionCaptureService(fixture.database());
		assertEquals(sharedContextId,
				reopenedService.findPendingMcqSharedContext(fixture.booklet()).orElseThrow().getId());
		Question questionSix = reopenedService.save(
				new SqliteQuestionCaptureService.Request(SqliteQuestionCaptureService.Operation.NEW, fixture.booklet(),
						null, "Q6", 1, List.of(new QuestionRegion(fixture.booklet(), 1, 0.10, 0.48, 0.70, 0.12)),
						fixture.classification(), QuestionResponseType.MULTIPLE_CHOICE, null, null, true));

		// Q6 automatically receives Q5's persisted context. Keeping the continuation
		// flag true means that same context must now remain available for Q7.
		assertFalse(questionSix.hasSourceQuestion());
		assertTrue(questionSix.hasSharedContext());
		assertEquals(sharedContextId, questionSix.getSharedContext().getId());
		assertEquals(sharedContextId,
				reopenedService.findPendingMcqSharedContext(fixture.booklet()).orElseThrow().getId());
		SqliteQuestionCaptureService secondReopenedService = new SqliteQuestionCaptureService(fixture.database());
		Question questionSeven = secondReopenedService.save(
				new SqliteQuestionCaptureService.Request(SqliteQuestionCaptureService.Operation.NEW, fixture.booklet(),
						null, "Q7", 1, List.of(new QuestionRegion(fixture.booklet(), 1, 0.10, 0.66, 0.70, 0.12)),
						fixture.classification(), QuestionResponseType.MULTIPLE_CHOICE, null, null, false));

		// Q7 consumes the continuation because it does not ask for the context to
		// continue again.
		assertFalse(questionSeven.hasSourceQuestion());
		assertTrue(questionSeven.hasSharedContext());
		assertEquals(sharedContextId, questionSeven.getSharedContext().getId());
		assertTrue(secondReopenedService.findPendingMcqSharedContext(fixture.booklet()).isEmpty());
		Question questionEight = secondReopenedService.save(
				new SqliteQuestionCaptureService.Request(SqliteQuestionCaptureService.Operation.NEW, fixture.booklet(),
						null, "Q8", 1, List.of(new QuestionRegion(fixture.booklet(), 1, 0.10, 0.82, 0.70, 0.10)),
						fixture.classification(), QuestionResponseType.MULTIPLE_CHOICE, null, null, false));

		// Once consumed, the old context must not leak into later independent MCQs.
		assertFalse(questionEight.hasSourceQuestion());
		assertFalse(questionEight.hasSharedContext());
	}

	@Test
	void newCapturePersistsExplicitResponseType() throws Exception {
		Fixture fixture = createFixture("new-response-type.db");
		SqliteQuestionCaptureService service = new SqliteQuestionCaptureService(fixture.database());
		SqliteQuestionCaptureService.Request request = new SqliteQuestionCaptureService.Request(
				SqliteQuestionCaptureService.Operation.NEW, fixture.booklet(), null, "Q1", 1,
				List.of(new QuestionRegion(fixture.booklet(), 1, 0.10, 0.10, 0.70, 0.20)), fixture.classification(),
				QuestionResponseType.MULTIPLE_CHOICE, null, null);
		Question saved = service.save(request);
		assertEquals(QuestionResponseType.MULTIPLE_CHOICE, saved.getResponseType());
		Question reloaded = new SqliteQuestionRepository(fixture.database()).findById(saved.getId()).orElseThrow();
		assertEquals(QuestionResponseType.MULTIPLE_CHOICE, reloaded.getResponseType());
	}

	@Test
	void newCapturePersistsMixedImageAndPdfContentInOrder() throws Exception {
		Fixture fixture = createFixture("mixed-question-capture.db");
		QuestionRegion region = new QuestionRegion(fixture.booklet(), 2, 0.10, 0.20, 0.70, 0.20);
		byte[] pngBytes = createTestPng();
		List<QuestionContentPart> contentParts = List.of(new ImageQuestionContentPart(pngBytes),
				new PdfQuestionContentPart(region));
		SqliteQuestionCaptureService service = new SqliteQuestionCaptureService(fixture.database());
		Question saved = service.save(SqliteQuestionCaptureService.Request.withContent(
				SqliteQuestionCaptureService.Operation.NEW, fixture.booklet(), null, "Q9", 2, contentParts,
				fixture.classification(), QuestionResponseType.WRITTEN_RESPONSE, null, null, false));
		Question reloaded = new SqliteQuestionRepository(fixture.database()).findById(saved.getId()).orElseThrow();
		assertEquals(2, reloaded.getContentParts().size());
		assertTrue(reloaded.getContentParts().get(0) instanceof ImageQuestionContentPart);
		assertTrue(reloaded.getContentParts().get(1) instanceof PdfQuestionContentPart);
		ImageQuestionContentPart imagePart = (ImageQuestionContentPart) reloaded.getContentParts().get(0);
		assertArrayEquals(pngBytes, imagePart.pngBytes());
		assertEquals(1, reloaded.getRegions().size());
		assertEquals(2, reloaded.getRegions().getFirst().pageNumber());
	}

	@Test
	void pendingIndependentMcqContextDoesNotLeakToAnotherBooklet() throws Exception {
		Fixture fixture = createFixture("independent-mcq-booklet-scope.db");
		SqliteQuestionCaptureService service = new SqliteQuestionCaptureService(fixture.database());
		Question questionFive = service.save(
				new SqliteQuestionCaptureService.Request(SqliteQuestionCaptureService.Operation.NEW, fixture.booklet(),
						null, "Q5", 1, List.of(new QuestionRegion(fixture.booklet(), 1, 0.10, 0.30, 0.70, 0.12)),
						fixture.classification(), QuestionResponseType.MULTIPLE_CHOICE, null,
						new SqliteQuestionCaptureService.PendingSharedContext("CTX-Q5",
								List.of(new SharedQuestionContextRegion(1, 0.10, 0.10, 0.80, 0.15))),
						true));
		long sharedContextId = questionFive.getSharedContext().getId();
		SqliteExamWriter examWriter = new SqliteExamWriter(fixture.database());
		var otherSourceDocument = examWriter.insertSourceDocument("Chemistry/2019/paper2.pdf");
		ExamBooklet otherBooklet = examWriter.insertExamBooklet(fixture.booklet().getExam(), otherSourceDocument,
				"Paper 2");

		// Continuation belongs to the original booklet, not merely to the same Exam.
		assertTrue(service.findPendingMcqSharedContext(otherBooklet).isEmpty());
		Question otherBookletQuestion = service
				.save(new SqliteQuestionCaptureService.Request(SqliteQuestionCaptureService.Operation.NEW, otherBooklet,
						null, "Q1", 1, List.of(new QuestionRegion(otherBooklet, 1, 0.10, 0.30, 0.70, 0.12)),
						fixture.classification(), QuestionResponseType.MULTIPLE_CHOICE, null, null, false));
		assertFalse(otherBookletQuestion.hasSharedContext());

		// Work in another booklet must not consume the original booklet's unfinished
		// MCQ context chain.
		assertEquals(sharedContextId, service.findPendingMcqSharedContext(fixture.booklet()).orElseThrow().getId());
	}

	@Test
	void pendingMcqContextWaitsForImmediateSuccessorWhenCaptureIsOutOfSequence() throws Exception {
		Fixture fixture = createFixture("mcq-context-sequence.db");
		SqliteQuestionCaptureService service = new SqliteQuestionCaptureService(fixture.database());
		Question questionFive = service.save(
				new SqliteQuestionCaptureService.Request(SqliteQuestionCaptureService.Operation.NEW, fixture.booklet(),
						null, "Q5", 1, List.of(new QuestionRegion(fixture.booklet(), 1, 0.10, 0.30, 0.70, 0.12)),
						fixture.classification(), QuestionResponseType.MULTIPLE_CHOICE, null,
						new SqliteQuestionCaptureService.PendingSharedContext("CTX-Q5",
								List.of(new SharedQuestionContextRegion(1, 0.10, 0.10, 0.80, 0.15))),
						true));
		long contextId = questionFive.getSharedContext().getId();

		// Q7 is not the immediate successor of Q5. It must neither inherit nor consume
		// the continuation that is waiting specifically for Q6.
		Question questionSeven = service.save(
				new SqliteQuestionCaptureService.Request(SqliteQuestionCaptureService.Operation.NEW, fixture.booklet(),
						null, "Q7", 1, List.of(new QuestionRegion(fixture.booklet(), 1, 0.10, 0.60, 0.70, 0.12)),
						fixture.classification(), QuestionResponseType.MULTIPLE_CHOICE, null, null, false));
		assertFalse(questionSeven.hasSharedContext());
		assertEquals(contextId, service.findPendingMcqSharedContext(fixture.booklet()).orElseThrow().getId());
		assertTrue(service.findPendingMcqSharedContextForQuestion(fixture.booklet(), "Q7").isEmpty());
		assertEquals(contextId,
				service.findPendingMcqSharedContextForQuestion(fixture.booklet(), "Q6").orElseThrow().getId());

		// Capturing Q6 later receives the original Q5 context and consumes the pending
		// continuation because Q6 does not extend it further.
		Question questionSix = service.save(
				new SqliteQuestionCaptureService.Request(SqliteQuestionCaptureService.Operation.NEW, fixture.booklet(),
						null, "Q6", 1, List.of(new QuestionRegion(fixture.booklet(), 1, 0.10, 0.45, 0.70, 0.12)),
						fixture.classification(), QuestionResponseType.MULTIPLE_CHOICE, null, null, false));
		assertTrue(questionSix.hasSharedContext());
		assertEquals(contextId, questionSix.getSharedContext().getId());
		assertTrue(service.findPendingMcqSharedContext(fixture.booklet()).isEmpty());
	}

	@Test
	void rejectsMultipleChoiceCaptureWithMoreThanOneMark() throws Exception {
		Fixture fixture = createFixture("reject-multi-mark-mcq.db");
		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> new SqliteQuestionCaptureService.Request(SqliteQuestionCaptureService.Operation.NEW,
						fixture.booklet(), null, "Q1", 2,
						List.of(new QuestionRegion(fixture.booklet(), 1, 0.10, 0.10, 0.70, 0.20)),
						fixture.classification(), QuestionResponseType.MULTIPLE_CHOICE, null, null));

		// Capture metadata cannot represent an MCQ with a mark value other than one.
		assertEquals("Multiple-choice questions must be worth exactly 1 mark", error.getMessage());
		assertTrue(new SqliteQuestionRepository(fixture.database()).findAll().isEmpty());
	}

	@Test
	void scopedDuplicateCodeLookupExcludesOnlySelectedQuestion() throws Exception {

		Fixture fixture = createFixture("scoped-code-edit.db");

		SqliteQuestionRepository repository = new SqliteQuestionRepository(fixture.database());

		SqliteQuestionCaptureService service = new SqliteQuestionCaptureService(fixture.database());

		Question first = repository.save(fixture.booklet(), "Q1", "", 1, List.of(), fixture.classification(), false);

		Question second = repository.save(fixture.booklet(), "Q2", "", 1, List.of(), fixture.classification(), false);

		assertFalse(service.hasConflictingQuestionCode(fixture.booklet(), "Q1", first.getId()));

		assertTrue(service.hasConflictingQuestionCode(fixture.booklet(), "Q2", first.getId()));

		assertTrue(service.hasConflictingQuestionCode(fixture.booklet(), "Q1", second.getId()));

		assertFalse(service.hasConflictingQuestionCode(fixture.booklet(), "Q2", second.getId()));
	}

	@Test
	void scopedDuplicateCodeLookupReadsCurrentStoredState() throws Exception {

		Fixture fixture = createFixture("scoped-code-check.db");

		SqliteQuestionCaptureService service = new SqliteQuestionCaptureService(fixture.database());

		SqliteQuestionRepository repository = new SqliteQuestionRepository(fixture.database());

		assertFalse(service.hasConflictingQuestionCode(fixture.booklet(), "Q7", null));

		Question saved = repository.save(fixture.booklet(), "Q7", "", 1, List.of(), fixture.classification(), false);

		// The query must observe persistence performed after the
		// preceding check rather than relying on a cached result.
		assertTrue(service.hasConflictingQuestionCode(fixture.booklet(), "Q7", null));

		// An edit may retain its own code.
		assertFalse(service.hasConflictingQuestionCode(fixture.booklet(), "Q7", saved.getId()));

		assertFalse(service.hasConflictingQuestionCode(fixture.booklet(), "Q8", null));
	}

	@Test
	void scopedDuplicateCodeLookupRejectsInvalidArguments() throws Exception {

		Fixture fixture = createFixture("scoped-code-arguments.db");

		SqliteQuestionCaptureService service = new SqliteQuestionCaptureService(fixture.database());

		assertThrows(NullPointerException.class, () -> service.hasConflictingQuestionCode(null, "Q1", null));

		assertThrows(NullPointerException.class,
				() -> service.hasConflictingQuestionCode(fixture.booklet(), null, null));

		assertThrows(IllegalArgumentException.class,
				() -> service.hasConflictingQuestionCode(fixture.booklet(), " ", null));
	}

	private Fixture createFixture(String databaseName) throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve(databaseName));
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SyllabusVersion syllabus = curriculumWriter.insertSyllabusVersion(chemistry, "2019", false);
		Unit unit = curriculumWriter.insertUnit(syllabus, "1", "Unit 1", 1);
		Topic topic = curriculumWriter.insertTopic(unit, "1.1", "Topic 1", 1);
		Subtopic classification = curriculumWriter.insertSubtopic(topic, "1.1.1", "Subtopic 1", 1);
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		ExamBooklet booklet = new SqliteExamImporter(database, examWriter).importExam(chemistry, "QCAA", 2019,
				"External Assessment", "Paper 1", "Chemistry/2019/paper1.pdf");
		return new Fixture(database, booklet, classification);
	}

	private byte[] createTestPng() throws Exception {
		BufferedImage image = new BufferedImage(8, 4, BufferedImage.TYPE_INT_RGB);
		try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			assertTrue(ImageIO.write(image, "png", output));
			return output.toByteArray();
		}
	}

	private record Fixture(SqliteDatabase database, ExamBooklet booklet, Subtopic classification) {
	}
}
