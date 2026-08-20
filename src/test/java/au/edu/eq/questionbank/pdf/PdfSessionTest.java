package au.edu.eq.questionbank.pdf;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfSessionTest {

	@TempDir
	Path tempDir;

	private Path createPdf(int pageCount) throws Exception {
		Path path = tempDir.resolve("session.pdf");
		try (PDDocument document = new PDDocument()) {
			for (int index = 0; index < pageCount; index++) {
				document.addPage(new PDPage(new PDRectangle(72, 72)));
			}
			document.save(path.toFile());
		}
		return path;
	}

	@Test
	void rejectsANullPdfPath() {
		assertThrows(NullPointerException.class, () -> PdfSession.open(null));
	}

	@Test
	void rejectsInfiniteDpi() throws IOException, Exception {
		try (PdfSession session = PdfSession.open(createPdf(2))) {
			assertThrows(IllegalArgumentException.class, () -> session.renderPage(1, Float.POSITIVE_INFINITY));
		}
	}

	@Test
	void rejectsNanDpi() throws IOException, Exception {
		try (PdfSession session = PdfSession.open(createPdf(2))) {
			assertThrows(IllegalArgumentException.class, () -> session.renderPage(1, Float.NaN));
		}
	}

	@Test
	void rejectsPageNumbersOutsideTheDocument() throws Exception {
		try (PdfSession session = PdfSession.open(createPdf(2))) {
			assertAll(() -> assertThrows(IllegalArgumentException.class, () -> session.renderPage(0, 72)),
					() -> assertThrows(IllegalArgumentException.class, () -> session.renderPage(3, 72)));
		}
	}

	@Test
	void rejectsZeroDpi() throws IOException, Exception {
		try (PdfSession session = PdfSession.open(createPdf(2))) {
			assertThrows(IllegalArgumentException.class, () -> session.renderPage(1, 0.0f));
		}
	}

	@Test
	void reportsPageCountAndRendersOneBasedPages() throws Exception {
		try (PdfSession session = PdfSession.open(createPdf(2))) {
			BufferedImage page = session.renderPage(2, 72);

			assertAll(() -> assertEquals(2, session.getPageCount()), () -> assertEquals(72, page.getWidth()),
					() -> assertEquals(72, page.getHeight()));
		}
	}
}
