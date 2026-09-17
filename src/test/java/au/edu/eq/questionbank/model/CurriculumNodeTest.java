package au.edu.eq.questionbank.model;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CurriculumNodeTest {

	private SyllabusVersion syllabusVersion;
	private Subject chemistry;
	private Unit unit;
	private Topic topic;
	private Subtopic subtopic;
	private Descriptor topicDescriptor;
	private Descriptor subtopicDescriptor;

	@Test
	void acceptsValidUnitTopicSubtopicHierarchy() {
		assertEquals(unit, topic.getParent());
		assertEquals(topic, subtopic.getParent());
		assertEquals(CurriculumLevel.UNIT, unit.getLevel());
		assertEquals(CurriculumLevel.TOPIC, topic.getLevel());
		assertEquals(CurriculumLevel.SUBTOPIC, subtopic.getLevel());
	}

	@Test
	void acceptsZeroDisplayOrder() {
		Unit firstUnit = new Unit(6, syllabusVersion, "2", "Unit 2", 0);
		assertEquals(0, firstUnit.getDisplayOrder());
	}

	@Test
	void descriptorMayBelongDirectlyToTopic() {
		assertEquals(topic, topicDescriptor.getParent());
	}

	@Test
	void descriptorMayBelongToSubtopic() {
		assertEquals(subtopic, subtopicDescriptor.getParent());
	}

	@Test
	void descriptorsExposeCorrectLevelsAndValues() {
		assertAll(() -> assertEquals(4, topicDescriptor.getId()),
				() -> assertSame(syllabusVersion, topicDescriptor.getSyllabusVersion()),
				() -> assertSame(topic, topicDescriptor.getParent()),
				() -> assertEquals("1.1.a", topicDescriptor.getCode()),
				() -> assertEquals("Topic descriptor", topicDescriptor.getName()),
				() -> assertEquals(CurriculumLevel.DESCRIPTOR, topicDescriptor.getLevel()),
				() -> assertEquals(0, topicDescriptor.getDisplayOrder()),
				() -> assertEquals(5, subtopicDescriptor.getId()),
				() -> assertSame(syllabusVersion, subtopicDescriptor.getSyllabusVersion()),
				() -> assertSame(subtopic, subtopicDescriptor.getParent()),
				() -> assertEquals("1.1.1.a", subtopicDescriptor.getCode()),
				() -> assertEquals("Subtopic descriptor", subtopicDescriptor.getName()),
				() -> assertEquals(CurriculumLevel.DESCRIPTOR, subtopicDescriptor.getLevel()),
				() -> assertEquals(0, subtopicDescriptor.getDisplayOrder()));
	}

	@Test
	void equalityUsesIdAcrossConcreteNodeTypes() {
		Unit sameIdUnit = new Unit(7, syllabusVersion, "3", "Unit 3", 1);
		Topic sameIdTopic = new Topic(7, syllabusVersion, unit, "1.2", "Topic 2", 1);
		assertEquals(sameIdUnit, sameIdTopic);
		assertEquals(sameIdTopic, sameIdUnit);
		assertEquals(sameIdUnit.hashCode(), sameIdTopic.hashCode());
	}

	@Test
	void equalityUsesNodeIdentity() {
		Unit first = new Unit(7, syllabusVersion, "3", "Unit 3", 1);
		Unit sameId = new Unit(7, syllabusVersion, "4", "Unit 4", 2);
		Unit differentId = new Unit(8, syllabusVersion, "3", "Unit 3", 1);
		assertEquals(first, sameId);
		assertEquals(first.hashCode(), sameId.hashCode());
		assertNotEquals(first, differentId);
	}

	@Test
	void exposesNodeValuesAndReadableText() {
		Unit unit = new Unit(10, syllabusVersion, "3", "Unit 3", 1);
		assertAll(() -> assertEquals(10, unit.getId()), () -> assertEquals(syllabusVersion, unit.getSyllabusVersion()),
				() -> assertEquals("3", unit.getCode()), () -> assertEquals("Unit 3", unit.getName()),
				() -> assertEquals(CurriculumLevel.UNIT, unit.getLevel()),
				() -> assertEquals(1, unit.getDisplayOrder()), () -> assertEquals("3 Unit 3", unit.toString()));
	}

	@Test
	void rejectsDescriptorParentFromDifferentSyllabusVersion() {
		SyllabusVersion syllabus2019 = new SyllabusVersion(2, chemistry, "2019", false);
		Unit unit2019 = new Unit(6, syllabus2019, "3", "Unit 3", 1);
		Topic topic2019 = new Topic(7, syllabus2019, unit2019, "3.1", "Topic 3.1", 1);
		assertThrows(IllegalArgumentException.class,
				() -> new Descriptor(8, syllabusVersion, topic2019, "3.1.a", "Descriptor", 0));
	}

	@Test
	void rejectsInvalidRequiredValues() {
		assertAll(() -> assertThrows(NullPointerException.class, () -> new Unit(1, null, "1", "Unit", 0)),
				() -> assertThrows(IllegalArgumentException.class, () -> new Unit(1, syllabusVersion, " ", "Unit", 0)),
				() -> assertThrows(IllegalArgumentException.class, () -> new Unit(1, syllabusVersion, "1", " ", 0)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new Unit(1, syllabusVersion, "1", "Unit", -1)));
	}

	@Test
	void rejectsNegativeId() {
		assertThrows(IllegalArgumentException.class, () -> new Unit(-1, syllabusVersion, "3", "Unit 3", 1));
	}

	@Test
	void rejectsNullCodeAndName() {
		assertAll(
				() -> assertThrows(IllegalArgumentException.class,
						() -> new Unit(6, syllabusVersion, null, "Unit 2", 0)),
				() -> assertThrows(IllegalArgumentException.class, () -> new Unit(6, syllabusVersion, "2", null, 0)));
	}

	@Test
	void rejectsNullParentsForNonUnitNodes() {
		assertAll(
				() -> assertThrows(IllegalArgumentException.class,
						() -> new Topic(6, syllabusVersion, null, "1.2", "Topic 2", 0)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new Subtopic(6, syllabusVersion, null, "1.1.2", "Subtopic 2", 0)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new Descriptor(6, syllabusVersion, (Topic) null, "1.2.a", "Descriptor", 0)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new Descriptor(6, syllabusVersion, (Subtopic) null, "1.1.2.a", "Descriptor", 0)));
	}

	@Test
	void rejectsParentFromDifferentSyllabusVersion() {
		SyllabusVersion syllabus2019 = new SyllabusVersion(2, chemistry, "2019", false);
		Unit unit2019 = new Unit(6, syllabus2019, "3", "Unit 3", 1);
		assertThrows(IllegalArgumentException.class,
				() -> new Topic(7, syllabusVersion, unit2019, "3.1", "Topic 3.1", 1));
	}

	@Test
	void rejectsTopicWithoutParent() {
		assertThrows(IllegalArgumentException.class, () -> new Topic(6, syllabusVersion, null, "3.1", "Topic 3.1", 1));
	}

	@Test
	void rejectsZeroId() {
		assertThrows(IllegalArgumentException.class, () -> new Unit(0, syllabusVersion, "3", "Unit 3", 1));
	}

	@BeforeEach
	void setUp() {
		chemistry = new Subject(1, "Chemistry");
		syllabusVersion = new SyllabusVersion(1, chemistry, "2025", true);
		unit = new Unit(1, syllabusVersion, "1", "Unit 1", 0);
		topic = new Topic(2, syllabusVersion, unit, "1.1", "Topic 1", 0);
		subtopic = new Subtopic(3, syllabusVersion, topic, "1.1.1", "Subtopic 1", 0);
		topicDescriptor = new Descriptor(4, syllabusVersion, topic, "1.1.a", "Topic descriptor", 0);
		subtopicDescriptor = new Descriptor(5, syllabusVersion, subtopic, "1.1.1.a", "Subtopic descriptor", 0);
	}

	@Test
	void unitHasNoParent() {
		assertNull(unit.getParent());
	}
}
