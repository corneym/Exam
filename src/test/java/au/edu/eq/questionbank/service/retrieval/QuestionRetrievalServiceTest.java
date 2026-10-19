package au.edu.eq.questionbank.service.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.repository.assessment.QuestionApplicabilityMatch;
import au.edu.eq.questionbank.repository.assessment.QuestionRetrievalRepository;
import au.edu.eq.questionbank.repository.curriculum.InMemoryCurriculumRepository;

class QuestionRetrievalServiceTest {

	@Test
	void acceptsCurrentDescriptorSearch() {
		Fixture fixture = new Fixture();
		QuestionRetrievalRepository repository = currentNodes -> {
			assertEquals(List.of(fixture.currentDescriptor), currentNodes);
			return List.of(new QuestionApplicabilityMatch(fixture.firstQuestion, fixture.currentDescriptor));
		};
		QuestionRetrievalService service = fixture.createService(repository);
		List<QuestionRetrievalResult> results = service.findQuestionsApplicableTo(fixture.currentDescriptor);
		assertEquals(1, results.size());
		assertSame(fixture.firstQuestion, results.get(0).getQuestion());
		assertEquals(List.of(fixture.currentDescriptor), results.get(0).getCurrentApplicability());
	}

	@Test
	void acceptsCurrentSubtopicSearch() {
		Fixture fixture = new Fixture();
		Question currentSubtopicQuestion = new Question(3, fixture.booklet, "5", "", 2, List.of(),
				fixture.currentSubtopic, false);
		QuestionRetrievalRepository repository = currentNodes -> {
			assertEquals(List.of(fixture.currentSubtopic, fixture.currentDescriptor, fixture.otherCurrentDescriptor),
					currentNodes);
			return List.of(new QuestionApplicabilityMatch(currentSubtopicQuestion, fixture.currentSubtopic));
		};
		QuestionRetrievalService service = fixture.createService(repository);
		List<QuestionRetrievalResult> results = service.findQuestionsApplicableTo(fixture.currentSubtopic);
		assertEquals(1, results.size());
		assertSame(currentSubtopicQuestion, results.get(0).getQuestion());
		assertEquals(List.of(fixture.currentSubtopic), results.get(0).getCurrentApplicability());
	}

	@Test
	void acceptsCurrentTopicSearch() {
		Fixture fixture = new Fixture();
		QuestionRetrievalRepository repository = currentNodes -> {
			assertEquals(List.of(fixture.currentSubtopic, fixture.currentDescriptor, fixture.otherCurrentDescriptor),
					currentNodes);
			return List.of(new QuestionApplicabilityMatch(fixture.firstQuestion, fixture.currentDescriptor));
		};
		QuestionRetrievalService service = fixture.createService(repository);
		List<QuestionRetrievalResult> results = service.findQuestionsApplicableTo(fixture.currentTopic);
		assertEquals(1, results.size());
		assertSame(fixture.firstQuestion, results.get(0).getQuestion());
		assertEquals(List.of(fixture.currentDescriptor), results.get(0).getCurrentApplicability());
	}

	@Test
	void acceptsCurrentUnitSearch() {
		Fixture fixture = new Fixture();
		QuestionRetrievalRepository repository = currentNodes -> {
			assertEquals(List.of(fixture.currentSubtopic, fixture.currentDescriptor, fixture.otherCurrentDescriptor),
					currentNodes);
			return List.of(new QuestionApplicabilityMatch(fixture.firstQuestion, fixture.otherCurrentDescriptor),
					new QuestionApplicabilityMatch(fixture.firstQuestion, fixture.currentDescriptor));
		};
		QuestionRetrievalService service = fixture.createService(repository);
		List<QuestionRetrievalResult> results = service.findQuestionsApplicableTo(fixture.currentUnit);
		assertEquals(1, results.size());
		assertSame(fixture.firstQuestion, results.get(0).getQuestion());
		assertEquals(List.of(fixture.currentDescriptor, fixture.otherCurrentDescriptor),
				results.get(0).getCurrentApplicability());
	}

	@Test
	void deterministicOrderingUsesQuestionIdentifier() {
		Fixture fixture = new Fixture();
		QuestionRetrievalRepository repository = _ -> List.of(
				new QuestionApplicabilityMatch(fixture.secondQuestion, fixture.currentDescriptor),
				new QuestionApplicabilityMatch(fixture.firstQuestion, fixture.currentDescriptor));
		QuestionRetrievalService service = fixture.createService(repository);
		List<QuestionRetrievalResult> results = service.findQuestionsApplicableTo(fixture.currentDescriptor);
		assertEquals(2, results.size());
		assertSame(fixture.firstQuestion, results.get(0).getQuestion());
		assertSame(fixture.secondQuestion, results.get(1).getQuestion());
	}

