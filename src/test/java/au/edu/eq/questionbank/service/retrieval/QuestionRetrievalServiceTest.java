package au.edu.eq.questionbank.service.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

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

class QuestionRetrievalServiceTest {

	@Test
	void acceptsCurrentDescriptorSearch() {
		Fixture fixture = new Fixture();

		QuestionRetrievalRepository repository = currentNodes -> {
			assertEquals(List.of(fixture.currentDescriptor), currentNodes);

			return List.of(new QuestionApplicabilityMatch(fixture.firstQuestion, fixture.currentDescriptor));
		};

		QuestionRetrievalService service = new QuestionRetrievalService(repository);

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
			assertEquals(List.of(fixture.currentSubtopic), currentNodes);

			return List.of(new QuestionApplicabilityMatch(currentSubtopicQuestion, fixture.currentSubtopic));
		};

		QuestionRetrievalService service = new QuestionRetrievalService(repository);

		List<QuestionRetrievalResult> results = service.findQuestionsApplicableTo(fixture.currentSubtopic);

		assertEquals(1, results.size());
		assertSame(currentSubtopicQuestion, results.get(0).getQuestion());
		assertEquals(List.of(fixture.currentSubtopic), results.get(0).getCurrentApplicability());
	}

	@Test
	void deterministicOrderingUsesQuestionIdentifier() {
		Fixture fixture = new Fixture();

		QuestionRetrievalRepository repository = currentNodes -> List.of(
				new QuestionApplicabilityMatch(fixture.secondQuestion, fixture.currentDescriptor),
				new QuestionApplicabilityMatch(fixture.firstQuestion, fixture.currentDescriptor));

		QuestionRetrievalService service = new QuestionRetrievalService(repository);

		List<QuestionRetrievalResult> results = service.findQuestionsApplicableTo(fixture.currentDescriptor);

		assertEquals(2, results.size());
		assertSame(fixture.firstQuestion, results.get(0).getQuestion());
		assertSame(fixture.secondQuestion, results.get(1).getQuestion());
	}

	@Test
	void duplicateMatchesProduceOneQuestionResult() {
		Fixture fixture = new Fixture();

		QuestionRetrievalRepository repository = currentNodes -> List.of(
				new QuestionApplicabilityMatch(fixture.firstQuestion, fixture.currentDescriptor),
				new QuestionApplicabilityMatch(fixture.firstQuestion, fixture.currentDescriptor),
				new QuestionApplicabilityMatch(fixture.firstQuestion, fixture.currentDescriptor));

		QuestionRetrievalService service = new QuestionRetrievalService(repository);

		List<QuestionRetrievalResult> results = service.findQuestionsApplicableTo(fixture.currentDescriptor);

		assertEquals(1, results.size());
		assertSame(fixture.firstQuestion, results.get(0).getQuestion());
		assertEquals(List.of(fixture.currentDescriptor), results.get(0).getCurrentApplicability());
	}

	@Test
	void emptyRepositoryResultProducesEmptySearchResult() {
		Fixture fixture = new Fixture();

		QuestionRetrievalRepository repository = currentNodes -> List.of();

		QuestionRetrievalService service = new QuestionRetrievalService(repository);

		assertEquals(List.of(), service.findQuestionsApplicableTo(fixture.currentDescriptor));
	}

	@Test
	void historicalClassificationRemainsAuthoritativeProvenance() {
		Fixture fixture = new Fixture();

		QuestionRetrievalRepository repository = currentNodes -> List
				.of(new QuestionApplicabilityMatch(fixture.firstQuestion, fixture.currentDescriptor));

		QuestionRetrievalService service = new QuestionRetrievalService(repository);

		List<QuestionRetrievalResult> results = service.findQuestionsApplicableTo(fixture.currentDescriptor);

		assertEquals(1, results.size());
		assertSame(fixture.historicalDescriptor, results.get(0).getOriginalClassification());
		assertSame(fixture.historicalDescriptor, fixture.firstQuestion.getClassification());
		assertEquals(List.of(fixture.currentDescriptor), results.get(0).getCurrentApplicability());
	}

	@Test
	void rejectsHistoricalSearchNode() {
		Fixture fixture = new Fixture();

		QuestionRetrievalRepository repository = currentNodes -> List.of();
		QuestionRetrievalService service = new QuestionRetrievalService(repository);

		assertThrows(IllegalArgumentException.class,
				() -> service.findQuestionsApplicableTo(fixture.historicalDescriptor));
	}

	@Test
	void rejectsNullRepositoryAndSearchNode() {
		Fixture fixture = new Fixture();

		assertThrows(NullPointerException.class, () -> new QuestionRetrievalService(null));

		QuestionRetrievalRepository repository = currentNodes -> List.of();
		QuestionRetrievalService service = new QuestionRetrievalService(repository);

		assertThrows(NullPointerException.class, () -> service.findQuestionsApplicableTo(null));
	}

	@Test
	void rejectsRepositoryMatchForUnrequestedNode() {
		Fixture fixture = new Fixture();

		QuestionRetrievalRepository repository = currentNodes -> List
				.of(new QuestionApplicabilityMatch(fixture.firstQuestion, fixture.otherCurrentDescriptor));

		QuestionRetrievalService service = new QuestionRetrievalService(repository);

		assertThrows(IllegalStateException.class, () -> service.findQuestionsApplicableTo(fixture.currentDescriptor));
	}

	@Test
	void rejectsRepositoryReturningNullList() {
		Fixture fixture = new Fixture();

		QuestionRetrievalRepository repository = currentNodes -> null;
		QuestionRetrievalService service = new QuestionRetrievalService(repository);

		assertThrows(IllegalStateException.class, () -> service.findQuestionsApplicableTo(fixture.currentDescriptor));
	}

	@Test
	void rejectsUnsupportedCurrentHierarchyLevels() {
		Fixture fixture = new Fixture();

		QuestionRetrievalRepository repository = currentNodes -> List.of();
		QuestionRetrievalService service = new QuestionRetrievalService(repository);

		assertThrows(IllegalArgumentException.class, () -> service.findQuestionsApplicableTo(fixture.currentUnit));

		assertThrows(IllegalArgumentException.class, () -> service.findQuestionsApplicableTo(fixture.currentTopic));
	}

	private static final class Fixture {

		private final ExamBooklet booklet;
		private final Descriptor currentDescriptor;
		private final Subtopic currentSubtopic;
		private final Topic currentTopic;
		private final Unit currentUnit;
		private final Descriptor historicalDescriptor;
		private final Descriptor otherCurrentDescriptor;
		private final Question firstQuestion;
		private final Question secondQuestion;

		private Fixture() {
			Subject chemistry = new Subject(1, "Chemistry");

			SyllabusVersion historicalVersion = new SyllabusVersion(1, chemistry, "2019", false);
			Unit historicalUnit = new Unit(1, historicalVersion, "1", "Historical unit", 1);
			Topic historicalTopic = new Topic(2, historicalVersion, historicalUnit, "1.1", "Historical topic", 1);
			historicalDescriptor = new Descriptor(3, historicalVersion, historicalTopic, "1.1.1",
					"Historical descriptor", 1);

			SyllabusVersion currentVersion = new SyllabusVersion(2, chemistry, "2025", true);
			currentUnit = new Unit(10, currentVersion, "1", "Current unit", 1);
			currentTopic = new Topic(11, currentVersion, currentUnit, "1.1", "Current topic", 1);
			currentSubtopic = new Subtopic(12, currentVersion, currentTopic, "1.1.1", "Current subtopic", 1);
			currentDescriptor = new Descriptor(13, currentVersion, currentSubtopic, "1.1.1.1", "Current descriptor", 1);
			otherCurrentDescriptor = new Descriptor(14, currentVersion, currentSubtopic, "1.1.1.2",
					"Other current descriptor", 2);

			ExamProvider provider = new ExamProvider(1, "QCAA");
			Exam exam = new Exam(1, chemistry, provider, 2020, "2020 Chemistry");
			SourceDocument sourceDocument = new SourceDocument(1, "paper1.pdf");
			booklet = new ExamBooklet(1, exam, "Paper 1", sourceDocument);

			firstQuestion = new Question(1, booklet, "3", "", 2, List.of(), historicalDescriptor, false);

			secondQuestion = new Question(2, booklet, "4", "", 3, List.of(), historicalDescriptor, false);
		}
	}
}
