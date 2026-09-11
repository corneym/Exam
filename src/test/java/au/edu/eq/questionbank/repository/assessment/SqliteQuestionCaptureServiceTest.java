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
import au.edu.eq.questionbank.model.PreambleStatus;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
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
	void editingLastMultipartPartToNonMultipartRemovesUnreferencedSourceQuestionButRetainsContext() throws Exception {
		Fixture fixture = createFixture("edit-last-part-to-non-multipart.db");
		SqliteSourceQuestionRepository sourceRepository = new SqliteSourceQuestionRepository(fixture.database());
		SourceQuestion sourceQuestion = sourceRepository.save(fixture.booklet(), "24");
		sourceQuestion = sourceRepository.updatePreambleStatus(sourceQuestion, PreambleStatus.PRESENT);
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
		sourceQuestion = sourceRepository.updatePreambleStatus(sourceQuestion, PreambleStatus.PRESENT);
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
		assertEquals(PreambleStatus.PRESENT, retainedSource.getPreambleStatus());
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
		assertEquals(PreambleStatus.UNKNOWN, reloadedSource.getPreambleStatus());
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
