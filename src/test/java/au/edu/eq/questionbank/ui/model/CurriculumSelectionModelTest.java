package au.edu.eq.questionbank.ui.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.repository.curriculum.InMemoryCurriculumRepository;

class CurriculumSelectionModelTest {

	private Subject chemistry;
	private SyllabusVersion syllabus2019;
	private SyllabusVersion syllabus2025;

	private Unit unit3;
	private Topic topic31;
	private Subtopic subtopic311;
	private Unit historicalUnit;
	private Topic historicalTopic;
	private Descriptor historicalDescriptor;

	private CurriculumSelectionModel model;

	@Test
	void cascadesUnitTopicAndClassificationSelections() {
		model.selectSubject(chemistry);
		model.selectUnit(unit3);
		assertEquals(List.of(topic31), model.getTopics());
		model.selectTopic(topic31);
		assertEquals(List.of(subtopic311), model.getClassifications());
		model.selectClassification(subtopic311);
		assertEquals(subtopic311, model.getClassification());
	}

	@Test
	void changingSubjectClearsLowerSelections() {
		model.selectSubject(chemistry);
		model.selectUnit(unit3);
		model.selectTopic(topic31);
		model.selectClassification(subtopic311);

		model.selectSubject(null);

		assertNull(model.getSubject());
		assertNull(model.getSyllabusVersion());
		assertNull(model.getUnit());
		assertNull(model.getTopic());
		assertNull(model.getClassification());

		assertEquals(List.of(), model.getSyllabusVersions());
		assertEquals(List.of(), model.getUnits());
		assertEquals(List.of(), model.getTopics());
		assertEquals(List.of(), model.getClassifications());
	}

	@Test
	void changingTopicClearsClassificationSelection() {
		model.selectSubject(chemistry);
		model.selectUnit(unit3);
		model.selectTopic(topic31);
		model.selectClassification(subtopic311);

		model.selectTopic(null);

		assertNull(model.getTopic());
		assertNull(model.getClassification());

		assertEquals(List.of(), model.getClassifications());
	}

	@Test
	void changingUnitClearsLowerSelections() {
		model.selectSubject(chemistry);
		model.selectUnit(unit3);
		model.selectTopic(topic31);
		model.selectClassification(subtopic311);

		model.selectUnit(null);

		assertNull(model.getUnit());
		assertNull(model.getTopic());
		assertNull(model.getClassification());

		assertEquals(List.of(), model.getTopics());

		assertEquals(List.of(), model.getClassifications());
	}

	@Test
	void exposesSubjects() {
		assertEquals(List.of(chemistry), model.getSubjects());
	}

	@Test
	void exposesDescriptorsAttachedDirectlyToATopic() {
		Descriptor descriptor = new Descriptor(4, syllabus2025, topic31, "3.1.1", "Descriptor 3.1.1", 1);
		InMemoryCurriculumRepository repository = new InMemoryCurriculumRepository(List.of(chemistry),
				List.of(syllabus2019, syllabus2025), List.of(unit3, topic31, descriptor));
		CurriculumSelectionModel descriptorModel = new CurriculumSelectionModel(repository);

		descriptorModel.selectSubject(chemistry);
		descriptorModel.selectUnit(unit3);
		descriptorModel.selectTopic(topic31);

		assertEquals(List.of(descriptor), descriptorModel.getClassifications());

		descriptorModel.selectClassification(descriptor);
		assertEquals(descriptor, descriptorModel.getClassification());
	}

	@Test
	void exposesUnitsForDefaultSyllabus() {
		model.selectSubject(chemistry);

		assertEquals(List.of(unit3), model.getUnits());
	}

	@Test
	void allowsSubjectWithOnlyHistoricalSyllabus() {
		Subject physics = new Subject(2, "Physics");
		SyllabusVersion oldPhysics = new SyllabusVersion(3, physics, "2019", false);
		InMemoryCurriculumRepository repository = new InMemoryCurriculumRepository(List.of(physics),
				List.of(oldPhysics), List.of());
		CurriculumSelectionModel physicsModel = new CurriculumSelectionModel(repository);
		physicsModel.selectSubject(physics);
		assertEquals(physics, physicsModel.getSubject());
		assertNull(physicsModel.getSyllabusVersion());
		assertEquals(List.of(oldPhysics), physicsModel.getSyllabusVersions());
		physicsModel.selectSyllabusVersion(oldPhysics);
		assertEquals(oldPhysics, physicsModel.getSyllabusVersion());
	}

	@Test
	void rejectsSyllabusFromAnotherSubject() {
		Subject physics = new Subject(2, "Physics");
		SyllabusVersion physics2019 = new SyllabusVersion(3, physics, "2019", false);
		selectCurrentClassification();
		assertThrows(IllegalArgumentException.class, () -> model.selectSyllabusVersion(physics2019));
		assertCurrentClassificationRetained();
	}

	@Test
	void rejectsForeignSubjectEvenWhenVersionIdentifierMatches() {
		Subject physics = new Subject(2, "Physics");
		SyllabusVersion conflictingVersion = new SyllabusVersion(syllabus2025.getId(), physics, "2025", true);
		selectCurrentClassification();

		assertThrows(IllegalArgumentException.class, () -> model.selectSyllabusVersion(conflictingVersion));
		assertCurrentClassificationRetained();
	}

	@Test
	void rejectsUnavailableVersionWithoutChangingSelection() {
		SyllabusVersion unavailable = new SyllabusVersion(99, chemistry, "Unimported", false);
		selectCurrentClassification();

		assertThrows(IllegalArgumentException.class, () -> model.selectSyllabusVersion(unavailable));
		assertCurrentClassificationRetained();
	}

