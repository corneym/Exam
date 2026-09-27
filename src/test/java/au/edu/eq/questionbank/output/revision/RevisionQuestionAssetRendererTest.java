package au.edu.eq.questionbank.output.revision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.Descriptor;
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
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.pdf.PdfStore;
import au.edu.eq.questionbank.pdf.QuestionExtractor;
import au.edu.eq.questionbank.repository.assessment.InMemoryQuestionOutputApplicabilityRepository;
import au.edu.eq.questionbank.repository.assessment.QuestionApplicabilityMatch;
import au.edu.eq.questionbank.repository.assessment.QuestionRetrievalRepository;
import au.edu.eq.questionbank.repository.curriculum.InMemoryCurriculumRepository;
import au.edu.eq.questionbank.service.retrieval.CurriculumSearchNodeExpansionService;
import au.edu.eq.questionbank.service.retrieval.QuestionRetrievalService;
import au.edu.eq.questionbank.service.revision.RevisionCorpus;
import au.edu.eq.questionbank.service.revision.RevisionCorpusBuilder;

class RevisionQuestionAssetRendererTest {

	@TempDir
	Path tempDir;

	@Test
	void doesNotRenderMetadataOnlyQuestionWithoutContent() throws Exception {
		Fixture fixture = new Fixture(tempDir);
		QuestionRetrievalRepository retrievalRepository = _ -> List
				.of(new QuestionApplicabilityMatch(fixture.metadataOnlyQuestion, fixture.firstDescriptor));
		RevisionCorpus corpus = fixture.createCorpus(retrievalRepository);
		Path outputRoot = tempDir.resolve("output");
		RevisionQuestionAssetRenderer renderer = new RevisionQuestionAssetRenderer(fixture.pdfStore,
				new QuestionExtractor());
		List<RevisionQuestionAsset> assets = renderer.render(corpus, outputRoot);
		assertEquals(List.of(), assets);
		assertFalse(Files.exists(outputRoot.resolve(Path.of("assets", "questions", "question-1.png"))));
	}

	@Test
	void failsWhenRenderableQuestionSourcePdfIsMissing() throws Exception {
		Fixture fixture = new Fixture(tempDir);
		Files.delete(fixture.pdfStore
				.resolve(fixture.renderableQuestion.getBooklet().getSourceDocument().getRelativePath()));
		QuestionRetrievalRepository retrievalRepository = _ -> List
				.of(new QuestionApplicabilityMatch(fixture.renderableQuestion, fixture.firstDescriptor));
		RevisionCorpus corpus = fixture.createCorpus(retrievalRepository);
		Path outputRoot = tempDir.resolve("output");
		RevisionQuestionAssetRenderer renderer = new RevisionQuestionAssetRenderer(fixture.pdfStore,
				new QuestionExtractor());
		IOException exception = assertThrows(IOException.class, () -> renderer.render(corpus, outputRoot));
		assertTrue(exception.getMessage().contains("Question source PDF is not available"));
		assertTrue(exception.getMessage().contains("2"));
	}

	@Test
	void renderedRevisionAssetContainsQuestionBodyOnly() throws Exception {
		Fixture fixture = new Fixture(tempDir);
		ExamBooklet booklet = fixture.renderableQuestion.getBooklet();
		SharedQuestionContext sharedContext = new SharedQuestionContext(50, booklet, "Shared stem",
				List.of(new SharedQuestionContextRegion(1, 0.0, 0.0, 1.0, 0.5)));
		Question question = new Question(3, booklet, "2a", "", 3,
				List.of(new QuestionRegion(booklet, 1, 0.0, 0.75, 1.0, 0.25)),
				fixture.renderableQuestion.getClassification(), false, null, sharedContext);
		QuestionRetrievalRepository retrievalRepository = _ -> List
				.of(new QuestionApplicabilityMatch(question, fixture.firstDescriptor));
		RevisionCorpus corpus = fixture.createCorpus(retrievalRepository);
		Path outputRoot = tempDir.resolve("question-body-output");
		RevisionQuestionAssetRenderer renderer = new RevisionQuestionAssetRenderer(fixture.pdfStore,
				new QuestionExtractor());
		List<RevisionQuestionAsset> assets = renderer.render(corpus, outputRoot);
		assertEquals(1, assets.size());
		Path renderedFile = outputRoot.resolve(assets.getFirst().getRelativePath());
		BufferedImage image = ImageIO.read(renderedFile.toFile());
		assertEquals(150, image.getWidth());
		assertEquals(38, image.getHeight());
	}

