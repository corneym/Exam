package au.edu.eq.questionbank.output.revision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.Answer;
import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.ImageQuestionContentPart;
import au.edu.eq.questionbank.model.ImageSharedContextContentPart;
import au.edu.eq.questionbank.model.PdfQuestionContentPart;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionContentPart;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.SharedContextContentPart;
import au.edu.eq.questionbank.model.SharedContextStatus;
import au.edu.eq.questionbank.model.SharedQuestionContext;
import au.edu.eq.questionbank.model.SharedQuestionContextRegion;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.SourceQuestion;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.pdf.PdfStore;
import au.edu.eq.questionbank.pdf.QuestionExtractor;
import au.edu.eq.questionbank.repository.assessment.InMemoryQuestionOutputApplicabilityRepository;
import au.edu.eq.questionbank.repository.assessment.QuestionApplicabilityMatch;
import au.edu.eq.questionbank.repository.assessment.SqliteAnswerWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteExamImporter;
import au.edu.eq.questionbank.repository.assessment.SqliteExamWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteQuestionRepository;
import au.edu.eq.questionbank.repository.assessment.SqliteSharedContextCorrectionService;
import au.edu.eq.questionbank.repository.assessment.SqliteSourceQuestionRepository;
import au.edu.eq.questionbank.repository.curriculum.InMemoryCurriculumRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.retrieval.CurriculumSearchNodeExpansionService;
import au.edu.eq.questionbank.service.retrieval.QuestionPreviewService;
import au.edu.eq.questionbank.service.retrieval.QuestionRetrievalService;
import au.edu.eq.questionbank.service.revision.RevisionCorpusBuilder;
import au.edu.eq.questionbank.service.revision.RevisionPresentationPlanner;

class RevisionMultipartExportIntegrationTest {

	@TempDir
	Path tempDir;

