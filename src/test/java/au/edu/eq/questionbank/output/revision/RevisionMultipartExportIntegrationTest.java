package au.edu.eq.questionbank.output.revision;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.SharedContextStatus;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
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
import au.edu.eq.questionbank.repository.assessment.QuestionApplicabilityMatch;
import au.edu.eq.questionbank.repository.curriculum.InMemoryCurriculumRepository;
import au.edu.eq.questionbank.service.retrieval.CurriculumSearchNodeExpansionService;
import au.edu.eq.questionbank.service.retrieval.QuestionRetrievalService;
import au.edu.eq.questionbank.service.revision.RevisionCorpusBuilder;
import au.edu.eq.questionbank.service.revision.RevisionPresentationPlanner;

class RevisionMultipartExportIntegrationTest {

	@TempDir
	Path tempDir;

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
		SharedQuestionContext sharedContext = new SharedQuestionContext(50, booklet, "Question 24 preamble",
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
		RevisionCorpusBuilder corpusBuilder = new RevisionCorpusBuilder(curriculumRepository, retrievalService);
		PdfStore pdfStore = new PdfStore(pdfRoot);
		QuestionExtractor extractor = new QuestionExtractor();
		RevisionExportService service = new RevisionExportService(corpusBuilder, new RevisionPresentationPlanner(),
				new RevisionQuestionAssetRenderer(pdfStore, extractor),
				new RevisionSharedContextAssetRenderer(pdfStore, extractor),
				new RevisionAnswerAssetRenderer(pdfStore, extractor), new RevisionExportValidator());
		Path destination = tempDir.resolve("revision-output");
		RevisionExportResult result = service.export(new RevisionExportRequest(chemistry, destination));
		Path topicFile = destination.resolve(Path.of("units", "unit-10", "topic-11.html"));
		Path contextImage = destination.resolve(Path.of("assets", "contexts", "context-50.png"));
		Path partAImage = destination.resolve(Path.of("assets", "questions", "question-4.png"));
		Path partBImage = destination.resolve(Path.of("assets", "questions", "question-5.png"));
		assertTrue(Files.isRegularFile(topicFile));
		assertTrue(Files.isRegularFile(contextImage));
		assertTrue(Files.isRegularFile(partAImage));
		assertTrue(Files.isRegularFile(partBImage));
		String html = Files.readString(topicFile);
		assertEquals(1, countOccurrences(html, "context-50.png"));
		assertEquals(1, countOccurrences(html, "<summary>Reveal answer</summary>"));
		assertTrue(html.contains("Question 1"));
		assertTrue(html.contains("5 marks"));
		assertTrue(html.contains("Source part 24a"));
		assertTrue(html.contains("Source part 24b"));
		assertTrue(html.contains("question-4.png"));
		assertTrue(html.contains("question-5.png"));
		assertTrue(html.contains("Answer A"));
		assertTrue(html.contains("Answer B"));
		assertEquals(2, result.getStatistics().getUniqueApplicableQuestions());
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
}
