package au.edu.eq.questionbank.service.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

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
}
