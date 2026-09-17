package au.edu.eq.questionbank.service.revision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.PreambleStatus;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.SharedQuestionContext;
import au.edu.eq.questionbank.model.SharedQuestionContextRegion;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.SourceQuestion;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;

class RevisionPresentationPlannerTest {

	private final RevisionPresentationPlanner planner = new RevisionPresentationPlanner();

	@Test
	void conflictingSharedContextsWithinSourceGroupAreRejected() {
		Fixture fixture = new Fixture();
		SourceQuestion source = fixture.sourceQuestion(24);
		SharedQuestionContext firstContext = fixture.context(1, "First");
		SharedQuestionContext secondContext = fixture.context(2, "Second");
		Question partA = fixture.question(1, "24a", 2, source, firstContext);
		Question partB = fixture.question(2, "24b", 3, source, secondContext);
		RevisionCorpus corpus = fixture.corpus(List.of(partA, partB), List.of());
		assertThrows(IllegalStateException.class, () -> planner.plan(corpus));
	}

	@Test
	void groupsSameSourceWithinOneBucketAndSumsMarks() {
		Fixture fixture = new Fixture();
		SourceQuestion source = fixture.sourceQuestion(24);
		SharedQuestionContext context = fixture.context(1, "Question 24 preamble");
		Question partC = fixture.question(1, "24c", 1, source, context);
		Question partA = fixture.question(3, "24a", 2, source, context);
		Question partB = fixture.question(2, "24b", 3, source, context);
		RevisionPresentationPlan plan = planner.plan(fixture.corpus(List.of(partC, partA, partB), List.of()));
		RevisionQuestionPresentation presentation = fixture.presentations(plan, fixture.firstDescriptor).getFirst();
		assertEquals(1, fixture.presentations(plan, fixture.firstDescriptor).size());
		assertTrue(presentation.isMultipart());
		assertEquals(6, presentation.getTotalMarks());
		assertEquals(1, presentation.getRevisionNumber());
		assertEquals(List.of("24a", "24b", "24c"),
				presentation.getMembers().stream().map(Question::getQuestionCode).toList());
		assertSame(source, presentation.getSourceQuestion());
		assertSame(context, presentation.getSharedContext());
		assertTrue(presentation.shouldRenderSharedContext());
	}

	@Test
	void metadataOnlyPlacementDoesNotConsumePresentationNumber() {
		Fixture fixture = new Fixture();
		Question incomplete = fixture.metadataOnlyQuestion(1, "1");
		Question complete = fixture.question(2, "2", 2, null, null);
		RevisionPresentationPlan plan = planner.plan(fixture.corpus(List.of(incomplete, complete), List.of()));
		List<RevisionQuestionPresentation> presentations = fixture.presentations(plan, fixture.firstDescriptor);
		assertEquals(1, presentations.size());
		assertSame(complete, presentations.getFirst().getMembers().getFirst());
		assertEquals(1, presentations.getFirst().getRevisionNumber());
	}

	@Test
	void sameSourceInDifferentBucketsProducesSeparatePresentations() {
		Fixture fixture = new Fixture();
		SourceQuestion source = fixture.sourceQuestion(24);
		SharedQuestionContext context = fixture.context(1, "Question 24 preamble");
		Question partA = fixture.question(1, "24a", 2, source, context);
		Question partB = fixture.question(2, "24b", 4, source, context);
		RevisionPresentationPlan plan = planner.plan(fixture.corpus(List.of(partA), List.of(partB)));
		RevisionQuestionPresentation first = fixture.presentations(plan, fixture.firstDescriptor).getFirst();
		RevisionQuestionPresentation second = fixture.presentations(plan, fixture.secondDescriptor).getFirst();
		assertFalse(first.isMultipart());
		assertFalse(second.isMultipart());
		assertEquals(2, first.getTotalMarks());
		assertEquals(4, second.getTotalMarks());
		assertEquals(1, first.getRevisionNumber());
		assertEquals(2, second.getRevisionNumber());
		assertTrue(first.shouldRenderSharedContext());
		assertTrue(second.shouldRenderSharedContext());
	}

	@Test
	void sharedContextDoesNotGroupIndependentQuestions() {
		Fixture fixture = new Fixture();
		SharedQuestionContext context = fixture.context(1, "MCQ shared graph");
		Question question8 = fixture.question(8, "8", 1, null, context);
		Question question9 = fixture.question(9, "9", 1, null, context);
		RevisionPresentationPlan plan = planner.plan(fixture.corpus(List.of(question8, question9), List.of()));
		List<RevisionQuestionPresentation> presentations = fixture.presentations(plan, fixture.firstDescriptor);
		assertEquals(2, presentations.size());
		assertFalse(presentations.get(0).isMultipart());
		assertFalse(presentations.get(1).isMultipart());
		assertSame(question8, presentations.get(0).getMembers().getFirst());
		assertSame(question9, presentations.get(1).getMembers().getFirst());
		assertTrue(presentations.get(0).shouldRenderSharedContext());
		assertFalse(presentations.get(1).shouldRenderSharedContext());
	}

	@Test
	void singleSourceMemberBehavesAsOrdinaryPresentation() {
		Fixture fixture = new Fixture();
		SourceQuestion source = fixture.sourceQuestion(24);
		Question partA = fixture.question(1, "24a", 2, source, null);
		RevisionPresentationPlan plan = planner.plan(fixture.corpus(List.of(partA), List.of()));
		RevisionQuestionPresentation presentation = fixture.presentations(plan, fixture.firstDescriptor).getFirst();
		assertFalse(presentation.isMultipart());
		assertEquals(1, presentation.getMembers().size());
		assertEquals(2, presentation.getTotalMarks());
		assertSame(source, presentation.getSourceQuestion());
	}