	@Test
	void duplicateMatchesProduceOneQuestionResult() {
		Fixture fixture = new Fixture();
		QuestionRetrievalRepository repository = _ -> List.of(
				new QuestionApplicabilityMatch(fixture.firstQuestion, fixture.currentDescriptor),
				new QuestionApplicabilityMatch(fixture.firstQuestion, fixture.currentDescriptor),
				new QuestionApplicabilityMatch(fixture.firstQuestion, fixture.currentDescriptor));
		QuestionRetrievalService service = fixture.createService(repository);
		List<QuestionRetrievalResult> results = service.findQuestionsApplicableTo(fixture.currentDescriptor);
		assertEquals(1, results.size());
		assertSame(fixture.firstQuestion, results.get(0).getQuestion());
		assertEquals(List.of(fixture.currentDescriptor), results.get(0).getCurrentApplicability());
	}

	@Test
	void emptyRepositoryResultProducesEmptySearchResult() {
		Fixture fixture = new Fixture();
		QuestionRetrievalRepository repository = _ -> List.of();
		QuestionRetrievalService service = fixture.createService(repository);
		assertEquals(List.of(), service.findQuestionsApplicableTo(fixture.currentDescriptor));
	}

	@Test
	void emptyTopicProducesEmptyResultWithoutRepositoryCall() {
		Fixture fixture = new Fixture();
		QuestionRetrievalRepository repository = _ -> {
			throw new AssertionError("Question repository should not be called");
		};
		QuestionRetrievalService service = fixture.createService(repository);
		assertEquals(List.of(), service.findQuestionsApplicableTo(fixture.emptyTopic));
	}

	@Test
	void historicalClassificationRemainsAuthoritativeProvenance() {
		Fixture fixture = new Fixture();
		QuestionRetrievalRepository repository = _ -> List
				.of(new QuestionApplicabilityMatch(fixture.firstQuestion, fixture.currentDescriptor));
		QuestionRetrievalService service = fixture.createService(repository);
		List<QuestionRetrievalResult> results = service.findQuestionsApplicableTo(fixture.currentDescriptor);
		assertEquals(1, results.size());
		assertSame(fixture.historicalDescriptor, results.get(0).getOriginalClassification());
		assertSame(fixture.historicalDescriptor, fixture.firstQuestion.getClassification());
		assertEquals(List.of(fixture.currentDescriptor), results.get(0).getCurrentApplicability());
	}

	@Test
	void rejectsHistoricalSearchNode() {
		Fixture fixture = new Fixture();
		QuestionRetrievalRepository repository = _ -> List.of();
		QuestionRetrievalService service = fixture.createService(repository);
		assertThrows(IllegalArgumentException.class,
				() -> service.findQuestionsApplicableTo(fixture.historicalDescriptor));
	}

	@Test
	void rejectsNullDependenciesAndSearchNode() {
		Fixture fixture = new Fixture();
		QuestionRetrievalRepository repository = _ -> List.of();
		CurriculumSearchNodeExpansionService expansionService = fixture.createExpansionService();
		assertThrows(NullPointerException.class, () -> new QuestionRetrievalService(null, expansionService));
		assertThrows(NullPointerException.class, () -> new QuestionRetrievalService(repository, null));
		QuestionRetrievalService service = fixture.createService(repository);
		assertThrows(NullPointerException.class, () -> service.findQuestionsApplicableTo((CurriculumNode) null));
		assertThrows(NullPointerException.class, () -> service.findQuestionsApplicableTo((Subject) null));
	}

	@Test
	void rejectsNullMatchReturnedByRepository() {
		Fixture fixture = new Fixture();
		List<QuestionApplicabilityMatch> matches = new ArrayList<>();
		matches.add(null);
		QuestionRetrievalRepository repository = _ -> matches;
		QuestionRetrievalService service = fixture.createService(repository);
		IllegalStateException failure = assertThrows(IllegalStateException.class,
				() -> service.findQuestionsApplicableTo(fixture.currentDescriptor));
		assertEquals("Question retrieval repository returned a null match", failure.getMessage());
	}

	@Test
	void rejectsNullRepositoryResultForSelectedQuestion() {
		Fixture fixture = new Fixture();
		QuestionRetrievalRepository repository = new QuestionRetrievalRepository() {

			@Override
			public List<QuestionApplicabilityMatch> findApplicableToNodes(List<CurriculumNode> currentNodes) {
				throw new AssertionError("Selected retrieval must not call broad retrieval");
			}

			@Override
			public List<QuestionApplicabilityMatch> findApplicableToNodesForQuestion(List<CurriculumNode> currentNodes,
					long questionId, long parentOperationId) {
				assertEquals(fixture.firstQuestion.getId(), questionId);
				return null;
			}
		};
		QuestionRetrievalService service = fixture.createService(repository);
		IllegalStateException failure = assertThrows(IllegalStateException.class,
				() -> service.findCurrentApplicabilityForQuestion(fixture.firstQuestion, 0));
		assertEquals("Question retrieval repository returned null", failure.getMessage());
	}

