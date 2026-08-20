package au.edu.eq.questionbank.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;

class ChemistryCurriculumIntegrationTest {

	private void assertUniqueCodes(List<CurriculumNode> nodes) {

		Set<String> codes = new HashSet<>();

		for (CurriculumNode node : nodes) {
			if (!codes.add(node.getCode())) {
				throw new AssertionError("Duplicate curriculum code: " + node.getCode());
			}
		}
	}

	private CurriculumNode find(List<CurriculumNode> nodes, String code) {

		return nodes.stream().filter(node -> node.getCode().equals(code)).findFirst()
				.orElseThrow(() -> new AssertionError("Curriculum node not found: " + code));
	}

	private Path resourcePath(String fileName) throws Exception {

		URL resource = getClass().getResource("/curriculum/" + fileName);

		if (resource == null) {
			throw new IllegalStateException("Test resource not found: " + fileName);
		}

		return Path.of(resource.toURI());
	}

	private Set<String> rootCodes(List<CurriculumNode> nodes) {

		return nodes.stream().filter(node -> node.getParent() == null).map(CurriculumNode::getCode)
				.collect(Collectors.toSet());
	}

	@Test
	void importsReal2019And2025ChemistryCurricula() throws Exception {
		CurriculumExcelImporter importer = new CurriculumExcelImporter();

		CurriculumNodeBuilder builder = new CurriculumNodeBuilder();

		Subject chemistry = new Subject(1, "Chemistry");

		SyllabusVersion syllabus2019 = new SyllabusVersion(1, chemistry, "2019", false);

		SyllabusVersion syllabus2025 = new SyllabusVersion(2, chemistry, "2025", true);

		List<CurriculumImportRow> rows2019 = importer.read(resourcePath("CHM Study Checklist [2019 Syllabus].xlsx"));

		List<CurriculumImportRow> rows2025 = new ArrayList<>();

		rows2025.addAll(importer.read(resourcePath("CHM Study Checklist - Unit 1 and 2 [2025 Syllabus].xlsx")));

		rows2025.addAll(importer.read(resourcePath("CHM Study Checklist - Unit 3 and 4 [2025 Syllabus].xlsx")));

		assertFalse(rows2019.isEmpty());
		assertFalse(rows2025.isEmpty());

		/*
		 * Use one ID sequence across both syllabus versions. CurriculumNode identity is
		 * based on its ID, so IDs must remain unique even when different versions are
		 * loaded.
		 */
		AtomicLong ids = new AtomicLong(1);

		List<CurriculumNode> nodes2019 = builder.build(syllabus2019, rows2019, ids::getAndIncrement);

		List<CurriculumNode> nodes2025 = builder.build(syllabus2025, rows2025, ids::getAndIncrement);

		assertFalse(nodes2019.isEmpty());
		assertFalse(nodes2025.isEmpty());

		assertUniqueCodes(nodes2019);
		assertUniqueCodes(nodes2025);

		assertEquals(Set.of("1", "2", "3", "4"), rootCodes(nodes2019));

		assertEquals(Set.of("1", "2", "3", "4"), rootCodes(nodes2025));

		CurriculumNode node2019 = find(nodes2019, "3.1.1");

		CurriculumNode node2025 = find(nodes2025, "3.1.1");

		assertEquals("3.1", node2019.getParent().getCode());

		assertEquals("3", node2019.getParent().getParent().getCode());

		assertEquals("3.1", node2025.getParent().getCode());

		assertEquals("3", node2025.getParent().getParent().getCode());

		assertNotEquals(node2019.getSyllabusVersion(), node2025.getSyllabusVersion());
	}
}