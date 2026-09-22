package au.edu.eq.questionbank.repository.assessment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.SharedContextStatus;
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
import au.edu.eq.questionbank.repository.assessment.LegacyQuestionSplitService.NewSharedContext;
import au.edu.eq.questionbank.repository.assessment.LegacyQuestionSplitService.SplitPart;
import au.edu.eq.questionbank.repository.assessment.LegacyQuestionSplitService.SplitRequest;
import au.edu.eq.questionbank.repository.assessment.LegacyQuestionSplitService.SplitResult;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class LegacyQuestionSplitServiceTest {

	@TempDir
	Path tempDirectory;
	private SqliteDatabase database;
	private SqliteQuestionRepository questionRepository;
	private LegacyQuestionSplitService splitService;
	private ExamBooklet booklet;
	private Subtopic subtopicOne;
	private Subtopic subtopicTwo;

	@Test
	void failedAdditionalPartInsertRollsBackEntireSplit() throws Exception {
		Question original = questionRepository.save(booklet, "3", "", 5,
				List.of(new QuestionRegion(booklet, 1, 0.10, 0.10, 0.80, 0.60)), subtopicOne, true, null, null,
				QuestionResponseType.WRITTEN_RESPONSE);

		// Fail only when the second resulting Question is inserted. By this point the
		// transaction has already created the SourceQuestion and updated the retained
		// original row, so successful restoration proves transaction-wide rollback.
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					CREATE TRIGGER reject_split_second_part
					BEFORE INSERT ON questions
					WHEN NEW.question_code = '3b'
					BEGIN
					    SELECT RAISE(ABORT, 'reject split second part');
					END
					""");
		}
		SplitPart partA = new SplitPart("3a", 2, subtopicOne, QuestionResponseType.WRITTEN_RESPONSE,
				List.of(new QuestionRegion(booklet, 1, 0.10, 0.15, 0.80, 0.20)));

		// Keep the second part valid so this regression reaches the deliberately
		// failing SQLite trigger that is meant to test transaction rollback.
		// Keep the split definition valid so the deliberately failing SQLite trigger
		// remains the condition that exercises transaction-wide rollback.
		SplitPart partB = new SplitPart("3b", 3, subtopicTwo, QuestionResponseType.WRITTEN_RESPONSE,
				List.of(new QuestionRegion(booklet, 2, 0.10, 0.20, 0.80, 0.25)));
		assertThrows(IllegalStateException.class,
				() -> splitService.split(new SplitRequest(original, "3", List.of(partA, partB), 0)));
		SqliteQuestionRepository reloadedRepository = new SqliteQuestionRepository(database);
		Question reloadedOriginal = reloadedRepository.findById(original.getId()).orElseThrow();

		// The original row update must have rolled back completely.
		assertEquals("3", reloadedOriginal.getQuestionCode());
		assertEquals(5, reloadedOriginal.getMarks());
		assertEquals(subtopicOne.getId(), reloadedOriginal.getClassification().getId());
		assertEquals(QuestionResponseType.WRITTEN_RESPONSE, reloadedOriginal.getResponseType());
		assertTrue(reloadedOriginal.isSharedContextCaptureRequired());
		assertFalse(reloadedOriginal.hasSourceQuestion());
		assertFalse(reloadedOriginal.hasSharedContext());

		// The original source capture must also remain intact rather than retaining
		// the replacement region intended for part 3a.
		assertEquals(1, reloadedOriginal.getRegions().size());
		QuestionRegion restoredRegion = reloadedOriginal.getRegions().getFirst();
		assertEquals(1, restoredRegion.pageNumber());
		assertEquals(0.10, restoredRegion.x());
		assertEquals(0.10, restoredRegion.y());
		assertEquals(0.80, restoredRegion.width());
		assertEquals(0.60, restoredRegion.height());

		// Neither the failed additional part nor the temporary multipart identity may
		// survive a failed split transaction.
		assertEquals(1, reloadedRepository.findAll().size());
		assertTrue(new SqliteSourceQuestionRepository(database).findByBooklet(booklet).isEmpty());
	}

	@Test
	void failedSplitRollsBackNewSharedPreambleAndSourceQuestion() throws Exception {
		Question original = questionRepository.save(booklet, "3", "", 5,
				List.of(new QuestionRegion(booklet, 1, 0.10, 0.10, 0.80, 0.60)), subtopicOne, true, null, null,
				QuestionResponseType.WRITTEN_RESPONSE);

		// Reject the second resulting Question after the transaction has already
		// created the SourceQuestion, SharedQuestionContext and retained part update.
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					CREATE TRIGGER reject_shared_split_second_part
					BEFORE INSERT ON questions
					WHEN NEW.question_code = '3b'
					BEGIN
					    SELECT RAISE(ABORT, 'reject shared split second part');
					END
					""");
		}
		SplitPart partA = new SplitPart("3a", 2, subtopicOne, QuestionResponseType.WRITTEN_RESPONSE,
				List.of(new QuestionRegion(booklet, 1, 0.10, 0.35, 0.80, 0.20)));
		SplitPart partB = new SplitPart("3b", 3, subtopicTwo, QuestionResponseType.WRITTEN_RESPONSE,
				List.of(new QuestionRegion(booklet, 2, 0.10, 0.20, 0.80, 0.25)));
		NewSharedContext newSharedContext = new NewSharedContext(
				List.of(new SharedQuestionContextRegion(1, 0.10, 0.10, 0.80, 0.15)));
		assertThrows(IllegalStateException.class,
				() -> splitService.split(new SplitRequest(original, "3", List.of(partA, partB), 0, newSharedContext)));
		Question reloadedOriginal = new SqliteQuestionRepository(database).findById(original.getId()).orElseThrow();

		// The retained original row must be completely restored.
		assertEquals("3", reloadedOriginal.getQuestionCode());
		assertEquals(5, reloadedOriginal.getMarks());
		assertTrue(reloadedOriginal.isSharedContextCaptureRequired());
		assertFalse(reloadedOriginal.hasSourceQuestion());
		assertFalse(reloadedOriginal.hasSharedContext());
		assertEquals(1, reloadedOriginal.getRegions().size());
		assertEquals(0.10, reloadedOriginal.getRegions().getFirst().y());

		// Neither multipart identity nor shared-preamble state may survive the
		// rollback.
		assertTrue(new SqliteSourceQuestionRepository(database).findByBooklet(booklet).isEmpty());
		assertTrue(new SqliteSharedQuestionContextRepository(database).findByBooklet(booklet).isEmpty());
		assertEquals(1, new SqliteQuestionRepository(database).findAll().size());

		// Verify directly that the dependent shared-context region row also rolled
		// back rather than becoming an orphan.
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				var result = statement.executeQuery("""
						SELECT COUNT(*) AS region_count
						FROM shared_question_context_regions
						""")) {
			assertTrue(result.next());
			assertEquals(0, result.getInt("region_count"));
		}
	}

	@BeforeEach
	void setUp() throws Exception {
		database = new SqliteDatabase(tempDirectory.resolve("legacy-question-split.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SyllabusVersion syllabus = curriculumWriter.insertSyllabusVersion(chemistry, "2019", false);
		Unit unit = curriculumWriter.insertUnit(syllabus, "1", "Unit 1", 1);
		Topic topic = curriculumWriter.insertTopic(unit, "1.1", "Topic 1", 1);
		subtopicOne = curriculumWriter.insertSubtopic(topic, "1.1.1", "Subtopic 1", 1);
		subtopicTwo = curriculumWriter.insertSubtopic(topic, "1.1.2", "Subtopic 2", 2);
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		booklet = new SqliteExamImporter(database, examWriter).importExam(chemistry, "QCAA", 2019,
				"External Assessment", "Paper 1", "Chemistry/2019/paper1.pdf");
		questionRepository = new SqliteQuestionRepository(database);
		splitService = new LegacyQuestionSplitService(database);
	}

	@Test
	void splitCreatesAndSharesNewPreambleAtomically() {
		Question original = questionRepository.save(booklet, "3", "", 5,
				List.of(new QuestionRegion(booklet, 1, 0.10, 0.10, 0.80, 0.60)), subtopicOne, true, null, null,
				QuestionResponseType.WRITTEN_RESPONSE);
		SplitPart partA = new SplitPart("3a", 2, subtopicOne, QuestionResponseType.WRITTEN_RESPONSE,
				List.of(new QuestionRegion(booklet, 1, 0.10, 0.35, 0.80, 0.20)));
		SplitPart partB = new SplitPart("3b", 3, subtopicTwo, QuestionResponseType.WRITTEN_RESPONSE,
				List.of(new QuestionRegion(booklet, 2, 0.10, 0.20, 0.80, 0.25)));
		NewSharedContext newSharedContext = new NewSharedContext(
				List.of(new SharedQuestionContextRegion(1, 0.10, 0.10, 0.80, 0.15)));
		SplitResult result = splitService
				.split(new SplitRequest(original, "3", List.of(partA, partB), 0, newSharedContext));
		assertEquals(SharedContextStatus.PRESENT, result.sourceQuestion().getSharedContextStatus());
		assertNotNull(result.sharedContext());
		assertEquals("Question 3 preamble", result.sharedContext().getLabel());
		assertEquals(1, result.sharedContext().getRegions().size());
		Question resultingA = result.questions().get(0);
		Question resultingB = result.questions().get(1);

		// Every resulting part must use both the same SourceQuestion identity and the
		// same newly persisted SharedQuestionContext identity.
		assertEquals(result.sourceQuestion().getId(), resultingA.getSourceQuestion().getId());
		assertEquals(result.sourceQuestion().getId(), resultingB.getSourceQuestion().getId());
		assertTrue(resultingA.hasSharedContext());
		assertTrue(resultingB.hasSharedContext());
		assertEquals(result.sharedContext().getId(), resultingA.getSharedContext().getId());
		assertEquals(result.sharedContext().getId(), resultingB.getSharedContext().getId());

		// The old legacy hint is no longer the authority after an explicit split. The
		// SourceQuestion and SharedQuestionContext now represent preamble semantics.
		assertFalse(resultingA.isSharedContextCaptureRequired());
		assertFalse(resultingB.isSharedContextCaptureRequired());
		Question reloadedA = new SqliteQuestionRepository(database).findById(resultingA.getId()).orElseThrow();
		Question reloadedB = new SqliteQuestionRepository(database).findById(resultingB.getId()).orElseThrow();

		// Reload proves the shared identity and preamble are persisted rather than
		// merely attached to the returned objects.
		assertEquals(SharedContextStatus.PRESENT, reloadedA.getSourceQuestion().getSharedContextStatus());
		assertEquals(reloadedA.getSourceQuestion().getId(), reloadedB.getSourceQuestion().getId());
		assertEquals(reloadedA.getSharedContext().getId(), reloadedB.getSharedContext().getId());
		assertEquals(1, new SqliteSharedQuestionContextRepository(database).findByBooklet(booklet).size());
	}

	@Test
	void splitKeepsExistingAnswerWithExplicitlyRetainedPart() throws Exception {
		Question original = questionRepository.save(booklet, "3", "", 5,
				List.of(new QuestionRegion(booklet, 1, 0.10, 0.10, 0.80, 0.60)), subtopicOne, true, null, null,
				QuestionResponseType.WRITTEN_RESPONSE);
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		SqliteAnswerWriter answerWriter = new SqliteAnswerWriter(database, examWriter);

		// A text-only Answer is sufficient for this ownership regression. Its
		// persistent identity must remain attached to whichever split part reuses the
		// original Question row.
		var originalAnswer = answerWriter.insertAnswer(original, "Original combined answer", List.of());

		// MCQ metadata is incidental to this Answer-ownership test and must satisfy the
		// production one-mark invariant.
		SplitPart partA = new SplitPart("3a", 2, subtopicOne, QuestionResponseType.WRITTEN_RESPONSE,
				List.of(new QuestionRegion(booklet, 1, 0.10, 0.20, 0.80, 0.20)));
		SplitPart partB = new SplitPart("3b", 3, subtopicTwo, QuestionResponseType.WRITTEN_RESPONSE,
				List.of(new QuestionRegion(booklet, 2, 0.10, 0.20, 0.80, 0.25)));
		SplitResult result = splitService.split(new SplitRequest(original, "3", List.of(partA, partB),

				// Part 3b explicitly receives the original Question row and
				// therefore retains the existing Answer.
				1));
		Question resultingA = result.questions().get(0);
		Question resultingB = result.questions().get(1);

		// The retained part is selected explicitly rather than inferred from part
		// order or Question code.
		assertNotEquals(original.getId(), resultingA.getId());
		assertEquals(original.getId(), resultingB.getId());
		assertEquals("3a", resultingA.getQuestionCode());
		assertEquals("3b", resultingB.getQuestionCode());

		// The new Question row must not receive a copied Answer.
		assertFalse(resultingA.hasAnswer());

		// The existing Answer remains attached to the reused Question identity.
		assertTrue(resultingB.hasAnswer());
		assertEquals(originalAnswer.getId(), resultingB.getAnswer().getId());
		assertEquals("Original combined answer", resultingB.getAnswer().getAnswerText());
		Question reloadedA = new SqliteQuestionRepository(database).findById(resultingA.getId()).orElseThrow();
		Question reloadedB = new SqliteQuestionRepository(database).findById(resultingB.getId()).orElseThrow();

		// Reload verifies that Answer ownership is represented by the persisted
		// Question identity rather than only by returned in-memory objects.
		assertFalse(reloadedA.hasAnswer());
		assertTrue(reloadedB.hasAnswer());
		assertEquals(originalAnswer.getId(), reloadedB.getAnswer().getId());
		assertEquals("Original combined answer", reloadedB.getAnswer().getAnswerText());

		// There must still be exactly one Answer row after the split.
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				var resultSet = statement.executeQuery("""
						SELECT COUNT(*) AS answer_count
						FROM answers
						""")) {
			assertTrue(resultSet.next());
			assertEquals(1, resultSet.getInt("answer_count"));
		}
	}

	@Test
	void splitPartRejectsMultipleChoiceResponseType() {

		// Legacy splitting reconstructs multipart written-response Questions only.
		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
				() -> new SplitPart("3a", 1, subtopicOne, QuestionResponseType.MULTIPLE_CHOICE,
						List.of(new QuestionRegion(booklet, 1, 0.10, 0.20, 0.80, 0.20))));
		assertTrue(failure.getMessage().contains("must be written response"));
	}

	@Test
	void splitRejectsExistingDestinationQuestionWithoutChangingOriginal() {
		Question original = questionRepository.save(booklet, "3", "", 5,
				List.of(new QuestionRegion(booklet, 1, 0.10, 0.10, 0.80, 0.60)), subtopicOne, true, null, null,
				QuestionResponseType.WRITTEN_RESPONSE);

		// Simulate an already-persisted Question whose natural identity would collide
		// with one of the requested split destinations.
		Question existingPart = questionRepository.save(booklet, "3b", "", 1,
				List.of(new QuestionRegion(booklet, 2, 0.10, 0.70, 0.80, 0.15)), subtopicTwo, false, null, null,
				QuestionResponseType.WRITTEN_RESPONSE);
		SplitPart partA = new SplitPart("3a", 2, subtopicOne, QuestionResponseType.WRITTEN_RESPONSE,
				List.of(new QuestionRegion(booklet, 1, 0.10, 0.15, 0.80, 0.20)));
		SplitPart partB = new SplitPart("3b", 3, subtopicTwo, QuestionResponseType.WRITTEN_RESPONSE,
				List.of(new QuestionRegion(booklet, 2, 0.10, 0.20, 0.80, 0.25)));
		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
				() -> splitService.split(new SplitRequest(original, "3", List.of(partA, partB), 0)));
		assertTrue(failure.getMessage().contains("3b"));
		SqliteQuestionRepository reloadedRepository = new SqliteQuestionRepository(database);
		Question reloadedOriginal = reloadedRepository.findById(original.getId()).orElseThrow();
		Question reloadedExistingPart = reloadedRepository.findById(existingPart.getId()).orElseThrow();

		// Collision detection must occur without converting the legacy Question.
		assertEquals("3", reloadedOriginal.getQuestionCode());
		assertEquals(5, reloadedOriginal.getMarks());
		assertTrue(reloadedOriginal.isSharedContextCaptureRequired());
		assertFalse(reloadedOriginal.hasSourceQuestion());
		assertEquals(1, reloadedOriginal.getRegions().size());

		// The pre-existing conflicting Question must also remain untouched.
		assertEquals("3b", reloadedExistingPart.getQuestionCode());
		assertEquals(1, reloadedExistingPart.getMarks());
		assertFalse(reloadedExistingPart.hasSourceQuestion());

		// Rejection must not leave behind a SourceQuestion created for the attempted
		// split.
		assertTrue(new SqliteSourceQuestionRepository(database).findByBooklet(booklet).isEmpty());
		assertEquals(2, reloadedRepository.findAll().size());
	}

	@Test
	void splitRejectsExistingSourceQuestionWithUnresolvedPreambleStatus() {
		SqliteSourceQuestionRepository sourceRepository = new SqliteSourceQuestionRepository(database);

		// A newly persisted SourceQuestion has UNKNOWN preamble status. The split
		// must not silently reinterpret that unresolved state as no preamble.
		SourceQuestion existingSourceQuestion = sourceRepository.save(booklet, "3");
		Question existingSibling = questionRepository.save(booklet, "3c", "", 1,
				List.of(new QuestionRegion(booklet, 2, 0.10, 0.65, 0.80, 0.15)), subtopicOne, false,
				existingSourceQuestion, null, QuestionResponseType.WRITTEN_RESPONSE);
		Question original = questionRepository.save(booklet, "3", "", 5,
				List.of(new QuestionRegion(booklet, 1, 0.10, 0.10, 0.80, 0.50)), subtopicOne, true, null, null,
				QuestionResponseType.WRITTEN_RESPONSE);
		SplitPart partA = new SplitPart("3a", 2, subtopicOne, QuestionResponseType.WRITTEN_RESPONSE,
				List.of(new QuestionRegion(booklet, 1, 0.10, 0.15, 0.80, 0.20)));

		// Keep the destination metadata valid so unresolved preamble status remains the
		// reason this split is rejected.
		SplitPart partB = new SplitPart("3b", 3, subtopicTwo, QuestionResponseType.WRITTEN_RESPONSE,
				List.of(new QuestionRegion(booklet, 2, 0.10, 0.20, 0.80, 0.25)));
		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
				() -> splitService.split(new SplitRequest(original, "3", List.of(partA, partB), 0)));
		assertTrue(failure.getMessage().contains("compatible no-preamble status"));
		Question reloadedOriginal = questionRepository.findById(original.getId()).orElseThrow();
		Question reloadedSibling = questionRepository.findById(existingSibling.getId()).orElseThrow();

		// The failed attempt must not convert either the original Question or the
		// unresolved existing source group.
		assertEquals("3", reloadedOriginal.getQuestionCode());
		assertFalse(reloadedOriginal.hasSourceQuestion());
		assertTrue(reloadedOriginal.isSharedContextCaptureRequired());
		assertEquals(existingSourceQuestion.getId(), reloadedSibling.getSourceQuestion().getId());
		assertEquals(SharedContextStatus.UNKNOWN,
				sourceRepository.findByBookletAndCode(booklet, "3").orElseThrow().getSharedContextStatus());
		assertEquals(2, questionRepository.findAll().size());
	}

	@Test
	void splitRejectsNoPreambleSourceGroupWhoseMembersUseSharedContext() {
		SqliteSourceQuestionRepository sourceRepository = new SqliteSourceQuestionRepository(database);
		SourceQuestion existingSourceQuestion = sourceRepository.save(booklet, "3");
		existingSourceQuestion = sourceRepository.updatePreambleStatus(existingSourceQuestion,
				SharedContextStatus.NONE);
		SqliteSharedQuestionContextRepository contextRepository = new SqliteSharedQuestionContextRepository(database);
		SharedQuestionContext sharedContext = contextRepository.save(booklet, "Existing shared material",
				List.of(new SharedQuestionContextRegion(2, 0.10, 0.10, 0.80, 0.20)));

		// Deliberately construct inconsistent persisted semantics: the SourceQuestion
		// says NONE while an existing member is linked to shared context. The split
		// service must detect this rather than adding further Questions to the group.
		Question existingSibling = questionRepository.save(booklet, "3c", "", 1,
				List.of(new QuestionRegion(booklet, 2, 0.10, 0.65, 0.80, 0.15)), subtopicOne, false,
				existingSourceQuestion, sharedContext, QuestionResponseType.WRITTEN_RESPONSE);
		Question original = questionRepository.save(booklet, "3", "", 5,
				List.of(new QuestionRegion(booklet, 1, 0.10, 0.10, 0.80, 0.50)), subtopicOne, true, null, null,
				QuestionResponseType.WRITTEN_RESPONSE);
		SplitPart partA = new SplitPart("3a", 2, subtopicOne, QuestionResponseType.WRITTEN_RESPONSE,
				List.of(new QuestionRegion(booklet, 1, 0.10, 0.15, 0.80, 0.20)));

		// Keep the requested MCQ valid so the inconsistent shared-context relationship
		// remains the behaviour under test.
		SplitPart partB = new SplitPart("3b", 3, subtopicTwo, QuestionResponseType.WRITTEN_RESPONSE,
				List.of(new QuestionRegion(booklet, 2, 0.10, 0.20, 0.80, 0.25)));
		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
				() -> splitService.split(new SplitRequest(original, "3", List.of(partA, partB), 0)));
		assertTrue(failure.getMessage().contains("already uses shared context"));
		Question reloadedOriginal = questionRepository.findById(original.getId()).orElseThrow();
		Question reloadedSibling = questionRepository.findById(existingSibling.getId()).orElseThrow();

		// Rejection must leave both the unsplit legacy Question and the existing
		// inconsistent group exactly as they were.
		assertEquals("3", reloadedOriginal.getQuestionCode());
		assertFalse(reloadedOriginal.hasSourceQuestion());
		assertTrue(reloadedOriginal.isSharedContextCaptureRequired());
		assertEquals(existingSourceQuestion.getId(), reloadedSibling.getSourceQuestion().getId());
		assertTrue(reloadedSibling.hasSharedContext());
		assertEquals(sharedContext.getId(), reloadedSibling.getSharedContext().getId());
		assertEquals(2, questionRepository.findAll().size());
		assertEquals(1, contextRepository.findByBooklet(booklet).size());
	}

	@Test
	void splitReusesCompatibleExistingSourceQuestion() {
		SqliteSourceQuestionRepository sourceRepository = new SqliteSourceQuestionRepository(database);
		SourceQuestion existingSourceQuestion = sourceRepository.save(booklet, "3");
		existingSourceQuestion = sourceRepository.updatePreambleStatus(existingSourceQuestion,
				SharedContextStatus.NONE);

		// An existing sibling establishes that SourceQuestion 3 is already a real
		// multipart group. It deliberately has no shared context.
		Question existingSibling = questionRepository.save(booklet, "3c", "", 1,
				List.of(new QuestionRegion(booklet, 2, 0.10, 0.65, 0.80, 0.15)), subtopicOne, false,
				existingSourceQuestion, null, QuestionResponseType.WRITTEN_RESPONSE);
		Question original = questionRepository.save(booklet, "3", "", 5,
				List.of(new QuestionRegion(booklet, 1, 0.10, 0.10, 0.80, 0.50)), subtopicOne, true, null, null,
				QuestionResponseType.WRITTEN_RESPONSE);
		SplitPart partA = new SplitPart("3a", 2, subtopicOne, QuestionResponseType.WRITTEN_RESPONSE,
				List.of(new QuestionRegion(booklet, 1, 0.10, 0.15, 0.80, 0.20)));

		// A reused multipart source can contain an MCQ part, but that part is
		// necessarily worth exactly one mark.
		SplitPart partB = new SplitPart("3b", 3, subtopicTwo, QuestionResponseType.WRITTEN_RESPONSE,
				List.of(new QuestionRegion(booklet, 2, 0.10, 0.20, 0.80, 0.25)));
		SplitResult result = splitService.split(new SplitRequest(original, "3", List.of(partA, partB), 0));
		assertEquals(existingSourceQuestion.getId(), result.sourceQuestion().getId());
		assertEquals(SharedContextStatus.NONE, result.sourceQuestion().getSharedContextStatus());
		Question resultingA = questionRepository.findById(result.questions().get(0).getId()).orElseThrow();
		Question resultingB = questionRepository.findById(result.questions().get(1).getId()).orElseThrow();
		Question reloadedSibling = questionRepository.findById(existingSibling.getId()).orElseThrow();

		// All three parts must share the same persisted SourceQuestion identity.
		assertEquals(existingSourceQuestion.getId(), resultingA.getSourceQuestion().getId());
		assertEquals(existingSourceQuestion.getId(), resultingB.getSourceQuestion().getId());
		assertEquals(existingSourceQuestion.getId(), reloadedSibling.getSourceQuestion().getId());
		assertFalse(resultingA.hasSharedContext());
		assertFalse(resultingB.hasSharedContext());
		assertFalse(reloadedSibling.hasSharedContext());
		assertEquals(List.of("3a", "3b", "3c"),
				questionRepository.findAll().stream().map(Question::getQuestionCode).sorted().toList());

		// Reuse must not manufacture a second SourceQuestion with the same natural
		// identity.
		assertEquals(1, sourceRepository.findByBooklet(booklet).size());
	}

	@Test
	void splitReusesExistingSourceQuestionAndSharedPreamble() {
		SqliteSourceQuestionRepository sourceRepository = new SqliteSourceQuestionRepository(database);
		SourceQuestion existingSource = sourceRepository.save(booklet, "3");
		existingSource = sourceRepository.updatePreambleStatus(existingSource, SharedContextStatus.PRESENT);
		SqliteSharedQuestionContextRepository contextRepository = new SqliteSharedQuestionContextRepository(database);
		SharedQuestionContext existingContext = contextRepository.save(booklet, "Question 3 preamble",
				List.of(new SharedQuestionContextRegion(1, 0.10, 0.10, 0.80, 0.15)));

		// Existing part 3c establishes both the SourceQuestion identity and the
		// authoritative shared context that the corrected split must reuse.
		Question existingSibling = questionRepository.save(booklet, "3c", "", 1,
				List.of(new QuestionRegion(booklet, 2, 0.10, 0.65, 0.80, 0.15)), subtopicOne, false, existingSource,
				existingContext, QuestionResponseType.WRITTEN_RESPONSE);
		Question original = questionRepository.save(booklet, "3", "", 5,
				List.of(new QuestionRegion(booklet, 1, 0.10, 0.25, 0.80, 0.50)), subtopicOne, true, null, null,
				QuestionResponseType.WRITTEN_RESPONSE);
		SplitPart partA = new SplitPart("3a", 2, subtopicOne, QuestionResponseType.WRITTEN_RESPONSE,
				List.of(new QuestionRegion(booklet, 1, 0.10, 0.30, 0.80, 0.20)));
		SplitPart partB = new SplitPart("3b", 3, subtopicTwo, QuestionResponseType.WRITTEN_RESPONSE,
				List.of(new QuestionRegion(booklet, 2, 0.10, 0.20, 0.80, 0.25)));
		SplitResult result = splitService
				.split(new SplitRequest(original, "3", List.of(partA, partB), 0, existingContext));
		assertEquals(existingSource.getId(), result.sourceQuestion().getId());
		assertEquals(existingContext.getId(), result.sharedContext().getId());
		Question resultingA = questionRepository.findById(result.questions().get(0).getId()).orElseThrow();
		Question resultingB = questionRepository.findById(result.questions().get(1).getId()).orElseThrow();
		Question reloadedSibling = questionRepository.findById(existingSibling.getId()).orElseThrow();

		// The two corrected parts and the existing sibling must reconstruct as one
		// persisted multipart group using exactly one shared preamble.
		assertEquals(existingSource.getId(), resultingA.getSourceQuestion().getId());
		assertEquals(existingSource.getId(), resultingB.getSourceQuestion().getId());
		assertEquals(existingSource.getId(), reloadedSibling.getSourceQuestion().getId());
		assertEquals(existingContext.getId(), resultingA.getSharedContext().getId());
		assertEquals(existingContext.getId(), resultingB.getSharedContext().getId());
		assertEquals(existingContext.getId(), reloadedSibling.getSharedContext().getId());
		assertEquals(SharedContextStatus.PRESENT, resultingA.getSourceQuestion().getSharedContextStatus());
		assertEquals(1, contextRepository.findByBooklet(booklet).size());
		assertEquals(List.of("3a", "3b", "3c"),
				questionRepository.findAll().stream().map(Question::getQuestionCode).sorted().toList());
	}

	@Test
	void splitsSingleLegacyQuestionIntoMultipartQuestionsWithoutSharedContext() {
		Question original = questionRepository.save(booklet, "3", "", 5,
				List.of(new QuestionRegion(booklet, 1, 0.10, 0.10, 0.80, 0.60)), subtopicOne, true, null, null,
				QuestionResponseType.WRITTEN_RESPONSE);
		SplitPart partA = new SplitPart("3a", 2, subtopicOne, QuestionResponseType.WRITTEN_RESPONSE,
				List.of(new QuestionRegion(booklet, 1, 0.10, 0.15, 0.80, 0.20)));

		// The multiple-choice split part must satisfy the one-mark invariant.
		SplitPart partB = new SplitPart("3b", 3, subtopicTwo, QuestionResponseType.WRITTEN_RESPONSE,
				List.of(new QuestionRegion(booklet, 2, 0.10, 0.20, 0.80, 0.25)));
		SplitResult result = splitService.split(new SplitRequest(original, "3", List.of(partA, partB), 0));
		assertEquals(2, result.questions().size());
		Question resultingA = result.questions().get(0);
		Question resultingB = result.questions().get(1);

		// The nominated retained part keeps the persistent identity of the legacy
		// Question; only the additional part receives a new identity.
		assertEquals(original.getId(), resultingA.getId());
		assertNotEquals(original.getId(), resultingB.getId());
		assertEquals("3a", resultingA.getQuestionCode());
		assertEquals("3b", resultingB.getQuestionCode());
		assertEquals(2, resultingA.getMarks());
		assertEquals(3, resultingB.getMarks());
		assertEquals(subtopicOne.getId(), resultingA.getClassification().getId());
		assertEquals(subtopicTwo.getId(), resultingB.getClassification().getId());
		assertEquals(QuestionResponseType.WRITTEN_RESPONSE, resultingA.getResponseType());
		assertEquals(QuestionResponseType.WRITTEN_RESPONSE, resultingB.getResponseType());
		assertEquals(1, resultingA.getRegions().size());
		assertEquals(1, resultingA.getRegions().getFirst().pageNumber());
		assertEquals(1, resultingB.getRegions().size());
		assertEquals(2, resultingB.getRegions().getFirst().pageNumber());

		// Both resulting Questions must reconstruct through one persisted source
		// identity rather than merely having similar text codes.
		assertTrue(resultingA.hasSourceQuestion());
		assertTrue(resultingB.hasSourceQuestion());
		assertEquals(resultingA.getSourceQuestion().getId(), resultingB.getSourceQuestion().getId());
		assertEquals(result.sourceQuestion().getId(), resultingA.getSourceQuestion().getId());
		assertEquals("3", result.sourceQuestion().getSourceQuestionCode());
		assertEquals(SharedContextStatus.NONE, result.sourceQuestion().getSharedContextStatus());
		assertFalse(resultingA.hasSharedContext());
		assertFalse(resultingB.hasSharedContext());

		// The old legacy preamble hint must not survive a confirmed no-preamble
		// split, otherwise Corpus Audit would still report unresolved context.
		assertFalse(resultingA.isSharedContextCaptureRequired());
		assertFalse(resultingB.isSharedContextCaptureRequired());
		SqliteQuestionRepository reloadedRepository = new SqliteQuestionRepository(database);
		Question reloadedA = reloadedRepository.findById(resultingA.getId()).orElseThrow();
		Question reloadedB = reloadedRepository.findById(resultingB.getId()).orElseThrow();

		// Reload proves the split exists in SQLite rather than only in the returned
		// domain objects.
		assertEquals("3a", reloadedA.getQuestionCode());
		assertEquals("3b", reloadedB.getQuestionCode());
		assertTrue(reloadedA.hasSourceQuestion());
		assertTrue(reloadedB.hasSourceQuestion());
		assertEquals(reloadedA.getSourceQuestion().getId(), reloadedB.getSourceQuestion().getId());
		assertEquals(SharedContextStatus.NONE, reloadedA.getSourceQuestion().getSharedContextStatus());
		assertFalse(reloadedA.hasSharedContext());
		assertFalse(reloadedB.hasSharedContext());
		List<Question> allQuestions = reloadedRepository.findAll();
		assertEquals(2, allQuestions.size());

		// The unsplit natural identity must no longer remain as a third Question.
		assertFalse(allQuestions.stream().anyMatch(question -> "3".equals(question.getQuestionCode())));
	}
}