	@Test
	void rejectsRepositoryMatchForUnrequestedNode() {
		Fixture fixture = new Fixture();
		QuestionRetrievalRepository repository = _ -> List
				.of(new QuestionApplicabilityMatch(fixture.firstQuestion, fixture.otherCurrentDescriptor));
		QuestionRetrievalService service = fixture.createService(repository);
		assertThrows(IllegalStateException.class, () -> service.findQuestionsApplicableTo(fixture.currentDescriptor));
	}

	@Test
	void rejectsRepositoryReturningNullList() {
		Fixture fixture = new Fixture();
		QuestionRetrievalRepository repository = _ -> null;
		QuestionRetrievalService service = fixture.createService(repository);
		assertThrows(IllegalStateException.class, () -> service.findQuestionsApplicableTo(fixture.currentDescriptor));
	}

	@Test
	void rejectsUnrequestedNodeForSelectedQuestion() {
		Fixture fixture = new Fixture();

		// This is a valid current Descriptor, but it is deliberately
		// absent from the curriculum repository's search expansion.
		Descriptor unrequestedDescriptor = new Descriptor(99, fixture.currentVersion, fixture.currentSubtopic,
				"1.1.1.99", "Unrequested descriptor", 99);
		QuestionRetrievalRepository repository = new QuestionRetrievalRepository() {

			@Override
			public List<QuestionApplicabilityMatch> findApplicableToNodes(List<CurriculumNode> currentNodes) {
				throw new AssertionError("Selected retrieval must not call broad retrieval");
			}

			@Override
			public List<QuestionApplicabilityMatch> findApplicableToNodesForQuestion(List<CurriculumNode> currentNodes,
					long questionId, long parentOperationId) {
				assertEquals(fixture.firstQuestion.getId(), questionId);

				// The repository incorrectly returns a placement
				// outside the requested current-node set.
				return List.of(new QuestionApplicabilityMatch(fixture.firstQuestion, unrequestedDescriptor));
			}
		};
		QuestionRetrievalService service = fixture.createService(repository);
		IllegalStateException failure = assertThrows(IllegalStateException.class,
				() -> service.findCurrentApplicabilityForQuestion(fixture.firstQuestion, 0));
		assertEquals("Question retrieval repository returned an unrequested current node", failure.getMessage());

		// Even malformed repository output must not change provenance.
		assertSame(fixture.historicalDescriptor, fixture.firstQuestion.getClassification());
	}

	@Test
	void selectedQuestionApplicabilityRejectsAnotherQuestion() {
		Fixture fixture = new Fixture();
		QuestionRetrievalRepository repository = new QuestionRetrievalRepository() {

			@Override
			public List<QuestionApplicabilityMatch> findApplicableToNodes(List<CurriculumNode> currentNodes) {
				throw new AssertionError("Selected-Question retrieval must not call broad retrieval");
			}

			@Override
			public List<QuestionApplicabilityMatch> findApplicableToNodesForQuestion(List<CurriculumNode> currentNodes,
					long questionId, long parentOperationId) {
				return List.of(new QuestionApplicabilityMatch(fixture.secondQuestion, fixture.currentDescriptor));
			}
		};
		QuestionRetrievalService service = fixture.createService(repository);
		assertThrows(IllegalStateException.class,
				() -> service.findCurrentApplicabilityForQuestion(fixture.firstQuestion, 0));
	}

	@Test
	void selectedQuestionApplicabilityReturnsEmptyForNoCurrentPlacements() {
		Fixture fixture = new Fixture();
		QuestionRetrievalRepository repository = new QuestionRetrievalRepository() {

			@Override
			public List<QuestionApplicabilityMatch> findApplicableToNodes(List<CurriculumNode> currentNodes) {
				throw new AssertionError("Selected-Question retrieval must not call broad retrieval");
			}

			@Override
			public List<QuestionApplicabilityMatch> findApplicableToNodesForQuestion(List<CurriculumNode> currentNodes,
					long questionId, long parentOperationId) {
				assertEquals(fixture.firstQuestion.getId(), questionId);
				return List.of();
			}
		};
		QuestionRetrievalService service = fixture.createService(repository);
		assertEquals(List.of(), service.findCurrentApplicabilityForQuestion(fixture.firstQuestion, 0));
		assertThrows(NullPointerException.class, () -> service.findCurrentApplicabilityForQuestion(null, 0));
	}

