package au.edu.eq.questionbank.service.revision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import au.edu.eq.questionbank.model.Answer;
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
import au.edu.eq.questionbank.repository.assessment.InMemoryQuestionOutputApplicabilityRepository;
import au.edu.eq.questionbank.repository.assessment.QuestionApplicabilityMatch;
import au.edu.eq.questionbank.repository.assessment.QuestionRetrievalRepository;
import au.edu.eq.questionbank.repository.curriculum.InMemoryCurriculumRepository;
import au.edu.eq.questionbank.service.retrieval.CurriculumSearchNodeExpansionService;
import au.edu.eq.questionbank.service.retrieval.QuestionRetrievalService;

class RevisionCorpusBuilderTest {

	private static List<CurriculumNode> curriculumNodes(List<RevisionCorpusNode> corpusNodes) {
		return corpusNodes.stream().map(RevisionCorpusNode::getCurriculumNode).toList();
	}

	private static RevisionCorpusNode findNode(RevisionCorpus corpus, CurriculumNode expected) {
		for (RevisionCorpusNode root : corpus.getRootNodes()) {
			RevisionCorpusNode result = findNode(root, expected);
			if (result != null) {
				return result;
			}
		}
		throw new AssertionError("Curriculum node not present in corpus: " + expected);
	}

	private static RevisionCorpusNode findNode(RevisionCorpusNode current, CurriculumNode expected) {
		if (current.getCurriculumNode().equals(expected)) {
			return current;
		}
		for (RevisionCorpusNode child : current.getChildren()) {
			RevisionCorpusNode result = findNode(child, expected);
			if (result != null) {
				return result;
			}
		}
		return null;
	}

	@Test
	void buildsCompleteCurrentHierarchyInDisplayOrder() {
		Fixture fixture = new Fixture();
		RevisionCorpus corpus = fixture.createBuilder(_ -> List.of()).build(fixture.chemistry);
		assertSame(fixture.chemistry, corpus.getSubject());
		assertSame(fixture.currentVersion, corpus.getSyllabusVersion());
		assertEquals(List.of(fixture.firstUnit, fixture.secondUnit), curriculumNodes(corpus.getRootNodes()));
		RevisionCorpusNode firstUnit = corpus.getRootNodes().get(0);
		assertEquals(List.of(fixture.descriptorModeTopic, fixture.subtopicModeTopic),
				curriculumNodes(firstUnit.getChildren()));
		assertEquals(List.of(fixture.directDescriptor), curriculumNodes(firstUnit.getChildren().get(0).getChildren()));
		RevisionCorpusNode subtopicModeTopic = firstUnit.getChildren().get(1);
		assertEquals(List.of(fixture.currentSubtopic), curriculumNodes(subtopicModeTopic.getChildren()));
		assertEquals(List.of(fixture.subtopicDescriptor),
				curriculumNodes(subtopicModeTopic.getChildren().get(0).getChildren()));
	}

	@Test
	void duplicateMatchesProduceOnePlacementAndQuestionsAreOrderedById() {
		Fixture fixture = new Fixture();
		QuestionRetrievalRepository retrievalRepository = _ -> List.of(
				new QuestionApplicabilityMatch(fixture.laterQuestion, fixture.directDescriptor),
				new QuestionApplicabilityMatch(fixture.renderableQuestion, fixture.directDescriptor),
				new QuestionApplicabilityMatch(fixture.renderableQuestion, fixture.directDescriptor));
		RevisionCorpus corpus = fixture.createBuilder(retrievalRepository).build(fixture.chemistry);
		List<RevisionQuestionPlacement> placements = findNode(corpus, fixture.directDescriptor).getQuestionPlacements();
		assertEquals(2, placements.size());
		assertSame(fixture.renderableQuestion, placements.get(0).getQuestion());
		assertSame(fixture.laterQuestion, placements.get(1).getQuestion());
	}

	@Test
	void emptyCorpusHasZeroStatistics() {
		Fixture fixture = new Fixture();
		RevisionCorpus corpus = fixture.createBuilder(_ -> List.of()).build(fixture.chemistry);
		RevisionCorpusStatistics statistics = corpus.getStatistics();
		assertEquals(0, statistics.getApplicablePlacements());
		assertEquals(0, statistics.getUniqueApplicableQuestions());
		assertEquals(0, statistics.getRenderableQuestions());
		assertEquals(0, statistics.getMissingQuestionRegionQuestions());
		assertEquals(0, statistics.getQuestionsWithAnswers());
		assertEquals(0, statistics.getQuestionsWithoutAnswers());
		assertEquals(0, statistics.getSharedContextReviewQuestions());
	}

