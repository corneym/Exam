package au.edu.eq.questionbank.service.curriculum;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
		/*
		 * The application must no longer depend on the original external file once it
		 * has been managed.
		 */
		Files.delete(externalPdf);
		assertFalse(Files.exists(externalPdf));
		assertTrue(Files.isRegularFile(managedPdf));
		assertArrayEquals(originalContent, Files.readAllBytes(managedPdf));
	}

	@Test
	void rejectsManagedPathThatEscapesCurriculumRoot() {
		CurriculumSourcePdfStore store = new CurriculumSourcePdfStore(tempDir.resolve("curriculum"));
		assertThrows(IllegalArgumentException.class, () -> store.resolveManagedPdf("../outside.pdf"));
	}
}
