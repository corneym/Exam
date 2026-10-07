package au.edu.eq.questionbank.service.curriculum;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;

class CurriculumSourcePdfStoreTest {

	@TempDir
	Path tempDir;

	@Test
	void copiesExternalPdfIntoManagedCurriculumStorage() throws Exception {
		Path curriculumRoot = tempDir.resolve("data").resolve("curriculum");
		Path externalDirectory = tempDir.resolve("downloads");
		Files.createDirectories(externalDirectory);
		Path externalPdf = externalDirectory.resolve("Engineering Syllabus 2025.pdf");
		byte[] originalContent = """
				%PDF-1.4
				test syllabus content
				%%EOF
				""".getBytes(StandardCharsets.UTF_8);
		Files.write(externalPdf, originalContent);
		Subject engineering = new Subject(1, "Engineering");
		SyllabusVersion syllabus = new SyllabusVersion(7, engineering, "2025", true);
		CurriculumSourcePdfStore store = new CurriculumSourcePdfStore(curriculumRoot);
		String relativePath = store.managePdf(syllabus, externalPdf);
		assertTrue(relativePath.startsWith("Engineering--subject-1/" + "2025--syllabus-7/" + "sources/"));
		assertTrue(relativePath.endsWith("Engineering-Syllabus-2025.pdf"));
		Path managedPdf = store.resolveManagedPdf(relativePath);
		assertTrue(Files.isRegularFile(managedPdf));
		assertArrayEquals(originalContent, Files.readAllBytes(managedPdf));

		// The application must no longer depend on the original external file once it
		// has been managed.
		Files.delete(externalPdf);
		assertFalse(Files.exists(externalPdf));
		assertTrue(Files.isRegularFile(managedPdf));
		assertArrayEquals(originalContent, Files.readAllBytes(managedPdf));
	}

	@Test
	void copiesExternalPdfIntoSubjectFirstSourceDirectory() throws Exception {
		Path dataRoot = tempDir.resolve("subject-first-data");
		Path curriculumRoot = dataRoot.resolve("curriculum");
		ManagedDataLayout layout = new ManagedDataLayout(dataRoot);
		Path externalDirectory = Files.createDirectories(tempDir.resolve("subject-first-downloads"));
		Path externalPdf = externalDirectory.resolve("Engineering Syllabus 2025.pdf");
		byte[] originalContent = """
				%PDF-1.4
				subject-first syllabus
				%%EOF
				""".getBytes(StandardCharsets.UTF_8);
		Files.write(externalPdf, originalContent);
		Subject engineering = new Subject(1, "Engineering");
		SyllabusVersion syllabus = new SyllabusVersion(7, engineering, "2025", true);
		CurriculumSourcePdfStore store = new CurriculumSourcePdfStore(layout, curriculumRoot);
		String relativePath = store.managePdf(syllabus, externalPdf);
		assertTrue(relativePath.startsWith("subjects/Engineering/curriculum/2025/sources/"));
		assertTrue(relativePath.endsWith("Engineering-Syllabus-2025.pdf"));
		Path managedPdf = store.resolveManagedPdf(relativePath);
		assertTrue(managedPdf.startsWith(layout.curriculumSourceDirectory("Engineering", "2025")));
		assertTrue(Files.isRegularFile(managedPdf));
		assertArrayEquals(originalContent, Files.readAllBytes(managedPdf));

		// External selection remains independent of the managed copy.
		Files.delete(externalPdf);
		assertFalse(Files.exists(externalPdf));
		assertArrayEquals(originalContent, Files.readAllBytes(managedPdf));
	}

	@Test
	void rejectsManagedPathThatEscapesCurriculumRoot() {
		CurriculumSourcePdfStore store = new CurriculumSourcePdfStore(tempDir.resolve("curriculum"));
		assertThrows(IllegalArgumentException.class, () -> store.resolveManagedPdf("../outside.pdf"));
	}

	@Test
	void subjectFirstResolutionRejectsTraversalOutsideDataRoot() {
		Path dataRoot = tempDir.resolve("containment-data").toAbsolutePath().normalize();
		Path curriculumRoot = dataRoot.resolve("curriculum");
		CurriculumSourcePdfStore store = new CurriculumSourcePdfStore(new ManagedDataLayout(dataRoot), curriculumRoot);
		assertThrows(IllegalArgumentException.class, () -> store
				.resolveManagedPdf("subjects/Chemistry/curriculum/2025/sources/../../../../../../outside.pdf"));
	}

	@Test
	void subjectFirstSourcesAreIsolatedBySubjectAndVersion() throws Exception {
		Path dataRoot = tempDir.resolve("isolated-data");
		Path curriculumRoot = dataRoot.resolve("curriculum");
		ManagedDataLayout layout = new ManagedDataLayout(dataRoot);
		CurriculumSourcePdfStore store = new CurriculumSourcePdfStore(layout, curriculumRoot);
		Path source = tempDir.resolve("shared-name.pdf");
		Files.writeString(source, "%PDF-1.4\nsource\n%%EOF");
		Subject chemistry = new Subject(1, "Chemistry");
		Subject physics = new Subject(2, "Physics");
		String chemistry2025 = store.managePdf(new SyllabusVersion(10, chemistry, "2025", true), source);
		String chemistry2019 = store.managePdf(new SyllabusVersion(11, chemistry, "2019", false), source);
		String physics2025 = store.managePdf(new SyllabusVersion(12, physics, "2025", true), source);
		assertTrue(chemistry2025.startsWith("subjects/Chemistry/curriculum/2025/sources/"));
		assertTrue(chemistry2019.startsWith("subjects/Chemistry/curriculum/2019/sources/"));
		assertTrue(physics2025.startsWith("subjects/Physics/curriculum/2025/sources/"));
		assertTrue(Files.isRegularFile(store.resolveManagedPdf(chemistry2025)));
		assertTrue(Files.isRegularFile(store.resolveManagedPdf(chemistry2019)));
		assertTrue(Files.isRegularFile(store.resolveManagedPdf(physics2025)));
	}

	@Test
	void transitionalStoreResolvesLegacyAndSubjectFirstPaths() throws Exception {
		Path dataRoot = tempDir.resolve("transition-data").toAbsolutePath().normalize();
		Path curriculumRoot = dataRoot.resolve("curriculum");
		ManagedDataLayout layout = new ManagedDataLayout(dataRoot);
		CurriculumSourcePdfStore store = new CurriculumSourcePdfStore(layout, curriculumRoot);
		String subjectFirstPath = "subjects/Chemistry/curriculum/2025/sources/source.pdf";
		assertEquals(dataRoot.resolve(subjectFirstPath), store.resolveManagedPdf(subjectFirstPath));
		String legacyPath = "Chemistry--subject-1/2025--syllabus-7/sources/source.pdf";
		assertEquals(curriculumRoot.resolve(legacyPath), store.resolveManagedPdf(legacyPath));
	}
}
