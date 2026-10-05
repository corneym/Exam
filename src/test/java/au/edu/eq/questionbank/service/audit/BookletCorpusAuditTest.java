package au.edu.eq.questionbank.service.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import au.edu.eq.questionbank.model.Answer;
import au.edu.eq.questionbank.model.AnswerFile;
import au.edu.eq.questionbank.model.AnswerRegion;
import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamBookletQuestionFormat;
import au.edu.eq.questionbank.model.ExamCaptureState;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.SharedContextStatus;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.SourceQuestion;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;

class BookletCorpusAuditTest {

	private final Fixture fixture = new Fixture();

	@Test
	void absentAnswerFileIsReportedWithoutInventingAProblem() {
		ExamBooklet oneQuestionBooklet = new ExamBooklet(30, fixture.exam, "Paper 2",
				new SourceDocument(31, "Chemistry/2025/paper2.pdf"), ExamBookletQuestionFormat.MULTIPLE_CHOICE, 1);
		Question question = fixture.question(120, oneQuestionBooklet, "1", null);
		BookletCorpusStatus status = BookletCorpusAudit.assess(oneQuestionBooklet, List.of(question), null, true);

		// AnswerFile assignment is descriptive booklet state. Its absence is not
		// automatically an asset defect because an MCQ booklet may need no Answer PDF.
		assertNull(status.assignedAnswerFile());
		assertTrue(status.findings().isEmpty());
		assertEquals(1, status.encounteredTopLevelQuestionCount());
	}

	@Test
	void assignedAnswerFileIsIncludedInStatus() {
		Question question = fixture.question(130, fixture.booklet, "1", null);
		BookletCorpusStatus status = BookletCorpusAudit.assess(fixture.booklet, List.of(question), fixture.answerFile,
				true);

		// Dashboard consumers receive the authoritative booklet assignment alongside
		// calculated Question counts.
		assertSame(fixture.answerFile, status.assignedAnswerFile());
	}

	@Test
	void countsQuestionsWhoseClassificationHasNotReachedDescriptorLevel() {
		Subtopic subtopic = new Subtopic(60, fixture.syllabus, fixture.topic, "1.1.0", "Legacy subtopic", 1);
		Question descriptorQuestion = fixture.question(170, fixture.booklet, "1", null);
		QuestionRegion subtopicRegion = new QuestionRegion(fixture.booklet, 1, 0.10, 0.10, 0.70, 0.20);
		Question subtopicQuestion = new Question(171, fixture.booklet, "2", "", 2, List.of(subtopicRegion), subtopic,
				false, null, null, QuestionResponseType.WRITTEN_RESPONSE);
		BookletCorpusStatus status = BookletCorpusAudit.assess(fixture.booklet,
				List.of(descriptorQuestion, subtopicQuestion), fixture.answerFile, true);

		// Descriptor classification is already complete. The legacy Subtopic-only
		// classification remains valid persisted metadata but must be reported as work
		// for later descriptor-level curriculum mapping.
		assertEquals(2, status.questionPartCount());
		assertEquals(1, status.questionsWithoutDescriptorCount());
	}

	@Test
	void expectedCountMismatchIsAdvisoryAndDoesNotChangeExamState() {
		Question question = fixture.question(110, fixture.booklet, "1", null);
		BookletCorpusStatus status = BookletCorpusAudit.assess(fixture.booklet, List.of(question), null, true);

		// The audit reports the discrepancy but never reverses a deliberate COMPLETE
		// declaration.
		assertTrue(status.hasFinding(BookletCorpusFinding.EXPECTED_TOP_LEVEL_QUESTION_COUNT_MISMATCH));
		assertEquals(ExamCaptureState.COMPLETE, fixture.exam.getCaptureState());
		assertTrue(fixture.exam.isComplete());
	}

	@Test
	void missingMcqExplanationIsSeparateFromOrdinaryAnswerCompletenessButRequiresAttention() {
		AnswerFile explanations = new AnswerFile(50, fixture.exam, "Solutions",
				new SourceDocument(51, "Chemistry/2025/solutions.pdf"), true);
		Question explained = fixture.multipleChoiceQuestion(150, fixture.booklet, "1");
		explained.setAnswer(new Answer(160, "A", List.of(new AnswerRegion(explanations, 1, 0.10, 0.10, 0.70, 0.20))));
		Question notExplained = fixture.multipleChoiceQuestion(151, fixture.booklet, "2");
		notExplained.setAnswer(new Answer(161, "B", List.of()));
		BookletCorpusStatus status = BookletCorpusAudit.assess(fixture.booklet, List.of(explained, notExplained),
				explanations, true);
		McqExplanationCoverage coverage = status.mcqExplanationCoverage();

		// Both A-D Answers remain ordinarily complete. Explanation coverage is a
		// separate requirement created by the AnswerFile declaration.
		assertTrue(coverage.explanationCapable());
		assertEquals(2, coverage.eligibleQuestionCount());
		assertEquals(1, coverage.capturedExplanationCount());
		assertEquals(1, coverage.missingExplanationCount());
		assertEquals(2, status.questionSummary().completeQuestions());
		assertEquals(0, status.questionSummary().missingAnswer());
		assertTrue(status.findings().isEmpty());

		// Required explanation work now counts as booklet attention without inventing a
		// MISSING_ANSWER problem.
		assertTrue(status.requiresAttention());
	}

