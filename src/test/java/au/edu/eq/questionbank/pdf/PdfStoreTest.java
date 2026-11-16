package au.edu.eq.questionbank.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.ManagedAssetPublicationLock;

class PdfStoreTest {

	@TempDir
	Path tempDir;

	@Test
	void identicalOrdinaryIntakeCannotAdoptUnresolvedPackageFiles() throws Exception {
		Path source = tempDir.resolve("paper.pdf");
		Files.writeString(source, "Identical PDF contents");
		Path root = tempDir.resolve("data");
		PdfStore store = new PdfStore(new ManagedDataLayout(root));
		Path destination = store.managedDestination(source, "Biology", "QCAA", 2025, "External Assessment");
		Files.createDirectories(destination.getParent());
		Files.copy(source, destination);
		for (String name : List.of(".subject-import-staging", ".assignment-install-staging")) {
			Path pending = Files.createDirectories(root.resolve(name).resolve(UUID.randomUUID().toString()));
			assertThrows(java.io.IOException.class,
					() -> store.importExamPdf(source, "Biology", "QCAA", 2025, "External Assessment"));
			assertThrows(java.io.IOException.class,
					() -> store.importExamPdf(destination, "Biology", "QCAA", 2025, "External Assessment"));
			assertEquals(-1, Files.mismatch(source, destination));
			Files.delete(pending);
		}
		try (var lock = ManagedAssetPublicationLock.acquire(root)) {
			assertThrows(java.io.IOException.class,
					() -> store.importExamPdf(source, "Biology", "QCAA", 2025, "External Assessment"));
		}
		assertEquals(destination, store.importExamPdf(source, "Biology", "QCAA", 2025, "External Assessment"));
	}

	@Test
	void importsExamPdfIntoSubjectFirstAssessmentHierarchy() throws Exception {
		Path source = tempDir.resolve("source/paper1.pdf");
		Files.createDirectories(source.getParent());
		Files.writeString(source, "PDF contents");
		Path dataRoot = tempDir.resolve("data");
		PdfStore store = new PdfStore(new ManagedDataLayout(dataRoot));
		Path stored = store.importExamPdf(source, "Chemistry", "QCAA", 2024, "External Assessment");
		assertEquals(dataRoot.resolve("subjects/Chemistry/exams/QCAA/2024/External Assessment/paper1.pdf"), stored);
		assertEquals("subjects/Chemistry/exams/QCAA/2024/External Assessment/paper1.pdf", store.relativePath(stored));
		assertTrue(Files.isRegularFile(stored));
		assertEquals("PDF contents", Files.readString(stored));
	}

	@Test
	void importsExamPdfIntoSubjectProviderYearHierarchy() throws Exception {
		Path source = tempDir.resolve("source").resolve("paper1.pdf");
		Files.createDirectories(source.getParent());
		Files.writeString(source, "PDF contents");
		Path pdfRoot = tempDir.resolve("pdf");
		PdfStore store = new PdfStore(pdfRoot);
		Path stored = store.importExamPdf(source, "Chemistry", "QCAA", 2020);
		assertEquals(pdfRoot.resolve("Chemistry/QCAA/2020/paper1.pdf"), stored);
		assertTrue(Files.exists(stored));
		assertEquals("PDF contents", Files.readString(stored));
	}

	@Test
	void normalizesAContainedRelativePath() {
		PdfStore store = new PdfStore(tempDir);
		Path resolved = store.resolve("chemistry/../physics/exam.pdf");
		assertEquals(tempDir.resolve("physics/exam.pdf"), resolved);
	}

	@Test
	void preservesNestedRelativePathSegments() {
		PdfStore store = new PdfStore(tempDir);
		Path relativePath = Path.of("chemistry", "QCAA", "2024", "paper.pdf");
		Path resolved = store.resolve(relativePath.toString());
		assertEquals(tempDir.resolve(relativePath), resolved);
	}