	@Test
	void exportsCorrectedMultipartSourceWithOneContextAndRefreshedPreview() throws Exception {
		Path pdfRoot = tempDir.resolve("corrected-pdf");
		Files.createDirectories(pdfRoot);
		createContrastingPdf(pdfRoot.resolve("question.pdf"));
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("corrected-revision.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(database);
		Subject chemistry = writer.insertSubject("Chemistry");
		SyllabusVersion historical = writer.insertSyllabusVersion(chemistry, "2019", false);
		Unit historicalUnit = writer.insertUnit(historical, "1", "Historical unit", 1);
		Topic historicalTopic = writer.insertTopic(historicalUnit, "1.1", "Historical topic", 1);
		Descriptor classification = writer.insertDescriptor(historicalTopic, "1.1.1", "Historical descriptor", 1);
		SyllabusVersion current = writer.insertSyllabusVersion(chemistry, "2025", true);
		Unit currentUnit = writer.insertUnit(current, "1", "Unit 1", 1);
		Topic currentTopic = writer.insertTopic(currentUnit, "1.1", "Atomic structure", 1);
		Descriptor currentDescriptor = writer.insertDescriptor(currentTopic, "1.1.1", "Atomic models", 1);
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		ExamBooklet booklet = new SqliteExamImporter(database, examWriter).importExam(chemistry, "QCAA", 2019,
				"External Assessment", "Paper 1", "question.pdf");
		SqliteQuestionRepository questions = new SqliteQuestionRepository(database);
		SqliteSourceQuestionRepository sources = new SqliteSourceQuestionRepository(database);
		SourceQuestion source = sources.save(booklet, "36");
		source = sources.updateSharedContextStatus(source, SharedContextStatus.NONE);
		Question partA = questions.save(booklet, "36a", "", 2,
				List.of(new QuestionRegion(booklet, 1, 0.0, 0.25, 1.0, 0.25)), classification, false, source, null);
		Question partB = questions.save(booklet, "36b", "", 3,
				List.of(new QuestionRegion(booklet, 1, 0.0, 0.50, 1.0, 0.25)), classification, false, source, null);
		Question partC = questions.save(booklet, "36c", "", 4,
				List.of(new QuestionRegion(booklet, 1, 0.0, 0.75, 1.0, 0.25)), classification, false, source, null);
		SqliteAnswerWriter answers = new SqliteAnswerWriter(database, examWriter);
		answers.insertAnswer(partA, "Answer A", List.of());
		answers.insertAnswer(partB, "Answer B", List.of());
		answers.insertAnswer(partC, "Answer C", List.of());
		for (Question part : List.of(partA, partB, partC)) {
			assertFalse(questions.findById(part.getId()).orElseThrow().hasSharedContext());
		}

		// Correct one selected part. Persistence must attach the
		// newly captured Shared Context to the entire source group.
		SqliteSharedContextCorrectionService correction = new SqliteSharedContextCorrectionService(database);
		var result = correction.captureNew(partB.getId(), "Question 36 shared context",
				List.of(new SharedQuestionContextRegion(1, 0.0, 0.0, 1.0, 0.25)));
		assertEquals(3, result.affectedQuestionIds().size());

		// Reload through the normal Question repository so the
		// preview and revision pipelines receive persisted state.
		SqliteQuestionRepository reloadedRepository = new SqliteQuestionRepository(database);
		Question reloadedA = reloadedRepository.findById(partA.getId()).orElseThrow();
		Question reloadedB = reloadedRepository.findById(partB.getId()).orElseThrow();
		Question reloadedC = reloadedRepository.findById(partC.getId()).orElseThrow();
		List<Question> corrected = List.of(reloadedA, reloadedB, reloadedC);
		PdfStore pdfStore = new PdfStore(pdfRoot);
		QuestionExtractor extractor = new QuestionExtractor();
		QuestionPreviewService previewService = new QuestionPreviewService(pdfStore, extractor);
		for (Question part : corrected) {
			assertEquals(result.context().getId(), part.getSharedContext().getId());
			assertEquals(SharedContextStatus.PRESENT, part.getSourceQuestion().getSharedContextStatus());

			// Search previews must show Shared Context first
			// and the individual Question region second.
			BufferedImage preview = previewService.loadPreview(part).orElseThrow();
			assertEquals(Color.RED.getRGB(), preview.getRGB(75, 15));
			assertEquals(Color.BLUE.getRGB(), preview.getRGB(75, preview.getHeight() - 15));
		}
		InMemoryCurriculumRepository curriculumRepository = new InMemoryCurriculumRepository(List.of(chemistry),
				List.of(historical, current),
				List.of(historicalUnit, historicalTopic, classification, currentUnit, currentTopic, currentDescriptor));
		QuestionRetrievalService retrieval = new QuestionRetrievalService(
				_ -> List.of(new QuestionApplicabilityMatch(reloadedA, currentDescriptor),
						new QuestionApplicabilityMatch(reloadedB, currentDescriptor),
						new QuestionApplicabilityMatch(reloadedC, currentDescriptor)),
				new CurriculumSearchNodeExpansionService(curriculumRepository));
		RevisionCorpusBuilder corpusBuilder = new RevisionCorpusBuilder(curriculumRepository, retrieval,
				new InMemoryQuestionOutputApplicabilityRepository());
		RevisionExportService exportService = new RevisionExportService(corpusBuilder,
				new RevisionPresentationPlanner(), new RevisionQuestionAssetRenderer(pdfStore, extractor),
				new RevisionSharedContextAssetRenderer(pdfStore, extractor),
				new RevisionAnswerAssetRenderer(pdfStore, extractor), new RevisionExportValidator());
		Path destination = tempDir.resolve("corrected-revision-output");
		exportService.export(new RevisionExportRequest(chemistry, destination));
		String contextFileName = "context-" + result.context().getId() + ".png";
		Path contextImagePath = destination.resolve(Path.of("assets", "contexts", contextFileName));
		assertTrue(Files.isRegularFile(contextImagePath));
		BufferedImage contextImage = ImageIO.read(contextImagePath.toFile());
		assertEquals(Color.RED.getRGB(), contextImage.getRGB(75, 15));
		for (Question part : corrected) {
			Path questionImagePath = destination
					.resolve(Path.of("assets", "questions", "question-" + part.getId() + ".png"));
			assertTrue(Files.isRegularFile(questionImagePath));
			BufferedImage questionImage = ImageIO.read(questionImagePath.toFile());

			// Revision Question assets must contain only their
			// own blue Question regions, not the red context.
			assertEquals(Color.BLUE.getRGB(), questionImage.getRGB(75, 15));
		}
		Path topicFile = destination
				.resolve(Path.of("units", "unit-" + currentUnit.getId(), "topic-" + currentTopic.getId() + ".html"));
		assertTrue(Files.isRegularFile(topicFile));
		String html = Files.readString(topicFile);

		// Three corrected parts must produce one multipart
		// presentation with one linked context image.
		assertEquals(1, countOccurrences(html, contextFileName));
		assertTrue(html.contains("Questions 36a, 36b, 36c"));
		assertTrue(html.contains("9 marks"));
		for (Question part : corrected) {
			assertTrue(html.contains("question-" + part.getId() + ".png"));
		}
		assertTrue(html.indexOf(contextFileName) < html.indexOf("question-" + partA.getId() + ".png"));
		assertTrue(html.contains("Answer A"));
		assertTrue(html.contains("Answer B"));
		assertTrue(html.contains("Answer C"));
	}

	@Test
	void exportsImageOnlySharedContextWithoutSourcePdf() throws Exception {
		assertImageBackedSharedContextExport(false);
	}

	@Test
	void exportsMixedSharedContextPreservingPartOrder() throws Exception {
		assertImageBackedSharedContextExport(true);
	}

	@Test
	void exportsMultipartQuestionWithSharedContextOnce() throws Exception {
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

		// Both source parts remain included in this multipart export fixture.
		RevisionCorpusBuilder corpusBuilder = new RevisionCorpusBuilder(curriculumRepository, retrievalService,
				new InMemoryQuestionOutputApplicabilityRepository());
		PdfStore pdfStore = new PdfStore(pdfRoot);
		QuestionExtractor extractor = new QuestionExtractor();
		RevisionExportService service = new RevisionExportService(corpusBuilder, new RevisionPresentationPlanner(),
				new RevisionQuestionAssetRenderer(pdfStore, extractor),
				new RevisionSharedContextAssetRenderer(pdfStore, extractor),
				new RevisionAnswerAssetRenderer(pdfStore, extractor), new RevisionExportValidator());
		Path destination = tempDir.resolve("revision-output");
		RevisionExportResult result = service.export(new RevisionExportRequest(chemistry, destination));
		Path topicFile = destination.resolve(Path.of("units", "unit-10", "topic-11.html"));
		Path subjectIndex = destination.resolve("index.html");
		Path contextImage = destination.resolve(Path.of("assets", "contexts", "context-50.png"));
		Path partAImage = destination.resolve(Path.of("assets", "questions", "question-4.png"));
		Path partBImage = destination.resolve(Path.of("assets", "questions", "question-5.png"));
		assertTrue(Files.isRegularFile(topicFile));
		assertTrue(Files.isRegularFile(subjectIndex));
		assertTrue(Files.isRegularFile(contextImage));
		assertTrue(Files.isRegularFile(partAImage));
		assertTrue(Files.isRegularFile(partBImage));
		String html = Files.readString(topicFile);
		String subjectHtml = Files.readString(subjectIndex);

		// Two persisted source parts form one student-facing revision Question.
		assertTrue(subjectHtml.contains("<strong>1</strong>"));
		assertTrue(subjectHtml.contains("Revision question"));
		assertFalse(subjectHtml.contains("Applicable questions"));
		assertFalse(subjectHtml.contains("Exportable questions"));
		assertFalse(subjectHtml.contains("Awaiting question capture"));
		assertEquals(1, countOccurrences(html, "context-50.png"));
		assertEquals(1, countOccurrences(html, "<summary>Reveal answer</summary>"));
		assertTrue(html.contains("Question 1"));
		assertTrue(html.contains("5 marks"));
		assertFalse(html.contains("Source part 24a"));
		assertFalse(html.contains("Source part 24b"));
		assertTrue(html.contains("Questions 24a, 24b"));
		assertTrue(html.contains("question-4.png"));
		assertTrue(html.contains("question-5.png"));
		assertTrue(html.contains("Answer A"));
		assertTrue(html.contains("Answer B"));
		assertEquals(2, result.getStatistics().getUniqueApplicableQuestions());
	}

	private void assertImageBackedSharedContextExport(boolean mixed) throws Exception {
		Path pdfRoot = tempDir.resolve(mixed ? "mixed-pdf" : "image-only-pdf");
		Files.createDirectories(pdfRoot);
		if (mixed) {
			createContrastingPdf(pdfRoot.resolve("question.pdf"));
		}
		Subject subject = new Subject(1, "Chemistry");
		SyllabusVersion historical = new SyllabusVersion(2, subject, "2019", false);
		Unit originalUnit = new Unit(3, historical, "1", "Historical unit", 1);
		Topic originalTopic = new Topic(4, historical, originalUnit, "1.1", "Historical topic", 1);
		Descriptor classification = new Descriptor(5, historical, originalTopic, "1.1.1", "Historical descriptor", 1);
		SyllabusVersion current = new SyllabusVersion(6, subject, "2025", true);
		Unit currentUnit = new Unit(7, current, "1", "Current unit", 1);
		Topic currentTopic = new Topic(8, current, currentUnit, "1.1", "Current topic", 1);
		Descriptor currentDescriptor = new Descriptor(9, current, currentTopic, "1.1.1", "Current descriptor", 1);
		Exam exam = new Exam(10, subject, new ExamProvider(11, "QCAA"), 2019, "Exam");
		ExamBooklet booklet = new ExamBooklet(12, exam, "Paper 1", new SourceDocument(13, "question.pdf"));
		SourceQuestion source = new SourceQuestion(14, booklet, "24", SharedContextStatus.PRESENT);
		List<SharedContextContentPart> contextParts = mixed
				? List.of(new ImageSharedContextContentPart(createPng(40, 12, Color.RED)),
						new SharedQuestionContextRegion(1, 0.0, 0.25, 1.0, 0.25),
						new ImageSharedContextContentPart(createPng(35, 8, Color.GREEN)))
				: List.of(new ImageSharedContextContentPart(createPng(40, 12, Color.RED)));
		SharedQuestionContext context = SharedQuestionContext.fromContentParts(15, booklet, "Question 24 context",
				contextParts);
		QuestionRegion firstRegion = new QuestionRegion(booklet, 1, 0.0, 0.50, 1.0, 0.20);
		QuestionRegion secondRegion = new QuestionRegion(booklet, 1, 0.0, 0.75, 1.0, 0.20);
		List<QuestionRegion> firstRegions = mixed ? List.of(firstRegion) : List.of();
		List<QuestionRegion> secondRegions = mixed ? List.of(secondRegion) : List.of();
		List<QuestionContentPart> firstBody = mixed ? List.of(new PdfQuestionContentPart(firstRegion))
				: List.of(new ImageQuestionContentPart(createPng(32, 10, Color.BLUE)));
		List<QuestionContentPart> secondBody = mixed ? List.of(new PdfQuestionContentPart(secondRegion))
				: List.of(new ImageQuestionContentPart(createPng(30, 9, Color.BLUE)));
		Question partA = new Question(101, booklet, "24a", "", 2, firstRegions, classification, false, source, context,
				QuestionResponseType.WRITTEN_RESPONSE, firstBody);
		Question partB = new Question(102, booklet, "24b", "", 3, secondRegions, classification, false, source, context,
				QuestionResponseType.WRITTEN_RESPONSE, secondBody);
		partA.setAnswer(new Answer(201, "Answer A", List.of()));
		partB.setAnswer(new Answer(202, "Answer B", List.of()));
		InMemoryCurriculumRepository curriculum = new InMemoryCurriculumRepository(List.of(subject),
				List.of(historical, current),
				List.of(originalUnit, originalTopic, classification, currentUnit, currentTopic, currentDescriptor));
		QuestionRetrievalService retrieval = new QuestionRetrievalService(
				_ -> List.of(new QuestionApplicabilityMatch(partA, currentDescriptor),
						new QuestionApplicabilityMatch(partB, currentDescriptor)),
				new CurriculumSearchNodeExpansionService(curriculum));
		RevisionCorpusBuilder corpusBuilder = new RevisionCorpusBuilder(curriculum, retrieval,
				new InMemoryQuestionOutputApplicabilityRepository());
		PdfStore pdfStore = new PdfStore(pdfRoot);
		QuestionExtractor extractor = new QuestionExtractor();
		RevisionExportService service = new RevisionExportService(corpusBuilder, new RevisionPresentationPlanner(),
				new RevisionQuestionAssetRenderer(pdfStore, extractor),
				new RevisionSharedContextAssetRenderer(pdfStore, extractor),
				new RevisionAnswerAssetRenderer(pdfStore, extractor), new RevisionExportValidator());
		Path destination = tempDir.resolve(mixed ? "mixed-context-output" : "image-only-context-output");
		service.export(new RevisionExportRequest(subject, destination));
		String contextFile = "context-" + context.getId() + ".png";
		Path contextAsset = destination.resolve(Path.of("assets", "contexts", contextFile));
		assertTrue(Files.isRegularFile(contextAsset));
		BufferedImage contextImage = ImageIO.read(contextAsset.toFile());
		assertEquals(Color.RED.getRGB(), contextImage.getRGB(10, 5));
		if (mixed) {
			assertEquals(150, contextImage.getWidth());
			assertEquals(Color.BLUE.getRGB(), contextImage.getRGB(75, 20));
			assertEquals(Color.GREEN.getRGB(), contextImage.getRGB(10, contextImage.getHeight() - 4));
		} else {
			assertEquals(40, contextImage.getWidth());
			assertEquals(12, contextImage.getHeight());
			assertFalse(Files.exists(pdfRoot.resolve("question.pdf")));
		}
		Path topicHtml = destination
				.resolve(Path.of("units", "unit-" + currentUnit.getId(), "topic-" + currentTopic.getId() + ".html"));
		String html = Files.readString(topicHtml);
		assertEquals(1, countOccurrences(html, contextFile), "One Shared Context asset is referenced for both parts");
		assertTrue(html.indexOf(contextFile) < html.indexOf("question-101.png"));
		assertTrue(html.contains("question-101.png"));
		assertTrue(html.contains("question-102.png"));
		assertTrue(Files.isRegularFile(destination.resolve("assets/questions/question-101.png")));
		assertTrue(Files.isRegularFile(destination.resolve("assets/questions/question-102.png")));
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

	private void createContrastingPdf(Path destination) throws IOException {
		try (PDDocument document = new PDDocument()) {
			PDPage page = new PDPage(new PDRectangle(72, 72));
			document.addPage(page);
			try (PDPageContentStream content = new PDPageContentStream(document, page)) {

				// Blue Question body occupies the lower 75%.
				content.setNonStrokingColor(Color.BLUE);
				content.addRect(0, 0, 72, 54);
				content.fill();

				// Red Shared Context occupies the upper 25%.
				// PDF coordinates have their origin at the bottom.
				content.setNonStrokingColor(Color.RED);
				content.addRect(0, 54, 72, 18);
				content.fill();
			}
			document.save(destination.toFile());
		}
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

	private byte[] createPng(int width, int height, Color color) throws IOException {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				image.setRGB(x, y, color.getRGB());
			}
		}
		try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			if (!ImageIO.write(image, "png", output)) {
				throw new IOException("Test PNG writer unavailable");
			}
			return output.toByteArray();
		}
	}
}
