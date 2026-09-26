package au.edu.eq.questionbank.service.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
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
import au.edu.eq.questionbank.model.ImageQuestionContentPart;
import au.edu.eq.questionbank.model.PdfQuestionContentPart;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionContentPart;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.pdf.PdfStore;
import au.edu.eq.questionbank.pdf.QuestionExtractor;

class QuestionContentRendererTest {

	@TempDir
	Path tempDir;
	private ExamBooklet booklet;
	private CurriculumNode classification;
	private QuestionContentRenderer renderer;

	@Test
	void mixedQuestionRequiresPdfWhenItContainsPdfContent() throws Exception {
		byte[] pngBytes = createPng(40, 20, Color.RED);
		QuestionRegion region = new QuestionRegion(booklet, 1, 0.0, 0.0, 1.0, 1.0);
		Question question = new Question(13, booklet, "Q4", "", 2, List.of(region), classification, false, null, null,
				QuestionResponseType.WRITTEN_RESPONSE,
				List.of(new ImageQuestionContentPart(pngBytes), new PdfQuestionContentPart(region)));
		IOException exception = assertThrows(IOException.class, () -> renderer.renderQuestionBody(question));
		assertTrue(exception.getMessage().contains("Question source PDF is not available"));
	}

	@Test
	void rendersExistingPdfOnlyQuestion() throws Exception {
		createPdf(tempDir.resolve("pdf").resolve("exam.pdf"), Color.BLUE);
		QuestionRegion region = new QuestionRegion(booklet, 1, 0.0, 0.0, 1.0, 1.0);
		Question question = new Question(10, booklet, "Q1", "", 2, List.of(region), classification, false);
		BufferedImage image = renderer.renderQuestionBody(question);
		assertEquals(150, image.getWidth());
		assertEquals(150, image.getHeight());
		assertEquals(Color.BLUE.getRGB(), image.getRGB(75, 75));
	}

	@Test
	void rendersImageOnlyQuestionWithoutSourcePdf() throws Exception {
		byte[] pngBytes = createPng(40, 20, Color.RED);
		Question question = new Question(11, booklet, "Q2", "", 2, List.of(), classification, false, null, null,
				QuestionResponseType.WRITTEN_RESPONSE, List.of(new ImageQuestionContentPart(pngBytes)));

		// Deliberately do not create exam.pdf. Stored image content must be
		// independently
		// renderable without access to a source PDF.
		BufferedImage image = renderer.renderQuestionBody(question);
		assertEquals(40, image.getWidth());
		assertEquals(20, image.getHeight());
		assertEquals(Color.RED.getRGB(), image.getRGB(20, 10));
	}

	@Test
	void rendersMixedImageThenPdfInAuthoritativeOrder() throws Exception {
		createPdf(tempDir.resolve("pdf").resolve("exam.pdf"), Color.BLUE);
		byte[] pngBytes = createPng(40, 20, Color.RED);
		QuestionRegion region = new QuestionRegion(booklet, 1, 0.0, 0.0, 1.0, 1.0);
		List<QuestionContentPart> contentParts = List.of(new ImageQuestionContentPart(pngBytes),
				new PdfQuestionContentPart(region));
		Question question = new Question(12, booklet, "Q3", "", 1, List.of(region), classification, false, null, null,
				QuestionResponseType.MULTIPLE_CHOICE, contentParts);
		BufferedImage image = renderer.renderQuestionBody(question);

		// The wider PDF defines final width; heights are stacked without scaling.
		assertEquals(150, image.getWidth());
		assertEquals(170, image.getHeight());

		// Clipboard image appears first.
		assertEquals(Color.RED.getRGB(), image.getRGB(20, 10));

		// Unused width beside the narrower image is white.
		assertEquals(Color.WHITE.getRGB(), image.getRGB(100, 10));

		// PDF question material follows the image.
		assertEquals(Color.BLUE.getRGB(), image.getRGB(75, 95));
	}

	@BeforeEach
	void setUp() throws Exception {
		Path pdfRoot = tempDir.resolve("pdf");
		Files.createDirectories(pdfRoot);
		Subject subject = new Subject(1, "Chemistry");
		ExamProvider provider = new ExamProvider(2, "QCAA");
		Exam exam = new Exam(3, subject, provider, 2025, "External Assessment");
		SourceDocument sourceDocument = new SourceDocument(4, "exam.pdf");
		booklet = new ExamBooklet(5, exam, "Paper 1", sourceDocument);
		SyllabusVersion syllabus = new SyllabusVersion(6, subject, "2025", true);
		Unit unit = new Unit(7, syllabus, "1", "Unit 1", 1);
		Topic topic = new Topic(8, syllabus, unit, "1.1", "Topic 1", 1);
		classification = new Subtopic(9, syllabus, topic, "1.1.1", "Subtopic 1", 1);
		renderer = new QuestionContentRenderer(new PdfStore(pdfRoot), new QuestionExtractor());
	}

	private void createPdf(Path path, Color color) throws Exception {
		try (PDDocument document = new PDDocument()) {
			PDPage page = new PDPage(new PDRectangle(72, 72));
			document.addPage(page);
			try (PDPageContentStream content = new PDPageContentStream(document, page)) {
				content.setNonStrokingColor(color);
				content.addRect(0, 0, 72, 72);
				content.fill();
			}
			document.save(path.toFile());
		}
	}

	private byte[] createPng(int width, int height, Color color) throws Exception {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = image.createGraphics();
		try {
			graphics.setColor(color);
			graphics.fillRect(0, 0, width, height);
		} finally {
			graphics.dispose();
		}
		try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			if (!ImageIO.write(image, "png", output)) {
				throw new IOException("PNG writer unavailable during test");
			}
			return output.toByteArray();
		}
	}
}
