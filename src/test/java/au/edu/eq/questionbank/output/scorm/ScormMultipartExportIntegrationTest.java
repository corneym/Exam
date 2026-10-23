package au.edu.eq.questionbank.output.scorm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.Answer;
import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.ImageQuestionContentPart;
import au.edu.eq.questionbank.model.ImageSharedContextContentPart;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.SharedContextStatus;
import au.edu.eq.questionbank.model.SharedQuestionContext;
import au.edu.eq.questionbank.model.SharedQuestionContextRegion;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.SourceQuestion;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.output.revision.RevisionAnswerAssetRenderer;
import au.edu.eq.questionbank.output.revision.RevisionExportService;
import au.edu.eq.questionbank.output.revision.RevisionExportValidator;
import au.edu.eq.questionbank.output.revision.RevisionQuestionAssetRenderer;
import au.edu.eq.questionbank.output.revision.RevisionSharedContextAssetRenderer;
import au.edu.eq.questionbank.pdf.PdfStore;
import au.edu.eq.questionbank.pdf.QuestionExtractor;
import au.edu.eq.questionbank.repository.assessment.InMemoryQuestionOutputApplicabilityRepository;
import au.edu.eq.questionbank.repository.assessment.QuestionApplicabilityMatch;
import au.edu.eq.questionbank.repository.curriculum.InMemoryCurriculumRepository;
import au.edu.eq.questionbank.service.retrieval.CurriculumSearchNodeExpansionService;
import au.edu.eq.questionbank.service.retrieval.QuestionRetrievalService;
import au.edu.eq.questionbank.service.revision.RevisionCorpusBuilder;
import au.edu.eq.questionbank.service.revision.RevisionPresentationPlanner;

class ScormMultipartExportIntegrationTest {

	@TempDir
	Path tempDir;

