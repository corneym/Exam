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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;

class QuestionExtractorTest {

	private record PageSpec(float width, float height, Color color) {
	}

	@TempDir
	Path tempDir;

	private final QuestionExtractor extractor = new QuestionExtractor();
	private ExamBooklet booklet;

	private CurriculumNode createClassification() {
		Subject subject = new Subject(1, "Science");
		SyllabusVersion syllabus = new SyllabusVersion(1, subject, "2026", true);
		Unit unit = new Unit(1, syllabus, "1", "Unit 1", 1);
		Topic topic = new Topic(2, syllabus, unit, "1.1", "Topic 1", 1);
		return new Subtopic(3, syllabus, topic, "1.1.1", "Subtopic 1", 1);
	}

	private Path createHorizontallySplitPdf(Color leftColor, Color rightColor) throws IOException {
		Path pdf = tempDir.resolve("split-source-" + System.nanoTime() + ".pdf");
		try (PDDocument document = new PDDocument()) {
			PDPage page = new PDPage(new PDRectangle(72, 72));
			document.addPage(page);
			try (PDPageContentStream content = new PDPageContentStream(document, page)) {
				content.setNonStrokingColor(leftColor);
				content.addRect(0, 0, 36, 72);
				content.fill();
				content.setNonStrokingColor(rightColor);
				content.addRect(36, 0, 36, 72);
				content.fill();
			}
			document.save(pdf.toFile());
		}
		return pdf;
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

	private Path createRotatedPdf(float width, float height, int rotation, Color color) throws IOException {
		Path pdf = tempDir.resolve("rotated-source-" + System.nanoTime() + ".pdf");
		try (PDDocument document = new PDDocument()) {
			PDPage page = new PDPage(new PDRectangle(width, height));
			page.setRotation(rotation);
			document.addPage(page);
			try (PDPageContentStream content = new PDPageContentStream(document, page)) {
				content.setNonStrokingColor(color);
				content.addRect(0, 0, width, height);
				content.fill();
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
		Question question = new Question(3, booklet.getExam(), "Q1", "A multi-page question.",
				List.of(new QuestionRegion(booklet, 1, 0.0, 0.0, 1.0, 1.0),
						new QuestionRegion(booklet, 2, 0.0, 0.0, 1.0, 1.0)),
				createClassification());

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

		extractor.extractRegion(pdf, new QuestionRegion(booklet, 1, 0.2, 0.2, 0.6, 0.6), output.toFile());

		BufferedImage image = readImage(output);
		assertAll(() -> assertEquals(3, image.getWidth()), () -> assertEquals(3, image.getHeight()));
	}

	@Test
	void extractsAFullPageRegion() throws Exception {
		Path pdf = createPdf(new PageSpec(72, 72, Color.WHITE));
		Path output = tempDir.resolve("full-page.png");

		extractor.extractRegion(pdf, new QuestionRegion(booklet, 1, 0.0, 0.0, 1.0, 1.0), output.toFile());

		BufferedImage image = readImage(output);
		assertAll(() -> assertEquals(150, image.getWidth()), () -> assertEquals(150, image.getHeight()));
	}

	@Test
	void extractsProportionalCoordinatesFromARotatedPage() throws Exception {
		Path pdf = createRotatedPdf(72, 144, 90, Color.GREEN);
		Path output = tempDir.resolve("rotated-region.png");

		extractor.extractRegion(pdf, new QuestionRegion(booklet, 1, 0.25, 0.0, 0.5, 1.0), output.toFile());

		BufferedImage image = readImage(output);
		assertAll(() -> assertEquals(150, image.getWidth()), () -> assertEquals(150, image.getHeight()),
				() -> assertEquals(Color.GREEN.getRGB(), image.getRGB(75, 75)));
	}

	@Test
	void extractsARegionEndingAtTheRightAndBottomEdges() throws Exception {
		Path pdf = createHorizontallySplitPdf(Color.RED, Color.BLUE);
		Path output = tempDir.resolve("bottom-right.png");

		extractor.extractRegion(pdf, new QuestionRegion(booklet, 1, 0.5, 0.5, 0.5, 0.5), output.toFile());

		BufferedImage image = readImage(output);
		assertAll(() -> assertEquals(75, image.getWidth()), () -> assertEquals(75, image.getHeight()),
				() -> assertEquals(Color.BLUE.getRGB(), image.getRGB(37, 37)));
	}

	@Test
	void padsNarrowerRegionsWithWhiteWhenCombining() throws Exception {
		Path pdf = createPdf(new PageSpec(72, 72, Color.RED), new PageSpec(36, 72, Color.BLUE));
		Path output = tempDir.resolve("different-widths.png");
		Question question = new Question(3, booklet.getExam(), "Q2", "Different widths",
				List.of(new QuestionRegion(booklet, 1, 0.0, 0.0, 1.0, 1.0),
						new QuestionRegion(booklet, 2, 0.0, 0.0, 1.0, 1.0)),
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
		Path output = tempDir.resolve("very-narrow-region.png");

		extractor.extractRegion(pdf, new QuestionRegion(booklet, 1, 0.5, 0.0, 0.001, 1.0), output.toFile());

		BufferedImage image = readImage(output);

		assertAll(() -> assertEquals(1, image.getWidth()), () -> assertEquals(150, image.getHeight()));
	}

	@Test
	void preservesAtLeastOnePixelForAVeryShortRegion() throws Exception {
		Path pdf = createPdf(new PageSpec(72, 72, Color.WHITE));
		Path output = tempDir.resolve("very-short-region.png");

		extractor.extractRegion(pdf, new QuestionRegion(booklet, 1, 0.0, 0.5, 1.0, 0.001), output.toFile());

		BufferedImage image = readImage(output);

		assertAll(() -> assertEquals(150, image.getWidth()), () -> assertEquals(1, image.getHeight()));
	}

	@Test
	void rejectsARegionWhosePageIsOutsideTheDocument() throws Exception {
		Path pdf = createPdf(new PageSpec(72, 72, Color.WHITE));

		try (PdfSession session = PdfSession.open(pdf)) {
			assertThrows(IllegalArgumentException.class,
					() -> extractor.extractRegion(session, new QuestionRegion(booklet, 2, 0.0, 0.0, 1.0, 1.0)));
		}
	}

	@BeforeEach
	void setUp() {
		Subject subject = new Subject(78, "Psych");
		ExamProvider provider = new ExamProvider(45, "QCAA");
		Exam exam = new Exam(9, subject, provider, 2020, "Test exam");
		SourceDocument document = new SourceDocument(4, "path");

		booklet = new ExamBooklet(3, exam, "Test booklet", document);
	}
}
