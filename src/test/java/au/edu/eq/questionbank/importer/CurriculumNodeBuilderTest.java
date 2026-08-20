package au.edu.eq.questionbank.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

	@BeforeEach
	void setUp() {
		syllabus = new SyllabusVersion(1, new Subject(1, "Chemistry"), "2025", true);
	}

	@Test
	void buildsEachHierarchyNodeOnceInEncounterOrder() {
		List<CurriculumImportRow> rows = List.of(
				row("Unit 3", "Topic 1", "Equilibrium", "3.1.1", "First descriptor"),
				row("Unit 3", "Topic 1", "Equilibrium", "3.1.1", "Second descriptor"),
				row("Unit 3", "Topic 1", "Le Chatelier", "3.1.2", "Third descriptor"),
				row("Unit 3", "Topic 2", "Acids", "3.2.1", "Fourth descriptor"));
		AtomicLong ids = new AtomicLong(1);

		List<CurriculumNode> nodes = builder.build(syllabus, rows, ids::getAndIncrement);

		assertEquals(List.of("3", "3.1", "3.1.1", "3.1.2", "3.2", "3.2.1"),
				nodes.stream().map(CurriculumNode::getCode).toList());
		assertEquals(List.of(CurriculumLevel.UNIT, CurriculumLevel.TOPIC, CurriculumLevel.SUBTOPIC,
				CurriculumLevel.SUBTOPIC, CurriculumLevel.TOPIC, CurriculumLevel.SUBTOPIC),
				nodes.stream().map(CurriculumNode::getLevel).toList());
	}

	@Test
	void assignsDisplayOrderWithinEachParent() {
		List<CurriculumImportRow> rows = List.of(
				row("Unit 3", "Topic 1", "First", "3.1.1", "Descriptor"),
				row("Unit 3", "Topic 1", "Second", "3.1.2", "Descriptor"),
				row("Unit 3", "Topic 2", "Third", "3.2.1", "Descriptor"));

		List<CurriculumNode> nodes = builder.build(syllabus, rows, new AtomicLong(1)::getAndIncrement);

		assertEquals(List.of(1, 1, 1, 2, 2, 1),
				nodes.stream().map(CurriculumNode::getDisplayOrder).toList());
	}

	@Test
	void rejectsMalformedClassificationCodes() {
		List<CurriculumImportRow> rows = List.of(row("Unit 3", "Topic 1", "Subtopic", "3.1", "Descriptor"));

		assertThrows(IllegalArgumentException.class,
				() -> builder.build(syllabus, rows, new AtomicLong(1)::getAndIncrement));
	}

	@Test
	void rejectsConflictingNamesForTheSameCode() {
		List<CurriculumImportRow> rows = List.of(
				row("Unit 3", "Topic 1", "First name", "3.1.1", "Descriptor"),
				row("Unit 3", "Topic 1", "Different name", "3.1.1", "Descriptor"));

		assertThrows(IllegalArgumentException.class,
				() -> builder.build(syllabus, rows, new AtomicLong(1)::getAndIncrement));
	}

	@Test
	void returnsAnImmutableNodeList() {
		List<CurriculumNode> nodes = builder.build(syllabus,
				List.of(row("Unit 3", "Topic 1", "Subtopic", "3.1.1", "Descriptor")),
				new AtomicLong(1)::getAndIncrement);

		assertThrows(UnsupportedOperationException.class, () -> nodes.clear());
	}

	private CurriculumImportRow row(String unit, String topic, String subtopic, String code, String descriptor) {
		return new CurriculumImportRow(unit, topic, subtopic, code, descriptor);
	}
}
