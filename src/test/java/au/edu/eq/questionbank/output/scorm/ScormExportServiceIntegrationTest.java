package au.edu.eq.questionbank.output.scorm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

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
import au.edu.eq.questionbank.output.revision.RevisionAnswerAssetRenderer;
import au.edu.eq.questionbank.output.revision.RevisionExportService;
import au.edu.eq.questionbank.output.revision.RevisionExportValidator;
import au.edu.eq.questionbank.output.revision.RevisionQuestionAssetRenderer;
import au.edu.eq.questionbank.output.revision.RevisionSharedContextAssetRenderer;
import au.edu.eq.questionbank.pdf.PdfStore;
import au.edu.eq.questionbank.pdf.QuestionExtractor;
import au.edu.eq.questionbank.repository.assessment.QuestionApplicabilityMatch;
import au.edu.eq.questionbank.repository.curriculum.InMemoryCurriculumRepository;
import au.edu.eq.questionbank.service.retrieval.CurriculumSearchNodeExpansionService;
import au.edu.eq.questionbank.service.retrieval.QuestionRetrievalService;
import au.edu.eq.questionbank.service.revision.RevisionCorpusBuilder;
import au.edu.eq.questionbank.service.revision.RevisionPresentationPlanner;

class ScormExportServiceIntegrationTest {

	@TempDir
	Path tempDir;

	@Test
	void exportsCompleteScormZipFromStoredPdfRegions() throws Exception {
		Fixture fixture = new Fixture(tempDir);
		Path destination = tempDir.resolve("Chemistry.zip");
		ScormExportResult result = fixture.service.export(new ScormExportRequest(fixture.chemistry, destination));
		assertTrue(Files.isRegularFile(destination));
		assertEquals(destination.toAbsolutePath().normalize(), result.getDestination());
		assertEquals(1, result.getStatistics().getApplicablePlacements());
		assertEquals(1, result.getStatistics().getUniqueApplicableQuestions());
		assertEquals(1, result.getStatistics().getRenderableQuestions());
		assertEquals(1, result.getStatistics().getQuestionsWithAnswers());
		try (ZipFile zip = new ZipFile(destination.toFile(), StandardCharsets.UTF_8)) {
			assertTrue(zip.getEntry("imsmanifest.xml") != null);
			assertTrue(zip.getEntry("index.html") != null);
			assertTrue(zip.getEntry("assets/revision.css") != null);
			assertTrue(zip.getEntry("assets/questions/question-1.png") != null);
			assertTrue(zip.getEntry("assets/answers/question-1-answer-01.png") != null);
			assertTrue(zip.getEntry("units/unit-10/index.html") != null);
			assertTrue(zip.getEntry("units/unit-10/topic-11.html") != null);
			assertTrue(zip.getEntry("adlcp_rootv1p2.xsd") != null);
			assertTrue(zip.getEntry("ims_xml.xsd") != null);
			assertTrue(zip.getEntry("imscp_rootv1p1p2.xsd") != null);
			assertTrue(zip.getEntry("imsmd_rootv1p2p1.xsd") != null);
			String manifest = new String(zip.getInputStream(zip.getEntry("imsmanifest.xml")).readAllBytes(),
					StandardCharsets.UTF_8);
			assertTrue(manifest.contains("<schemaversion>1.2</schemaversion>"));
			assertTrue(manifest.contains("href=\"index.html\""));
			assertTrue(manifest.contains("<file href=\"index.html\""));
			assertTrue(manifest.contains("<file href=\"assets/revision.css\""));
			assertTrue(manifest.contains("<file href=\"assets/questions/question-1.png\""));
			assertTrue(manifest.contains("<file href=\"assets/answers/question-1-answer-01.png\""));
			assertTrue(manifest.contains("<file href=\"units/unit-10/topic-11.html\""));
			assertFalse(manifest.contains("<file href=\"adlcp_rootv1p2.xsd\""));
			assertFalse(manifest.contains("<file href=\"ims_xml.xsd\""));
		}
		assertFalse(hasScormWorkspace());
	}

	@Test
	void rejectsExistingDestinationWithoutReplacingIt() throws Exception {
		Fixture fixture = new Fixture(tempDir);
		Path destination = tempDir.resolve("Chemistry.zip");
		Files.writeString(destination, "existing");
		assertThrows(IOException.class,
				() -> fixture.service.export(new ScormExportRequest(fixture.chemistry, destination)));
		assertEquals("existing", Files.readString(destination));
		assertFalse(hasScormWorkspace());
	}

	@Test
	void staticRevisionFailureDoesNotPublishScormZip() throws Exception {
		Fixture fixture = new Fixture(tempDir);
		Files.delete(fixture.answerPdf);
		Path destination = tempDir.resolve("Chemistry.zip");
		assertThrows(IOException.class,
				() -> fixture.service.export(new ScormExportRequest(fixture.chemistry, destination)));
		assertFalse(Files.exists(destination));
		assertFalse(hasScormWorkspace());
	}

	private boolean hasScormWorkspace() throws IOException {
		try (java.util.stream.Stream<Path> children = Files.list(tempDir)) {
			return children.anyMatch(path -> path.getFileName().toString().startsWith(".scorm-work-"));
		}
	}

	private static final class Fixture {

		private final Subject chemistry;
		private final Path answerPdf;
		private final ScormExportService service;

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
			RevisionExportService revisionExportService = new RevisionExportService(corpusBuilder,
					new RevisionPresentationPlanner(), new RevisionQuestionAssetRenderer(pdfStore, extractor),
					new RevisionSharedContextAssetRenderer(pdfStore, extractor),
					new RevisionAnswerAssetRenderer(pdfStore, extractor), new RevisionExportValidator());
			service = new ScormExportService(revisionExportService, new ScormManifestWriter(), new ScormSchemaSupport(),
					new ScormPackageValidator(), new ScormZipWriter());
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
