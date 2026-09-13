package au.edu.eq.questionbank.service.curriculum;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.CurriculumLevel;

class CurriculumDraftTextExporterTest {

	@TempDir
	Path tempDir;
	private final CurriculumDraftTextExporter exporter = new CurriculumDraftTextExporter();

	@Test
	void emptyDraftStillProducesUsefulPreview() {
		String output = exporter.render(new CurriculumDraft(), null);
		assertTrue(output.contains("Node count: 0"));
		assertTrue(output.contains("Curriculum draft must contain at least one node"));
		assertTrue(output.contains("<none>"));
	}

	@Test
	void includesValidationProblemsRatherThanRefusingExport() {
		CurriculumDraft draft = new CurriculumDraft();
		draft.addNode(CurriculumLevel.TOPIC, "T1", "Orphan topic", null, 5);
		String output = exporter.render(draft, null);
		assertAll(() -> assertTrue(output.contains("VALIDATION: 1 problem")),
				() -> assertTrue(output.contains("TOPIC T1 (draft 1) requires a parent")),
				() -> assertTrue(output.contains("TOPIC [draftId=1]")));
	}

	@Test
	void rendersExplicitHierarchyAndPdfProvenance() {
		CurriculumDraft draft = new CurriculumDraft();
		CurriculumDraftNode unit = draft.addNode(CurriculumLevel.UNIT, "U1", "Unit one", null, 14);
		CurriculumDraftNode topic = draft.addNode(CurriculumLevel.TOPIC, "T1", "Chemical equilibrium", unit.draftId(),
				15);
		draft.addNode(CurriculumLevel.DESCRIPTOR, "D1", "Explain the dynamic nature of chemical equilibrium",
				topic.draftId(), 16);
		String output = exporter.render(draft, Path.of("syllabus.pdf"));
		assertAll(() -> assertTrue(output.contains("CURRICULUM DRAFT")),
				() -> assertTrue(output.contains("VALIDATION: VALID")),
				() -> assertTrue(output.contains("UNIT [draftId=1]")),
				() -> assertTrue(output.contains("TOPIC [draftId=2]")),
				() -> assertTrue(output.contains("DESCRIPTOR [draftId=3]")),
				() -> assertTrue(output.contains("code: D1")), () -> assertTrue(output.contains("parentDraftId: 2")),
				() -> assertTrue(output.contains("sourcePage: 16")),
				() -> assertTrue(output.contains("Explain the dynamic nature of chemical equilibrium")));
	}

	@Test
	void writesPreviewToUtf8TextFile() throws Exception {
		CurriculumDraft draft = new CurriculumDraft();
		draft.addNode(CurriculumLevel.UNIT, "U1", "Energy and change", null, 7);
		Path target = tempDir.resolve("preview").resolve("curriculum-draft.txt");
		exporter.write(draft, tempDir.resolve("source-syllabus.pdf"), target);
		assertTrue(Files.isRegularFile(target));
		String saved = Files.readString(target);
		assertAll(() -> assertTrue(saved.contains("Source PDF:")), () -> assertTrue(saved.contains("UNIT [draftId=1]")),
				() -> assertTrue(saved.contains("Energy and change")),
				() -> assertTrue(saved.contains("sourcePage: 7")));
	}
}