	@Test
	void excludesOnlySpecifiedQuestionApplicabilityPlacement() {
		Fixture fixture = new Fixture();
		QuestionRetrievalRepository retrievalRepository = _ -> List.of(
				new QuestionApplicabilityMatch(fixture.renderableQuestion, fixture.directDescriptor),
				new QuestionApplicabilityMatch(fixture.renderableQuestion, fixture.subtopicDescriptor),
				new QuestionApplicabilityMatch(fixture.laterQuestion, fixture.directDescriptor));
		InMemoryQuestionOutputApplicabilityRepository outputApplicabilityRepository = new InMemoryQuestionOutputApplicabilityRepository();
		outputApplicabilityRepository.setExcluded(fixture.renderableQuestion, fixture.directDescriptor, true);
		RevisionCorpus corpus = fixture.createBuilder(retrievalRepository, outputApplicabilityRepository)
				.build(fixture.chemistry);
		List<RevisionQuestionPlacement> directPlacements = findNode(corpus, fixture.directDescriptor)
				.getQuestionPlacements();
		List<RevisionQuestionPlacement> nestedPlacements = findNode(corpus, fixture.subtopicDescriptor)
				.getQuestionPlacements();

		// Excluding Question 2 from one Descriptor must not remove Question 3 from
		// that Descriptor or Question 2 from its other valid current placement.
		assertEquals(1, directPlacements.size());
		assertSame(fixture.laterQuestion, directPlacements.getFirst().getQuestion());
		assertEquals(1, nestedPlacements.size());
		assertSame(fixture.renderableQuestion, nestedPlacements.getFirst().getQuestion());
		RevisionCorpusStatistics statistics = corpus.getStatistics();

		// Statistics describe the final revision corpus after exclusions rather than
		// the larger set of curriculum-derived candidate placements.
		assertEquals(2, statistics.getApplicablePlacements());
		assertEquals(2, statistics.getUniqueApplicableQuestions());
		assertEquals(2, statistics.getRenderableQuestions());
	}

	@Test
	void placementExposesRenderabilityAnswerAndSharedContextState() {
		Fixture fixture = new Fixture();
		QuestionRetrievalRepository retrievalRepository = _ -> List.of(
				new QuestionApplicabilityMatch(fixture.subtopicQuestion, fixture.currentSubtopic),
				new QuestionApplicabilityMatch(fixture.renderableQuestion, fixture.directDescriptor));
		RevisionCorpus corpus = fixture.createBuilder(retrievalRepository).build(fixture.chemistry);
		RevisionQuestionPlacement incomplete = findNode(corpus, fixture.currentSubtopic).getQuestionPlacements().get(0);
		assertFalse(incomplete.isRenderable());
		assertFalse(incomplete.hasRevisionNumber());
		assertThrows(IllegalStateException.class, incomplete::getRevisionNumber);
		assertFalse(incomplete.hasAnswer());
		assertTrue(incomplete.issharedContextCaptureRequired());
		RevisionQuestionPlacement renderable = findNode(corpus, fixture.directDescriptor).getQuestionPlacements()
				.get(0);
		assertTrue(renderable.isRenderable());
		assertTrue(renderable.hasRevisionNumber());
		assertTrue(renderable.hasAnswer());
		assertFalse(renderable.issharedContextCaptureRequired());
	}

	@Test
	void placesQuestionAtEachReturnedCurrentApplicabilityNode() {
		Fixture fixture = new Fixture();
		QuestionRetrievalRepository retrievalRepository = _ -> List.of(
				new QuestionApplicabilityMatch(fixture.renderableQuestion, fixture.directDescriptor),
				new QuestionApplicabilityMatch(fixture.renderableQuestion, fixture.subtopicDescriptor),
				new QuestionApplicabilityMatch(fixture.subtopicQuestion, fixture.currentSubtopic));
		RevisionCorpus corpus = fixture.createBuilder(retrievalRepository).build(fixture.chemistry);
		RevisionCorpusNode directDescriptor = findNode(corpus, fixture.directDescriptor);
		RevisionCorpusNode subtopic = findNode(corpus, fixture.currentSubtopic);
		RevisionCorpusNode subtopicDescriptor = findNode(corpus, fixture.subtopicDescriptor);
		assertEquals(1, directDescriptor.getQuestionPlacements().size());
		assertSame(fixture.renderableQuestion, directDescriptor.getQuestionPlacements().get(0).getQuestion());
		assertEquals(1, subtopicDescriptor.getQuestionPlacements().size());
		assertSame(fixture.renderableQuestion, subtopicDescriptor.getQuestionPlacements().get(0).getQuestion());
		assertEquals(1, subtopic.getQuestionPlacements().size());
		assertSame(fixture.subtopicQuestion, subtopic.getQuestionPlacements().get(0).getQuestion());
		assertEquals(0, findNode(corpus, fixture.subtopicModeTopic).getQuestionPlacements().size());
	}

