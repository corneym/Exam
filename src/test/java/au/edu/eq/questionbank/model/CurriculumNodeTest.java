package au.edu.eq.questionbank.model;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CurriculumNodeTest {

	private Subject chemistry;
	private SyllabusVersion syllabus;

	@Test
	void acceptsValidUnitTopicSubtopicHierarchy() {
		Subject chemistry = new Subject(1, "Chemistry");

		SyllabusVersion syllabus2025 = new SyllabusVersion(1, chemistry, "2025", true);

		CurriculumNode unit3 = new CurriculumNode(1, syllabus2025, null, "3", "Unit 3", CurriculumLevel.UNIT, 1);

		CurriculumNode topic31 = new CurriculumNode(2, syllabus2025, unit3, "3.1", "Topic 3.1", CurriculumLevel.TOPIC,
				1);

		new CurriculumNode(3, syllabus2025, topic31, "3.1.1", "Subtopic 3.1.1", CurriculumLevel.SUBTOPIC, 1);
	}

	@Test
	void equalityUsesNodeIdentity() {
		CurriculumNode first = new CurriculumNode(7, syllabus, null, "3", "Unit 3", CurriculumLevel.UNIT, 1);
		CurriculumNode sameId = new CurriculumNode(7, syllabus, null, "4", "Unit 4", CurriculumLevel.UNIT, 2);
		CurriculumNode differentId = new CurriculumNode(8, syllabus, null, "3", "Unit 3", CurriculumLevel.UNIT, 1);

		assertEquals(first, sameId);
		assertEquals(first.hashCode(), sameId.hashCode());
		assertNotEquals(first, differentId);
	}

	@Test
	void exposesNodeValuesAndReadableText() {
		CurriculumNode unit = new CurriculumNode(10, syllabus, null, "3", "Unit 3", CurriculumLevel.UNIT, 1);

		assertAll(() -> assertEquals(10, unit.getId()), () -> assertEquals(syllabus, unit.getSyllabusVersion()),
				() -> assertEquals("3", unit.getCode()), () -> assertEquals("Unit 3", unit.getName()),
				() -> assertEquals(CurriculumLevel.UNIT, unit.getLevel()),
				() -> assertEquals(1, unit.getDisplayOrder()), () -> assertEquals("3 Unit 3", unit.toString()));
	}

	@Test
	void rejectsAParentFromAnotherSyllabusVersion() {
		SyllabusVersion other = new SyllabusVersion(2, chemistry, "2019", false);
		CurriculumNode parent = new CurriculumNode(1, other, null, "3", "Unit 3", CurriculumLevel.UNIT, 1);

		assertThrows(IllegalArgumentException.class,
				() -> new CurriculumNode(2, syllabus, parent, "3.1", "Topic 1", CurriculumLevel.TOPIC, 1));
	}

	@Test
	void rejectsInvalidRequiredValues() {
		assertAll(
				() -> assertThrows(NullPointerException.class,
						() -> new CurriculumNode(1, null, null, "1", "Unit", CurriculumLevel.UNIT, 0)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new CurriculumNode(1, syllabus, null, " ", "Unit", CurriculumLevel.UNIT, 0)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new CurriculumNode(1, syllabus, null, "1", " ", CurriculumLevel.UNIT, 0)),
				() -> assertThrows(NullPointerException.class,
						() -> new CurriculumNode(1, syllabus, null, "1", "Unit", null, 0)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new CurriculumNode(1, syllabus, null, "1", "Unit", CurriculumLevel.UNIT, -1)));
	}

	@Test
	void rejectsParentFromDifferentSyllabusVersion() {
		Subject chemistry = new Subject(1, "Chemistry");

		SyllabusVersion syllabus2019 = new SyllabusVersion(1, chemistry, "2019", false);

		SyllabusVersion syllabus2025 = new SyllabusVersion(2, chemistry, "2025", true);

		CurriculumNode unit2019 = new CurriculumNode(1, syllabus2019, null, "3", "Unit 3", CurriculumLevel.UNIT, 1);

		assertThrows(IllegalArgumentException.class,
				() -> new CurriculumNode(2, syllabus2025, unit2019, "3.1", "Topic 3.1", CurriculumLevel.TOPIC, 1));
	}

	@Test
	void rejectsSubtopicDirectlyUnderUnit() {
		Subject chemistry = new Subject(1, "Chemistry");

		SyllabusVersion syllabus2025 = new SyllabusVersion(1, chemistry, "2025", true);

		CurriculumNode unit3 = new CurriculumNode(1, syllabus2025, null, "3", "Unit 3", CurriculumLevel.UNIT, 1);

		assertThrows(IllegalArgumentException.class, () -> new CurriculumNode(2, syllabus2025, unit3, "3.1.1",
				"Subtopic 3.1.1", CurriculumLevel.SUBTOPIC, 1));
	}

	@Test
	void rejectsTopicWithoutParent() {
		Subject chemistry = new Subject(1, "Chemistry");

		SyllabusVersion syllabus2025 = new SyllabusVersion(1, chemistry, "2025", true);

		assertThrows(IllegalArgumentException.class,
				() -> new CurriculumNode(1, syllabus2025, null, "3.1", "Topic 3.1", CurriculumLevel.TOPIC, 1));
	}

	@Test
	void rejectsUnitWithParent() {
		Subject chemistry = new Subject(1, "Chemistry");

		SyllabusVersion syllabus2025 = new SyllabusVersion(1, chemistry, "2025", true);

		CurriculumNode parentUnit = new CurriculumNode(1, syllabus2025, null, "3", "Unit 3", CurriculumLevel.UNIT, 1);

		assertThrows(IllegalArgumentException.class,
				() -> new CurriculumNode(2, syllabus2025, parentUnit, "4", "Unit 4", CurriculumLevel.UNIT, 2));
	}

	@Test
	void rejectsZeroId() {
		Subject chemistry = new Subject(1, "Chemistry");

		SyllabusVersion syllabus2025 = new SyllabusVersion(1, chemistry, "2025", true);

		assertThrows(IllegalArgumentException.class,
				() -> new CurriculumNode(0, syllabus2025, null, "3", "Unit 3", CurriculumLevel.UNIT, 1));
	}

	@Test
	void rejectsNegativeId() {
		Subject chemistry = new Subject(1, "Chemistry");

		SyllabusVersion syllabus2025 = new SyllabusVersion(1, chemistry, "2025", true);

		assertThrows(IllegalArgumentException.class,
				() -> new CurriculumNode(-1, syllabus2025, null, "3", "Unit 3", CurriculumLevel.UNIT, 1));
	}

	@BeforeEach
	void setUp() {
		chemistry = new Subject(1, "Chemistry");
		syllabus = new SyllabusVersion(1, chemistry, "2025", true);
	}
}
