package au.edu.eq.questionbank.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class PrintableExamPageTest {

	@TempDir
	Path tempDir;

	@Test
	void createsRunningHeadersAndResponseOutlines() throws Exception {
		Path outputPath = tempDir.resolve("page-elements.pdf");
		try (PDDocument document = new PDDocument()) {
			PrintableExamFonts fonts = createTestFonts();
			PDPage first = PrintableExamPage.appendMultipleChoicePage(document, fonts);
			PrintableExamPage.appendMultipleChoicePage(document, fonts);
			PrintableExamPage.appendResponsePage(document, fonts);
			try (PDPageContentStream stream = new PDPageContentStream(document, first,
					PDPageContentStream.AppendMode.APPEND, true, true)) {
				PrintableExamPage.writeText(stream, first, fonts.bold(), 16, 42.5f, 82, "Section 1");
				PrintableExamPage.drawHorizontalRule(stream, first, 42.5f, 552.8f, 147.3f);
			}
			document.save(outputPath.toFile());
		}
		try (PDDocument document = Loader.loadPDF(outputPath.toFile())) {
			assertEquals(3, document.getNumberOfPages());
			for (PDPage page : document.getPages()) {
				assertEquals(PDRectangle.A4.getWidth(), page.getMediaBox().getWidth(), 0.01f);
				assertEquals(PDRectangle.A4.getHeight(), page.getMediaBox().getHeight(), 0.01f);
			}
			String firstPage = extractPageText(document, 1);
			String secondPage = extractPageText(document, 2);
			String thirdPage = extractPageText(document, 3);
			assertTrue(firstPage.contains("THIS PAGE WILL NOT BE MARKED"));
			assertTrue(firstPage.contains("Section 1"));
			assertTrue(secondPage.contains("THIS PAGE WILL NOT BE MARKED"));
			assertFalse(secondPage.contains("Section 1"));
			assertTrue(thirdPage.contains("Do not write outside this box."));
			assertFalse(thirdPage.contains("THIS PAGE WILL NOT BE MARKED"));

			// Render at two pixels per PDF point and confirm
			// that the response outline is actually visible.
			PDFRenderer renderer = new PDFRenderer(document);
			BufferedImage mcqImage = renderer.renderImageWithDPI(1, 144);
			BufferedImage responseImage = renderer.renderImageWithDPI(2, 144);
			assertFalse(hasVisibleBorder(mcqImage));
			assertTrue(hasVisibleBorder(responseImage));
		}
	}

	@Test
	void rejectsMissingRequiredFonts() throws Exception {
		try (PDDocument document = new PDDocument()) {
			IOException exception = assertThrows(IOException.class,
					() -> PrintableExamFonts.loadStudent(document, tempDir));
			assertTrue(exception.getMessage().contains("times.ttf"));
		}
	}

	@Test
	@EnabledOnOs(OS.WINDOWS)
	void usesEmbeddedWindowsExaminationFonts() throws Exception {
		Path directory = Path.of("target", "pdf-feasibility").toAbsolutePath();
		Files.createDirectories(directory);
		Path outputPath = directory.resolve("examination-fonts-and-pages.pdf");
		try (PDDocument document = new PDDocument()) {
			Path fontDirectory = PrintableExamFonts.windowsFontDirectory();
			PrintableExamFonts student = PrintableExamFonts.loadStudent(document, fontDirectory);
			PrintableExamFonts guide = PrintableExamFonts.loadMarkingGuide(document, fontDirectory);
			assertTrue(student.regular().getName().contains("TimesNewRoman"));
			assertTrue(student.bold().getName().contains("Bold"));
			assertTrue(guide.regular().getName().contains("Arial"));
			assertTrue(guide.bold().getName().contains("Bold"));
			for (PDFont font : List.of(student.regular(), student.bold(), student.italic(), student.boldItalic(),
					guide.regular(), guide.bold(), guide.italic(), guide.boldItalic())) {
				assertTrue(font.isEmbedded());
			}
			PDPage mcqPage = PrintableExamPage.appendMultipleChoicePage(document, student);
			try (PDPageContentStream stream = new PDPageContentStream(document, mcqPage,
					PDPageContentStream.AppendMode.APPEND, true, true)) {
				PrintableExamPage.writeText(stream, mcqPage, student.bold(), 16, 42.5f, 82, "Section 1");
				PrintableExamPage.writeText(stream, mcqPage, student.bold(), 13, 42.5f, 108, "Instruction");
				PrintableExamPage.writeText(stream, mcqPage, student.regular(), 11.5f, 42.5f, 130,
						"Respond to these questions in the " + "question and response book.");
				PrintableExamPage.drawHorizontalRule(stream, mcqPage, 42.5f, 552.8f, 147.3f);
				PrintableExamPage.writeText(stream, mcqPage, student.bold(), 13, 42.5f, 188, "QUESTION 1");
				PrintableExamPage.writeText(stream, mcqPage, student.regular(), 12, 42.5f, 212,
						"Calculate the equilibrium constant.");
				PrintableExamPage.writeText(stream, mcqPage, student.italic(), 12, 42.5f, 236, "Equilibrium constant");
				PrintableExamPage.writeText(stream, mcqPage, student.boldItalic(), 12, 42.5f, 260, "Kc");
			}
			PDPage responsePage = PrintableExamPage.appendResponsePage(document, student);
			try (PDPageContentStream stream = new PDPageContentStream(document, responsePage,
					PDPageContentStream.AppendMode.APPEND, true, true)) {
				PrintableExamPage.writeText(stream, responsePage, student.bold(), 13, 52, 90, "QUESTION 11 (2 marks)");
				PrintableExamPage.writeText(stream, responsePage, student.regular(), 12, 52, 116,
						"Explain the effect of temperature.");
			}
			PDPage guidePage = new PDPage(PDRectangle.A4);
			document.addPage(guidePage);
			try (PDPageContentStream stream = new PDPageContentStream(document, guidePage)) {
				PrintableExamPage.writeText(stream, guidePage, guide.bold(), 28, 70.9f, 180, "Chemistry marking guide");
				PrintableExamPage.writeText(stream, guidePage, guide.bold(), 28, 70.9f, 215, "and response");
				PrintableExamPage.writeText(stream, guidePage, guide.regular(), 16, 70.9f, 245, "Mock assessment 2026");
				PrintableExamPage.writeText(stream, guidePage, guide.italic(), 11, 70.9f, 280, "Sample response");
				PrintableExamPage.writeText(stream, guidePage, guide.boldItalic(), 11, 70.9f, 300, "Marking criteria");
			}
			document.save(outputPath.toFile());
		}
		try (PDDocument document = Loader.loadPDF(outputPath.toFile())) {
			assertEquals(3, document.getNumberOfPages());
			assertTrue(extractPageText(document, 1).contains("QUESTION 1"));
			assertTrue(extractPageText(document, 2).contains("QUESTION 11 (2 marks)"));
			assertTrue(extractPageText(document, 3).contains("Mock assessment 2026"));
		}
		System.out.println("Examination font and page samples: " + outputPath);
	}

	private PrintableExamFonts createTestFonts() {

		// Standard PDF fonts keep the geometry test independent
		// of proprietary fonts installed on the CI host.
		return new PrintableExamFonts(new PDType1Font(Standard14Fonts.FontName.TIMES_ROMAN),
				new PDType1Font(Standard14Fonts.FontName.TIMES_BOLD),
				new PDType1Font(Standard14Fonts.FontName.TIMES_ITALIC),
				new PDType1Font(Standard14Fonts.FontName.TIMES_BOLD_ITALIC));
	}

	private String extractPageText(PDDocument document, int pageNumber) throws IOException {
		PDFTextStripper stripper = new PDFTextStripper();
		stripper.setStartPage(pageNumber);
		stripper.setEndPage(pageNumber);
		return stripper.getText(document);
	}

	private boolean hasVisibleBorder(BufferedImage image) {

		// The left edge is at 35.5 PDF points, corresponding
		// to approximately pixel 71 at 144 DPI. Check a narrow
		// neighbourhood to accommodate rendering antialiasing.
		for (int x = 69; x <= 73; x++) {
			int blue = image.getRGB(x, 200) & 0xff;
			if (blue < 200) {
				return true;
			}
		}
		return false;
	}
}
