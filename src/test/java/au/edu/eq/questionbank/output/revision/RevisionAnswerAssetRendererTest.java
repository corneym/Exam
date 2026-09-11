package au.edu.eq.questionbank.output.revision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.Answer;
import au.edu.eq.questionbank.model.AnswerFile;
import au.edu.eq.questionbank.model.AnswerRegion;
import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.pdf.PdfStore;
import au.edu.eq.questionbank.pdf.QuestionExtractor;
import au.edu.eq.questionbank.repository.assessment.QuestionApplicabilityMatch;
import au.edu.eq.questionbank.repository.assessment.QuestionRetrievalRepository;
import au.edu.eq.questionbank.repository.curriculum.InMemoryCurriculumRepository;
import au.edu.eq.questionbank.service.retrieval.CurriculumSearchNodeExpansionService;
import au.edu.eq.questionbank.service.retrieval.QuestionRetrievalService;
import au.edu.eq.questionbank.service.revision.RevisionCorpus;
import au.edu.eq.questionbank.service.revision.RevisionCorpusBuilder;

class RevisionAnswerAssetRendererTest {

	@TempDir
	Path tempDir;

	@Test
	void failsWhenAnswerRegionSourcePdfIsMissing() throws Exception {
		Fixture fixture = new Fixture(tempDir);
		Files.delete(fixture.firstAnswerPdf);
		RevisionCorpus corpus = fixture.createCorpus(_ -> List
				.of(new QuestionApplicabilityMatch(fixture.regionAnswerQuestion, fixture.firstDescriptor)));
		RevisionAnswerAssetRenderer renderer = new RevisionAnswerAssetRenderer(fixture.pdfStore,
				new QuestionExtractor());
		IOException exception = assertThrows(IOException.class,
				() -> renderer.render(corpus, tempDir.resolve("output")));
		assertTrue(exception.getMessage().contains("Answer source PDF is not available"));
		assertTrue(exception.getMessage().contains("question 2"));
	}

	@Test
	void multipleCurriculumPlacementsRenderOneSetOfAnswerAssets() throws Exception {
		Fixture fixture = new Fixture(tempDir);
		QuestionRetrievalRepository retrievalRepository = _ -> List.of(
				new QuestionApplicabilityMatch(fixture.regionAnswerQuestion, fixture.firstDescriptor),
				new QuestionApplicabilityMatch(fixture.regionAnswerQuestion, fixture.secondDescriptor));
		RevisionCorpus corpus = fixture.createCorpus(retrievalRepository);
		RevisionAnswerAssetRenderer renderer = new RevisionAnswerAssetRenderer(fixture.pdfStore,
				new QuestionExtractor());
		List<RevisionAnswerAsset> assets = renderer.render(corpus, tempDir.resolve("output"));
		assertEquals(2, assets.size());
	}

	@Test
	void rendersAnswerRegionsInPersistedOrderWithDeterministicNames() throws Exception {
		Fixture fixture = new Fixture(tempDir);
		RevisionCorpus corpus = fixture.createCorpus(_ -> List
				.of(new QuestionApplicabilityMatch(fixture.regionAnswerQuestion, fixture.firstDescriptor)));
		RevisionAnswerAssetRenderer renderer = new RevisionAnswerAssetRenderer(fixture.pdfStore,
				new QuestionExtractor());
		Path outputRoot = tempDir.resolve("output");
		List<RevisionAnswerAsset> assets = renderer.render(corpus, outputRoot);
		assertEquals(2, assets.size());
		assertSame(fixture.regionAnswerQuestion, assets.get(0).getQuestion());
		assertEquals(1, assets.get(0).getRegionNumber());
		assertEquals(Path.of("assets", "answers", "question-2-answer-01.png"), assets.get(0).getRelativePath());
		assertEquals(2, assets.get(1).getRegionNumber());
		assertEquals(Path.of("assets", "answers", "question-2-answer-02.png"), assets.get(1).getRelativePath());
		assertTrue(Files.size(outputRoot.resolve(assets.get(0).getRelativePath())) > 0);
		assertTrue(Files.size(outputRoot.resolve(assets.get(1).getRelativePath())) > 0);
	}

	@Test
	void textOnlyAnswerProducesNoImageAssets() throws Exception {
		Fixture fixture = new Fixture(tempDir);
		RevisionCorpus corpus = fixture.createCorpus(_ -> List
				.of(new QuestionApplicabilityMatch(fixture.textAnswerQuestion, fixture.firstDescriptor)));
		RevisionAnswerAssetRenderer renderer = new RevisionAnswerAssetRenderer(fixture.pdfStore,
				new QuestionExtractor());
		List<RevisionAnswerAsset> assets = renderer.render(corpus, tempDir.resolve("output"));
		assertEquals(List.of(), assets);
		assertEquals("B", fixture.textAnswerQuestion.getAnswer().getAnswerText());
	}