	@Test
	void questionExcludedFromEveryDerivedPlacementIsAbsentFromCorpusStatistics() {
		Fixture fixture = new Fixture();
		QuestionRetrievalRepository retrievalRepository = _ -> List.of(
				new QuestionApplicabilityMatch(fixture.renderableQuestion, fixture.directDescriptor),
				new QuestionApplicabilityMatch(fixture.renderableQuestion, fixture.subtopicDescriptor));
		InMemoryQuestionOutputApplicabilityRepository outputApplicabilityRepository = new InMemoryQuestionOutputApplicabilityRepository();
		outputApplicabilityRepository.setExcluded(fixture.renderableQuestion, fixture.directDescriptor, true);
		outputApplicabilityRepository.setExcluded(fixture.renderableQuestion, fixture.subtopicDescriptor, true);
		RevisionCorpus corpus = fixture.createBuilder(retrievalRepository, outputApplicabilityRepository)
				.build(fixture.chemistry);

		// The current curriculum hierarchy remains complete even though this Question
		// has been suppressed from every revision-output placement.
		assertTrue(findNode(corpus, fixture.directDescriptor).getQuestionPlacements().isEmpty());
		assertTrue(findNode(corpus, fixture.subtopicDescriptor).getQuestionPlacements().isEmpty());
		RevisionCorpusStatistics statistics = corpus.getStatistics();
		assertEquals(0, statistics.getApplicablePlacements());
		assertEquals(0, statistics.getUniqueApplicableQuestions());
		assertEquals(0, statistics.getRenderableQuestions());
		assertEquals(0, statistics.getQuestionsWithAnswers());
	}

	@Test
	void rebuildingSameCorpusProducesSameRevisionNumbers() {
		Fixture fixture = new Fixture();
		QuestionRetrievalRepository retrievalRepository = _ -> List.of(
				new QuestionApplicabilityMatch(fixture.renderableQuestion, fixture.directDescriptor),
				new QuestionApplicabilityMatch(fixture.laterQuestion, fixture.directDescriptor));
		RevisionCorpusBuilder builder = fixture.createBuilder(retrievalRepository);
		RevisionCorpus first = builder.build(fixture.chemistry);
		RevisionCorpus second = builder.build(fixture.chemistry);
		List<RevisionQuestionPlacement> firstPlacements = findNode(first, fixture.directDescriptor)
				.getQuestionPlacements();
		List<RevisionQuestionPlacement> secondPlacements = findNode(second, fixture.directDescriptor)
				.getQuestionPlacements();
		assertEquals(1, firstPlacements.get(0).getRevisionNumber());
		assertEquals(2, firstPlacements.get(1).getRevisionNumber());
		assertEquals(1, secondPlacements.get(0).getRevisionNumber());
		assertEquals(2, secondPlacements.get(1).getRevisionNumber());
	}

	@Test
	void rejectsMultipleCurrentSyllabuses() {
		Subject chemistry = new Subject(1, "Chemistry");
		SyllabusVersion firstCurrent = new SyllabusVersion(1, chemistry, "2025", true);
		SyllabusVersion secondCurrent = new SyllabusVersion(2, chemistry, "2026", true);
		InMemoryCurriculumRepository curriculumRepository = new InMemoryCurriculumRepository(List.of(chemistry),
				List.of(firstCurrent, secondCurrent), List.of());
		CurriculumSearchNodeExpansionService expansionService = new CurriculumSearchNodeExpansionService(
				curriculumRepository);
		QuestionRetrievalService retrievalService = new QuestionRetrievalService(_ -> List.of(), expansionService);
		RevisionCorpusBuilder builder = new RevisionCorpusBuilder(curriculumRepository, retrievalService,
				new InMemoryQuestionOutputApplicabilityRepository());

		// Output exclusions cannot make an ambiguous current-syllabus definition
		// acceptable; corpus construction must reject it first.
		assertThrows(IllegalStateException.class, () -> builder.build(chemistry));
	}

