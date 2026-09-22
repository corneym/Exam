package au.edu.eq.questionbank.repository.assessment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.Question;
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
	void editingLastMultipartPartToNonMultipartRemovesUnreferencedSourceQuestionButRetainsContext() throws Exception {
		Fixture fixture = createFixture("edit-last-part-to-non-multipart.db");
		SqliteSourceQuestionRepository sourceRepository = new SqliteSourceQuestionRepository(fixture.database());
		SourceQuestion sourceQuestion = sourceRepository.save(fixture.booklet(), "24");
		sourceQuestion = sourceRepository.updatePreambleStatus(sourceQuestion, SharedContextStatus.PRESENT);
		SqliteSharedQuestionContextRepository sharedContextRepository = new SqliteSharedQuestionContextRepository(
				fixture.database());
		var sharedContext = sharedContextRepository.save(fixture.booklet(), "Question 24 preamble",
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
		sourceQuestion = sourceRepository.updatePreambleStatus(sourceQuestion, SharedContextStatus.PRESENT);
		SqliteSharedQuestionContextRepository sharedContextRepository = new SqliteSharedQuestionContextRepository(
				fixture.database());
		var sharedContext = sharedContextRepository.save(fixture.booklet(), "Question 24 preamble",
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
	void failedImportedCaptureRollsBackContextPropagationAndPreambleStatus() throws Exception {
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
				fixture.classification(), null, new SqliteQuestionCaptureService.PendingSharedContext(
						"Question 24 preamble", List.of(new SharedQuestionContextRegion(1, 0.10, 0.10, 0.80, 0.15))));
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
				null, new SqliteQuestionCaptureService.PendingSharedContext("Question 25 preamble",
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

	private record Fixture(SqliteDatabase database, ExamBooklet booklet, Subtopic classification) {
	}
}