	@Test
	void packagesImageOnlyAndMixedSharedContextWithManifestReferences() throws Exception {
		Path pdfRoot = tempDir.resolve("mixed-context-pdf");
		Files.createDirectories(pdfRoot);
		createPdf(pdfRoot.resolve("question.pdf"));
		Subject chemistry = new Subject(1, "Chemistry");
		SyllabusVersion historical = new SyllabusVersion(1, chemistry, "2019", false);
		Unit historicalUnit = new Unit(100, historical, "1", "Historical unit", 1);
		Topic historicalTopic = new Topic(101, historical, historicalUnit, "1.1", "Historical topic", 1);
		Descriptor classification = new Descriptor(102, historical, historicalTopic, "1.1.1", "Historical descriptor",
				1);
		SyllabusVersion current = new SyllabusVersion(2, chemistry, "2025", true);
		Unit currentUnit = new Unit(10, current, "1", "Unit 1", 1);
		Topic currentTopic = new Topic(11, current, currentUnit, "1.1", "Atomic structure", 1);
		Descriptor currentDescriptor = new Descriptor(12, current, currentTopic, "1.1.1", "Atomic models", 1);
		ExamProvider provider = new ExamProvider(1, "QCAA");
		Exam exam = new Exam(1, chemistry, provider, 2022, "Chemistry examination");

		// The first booklet deliberately has no readable source PDF.
		ExamBooklet imageBooklet = new ExamBooklet(1, exam, "Paper 1", new SourceDocument(1, "unavailable.pdf"));
		ExamBooklet pdfBooklet = new ExamBooklet(2, exam, "Paper 2", new SourceDocument(2, "question.pdf"));
		assertFalse(Files.exists(pdfRoot.resolve("unavailable.pdf")));
		byte[] redContextPng = createPng(72, 18, Color.RED.getRGB());
		byte[] greenQuestionPng = createPng(72, 18, Color.GREEN.getRGB());
		byte[] blueContextPng = createPng(72, 18, Color.BLUE.getRGB());
		SharedQuestionContext imageOnlyContext = SharedQuestionContext.fromContentParts(50, imageBooklet,
				"Image-only Shared Context", List.of(new ImageSharedContextContentPart(redContextPng)));
		SharedQuestionContext mixedContext = SharedQuestionContext.fromContentParts(51, pdfBooklet,
				"Mixed Shared Context",
				List.of(new SharedQuestionContextRegion(1, 0.0, 0.0, 1.0, 0.2),
						new ImageSharedContextContentPart(blueContextPng),
						new SharedQuestionContextRegion(1, 0.0, 0.75, 1.0, 0.25)));
		SourceQuestion imageSource = new SourceQuestion(24, imageBooklet, "24", SharedContextStatus.PRESENT);
		Question partA = new Question(4, imageBooklet, "24a", "", 1, List.of(), classification, false, imageSource,
				imageOnlyContext, QuestionResponseType.UNKNOWN,
				List.of(new ImageQuestionContentPart(greenQuestionPng)));
		Question partB = new Question(5, imageBooklet, "24b", "", 1, List.of(), classification, false, imageSource,
				imageOnlyContext, QuestionResponseType.UNKNOWN,
				List.of(new ImageQuestionContentPart(greenQuestionPng)));
		QuestionRegion bodyRegion = new QuestionRegion(pdfBooklet, 1, 0.0, 0.3, 1.0, 0.2);
		Question mixedQuestion = new Question(6, pdfBooklet, "25", "", 2, List.of(bodyRegion), classification, false,
				null, mixedContext);
		InMemoryCurriculumRepository curriculumRepository = new InMemoryCurriculumRepository(List.of(chemistry),
				List.of(historical, current),
				List.of(historicalUnit, historicalTopic, classification, currentUnit, currentTopic, currentDescriptor));
		QuestionRetrievalService retrieval = new QuestionRetrievalService(
				_ -> List.of(new QuestionApplicabilityMatch(partA, currentDescriptor),
						new QuestionApplicabilityMatch(partB, currentDescriptor),
						new QuestionApplicabilityMatch(mixedQuestion, currentDescriptor)),
				new CurriculumSearchNodeExpansionService(curriculumRepository));
		RevisionCorpusBuilder corpusBuilder = new RevisionCorpusBuilder(curriculumRepository, retrieval,
				new InMemoryQuestionOutputApplicabilityRepository());
		PdfStore pdfStore = new PdfStore(pdfRoot);
		QuestionExtractor extractor = new QuestionExtractor();
		RevisionExportService revisionService = new RevisionExportService(corpusBuilder,
				new RevisionPresentationPlanner(), new RevisionQuestionAssetRenderer(pdfStore, extractor),
				new RevisionSharedContextAssetRenderer(pdfStore, extractor),
				new RevisionAnswerAssetRenderer(pdfStore, extractor), new RevisionExportValidator());
		ScormExportService scormService = new ScormExportService(revisionService, new ScormManifestWriter(),
				new ScormSchemaSupport(), new ScormPackageValidator(), new ScormZipWriter());
		Path destination = tempDir.resolve("Chemistry-mixed-context.zip");
		ScormExportResult result = scormService.export(new ScormExportRequest(chemistry, destination));
		assertTrue(Files.isRegularFile(destination));
		assertEquals(3, result.getStatistics().getUniqueApplicableQuestions());
		try (ZipFile zip = new ZipFile(destination.toFile(), StandardCharsets.UTF_8)) {
			String context50 = "assets/contexts/context-50.png";
			String context51 = "assets/contexts/context-51.png";
			ZipEntry imageEntry = zip.getEntry(context50);
			ZipEntry mixedEntry = zip.getEntry(context51);
			assertNotNull(imageEntry);
			assertNotNull(mixedEntry);
			assertNotNull(zip.getEntry("assets/questions/question-4.png"));
			assertNotNull(zip.getEntry("assets/questions/question-5.png"));
			assertNotNull(zip.getEntry("assets/questions/question-6.png"));
			ZipEntry manifestEntry = zip.getEntry("imsmanifest.xml");
			ZipEntry topicEntry = zip.getEntry("units/unit-10/topic-11.html");
			assertNotNull(manifestEntry);
			assertNotNull(topicEntry);
			String manifest = new String(zip.getInputStream(manifestEntry).readAllBytes(), StandardCharsets.UTF_8);
			String topicHtml = new String(zip.getInputStream(topicEntry).readAllBytes(), StandardCharsets.UTF_8);
			for (String asset : List.of(context50, context51, "assets/questions/question-4.png",
					"assets/questions/question-5.png", "assets/questions/question-6.png")) {
				assertEquals(1, countOccurrences(manifest, "<file href=\"" + asset + "\""),
						"Every generated PNG must occur exactly once in the SCORM manifest: " + asset);
			}
			assertEquals(1, countOccurrences(topicHtml, "context-50.png"));
			assertEquals(1, countOccurrences(topicHtml, "context-51.png"));
			assertTrue(topicHtml.contains("Questions 24a, 24b"));
			assertTrue(topicHtml.indexOf("context-50.png") < topicHtml.indexOf("question-4.png"));
			assertTrue(topicHtml.indexOf("context-51.png") < topicHtml.indexOf("question-6.png"));
			assertFalse(zip.stream().anyMatch(entry -> entry.getName().endsWith(".pdf")),
					"Source PDFs must never be included in the SCORM ZIP");

			// Inspect pixels from the actual packaged PNGs, not an intermediate revision
			// directory.
			BufferedImage imageOnly = ImageIO.read(zip.getInputStream(imageEntry));
			assertNotNull(imageOnly);
			assertEquals(Color.RED.getRGB(), imageOnly.getRGB(10, imageOnly.getHeight() / 2));
			BufferedImage mixed = ImageIO.read(zip.getInputStream(mixedEntry));
			assertNotNull(mixed);
			assertEquals(Color.WHITE.getRGB(), mixed.getRGB(10, 2), "The first stored PDF region must be at the top");
			assertEquals(Color.BLUE.getRGB(), mixed.getRGB(10, mixed.getHeight() / 2),
					"The stored clipboard image must be between the PDF regions");
			assertEquals(Color.WHITE.getRGB(), mixed.getRGB(10, mixed.getHeight() - 3),
					"The final stored PDF region must be at the bottom");
		}
	}