	@Test
	void rejectsSubjectWithoutCurrentSyllabus() {
		Subject chemistry = new Subject(1, "Chemistry");
		SyllabusVersion historicalVersion = new SyllabusVersion(1, chemistry, "2019", false);
		InMemoryCurriculumRepository curriculumRepository = new InMemoryCurriculumRepository(List.of(chemistry),
				List.of(historicalVersion), List.of());
		CurriculumSearchNodeExpansionService expansionService = new CurriculumSearchNodeExpansionService(
				curriculumRepository);
		QuestionRetrievalService retrievalService = new QuestionRetrievalService(_ -> List.of(), expansionService);
		RevisionCorpusBuilder builder = new RevisionCorpusBuilder(curriculumRepository, retrievalService,
				new InMemoryQuestionOutputApplicabilityRepository());

		// Question-specific exclusions do not substitute for a real current syllabus.
		assertThrows(IllegalStateException.class, () -> builder.build(chemistry));
	}

	@Test
	void revisionNumbersFollowDeterministicCorpusTraversal() {
		Fixture fixture = new Fixture();
		QuestionRetrievalRepository retrievalRepository = _ -> List.of(
				new QuestionApplicabilityMatch(fixture.laterQuestion, fixture.directDescriptor),
				new QuestionApplicabilityMatch(fixture.renderableQuestion, fixture.directDescriptor),
				new QuestionApplicabilityMatch(fixture.subtopicQuestion, fixture.currentSubtopic),
				new QuestionApplicabilityMatch(fixture.renderableQuestion, fixture.subtopicDescriptor));
		RevisionCorpus corpus = fixture.createBuilder(retrievalRepository).build(fixture.chemistry);
		List<RevisionQuestionPlacement> directPlacements = findNode(corpus, fixture.directDescriptor)
				.getQuestionPlacements();
		assertEquals(1, directPlacements.get(0).getRevisionNumber());
		assertSame(fixture.renderableQuestion, directPlacements.get(0).getQuestion());
		assertEquals(2, directPlacements.get(1).getRevisionNumber());
		assertSame(fixture.laterQuestion, directPlacements.get(1).getQuestion());
		RevisionQuestionPlacement subtopicPlacement = findNode(corpus, fixture.currentSubtopic).getQuestionPlacements()
				.get(0);
		assertFalse(subtopicPlacement.hasRevisionNumber());
		assertThrows(IllegalStateException.class, subtopicPlacement::getRevisionNumber);
		RevisionQuestionPlacement descriptorPlacement = findNode(corpus, fixture.subtopicDescriptor)
				.getQuestionPlacements().get(0);
		assertEquals(3, descriptorPlacement.getRevisionNumber());
		assertSame(fixture.renderableQuestion, descriptorPlacement.getQuestion());
	}

	@Test
	void statisticsDistinguishUniqueQuestionsFromPlacements() {
		Fixture fixture = new Fixture();
		QuestionRetrievalRepository retrievalRepository = _ -> List.of(
				new QuestionApplicabilityMatch(fixture.renderableQuestion, fixture.directDescriptor),
				new QuestionApplicabilityMatch(fixture.renderableQuestion, fixture.subtopicDescriptor),
				new QuestionApplicabilityMatch(fixture.subtopicQuestion, fixture.currentSubtopic),
				new QuestionApplicabilityMatch(fixture.laterQuestion, fixture.directDescriptor));
		RevisionCorpus corpus = fixture.createBuilder(retrievalRepository).build(fixture.chemistry);
		RevisionCorpusStatistics statistics = corpus.getStatistics();
		assertEquals(4, statistics.getApplicablePlacements());
		assertEquals(3, statistics.getUniqueApplicableQuestions());
		assertEquals(2, statistics.getRenderableQuestions());
		assertEquals(1, statistics.getMissingQuestionRegionQuestions());
		assertEquals(1, statistics.getQuestionsWithAnswers());
		assertEquals(2, statistics.getQuestionsWithoutAnswers());
		assertEquals(1, statistics.getSharedContextReviewQuestions());
	}

	private static final class Fixture {

