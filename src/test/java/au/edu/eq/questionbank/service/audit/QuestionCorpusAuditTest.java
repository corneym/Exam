package au.edu.eq.questionbank.service.audit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import au.edu.eq.questionbank.model.Answer;
import au.edu.eq.questionbank.model.AnswerFile;
import au.edu.eq.questionbank.model.AnswerRegion;
import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;

class QuestionCorpusAuditTest {

	private final Fixture fixture = new Fixture();

	@Test
	void completeMultipleChoiceRequiresOnlyValidLetter() {
		Question question = fixture.question(QuestionResponseType.MULTIPLE_CHOICE, true, false);
		question.setAnswer(new Answer(101, "B", List.of()));
		QuestionCorpusStatus status = QuestionCorpusAudit.assess(question);
		assertTrue(status.answerComplete());
		assertTrue(status.isComplete());
	}

	@Test
	void completeWrittenResponseHasNoProblems() {
		Question question = fixture.question(QuestionResponseType.WRITTEN_RESPONSE, true, false);
		question.setAnswer(new Answer(100, null, List.of(fixture.answerRegion())));
		QuestionCorpusStatus status = QuestionCorpusAudit.assess(question);
		assertTrue(status.questionSourceCaptured());
		assertTrue(status.responseTypeResolved());
		assertTrue(status.answerComplete());
		assertTrue(status.sharedContextResolved());
		assertTrue(status.isComplete());
		assertTrue(status.problems().isEmpty());
	}

	@Test
	void missingQuestionRegionIsReportedIndependently() {
		Question question = fixture.question(QuestionResponseType.MULTIPLE_CHOICE, false, false);
		question.setAnswer(new Answer(102, "A", List.of()));
		QuestionCorpusStatus status = QuestionCorpusAudit.assess(question);
		assertFalse(status.questionSourceCaptured());
		assertTrue(status.hasProblem(QuestionCorpusProblem.MISSING_QUESTION_SOURCE));
		assertFalse(status.hasProblem(QuestionCorpusProblem.MISSING_ANSWER));
	}

	@Test
	void multipleChoiceRegionWithoutValidLetterIsIncomplete() {
		Question question = fixture.question(QuestionResponseType.MULTIPLE_CHOICE, true, false);
		question.setAnswer(new Answer(105, null, List.of(fixture.answerRegion())));
		QuestionCorpusStatus status = QuestionCorpusAudit.assess(question);
		assertFalse(status.answerComplete());
		assertTrue(status.hasProblem(QuestionCorpusProblem.MISSING_ANSWER));
	}

	@Test
	void unknownResponseTypeDoesNotAlsoReportMissingAnswer() {
		Question question = fixture.question(QuestionResponseType.UNKNOWN, true, false);
		QuestionCorpusStatus status = QuestionCorpusAudit.assess(question);
		assertFalse(status.responseTypeResolved());
		assertFalse(status.answerComplete());
		assertTrue(status.hasProblem(QuestionCorpusProblem.UNKNOWN_RESPONSE_TYPE));
		assertFalse(status.hasProblem(QuestionCorpusProblem.MISSING_ANSWER));
	}

	@Test
	void unresolvedSharedContextIsReportedIndependently() {
		Question question = fixture.question(QuestionResponseType.MULTIPLE_CHOICE, true, true);
		question.setAnswer(new Answer(103, "C", List.of()));
		QuestionCorpusStatus status = QuestionCorpusAudit.assess(question);
		assertFalse(status.sharedContextResolved());
		assertTrue(status.hasProblem(QuestionCorpusProblem.UNRESOLVED_SHARED_CONTEXT));
		assertFalse(status.isComplete());
	}

	@Test
	void writtenResponseTextWithoutRegionIsIncomplete() {
		Question question = fixture.question(QuestionResponseType.WRITTEN_RESPONSE, true, false);
		question.setAnswer(new Answer(104, "legacy text", List.of()));
		QuestionCorpusStatus status = QuestionCorpusAudit.assess(question);
		assertFalse(status.answerComplete());
		assertTrue(status.hasProblem(QuestionCorpusProblem.MISSING_ANSWER));
	}

	private static final class Fixture {

		private final Subject subject = new Subject(1, "Chemistry");
		private final SyllabusVersion syllabus = new SyllabusVersion(2, subject, "2025", true);
		private final Unit unit = new Unit(3, syllabus, "1", "Unit 1", 1);
		private final Topic topic = new Topic(4, syllabus, unit, "1.1", "Topic 1", 1);
		private final Descriptor classification = new Descriptor(5, syllabus, topic, "1.1.1", "Descriptor 1", 1);
		private final ExamProvider provider = new ExamProvider(6, "QCAA");
		private final Exam exam = new Exam(7, subject, provider, 2025, "External Assessment");
		private final ExamBooklet booklet = new ExamBooklet(8, exam, "Paper 1",
				new SourceDocument(9, "Chemistry/2025/paper1.pdf"));
		private final AnswerFile answerFile = new AnswerFile(10, exam, "Marking guide",
				new SourceDocument(11, "Chemistry/2025/answers.pdf"));

		private AnswerRegion answerRegion() {
			return new AnswerRegion(answerFile, 1, 0.10, 0.20, 0.70, 0.20);
		}

		private Question question(QuestionResponseType responseType, boolean withQuestionRegion,
				boolean preambleCaptureRequired) {
			List<QuestionRegion> regions = withQuestionRegion
					? List.of(new QuestionRegion(booklet, 1, 0.10, 0.10, 0.70, 0.20))
					: List.of();
			return new Question(20, booklet, "Q1", "", 2, regions, classification, preambleCaptureRequired, null, null,
					responseType);
		}
	}
}
