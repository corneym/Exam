package au.edu.eq.questionbank.service.revision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;

class RevisionPresentationGroupingTest {

	private final RevisionPresentationPlanner planner = new RevisionPresentationPlanner();

	@Test
	void completeDescriptorCoverageAllowsDescriptorGrouping() {
		Fixture fixture = new Fixture();
		Question first2024 = fixture.question(20, "4", 2024, fixture.qcaa);
		Question first2021 = fixture.question(21, "2", 2021, fixture.qcaa);
		Question second2022 = fixture.question(22, "1", 2022, fixture.qcaa);
		RevisionCorpus corpus = fixture.fourLevelCorpus(List.of(),
				List.of(fixture.placement(first2024, fixture.firstDescriptor, 1),
						fixture.placement(first2021, fixture.firstDescriptor, 2)),
				List.of(fixture.placement(second2022, fixture.secondDescriptor, 3)));
		assertTrue(planner.isDescriptorGroupingAvailable(corpus));
		RevisionPresentationPlan plan = planner.plan(corpus, RevisionGroupingMode.DESCRIPTOR);
		assertEquals(RevisionGroupingMode.DESCRIPTOR, plan.getGroupingMode());
		assertTrue(fixture.presentations(plan, fixture.subtopic).isEmpty());

		// Questions within one Descriptor are chronological.
		assertEquals(List.of(21L, 20L), fixture.presentationQuestionIds(plan, fixture.firstDescriptor));
		assertEquals(List.of(22L), fixture.presentationQuestionIds(plan, fixture.secondDescriptor));
	}

	@Test
	void descriptorGroupingIsUnavailableWhenRenderableSubtopicPlacementExists() {
		Fixture fixture = new Fixture();
		Question directSubtopic = fixture.question(1, "90", 2024, fixture.qcaa);
		Question descriptorQuestion = fixture.question(2, "1", 2020, fixture.qcaa);
		RevisionCorpus corpus = fixture.fourLevelCorpus(List.of(fixture.placement(directSubtopic, fixture.subtopic, 1)),
				List.of(fixture.placement(descriptorQuestion, fixture.firstDescriptor, 2)), List.of());
		assertFalse(planner.isDescriptorGroupingAvailable(corpus));

		// Descriptor output must never silently discard a broader Subtopic placement.
		assertThrows(IllegalArgumentException.class, () -> planner.plan(corpus, RevisionGroupingMode.DESCRIPTOR));
		RevisionPresentationPlan plan = planner.plan(corpus);

		// The default planner chooses the finest safe grouping automatically.
		assertEquals(RevisionGroupingMode.SUBTOPIC, plan.getGroupingMode());

		// Direct Subtopic Questions precede the Descriptor-derived groups. The year
		// ordering applies within each curriculum bucket, not across Descriptor
		// boundaries.
		assertEquals(List.of(1L, 2L), fixture.presentationQuestionIds(plan, fixture.subtopic));

		// Child Descriptor presentations have been consumed by the Subtopic roll-up.
		assertTrue(fixture.presentations(plan, fixture.firstDescriptor).isEmpty());
	}

	@Test
	void subtopicGroupingUsesDescriptorOrderThenYearThenSourceOrder() {
		Fixture fixture = new Fixture();
		Question firstDescriptor2024 = fixture.question(10, "4", 2024, fixture.qcaa);
		Question firstDescriptor2022Qcaa = fixture.question(11, "3", 2022, fixture.qcaa);
		Question firstDescriptor2022Alpha = fixture.question(12, "7", 2022, fixture.alpha);
		Question secondDescriptor2021 = fixture.question(13, "1", 2021, fixture.qcaa);
		RevisionCorpus corpus = fixture.fourLevelCorpus(List.of(),
				List.of(fixture.placement(firstDescriptor2024, fixture.firstDescriptor, 1),
						fixture.placement(firstDescriptor2022Qcaa, fixture.firstDescriptor, 2),
						fixture.placement(firstDescriptor2022Alpha, fixture.firstDescriptor, 3)),
				List.of(fixture.placement(secondDescriptor2021, fixture.secondDescriptor, 4),

						// The same Question may legitimately be applicable to more than one Descriptor.
						// Once those Descriptors are rolled into one Subtopic, emit the Question only
						// once.
						fixture.placement(firstDescriptor2022Qcaa, fixture.secondDescriptor, 5)));
		RevisionPresentationPlan plan = planner.plan(corpus, RevisionGroupingMode.SUBTOPIC);
		assertEquals(RevisionGroupingMode.SUBTOPIC, plan.getGroupingMode());

		// Descriptor 1 comes before Descriptor 2 even though Descriptor 2 contains an
		// older Question. Inside Descriptor 1, 2022 precedes 2024. The two 2022
		// Questions are then ordered by established source order, Alpha before QCAA.
		assertEquals(List.of(12L, 11L, 10L, 13L), fixture.presentationQuestionIds(plan, fixture.subtopic));
		assertTrue(fixture.presentations(plan, fixture.firstDescriptor).isEmpty());
		assertTrue(fixture.presentations(plan, fixture.secondDescriptor).isEmpty());
	}

