package au.edu.eq.questionbank.output.revision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.pdf.PdfStore;
import au.edu.eq.questionbank.pdf.QuestionExtractor;
import au.edu.eq.questionbank.repository.assessment.QuestionApplicabilityMatch;
import au.edu.eq.questionbank.repository.curriculum.InMemoryCurriculumRepository;
import au.edu.eq.questionbank.service.retrieval.CurriculumSearchNodeExpansionService;
import au.edu.eq.questionbank.service.retrieval.QuestionRetrievalService;
import au.edu.eq.questionbank.service.revision.RevisionCorpusBuilder;

class RevisionExportServiceIntegrationTest {

	@TempDir
	Path tempDir;

	@Test
	void exportsCompleteRevisionSiteFromStoredPdfRegions() throws Exception {
		Fixture fixture = new Fixture(tempDir);
		Path destination = tempDir.resolve("chemistry-revision");
		RevisionExportResult result = fixture.service.export(new RevisionExportRequest(fixture.chemistry, destination));
		Path subjectIndex = destination.resolve("index.html");
		Path unitIndex = destination.resolve(Path.of("units", "unit-10", "index.html"));
		Path topicFile = destination.resolve(Path.of("units", "unit-10", "topic-11.html"));
		Path stylesheet = destination.resolve(Path.of("assets", "revision.css"));
		Path questionImage = destination.resolve(Path.of("assets", "questions", "question-1.png"));
		Path answerImage = destination.resolve(Path.of("assets", "answers", "question-1-answer-01.png"));
		assertTrue(Files.isRegularFile(subjectIndex));
		assertTrue(Files.isRegularFile(unitIndex));
		assertTrue(Files.isRegularFile(topicFile));
		assertTrue(Files.isRegularFile(stylesheet));
		assertTrue(Files.isRegularFile(questionImage));
		assertTrue(Files.size(questionImage) > 0);
		assertTrue(Files.isRegularFile(answerImage));
		assertTrue(Files.size(answerImage) > 0);
		String topicHtml = Files.readString(topicFile);
		assertTrue(topicHtml.contains("src=\"../../assets/questions/question-1.png\""));
		assertTrue(topicHtml.contains("src=\"../../assets/answers/question-1-answer-01.png\""));
		assertTrue(topicHtml.contains("<summary>Reveal answer</summary>"));
		assertTrue(topicHtml.contains("Worked solution"));
		assertTrue(topicHtml.contains("Source: QCAA, 2022, Chemistry examination, Paper 1, Question 7"));
		assertTrue(topicHtml.contains("Question 1"));
		assertEquals(1, result.getStatistics().getApplicablePlacements());
		assertEquals(1, result.getStatistics().getUniqueApplicableQuestions());
		assertEquals(1, result.getStatistics().getRenderableQuestions());
		assertEquals(1, result.getStatistics().getQuestionsWithAnswers());
		assertEquals(destination.toAbsolutePath().normalize(), result.getDestination());
		assertFalse(hasStagingDirectory(destination));
	}

	@Test
	void failedAnswerRenderingDoesNotPublishDestination() throws Exception {
		Fixture fixture = new Fixture(tempDir);
		Files.delete(fixture.answerPdf);
		Path destination = tempDir.resolve("failed-export");
		assertThrows(IOException.class,
				() -> fixture.service.export(new RevisionExportRequest(fixture.chemistry, destination)));
		assertFalse(Files.exists(destination));
		assertFalse(hasStagingDirectory(destination));
	}

	private boolean hasStagingDirectory(Path destination) throws IOException {
		Path parent = destination.getParent();
		String prefix = destination.getFileName() + ".staging-";
		try (java.util.stream.Stream<Path> children = Files.list(parent)) {
			return children.anyMatch(path -> path.getFileName().toString().startsWith(prefix));
		}
	}

	private static final class Fixture {

		private final Subject chemistry;
		private final Path answerPdf;
		private final RevisionExportService service;

		private Fixture(Path tempDir) throws IOException {
			Path pdfRoot = tempDir.resolve("pdf");
			Files.createDirectories(pdfRoot);
			Path questionPdf = pdfRoot.resolve("question.pdf");
			answerPdf = pdfRoot.resolve("answer.pdf");
			createPdf(questionPdf, Color.WHITE);
			createPdf(answerPdf, Color.LIGHT_GRAY);
			chemistry = new Subject(1, "Chemistry");
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
			SourceDocument questionSource = new SourceDocument(1, "question.pdf");
			ExamBooklet booklet = new ExamBooklet(1, exam, "Paper 1", questionSource);
			QuestionRegion questionRegion = new QuestionRegion(booklet, 1, 0.0, 0.0, 1.0, 1.0);
			Question question = new Question(1, booklet, "7", "", 2, List.of(questionRegion), historicalDescriptor,
					false);
			SourceDocument answerSource = new SourceDocument(2, "answer.pdf");
			AnswerFile answerFile = new AnswerFile(1, exam, "Marking guide", answerSource);
			AnswerRegion answerRegion = new AnswerRegion(answerFile, 1, 0.0, 0.0, 1.0, 1.0);
			question.setAnswer(new Answer(1, "Worked solution", List.of(answerRegion)));
			InMemoryCurriculumRepository repository = new InMemoryCurriculumRepository(List.of(chemistry),
					List.of(historical, current), List.of(historicalUnit, historicalTopic, historicalDescriptor,
							currentUnit, currentTopic, currentDescriptor));
			CurriculumSearchNodeExpansionService expansion = new CurriculumSearchNodeExpansionService(repository);
			QuestionRetrievalService retrieval = new QuestionRetrievalService(
					currentNodes -> List.of(new QuestionApplicabilityMatch(question, currentDescriptor)), expansion);
			RevisionCorpusBuilder corpusBuilder = new RevisionCorpusBuilder(repository, retrieval);
			PdfStore pdfStore = new PdfStore(pdfRoot);
			QuestionExtractor extractor = new QuestionExtractor();
			service = new RevisionExportService(corpusBuilder, new RevisionQuestionAssetRenderer(pdfStore, extractor),
					new RevisionAnswerAssetRenderer(pdfStore, extractor), new RevisionExportValidator());
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
	}
}