	@Test
	void packagesMultipartPresentationAndSharedContext() throws Exception {
		Path pdfRoot = tempDir.resolve("pdf");
		Files.createDirectories(pdfRoot);
		Path questionPdf = pdfRoot.resolve("question.pdf");
		createPdf(questionPdf);
		Subject chemistry = new Subject(1, "Chemistry");
		SyllabusVersion historical = new SyllabusVersion(1, chemistry, "2019", false);
		Unit historicalUnit = new Unit(100, historical, "1", "Historical unit", 1);
		Topic historicalTopic = new Topic(101, historical, historicalUnit, "1.1", "Historical topic", 1);
		Descriptor historicalDescriptor = new Descriptor(102, historical, historicalTopic, "1.1.1",
				"Historical descriptor", 1);
		SyllabusVersion current = new SyllabusVersion(2, chemistry, "2025", true);
		Unit currentUnit = new Unit(10, current, "1", "Unit 1", 1);
		Topic currentTopic = new Topic(11, current, currentUnit, "1.1", "Atomic structure", 1);
		Descriptor currentDescriptor = new Descriptor(12, current, currentTopic, "1.1.1", "Atomic models", 1);
		ExamProvider provider = new ExamProvider(1, "QCAA");
		Exam exam = new Exam(1, chemistry, provider, 2022, "Chemistry examination");
		SourceDocument sourceDocument = new SourceDocument(1, "question.pdf");
		ExamBooklet booklet = new ExamBooklet(1, exam, "Paper 1", sourceDocument);
		SourceQuestion sourceQuestion = new SourceQuestion(24, booklet, "24", SharedContextStatus.PRESENT);
		SharedQuestionContext sharedContext = new SharedQuestionContext(50, booklet, "Question 24 shared context",
				List.of(new SharedQuestionContextRegion(1, 0.0, 0.0, 1.0, 0.25)));
		Question partA = new Question(4, booklet, "24a", "", 2,
				List.of(new QuestionRegion(booklet, 1, 0.0, 0.25, 1.0, 0.25)), historicalDescriptor, false,
				sourceQuestion, sharedContext);
		Question partB = new Question(5, booklet, "24b", "", 3,
				List.of(new QuestionRegion(booklet, 1, 0.0, 0.50, 1.0, 0.25)), historicalDescriptor, false,
				sourceQuestion, sharedContext);
		partA.setAnswer(new Answer(1, "Answer A", List.of()));
		partB.setAnswer(new Answer(2, "Answer B", List.of()));
		InMemoryCurriculumRepository curriculumRepository = new InMemoryCurriculumRepository(List.of(chemistry),
				List.of(historical, current), List.of(historicalUnit, historicalTopic, historicalDescriptor,
						currentUnit, currentTopic, currentDescriptor));
		CurriculumSearchNodeExpansionService expansionService = new CurriculumSearchNodeExpansionService(
				curriculumRepository);
		QuestionRetrievalService retrievalService = new QuestionRetrievalService(
				_ -> List.of(new QuestionApplicabilityMatch(partA, currentDescriptor),
						new QuestionApplicabilityMatch(partB, currentDescriptor)),
				expansionService);

		// The multipart SCORM fixture exercises normal fully included applicability.
		RevisionCorpusBuilder corpusBuilder = new RevisionCorpusBuilder(curriculumRepository, retrievalService,
				new InMemoryQuestionOutputApplicabilityRepository());
		PdfStore pdfStore = new PdfStore(pdfRoot);
		QuestionExtractor extractor = new QuestionExtractor();
		RevisionExportService revisionExportService = new RevisionExportService(corpusBuilder,
				new RevisionPresentationPlanner(), new RevisionQuestionAssetRenderer(pdfStore, extractor),
				new RevisionSharedContextAssetRenderer(pdfStore, extractor),
				new RevisionAnswerAssetRenderer(pdfStore, extractor), new RevisionExportValidator());
		ScormExportService scormExportService = new ScormExportService(revisionExportService, new ScormManifestWriter(),
				new ScormSchemaSupport(), new ScormPackageValidator(), new ScormZipWriter());
		Path destination = tempDir.resolve("Chemistry-multipart.zip");
		ScormExportResult result = scormExportService.export(new ScormExportRequest(chemistry, destination));
		assertTrue(Files.isRegularFile(destination));
		assertEquals(2, result.getStatistics().getUniqueApplicableQuestions());
		try (ZipFile zip = new ZipFile(destination.toFile(), StandardCharsets.UTF_8)) {
			assertNotNull(zip.getEntry("assets/contexts/context-50.png"));
			assertNotNull(zip.getEntry("assets/questions/question-4.png"));
			assertNotNull(zip.getEntry("assets/questions/question-5.png"));
			ZipEntry topicEntry = zip.getEntry("units/unit-10/topic-11.html");
			assertNotNull(topicEntry);
			String topicHtml = new String(zip.getInputStream(topicEntry).readAllBytes(), StandardCharsets.UTF_8);
			assertEquals(1, countOccurrences(topicHtml, "context-50.png"));
			assertEquals(1, countOccurrences(topicHtml, "<summary>Reveal answer</summary>"));
			assertTrue(topicHtml.contains("Question 1"));
			assertTrue(topicHtml.contains("5 marks"));
			assertFalse(topicHtml.contains("Source part 24a"));
			assertFalse(topicHtml.contains("Source part 24b"));
			assertTrue(topicHtml.contains("Questions 24a, 24b"));
			assertTrue(topicHtml.contains("Answer A"));
			assertTrue(topicHtml.contains("Answer B"));
			ZipEntry manifestEntry = zip.getEntry("imsmanifest.xml");
			assertNotNull(manifestEntry);
			String manifest = new String(zip.getInputStream(manifestEntry).readAllBytes(), StandardCharsets.UTF_8);
			assertTrue(manifest.contains("<file href=\"assets/contexts/context-50.png\""));
			assertTrue(manifest.contains("<file href=\"assets/questions/question-4.png\""));
			assertTrue(manifest.contains("<file href=\"assets/questions/question-5.png\""));
			assertTrue(manifest.contains("<file href=\"units/unit-10/topic-11.html\""));
		}
	}

	private int countOccurrences(String text, String target) {
		int count = 0;
		int offset = 0;
		while ((offset = text.indexOf(target, offset)) >= 0) {
			count++;
			offset += target.length();
		}
		return count;
	}

	private void createPdf(Path destination) throws IOException {
		try (PDDocument document = new PDDocument()) {
			PDPage page = new PDPage(new PDRectangle(72, 72));
			document.addPage(page);
			try (PDPageContentStream content = new PDPageContentStream(document, page)) {
				content.setNonStrokingColor(Color.WHITE);
				content.addRect(0, 0, 72, 72);
				content.fill();
			}
			document.save(destination.toFile());
		}
	}

	private byte[] createPng(int width, int height, int rgb) throws IOException {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				image.setRGB(x, y, rgb);
			}
		}
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		if (!ImageIO.write(image, "png", bytes)) {
			throw new IOException("PNG writer unavailable for test fixture");
		}
		return bytes.toByteArray();
	}
}
