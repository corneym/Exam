package au.edu.eq.questionbank.importer.legacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.pdf.PdfStore;
import au.edu.eq.questionbank.repository.assessment.SqliteExamImporter;
import au.edu.eq.questionbank.repository.assessment.SqliteExamWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteQuestionRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class LegacyQuestionImportWorkflowIntegrationTest {

	@TempDir
	Path tempDirectory;

	@Test
	void importsMissingBookletsQuestionsAndCaptureStateEndToEnd() throws Exception {
		Path databasePath = tempDirectory.resolve("questionbank.db");
		Path pdfRoot = tempDirectory.resolve("data/pdf");
		Path incomingRoot = tempDirectory.resolve("incoming");
		Files.createDirectories(incomingRoot);
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		Subject chemistry = createCurriculum(database);
		Path workbook = createWorkbook();
		LegacyQuestionMetadataImporter metadataImporter = new LegacyQuestionMetadataImporter(database);
		List<LegacyBookletRequirement> requirements = metadataImporter.findMissingBooklets(workbook, "Chemistry",
				"2019");
		assertEquals(5, requirements.size());
		PdfStore pdfStore = new PdfStore(pdfRoot);
		SqliteExamImporter examImporter = new SqliteExamImporter(database, new SqliteExamWriter(database));
		List<Path> storedPdfs = new ArrayList<>();
		for (LegacyBookletRequirement requirement : requirements) {
			Path source = incomingRoot.resolve(sourceFilename(requirement));
			Files.writeString(source, requirement.toString());
			Path stored = pdfStore.importExamPdf(source, chemistry.getName(), requirement.providerName(),
					requirement.year());
			Path reused = pdfStore.importExamPdf(source, chemistry.getName(), requirement.providerName(),
					requirement.year());
			assertEquals(stored, reused);
			storedPdfs.add(stored);
			String relativePath = pdfRoot.relativize(stored).toString();
			examImporter.importExam(chemistry, requirement.providerName(), requirement.year(), "External Assessment",
					requirement.bookletName(), relativePath);
		}
		assertTrue(metadataImporter.findMissingBooklets(workbook, "Chemistry", "2019").isEmpty());
		LegacyQuestionImportResult first = metadataImporter.importWorkbook(workbook, "Chemistry", "2019");
		LegacyQuestionImportResult second = metadataImporter.importWorkbook(workbook, "Chemistry", "2019");
		assertEquals(new LegacyQuestionImportResult(5, 0, 2), first);
		assertEquals(new LegacyQuestionImportResult(0, 5, 0), second);
		for (Path storedPdf : storedPdfs) {
			assertTrue(Files.isRegularFile(storedPdf));
			assertTrue(storedPdf.startsWith(pdfRoot.resolve("Chemistry")));
			assertFalse(storedPdf.getFileName().toString().contains("(2)"));
		}
		SqliteQuestionRepository repository = new SqliteQuestionRepository(database);
		List<Question> importedQuestions = repository.findAll();
		assertEquals(5, importedQuestions.size());
		for (Question question : importedQuestions) {
			assertTrue(question.getRegions().isEmpty());
			assertEquals("2019", question.getClassification().getSyllabusVersion().getName());
			assertTrue(question.getClassification() instanceof Subtopic);
		}
		Question mcq = findQuestion(importedQuestions, "QCAA", 2020, "MCQ booklet", "1");
		Question paper1 = findQuestion(importedQuestions, "QCAA", 2020, "Paper 1", "21a");
		Question paper2 = findQuestion(importedQuestions, "QCAA", 2020, "Paper 2", "1a");
		assertTrue(mcq.hasAnswer());
		assertEquals("B", mcq.getAnswer().getAnswerText());
		assertFalse(paper1.hasAnswer());
		assertFalse(paper1.isSharedContextCaptureRequired());
		assertTrue(paper2.isSharedContextCaptureRequired());
		ExamBooklet booklet = paper1.getBooklet();
		Question captured = repository.attachRegions(paper1.getId(),
				List.of(new QuestionRegion(booklet, 2, 0.10, 0.20, 0.70, 0.15)));
		assertEquals(1, captured.getRegions().size());
		LegacyQuestionImportResult afterCapture = metadataImporter.importWorkbook(workbook, "Chemistry", "2019");
		assertEquals(new LegacyQuestionImportResult(0, 5, 0), afterCapture);
		Question reloaded = new SqliteQuestionRepository(database).findById(paper1.getId()).orElseThrow();
		assertEquals(1, reloaded.getRegions().size());
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("SELECT relative_path FROM source_documents")) {
			int pathCount = 0;
			while (result.next()) {
				String relativePath = result.getString(1).replace('\\', '/');
				assertTrue(relativePath.startsWith("Chemistry/"));
				assertTrue(relativePath.contains("/2020/") || relativePath.contains("/2021/"));
				pathCount++;
			}
			assertEquals(5, pathCount);
		}
	}

	private Subject createCurriculum(SqliteDatabase database) throws Exception {
		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(database);
		Subject chemistry = writer.insertSubject("Chemistry");
		SyllabusVersion syllabus = writer.insertSyllabusVersion(chemistry, "2019", false);
		Unit unit = writer.insertUnit(syllabus, "1", "Unit 1", 1);
		Topic topic = writer.insertTopic(unit, "1.1", "Topic 1", 1);
		writer.insertSubtopic(topic, "1.1.1", "Subtopic 1", 1);
		return chemistry;
	}

	private Path createWorkbook() throws Exception {
		Path path = tempDirectory.resolve("legacy-workflow.xlsx");
		try (Workbook workbook = new XSSFWorkbook()) {
			Sheet qcaa = workbook.createSheet("QCAA");
			writeHeader(qcaa);
			writeQuestion(qcaa, 1, 2020, "MCQ", "1", 1, "1.1.1", "B", false);
			writeQuestion(qcaa, 2, 2020, "1", "21a", 3, "1.1.1", null, false);
			writeQuestion(qcaa, 3, 2020, "2", "1a", 2, "1.1.1", null, true);
			writeQuestion(qcaa, 4, 2021, "1", "21a", 4, "1.1.1", null, true);
			Sheet neap = workbook.createSheet("NEAP");
			writeHeader(neap);
			writeQuestion(neap, 1, 2021, "MCQ", "1", 1, "1.1.1", "D", false);
			try (OutputStream output = Files.newOutputStream(path)) {
				workbook.write(output);
			}
		}
		return path;
	}

	private Question findQuestion(List<Question> questions, String provider, int year, String bookletName,
			String questionCode) {
		for (Question question : questions) {
			if (provider.equals(question.getExam().getProvider().getName()) && year == question.getExam().getYear()
					&& bookletName.equals(question.getBooklet().getName())
					&& questionCode.equals(question.getQuestionCode())) {
				return question;
			}
		}
		throw new AssertionError(
				"Question not found: " + provider + " " + year + " " + bookletName + " " + questionCode);
	}

	private String sourceFilename(LegacyBookletRequirement requirement) {
		return (requirement.providerName() + "-" + requirement.year() + "-" + requirement.bookletName() + ".pdf")
				.replace(' ', '-');
	}

	private void writeHeader(Sheet sheet) {
		Row header = sheet.createRow(0);
		header.createCell(0).setCellValue("Year");
		header.createCell(1).setCellValue("Paper");
		header.createCell(2).setCellValue("Question");
		header.createCell(3).setCellValue("Marks");
		header.createCell(4).setCellValue("Topic");
		header.createCell(5).setCellValue("Answer");
		header.createCell(6).setCellValue("Preamble");
	}

	private void writeQuestion(Sheet sheet, int rowIndex, int year, String paper, String questionCode, int marks,
			String classificationCode, String answer, boolean sharedContext) {
		Row row = sheet.createRow(rowIndex);
		row.createCell(0).setCellValue(year);
		row.createCell(1).setCellValue(paper);
		row.createCell(2).setCellValue(questionCode);
		row.createCell(3).setCellValue(marks);
		row.createCell(4).setCellValue(classificationCode);
		if (answer != null) {
			row.createCell(5).setCellValue(answer);
		}
		if (sharedContext) {
			row.createCell(6).setCellValue(1);
		}
	}
}