	@Test
	void rendersImageOnlyQuestionWithoutSourcePdf() throws Exception {
		Fixture fixture = new Fixture(tempDir);
		ExamBooklet booklet = fixture.renderableQuestion.getBooklet();
		byte[] imageBytes = createPng(80, 40, Color.ORANGE);
		Question imageQuestion = new Question(20, booklet, "Image", "", 2, List.of(),
				fixture.renderableQuestion.getClassification(), false, null, null,
				QuestionResponseType.WRITTEN_RESPONSE, List.of(new ImageQuestionContentPart(imageBytes)));

		// Prove that revision rendering does not unnecessarily depend on the original
		// PDF when the entire Question body is persisted as an image.
		Files.delete(fixture.pdfStore.resolve(booklet.getSourceDocument().getRelativePath()));
		QuestionRetrievalRepository retrievalRepository = _ -> List
				.of(new QuestionApplicabilityMatch(imageQuestion, fixture.firstDescriptor));
		RevisionCorpus corpus = fixture.createCorpus(retrievalRepository);
		Path outputRoot = tempDir.resolve("image-only-output");
		RevisionQuestionAssetRenderer renderer = new RevisionQuestionAssetRenderer(fixture.pdfStore,
				new QuestionExtractor());
		List<RevisionQuestionAsset> assets = renderer.render(corpus, outputRoot);
		assertEquals(1, assets.size());
		Path renderedFile = outputRoot.resolve(assets.getFirst().getRelativePath());
		BufferedImage image = ImageIO.read(renderedFile.toFile());
		assertEquals(80, image.getWidth());
		assertEquals(40, image.getHeight());
		assertEquals(Color.ORANGE.getRGB(), image.getRGB(40, 20));
	}

	@Test
	void rendersMixedImageAndPdfQuestionBodyInStoredOrder() throws Exception {
		Fixture fixture = new Fixture(tempDir);
		ExamBooklet booklet = fixture.renderableQuestion.getBooklet();
		QuestionRegion pdfRegion = new QuestionRegion(booklet, 1, 0.0, 0.5, 1.0, 0.5);
		byte[] replacementCopyrightImage = createPng(100, 30, Color.RED);
		Question mixedQuestion = new Question(21, booklet, "Mixed", "", 1, List.of(pdfRegion),
				fixture.renderableQuestion.getClassification(), false, null, null, QuestionResponseType.MULTIPLE_CHOICE,
				List.of(new ImageQuestionContentPart(replacementCopyrightImage),
						new PdfQuestionContentPart(pdfRegion)));
		QuestionRetrievalRepository retrievalRepository = _ -> List
				.of(new QuestionApplicabilityMatch(mixedQuestion, fixture.firstDescriptor));
		RevisionCorpus corpus = fixture.createCorpus(retrievalRepository);
		Path outputRoot = tempDir.resolve("mixed-output");
		RevisionQuestionAssetRenderer renderer = new RevisionQuestionAssetRenderer(fixture.pdfStore,
				new QuestionExtractor());
		List<RevisionQuestionAsset> assets = renderer.render(corpus, outputRoot);
		assertEquals(1, assets.size());
		BufferedImage rendered = ImageIO.read(outputRoot.resolve(assets.getFirst().getRelativePath()).toFile());

		// The stored clipboard image is first and the PDF crop follows it.
		assertEquals(150, rendered.getWidth());
		assertEquals(105, rendered.getHeight());
		assertEquals(Color.RED.getRGB(), rendered.getRGB(50, 15));
		assertEquals(Color.WHITE.getRGB(), rendered.getRGB(75, 70));
	}

	@Test
	void rendersOneDeterministicAssetPerUniqueRenderableQuestion() throws Exception {
		Fixture fixture = new Fixture(tempDir);
		QuestionRetrievalRepository retrievalRepository = _ -> List.of(
				new QuestionApplicabilityMatch(fixture.renderableQuestion, fixture.firstDescriptor),
				new QuestionApplicabilityMatch(fixture.renderableQuestion, fixture.secondDescriptor));
		RevisionCorpus corpus = fixture.createCorpus(retrievalRepository);
		Path outputRoot = tempDir.resolve("output");
		RevisionQuestionAssetRenderer renderer = new RevisionQuestionAssetRenderer(fixture.pdfStore,
				new QuestionExtractor());
		List<RevisionQuestionAsset> assets = renderer.render(corpus, outputRoot);
		assertEquals(1, assets.size());
		RevisionQuestionAsset asset = assets.get(0);
		assertSame(fixture.renderableQuestion, asset.getQuestion());
		assertEquals(Path.of("assets", "questions", "question-2.png"), asset.getRelativePath());
		Path renderedFile = outputRoot.resolve(asset.getRelativePath());
		assertTrue(Files.isRegularFile(renderedFile));
		assertTrue(Files.size(renderedFile) > 0);
	}

