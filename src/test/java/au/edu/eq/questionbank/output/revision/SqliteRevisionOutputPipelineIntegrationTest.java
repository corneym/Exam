package au.edu.eq.questionbank.output.revision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipFile;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.output.scorm.ScormExportRequest;
import au.edu.eq.questionbank.output.scorm.ScormExportResult;
import au.edu.eq.questionbank.output.scorm.ScormExportService;
import au.edu.eq.questionbank.output.scorm.ScormManifestWriter;
import au.edu.eq.questionbank.output.scorm.ScormPackageValidator;
import au.edu.eq.questionbank.output.scorm.ScormSchemaSupport;
import au.edu.eq.questionbank.output.scorm.ScormZipWriter;
import au.edu.eq.questionbank.pdf.PdfStore;
import au.edu.eq.questionbank.pdf.QuestionExtractor;
import au.edu.eq.questionbank.repository.assessment.SqliteExamImporter;
import au.edu.eq.questionbank.repository.assessment.SqliteExamWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteQuestionOutputApplicabilityRepository;
import au.edu.eq.questionbank.repository.assessment.SqliteQuestionRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumMappingReviewWriter;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.retrieval.CurriculumSearchNodeExpansionService;
import au.edu.eq.questionbank.service.retrieval.QuestionRetrievalService;
import au.edu.eq.questionbank.service.revision.RevisionCorpus;
import au.edu.eq.questionbank.service.revision.RevisionCorpusBuilder;
import au.edu.eq.questionbank.service.revision.RevisionPresentationPlanner;

class SqliteRevisionOutputPipelineIntegrationTest {

	@TempDir
	Path tempDir;

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