		private final Subject chemistry;
		private final SyllabusVersion currentVersion;
		private final Unit firstUnit;
		private final Unit secondUnit;
		private final Topic descriptorModeTopic;
		private final Topic subtopicModeTopic;
		private final Topic secondUnitTopic;
		private final Subtopic currentSubtopic;
		private final Descriptor directDescriptor;
		private final Descriptor subtopicDescriptor;
		private final Descriptor secondUnitDescriptor;
		private final Question subtopicQuestion;
		private final Question renderableQuestion;
		private final Question laterQuestion;
		private final InMemoryCurriculumRepository curriculumRepository;

		private Fixture() {
			chemistry = new Subject(1, "Chemistry");
			SyllabusVersion historicalVersion = new SyllabusVersion(1, chemistry, "2019", false);
			Unit historicalUnit = new Unit(100, historicalVersion, "1", "Historical unit", 1);
			Topic historicalTopic = new Topic(101, historicalVersion, historicalUnit, "1.1", "Historical topic", 1);
			Subtopic historicalSubtopic = new Subtopic(102, historicalVersion, historicalTopic, "1.1.1",
					"Historical subtopic", 1);
			Descriptor historicalDescriptor = new Descriptor(103, historicalVersion, historicalSubtopic, "1.1.1.1",
					"Historical descriptor", 1);
			currentVersion = new SyllabusVersion(2, chemistry, "2025", true);
			firstUnit = new Unit(10, currentVersion, "1", "First unit", 1);
			secondUnit = new Unit(20, currentVersion, "2", "Second unit", 2);
			subtopicModeTopic = new Topic(11, currentVersion, firstUnit, "1.2", "Subtopic mode topic", 2);
			descriptorModeTopic = new Topic(12, currentVersion, firstUnit, "1.1", "Descriptor mode topic", 1);
			currentSubtopic = new Subtopic(13, currentVersion, subtopicModeTopic, "1.2.1", "Current subtopic", 1);
			subtopicDescriptor = new Descriptor(14, currentVersion, currentSubtopic, "1.2.1.1", "Subtopic descriptor",
					1);
			directDescriptor = new Descriptor(15, currentVersion, descriptorModeTopic, "1.1.1", "Direct descriptor", 1);
			secondUnitTopic = new Topic(21, currentVersion, secondUnit, "2.1", "Second unit topic", 1);
			secondUnitDescriptor = new Descriptor(22, currentVersion, secondUnitTopic, "2.1.1",
					"Second unit descriptor", 1);
			ExamProvider provider = new ExamProvider(1, "QCAA");
			Exam exam = new Exam(1, chemistry, provider, 2022, "Chemistry examination");
			SourceDocument sourceDocument = new SourceDocument(1, "chemistry.pdf");
			ExamBooklet booklet = new ExamBooklet(1, exam, "Paper 1", sourceDocument);
			subtopicQuestion = new Question(1, booklet, "1", "", 2, List.of(), historicalSubtopic, true);
			QuestionRegion questionRegion = new QuestionRegion(booklet, 1, 0.10, 0.10, 0.50, 0.20);
			renderableQuestion = new Question(2, booklet, "2", "", 3, List.of(questionRegion), historicalDescriptor,
					false);
			renderableQuestion.setAnswer(new Answer(1, "B", List.of()));
			laterQuestion = new Question(3, booklet, "3", "", 4, List.of(questionRegion), historicalDescriptor, false);
			curriculumRepository = new InMemoryCurriculumRepository(List.of(chemistry),
					List.of(historicalVersion, currentVersion),
					List.of(historicalUnit, historicalTopic, historicalSubtopic, historicalDescriptor, secondUnit,
							secondUnitTopic, secondUnitDescriptor, firstUnit, subtopicModeTopic, descriptorModeTopic,
							currentSubtopic, subtopicDescriptor, directDescriptor));
		}

		private RevisionCorpusBuilder createBuilder(QuestionRetrievalRepository retrievalRepository) {

			// Existing corpus tests exercise ordinary derived applicability with no
			// Question-specific output exceptions.
			return createBuilder(retrievalRepository, new InMemoryQuestionOutputApplicabilityRepository());
		}

		private RevisionCorpusBuilder createBuilder(QuestionRetrievalRepository retrievalRepository,
				InMemoryQuestionOutputApplicabilityRepository outputApplicabilityRepository) {
			CurriculumSearchNodeExpansionService expansionService = new CurriculumSearchNodeExpansionService(
					curriculumRepository);
			QuestionRetrievalService retrievalService = new QuestionRetrievalService(retrievalRepository,
					expansionService);
			return new RevisionCorpusBuilder(curriculumRepository, retrievalService, outputApplicabilityRepository);
		}
	}
}
