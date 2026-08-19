package au.edu.eq.questionbank.pdf;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.SourceDocument;

class QuestionExtractorTest {

	@TempDir
	Path tempDir;

	private final QuestionExtractor extractor = new QuestionExtractor();

	@Test
	void extractsAFullPageRegion() throws IOException {
		Path pdf = createPdf(new PageSpec(72, 72, Color.WHITE));
		Path output = tempDir.resolve("full-page.png");

		extractor.extractRegion(pdf, new QuestionRegion(1, 0, 0, 1, 1), output.toFile());

		BufferedImage image = readImage(output);
		assertAll(
				() -> assertEquals(150, image.getWidth()),
				() -> assertEquals(150, image.getHeight()));
	}

	@Test
	void extractsARegionEndingAtTheRightAndBottomEdges() throws IOException {
		Path pdf = createPdf(new PageSpec(72, 72, Color.WHITE));
		Path output = tempDir.resolve("bottom-right.png");

		extractor.extractRegion(pdf, new QuestionRegion(1, 0.5, 0.5, 0.5, 0.5), output.toFile());

		BufferedImage image = readImage(output);
		assertAll(
				() -> assertEquals(75, image.getWidth()),
				() -> assertEquals(75, image.getHeight()));
	}

	@Test
	void derivesCropSizeFromEndpointsOnAnOddSizedRenderedPage() throws IOException {
		Path pdf = createPdf(new PageSpec(2.4f, 2.4f, Color.WHITE));
		Path output = tempDir.resolve("odd-sized-page.png");

		extractor.extractRegion(pdf, new QuestionRegion(1, 0.1, 0.1, 0.9, 0.9), output.toFile());

		BufferedImage image = readImage(output);
		assertAll(
				() -> assertEquals(5, image.getWidth()),
				() -> assertEquals(5, image.getHeight()));
	}

	@Test
	void preservesAtLeastOnePixelForAVeryNarrowRegion() throws IOException {
		Path pdf = createPdf(new PageSpec(72, 72, Color.WHITE));
		Path output = tempDir.resolve("narrow-region.png");

		extractor.extractRegion(pdf, new QuestionRegion(1, 0.5, 0.5, 0.001, 0.001), output.toFile());

		BufferedImage image = readImage(output);
		assertAll(
				() -> assertEquals(1, image.getWidth()),
				() -> assertEquals(1, image.getHeight()));
	}

	@Test
	void combinesMultipleRegionsInQuestionOrder() throws IOException {
		Path pdf = createPdf(
				new PageSpec(72, 72, Color.RED),
				new PageSpec(72, 72, Color.BLUE));
		Path output = tempDir.resolve("multiple-regions.png");
		SourceDocument sourceDocument = new SourceDocument(1, "exam.pdf");
		Exam exam = new Exam(2, "Science", 2026, "External assessment", sourceDocument);
		Question question = new Question(3, exam, "Q1", "A multi-page question.", List.of(
				new QuestionRegion(1, 0, 0, 1, 1),
				new QuestionRegion(2, 0, 0, 1, 1)));

		extractor.extractQuestion(pdf, question, output.toFile());

		BufferedImage image = readImage(output);
		assertAll(
				() -> assertEquals(150, image.getWidth()),
				() -> assertEquals(300, image.getHeight()),
				() -> assertEquals(Color.RED.getRGB(), image.getRGB(75, 75)),
				() -> assertEquals(Color.BLUE.getRGB(), image.getRGB(75, 225)));
	}

	private Path createPdf(PageSpec... pages) throws IOException {
		Path pdf = tempDir.resolve("source-" + System.nanoTime() + ".pdf");
		try (PDDocument document = new PDDocument()) {
			for (PageSpec pageSpec : pages) {
				PDPage page = new PDPage(new PDRectangle(pageSpec.width(), pageSpec.height()));
				document.addPage(page);
				try (PDPageContentStream content = new PDPageContentStream(document, page)) {
					content.setNonStrokingColor(pageSpec.color());
					content.addRect(0, 0, pageSpec.width(), pageSpec.height());
					content.fill();
				}
			}
			document.save(pdf.toFile());
		}
		return pdf;
	}

	private BufferedImage readImage(Path path) throws IOException {
		BufferedImage image = ImageIO.read(path.toFile());
		assertNotNull(image);
		return image;
	}

	private record PageSpec(float width, float height, Color color) {
	}
}