	@Test
	void selectedQuestionApplicabilityUsesSelectiveRepositoryAndDeduplicatesPlacements() {
		Fixture fixture = new Fixture();
		QuestionRetrievalRepository repository = new QuestionRetrievalRepository() {

			@Override
			public List<QuestionApplicabilityMatch> findApplicableToNodes(List<CurriculumNode> currentNodes) {
				throw new AssertionError("Selected-Question retrieval must not call broad retrieval");
			}

			@Override
			public List<QuestionApplicabilityMatch> findApplicableToNodesForQuestion(List<CurriculumNode> currentNodes,
					long questionId, long parentOperationId) {

				// The requested nodes must span the entire current Subject,
				// not only the first Descriptor that matched Search.
				assertEquals(
						List.of(fixture.currentSubtopic, fixture.currentDescriptor, fixture.otherCurrentDescriptor),
						currentNodes);
				assertEquals(fixture.firstQuestion.getId(), questionId);

				// Return placements out of order and repeat one mapping
				// to verify the existing aggregation contract.
				return List.of(new QuestionApplicabilityMatch(fixture.firstQuestion, fixture.otherCurrentDescriptor),
						new QuestionApplicabilityMatch(fixture.firstQuestion, fixture.currentDescriptor),
						new QuestionApplicabilityMatch(fixture.firstQuestion, fixture.currentDescriptor));
			}
		};
		QuestionRetrievalService service = fixture.createService(repository);
		List<CurriculumNode> placements = service.findCurrentApplicabilityForQuestion(fixture.firstQuestion, 0);
		assertEquals(List.of(fixture.currentDescriptor, fixture.otherCurrentDescriptor), placements);

		// Applicability does not rewrite the historical provenance.
		assertSame(fixture.historicalDescriptor, fixture.firstQuestion.getClassification());
	}

	private static final class Fixture {

		// Curriculum and assessment fixtures.
		private final ExamBooklet booklet;
		private final Subject chemistry;
		private final Descriptor currentDescriptor;
		private final Subtopic currentSubtopic;
		private final Topic currentTopic;
		private final Unit currentUnit;
		private final SyllabusVersion currentVersion;
		private final Topic emptyTopic;
		private final Question firstQuestion;
		private final Descriptor historicalDescriptor;
		private final Descriptor otherCurrentDescriptor;
		private final Question secondQuestion;

		private Fixture() {
			chemistry = new Subject(1, "Chemistry");
			SyllabusVersion historicalVersion = new SyllabusVersion(1, chemistry, "2019", false);
			Unit historicalUnit = new Unit(1, historicalVersion, "1", "Historical unit", 1);
			Topic historicalTopic = new Topic(2, historicalVersion, historicalUnit, "1.1", "Historical topic", 1);
			historicalDescriptor = new Descriptor(3, historicalVersion, historicalTopic, "1.1.1",
					"Historical descriptor", 1);
			currentVersion = new SyllabusVersion(2, chemistry, "2025", true);
			currentUnit = new Unit(10, currentVersion, "1", "Current unit", 1);
			currentTopic = new Topic(11, currentVersion, currentUnit, "1.1", "Current topic", 1);
			currentSubtopic = new Subtopic(12, currentVersion, currentTopic, "1.1.1", "Current subtopic", 1);
			currentDescriptor = new Descriptor(13, currentVersion, currentSubtopic, "1.1.1.1", "Current descriptor", 1);
			otherCurrentDescriptor = new Descriptor(14, currentVersion, currentSubtopic, "1.1.1.2",
					"Other current descriptor", 2);
			emptyTopic = new Topic(15, currentVersion, currentUnit, "1.2", "Empty topic", 2);
			ExamProvider provider = new ExamProvider(1, "QCAA");
			Exam exam = new Exam(1, chemistry, provider, 2020, "2020 Chemistry");
			SourceDocument sourceDocument = new SourceDocument(1, "paper1.pdf");
			booklet = new ExamBooklet(1, exam, "Paper 1", sourceDocument);
			firstQuestion = new Question(1, booklet, "3", "", 2, List.of(), historicalDescriptor, false);
			secondQuestion = new Question(2, booklet, "4", "", 3, List.of(), historicalDescriptor, false);
		}

		private CurriculumSearchNodeExpansionService createExpansionService() {
			InMemoryCurriculumRepository repository = new InMemoryCurriculumRepository(List.of(chemistry),
					List.of(currentVersion), List.of(currentUnit, currentTopic, emptyTopic, currentSubtopic,
							currentDescriptor, otherCurrentDescriptor));
			return new CurriculumSearchNodeExpansionService(repository);
		}

		private QuestionRetrievalService createService(QuestionRetrievalRepository repository) {
			return new QuestionRetrievalService(repository, createExpansionService());
		}
	}
}
