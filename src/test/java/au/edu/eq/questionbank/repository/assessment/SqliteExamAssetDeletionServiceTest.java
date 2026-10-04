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

import au.edu.eq.questionbank.model.AnswerFile;
import au.edu.eq.questionbank.model.AnswerRegion;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamCaptureState;
import au.edu.eq.questionbank.model.PdfQuestionContentPart;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.SharedContextStatus;
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

class SqliteExamAssetDeletionServiceTest {

	@TempDir
	Path tempDirectory;

	@Test
	void activeAnswerFileDeletionRemovesDependentAnswersAndBookletAssignments() throws Exception {
		Fixture fixture = createFixture("delete-answer-file.db");
		SqliteExamAssetDeletionService service = new SqliteExamAssetDeletionService(fixture.database(),
				fixture.examWriter());
		SqliteExamAssetDeletionService.DeletionResult result = service.deleteAnswerFile(fixture.answerFile());

		// The AnswerFile owns a distinct managed source, so both its structural row and
		// now-unreferenced SourceDocument are removed in the committed transaction.
		assertEquals("Chemistry/QCAA/2025/answers.pdf", result.relativePath());
		assertTrue(result.sourceDocumentDeleted());
		assertEquals(0, countRows(fixture.database(), "answer_files"));
		assertEquals(0, countRows(fixture.database(), "answers"));
		assertEquals(0, countRows(fixture.database(), "answer_regions"));
		assertEquals(1, countRows(fixture.database(), "source_documents"));

		// Deleting the Answer asset retains the Question corpus and explicitly clears
		// the booklet assignment used by later recapture.
		assertNull(fixture.answerWriter().findAnswerFile(fixture.booklet()));
		Question reloaded = new SqliteQuestionRepository(fixture.database()).findById(fixture.question().getId())
				.orElseThrow();
		assertFalse(reloaded.hasAnswer());
		assertEquals(1, countRows(fixture.database(), "questions"));
	}

	@Test
	void activeQuestionBookletDeletionRemovesItsCompleteCaptureGraph() throws Exception {
		Fixture fixture = createFixture("delete-question-booklet.db");
		SqliteExamAssetDeletionService service = new SqliteExamAssetDeletionService(fixture.database(),
				fixture.examWriter());
		SqliteExamAssetDeletionService.DeletionResult result = service.deleteQuestionBooklet(fixture.booklet());

		// The Question booklet source becomes unreferenced, while the independent
		// AnswerFile and its source remain available for deliberate later correction.
		assertEquals("Chemistry/QCAA/2025/paper1.pdf", result.relativePath());
		assertTrue(result.sourceDocumentDeleted());
		assertEquals(0, countRows(fixture.database(), "exam_booklets"));
		assertEquals(1, countRows(fixture.database(), "answer_files"));
		assertEquals(1, countRows(fixture.database(), "source_documents"));

		// Every persisted object whose identity or coordinates depend on the deleted
		// booklet is removed rather than left as an orphan.
		assertEquals(0, countRows(fixture.database(), "questions"));
		assertEquals(0, countRows(fixture.database(), "question_regions"));
		assertEquals(0, countRows(fixture.database(), "question_content_parts"));
		assertEquals(0, countRows(fixture.database(), "source_questions"));
		assertEquals(0, countRows(fixture.database(), "shared_question_contexts"));
		assertEquals(0, countRows(fixture.database(), "shared_question_context_regions"));
		assertEquals(0, countRows(fixture.database(), "answers"));
		assertEquals(0, countRows(fixture.database(), "answer_regions"));
	}

	@Test
	void completeExamRejectsAssetDeletionWithoutChangingPersistence() throws Exception {
		Fixture fixture = createFixture("reject-complete-deletion.db");
		fixture.examWriter().setExamCaptureState(fixture.booklet().getExam(), ExamCaptureState.COMPLETE);
		SqliteExamAssetDeletionService service = new SqliteExamAssetDeletionService(fixture.database(),
				fixture.examWriter());

		// Lifecycle enforcement uses authoritative persisted Exam state rather than the
		// ACTIVE state carried by the older fixture objects.
		assertThrows(IllegalStateException.class, () -> service.deleteAnswerFile(fixture.answerFile()));
		assertThrows(IllegalStateException.class, () -> service.deleteQuestionBooklet(fixture.booklet()));

		// Both rejected transactions leave the complete structural graph intact.
		assertEquals(1, countRows(fixture.database(), "exam_booklets"));
		assertEquals(1, countRows(fixture.database(), "answer_files"));
		assertEquals(1, countRows(fixture.database(), "questions"));
		assertEquals(1, countRows(fixture.database(), "answers"));
		assertEquals(2, countRows(fixture.database(), "source_documents"));
	}

	private int countRows(SqliteDatabase database, String tableName) throws Exception {
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + tableName)) {

			// Test callers supply only fixed schema table names from the assertions above.
			return result.getInt(1);
		}
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

		// Build a representative captured Question graph so deletion exercises source,
		// context, content and Answer dependencies together.
		SqliteSourceQuestionRepository sourceRepository = new SqliteSourceQuestionRepository(database);
		SourceQuestion sourceQuestion = sourceRepository.save(booklet, "Q1");
		sourceQuestion = sourceRepository.updatesharedContextStatus(sourceQuestion, SharedContextStatus.PRESENT);
		SharedQuestionContext sharedContext = new SqliteSharedQuestionContextRepository(database).save(booklet,
				"Questions 1-2 context", List.of(new SharedQuestionContextRegion(1, 0.05, 0.05, 0.90, 0.10)));
		QuestionRegion questionRegion = new QuestionRegion(booklet, 1, 0.10, 0.20, 0.70, 0.20);
		Question question = new SqliteQuestionWriter(database).insertQuestionWithContent(booklet, "Q1", "", 2,
				List.of(new PdfQuestionContentPart(questionRegion)), subtopic, false, sourceQuestion, sharedContext,
				QuestionResponseType.WRITTEN_RESPONSE);

		// Answer structure is separate from the Question PDF but still depends on the
		// Question and its booklet assignment.
		SqliteAnswerWriter answerWriter = new SqliteAnswerWriter(database, examWriter);
		AnswerFile answerFile = answerWriter.findOrCreateAnswerFile(booklet.getExam(), "Answers",
				"Chemistry/QCAA/2025/answers.pdf");
		answerWriter.assignAnswerFile(booklet, answerFile);
		answerWriter.insertAnswer(question, null, List.of(new AnswerRegion(answerFile, 2, 0.10, 0.10, 0.60, 0.20)));
		return new Fixture(database, examWriter, answerWriter, booklet, question, answerFile);
	}

	private record Fixture(SqliteDatabase database, SqliteExamWriter examWriter, SqliteAnswerWriter answerWriter,
			ExamBooklet booklet, Question question, AnswerFile answerFile) {
	}
}
