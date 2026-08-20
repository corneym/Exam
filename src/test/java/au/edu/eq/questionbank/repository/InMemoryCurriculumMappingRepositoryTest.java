package au.edu.eq.questionbank.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumMapping;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.MappingStatus;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;

class InMemoryCurriculumMappingRepositoryTest {

	private CurriculumNode source1;
	private CurriculumNode source2;
	private CurriculumNode target1;
	private CurriculumNode target2;

	private CurriculumMapping mapping1;
	private CurriculumMapping mapping2;
	private CurriculumMapping mapping3;

	private CurriculumMappingRepository repository;

	@BeforeEach
	void setUp() {
		Subject chemistry = new Subject(1, "Chemistry");

		SyllabusVersion syllabus2019 = new SyllabusVersion(1, chemistry, "2019", false);

		SyllabusVersion syllabus2025 = new SyllabusVersion(2, chemistry, "2025", true);

		CurriculumNode unit2019 = new CurriculumNode(1, syllabus2019, null, "3", "Unit 3", CurriculumLevel.UNIT, 1);

		CurriculumNode topic2019 = new CurriculumNode(2, syllabus2019, unit2019, "3.1", "Topic 3.1",
				CurriculumLevel.TOPIC, 1);

		source1 = new CurriculumNode(3, syllabus2019, topic2019, "3.1.1", "Old classification 1",
				CurriculumLevel.SUBTOPIC, 1);

		source2 = new CurriculumNode(4, syllabus2019, topic2019, "3.1.2", "Old classification 2",
				CurriculumLevel.SUBTOPIC, 2);

		CurriculumNode unit2025 = new CurriculumNode(5, syllabus2025, null, "3", "Unit 3", CurriculumLevel.UNIT, 1);

		CurriculumNode topic2025 = new CurriculumNode(6, syllabus2025, unit2025, "3.2", "Topic 3.2",
				CurriculumLevel.TOPIC, 1);

		target1 = new CurriculumNode(7, syllabus2025, topic2025, "3.2.1", "New classification 1",
				CurriculumLevel.SUBTOPIC, 1);

		target2 = new CurriculumNode(8, syllabus2025, topic2025, "3.2.2", "New classification 2",
				CurriculumLevel.SUBTOPIC, 2);

		mapping1 = new CurriculumMapping(1, source1, target1, MappingStatus.CONFIRMED);

		mapping2 = new CurriculumMapping(2, source1, target2, MappingStatus.CONFIRMED);

		mapping3 = new CurriculumMapping(3, source2, target2, MappingStatus.CONFIRMED);

		repository = new InMemoryCurriculumMappingRepository(List.of(mapping1, mapping2, mapping3));
	}

	@Test
	void findsAllMappings() {
		assertEquals(List.of(mapping1, mapping2, mapping3), repository.findAll());
	}

	@Test
	void findsTargetsForSource() {
		assertEquals(List.of(mapping1, mapping2), repository.findTargets(source1));
	}

	@Test
	void findsSourcesForTarget() {
		assertEquals(List.of(mapping2, mapping3), repository.findSources(target2));
	}

	@Test
	void returnsEmptyListWhenNoMappingExists() {
		assertTrue(repository.findTargets(source2).stream().noneMatch(mapping -> mapping.getTarget().equals(target1)));
	}

	@Test
	void rejectsDuplicateMappingIds() {
		CurriculumMapping duplicate = new CurriculumMapping(mapping1.getId(), source2, target1,
				MappingStatus.CONFIRMED);

		assertThrows(IllegalArgumentException.class,
				() -> new InMemoryCurriculumMappingRepository(List.of(mapping1, duplicate)));
	}

	@Test
	void takesImmutableSnapshotOfMappings() {
		List<CurriculumMapping> mappings = new java.util.ArrayList<>(List.of(mapping1));

		CurriculumMappingRepository snapshotRepository = new InMemoryCurriculumMappingRepository(mappings);

		mappings.clear();

		assertEquals(List.of(mapping1), snapshotRepository.findAll());

		assertThrows(UnsupportedOperationException.class, () -> snapshotRepository.findAll().add(mapping2));
	}
}