package au.edu.eq.questionbank.service.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import au.edu.eq.questionbank.model.Answer;
import au.edu.eq.questionbank.model.AnswerFile;
import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamAssetExpectations;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamBookletQuestionFormat;
import au.edu.eq.questionbank.model.ExamCaptureState;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;

class ExamCorpusAuditTest {

	private final Fixture fixture = new Fixture();

	@Test
	void aggregatesQuestionStatusAcrossBooklets() {
		Question complete = fixture.multipleChoiceQuestion(100, fixture.paper1, "1");
		complete.setAnswer(new Answer(200, "A", List.of()));
		Question missingAnswer = fixture.writtenQuestion(101, fixture.paper2, "2");
		BookletCorpusStatus paper1Status = BookletCorpusAudit.assess(fixture.paper1, List.of(complete),
				fixture.answerFile, true);
		BookletCorpusStatus paper2Status = BookletCorpusAudit.assess(fixture.paper2, List.of(missingAnswer),
				fixture.answerFile, true);
		ExamAssetExpectations expectations = new ExamAssetExpectations(2, 2, 1, 1);
		ExamCorpusStatus status = ExamCorpusAudit.assess(fixture.exam, expectations,
				List.of(paper1Status, paper2Status));

		// Exam aggregation reuses the already-calculated booklet Question summaries
		// rather than inventing another Question-completeness implementation.
		assertEquals(2, status.questionSummary().totalQuestions());
		assertEquals(1, status.questionSummary().completeQuestions());
		assertEquals(1, status.questionSummary().incompleteQuestions());
		assertEquals(1, status.questionSummary().missingAnswer());
	}

	@Test
	void answerExplanationMetadataDoesNotAffectOrdinaryCompleteness() {
		AnswerFile explanations = new AnswerFile(50, fixture.exam, "Solutions",
				new SourceDocument(51, "Chemistry/2025/solutions.pdf"), true);
		Question question = fixture.multipleChoiceQuestion(120, fixture.paper1, "3");
		question.setAnswer(new Answer(220, "B", List.of()));
		BookletCorpusStatus paper1Status = BookletCorpusAudit.assess(fixture.paper1, List.of(question), explanations,
				true);
		BookletCorpusStatus paper2Status = BookletCorpusAudit.assess(fixture.paper2, List.of(), null, true);
		ExamAssetExpectations expectations = new ExamAssetExpectations(2, 2, 1, 1);
		ExamCorpusStatus status = ExamCorpusAudit.assess(fixture.exam, expectations,
				List.of(paper1Status, paper2Status));

		// An MCQ's ordinary Answer is complete from its A-D choice. Explanation
		// availability remains a separate reporting dimension.
		assertEquals(1, status.questionSummary().completeQuestions());
		assertEquals(0, status.questionSummary().missingAnswer());
		McqExplanationSummary explanationSummary = status.mcqExplanationSummary();
		assertEquals(1, explanationSummary.explanationCapableBookletCount());
		assertEquals(1, explanationSummary.eligibleQuestionCount());
		assertEquals(0, explanationSummary.capturedExplanationCount());
		assertEquals(1, explanationSummary.missingExplanationCount());

		// Missing explanation coverage does not create an Exam-level finding.
		assertTrue(status.findings().isEmpty());
	}

	@Test
	void bookletFindingStillRequiresExamAttentionWithoutChangingExamState() {
		BookletCorpusStatus missingPdf = BookletCorpusAudit.assess(fixture.paper1, List.of(), null, false);
		BookletCorpusStatus paper2Status = BookletCorpusAudit.assess(fixture.paper2, List.of(), null, true);
		ExamAssetExpectations expectations = new ExamAssetExpectations(2, 2, 1, 1);
		ExamCorpusStatus status = ExamCorpusAudit.assess(fixture.exam, expectations, List.of(missingPdf, paper2Status));

		// Exam-level attention includes nested booklet findings while keeping the
		// persisted lifecycle declaration completely independent.
		assertTrue(status.findings().isEmpty());
		assertTrue(status.requiresAttention());
		assertEquals(ExamCaptureState.COMPLETE, status.declaredCaptureState());
	}

