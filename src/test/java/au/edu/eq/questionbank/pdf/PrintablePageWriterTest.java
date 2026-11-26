package au.edu.eq.questionbank.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class PrintablePageWriterTest {

	@Test
	void placesRegionsOnSuccessiveA4Pages() throws Exception {
		Path directory = Path.of("target", "pdf-feasibility").toAbsolutePath();
		Files.createDirectories(directory);
		Path outputPath = directory.resolve("booklet-placement.pdf");
		try (PDDocument source = new PDDocument(); PDDocument output = new PDDocument()) {
			PDPage sourcePage = new PDPage(new PDRectangle(400, 500));
			source.addPage(sourcePage);
			try (PDPageContentStream stream = new PDPageContentStream(source, sourcePage)) {
				stream.beginText();
				stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
				stream.newLineAtOffset(40, 430);
				stream.showText("Calculate the equilibrium constant.");
				stream.endText();

				// Provide representative captured answer lines.
				stream.setLineWidth(0.5f);
				for (int index = 0; index < 12; index++) {
					float y = 390 - index * 25;
					stream.moveTo(40, y);
					stream.lineTo(340, y);
				}
				stream.stroke();
			}
			PrintablePageWriter writer = new PrintablePageWriter(output);
			writer.appendText("Question 1");
			PDRectangle region = new PDRectangle(20, 20, 350, 440);
			writer.appendRegion(source, 1, region, "", "");
			writer.appendRegion(source, 1, region, "", "");

			// Two regions cannot fit within one A4 page.
			assertEquals(2, output.getNumberOfPages());
			output.save(outputPath.toFile());
		}
		try (PDDocument output = Loader.loadPDF(outputPath.toFile())) {
			assertEquals(2, output.getNumberOfPages());
			for (PDPage page : output.getPages()) {
				assertEquals(PDRectangle.A4.getWidth(), page.getMediaBox().getWidth(), 0.01f);
				assertEquals(PDRectangle.A4.getHeight(), page.getMediaBox().getHeight(), 0.01f);
				boolean containsForm = false;
				for (COSName name : page.getResources().getXObjectNames()) {
					if (page.getResources().getXObject(name) instanceof PDFormXObject) {
						containsForm = true;
					}
				}
				assertTrue(containsForm);
			}
			PDFTextStripper stripper = new PDFTextStripper();
			stripper.setStartPage(1);
			stripper.setEndPage(1);
			String firstPage = stripper.getText(output);
			assertTrue(firstPage.contains("Question 1"));
			assertTrue(firstPage.contains("Calculate the equilibrium constant."));
			stripper.setStartPage(2);
			stripper.setEndPage(2);
			String secondPage = stripper.getText(output);
			assertTrue(secondPage.contains("Calculate the equilibrium constant."));
		}
		System.out.println("Booklet PDF: " + outputPath);
	}
}
