package au.edu.eq.questionbank.service.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.importer.legacy.LegacyQuestionImportResult;
import au.edu.eq.questionbank.importer.legacy.LegacyQuestionMetadataImporter;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.repository.assessment.SqliteExamImporter;
import au.edu.eq.questionbank.repository.assessment.SqliteExamWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteQuestionRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumMappingReviewWriter;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class QuestionRetrievalImportOriginTest {

	@TempDir
	Path tempDirectory;

	@Test
	void retrievesQuestionsRegardlessOfImportOriginAfterReopen() throws Exception {
		Path databasePath = tempDirectory.resolve("import-origin.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SyllabusVersion historicalVersion = curriculumWriter.insertSyllabusVersion(chemistry, "2019", false);
		Unit historicalUnit = curriculumWriter.insertUnit(historicalVersion, "1", "Historical unit", 1);
		Topic historicalTopic = curriculumWriter.insertTopic(historicalUnit, "1.1", "Historical topic", 1);
		Subtopic historicalSubtopic = curriculumWriter.insertSubtopic(historicalTopic, "1.1.1", "Historical subtopic",
				1);
		SyllabusVersion currentVersion = curriculumWriter.insertSyllabusVersion(chemistry, "2025", true);
		Unit currentUnit = curriculumWriter.insertUnit(currentVersion, "1", "Current unit", 1);
		Topic currentTopic = curriculumWriter.insertTopic(currentUnit, "1.1", "Current topic", 1);
		Subtopic currentSubtopic = curriculumWriter.insertSubtopic(currentTopic, "1.1.1", "Current subtopic", 1);
		SqliteExamImporter examImporter = new SqliteExamImporter(database, new SqliteExamWriter(database));
		examImporter.importExam(chemistry, "QCAA", 2020, "External Assessment", "Paper 1", "Chemistry/2020/paper1.pdf");
		Path workbookPath = createLegacyWorkbook();
		LegacyQuestionMetadataImporter legacyImporter = new LegacyQuestionMetadataImporter(database);
		LegacyQuestionImportResult importResult = legacyImporter.importWorkbook(workbookPath, "Chemistry", "2019");
		assertEquals(new LegacyQuestionImportResult(1, 0, 0), importResult);
		ExamBooklet currentBooklet = examImporter.importExam(chemistry, "QCAA", 2025, "External Assessment", "Paper 1",
				"Chemistry/2025/paper1.pdf");
		SqliteQuestionRepository questionRepository = new SqliteQuestionRepository(database);
		Question currentQuestion = questionRepository.save(currentBooklet, "CURRENT", "", 2, List.of(), currentSubtopic,
				false);
		SqliteCurriculumMappingReviewWriter mappingWriter = new SqliteCurriculumMappingReviewWriter(database);
		mappingWriter.confirmMappings(historicalSubtopic, currentVersion, List.of(currentSubtopic));
		SqliteDatabase reopenedDatabase = new SqliteDatabase(databasePath);
		reopenedDatabase.initialiseSchema();
		SqliteCurriculumRepository curriculumRepository = new SqliteCurriculumRepository(reopenedDatabase);
		SyllabusVersion reopenedCurrentVersion = curriculumRepository.findVersionById(currentVersion.getId())
				.orElseThrow();
		CurriculumNode reopenedCurrentSubtopic = curriculumRepository
				.findByCode(reopenedCurrentVersion, currentSubtopic.getCode()).orElseThrow();
		SqliteQuestionRepository reopenedQuestionRepository = new SqliteQuestionRepository(reopenedDatabase);
		QuestionRetrievalService retrievalService = new QuestionRetrievalService(reopenedQuestionRepository,
				new CurriculumSearchNodeExpansionService(curriculumRepository));
		List<QuestionRetrievalResult> results = retrievalService.findQuestionsApplicableTo(reopenedCurrentSubtopic);
		assertEquals(2, results.size());
		QuestionRetrievalResult legacyResult = findByQuestionCode(results, "21a");
		QuestionRetrievalResult currentResult = findByQuestionCode(results, "CURRENT");
		assertEquals(historicalSubtopic.getId(), legacyResult.getOriginalClassification().getId());
		assertEquals(List.of(reopenedCurrentSubtopic), legacyResult.getCurrentApplicability());
		assertEquals(currentQuestion.getId(), currentResult.getQuestion().getId());
		assertEquals(currentSubtopic.getId(), currentResult.getOriginalClassification().getId());
		assertEquals(List.of(reopenedCurrentSubtopic), currentResult.getCurrentApplicability());
	}

	private Path createLegacyWorkbook() throws Exception {
		Path workbookPath = tempDirectory.resolve("legacy-retrieval.xlsx");
		try (Workbook workbook = new XSSFWorkbook()) {
			Sheet sheet = workbook.createSheet("QCAA");
			writeHeader(sheet);
			Row questionRow = sheet.createRow(1);
			questionRow.createCell(0).setCellValue(2020);
			questionRow.createCell(1).setCellValue("1");
			questionRow.createCell(2).setCellValue("21a");
			questionRow.createCell(3).setCellValue(3);
			questionRow.createCell(4).setCellValue("1.1.1");
			try (OutputStream output = Files.newOutputStream(workbookPath)) {
				workbook.write(output);
			}
		}
		return workbookPath;
	}

	private QuestionRetrievalResult findByQuestionCode(List<QuestionRetrievalResult> results, String questionCode) {
		for (QuestionRetrievalResult result : results) {
			if (questionCode.equals(result.getQuestion().getQuestionCode())) {
				return result;
			}
		}
		throw new AssertionError("Question not found: " + questionCode);
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
}