	@Test
	void completionReadinessRequiresExplicitCompleteCorpusStructure() {
		BookletCorpusStatus readyPaper1 = new BookletCorpusStatus(fixture.paper1, true, fixture.answerFile, 1, 1, 0,
				new QuestionCorpusSummary(1, 1, 0, 0, 0, 0, 0), new McqExplanationCoverage(false, 0, 0), Set.of());
		BookletCorpusStatus readyPaper2 = new BookletCorpusStatus(fixture.paper2, true, fixture.answerFile, 1, 1, 0,
				new QuestionCorpusSummary(1, 1, 0, 0, 0, 0, 0), new McqExplanationCoverage(false, 0, 0), Set.of());
		ExamCorpusStatus ready = new ExamCorpusStatus(fixture.exam, new ExamAssetExpectations(2, 2, 1, 1),
				List.of(readyPaper1, readyPaper2), new QuestionCorpusSummary(2, 2, 0, 0, 0, 0, 0),
				new McqExplanationSummary(0, 0, 0), Set.of());

		// Every structural, content and classification requirement is satisfied.
		assertTrue(ready.isReadyForCompletion());
		ExamCorpusStatus unplanned = new ExamCorpusStatus(fixture.exam, new ExamAssetExpectations(null, 2, null, 1),
				List.of(readyPaper1, readyPaper2), new QuestionCorpusSummary(2, 2, 0, 0, 0, 0, 0),
				new McqExplanationSummary(0, 0, 0), Set.of());
		assertFalse(unplanned.isReadyForCompletion());
		BookletCorpusStatus missingPdf = new BookletCorpusStatus(fixture.paper1, false, fixture.answerFile, 1, 1, 0,
				new QuestionCorpusSummary(1, 1, 0, 0, 0, 0, 0), new McqExplanationCoverage(false, 0, 0),
				Set.of(BookletCorpusFinding.MISSING_QUESTION_PDF));
		ExamCorpusStatus missingSource = new ExamCorpusStatus(fixture.exam, new ExamAssetExpectations(1, 1, 1, 1),
				List.of(missingPdf), new QuestionCorpusSummary(1, 1, 0, 0, 0, 0, 0), new McqExplanationSummary(0, 0, 0),
				Set.of());
		assertFalse(missingSource.isReadyForCompletion());
		BookletCorpusStatus incompleteQuestion = new BookletCorpusStatus(fixture.paper1, true, fixture.answerFile, 1, 1,
				0, new QuestionCorpusSummary(1, 0, 1, 1, 1, 0, 0), new McqExplanationCoverage(false, 0, 0), Set.of());
		ExamCorpusStatus questionWorkRemaining = new ExamCorpusStatus(fixture.exam,
				new ExamAssetExpectations(1, 1, 1, 1), List.of(incompleteQuestion),
				new QuestionCorpusSummary(1, 0, 1, 1, 1, 0, 0), new McqExplanationSummary(0, 0, 0), Set.of());

		// Independent Question and Answer work must both be complete before lifecycle
		// completion becomes available.
		assertFalse(questionWorkRemaining.isReadyForCompletion());
		BookletCorpusStatus missingDescriptor = new BookletCorpusStatus(fixture.paper1, true, fixture.answerFile, 1, 1,
				1, new QuestionCorpusSummary(1, 1, 0, 0, 0, 0, 0), new McqExplanationCoverage(false, 0, 0), Set.of());
		ExamCorpusStatus classificationWorkRemaining = new ExamCorpusStatus(fixture.exam,
				new ExamAssetExpectations(1, 1, 1, 1), List.of(missingDescriptor),
				new QuestionCorpusSummary(1, 1, 0, 0, 0, 0, 0), new McqExplanationSummary(0, 0, 0), Set.of());

		// Ordinary Question completeness alone is insufficient. Every Question must
		// reach descriptor-level classification before the Exam can be completed.
		assertFalse(classificationWorkRemaining.isReadyForCompletion());
	}

