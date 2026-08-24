package au.edu.eq.questionbank.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CurriculumMappingTest {

	@Test
	void mapsNodesAcrossSyllabusVersions() {
		Subject chemistry = new Subject(1, "Chemistry");
		SyllabusVersion syllabus2019 = new SyllabusVersion(1, chemistry, "2019", false);
		SyllabusVersion syllabus2025 = new SyllabusVersion(2, chemistry, "2025", true);
		CurriculumNode oldNode = new CurriculumNode(1, syllabus2019, null, "3", "Old Unit 3", CurriculumLevel.UNIT, 1);
		CurriculumNode newNode = new CurriculumNode(2, syllabus2025, null, "3", "New Unit 3", CurriculumLevel.UNIT, 1);
		CurriculumMapping mapping = new CurriculumMapping(1, oldNode, newNode, MappingStatus.CONFIRMED);

		assertEquals(oldNode, mapping.getSource());
		assertEquals(newNode, mapping.getTarget());
		assertEquals(MappingStatus.CONFIRMED, mapping.getStatus());
	}

	@Test
	void rejectsMappingWithinSameSyllabusVersion() {
		Subject chemistry = new Subject(1, "Chemistry");
		SyllabusVersion syllabus2025 = new SyllabusVersion(1, chemistry, "2025", true);
		CurriculumNode node1 = new CurriculumNode(1, syllabus2025, null, "3", "Unit 3", CurriculumLevel.UNIT, 1);
		CurriculumNode node2 = new CurriculumNode(2, syllabus2025, null, "4", "Unit 4", CurriculumLevel.UNIT, 2);

		assertThrows(IllegalArgumentException.class,
				() -> new CurriculumMapping(1, node1, node2, MappingStatus.CONFIRMED));
	}

	@Test
	void rejectsNegativeId() {
		Subject chemistry = new Subject(1, "Chemistry");
		SyllabusVersion syllabus2019 = new SyllabusVersion(1, chemistry, "2019", false);
		SyllabusVersion syllabus2025 = new SyllabusVersion(2, chemistry, "2025", true);
		CurriculumNode unit2019 = new CurriculumNode(1, syllabus2019, null, "3", "Unit 3", CurriculumLevel.UNIT, 1);
		CurriculumNode unit2025 = new CurriculumNode(2, syllabus2025, null, "3", "Unit 3", CurriculumLevel.UNIT, 1);

		assertThrows(IllegalArgumentException.class,
				() -> new CurriculumMapping(-1, unit2019, unit2025, MappingStatus.CONFIRMED));
	}

	@Test
	void rejectsZeroId() {
		Subject chemistry = new Subject(1, "Chemistry");
		SyllabusVersion syllabus2019 = new SyllabusVersion(1, chemistry, "2019", false);
		SyllabusVersion syllabus2025 = new SyllabusVersion(2, chemistry, "2025", true);
		CurriculumNode unit2019 = new CurriculumNode(1, syllabus2019, null, "3", "Unit 3", CurriculumLevel.UNIT, 1);
		CurriculumNode topic2019 = new CurriculumNode(2, syllabus2019, unit2019, "3.1", "Topic 3.1",
				CurriculumLevel.TOPIC, 1);
		CurriculumNode source = new CurriculumNode(3, syllabus2019, topic2019, "3.1.1", "2019 subtopic",
				CurriculumLevel.SUBTOPIC, 1);
		CurriculumNode unit2025 = new CurriculumNode(4, syllabus2025, null, "3", "Unit 3", CurriculumLevel.UNIT, 1);
		CurriculumNode topic2025 = new CurriculumNode(5, syllabus2025, unit2025, "3.1", "Topic 3.1",
				CurriculumLevel.TOPIC, 1);
		CurriculumNode target = new CurriculumNode(6, syllabus2025, topic2025, "3.1.1", "2025 subtopic",
				CurriculumLevel.SUBTOPIC, 1);

		assertThrows(IllegalArgumentException.class,
				() -> new CurriculumMapping(0, source, target, MappingStatus.CONFIRMED));
	}
}