	private static final class Fixture {

		private final Subject chemistry;
		private final Descriptor firstDescriptor;
		private final Descriptor secondDescriptor;
		private final Question textAnswerQuestion;
		private final Question regionAnswerQuestion;
		private final InMemoryCurriculumRepository curriculumRepository;
		private final PdfStore pdfStore;
		private final Path firstAnswerPdf;

		private Fixture(Path tempDir) throws IOException {
			Path pdfRoot = tempDir.resolve("pdf");
			Files.createDirectories(pdfRoot);
			firstAnswerPdf = pdfRoot.resolve("answers-1.pdf");
			Path secondAnswerPdf = pdfRoot.resolve("answers-2.pdf");
			createPdf(firstAnswerPdf, Color.RED);
			createPdf(secondAnswerPdf, Color.BLUE);
			pdfStore = new PdfStore(pdfRoot);
			chemistry = new Subject(1, "Chemistry");
			SyllabusVersion historicalVersion = new SyllabusVersion(1, chemistry, "2019", false);
			Unit historicalUnit = new Unit(100, historicalVersion, "1", "Historical unit", 1);
			Topic historicalTopic = new Topic(101, historicalVersion, historicalUnit, "1.1", "Historical topic", 1);
			Descriptor historicalDescriptor = new Descriptor(102, historicalVersion, historicalTopic, "1.1.1",
					"Historical descriptor", 1);
			SyllabusVersion currentVersion = new SyllabusVersion(2, chemistry, "2025", true);
			Unit currentUnit = new Unit(10, currentVersion, "1", "Current unit", 1);
			Topic currentTopic = new Topic(11, currentVersion, currentUnit, "1.1", "Current topic", 1);
			firstDescriptor = new Descriptor(12, currentVersion, currentTopic, "1.1.1", "First descriptor", 1);
			secondDescriptor = new Descriptor(13, currentVersion, currentTopic, "1.1.2", "Second descriptor", 2);
			ExamProvider provider = new ExamProvider(1, "QCAA");
			Exam exam = new Exam(1, chemistry, provider, 2022, "Chemistry examination");
			SourceDocument questionSource = new SourceDocument(1, "question.pdf");
			ExamBooklet booklet = new ExamBooklet(1, exam, "Paper 1", questionSource);
			textAnswerQuestion = new Question(1, booklet, "1", "", 1, List.of(), historicalDescriptor, false);
			textAnswerQuestion.setAnswer(new Answer(1, "B", List.of()));
			SourceDocument firstAnswerDocument = new SourceDocument(2, "answers-1.pdf");
			SourceDocument secondAnswerDocument = new SourceDocument(3, "answers-2.pdf");
			AnswerFile firstAnswerFile = new AnswerFile(1, exam, "Marking guide 1", firstAnswerDocument);
			AnswerFile secondAnswerFile = new AnswerFile(2, exam, "Marking guide 2", secondAnswerDocument);
			AnswerRegion firstRegion = new AnswerRegion(firstAnswerFile, 1, 0.0, 0.0, 1.0, 1.0);
			AnswerRegion secondRegion = new AnswerRegion(secondAnswerFile, 1, 0.0, 0.0, 1.0, 1.0);
			regionAnswerQuestion = new Question(2, booklet, "2", "", 2, List.of(), historicalDescriptor, false);
			regionAnswerQuestion.setAnswer(new Answer(2, "Worked solution", List.of(firstRegion, secondRegion)));
			curriculumRepository = new InMemoryCurriculumRepository(List.of(chemistry),
					List.of(historicalVersion, currentVersion), List.of(historicalUnit, historicalTopic,
							historicalDescriptor, currentUnit, currentTopic, firstDescriptor, secondDescriptor));
		}

		private static void createPdf(Path destination, Color colour) throws IOException {
			try (PDDocument document = new PDDocument()) {
				PDPage page = new PDPage(new PDRectangle(72, 72));
				document.addPage(page);
				try (PDPageContentStream content = new PDPageContentStream(document, page)) {
					content.setNonStrokingColor(colour);
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
			RevisionCorpusBuilder builder = new RevisionCorpusBuilder(curriculumRepository, retrievalService);
			return builder.build(chemistry);
		}
	}
}
