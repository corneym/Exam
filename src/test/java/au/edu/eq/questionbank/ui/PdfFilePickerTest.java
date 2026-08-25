package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfFilePickerTest {

	@TempDir
	Path dataRoot;

	@Test
	void acceptsNormalizedPathsInsideConfiguredRoot() {
		PdfFilePicker picker = new PdfFilePicker(dataRoot);

		assertTrue(picker.isInsideDataRoot(dataRoot.resolve("chemistry").resolve("..").resolve("exam.pdf")));
	}

	@Test
	void rejectsSiblingWhoseNameOnlySharesTheRootPrefix() {
		PdfFilePicker picker = new PdfFilePicker(dataRoot);
		Path sibling = dataRoot.resolveSibling(dataRoot.getFileName() + "-other").resolve("exam.pdf");

		assertFalse(picker.isInsideDataRoot(sibling));
	}

	@Test
	void selectedPdfProducesRelativeSourceDocumentPath() {
		Path pdf = dataRoot.resolve("chemistry/QCAA/2024/exam.pdf").toAbsolutePath().normalize();
		SelectedPdf selectedPdf = new SelectedPdf(pdf.toFile(), pdf, dataRoot.toAbsolutePath().normalize());

		assertEquals(Path.of("chemistry", "QCAA", "2024", "exam.pdf").toString(), selectedPdf.relativePath());
	}
}
