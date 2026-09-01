package au.edu.eq.questionbank.importer.curriculum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;

class CurriculumNodeBuilderTest {

	private final CurriculumNodeBuilder builder = new CurriculumNodeBuilder();

	private SyllabusVersion syllabus;

	private void assertNode(CurriculumNode node, String expectedCode, String expectedContent,
			CurriculumLevel expectedLevel) {

		assertEquals(expectedCode, node.getCode());

		assertEquals(expectedContent, node.getName());

		assertEquals(expectedLevel, node.getLevel());
	}

	private CurriculumImportRow row(String code, String content) {

		return new CurriculumImportRow(code, content);
	}

	@Test
	void assignsDisplayOrderWithinEachParent() {

		List<CurriculumImportRow> rows = List.of(row("1", "Unit 1"), row("1.1", "Topic 1"), row("1.2", "Topic 2"),
				row("1.1.1", "First descriptor"), row("1.1.2", "Second descriptor"), row("1.2.1", "Third descriptor"));

		List<CurriculumNode> nodes = builder.build(syllabus, rows, new AtomicLong(1)::getAndIncrement);

		assertEquals(1, nodes.get(0).getDisplayOrder());

		assertEquals(1, nodes.get(1).getDisplayOrder());
		assertEquals(2, nodes.get(2).getDisplayOrder());

		assertEquals(1, nodes.get(3).getDisplayOrder());
		assertEquals(2, nodes.get(4).getDisplayOrder());

		assertEquals(1, nodes.get(5).getDisplayOrder());
	}

	@Test
	void buildsFourLevelHierarchyWithSubtopicAndDescriptors() {

		List<CurriculumImportRow> rows = List.of(row("4", "Structure and synthesis"), row("4.2", "Organic materials"),
				row("4.2.2", "Organic reactions"), row("4.2.2.1", "Describe addition reactions"),
				row("4.2.2.2", "Explain substitution reactions"));

		AtomicLong ids = new AtomicLong(1);

		List<CurriculumNode> nodes = builder.build(syllabus, rows, ids::getAndIncrement);

		assertEquals(5, nodes.size());

		assertNode(nodes.get(0), "4", "Structure and synthesis", CurriculumLevel.UNIT);

		assertNode(nodes.get(1), "4.2", "Organic materials", CurriculumLevel.TOPIC);

		assertNode(nodes.get(2), "4.2.2", "Organic reactions", CurriculumLevel.SUBTOPIC);

		assertNode(nodes.get(3), "4.2.2.1", "Describe addition reactions", CurriculumLevel.DESCRIPTOR);

		assertNode(nodes.get(4), "4.2.2.2", "Explain substitution reactions", CurriculumLevel.DESCRIPTOR);

		assertSame(nodes.get(1), nodes.get(2).getParent());

		assertSame(nodes.get(2), nodes.get(3).getParent());

		assertSame(nodes.get(2), nodes.get(4).getParent());
	}

	@Test
	void buildsThreeLevelHierarchyWithDescriptorsUnderTopic() {

		List<CurriculumImportRow> rows = List.of(row("2", "Individual behaviour"), row("2.3", "Diagnosis"),
				row("2.3.1", "Describe diagnostic systems"), row("2.3.2", "Explain approaches to diagnosis"));

		AtomicLong ids = new AtomicLong(1);

		List<CurriculumNode> nodes = builder.build(syllabus, rows, ids::getAndIncrement);

		assertEquals(4, nodes.size());

		assertNode(nodes.get(0), "2", "Individual behaviour", CurriculumLevel.UNIT);

		assertNode(nodes.get(1), "2.3", "Diagnosis", CurriculumLevel.TOPIC);

		assertNode(nodes.get(2), "2.3.1", "Describe diagnostic systems", CurriculumLevel.DESCRIPTOR);

		assertNode(nodes.get(3), "2.3.2", "Explain approaches to diagnosis", CurriculumLevel.DESCRIPTOR);

		assertSame(nodes.get(1), nodes.get(2).getParent());

		assertSame(nodes.get(1), nodes.get(3).getParent());
	}

	@Test
	void rejectsCodesDeeperThanFourLevels() {

		List<CurriculumImportRow> rows = List.of(row("1", "Unit 1"), row("1.1", "Topic 1"), row("1.1.1", "Subtopic"),
				row("1.1.1.1", "Descriptor"), row("1.1.1.1.1", "Too deep"));

		assertThrows(IllegalArgumentException.class,
				() -> builder.build(syllabus, rows, new AtomicLong(1)::getAndIncrement));
	}

	@Test
	void rejectsDuplicateCodes() {

		List<CurriculumImportRow> rows = List.of(row("1", "Unit 1"), row("1.1", "First topic name"),
				row("1.1", "Second topic name"));

		assertThrows(IllegalArgumentException.class,
				() -> builder.build(syllabus, rows, new AtomicLong(1)::getAndIncrement));
	}

	@Test
	void rejectsMissingParent() {

		List<CurriculumImportRow> rows = List.of(row("1", "Unit 1"), row("1.1.1", "Descriptor without topic"));

		assertThrows(IllegalArgumentException.class,
				() -> builder.build(syllabus, rows, new AtomicLong(1)::getAndIncrement));
	}

	@Test
	void returnsImmutableNodeList() {

		List<CurriculumImportRow> rows = List.of(row("1", "Unit 1"), row("1.1", "Topic 1"), row("1.1.1", "Descriptor"));

		List<CurriculumNode> nodes = builder.build(syllabus, rows, new AtomicLong(1)::getAndIncrement);

		assertThrows(UnsupportedOperationException.class, () -> nodes.clear());
	}

	@BeforeEach
	void setUp() {
		Subject subject = new Subject(1, "Test Subject");

		syllabus = new SyllabusVersion(1, subject, "2025", true);
	}
}