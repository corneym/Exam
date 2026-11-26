package au.edu.eq.questionbank.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class PrintableExamCoverTest {

	@TempDir
	Path tempDir;

	@Test
	void generatesCoversWithDynamicAssessmentMetadata() throws Exception {
		Path output = tempDir.resolve("covers.pdf");
		try (PDDocument document = new PDDocument()) {
			writeSamplePages(document, createStudentTestFonts(), createGuideTestFonts());
			document.save(output.toFile());
		}
		try (PDDocument document = Loader.loadPDF(output.toFile())) {
			assertEquals(6, document.getNumberOfPages());
			for (PDPage page : document.getPages()) {
				assertEquals(PDRectangle.A4.getWidth(), page.getMediaBox().getWidth(), 0.01f);
				assertEquals(PDRectangle.A4.getHeight(), page.getMediaBox().getHeight(), 0.01f);
			}
			String mcq = extractPageText(document, 1);
			assertTrue(mcq.contains("Mock assessment 2027"));
			assertTrue(mcq.contains("Chemistry"));
			assertTrue(mcq.contains("Paper 1"));
			assertTrue(mcq.contains("Multiple choice question book"));
			assertTrue(mcq.contains("Work in this book will not be marked."));
			String paperOne = extractPageText(document, 2);
			assertTrue(paperOne.contains("Mock assessment 2027"));
			assertTrue(paperOne.contains("Question and response book"));
			assertTrue(paperOne.contains("Paper 1"));
			assertTrue(paperOne.contains("LUI"));
			assertTrue(paperOne.contains("School code"));
			assertTrue(paperOne.contains("School name"));
			assertTrue(paperOne.contains("Given name/s"));
			assertTrue(paperOne.contains("Family name"));
			assertTrue(paperOne.contains("barcode ID label here"));
			assertTrue(paperOne.contains("books used"));
			assertTrue(paperOne.contains("Perusal time — 5 minutes"));
			assertTrue(paperOne.contains("Working time — 60 minutes"));
			assertTrue(paperOne.contains("General instructions"));
			assertTrue(paperOne.contains("Section 1 (10 marks)"));
			assertTrue(paperOne.contains("10 multiple choice questions"));
			assertTrue(paperOne.contains("Section 2 (24 marks)"));
			assertTrue(paperOne.contains("6 short response questions"));
			String paperTwo = extractPageText(document, 4);
			assertTrue(paperTwo.contains("Mock assessment 2027"));
			assertTrue(paperTwo.contains("Paper 2"));
			assertTrue(paperTwo.contains("Perusal time — 10 minutes"));
			assertTrue(paperTwo.contains("Working time — 75 minutes"));
			assertTrue(paperTwo.contains("Section 1 (40 marks)"));
			assertTrue(paperTwo.contains("8 short response questions"));
			assertTrue(paperTwo.contains("Write using black or blue pen."));
			assertFalse(paperTwo.contains("Section 2"));
			assertFalse(paperTwo.contains("multiple choice questions"));
			String guide = extractPageText(document, 6);
			assertTrue(guide.contains("Chemistry marking guide"));
			assertTrue(guide.contains("and response"));
			assertTrue(guide.contains("Mock assessment 2027"));
			assertFalse(guide.contains("Assessment objectives"));
			assertFalse(guide.contains("Purpose"));
			assertFalse(guide.contains("Mark allocation"));
			for (int pageNumber = 1; pageNumber <= 6; pageNumber++) {
				String text = extractPageText(document, pageNumber);
				assertFalse(text.contains("External assessment"));
				assertFalse(text.contains("Queensland Curriculum & Assessment Authority"));
			}
			verifyHatchedPage(document, 3);
			verifyHatchedPage(document, 5);
		}
	}

	@Test
	void rejectsMultipleChoiceQuestionsInPaperTwo() throws Exception {
		try (PDDocument document = new PDDocument()) {
			assertThrows(IllegalArgumentException.class, () -> PrintableExamCover.appendResponseCover(document,
					createStudentTestFonts(), "Chemistry", 2027, 2, 10, 90, 5, 8, 40));
			assertEquals(0, document.getNumberOfPages());
		}
	}

	@Test
	@EnabledOnOs(OS.WINDOWS)
	void writesAuthenticFontCoverSamples() throws Exception {
		Path directory = Path.of("target", "pdf-feasibility").toAbsolutePath();
		Files.createDirectories(directory);
		Path output = directory.resolve("examination-covers.pdf");
		try (PDDocument document = new PDDocument()) {
			Path fontDirectory = PrintableExamFonts.windowsFontDirectory();
			PrintableExamFonts student = PrintableExamFonts.loadStudent(document, fontDirectory);
			PrintableExamFonts guide = PrintableExamFonts.loadMarkingGuide(document, fontDirectory);
			writeSamplePages(document, student, guide);
			document.save(output.toFile());
		}
		try (PDDocument document = Loader.loadPDF(output.toFile())) {
			assertEquals(6, document.getNumberOfPages());
			assertTrue(extractPageText(document, 1).contains("Mock assessment 2027"));
			assertTrue(extractPageText(document, 6).contains("Chemistry marking guide"));
		}
		System.out.println("Examination covers: " + output);
	}

	private PrintableExamFonts createGuideTestFonts() {
		return new PrintableExamFonts(new PDType1Font(Standard14Fonts.FontName.HELVETICA),
				new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD),
				new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE),
				new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD_OBLIQUE));
	}

	private PrintableExamFonts createStudentTestFonts() {
		return new PrintableExamFonts(new PDType1Font(Standard14Fonts.FontName.TIMES_ROMAN),
				new PDType1Font(Standard14Fonts.FontName.TIMES_BOLD),
				new PDType1Font(Standard14Fonts.FontName.TIMES_ITALIC),
				new PDType1Font(Standard14Fonts.FontName.TIMES_BOLD_ITALIC));
	}

	private String extractPageText(PDDocument document, int pageNumber) throws IOException {
		PDFTextStripper stripper = new PDFTextStripper();
		stripper.setStartPage(pageNumber);
		stripper.setEndPage(pageNumber);
		return stripper.getText(document).replaceAll("\\s+", " ").trim();
	}

	private void verifyHatchedPage(PDDocument document, int pageNumber) throws IOException {
		String text = extractPageText(document, pageNumber);
		assertTrue(text.contains("DO NOT WRITE ON THIS PAGE"));
		assertTrue(text.contains("THIS PAGE WILL NOT BE MARKED"));
		BufferedImage image = new PDFRenderer(document).renderImageWithDPI(pageNumber - 1, 144);
		int shadedPixels = 0;

		// Inspect an area well below the warning text to
		// establish that the hatching has actually been rendered.
		for (int y = 500; y < 600; y++) {
			for (int x = 200; x < 300; x++) {
				int blue = image.getRGB(x, y) & 0xff;
				if (blue < 250) {
					shadedPixels++;
				}
			}
		}
		assertTrue(shadedPixels > 100, "Expected visible diagonal hatching");
	}

	private void writeSamplePages(PDDocument document, PrintableExamFonts student, PrintableExamFonts guide)
			throws IOException {
		PrintableExamCover.appendMultipleChoiceCover(document, student, "Chemistry", 2027);
		PrintableExamCover.appendResponseCover(document, student, "Chemistry", 2027, 1, 5, 60, 10, 6, 24);
		PrintableExamCover.appendHatchedPage(document, student);
		PrintableExamCover.appendResponseCover(document, student, "Chemistry", 2027, 2, 10, 75, 0, 8, 40);
		PrintableExamCover.appendHatchedPage(document, student);
		PrintableExamCover.appendMarkingGuideCover(document, guide, "Chemistry", 2027);
	}
}