	@Test
	void threeLevelCurriculumRollsDescriptorsIntoTopicForSubtopicMode() {
		Fixture fixture = new Fixture();
		Question firstDescriptor2023 = fixture.question(30, "6", 2023, fixture.qcaa);
		Question firstDescriptor2020 = fixture.question(31, "2", 2020, fixture.qcaa);
		Question secondDescriptor2019 = fixture.question(32, "1", 2019, fixture.qcaa);
		RevisionCorpus corpus = fixture.threeLevelCorpus(
				List.of(fixture.placement(firstDescriptor2023, fixture.directFirstDescriptor, 1),
						fixture.placement(firstDescriptor2020, fixture.directFirstDescriptor, 2)),
				List.of(fixture.placement(secondDescriptor2019, fixture.directSecondDescriptor, 3)));
		RevisionPresentationPlan plan = planner.plan(corpus, RevisionGroupingMode.SUBTOPIC);
		assertEquals(RevisionGroupingMode.SUBTOPIC, plan.getGroupingMode());

		// With no real Subtopic node, the Topic is the practical final bucket.
		// Descriptor order still takes precedence over year across Descriptors.
		assertEquals(List.of(31L, 30L, 32L), fixture.presentationQuestionIds(plan, fixture.directDescriptorTopic));
		assertTrue(fixture.presentations(plan, fixture.directFirstDescriptor).isEmpty());
		assertTrue(fixture.presentations(plan, fixture.directSecondDescriptor).isEmpty());
	}

	private static final class Fixture {

		private final Subject chemistry = new Subject(1, "Chemistry");
		private final SyllabusVersion currentVersion = new SyllabusVersion(2, chemistry, "2025", true);
		private final Unit unit = new Unit(10, currentVersion, "4", "Unit 4", 1);
		private final Topic subtopicTopic = new Topic(11, currentVersion, unit, "4.1", "Organic chemistry", 1);
		private final Subtopic subtopic = new Subtopic(12, currentVersion, subtopicTopic, "4.1.1", "Organic compounds",
				1);
		private final Descriptor firstDescriptor = new Descriptor(13, currentVersion, subtopic, "4.1.1.1",
				"First descriptor", 1);
		private final Descriptor secondDescriptor = new Descriptor(14, currentVersion, subtopic, "4.1.1.2",
				"Second descriptor", 2);
		private final Topic directDescriptorTopic = new Topic(20, currentVersion, unit, "4.2",
				"Direct descriptor topic", 2);
		private final Descriptor directFirstDescriptor = new Descriptor(21, currentVersion, directDescriptorTopic,
				"4.2.1", "Direct first descriptor", 1);
		private final Descriptor directSecondDescriptor = new Descriptor(22, currentVersion, directDescriptorTopic,
				"4.2.2", "Direct second descriptor", 2);
		private final ExamProvider alpha = new ExamProvider(100, "Alpha Authority");
		private final ExamProvider qcaa = new ExamProvider(101, "QCAA");

		@SafeVarargs
		private final RevisionCorpus corpus(RevisionCorpusNode unitNode,
				List<RevisionQuestionPlacement>... placementGroups) {
			int applicablePlacements = 0;
			Set<Long> uniqueQuestionIds = new HashSet<>();
			for (List<RevisionQuestionPlacement> placements : placementGroups) {
				applicablePlacements += placements.size();
				for (RevisionQuestionPlacement placement : placements) {
					uniqueQuestionIds.add(placement.getQuestion().getId());
				}
			}
			int uniqueQuestions = uniqueQuestionIds.size();

			// Every Question created by this fixture has one source region and no Answer,
			// so all unique Questions are renderable.
			RevisionCorpusStatistics statistics = new RevisionCorpusStatistics(applicablePlacements, uniqueQuestions,
					uniqueQuestions, 0, 0, uniqueQuestions, 0);
			return new RevisionCorpus(chemistry, currentVersion, List.of(unitNode), statistics);
		}