	@Test
	void persistedMappingAndExclusionFlowThroughCorpusHtmlAndScormAfterReopen() throws Exception {
		Fixture fixture = createPersistedFixture();

		// Reopen the database so the export pipeline cannot rely on any in-memory
		// objects used while the fixture was originally persisted.
		SqliteDatabase reopenedDatabase = new SqliteDatabase(fixture.databasePath());
		reopenedDatabase.initialiseSchema();
		SqliteCurriculumRepository curriculumRepository = new SqliteCurriculumRepository(reopenedDatabase);
		Subject chemistry = curriculumRepository.findSubjectById(fixture.subjectId()).orElseThrow();
		SyllabusVersion currentVersion = curriculumRepository.findVersionById(fixture.currentVersionId()).orElseThrow();
		CurriculumNode includedDescriptor = curriculumRepository
				.findByCode(currentVersion, fixture.includedDescriptorCode()).orElseThrow();
		CurriculumNode excludedDescriptor = curriculumRepository
				.findByCode(currentVersion, fixture.excludedDescriptorCode()).orElseThrow();
		SqliteQuestionRepository questionRepository = new SqliteQuestionRepository(reopenedDatabase);
		Question reloadedQuestion = questionRepository.findById(fixture.questionId()).orElseThrow();

		// Prove the persisted mapping still derives both placements before the
		// Question-specific output exception is applied by the corpus builder.
		QuestionRetrievalService retrievalService = new QuestionRetrievalService(questionRepository,
				new CurriculumSearchNodeExpansionService(curriculumRepository));
		var retrievalResults = retrievalService.findQuestionsApplicableTo(chemistry);
		assertEquals(1, retrievalResults.size());
		assertEquals(reloadedQuestion.getId(), retrievalResults.getFirst().getQuestion().getId());
		assertEquals(List.of(includedDescriptor, excludedDescriptor),
				retrievalResults.getFirst().getCurrentApplicability());
		SqliteQuestionOutputApplicabilityRepository outputApplicabilityRepository = new SqliteQuestionOutputApplicabilityRepository(
				reopenedDatabase);
		assertEquals(Set.of(excludedDescriptor.getId()),
				outputApplicabilityRepository.findExcludedCurrentNodeIds(reloadedQuestion));
		RevisionCorpusBuilder corpusBuilder = new RevisionCorpusBuilder(curriculumRepository, retrievalService,
				outputApplicabilityRepository);
		RevisionCorpus corpus = corpusBuilder.build(chemistry);

		// The confirmed mapping remains two-way applicable for retrieval, but the
		// persisted Question exception removes exactly one revision-output placement.
		assertEquals(1, corpus.getStatistics().getApplicablePlacements());
		assertEquals(1, corpus.getStatistics().getUniqueApplicableQuestions());
		assertEquals(1, corpus.getStatistics().getRenderableQuestions());
		RevisionExportService revisionExportService = createRevisionExportService(corpusBuilder, fixture.pdfRoot());
		Path htmlDestination = tempDir.resolve("revision-html");
		RevisionExportResult htmlResult = revisionExportService
				.export(new RevisionExportRequest(chemistry, htmlDestination));
		assertEquals(1, htmlResult.getStatistics().getApplicablePlacements());
		assertEquals(1, htmlResult.getStatistics().getUniqueApplicableQuestions());
		assertTrue(Files.isRegularFile(
				htmlDestination.resolve(Path.of("assets", "questions", "question-" + fixture.questionId() + ".png"))));

		// The included Descriptor remains represented in generated HTML while the
		// excluded Descriptor contributes no output placement.
		String generatedHtml = readGeneratedHtml(htmlDestination);
		assertTrue(generatedHtml.contains(includedDescriptor.getName()));
		assertFalse(generatedHtml.contains(excludedDescriptor.getName()));
		ScormExportService scormExportService = new ScormExportService(revisionExportService, new ScormManifestWriter(),
				new ScormSchemaSupport(), new ScormPackageValidator(), new ScormZipWriter());
		Path scormDestination = tempDir.resolve("Chemistry.zip");
		ScormExportResult scormResult = scormExportService.export(new ScormExportRequest(chemistry, scormDestination));
		assertEquals(1, scormResult.getStatistics().getApplicablePlacements());
		assertEquals(1, scormResult.getStatistics().getUniqueApplicableQuestions());
		assertTrue(Files.isRegularFile(scormDestination));

		// SCORM packages the same filtered revision site, so its HTML must preserve
		// the same included/excluded curriculum result.
		try (ZipFile zip = new ZipFile(scormDestination.toFile(), StandardCharsets.UTF_8)) {
			assertNotNull(zip.getEntry("imsmanifest.xml"));
			String packagedHtml = readGeneratedHtml(zip);
			assertTrue(packagedHtml.contains(includedDescriptor.getName()));
			assertFalse(packagedHtml.contains(excludedDescriptor.getName()));
		}
	}

