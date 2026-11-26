package au.edu.eq.questionbank.pdf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrintableRegionFeasibilityTest {

	@TempDir
	Path tempDir;

	@Test
	void locatesPartLetterUsingExtractedTextPositions() throws Exception {
		Path path = tempDir.resolve("source.pdf");
		createSourcePdf(path);
		try (PDDocument document = Loader.loadPDF(path.toFile())) {
			List<TextPosition> positions = new ArrayList<>();
			PDFTextStripper stripper = new PDFTextStripper() {

				@Override
				protected void processTextPosition(TextPosition text) {
					positions.add(text);
					super.processTextPosition(text);
				}
			};
			stripper.setStartPage(1);
			stripper.setEndPage(1);
			stripper.getText(document);

			// Convert the known PDF baseline into a top-relative
			// text extraction coordinate.
			float expectedY = document.getPage(0).getCropBox().getHeight() - 640;
			StringBuilder label = new StringBuilder();
			for (TextPosition position : positions) {
				float x = position.getXDirAdj();
				float y = position.getYDirAdj();
				if (x >= 48 && x <= 72 && Math.abs(y - expectedY) <= 5) {
					label.append(position.getUnicode());
				}
			}
			assertEquals("(b)", label.toString());
		}
	}

	@Test
	void reproducesAndRenumbersTwoQuestionRegions() throws Exception {
		Path outputDirectory = Path.of("target", "pdf-feasibility").toAbsolutePath();
		Files.createDirectories(outputDirectory);
		Path sourcePath = outputDirectory.resolve("source.pdf");
		Path outputPath = outputDirectory.resolve("assessment.pdf");
		createSourcePdf(sourcePath);
		byte[] originalBytes = Files.readAllBytes(sourcePath);
		try (PDDocument source = Loader.loadPDF(sourcePath.toFile()); PDDocument output = new PDDocument()) {
			PDPage page = new PDPage(PDRectangle.A4);
			output.addPage(page);
			try (PDPageContentStream stream = new PDPageContentStream(output, page)) {
				PrintablePdfRegionWriter.appendRegion(source, 1, output, stream, new PDRectangle(40, 560, 480, 100), 50,
						650, "(b)", "(a)");
				PrintablePdfRegionWriter.appendRegion(source, 1, output, stream, new PDRectangle(40, 420, 480, 100), 50,
						510, "(c)", "(b)");
				PrintablePdfRegionWriter.appendRegion(source, 1, output, stream, new PDRectangle(40, 420, 480, 100), 50,
						370, "(c)", "");
			}
			output.save(outputPath.toFile());
		}
		System.out.println("Source PDF: " + sourcePath);
		System.out.println("Assessment PDF: " + outputPath);

		// Generation must never modify the source PDF.
		assertArrayEquals(originalBytes, Files.readAllBytes(sourcePath));
		try (PDDocument output = Loader.loadPDF(outputPath.toFile()); PDDocument expected = new PDDocument()) {
			assertEquals(1, output.getNumberOfPages());
			PDPage page = output.getPage(0);
			assertEquals(PDRectangle.A4.getWidth(), page.getMediaBox().getWidth(), 0.01f);
			assertEquals(PDRectangle.A4.getHeight(), page.getMediaBox().getHeight(), 0.01f);
			boolean containsForm = false;
			for (COSName name : page.getResources().getXObjectNames()) {
				PDXObject object = page.getResources().getXObject(name);
				assertFalse(object instanceof PDImageXObject);
				if (object instanceof PDFormXObject) {
					containsForm = true;
				}
			}
			assertTrue(containsForm);

			// Independently construct the expected page, including
			// the standalone question without a part identifier.
			PDPage expectedPage = new PDPage(PDRectangle.A4);
			expected.addPage(expectedPage);
			try (PDPageContentStream stream = new PDPageContentStream(expected, expectedPage)) {
				writeText(stream, 60, 730, "(a)");
				writeText(stream, 90, 730, "Calculate the equilibrium constant.");
				writeAnswerLines(stream, 90, 700);
				writeText(stream, 60, 590, "(b)");
				writeText(stream, 90, 590, "Explain the effect of temperature.");
				writeAnswerLines(stream, 90, 560);
				writeText(stream, 90, 450, "Explain the effect of temperature.");
				writeAnswerLines(stream, 90, 420);
			}
			BufferedImage expectedImage = new PDFRenderer(expected).renderImageWithDPI(0, 72);
			BufferedImage outputImage = new PDFRenderer(output).renderImageWithDPI(0, 72);
			assertEquals(expectedImage.getWidth(), outputImage.getWidth());
			assertEquals(expectedImage.getHeight(), outputImage.getHeight());
			int differences = countDifferentPixels(expectedImage, 0, 0, outputImage, 0, 0, expectedImage.getWidth(),
					expectedImage.getHeight());
			assertEquals(0, differences, "Generated assessment differs from expected appearance");
		}
	}

	private int countDifferentPixels(BufferedImage source, int sourceX, int sourceY, BufferedImage output, int outputX,
			int outputY, int width, int height) {
		int sourceTop = source.getHeight() - sourceY - height;
		int outputTop = output.getHeight() - outputY - height;
		int differences = 0;
		for (int row = 0; row < height; row++) {
			for (int column = 0; column < width; column++) {
				int original = source.getRGB(sourceX + column, sourceTop + row);
				int generated = output.getRGB(outputX + column, outputTop + row);
				if (original != generated) {
					differences++;
				}
			}
		}
		return differences;
	}

	private void createSourcePdf(Path path) throws IOException {
		try (PDDocument document = new PDDocument()) {
			PDPage page = new PDPage(new PDRectangle(600, 800));
			document.addPage(page);
			try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
				writeText(stream, 50, 680, "UNRELATED CONTENT ABOVE QUESTION");
				writeText(stream, 50, 640, "(b)");
				writeText(stream, 80, 640, "Calculate the equilibrium constant.");
				writeAnswerLines(stream, 80, 610);
				writeText(stream, 50, 500, "(c)");
				writeText(stream, 80, 500, "Explain the effect of temperature.");
				writeAnswerLines(stream, 80, 470);
				writeText(stream, 50, 390, "UNRELATED CONTENT BELOW QUESTION");
			}
			document.save(path.toFile());
		}
	}

	private void writeAnswerLines(PDPageContentStream stream, float x, float y) throws IOException {
		stream.setLineWidth(0.5f);
		for (int index = 0; index < 3; index++) {
			float lineY = y - index * 15;
			stream.moveTo(x, lineY);
			stream.lineTo(x + 400, lineY);
		}
		stream.stroke();
	}

	private void writeText(PDPageContentStream stream, float x, float y, String text) throws IOException {
		stream.beginText();
		stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
		stream.newLineAtOffset(x, y);
		stream.showText(text);
		stream.endText();
	}
}
