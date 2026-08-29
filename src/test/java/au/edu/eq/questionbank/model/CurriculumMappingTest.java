package au.edu.eq.questionbank.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CurriculumMappingTest {

	private Subject chemistry;
	private SyllabusVersion syllabus2019;
	private SyllabusVersion syllabus2025;
	private Unit unit2019;
	private Unit unit2025;
	private Topic topic2019;
	private Topic topic2025;
	private Subtopic subtopic2019;
	private Subtopic subtopic2025;

	@Test
	void equalityUsesMappingIdentity() {
		CurriculumMapping first = new CurriculumMapping(7, unit2019, unit2025, MappingStatus.CONFIRMED);

		CurriculumMapping sameId = new CurriculumMapping(7, subtopic2019, subtopic2025, MappingStatus.SUGGESTED);

		CurriculumMapping differentId = new CurriculumMapping(8, unit2019, unit2025, MappingStatus.CONFIRMED);

		assertEquals(first, sameId);
		assertEquals(first.hashCode(), sameId.hashCode());
		assertNotEquals(first, differentId);
	}

	@Test
	void mapsNodesAcrossSyllabusVersions() {
		CurriculumMapping mapping = new CurriculumMapping(1, subtopic2019, subtopic2025, MappingStatus.CONFIRMED);

		assertEquals(1, mapping.getId());
		assertEquals(subtopic2019, mapping.getSource());
		assertEquals(subtopic2025, mapping.getTarget());
		assertEquals(MappingStatus.CONFIRMED, mapping.getStatus());
	}

	@Test
	void rejectsMappingBetweenDifferentCurriculumLevels() {
		assertThrows(IllegalArgumentException.class,
				() -> new CurriculumMapping(1, unit2019, topic2025, MappingStatus.CONFIRMED));
	}

	@Test
	void rejectsMappingBetweenDifferentSubjects() {
		Subject physics = new Subject(2, "Physics");

		SyllabusVersion physics2025 = new SyllabusVersion(3, physics, "2025", true);

		Unit physicsUnit = new Unit(7, physics2025, "3", "Unit 3", 1);

		assertThrows(IllegalArgumentException.class,
				() -> new CurriculumMapping(1, unit2019, physicsUnit, MappingStatus.CONFIRMED));
	}

	@Test
	void rejectsMappingWithinSameSyllabusVersion() {
		Unit otherUnit2025 = new Unit(7, syllabus2025, "4", "Unit 4", 2);

		assertThrows(IllegalArgumentException.class,
				() -> new CurriculumMapping(1, unit2025, otherUnit2025, MappingStatus.CONFIRMED));
	}

	@Test
	void rejectsNegativeId() {
		assertThrows(IllegalArgumentException.class,
				() -> new CurriculumMapping(-1, unit2019, unit2025, MappingStatus.CONFIRMED));
	}

	@Test
	void rejectsNullSource() {
		assertThrows(NullPointerException.class,
				() -> new CurriculumMapping(1, null, unit2025, MappingStatus.CONFIRMED));
	}

	@Test
	void rejectsNullStatus() {
		assertThrows(NullPointerException.class, () -> new CurriculumMapping(1, unit2019, unit2025, null));
	}

	@Test
	void rejectsNullTarget() {
		assertThrows(NullPointerException.class,
				() -> new CurriculumMapping(1, unit2019, null, MappingStatus.CONFIRMED));
	}

	@Test
	void rejectsZeroId() {
		assertThrows(IllegalArgumentException.class,
				() -> new CurriculumMapping(0, subtopic2019, subtopic2025, MappingStatus.CONFIRMED));
	}

	@BeforeEach
	void setUp() {
		chemistry = new Subject(1, "Chemistry");
		syllabus2019 = new SyllabusVersion(1, chemistry, "2019", false);
		syllabus2025 = new SyllabusVersion(2, chemistry, "2025", true);
		unit2019 = new Unit(1, syllabus2019, "3", "Old Unit 3", 1);
		topic2019 = new Topic(2, syllabus2019, unit2019, "3.1", "Old Topic 3.1", 1);
		subtopic2019 = new Subtopic(3, syllabus2019, topic2019, "3.1.1", "2019 subtopic", 1);
		unit2025 = new Unit(4, syllabus2025, "3", "New Unit 3", 1);
		topic2025 = new Topic(5, syllabus2025, unit2025, "3.1", "New Topic 3.1", 1);
		subtopic2025 = new Subtopic(6, syllabus2025, topic2025, "3.1.1", "2025 subtopic", 1);
	}
}