	private Fixture createPersistedFixture() throws Exception {
		Path databasePath = tempDir.resolve("questionbank.db");
		Path pdfRoot = tempDir.resolve("pdf");
		Path relativeQuestionPdf = Path.of("Chemistry", "2019", "paper1.pdf");
		Path questionPdf = pdfRoot.resolve(relativeQuestionPdf);
		Files.createDirectories(questionPdf.getParent());
		createPdf(questionPdf, Color.WHITE);
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SyllabusVersion historicalVersion = curriculumWriter.insertSyllabusVersion(chemistry, "2019", false);
		Unit historicalUnit = curriculumWriter.insertUnit(historicalVersion, "1", "Historical unit", 1);
		Topic historicalTopic = curriculumWriter.insertTopic(historicalUnit, "1.1", "Historical topic", 1);
		Subtopic historicalSubtopic = curriculumWriter.insertSubtopic(historicalTopic, "1.1.1", "Historical subtopic",
				1);
		Descriptor historicalDescriptor = curriculumWriter.insertDescriptor(historicalSubtopic, "1.1.1.1",
				"Historical descriptor", 1);
		SyllabusVersion currentVersion = curriculumWriter.insertSyllabusVersion(chemistry, "2025", true);
		Unit currentUnit = curriculumWriter.insertUnit(currentVersion, "1", "Current unit", 1);
		Topic currentTopic = curriculumWriter.insertTopic(currentUnit, "1.1", "Current topic", 1);
		Subtopic currentSubtopic = curriculumWriter.insertSubtopic(currentTopic, "1.1.1", "Current subtopic", 1);
		Descriptor includedDescriptor = curriculumWriter.insertDescriptor(currentSubtopic, "1.1.1.1",
				"Included current descriptor", 1);
		Descriptor excludedDescriptor = curriculumWriter.insertDescriptor(currentSubtopic, "1.1.1.2",
				"Excluded current descriptor", 2);
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		SqliteExamImporter examImporter = new SqliteExamImporter(database, examWriter);
		ExamBooklet historicalBooklet = examImporter.importExam(chemistry, "QCAA", 2019, "External Assessment",
				"Paper 1", relativeQuestionPdf.toString().replace('\\', '/'));
		SqliteQuestionRepository questionRepository = new SqliteQuestionRepository(database);
		QuestionRegion region = new QuestionRegion(historicalBooklet, 1, 0.0, 0.0, 1.0, 1.0);
		Question question = questionRepository.save(historicalBooklet, "Q1", "", 2, List.of(region),
				historicalDescriptor, false);

		// Persist one historical classification mapping to two current Descriptors.
		// Retrieval should therefore continue to derive both placements.
		SqliteCurriculumMappingReviewWriter mappingWriter = new SqliteCurriculumMappingReviewWriter(database);
		mappingWriter.confirmMappings(historicalDescriptor, currentVersion,
				List.of(includedDescriptor, excludedDescriptor));

		// Suppress only the second current placement. This must affect revision
		// output without altering the underlying confirmed curriculum mapping.
		SqliteQuestionOutputApplicabilityRepository outputRepository = new SqliteQuestionOutputApplicabilityRepository(
				database);
		outputRepository.setExcluded(question, excludedDescriptor, true);
		return new Fixture(databasePath, pdfRoot, chemistry.getId(), currentVersion.getId(),
				includedDescriptor.getCode(), excludedDescriptor.getCode(), question.getId());
	}

	private RevisionExportService createRevisionExportService(RevisionCorpusBuilder corpusBuilder, Path pdfRoot) {
		PdfStore pdfStore = new PdfStore(pdfRoot);
		QuestionExtractor extractor = new QuestionExtractor();

		// Use the production renderers and validator so the regression crosses the
		// real corpus-to-static-output boundary.
		return new RevisionExportService(corpusBuilder, new RevisionPresentationPlanner(),
				new RevisionQuestionAssetRenderer(pdfStore, extractor),
				new RevisionSharedContextAssetRenderer(pdfStore, extractor),
				new RevisionAnswerAssetRenderer(pdfStore, extractor), new RevisionExportValidator());
	}

	private String readGeneratedHtml(Path root) throws IOException {
		StringBuilder html = new StringBuilder();

		// Read every generated page because the exact curriculum page containing a
		// Descriptor is a renderer concern rather than part of this integration test.
		try (var paths = Files.walk(root)) {
			for (Path path : paths.filter(Files::isRegularFile)
					.filter(candidate -> candidate.getFileName().toString().endsWith(".html")).toList()) {
				html.append(Files.readString(path));
			}
		}
		return html.toString();
	}

	private String readGeneratedHtml(ZipFile zip) throws IOException {
		StringBuilder html = new StringBuilder();

		// Inspect every packaged HTML page so SCORM is checked against the same
		// observable curriculum output as the static export.
		var entries = zip.entries();
		while (entries.hasMoreElements()) {
			var entry = entries.nextElement();
			if (!entry.isDirectory() && entry.getName().endsWith(".html")) {
				html.append(new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8));
			}
		}
		return html.toString();
	}

	private record Fixture(Path databasePath, Path pdfRoot, long subjectId, long currentVersionId,
			String includedDescriptorCode, String excludedDescriptorCode, long questionId) {
	}
}