	private static final class Fixture {

		private final Subject chemistry;
		private final SyllabusVersion historicalVersion;
		private final SyllabusVersion currentVersion;
		private final Descriptor historicalDescriptor;
		private final Unit currentUnit;
		private final Topic currentTopic;
		private final Descriptor firstDescriptor;
		private final Descriptor secondDescriptor;
		private final ExamBooklet booklet;
		private final QuestionRegion region;

		private Fixture() {
			chemistry = new Subject(1, "Chemistry");
			historicalVersion = new SyllabusVersion(1, chemistry, "2019", false);
			Unit historicalUnit = new Unit(100, historicalVersion, "1", "Historical unit", 1);
			Topic historicalTopic = new Topic(101, historicalVersion, historicalUnit, "1.1", "Historical topic", 1);
			historicalDescriptor = new Descriptor(102, historicalVersion, historicalTopic, "1.1.1",
					"Historical descriptor", 1);
			currentVersion = new SyllabusVersion(2, chemistry, "2025", true);
			currentUnit = new Unit(10, currentVersion, "1", "Unit 1", 1);
			currentTopic = new Topic(11, currentVersion, currentUnit, "1.1", "Topic 1", 1);
			firstDescriptor = new Descriptor(12, currentVersion, currentTopic, "1.1.1", "First descriptor", 1);
			secondDescriptor = new Descriptor(13, currentVersion, currentTopic, "1.1.2", "Second descriptor", 2);
			ExamProvider provider = new ExamProvider(1, "QCAA");
			Exam exam = new Exam(1, chemistry, provider, 2024, "External Assessment");
			booklet = new ExamBooklet(1, exam, "Paper 2", new SourceDocument(1, "chemistry.pdf"));
			region = new QuestionRegion(booklet, 1, 0.10, 0.10, 0.50, 0.20);
		}

		private SharedQuestionContext context(long id, String label) {
			return new SharedQuestionContext(id, booklet, label,
					List.of(new SharedQuestionContextRegion(1, 0.10, 0.05, 0.80, 0.10)));
		}

		private RevisionCorpus corpus(List<Question> firstBucket, List<Question> secondBucket) {
			int revisionNumber = 1;
			List<RevisionQuestionPlacement> firstPlacements = new ArrayList<>();
			for (Question question : firstBucket) {
				int number = question.getRegions().isEmpty() ? 0 : revisionNumber++;
				firstPlacements.add(new RevisionQuestionPlacement(question, firstDescriptor, number));
			}
			List<RevisionQuestionPlacement> secondPlacements = new ArrayList<>();
			for (Question question : secondBucket) {
				int number = question.getRegions().isEmpty() ? 0 : revisionNumber++;
				secondPlacements.add(new RevisionQuestionPlacement(question, secondDescriptor, number));
			}
			RevisionCorpusNode firstDescriptorNode = new RevisionCorpusNode(firstDescriptor, List.of(),
					firstPlacements);
			RevisionCorpusNode secondDescriptorNode = new RevisionCorpusNode(secondDescriptor, List.of(),
					secondPlacements);
			RevisionCorpusNode topicNode = new RevisionCorpusNode(currentTopic,
					List.of(firstDescriptorNode, secondDescriptorNode), List.of());
			RevisionCorpusNode unitNode = new RevisionCorpusNode(currentUnit, List.of(topicNode), List.of());
			Set<Long> uniqueQuestionIds = new HashSet<>();
			int renderable = 0;
			for (Question question : firstBucket) {
				if (uniqueQuestionIds.add(question.getId()) && !question.getRegions().isEmpty()) {
					renderable++;
				}
			}
			for (Question question : secondBucket) {
				if (uniqueQuestionIds.add(question.getId()) && !question.getRegions().isEmpty()) {
					renderable++;
				}
			}
			int unique = uniqueQuestionIds.size();
			RevisionCorpusStatistics statistics = new RevisionCorpusStatistics(firstBucket.size() + secondBucket.size(),
					unique, renderable, unique - renderable, 0, unique, 0);
			return new RevisionCorpus(chemistry, currentVersion, List.of(unitNode), statistics);
		}

		private RevisionPresentationNode findNode(List<RevisionPresentationNode> nodes, CurriculumNode expected) {
			for (RevisionPresentationNode node : nodes) {
				if (node.getCurriculumNode().equals(expected)) {
					return node;
				}
				RevisionPresentationNode found = findNode(node.getChildren(), expected);
				if (found != null) {
					return found;
				}
			}
			return null;
		}

		private Question metadataOnlyQuestion(long id, String code) {
			return new Question(id, booklet, code, "", 1, List.of(), historicalDescriptor, false);
		}

		private List<RevisionQuestionPresentation> presentations(RevisionPresentationPlan plan,
				CurriculumNode expectedNode) {
			RevisionPresentationNode node = findNode(plan.getRootNodes(), expectedNode);
			return node.getPresentations();
		}

		private Question question(long id, String code, int marks, SourceQuestion sourceQuestion,
				SharedQuestionContext sharedContext) {
			return new Question(id, booklet, code, "", marks, List.of(region), historicalDescriptor, false,
					sourceQuestion, sharedContext);
		}

		private SourceQuestion sourceQuestion(long id) {
			return new SourceQuestion(id, booklet, Long.toString(id), PreambleStatus.PRESENT);
		}
	}
}
