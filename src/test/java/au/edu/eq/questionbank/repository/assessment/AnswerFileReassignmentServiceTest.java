package au.edu.eq.questionbank.repository.assessment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.AnswerFile;
import au.edu.eq.questionbank.model.AnswerRegion;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamCaptureState;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class AnswerFileReassignmentServiceTest {

	@TempDir
	Path tempDirectory;

	@Test
	void assigningCurrentAnswerFileIsNonDestructiveNoOp() throws Exception {
		Fixture fixture = createFixture("same-answer-file.db");
		Question written = fixture.questionRepository().save(fixture.booklet(), "Q1", "", 2,
				List.of(new QuestionRegion(fixture.booklet(), 1, 0.10, 0.10, 0.60, 0.20)), fixture.subtopic(), false,
				null, null, QuestionResponseType.WRITTEN_RESPONSE);
		AnswerFile answerFile = fixture.answerWriter().findOrCreateAnswerFile(fixture.booklet(), "Marking guide",
				"Chemistry/QCAA/2025/answers.pdf", "7".repeat(64));
		fixture.answerWriter().insertAnswer(written, null,
				List.of(new AnswerRegion(answerFile, 4, 0.10, 0.20, 0.50, 0.12)));
		AnswerFileReassignmentService service = new AnswerFileReassignmentService(fixture.database());
		AnswerFileReassignmentService.Result result = service.reassign(fixture.booklet(), answerFile);
		assertFalse(result.changed());
		Question reloaded = fixture.questionRepository().findById(written.getId()).orElseThrow();

		// Choosing the already assigned file must never masquerade as a replacement.
		assertTrue(reloaded.hasAnswer());
		assertEquals(1, reloaded.getAnswer().getRegions().size());
	}

	@Test
	void reassigningOneBookletDoesNotModifyAnotherBookletSharingOldAnswerFile() throws Exception {
		Fixture fixture = createFixture("shared-answer-file.db");
		ExamBooklet secondBooklet = new SqliteExamImporter(fixture.database(), fixture.examWriter()).importExam(
				fixture.subject(), "QCAA", 2025, "External Assessment", "Paper 2", "Chemistry/QCAA/2025/paper2.pdf");
		AnswerFile sharedFile = fixture.answerWriter().findOrCreateAnswerFile(fixture.booklet(), "Shared marking guide",
				"Chemistry/QCAA/2025/shared-answers.pdf", "3".repeat(64));
		fixture.answerWriter().assignAnswerFile(secondBooklet, sharedFile);
		AnswerFile replacement = fixture.answerWriter().findOrCreateAnswerFile(fixture.booklet().getExam(),
				"Paper 1 replacement", "Chemistry/QCAA/2025/paper1-correct-answers.pdf", "4".repeat(64));
		AnswerFileReassignmentService service = new AnswerFileReassignmentService(fixture.database());
		AnswerFileReassignmentService.Result result = service.reassign(fixture.booklet(), replacement);
		assertTrue(result.changed());
		assertEquals(replacement.getId(), fixture.answerWriter().findAnswerFile(fixture.booklet()).getId());

		// Reassignment is booklet-scoped. The shared AnswerFile remains authoritative
		// for every other booklet that still uses it.
		assertEquals(sharedFile.getId(), fixture.answerWriter().findAnswerFile(secondBooklet).getId());
	}

	@Test
	void reassignsBookletPreservingTextAnswersAndRemovingRegionOnlyAnswers() throws Exception {
		Fixture fixture = createFixture("safe-reassignment.db");
		Question written = fixture.questionRepository().save(fixture.booklet(), "Q1", "", 3,
				List.of(new QuestionRegion(fixture.booklet(), 1, 0.10, 0.10, 0.60, 0.20)), fixture.subtopic(), false,
				null, null, QuestionResponseType.WRITTEN_RESPONSE);
		Question mcq = fixture.questionRepository().save(fixture.booklet(), "Q2", "", 1,
				List.of(new QuestionRegion(fixture.booklet(), 2, 0.10, 0.10, 0.60, 0.20)), fixture.subtopic(), false,
				null, null, QuestionResponseType.MULTIPLE_CHOICE);
		AnswerFile oldFile = fixture.answerWriter().findOrCreateAnswerFile(fixture.booklet(), "Old marking guide",
				"Chemistry/QCAA/2025/old-answers.pdf", "1".repeat(64));
		AnswerFile correctFile = fixture.answerWriter().findOrCreateAnswerFile(fixture.booklet().getExam(),
				"Correct marking guide", "Chemistry/QCAA/2025/correct-answers.pdf", "2".repeat(64));

		// Written-response Answer contains no independent text. Removing its old PDF
		// region therefore makes the Question unanswered again.
		fixture.answerWriter().insertAnswer(written, null,
				List.of(new AnswerRegion(oldFile, 4, 0.10, 0.20, 0.50, 0.12)));

		// The text models the independent MCQ letter. A region is also supplied to
		// exercise future explanation-region invalidation without losing the letter.
		fixture.answerWriter().insertAnswer(mcq, "B", List.of(new AnswerRegion(oldFile, 5, 0.10, 0.20, 0.50, 0.12)));
		AnswerFileReassignmentService service = new AnswerFileReassignmentService(fixture.database());
		AnswerFileReassignmentService.Impact impact = service.assess(fixture.booklet());
		assertEquals(2, impact.affectedQuestionCount());
		assertEquals(2, impact.answerRegionCount());
		assertEquals(1, impact.preservedTextAnswerCount());
		assertEquals(1, impact.regionOnlyAnswerCount());
		AnswerFileReassignmentService.Result result = service.reassign(fixture.booklet(), correctFile);
		assertTrue(result.changed());
		assertEquals(correctFile.getId(), result.answerFile().getId());
		assertEquals(impact, result.impact());
		AnswerFile assigned = fixture.answerWriter().findAnswerFile(fixture.booklet());
		assertEquals(correctFile.getId(), assigned.getId());
		Question reloadedWritten = fixture.questionRepository().findById(written.getId()).orElseThrow();
		Question reloadedMcq = fixture.questionRepository().findById(mcq.getId()).orElseThrow();

		// Region-only written Answer has no valid content left and therefore returns
		// naturally to the unanswered queue.
		assertFalse(reloadedWritten.hasAnswer());

		// Independent MCQ text survives while every coordinate belonging to the old
		// marking guide is removed.
		assertTrue(reloadedMcq.hasAnswer());
		assertEquals("B", reloadedMcq.getAnswer().getAnswerText());
		assertTrue(reloadedMcq.getAnswer().getRegions().isEmpty());
	}

	@Test
	void rejectsReassignmentForCompleteExamWithoutInvalidatingExistingAnswer() throws Exception {
		Fixture fixture = createFixture("complete-answer-reassignment.db");
		Question written = fixture.questionRepository().save(fixture.booklet(), "Q1", "", 2,
				List.of(new QuestionRegion(fixture.booklet(), 1, 0.10, 0.10, 0.60, 0.20)), fixture.subtopic(), false,
				null, null, QuestionResponseType.WRITTEN_RESPONSE);
		AnswerFile oldFile = fixture.answerWriter().findOrCreateAnswerFile(fixture.booklet(), "Old marking guide",
				"Chemistry/QCAA/2025/old-answers.pdf", "5".repeat(64));
		AnswerFile replacement = fixture.answerWriter().findOrCreateAnswerFile(fixture.booklet().getExam(),
				"Correct marking guide", "Chemistry/QCAA/2025/new-answers.pdf", "6".repeat(64));
		fixture.answerWriter().insertAnswer(written, null,
				List.of(new AnswerRegion(oldFile, 3, 0.10, 0.20, 0.50, 0.12)));
		fixture.examWriter().setExamCaptureState(fixture.booklet().getExam(), ExamCaptureState.COMPLETE);
		AnswerFileReassignmentService service = new AnswerFileReassignmentService(fixture.database());
		assertThrows(IllegalStateException.class, () -> service.reassign(fixture.booklet(), replacement));

		// Structural rejection occurs before any source-dependent Answer data changes.
		assertEquals(oldFile.getId(), fixture.answerWriter().findAnswerFile(fixture.booklet()).getId());
		Question reloaded = fixture.questionRepository().findById(written.getId()).orElseThrow();
		assertTrue(reloaded.hasAnswer());
		assertEquals(1, reloaded.getAnswer().getRegions().size());
	}

	private Fixture createFixture(String databaseName) throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve(databaseName));
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SyllabusVersion syllabus = curriculumWriter.insertSyllabusVersion(chemistry, "2025", true);
		Unit unit = curriculumWriter.insertUnit(syllabus, "1", "Unit 1", 1);
		Topic topic = curriculumWriter.insertTopic(unit, "1.1", "Topic 1", 1);
		Subtopic subtopic = curriculumWriter.insertSubtopic(topic, "1.1.1", "Subtopic 1", 1);
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		ExamBooklet booklet = new SqliteExamImporter(database, examWriter).importExam(chemistry, "QCAA", 2025,
				"External Assessment", "Paper 1", "Chemistry/QCAA/2025/paper1.pdf");
		SqliteQuestionRepository questionRepository = new SqliteQuestionRepository(database);
		SqliteAnswerWriter answerWriter = new SqliteAnswerWriter(database, examWriter);

		// Keep fixture construction in one place so every reassignment regression
		// exercises the same persisted domain structure.
		return new Fixture(database, chemistry, subtopic, examWriter, booklet, questionRepository, answerWriter);
	}

	private record Fixture(SqliteDatabase database, Subject subject, Subtopic subtopic, SqliteExamWriter examWriter,
			ExamBooklet booklet, SqliteQuestionRepository questionRepository, SqliteAnswerWriter answerWriter) {
	}
}
