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
import au.edu.eq.questionbank.repository.InMemoryCurriculumRepository;

class CurriculumSelectionModelTest {

	private Subject chemistry;
	private SyllabusVersion syllabus2019;
	private SyllabusVersion syllabus2025;

	private Unit unit3;
	private Topic topic31;
	private Subtopic subtopic311;

	private CurriculumSelectionModel model;

	@Test
	void cascadesUnitTopicAndSubtopicSelections() {
		model.selectSubject(chemistry);
		model.selectUnit(unit3);
		assertEquals(List.of(topic31), model.getTopics());
		model.selectTopic(topic31);
		assertEquals(List.of(subtopic311), model.getSubtopics());
		model.selectSubtopic(subtopic311);
		assertEquals(subtopic311, model.getSubtopic());
	}

	@Test
	void changingSubjectClearsLowerSelections() {
		model.selectSubject(chemistry);
		model.selectUnit(unit3);
		model.selectTopic(topic31);
		model.selectSubtopic(subtopic311);

		model.selectSubject(null);

		assertNull(model.getSubject());
		assertNull(model.getSyllabusVersion());
		assertNull(model.getUnit());
		assertNull(model.getTopic());
		assertNull(model.getSubtopic());

		assertEquals(List.of(), model.getUnits());
	}

	@Test
	void changingTopicClearsSubtopicSelection() {
		model.selectSubject(chemistry);
		model.selectUnit(unit3);
		model.selectTopic(topic31);
		model.selectSubtopic(subtopic311);

		model.selectTopic(null);

		assertNull(model.getTopic());
		assertNull(model.getSubtopic());

		assertEquals(List.of(), model.getSubtopics());
	}

	@Test
	void changingUnitClearsLowerSelections() {
		model.selectSubject(chemistry);
		model.selectUnit(unit3);
		model.selectTopic(topic31);
		model.selectSubtopic(subtopic311);

		model.selectUnit(null);

		assertNull(model.getUnit());
		assertNull(model.getTopic());
		assertNull(model.getSubtopic());

		assertEquals(List.of(), model.getTopics());

		assertEquals(List.of(), model.getSubtopics());
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

		assertEquals(List.of(descriptor), descriptorModel.getSubtopics());

		descriptorModel.selectSubtopic(descriptor);
		assertEquals(descriptor, descriptorModel.getSubtopic());
	}

	@Test
	void exposesUnitsForSelectedSubject() {
		model.selectSubject(chemistry);

		assertEquals(List.of(unit3), model.getUnits());
	}

	@Test
	void rejectsSubjectWithoutCurrentSyllabus() {
		Subject physics = new Subject(2, "Physics");

		SyllabusVersion oldPhysics = new SyllabusVersion(3, physics, "2019", false);

		InMemoryCurriculumRepository repository = new InMemoryCurriculumRepository(List.of(physics),
				List.of(oldPhysics), List.of());

		CurriculumSelectionModel physicsModel = new CurriculumSelectionModel(repository);

		assertThrows(IllegalStateException.class, () -> physicsModel.selectSubject(physics));
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
		InMemoryCurriculumRepository repository = new InMemoryCurriculumRepository(List.of(chemistry),
				List.of(syllabus2019, syllabus2025), List.of(unit3, topic31, subtopic311));
		model = new CurriculumSelectionModel(repository);
	}
}
