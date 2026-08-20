package au.edu.eq.questionbank.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;

class ChemistryCurriculumIntegrationTest {

	private CurriculumExcelImporter importer;
	private CurriculumNodeBuilder builder;
	private Subject chemistry;

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

	@Test
	void importsReal2019ChemistryCurriculum() throws Exception {
		Path workbook = resourcePath("CHM Study Checklist [2019 Syllabus].xlsx");

		List<CurriculumImportRow> rows = importer.read(workbook);

		assertEquals(199, rows.size());

		SyllabusVersion syllabus2019 = new SyllabusVersion(1, chemistry, "2019", false);

		AtomicLong ids = new AtomicLong(1);

		List<CurriculumNode> nodes = builder.build(syllabus2019, rows, ids::getAndIncrement);

		assertEquals(58, nodes.size());

		assertEquals("Analytical techniques", find(nodes, "1.1.5").getName());

		assertEquals(CurriculumLevel.SUBTOPIC, find(nodes, "3.1.1").getLevel());

		assertEquals("3.1", find(nodes, "3.1.1").getParent().getCode());

		assertEquals("3", find(nodes, "3.1.1").getParent().getParent().getCode());
	}

	@Test
	void importsReal2025ChemistryCurriculum() throws Exception {
		Path units1And2 = resourcePath("CHM Study Checklist - Unit 1 and 2 [2025 Syllabus].xlsx");

		Path units3And4 = resourcePath("CHM Study Checklist - Unit 3 and 4 [2025 Syllabus].xlsx");

		List<CurriculumImportRow> rows = new ArrayList<>();

		rows.addAll(importer.read(units1And2));
		rows.addAll(importer.read(units3And4));

		assertEquals(226, rows.size());

		SyllabusVersion syllabus2025 = new SyllabusVersion(2, chemistry, "2025", true);

		AtomicLong ids = new AtomicLong(1);

		List<CurriculumNode> nodes = builder.build(syllabus2025, rows, ids::getAndIncrement);

		assertEquals(54, nodes.size());

		assertEquals("Analytical techniques", find(nodes, "1.1.3").getName());

		assertEquals(CurriculumLevel.SUBTOPIC, find(nodes, "3.1.1").getLevel());

		assertEquals("3.1", find(nodes, "3.1.1").getParent().getCode());

		assertEquals("3", find(nodes, "3.1.1").getParent().getParent().getCode());
	}

	@BeforeEach
	void setUp() {
		importer = new CurriculumExcelImporter();
		builder = new CurriculumNodeBuilder();
		chemistry = new Subject(1, "Chemistry");
	}
}