	@Test
	void rendersSharedContextOnceWhenUsedByMultipleQuestions() throws Exception {
		Fixture fixture = new Fixture(tempDir);
		ExamBooklet booklet = fixture.renderableQuestion.getBooklet();
		SharedQuestionContext sharedContext = new SharedQuestionContext(50, booklet, "Shared stem",
				List.of(new SharedQuestionContextRegion(1, 0.0, 0.0, 1.0, 0.5)));
		Question firstQuestion = new Question(3, booklet, "24a", "", 2,
				List.of(new QuestionRegion(booklet, 1, 0.0, 0.5, 1.0, 0.25)),
				fixture.renderableQuestion.getClassification(), false, null, sharedContext);
		Question secondQuestion = new Question(4, booklet, "24b", "", 3,
				List.of(new QuestionRegion(booklet, 1, 0.0, 0.75, 1.0, 0.25)),
				fixture.renderableQuestion.getClassification(), false, null, sharedContext);
		QuestionRetrievalRepository retrievalRepository = _ -> List.of(
				new QuestionApplicabilityMatch(firstQuestion, fixture.firstDescriptor),
				new QuestionApplicabilityMatch(secondQuestion, fixture.firstDescriptor));
		RevisionCorpus corpus = fixture.createCorpus(retrievalRepository);
		Path outputRoot = tempDir.resolve("context-output");
		RevisionSharedContextAssetRenderer renderer = new RevisionSharedContextAssetRenderer(fixture.pdfStore,
				new QuestionExtractor());
		List<RevisionSharedContextAsset> assets = renderer.render(corpus, outputRoot);
		assertEquals(1, assets.size());
		RevisionSharedContextAsset asset = assets.getFirst();
		assertSame(sharedContext, asset.getSharedContext());
		assertEquals(Path.of("assets", "contexts", "context-50.png"), asset.getRelativePath());
		Path renderedFile = outputRoot.resolve(asset.getRelativePath());
		assertTrue(Files.isRegularFile(renderedFile));
		BufferedImage image = ImageIO.read(renderedFile.toFile());
		assertEquals(150, image.getWidth());
		assertEquals(75, image.getHeight());
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

	private static final class Fixture {

		private final Subject chemistry;
		private final SyllabusVersion currentVersion;
		private final Descriptor firstDescriptor;
		private final Descriptor secondDescriptor;
		private final Question metadataOnlyQuestion;
		private final Question renderableQuestion;
		private final InMemoryCurriculumRepository curriculumRepository;
		private final PdfStore pdfStore;

		private Fixture(Path tempDir) throws IOException {
			Path pdfRoot = tempDir.resolve("pdf");
			Files.createDirectories(pdfRoot);
			Path sourcePdf = pdfRoot.resolve("source.pdf");
			createPdf(sourcePdf);
			pdfStore = new PdfStore(pdfRoot);
			chemistry = new Subject(1, "Chemistry");
			SyllabusVersion historicalVersion = new SyllabusVersion(1, chemistry, "2019", false);
			Unit historicalUnit = new Unit(100, historicalVersion, "1", "Historical unit", 1);
			Topic historicalTopic = new Topic(101, historicalVersion, historicalUnit, "1.1", "Historical topic", 1);
			Descriptor historicalDescriptor = new Descriptor(102, historicalVersion, historicalTopic, "1.1.1",
					"Historical descriptor", 1);
			currentVersion = new SyllabusVersion(2, chemistry, "2025", true);
			Unit currentUnit = new Unit(10, currentVersion, "1", "Current unit", 1);
			Topic currentTopic = new Topic(11, currentVersion, currentUnit, "1.1", "Current topic", 1);
			firstDescriptor = new Descriptor(12, currentVersion, currentTopic, "1.1.1", "First descriptor", 1);
			secondDescriptor = new Descriptor(13, currentVersion, currentTopic, "1.1.2", "Second descriptor", 2);
			ExamProvider provider = new ExamProvider(1, "QCAA");
			Exam exam = new Exam(1, chemistry, provider, 2022, "Chemistry examination");
			SourceDocument sourceDocument = new SourceDocument(1, "source.pdf");
			ExamBooklet booklet = new ExamBooklet(1, exam, "Paper 1", sourceDocument);
			metadataOnlyQuestion = new Question(1, booklet, "1", "", 2, List.of(), historicalDescriptor, false);
			QuestionRegion region = new QuestionRegion(booklet, 1, 0.0, 0.0, 1.0, 1.0);
			renderableQuestion = new Question(2, booklet, "2", "", 3, List.of(region), historicalDescriptor, false);
			curriculumRepository = new InMemoryCurriculumRepository(List.of(chemistry),
					List.of(historicalVersion, currentVersion), List.of(historicalUnit, historicalTopic,
							historicalDescriptor, currentUnit, currentTopic, firstDescriptor, secondDescriptor));
		}

		private static void createPdf(Path destination) throws IOException {
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

		private RevisionCorpus createCorpus(QuestionRetrievalRepository retrievalRepository) {
			CurriculumSearchNodeExpansionService expansionService = new CurriculumSearchNodeExpansionService(
					curriculumRepository);
			QuestionRetrievalService retrievalService = new QuestionRetrievalService(retrievalRepository,
					expansionService);

			// These renderer fixtures intentionally exercise the default state in which
			// every curriculum-derived placement remains included.
			RevisionCorpusBuilder builder = new RevisionCorpusBuilder(curriculumRepository, retrievalService,
					new InMemoryQuestionOutputApplicabilityRepository());
			return builder.build(chemistry);
		}
	}
}