	@Test
	void expectedAssetCountMismatchIsAdvisoryForCompletedExam() {
		BookletCorpusStatus paper1Status = BookletCorpusAudit.assess(fixture.paper1, List.of(), null, true);
		BookletCorpusStatus paper2Status = BookletCorpusAudit.assess(fixture.paper2, List.of(), null, true);
		ExamAssetExpectations expectations = new ExamAssetExpectations(3, 2, 2, 1);
		ExamCorpusStatus status = ExamCorpusAudit.assess(fixture.exam, expectations,
				List.of(paper1Status, paper2Status));

		// Both planning discrepancies are audit findings, but neither is allowed to
		// reverse the user's explicit COMPLETE declaration.
		assertTrue(status.hasFinding(ExamCorpusFinding.EXPECTED_QUESTION_BOOKLET_COUNT_MISMATCH));
		assertTrue(status.hasFinding(ExamCorpusFinding.EXPECTED_ANSWER_FILE_COUNT_MISMATCH));
		assertEquals(ExamCaptureState.COMPLETE, status.declaredCaptureState());
		assertTrue(fixture.exam.isComplete());
		assertTrue(status.requiresAttention());
	}

	@Test
	void matchingAssetExpectationsProduceNoExamLevelFindings() {
		BookletCorpusStatus paper1Status = BookletCorpusAudit.assess(fixture.paper1, List.of(), null, true);
		BookletCorpusStatus paper2Status = BookletCorpusAudit.assess(fixture.paper2, List.of(), null, true);
		ExamAssetExpectations expectations = new ExamAssetExpectations(2, 2, 1, 1);
		ExamCorpusStatus status = ExamCorpusAudit.assess(fixture.exam, expectations,
				List.of(paper1Status, paper2Status));

		// Matching structural planning values leave Exam-level findings clear even
		// though booklet or Question findings may still be inspected independently.
		assertTrue(status.findings().isEmpty());
		assertFalse(status.hasFinding(ExamCorpusFinding.EXPECTED_QUESTION_BOOKLET_COUNT_MISMATCH));
		assertFalse(status.hasFinding(ExamCorpusFinding.EXPECTED_ANSWER_FILE_COUNT_MISMATCH));
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
		private final ExamBooklet paper1 = new ExamBooklet(8, exam, "Paper 1",
				new SourceDocument(9, "Chemistry/2025/paper1.pdf"), ExamBookletQuestionFormat.MULTIPLE_CHOICE, 1);
		private final ExamBooklet paper2 = new ExamBooklet(10, exam, "Paper 2",
				new SourceDocument(11, "Chemistry/2025/paper2.pdf"), ExamBookletQuestionFormat.WRITTEN_RESPONSE, 1);
		private final AnswerFile answerFile = new AnswerFile(12, exam, "Marking guide",
				new SourceDocument(13, "Chemistry/2025/answers.pdf"));

		private Question multipleChoiceQuestion(long id, ExamBooklet booklet, String code) {
			QuestionRegion region = new QuestionRegion(booklet, 1, 0.10, 0.10, 0.70, 0.20);

			// MCQ fixtures obey the production one-mark invariant so audit assertions
			// exercise only completeness behaviour.
			return new Question(id, booklet, code, "", 1, List.of(region), classification, false, null, null,
					QuestionResponseType.MULTIPLE_CHOICE);
		}

		private Question writtenQuestion(long id, ExamBooklet booklet, String code) {
			QuestionRegion region = new QuestionRegion(booklet, 1, 0.10, 0.10, 0.70, 0.20);

			// Written-response fixtures deliberately omit their Answer unless a test
			// explicitly supplies one.
			return new Question(id, booklet, code, "", 2, List.of(region), classification, false, null, null,
					QuestionResponseType.WRITTEN_RESPONSE);
		}
	}
}
