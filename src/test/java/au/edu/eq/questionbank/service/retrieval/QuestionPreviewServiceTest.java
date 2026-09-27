package au.edu.eq.questionbank.service.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

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
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.SharedQuestionContext;
import au.edu.eq.questionbank.model.SharedQuestionContextRegion;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.pdf.PdfStore;
import au.edu.eq.questionbank.pdf.QuestionExtractor;
import au.edu.eq.questionbank.service.render.QuestionContentRenderer;

class QuestionPreviewServiceTest {

	@TempDir
	Path tempDir;
	private ExamBooklet booklet;
	private CurriculumNode classification;
	private QuestionPreviewService service;

	@Test
	void legacyQuestionWithoutRegionsHasNoPreview() throws Exception {
		Question question = new Question(11, booklet, "Q2", "", 1, List.of(), classification, false);
		Optional<BufferedImage> preview = service.loadPreview(question);
		assertTrue(preview.isEmpty());
	}

	@Test
	void loadsMixedImageAndPdfPreviewInContentOrder() throws Exception {
		createPdf(tempDir.resolve("exam.pdf"), Color.BLUE);
		byte[] imageBytes = createPng(50, 20, Color.RED);
		QuestionRegion region = new QuestionRegion(booklet, 1, 0.0, 0.0, 1.0, 1.0);
		Question question = new Question(15, booklet, "Q4", "", 1, List.of(region), classification, false, null, null,
				QuestionResponseType.MULTIPLE_CHOICE,
				List.of(new ImageQuestionContentPart(imageBytes), new PdfQuestionContentPart(region)));

		// The domain object must retain the authoritative mixed-content sequence before
		// the preview layer attempts to render it.
		assertEquals(2, question.getContentParts().size());
		assertTrue(question.getContentParts().get(0) instanceof ImageQuestionContentPart);
		assertTrue(question.getContentParts().get(1) instanceof PdfQuestionContentPart);

		// Isolate the mixed-content renderer from the preview-service wrapper.
		QuestionContentRenderer contentRenderer = new QuestionContentRenderer(new PdfStore(tempDir),
				new QuestionExtractor());
		BufferedImage directlyRendered = contentRenderer.renderQuestionPreview(question);
		assertEquals(150, directlyRendered.getWidth());
		assertEquals(170, directlyRendered.getHeight());
		assertEquals(Color.RED.getRGB(), directlyRendered.getRGB(25, 10));
		assertEquals(Color.BLUE.getRGB(), directlyRendered.getRGB(75, 95));
		BufferedImage image = service.loadPreview(question).orElseThrow();
		assertEquals(150, image.getWidth());
		assertEquals(170, image.getHeight());

		// Stored image appears before the captured PDF question region.
		assertEquals(Color.RED.getRGB(), image.getRGB(25, 10));
		assertEquals(Color.BLUE.getRGB(), image.getRGB(75, 95));
	}

	@Test
	void loadsPreviewFromStoredImageWithoutSourcePdf() throws Exception {
		byte[] imageBytes = createPng(60, 30, Color.MAGENTA);
		Question question = new Question(14, booklet, "Q3", "", 2, List.of(), classification, false, null, null,
				QuestionResponseType.WRITTEN_RESPONSE, List.of(new ImageQuestionContentPart(imageBytes)));

		// No exam.pdf exists. An image-only Question must preview entirely from its
		// persisted content.
		Optional<BufferedImage> preview = service.loadPreview(question);
		assertTrue(preview.isPresent());
		BufferedImage image = preview.orElseThrow();
		assertEquals(60, image.getWidth());
		assertEquals(30, image.getHeight());
		assertEquals(Color.MAGENTA.getRGB(), image.getRGB(30, 15));
	}

	@Test
	void loadsPreviewFromStoredPdfRegion() throws Exception {
		createPdf(tempDir.resolve("exam.pdf"), Color.GREEN);
		Question question = new Question(10, booklet.getExam(), "Q1", "", 2,
				List.of(new QuestionRegion(booklet, 1, 0.0, 0.0, 0.5, 1.0)), classification);
		Optional<BufferedImage> preview = service.loadPreview(question);
		assertTrue(preview.isPresent());
		BufferedImage image = preview.get();
		assertEquals(75, image.getWidth());
		assertEquals(150, image.getHeight());
		assertEquals(Color.GREEN.getRGB(), image.getRGB(37, 75));
	}

	@Test
	void prependsLinkedSharedContextToPreview() throws Exception {
		createPdf(tempDir.resolve("exam.pdf"), Color.RED, Color.BLUE);
		SharedQuestionContext sharedContext = new SharedQuestionContext(12, booklet, "Question 24 shared context",
				List.of(new SharedQuestionContextRegion(1, 0.0, 0.0, 1.0, 1.0)));
		Question question = new Question(13, booklet, "24a", "", 2,
				List.of(new QuestionRegion(booklet, 2, 0.0, 0.0, 1.0, 1.0)), classification, false, null,
				sharedContext);
		Optional<BufferedImage> preview = service.loadPreview(question);
		assertTrue(preview.isPresent());
		BufferedImage image = preview.get();
		assertEquals(150, image.getWidth());
		assertEquals(300, image.getHeight());
		assertEquals(Color.RED.getRGB(), image.getRGB(75, 75));
		assertEquals(Color.BLUE.getRGB(), image.getRGB(75, 225));
	}

	@BeforeEach
	void setUp() {
		Subject subject = new Subject(1, "Chemistry");
		ExamProvider provider = new ExamProvider(2, "QCAA");
		Exam exam = new Exam(3, subject, provider, 2025, "External Assessment");
		SourceDocument sourceDocument = new SourceDocument(4, "exam.pdf");
		booklet = new ExamBooklet(5, exam, "Paper 1", sourceDocument);
		SyllabusVersion syllabus = new SyllabusVersion(6, subject, "2025", true);
		Unit unit = new Unit(7, syllabus, "1", "Unit 1", 1);
		Topic topic = new Topic(8, syllabus, unit, "1.1", "Topic 1", 1);
		classification = new Subtopic(9, syllabus, topic, "1.1.1", "Subtopic 1", 1);
		service = new QuestionPreviewService(new PdfStore(tempDir), new QuestionExtractor());
	}

	private void createPdf(Path path, Color... colors) throws Exception {
		try (PDDocument document = new PDDocument()) {
			for (Color color : colors) {
				PDPage page = new PDPage(new PDRectangle(72, 72));
				document.addPage(page);
				try (PDPageContentStream content = new PDPageContentStream(document, page)) {
					content.setNonStrokingColor(color);
					content.addRect(0, 0, 72, 72);
					content.fill();
				}
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
			assertTrue(ImageIO.write(image, "png", output));
			return output.toByteArray();
		}
	}
}
