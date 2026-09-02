package au.edu.eq.questionbank.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfStoreTest {

	@TempDir
	Path tempDir;

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
	void rejectsANullRelativePath() {
		PdfStore store = new PdfStore(tempDir);

		assertThrows(NullPointerException.class, () -> store.resolve(null));
	}

	@Test
	void rejectsANullRoot() {
		assertThrows(NullPointerException.class, () -> new PdfStore(null));
	}

	@Test
	void rejectsAPathThatEscapesTheConfiguredRoot() {
		PdfStore store = new PdfStore(tempDir);

		assertThrows(IllegalArgumentException.class, () -> store.resolve("../outside.pdf"));
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
	}

	@Test
	void resolvesAnExamPathUnderTheConfiguredPdfRoot() {
		PdfStore store = new PdfStore(tempDir);

		Path resolved = store.resolve("chemistry/QCAA/2024/exam.pdf");

		assertEquals(tempDir.resolve("chemistry/QCAA/2024/exam.pdf"), resolved);
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
}
