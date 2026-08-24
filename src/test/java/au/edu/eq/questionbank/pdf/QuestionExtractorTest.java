package au.edu.eq.questionbank.pdf;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;

class QuestionExtractorTest {

	private record PageSpec(float width, float height, Color color) {
	}

	@TempDir
	Path tempDir;

	private final QuestionExtractor extractor = new QuestionExtractor();

	private CurriculumNode createClassification() {
		Subject subject = new Subject(1, "Science");
		SyllabusVersion syllabus = new SyllabusVersion(1, subject, "2026", true);
		CurriculumNode unit = new CurriculumNode(1, syllabus, null, "1", "Unit 1", CurriculumLevel.UNIT, 1);
		CurriculumNode topic = new CurriculumNode(2, syllabus, unit, "1.1", "Topic 1", CurriculumLevel.TOPIC, 1);
		return new CurriculumNode(3, syllabus, topic, "1.1.1", "Subtopic 1", CurriculumLevel.SUBTOPIC, 1);
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

	@Test
	void combinesMultipleRegionsInQuestionOrder() throws Exception {
		Path pdf = createPdf(new PageSpec(72, 72, Color.RED), new PageSpec(72, 72, Color.BLUE));
		Path output = tempDir.resolve("multiple-regions.png");
		SourceDocument sourceDocument = new SourceDocument(1, "exam.pdf");
		Exam exam = new Exam(2, new Subject(2, "Science"), 2026, "External assessment", sourceDocument);
		Question question = new Question(3, exam, "Q1", "A multi-page question.",
				List.of(new QuestionRegion(1, 0, 0, 1, 1), new QuestionRegion(2, 0, 0, 1, 1)), createClassification());

		extractor.extractQuestion(pdf, question, output.toFile());

		BufferedImage image = readImage(output);
		assertAll(() -> assertEquals(150, image.getWidth()), () -> assertEquals(300, image.getHeight()),
				() -> assertEquals(Color.RED.getRGB(), image.getRGB(75, 75)),
				() -> assertEquals(Color.BLUE.getRGB(), image.getRGB(75, 225)));
	}

	@Test
	void derivesCropSizeFromEndpointsOnAnOddSizedRenderedPage() throws Exception {
		Path pdf = createPdf(new PageSpec(2.4f, 2.4f, Color.WHITE));
		Path output = tempDir.resolve("odd-sized-page.png");

		extractor.extractRegion(pdf, new QuestionRegion(1, 0.1, 0.1, 0.9, 0.9), output.toFile());

		BufferedImage image = readImage(output);
		assertAll(() -> assertEquals(5, image.getWidth()), () -> assertEquals(5, image.getHeight()));
	}

	@Test
	void extractsAFullPageRegion() throws Exception {
		Path pdf = createPdf(new PageSpec(72, 72, Color.WHITE));
		Path output = tempDir.resolve("full-page.png");

		extractor.extractRegion(pdf, new QuestionRegion(1, 0, 0, 1, 1), output.toFile());

		BufferedImage image = readImage(output);
		assertAll(() -> assertEquals(150, image.getWidth()), () -> assertEquals(150, image.getHeight()));
	}

	@Test
	void extractsARegionEndingAtTheRightAndBottomEdges() throws Exception {
		Path pdf = createPdf(new PageSpec(72, 72, Color.WHITE));
		Path output = tempDir.resolve("bottom-right.png");

		extractor.extractRegion(pdf, new QuestionRegion(1, 0.5, 0.5, 0.5, 0.5), output.toFile());

		BufferedImage image = readImage(output);
		assertAll(() -> assertEquals(75, image.getWidth()), () -> assertEquals(75, image.getHeight()));
	}

	@Test
	void padsNarrowerRegionsWithWhiteWhenCombining() throws Exception {
		Path pdf = createPdf(new PageSpec(72, 72, Color.RED), new PageSpec(36, 72, Color.BLUE));
		Path output = tempDir.resolve("different-widths.png");
		Question question = new Question(3,
				new Exam(2, new Subject(3, "Biology"), 2026, "Assessment", new SourceDocument(1, "exam.pdf")), "Q2",
				"Different widths", List.of(new QuestionRegion(1, 0, 0, 1, 1), new QuestionRegion(2, 0, 0, 1, 1)),
				createClassification());

		extractor.extractQuestion(pdf, question, output.toFile());

		BufferedImage image = readImage(output);
		assertAll(() -> assertEquals(150, image.getWidth()), () -> assertEquals(300, image.getHeight()),
				() -> assertEquals(Color.BLUE.getRGB(), image.getRGB(25, 225)),
				() -> assertEquals(Color.WHITE.getRGB(), image.getRGB(125, 225)));
	}

	@Test
	void preservesAtLeastOnePixelForAVeryNarrowRegion() throws Exception {
		Path pdf = createPdf(new PageSpec(72, 72, Color.WHITE));
		Path output = tempDir.resolve("narrow-region.png");

		extractor.extractRegion(pdf, new QuestionRegion(1, 0.5, 0.5, 0.001, 0.001), output.toFile());

		BufferedImage image = readImage(output);
		assertAll(() -> assertEquals(1, image.getWidth()), () -> assertEquals(1, image.getHeight()));
	}

	@Test
	void rejectsARegionWhosePageIsOutsideTheDocument() throws Exception {
		Path pdf = createPdf(new PageSpec(72, 72, Color.WHITE));

		try (PdfSession session = PdfSession.open(pdf)) {
			assertThrows(IllegalArgumentException.class,
					() -> extractor.extractRegion(session, new QuestionRegion(2, 0, 0, 1, 1)));
		}
	}
}
