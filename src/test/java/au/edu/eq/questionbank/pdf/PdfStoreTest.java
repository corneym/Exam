package au.edu.eq.questionbank.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfStoreTest {

	@TempDir
	Path tempDir;

	@Test
	void resolvesAnExamPathUnderTheConfiguredPdfRoot() {
		PdfStore store = new PdfStore(tempDir);

		Path resolved = store.resolve("chemistry/QCAA/2024/exam.pdf");

		assertEquals(tempDir.resolve("chemistry/QCAA/2024/exam.pdf"), resolved);
	}

	@Test
	void preservesNestedRelativePathSegments() {
		PdfStore store = new PdfStore(tempDir);
		Path relativePath = Path.of("chemistry", "QCAA", "2024", "paper.pdf");

		Path resolved = store.resolve(relativePath.toString());

		assertEquals(tempDir.resolve(relativePath), resolved);
	}

	@Test
	void normalizesAContainedRelativePath() {
		PdfStore store = new PdfStore(tempDir);

		Path resolved = store.resolve("chemistry/../physics/exam.pdf");

		assertEquals(tempDir.resolve("physics/exam.pdf"), resolved);
	}

	@Test
	void rejectsAPathThatEscapesTheConfiguredRoot() {
		PdfStore store = new PdfStore(tempDir);

		assertThrows(IllegalArgumentException.class, () -> store.resolve("../outside.pdf"));
	}

	@Test
	void rejectsAnAbsolutePath() {
		PdfStore store = new PdfStore(tempDir);

		assertThrows(IllegalArgumentException.class, () -> store.resolve(tempDir.resolve("exam.pdf").toString()));
	}

	@Test
	void rejectsABlankPath() {
		PdfStore store = new PdfStore(tempDir);

		assertThrows(IllegalArgumentException.class, () -> store.resolve("  "));
	}

	@Test
	void rejectsANullRoot() {
		assertThrows(NullPointerException.class, () -> new PdfStore(null));
	}

	@Test
	void rejectsANullRelativePath() {
		PdfStore store = new PdfStore(tempDir);

		assertThrows(NullPointerException.class, () -> store.resolve(null));
	}
}