	@Test
	void requiresSubjectBeforeSelectingSyllabus() {
		assertThrows(IllegalStateException.class, () -> model.selectSyllabusVersion(syllabus2019));
		assertNull(model.getSubject());
		assertNull(model.getSyllabusVersion());
		assertEquals(List.of(), model.getSyllabusVersions());
		model.selectSyllabusVersion(null);
		assertEquals(List.of(), model.getUnits());
	}

	@Test
	void clearingSyllabusRetainsSubjectButClearsHierarchy() {
		selectCurrentClassification();

		model.selectSyllabusVersion(null);

		assertEquals(chemistry, model.getSubject());
		assertNull(model.getSyllabusVersion());
		assertNull(model.getUnit());
		assertNull(model.getTopic());
		assertNull(model.getClassification());
		assertEquals(List.of(syllabus2019, syllabus2025), model.getSyllabusVersions());
		assertEquals(List.of(), model.getUnits());
		assertEquals(List.of(), model.getTopics());
		assertEquals(List.of(), model.getClassifications());
	}

	@Test
	void explicitlySelectsHistoricalSyllabus() {
		selectCurrentClassification();
		model.selectSyllabusVersion(syllabus2019);
		assertEquals(chemistry, model.getSubject());
		assertEquals(syllabus2019, model.getSyllabusVersion());
		assertNull(model.getUnit());
		assertNull(model.getTopic());
		assertNull(model.getClassification());
		assertEquals(List.of(historicalUnit), model.getUnits());
		model.selectUnit(historicalUnit);
		assertEquals(List.of(historicalTopic), model.getTopics());
		model.selectTopic(historicalTopic);
		assertEquals(List.of(historicalDescriptor), model.getClassifications());
		model.selectClassification(historicalDescriptor);

		model.selectSyllabusVersion(syllabus2025);
		assertEquals(List.of(unit3), model.getUnits());
		assertNull(model.getUnit());
		assertNull(model.getTopic());
		assertNull(model.getClassification());
	}

	@Test
	void exposesSyllabusVersionsForSelectedSubject() {
		Subject physics = new Subject(2, "Physics");
		SyllabusVersion physics2019 = new SyllabusVersion(3, physics, "2019", false);
		model = new CurriculumSelectionModel(new InMemoryCurriculumRepository(List.of(physics, chemistry),
				List.of(physics2019, syllabus2019, syllabus2025), List.of()));
		assertEquals(List.of(), model.getSyllabusVersions());
		model.selectSubject(chemistry);
		assertEquals(List.of(syllabus2019, syllabus2025), model.getSyllabusVersions());
		model.selectSubject(physics);
		assertEquals(physics, model.getSubject());
		assertEquals(List.of(physics2019), model.getSyllabusVersions());
		assertNull(model.getSyllabusVersion());
		assertEquals(List.of(), model.getUnits());
	}

	@Test
	void selectingSubjectWithoutAnySyllabusesClearsPreviousHierarchy() {
		Subject biology = new Subject(3, "Biology");
		model = new CurriculumSelectionModel(new InMemoryCurriculumRepository(List.of(chemistry, biology),
				List.of(syllabus2019, syllabus2025), List.of(unit3, topic31, subtopic311)));
		selectCurrentClassification();

		model.selectSubject(biology);

		assertEquals(biology, model.getSubject());
		assertNull(model.getSyllabusVersion());
		assertNull(model.getUnit());
		assertNull(model.getTopic());
		assertNull(model.getClassification());
		assertEquals(List.of(), model.getSyllabusVersions());
		assertEquals(List.of(), model.getUnits());
	}

	private void selectCurrentClassification() {
		model.selectSubject(chemistry);
		model.selectUnit(unit3);
		model.selectTopic(topic31);
		model.selectClassification(subtopic311);
	}

	private void assertCurrentClassificationRetained() {
		assertEquals(chemistry, model.getSubject());
		assertEquals(syllabus2025, model.getSyllabusVersion());
		assertEquals(unit3, model.getUnit());
		assertEquals(topic31, model.getTopic());
		assertEquals(subtopic311, model.getClassification());
	}

	@Test
	void selectsCurrentSyllabusForSubject() {
		model.selectSubject(chemistry);

		assertEquals(chemistry, model.getSubject());

		assertEquals(syllabus2025, model.getSyllabusVersion());
	}

	@BeforeEach
	void setUp() {
		chemistry = new Subject(1, "Chemistry");
		syllabus2019 = new SyllabusVersion(1, chemistry, "2019", false);
		syllabus2025 = new SyllabusVersion(2, chemistry, "2025", true);
		unit3 = new Unit(1, syllabus2025, "3", "Unit 3", 1);
		topic31 = new Topic(2, syllabus2025, unit3, "3.1", "Topic 3.1", 1);
		subtopic311 = new Subtopic(3, syllabus2025, topic31, "3.1.1", "Subtopic 3.1.1", 1);
		historicalUnit = new Unit(5, syllabus2019, "1", "Historical unit", 1);
		historicalTopic = new Topic(6, syllabus2019, historicalUnit, "1.1", "Historical topic", 1);
		historicalDescriptor = new Descriptor(7, syllabus2019, historicalTopic, "1.1.1", "Historical descriptor", 1);
		InMemoryCurriculumRepository repository = new InMemoryCurriculumRepository(List.of(chemistry),
				List.of(syllabus2019, syllabus2025),
				List.of(unit3, topic31, subtopic311, historicalUnit, historicalTopic, historicalDescriptor));
		model = new CurriculumSelectionModel(repository);
	}
}
