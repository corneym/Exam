package au.edu.eq.questionbank.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class PdfStoreTest {

	@Test
	void resolvesAnExamPathUnderTheConfiguredPdfRoot() {
		PdfStore store = new PdfStore();

		Path resolved = store.resolve("chemistry/QCAA/2024/exam.pdf");

		assertEquals(Path.of("D:/git/Exam/data/exams/chemistry/QCAA/2024/exam.pdf"), resolved);
	}

	@Test
	void preservesNestedRelativePathSegments() {
		PdfStore store = new PdfStore();
		Path relativePath = Path.of("chemistry", "QCAA", "2024", "paper.pdf");

		Path resolved = store.resolve(relativePath.toString());

		assertEquals(Path.of("D:/git/Exam/data/exams").resolve(relativePath), resolved);
	}
}
