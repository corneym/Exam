package au.edu.eq.questionbank.pdf;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfSessionTest {

	@TempDir
	Path tempDir;

	@Test
	void extractsTextFromDistinctOneBasedPages() throws Exception {
		Path path = tempDir.resolve("text-pages.pdf");
		try (PDDocument document = new PDDocument()) {
			addTextPage(document, "First syllabus page");
			addTextPage(document, "Second syllabus page");
			document.save(path.toFile());
		}
		try (PdfSession session = PdfSession.open(path)) {
			assertAll(() -> assertEquals("First syllabus page", session.extractPageText(1).trim()),
					() -> assertEquals("Second syllabus page", session.extractPageText(2).trim()));
		}
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
	void rejectsTextExtractionOutsideTheDocument() throws Exception {
		try (PdfSession session = PdfSession.open(createPdf(2))) {
			assertAll(() -> assertThrows(IllegalArgumentException.class, () -> session.extractPageText(0)),
					() -> assertThrows(IllegalArgumentException.class, () -> session.extractPageText(3)));
		}
	}

	@Test
	void rejectsZeroDpi() throws IOException, Exception {
		try (PdfSession session = PdfSession.open(createPdf(2))) {
			assertThrows(IllegalArgumentException.class, () -> session.renderPage(1, 0.0f));
		}
	}

	@Test
	void rendersCropBoxAndRotationAtMultipleResolutions() throws Exception {
		Path path = tempDir.resolve("rotated-crop.pdf");
		try (PDDocument document = new PDDocument()) {
			PDPage page = new PDPage(new PDRectangle(300, 400));
			page.setCropBox(new PDRectangle(20, 30, 72, 144));
			page.setRotation(90);
			document.addPage(page);
			document.save(path.toFile());
		}
		try (PdfSession session = PdfSession.open(path)) {
			BufferedImage normal = session.renderPage(1, 72);
			BufferedImage doubled = session.renderPage(1, 144);
			assertAll(() -> assertEquals(144, normal.getWidth()), () -> assertEquals(72, normal.getHeight()),
					() -> assertEquals(288, doubled.getWidth()), () -> assertEquals(144, doubled.getHeight()));
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

	@Test
	void selectsDistinctFirstAndLastPagesUsingOneBasedNumbers() throws Exception {
		Path path = tempDir.resolve("different-pages.pdf");
		try (PDDocument document = new PDDocument()) {
			document.addPage(new PDPage(new PDRectangle(72, 144)));
			document.addPage(new PDPage(new PDRectangle(216, 72)));
			document.save(path.toFile());
		}
		try (PdfSession session = PdfSession.open(path)) {
			assertEquals(72, session.renderPage(1, 72).getWidth());
			assertEquals(216, session.renderPage(2, 72).getWidth());
		}
	}

	private void addTextPage(PDDocument document, String text) throws IOException {
		PDPage page = new PDPage(new PDRectangle(300, 300));
		document.addPage(page);
		try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
			contentStream.beginText();
			contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
			contentStream.newLineAtOffset(40, 250);
			contentStream.showText(text);
			contentStream.endText();
		}
	}

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
}