	@Test
	void rejectsABlankPath() {
		PdfStore store = new PdfStore(tempDir);
		assertThrows(IllegalArgumentException.class, () -> store.resolve("  "));
	}

	@Test
	void rejectsAnAbsolutePath() {
		PdfStore store = new PdfStore(tempDir);
		assertThrows(IllegalArgumentException.class, () -> store.resolve(tempDir.resolve("exam.pdf").toString()));
	}

	@Test
	void rejectsANullLegacyRoot() {
		assertThrows(NullPointerException.class, () -> new PdfStore((Path) null));
	}

	@Test
	void rejectsANullRelativePath() {
		PdfStore store = new PdfStore(tempDir);
		assertThrows(NullPointerException.class, () -> store.resolve(null));
	}

	@Test
	void rejectsAPathThatEscapesTheConfiguredRoot() {
		PdfStore store = new PdfStore(tempDir);
		assertThrows(IllegalArgumentException.class, () -> store.resolve("../outside.pdf"));
	}

	@Test
	void rejectsDifferentPdfAtSubjectFirstDestination() throws Exception {
		Path source = tempDir.resolve("source/paper1.pdf");
		Files.createDirectories(source.getParent());
		Files.writeString(source, "new contents");
		Path dataRoot = tempDir.resolve("data");
		Path existing = dataRoot.resolve("subjects/Chemistry/exams/QCAA/2024/External Assessment/paper1.pdf");
		Files.createDirectories(existing.getParent());
		Files.writeString(existing, "different contents");
		PdfStore store = new PdfStore(new ManagedDataLayout(dataRoot));
		assertThrows(FileAlreadyExistsException.class,
				() -> store.importExamPdf(source, "Chemistry", "QCAA", 2024, "External Assessment"));
		assertEquals("different contents", Files.readString(existing));
	}

	@Test
	void rejectsDifferentPdfWithSameFilename() throws Exception {
		Path source = tempDir.resolve("source").resolve("paper1.pdf");
		Files.createDirectories(source.getParent());
		Files.writeString(source, "new contents");
		Path pdfRoot = tempDir.resolve("pdf");
		Path existing = pdfRoot.resolve("Chemistry/QCAA/2020/paper1.pdf");
		Files.createDirectories(existing.getParent());
		Files.writeString(existing, "different contents");
		PdfStore store = new PdfStore(pdfRoot);
		assertThrows(FileAlreadyExistsException.class, () -> store.importExamPdf(source, "Chemistry", "QCAA", 2020));
		assertTrue(Files.notExists(existing.getParent().resolve("paper1 (2).pdf")));
	}

	@Test
	void rejectsLegacyImportSignatureForSubjectFirstStore() {
		PdfStore store = new PdfStore(new ManagedDataLayout(tempDir.resolve("data")));
		Path source = tempDir.resolve("paper.pdf");
		assertThrows(IllegalStateException.class, () -> store.importExamPdf(source, "Chemistry", "QCAA", 2024));
	}

	@Test
	void rejectsMissingOrNonFilePdfSources() throws Exception {
		PdfStore store = new PdfStore(tempDir.resolve("pdf"));
		Path missing = tempDir.resolve("missing.pdf");
		Path directory = tempDir.resolve("directory.pdf");
		Files.createDirectories(directory);
		assertThrows(java.io.IOException.class, () -> store.importExamPdf(missing, "Chemistry", "QCAA", 2020));
		assertThrows(java.io.IOException.class, () -> store.importExamPdf(directory, "Chemistry", "QCAA", 2020));
	}

	@Test
	void resolvesAnExamPathUnderTheConfiguredPdfRoot() {
		PdfStore store = new PdfStore(tempDir);
		Path resolved = store.resolve("chemistry/QCAA/2024/exam.pdf");
		assertEquals(tempDir.resolve("chemistry/QCAA/2024/exam.pdf"), resolved);
	}