		private RevisionPresentationNode findNode(List<RevisionPresentationNode> nodes, CurriculumNode expectedNode) {
			for (RevisionPresentationNode node : nodes) {
				if (node.getCurriculumNode().equals(expectedNode)) {
					return node;
				}
				RevisionPresentationNode found = findNode(node.getChildren(), expectedNode);
				if (found != null) {
					return found;
				}
			}
			return null;
		}

		private RevisionCorpus fourLevelCorpus(List<RevisionQuestionPlacement> directSubtopicPlacements,
				List<RevisionQuestionPlacement> firstDescriptorPlacements,
				List<RevisionQuestionPlacement> secondDescriptorPlacements) {
			RevisionCorpusNode firstDescriptorNode = new RevisionCorpusNode(firstDescriptor, List.of(),
					firstDescriptorPlacements);
			RevisionCorpusNode secondDescriptorNode = new RevisionCorpusNode(secondDescriptor, List.of(),
					secondDescriptorPlacements);
			RevisionCorpusNode subtopicNode = new RevisionCorpusNode(subtopic,
					List.of(firstDescriptorNode, secondDescriptorNode), directSubtopicPlacements);
			RevisionCorpusNode topicNode = new RevisionCorpusNode(subtopicTopic, List.of(subtopicNode), List.of());
			RevisionCorpusNode unitNode = new RevisionCorpusNode(unit, List.of(topicNode), List.of());
			return corpus(unitNode, directSubtopicPlacements, firstDescriptorPlacements, secondDescriptorPlacements);
		}

		private RevisionQuestionPlacement placement(Question question, CurriculumNode currentNode, int revisionNumber) {
			return new RevisionQuestionPlacement(question, currentNode, revisionNumber);
		}

		private List<Long> presentationQuestionIds(RevisionPresentationPlan plan, CurriculumNode node) {
			List<Long> result = new ArrayList<>();
			for (RevisionQuestionPresentation presentation : presentations(plan, node)) {
				for (Question member : presentation.getMembers()) {
					result.add(member.getId());
				}
			}
			return result;
		}

		private List<RevisionQuestionPresentation> presentations(RevisionPresentationPlan plan,
				CurriculumNode expectedNode) {
			RevisionPresentationNode node = findNode(plan.getRootNodes(), expectedNode);
			if (node == null) {
				throw new AssertionError("Presentation node not found: " + expectedNode.getCode());
			}
			return node.getPresentations();
		}

		private Question question(long id, String questionCode, int year, ExamProvider provider) {
			Exam exam = new Exam(1000 + id, chemistry, provider, year, "External Assessment");
			SourceDocument sourceDocument = new SourceDocument(2000 + id, "revision-source-" + id + ".pdf");
			ExamBooklet booklet = new ExamBooklet(3000 + id, exam, "Paper 1", sourceDocument);
			QuestionRegion region = new QuestionRegion(booklet, 1, 0.10, 0.10, 0.50, 0.20);

			// Original stored classification is deliberately independent of the current
			// applicability placement being tested.
			return new Question(id, booklet, questionCode, "", 1, List.of(region), firstDescriptor, false);
		}

		private RevisionCorpus threeLevelCorpus(List<RevisionQuestionPlacement> firstDescriptorPlacements,
				List<RevisionQuestionPlacement> secondDescriptorPlacements) {
			RevisionCorpusNode firstDescriptorNode = new RevisionCorpusNode(directFirstDescriptor, List.of(),
					firstDescriptorPlacements);
			RevisionCorpusNode secondDescriptorNode = new RevisionCorpusNode(directSecondDescriptor, List.of(),
					secondDescriptorPlacements);
			RevisionCorpusNode topicNode = new RevisionCorpusNode(directDescriptorTopic,
					List.of(firstDescriptorNode, secondDescriptorNode), List.of());
			RevisionCorpusNode unitNode = new RevisionCorpusNode(unit, List.of(topicNode), List.of());
			return corpus(unitNode, firstDescriptorPlacements, secondDescriptorPlacements);
		}
	}
}