	@Test
	void missingQuestionPdfIsReportedAsBookletFinding() {
		BookletCorpusStatus status = BookletCorpusAudit.assess(fixture.booklet, List.of(), null, false);

		// Physical source availability is independent of whether a SourceDocument row
		// remains registered in the booklet.
		assertFalse(status.questionPdfAvailable());
		assertTrue(status.hasFinding(BookletCorpusFinding.MISSING_QUESTION_PDF));
		assertSame(fixture.booklet, status.booklet());
	}

	@Test
	void multipartQuestionsCountAsOneTopLevelQuestionWithoutIdentityCollision() {
		SourceQuestion sourceQuestion = new SourceQuestion(100, fixture.booklet, "21", SharedContextStatus.NONE);
		Question partA = fixture.question(101, fixture.booklet, "21a", sourceQuestion);
		Question partB = fixture.question(102, fixture.booklet, "21b", sourceQuestion);

		// Deliberately reuse the SourceQuestion numeric id as an ordinary Question id.
		// The two persistence namespaces must still count as separate top-level items.
		Question ordinary = fixture.question(100, fixture.booklet, "22", null);
		BookletCorpusStatus status = BookletCorpusAudit.assess(fixture.booklet, List.of(partA, partB, ordinary),
				fixture.answerFile, true);
		assertEquals(3, status.questionPartCount());
		assertEquals(2, status.encounteredTopLevelQuestionCount());
		assertEquals(Integer.valueOf(2), status.expectedTopLevelQuestionCount());
		assertFalse(status.hasFinding(BookletCorpusFinding.EXPECTED_TOP_LEVEL_QUESTION_COUNT_MISMATCH));
	}

	@Test
	void questionsFromOtherBookletsAreExcluded() {
		ExamBooklet otherBooklet = new ExamBooklet(40, fixture.exam, "Paper 3",
				new SourceDocument(41, "Chemistry/2025/paper3.pdf"), ExamBookletQuestionFormat.WRITTEN_RESPONSE, 1);
		Question included = fixture.question(140, fixture.booklet, "1", null);
		Question excluded = fixture.question(141, otherBooklet, "2", null);
		BookletCorpusStatus status = BookletCorpusAudit.assess(fixture.booklet, List.of(excluded, included), null,
				true);

		// Supplying an Exam-wide Question snapshot must not leak another booklet into
		// this booklet's totals or Question audit summary.
		assertEquals(1, status.questionPartCount());
		assertEquals(1, status.questionSummary().totalQuestions());
		assertEquals(1, status.encounteredTopLevelQuestionCount());
	}

	private static final class Fixture {

		private final Subject subject = new Subject(1, "Chemistry");
		private final SyllabusVersion syllabus = new SyllabusVersion(2, subject, "2025", true);
		private final Unit unit = new Unit(3, syllabus, "1", "Unit 1", 1);
		private final Topic topic = new Topic(4, syllabus, unit, "1.1", "Topic 1", 1);
		private final Descriptor classification = new Descriptor(5, syllabus, topic, "1.1.1", "Descriptor", 1);
		private final ExamProvider provider = new ExamProvider(6, "QCAA");
		private final Exam exam = new Exam(7, subject, provider, 2025, "External Assessment",
				ExamCaptureState.COMPLETE);
		private final ExamBooklet booklet = new ExamBooklet(8, exam, "Paper 1",
				new SourceDocument(9, "Chemistry/2025/paper1.pdf"), ExamBookletQuestionFormat.MIXED, 2);
		private final AnswerFile answerFile = new AnswerFile(10, exam, "Marking guide",
				new SourceDocument(11, "Chemistry/2025/answers.pdf"));

		private Question multipleChoiceQuestion(long id, ExamBooklet questionBooklet, String code) {
			QuestionRegion region = new QuestionRegion(questionBooklet, 1, 0.10, 0.10, 0.70, 0.20);

			// MCQ fixtures obey the one-mark invariant while retaining the same
			// authoritative Question source used by the booklet audit.
			return new Question(id, questionBooklet, code, "", 1, List.of(region), classification, false, null, null,
					QuestionResponseType.MULTIPLE_CHOICE);
		}

		private Question question(long id, ExamBooklet questionBooklet, String code, SourceQuestion sourceQuestion) {
			QuestionRegion region = new QuestionRegion(questionBooklet, 1, 0.10, 0.10, 0.70, 0.20);

			// Written-response fixture Questions keep counting tests independent of the
			// one-mark invariant enforced for multiple-choice Questions.
			return new Question(id, questionBooklet, code, "", 2, List.of(region), classification, false,
					sourceQuestion, null, QuestionResponseType.WRITTEN_RESPONSE);
		}
	}
}