	@Test
	void resolvesSubjectFirstPersistedPathAgainstDataRoot() {
		Path dataRoot = tempDir.resolve("data");
		PdfStore store = new PdfStore(new ManagedDataLayout(dataRoot));
		Path resolved = store.resolve("subjects/Chemistry/exams/QCAA/2024/External Assessment/paper1.pdf");
		assertEquals(dataRoot.resolve("subjects/Chemistry/exams/QCAA/2024/External Assessment/paper1.pdf"), resolved);
	}

	@Test
	void reusesIdenticalPdfAlreadyInExamDirectory() throws Exception {
		Path source = tempDir.resolve("source").resolve("paper1.pdf");
		Files.createDirectories(source.getParent());
		Files.writeString(source, "same contents");
		Path pdfRoot = tempDir.resolve("pdf");
		Path existing = pdfRoot.resolve("Chemistry/QCAA/2020/paper1.pdf");
		Files.createDirectories(existing.getParent());
		Files.writeString(existing, "same contents");
		PdfStore store = new PdfStore(pdfRoot);
		Path stored = store.importExamPdf(source, "Chemistry", "QCAA", 2020);
		assertEquals(existing, stored);
		assertTrue(Files.notExists(existing.getParent().resolve("paper1 (2).pdf")));
	}

	@Test
	void transitionalStoreDoesNotMisclassifyLegacySubjectNamedSubjects() {
		Path dataRoot = tempDir.resolve("data").toAbsolutePath().normalize();
		Path legacyPdfRoot = dataRoot.resolve("pdf");
		PdfStore store = new PdfStore(new ManagedDataLayout(dataRoot), legacyPdfRoot);

		// The old layout may theoretically contain a Subject named "subjects".
		// Absence of the canonical third-component "exams" marker keeps it legacy.
		assertEquals(legacyPdfRoot.resolve("subjects/QCAA/2024/paper1.pdf"),
				store.resolve("subjects/QCAA/2024/paper1.pdf"));
	}

	@Test
	void transitionalStorePersistsOnlyDataRootRelativeNewPaths() {
		Path dataRoot = tempDir.resolve("data").toAbsolutePath().normalize();

		// Model the real ApplicationConfig relationship: the legacy PDF root is a
		// child of dataRoot rather than an unrelated directory.
		Path legacyPdfRoot = dataRoot.resolve("pdf");
		PdfStore store = new PdfStore(new ManagedDataLayout(dataRoot), legacyPdfRoot);
		Path managedPath = dataRoot.resolve("subjects/Chemistry/exams/QCAA/2024/External Assessment/paper1.pdf");
		assertEquals("subjects/Chemistry/exams/QCAA/2024/External Assessment/paper1.pdf",
				store.relativePath(managedPath));

		// Compatibility reads do not grant permission to manufacture new persisted
		// pdf/... paths from the old hierarchy.
		assertThrows(IllegalArgumentException.class,
				() -> store.relativePath(legacyPdfRoot.resolve("Chemistry/QCAA/2024/paper1.pdf")));
	}

	@Test
	void transitionalStoreResolvesLegacyAndSubjectFirstPaths() {
		Path dataRoot = tempDir.resolve("data").toAbsolutePath().normalize();
		Path legacyPdfRoot = dataRoot.resolve("pdf");
		PdfStore store = new PdfStore(new ManagedDataLayout(dataRoot), legacyPdfRoot);
		assertEquals(dataRoot.resolve("subjects/Chemistry/exams/QCAA/2024/External Assessment/paper1.pdf"),
				store.resolve("subjects/Chemistry/exams/QCAA/2024/External Assessment/paper1.pdf"));
		assertEquals(legacyPdfRoot.resolve("Chemistry/QCAA/2024/paper1.pdf"),
				store.resolve("Chemistry/QCAA/2024/paper1.pdf"));
	}
}
