package au.edu.eq.questionbank.service.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamBookletQuestionFormat;
import au.edu.eq.questionbank.model.ExamCaptureState;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.pdf.PdfStore;
import au.edu.eq.questionbank.repository.assessment.BookletApplicabilityReviewRepository;
import au.edu.eq.questionbank.repository.assessment.SqliteAnswerWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteBookletApplicabilityReviewRepository;
import au.edu.eq.questionbank.repository.assessment.SqliteExamWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteQuestionRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class ExamLifecycleServiceTest {

	// Test environment and collaborators.
	private SqliteAnswerWriter answerWriter;
	private SqliteExamWriter examWriter;
	private ExamLifecycleService lifecycleService;
	private Path paperPdf;
	private SqliteQuestionRepository questionRepository;
	private BookletApplicabilityReviewRepository reviewRepository;
	@TempDir
	Path tempDirectory;

	// Curriculum and assessment fixtures.
	private ExamBooklet booklet;
	private Descriptor descriptor;
	private Exam exam;

	@Test
	void completeExamRequiresExplicitBookletReview() throws Exception {
		captureCompleteQuestion();
		IllegalStateException failure = assertThrows(IllegalStateException.class,
				() -> lifecycleService.setCaptureState(exam, ExamCaptureState.COMPLETE));
		assertTrue(failure.getMessage().contains("Applicability Reviewed"));
		assertEquals(ExamCaptureState.ACTIVE, reloadedExam().getCaptureState());
		confirmBookletReview();
		Exam completed = lifecycleService.setCaptureState(exam, ExamCaptureState.COMPLETE);
		assertEquals(ExamCaptureState.COMPLETE, completed.getCaptureState());
		assertEquals(ExamCaptureState.COMPLETE, reloadedExam().getCaptureState());
	}

	@Test
	void incompleteAnswerBlocksCompletionDespiteReviewRecord() throws Exception {
		Question question = captureQuestionWithoutAnswer();

		// Bypass the review application service to exercise the
		// authoritative lifecycle guard against persisted data.
		confirmBookletReview();
		assertThrows(IllegalStateException.class,
				() -> lifecycleService.setCaptureState(exam, ExamCaptureState.COMPLETE));
		assertEquals(ExamCaptureState.ACTIVE, reloadedExam().getCaptureState());
		answerWriter.insertAnswer(question, "A", List.of());

		// Confirm the Answer has become complete according to the
		// actual Question audit, not merely because an Answer row exists.
		Question persisted = questionRepository.findById(question.getId()).orElseThrow();
		assertTrue(QuestionCorpusAudit.assess(persisted).answerComplete());

		// A later Answer edit invalidates the old review.
		assertFalse(reviewRepository.isReviewed(booklet.getId()));
		confirmBookletReview();
		Exam completed = lifecycleService.setCaptureState(exam, ExamCaptureState.COMPLETE);
		assertEquals(ExamCaptureState.COMPLETE, completed.getCaptureState());
	}

	@BeforeEach
	void initialise() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("lifecycle.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SyllabusVersion syllabus = curriculumWriter.insertSyllabusVersion(chemistry, "2025", true);
		Unit unit = curriculumWriter.insertUnit(syllabus, "1", "Unit 1", 1);
		Topic topic = curriculumWriter.insertTopic(unit, "1.1", "Topic 1", 1);
		descriptor = curriculumWriter.insertDescriptor(topic, "1.1.1", "Descriptor", 1);
		examWriter = new SqliteExamWriter(database);
		exam = examWriter.createExam(chemistry, "QCAA", 2025, "External Assessment");
		SourceDocument document = examWriter.insertSourceDocument("Chemistry/QCAA/2025/paper1.pdf");
		booklet = examWriter.insertExamBooklet(exam, document, "Paper 1", ExamBookletQuestionFormat.MULTIPLE_CHOICE, 1);

		// One Question booklet; no separate Answer source file.
		examWriter.updateExamAssetExpectations(exam, 1, 0);
		PdfStore pdfStore = new PdfStore(tempDirectory.resolve("pdf"));
		paperPdf = pdfStore.resolve(document.getRelativePath());
		Files.createDirectories(paperPdf.getParent());
		Files.writeString(paperPdf, "Fixture PDF bytes");
		answerWriter = new SqliteAnswerWriter(database, examWriter);
		questionRepository = new SqliteQuestionRepository(database);
		reviewRepository = new SqliteBookletApplicabilityReviewRepository(database);
		ExamCorpusAuditService auditService = new ExamCorpusAuditService(examWriter, answerWriter, questionRepository,
				pdfStore);
		lifecycleService = new ExamLifecycleService(examWriter, auditService, reviewRepository);
	}

	@Test
	void missingQuestionCaptureBlocksCompletionDespiteReviewRecord() throws Exception {

		// One Question is expected but none has been captured.
		confirmBookletReview();
		assertThrows(IllegalStateException.class,
				() -> lifecycleService.setCaptureState(exam, ExamCaptureState.COMPLETE));
		assertEquals(ExamCaptureState.ACTIVE, reloadedExam().getCaptureState());
	}

	@Test
	void missingQuestionPdfBlocksCompletionDespiteReviewRecord() throws Exception {
		captureCompleteQuestion();
		confirmBookletReview();
		Files.delete(paperPdf);
		assertThrows(IllegalStateException.class,
				() -> lifecycleService.setCaptureState(exam, ExamCaptureState.COMPLETE));
		assertEquals(ExamCaptureState.ACTIVE, reloadedExam().getCaptureState());
	}

	@Test
	void reactivationAllowsMissingReviewsAndCompletionRequiresNewReview() throws Exception {
		captureCompleteQuestion();
		confirmBookletReview();
		Exam completed = lifecycleService.setCaptureState(exam, ExamCaptureState.COMPLETE);
		reviewRepository.setReviewed(booklet.getId(), false);

		// An existing COMPLETE declaration is not automatically
		// reversed when a review is cleared.
		assertEquals(ExamCaptureState.COMPLETE, reloadedExam().getCaptureState());
		Exam active = lifecycleService.setCaptureState(completed, ExamCaptureState.ACTIVE);
		assertEquals(ExamCaptureState.ACTIVE, active.getCaptureState());
		assertThrows(IllegalStateException.class,
				() -> lifecycleService.setCaptureState(active, ExamCaptureState.COMPLETE));
		confirmBookletReview();
		assertEquals(ExamCaptureState.COMPLETE,
				lifecycleService.setCaptureState(active, ExamCaptureState.COMPLETE).getCaptureState());
	}

	@Test
	void unknownAssetExpectationsBlockCompletion() throws Exception {
		captureCompleteQuestion();
		confirmBookletReview();

		// A missing expected Answer-file count is not equivalent
		// to a deliberately recorded expectation of zero.
		examWriter.updateExamAssetExpectations(exam, 1, null);
		assertThrows(IllegalStateException.class,
				() -> lifecycleService.setCaptureState(exam, ExamCaptureState.COMPLETE));
		assertEquals(ExamCaptureState.ACTIVE, reloadedExam().getCaptureState());
	}

	private void captureCompleteQuestion() throws Exception {
		Question question = captureQuestionWithoutAnswer();
		answerWriter.insertAnswer(question, "A", List.of());

		// Verify the persistence fixture actually satisfies ordinary
		// Question completeness before exercising Exam completion.
		Question persisted = questionRepository.findById(question.getId()).orElseThrow();
		assertTrue(QuestionCorpusAudit.assess(persisted).answerComplete());
	}

	private Question captureQuestionWithoutAnswer() {
		return questionRepository.save(booklet, "1", "", 1,
				List.of(new QuestionRegion(booklet, 1, 0.10, 0.10, 0.70, 0.20)), descriptor, false, null, null,
				QuestionResponseType.MULTIPLE_CHOICE);
	}

	private void confirmBookletReview() {
		reviewRepository.setReviewed(booklet.getId(), true);
	}

	private Exam reloadedExam() throws Exception {
		return examWriter.findExamsForSubject(exam.getSubject()).stream()
				.filter(candidate -> candidate.getId() == exam.getId()).findFirst().orElseThrow();
	}
}
