package au.edu.eq.questionbank.service.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.repository.curriculum.InMemoryCurriculumRepository;

class CurriculumSearchNodeExpansionServiceTest {

	@Test
	void descriptorSearchIsExact() {
		Fixture fixture = new Fixture();
		CurriculumSearchNodeExpansionService service = fixture.createService();

		assertEquals(List.of(fixture.firstSubtopicDescriptor),
				service.expandSearchNode(fixture.firstSubtopicDescriptor));
	}

	@Test
	void rejectsHistoricalSearchNode() {
		Fixture fixture = new Fixture();
		CurriculumSearchNodeExpansionService service = fixture.createService();

		assertThrows(IllegalArgumentException.class, () -> service.expandSearchNode(fixture.historicalDescriptor));
	}

	@Test
	void rejectsNullArguments() {
		Fixture fixture = new Fixture();

		assertThrows(NullPointerException.class, () -> new CurriculumSearchNodeExpansionService(null));

		CurriculumSearchNodeExpansionService service = fixture.createService();

		assertThrows(NullPointerException.class, () -> service.expandSearchNode(null));
	}

	@Test
	void rejectsUnitAndTopicSearches() {
		Fixture fixture = new Fixture();
		CurriculumSearchNodeExpansionService service = fixture.createService();

		assertThrows(IllegalArgumentException.class, () -> service.expandSearchNode(fixture.currentUnit));

		assertThrows(IllegalArgumentException.class, () -> service.expandSearchNode(fixture.currentTopic));
	}

	@Test
	void subtopicSearchDoesNotIncludeDescriptorBelongingDirectlyToTopic() {
		Fixture fixture = new Fixture();
		CurriculumSearchNodeExpansionService service = fixture.createService();

		List<CurriculumNode> expandedNodes = service.expandSearchNode(fixture.currentSubtopic);

		assertEquals(3, expandedNodes.size());
		assertEquals(fixture.currentSubtopic, expandedNodes.get(0));
		assertEquals(fixture.firstSubtopicDescriptor, expandedNodes.get(1));
		assertEquals(fixture.secondSubtopicDescriptor, expandedNodes.get(2));
	}

	@Test
	void subtopicSearchIncludesDescriptorChildrenInDisplayOrder() {
		Fixture fixture = new Fixture();
		CurriculumSearchNodeExpansionService service = fixture.createService();

		assertEquals(
				List.of(fixture.currentSubtopic, fixture.firstSubtopicDescriptor, fixture.secondSubtopicDescriptor),
				service.expandSearchNode(fixture.currentSubtopic));
	}

	@Test
	void subtopicWithoutDescriptorsExpandsToItself() {
		Fixture fixture = new Fixture();
		CurriculumSearchNodeExpansionService service = fixture.createService();

		assertEquals(List.of(fixture.emptySubtopic), service.expandSearchNode(fixture.emptySubtopic));
	}

	private static final class Fixture {

		private final Subject chemistry;
		private final SyllabusVersion currentVersion;
		private final Unit currentUnit;
		private final Topic currentTopic;
		private final Subtopic currentSubtopic;
		private final Subtopic emptySubtopic;
		private final Descriptor firstSubtopicDescriptor;
		private final Descriptor secondSubtopicDescriptor;
		private final Descriptor topicDescriptor;
		private final Descriptor historicalDescriptor;

		private Fixture() {
			chemistry = new Subject(1, "Chemistry");
			currentVersion = new SyllabusVersion(1, chemistry, "2025", true);
			currentUnit = new Unit(10, currentVersion, "1", "Current unit", 1);
			currentTopic = new Topic(11, currentVersion, currentUnit, "1.1", "Current topic", 1);
			currentSubtopic = new Subtopic(12, currentVersion, currentTopic, "1.1.1", "Current subtopic", 1);
			emptySubtopic = new Subtopic(13, currentVersion, currentTopic, "1.1.2", "Empty subtopic", 2);
			secondSubtopicDescriptor = new Descriptor(15, currentVersion, currentSubtopic, "1.1.1.2",
					"Second descriptor", 2);
			firstSubtopicDescriptor = new Descriptor(14, currentVersion, currentSubtopic, "1.1.1.1", "First descriptor",
					1);
			topicDescriptor = new Descriptor(16, currentVersion, currentTopic, "1.1.3", "Topic descriptor", 3);
			SyllabusVersion historicalVersion = new SyllabusVersion(2, chemistry, "2019", false);
			Unit historicalUnit = new Unit(20, historicalVersion, "1", "Historical unit", 1);
			Topic historicalTopic = new Topic(21, historicalVersion, historicalUnit, "1.1", "Historical topic", 1);
			historicalDescriptor = new Descriptor(22, historicalVersion, historicalTopic, "1.1.1",
					"Historical descriptor", 1);
		}

		private CurriculumSearchNodeExpansionService createService() {
			InMemoryCurriculumRepository repository = new InMemoryCurriculumRepository(List.of(chemistry),
					List.of(currentVersion), List.of(currentUnit, currentTopic, currentSubtopic, emptySubtopic,
							secondSubtopicDescriptor, firstSubtopicDescriptor, topicDescriptor));

			return new CurriculumSearchNodeExpansionService(repository);
		}
	}
}
