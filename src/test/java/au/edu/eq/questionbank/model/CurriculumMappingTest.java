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
}