package au.edu.eq.questionbank.service.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.repository.curriculum.InMemoryCurriculumRepository;

class CurriculumSearchNodeExpansionServiceTest {

	@Test
	void descriptorModeTopicIncludesOnlyItsDescriptors() {
		Fixture fixture = new Fixture();
		CurriculumSearchNodeExpansionService service = fixture.createService();

		assertEquals(List.of(fixture.firstTopicDescriptor, fixture.secondTopicDescriptor),
				service.expandSearchNode(fixture.descriptorTopic));
	}

	@Test
	void descriptorSearchIsExact() {
		Fixture fixture = new Fixture();
		CurriculumSearchNodeExpansionService service = fixture.createService();

		assertEquals(List.of(fixture.firstTopicDescriptor), service.expandSearchNode(fixture.firstTopicDescriptor));
	}

	@Test
	void rejectsHistoricalSearchNode() {
		Fixture fixture = new Fixture();
		CurriculumSearchNodeExpansionService service = fixture.createService();
		assertThrows(IllegalArgumentException.class, () -> service.expandSearchNode(fixture.historicalDescriptor));
	}

	@Test
	void rejectsMixedTopicHierarchy() {
		Fixture fixture = new Fixture();
		CurriculumSearchNodeExpansionService service = fixture.createMixedTopicService();
		assertThrows(IllegalStateException.class, () -> service.expandSearchNode(fixture.mixedTopic));
	}

	@Test
	void rejectsNullArguments() {
		Fixture fixture = new Fixture();
		assertThrows(NullPointerException.class, () -> new CurriculumSearchNodeExpansionService(null));
		CurriculumSearchNodeExpansionService service = fixture.createService();
		assertThrows(NullPointerException.class, () -> service.expandSearchNode(null));
	}

	@Test
	void subtopicModeTopicIncludesSubtopicsAndTheirDescriptors() {
		Fixture fixture = new Fixture();
		CurriculumSearchNodeExpansionService service = fixture.createService();
		assertEquals(
				List.of(fixture.firstSubtopic, fixture.firstSubtopicDescriptor, fixture.secondSubtopicDescriptor,
						fixture.secondSubtopic, fixture.thirdSubtopicDescriptor),
				service.expandSearchNode(fixture.subtopicTopic));
	}

	@Test
	void subtopicSearchIncludesSubtopicAndItsDescriptors() {
		Fixture fixture = new Fixture();
		CurriculumSearchNodeExpansionService service = fixture.createService();
		assertEquals(List.of(fixture.firstSubtopic, fixture.firstSubtopicDescriptor, fixture.secondSubtopicDescriptor),
				service.expandSearchNode(fixture.firstSubtopic));
	}

	@Test
	void unitSearchCombinesBothTopicStructures() {
		Fixture fixture = new Fixture();
		CurriculumSearchNodeExpansionService service = fixture.createService();
		assertEquals(List.of(fixture.firstTopicDescriptor, fixture.secondTopicDescriptor, fixture.firstSubtopic,
				fixture.firstSubtopicDescriptor, fixture.secondSubtopicDescriptor, fixture.secondSubtopic,
				fixture.thirdSubtopicDescriptor), service.expandSearchNode(fixture.currentUnit));
	}

	private static final class Fixture {

		private final Subject chemistry;
		private final SyllabusVersion currentVersion;

		private final Unit currentUnit;

		private final Topic descriptorTopic;
		private final Descriptor firstTopicDescriptor;
		private final Descriptor secondTopicDescriptor;

		private final Topic subtopicTopic;
		private final Subtopic firstSubtopic;
		private final Descriptor firstSubtopicDescriptor;
		private final Descriptor secondSubtopicDescriptor;
		private final Subtopic secondSubtopic;
		private final Descriptor thirdSubtopicDescriptor;

		private final Topic mixedTopic;
		private final Descriptor mixedTopicDescriptor;
		private final Subtopic mixedTopicSubtopic;

		private final Descriptor historicalDescriptor;

		private Fixture() {
			chemistry = new Subject(1, "Chemistry");
			currentVersion = new SyllabusVersion(1, chemistry, "2025", true);
			currentUnit = new Unit(10, currentVersion, "1", "Current unit", 1);
			descriptorTopic = new Topic(11, currentVersion, currentUnit, "1.1", "Descriptor topic", 1);
			firstTopicDescriptor = new Descriptor(12, currentVersion, descriptorTopic, "1.1.1",
					"First topic descriptor", 1);
			secondTopicDescriptor = new Descriptor(13, currentVersion, descriptorTopic, "1.1.2",
					"Second topic descriptor", 2);
			subtopicTopic = new Topic(20, currentVersion, currentUnit, "1.2", "Subtopic topic", 2);
			firstSubtopic = new Subtopic(21, currentVersion, subtopicTopic, "1.2.1", "First subtopic", 1);
			firstSubtopicDescriptor = new Descriptor(22, currentVersion, firstSubtopic, "1.2.1.1",
					"First subtopic descriptor", 1);
			secondSubtopicDescriptor = new Descriptor(23, currentVersion, firstSubtopic, "1.2.1.2",
					"Second subtopic descriptor", 2);
			secondSubtopic = new Subtopic(24, currentVersion, subtopicTopic, "1.2.2", "Second subtopic", 2);
			thirdSubtopicDescriptor = new Descriptor(25, currentVersion, secondSubtopic, "1.2.2.1",
					"Third subtopic descriptor", 1);
			mixedTopic = new Topic(30, currentVersion, currentUnit, "1.3", "Invalid mixed topic", 3);
			mixedTopicDescriptor = new Descriptor(31, currentVersion, mixedTopic, "1.3.1", "Mixed topic descriptor", 1);
			mixedTopicSubtopic = new Subtopic(32, currentVersion, mixedTopic, "1.3.2", "Mixed topic subtopic", 2);
			SyllabusVersion historicalVersion = new SyllabusVersion(2, chemistry, "2019", false);
			Unit historicalUnit = new Unit(40, historicalVersion, "1", "Historical unit", 1);
			Topic historicalTopic = new Topic(41, historicalVersion, historicalUnit, "1.1", "Historical topic", 1);
			historicalDescriptor = new Descriptor(42, historicalVersion, historicalTopic, "1.1.1",
					"Historical descriptor", 1);
		}

		private CurriculumSearchNodeExpansionService createMixedTopicService() {
			InMemoryCurriculumRepository repository = new InMemoryCurriculumRepository(List.of(chemistry),
					List.of(currentVersion),
					List.of(currentUnit, mixedTopic, mixedTopicDescriptor, mixedTopicSubtopic));

			return new CurriculumSearchNodeExpansionService(repository);
		}

		private CurriculumSearchNodeExpansionService createService() {
			InMemoryCurriculumRepository repository = new InMemoryCurriculumRepository(List.of(chemistry),
					List.of(currentVersion),
					List.of(currentUnit, descriptorTopic, firstTopicDescriptor, secondTopicDescriptor, subtopicTopic,
							firstSubtopic, firstSubtopicDescriptor, secondSubtopicDescriptor, secondSubtopic,
							thirdSubtopicDescriptor));

			return new CurriculumSearchNodeExpansionService(repository);
		}
	}
}
