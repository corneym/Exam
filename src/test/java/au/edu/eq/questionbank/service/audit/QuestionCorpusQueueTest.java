package au.edu.eq.questionbank.service.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

class QuestionCorpusQueueTest {

	private final Fixture fixture = new Fixture();

	@Test
	void allFilterUsesSourceOrder() {
		List<QuestionCorpusWorkItem> items = QuestionCorpusQueue.build(fixture.questions(), QuestionCorpusFilter.all());
		assertEquals(List.of("Q3", "Q1", "Q2", "Q4"),
				items.stream().map(item -> item.question().getQuestionCode()).toList());
	}

	@Test
	void filtersByCompletionAndSpecificProblem() {
		QuestionCorpusFilter filter = new QuestionCorpusFilter(null, null, null, null,
				QuestionCorpusCompletionFilter.INCOMPLETE, QuestionCorpusProblem.MISSING_ANSWER);
		List<QuestionCorpusWorkItem> items = QuestionCorpusQueue.build(fixture.questions(), filter);
		assertEquals(1, items.size());
		assertEquals("Q2", items.getFirst().question().getQuestionCode());
	}

	@Test
	void filtersBySubjectProviderYearAndBooklet() {
		QuestionCorpusFilter filter = new QuestionCorpusFilter(fixture.chemistry.getId(), fixture.qcaa.getId(), 2024,
				fixture.paper1.getId(), QuestionCorpusCompletionFilter.ALL, null);
		List<QuestionCorpusWorkItem> items = QuestionCorpusQueue.build(fixture.questions(), filter);
		assertEquals(2, items.size());
		assertTrue(items.stream().allMatch(item -> item.question().getBooklet().getId() == fixture.paper1.getId()));
	}

	@Test
	void summaryCountsIndependentProblems() {
		QuestionCorpusSummary summary = QuestionCorpusQueue.summarise(fixture.questions());
		assertEquals(4, summary.totalQuestions());
		assertEquals(1, summary.completeQuestions());
		assertEquals(3, summary.incompleteQuestions());
		assertEquals(1, summary.missingQuestionSource());
		assertEquals(1, summary.missingAnswer());
		assertEquals(1, summary.unresolvedSharedContext());
		assertEquals(1, summary.unknownResponseType());
	}

	@Test
	void unknownResponseTypeIsNotReturnedAsMissingAnswer() {
		QuestionCorpusFilter missingAnswer = new QuestionCorpusFilter(null, null, null, null,
				QuestionCorpusCompletionFilter.ALL, QuestionCorpusProblem.MISSING_ANSWER);
		List<QuestionCorpusWorkItem> items = QuestionCorpusQueue.build(fixture.questions(), missingAnswer);
		assertTrue(items.stream().noneMatch(item -> "Q4".equals(item.question().getQuestionCode())));
	}

	private static final class Fixture {

		private final Subject chemistry = new Subject(1, "Chemistry");
		private final SyllabusVersion chemistrySyllabus = new SyllabusVersion(2, chemistry, "2025", true);
		private final Unit chemistryUnit = new Unit(3, chemistrySyllabus, "1", "Unit 1", 1);
		private final Topic chemistryTopic = new Topic(4, chemistrySyllabus, chemistryUnit, "1.1", "Topic 1", 1);
		private final Descriptor chemistryDescriptor = new Descriptor(5, chemistrySyllabus, chemistryTopic, "1.1.1",
				"Descriptor 1", 1);
		private final Subject physics = new Subject(6, "Physics");
		private final SyllabusVersion physicsSyllabus = new SyllabusVersion(7, physics, "2025", true);
		private final Unit physicsUnit = new Unit(8, physicsSyllabus, "1", "Unit 1", 1);
		private final Topic physicsTopic = new Topic(9, physicsSyllabus, physicsUnit, "1.1", "Topic 1", 1);
		private final Descriptor physicsDescriptor = new Descriptor(10, physicsSyllabus, physicsTopic, "1.1.1",
				"Descriptor 1", 1);
		private final ExamProvider qcaa = new ExamProvider(11, "QCAA");
		private final ExamProvider otherProvider = new ExamProvider(12, "Other");
		private final Exam chemistry2024 = new Exam(13, chemistry, qcaa, 2024, "External Assessment");
		private final Exam chemistry2023 = new Exam(14, chemistry, otherProvider, 2023, "External Assessment");
		private final Exam physics2024 = new Exam(15, physics, qcaa, 2024, "External Assessment");
		private final ExamBooklet paper1 = new ExamBooklet(16, chemistry2024, "Paper 1",
				new SourceDocument(17, "Chemistry/2024/paper1.pdf"));
		private final ExamBooklet paper2 = new ExamBooklet(18, chemistry2023, "Paper 2",
				new SourceDocument(19, "Chemistry/2023/paper2.pdf"));
		private final ExamBooklet physicsPaper = new ExamBooklet(20, physics2024, "Paper 1",
				new SourceDocument(21, "Physics/2024/paper1.pdf"));
		private final AnswerFile chemistry2023Answers = new AnswerFile(24, chemistry2023, "Answers",
				new SourceDocument(25, "Chemistry/2023/answers.pdf"));

		private Question question(long id, ExamBooklet booklet, Descriptor classification, String code,
				QuestionResponseType responseType, boolean withRegion, boolean preambleRequired) {
			List<QuestionRegion> regions = withRegion ? List.of(new QuestionRegion(booklet, 1, 0.10, 0.10, 0.70, 0.20))
					: List.of();
			return new Question(id, booklet, code, "", 2, regions, classification, preambleRequired, null, null,
					responseType);
		}

		private List<Question> questions() {
			Question complete = question(30, paper1, chemistryDescriptor, "Q1", QuestionResponseType.MULTIPLE_CHOICE,
					true, false);
			complete.setAnswer(new Answer(40, "A", List.of()));
			Question missingAnswer = question(31, paper1, chemistryDescriptor, "Q2",
					QuestionResponseType.WRITTEN_RESPONSE, true, false);
			Question missingSourceAndUnresolvedContext = question(32, paper2, chemistryDescriptor, "Q3",
					QuestionResponseType.WRITTEN_RESPONSE, false, true);
			missingSourceAndUnresolvedContext.setAnswer(
					new Answer(41, null, List.of(new AnswerRegion(chemistry2023Answers, 1, 0.10, 0.10, 0.70, 0.20))));
			Question unknown = question(33, physicsPaper, physicsDescriptor, "Q4", QuestionResponseType.UNKNOWN, true,
					false);
			return List.of(complete, missingAnswer, missingSourceAndUnresolvedContext, unknown);
		}
	}
}
