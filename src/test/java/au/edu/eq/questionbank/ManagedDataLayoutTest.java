package au.edu.eq.questionbank;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedDataLayoutTest {

	@TempDir
	Path tempDir;

	@Test
	void buildsCanonicalExamDirectory() {
		Path dataRoot = tempDir.resolve("data");
		ManagedDataLayout layout = new ManagedDataLayout(dataRoot);

		assertEquals(dataRoot.resolve("subjects/Chemistry/exams/QCAA"),
				layout.examProviderDirectory("Chemistry", "QCAA"));
		assertEquals(dataRoot.resolve("subjects/Chemistry/exams/QCAA/2024"),
				layout.examYearDirectory("Chemistry", "QCAA", 2024));
		assertEquals(dataRoot.resolve("subjects/Chemistry/exams/QCAA/2024/External Assessment"),
				layout.examDirectory("Chemistry", "QCAA", 2024, "External Assessment"));
	}

	@Test
	void buildsCanonicalSubjectFirstDirectories() {
		Path dataRoot = tempDir.resolve("data");
		ManagedDataLayout layout = new ManagedDataLayout(dataRoot);

		assertEquals(dataRoot.resolve("subjects"), layout.subjectsRoot());
		assertEquals(dataRoot.resolve("subjects/Chemistry"), layout.subjectDirectory("Chemistry"));
		assertEquals(dataRoot.resolve("subjects/Chemistry/curriculum"), layout.curriculumDirectory("Chemistry"));
		assertEquals(dataRoot.resolve("subjects/Chemistry/curriculum/2025"),
				layout.curriculumVersionDirectory("Chemistry", "2025"));
		assertEquals(dataRoot.resolve("subjects/Chemistry/curriculum/2025/workbooks"),
				layout.curriculumWorkbookDirectory("Chemistry", "2025"));
		assertEquals(dataRoot.resolve("subjects/Chemistry/curriculum/2025/sources"),
				layout.curriculumSourceDirectory("Chemistry", "2025"));
		assertEquals(dataRoot.resolve("subjects/Chemistry/legacy/2019"),
				layout.legacyImportDirectory("Chemistry", "2019"));
		assertEquals(dataRoot.resolve("subjects/Chemistry/exams"), layout.examsDirectory("Chemistry"));
	}

	@Test
	void doesNotCreateManagedDirectories() {
		Path dataRoot = tempDir.resolve("data");
		ManagedDataLayout layout = new ManagedDataLayout(dataRoot);

		layout.curriculumWorkbookDirectory("Chemistry", "2025");
		layout.curriculumSourceDirectory("Chemistry", "2025");
		layout.legacyImportDirectory("Chemistry", "2019");
		layout.examsDirectory("Chemistry");

		assertFalse(Files.exists(dataRoot));
	}

	@Test
	void normalisesTheConfiguredDataRootAndDirectoryComponents() {
		Path dataRoot = tempDir.resolve("old/../data");
		ManagedDataLayout layout = new ManagedDataLayout(dataRoot);

		assertEquals(tempDir.resolve("data").toAbsolutePath().normalize(), layout.dataRoot());
		assertEquals(tempDir.resolve("data/subjects/Chemistry").toAbsolutePath().normalize(),
				layout.subjectDirectory("  Chemistry  "));
		assertEquals(tempDir.resolve("data/subjects/Chemistry/curriculum/2025").toAbsolutePath().normalize(),
				layout.curriculumVersionDirectory("Chemistry", "  2025  "));
	}

	@Test
	void rejectsAbsoluteAndEscapingPersistedPaths() {
		ManagedDataLayout layout = new ManagedDataLayout(tempDir.resolve("data"));

		assertThrows(IllegalArgumentException.class, () -> layout.resolve("../outside.pdf"));
		assertThrows(IllegalArgumentException.class,
				() -> layout.resolve("subjects/Chemistry/../../../../outside.pdf"));
		assertThrows(IllegalArgumentException.class, () -> layout.resolve("C:/exam-data/outside.pdf"));
		assertThrows(IllegalArgumentException.class, () -> layout.resolve("C:\\exam-data\\outside.pdf"));
		assertThrows(IllegalArgumentException.class, () -> layout.resolve("\\\\server\\share\\outside.pdf"));
		assertThrows(IllegalArgumentException.class, () -> layout.resolve("/absolute/outside.pdf"));
		assertThrows(IllegalArgumentException.class, () -> layout.resolve("."));
	}

	@Test
	void rejectsInvalidDirectoryComponentsDeterministically() {
		ManagedDataLayout layout = new ManagedDataLayout(tempDir.resolve("data"));

		assertThrows(IllegalArgumentException.class, () -> layout.subjectDirectory(" "));
		assertThrows(IllegalArgumentException.class, () -> layout.subjectDirectory("."));
		assertThrows(IllegalArgumentException.class, () -> layout.subjectDirectory(".."));
		assertThrows(IllegalArgumentException.class, () -> layout.subjectDirectory("Science/Chemistry"));
		assertThrows(IllegalArgumentException.class, () -> layout.subjectDirectory("Science\\Chemistry"));
		assertThrows(IllegalArgumentException.class, () -> layout.subjectDirectory("Chemistry: Advanced"));
		assertThrows(IllegalArgumentException.class, () -> layout.subjectDirectory("NUL"));
		assertThrows(IllegalArgumentException.class, () -> layout.subjectDirectory("Chemistry."));
		assertThrows(IllegalArgumentException.class,
				() -> layout.curriculumVersionDirectory("Chemistry", "2025/Revision"));
	}

	@Test
	void rejectsInvalidExamDirectoryComponents() {
		ManagedDataLayout layout = new ManagedDataLayout(tempDir.resolve("data"));

		assertThrows(IllegalArgumentException.class, () -> layout.examProviderDirectory("Chemistry", "QCAA/External"));
		assertThrows(IllegalArgumentException.class, () -> layout.examYearDirectory("Chemistry", "QCAA", 0));
		assertThrows(IllegalArgumentException.class,
				() -> layout.examDirectory("Chemistry", "QCAA", 2024, "Paper: External"));
		assertThrows(IllegalArgumentException.class, () -> layout.examDirectory("Chemistry", "QCAA", 2024, ".."));
	}

	@Test
	void rejectsRelativePathConversionOutsideTheManagedDataRoot() {
		Path dataRoot = tempDir.resolve("data").toAbsolutePath().normalize();
		ManagedDataLayout layout = new ManagedDataLayout(dataRoot);

		assertThrows(IllegalArgumentException.class, () -> layout.relativePath(Path.of("subjects/Chemistry")));
		assertThrows(IllegalArgumentException.class, () -> layout.relativePath(dataRoot));
		assertThrows(IllegalArgumentException.class,
				() -> layout.relativePath(tempDir.resolve("outside.pdf").toAbsolutePath().normalize()));
	}

	@Test
	void resolvesDataRootRelativeManagedPaths() {
		Path dataRoot = tempDir.resolve("data").toAbsolutePath().normalize();
		ManagedDataLayout layout = new ManagedDataLayout(dataRoot);

		assertEquals(dataRoot.resolve("subjects/Chemistry/curriculum/2025/sources/syllabus.pdf"),
				layout.resolve("subjects/Chemistry/curriculum/2025/sources/syllabus.pdf"));
	}

	@Test
	void roundTripsManagedPathsUsingPortableSeparators() {
		ManagedDataLayout layout = new ManagedDataLayout(tempDir.resolve("data"));
		Path managedPath = layout.curriculumSourceDirectory("Chemistry", "2025").resolve("syllabus.pdf");

		String relativePath = layout.relativePath(managedPath);

		assertEquals("subjects/Chemistry/curriculum/2025/sources/syllabus.pdf", relativePath);
		assertEquals(managedPath, layout.resolve(relativePath));
	}
}
