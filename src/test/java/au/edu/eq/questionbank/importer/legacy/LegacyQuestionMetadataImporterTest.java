package au.edu.eq.questionbank.importer.legacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.repository.assessment.SqliteExamImporter;
import au.edu.eq.questionbank.repository.assessment.SqliteExamWriter;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class LegacyQuestionMetadataImporterTest {
	@TempDir
	Path tempDirectory;

	@Test
	void conflictingExistingQuestionPreventsAnyNewRows() throws Exception {
		Fixture fixture = createFixture("existing-conflict.db", false);
		LegacyQuestionMetadataImporter importer = new LegacyQuestionMetadataImporter(fixture.database());
		importer.importWorkbook(fixture.workbookPath(), "Chemistry", "2019");
		try (Connection connection = fixture.database().openConnection();
				Statement statement = connection.createStatement()) {
			statement.execute("DELETE FROM answers");
			statement.execute("DELETE FROM questions WHERE question_code = '1'");
			statement.execute("UPDATE questions SET marks = 4 WHERE question_code = '21a'");
		}
		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
				() -> importer.importWorkbook(fixture.workbookPath(), "Chemistry", "2019"));
		assertTrue(exception.getMessage().contains("Existing marks conflict"));
		try (Connection connection = fixture.database().openConnection();
				Statement statement = connection.createStatement()) {
			assertEquals(1, countRows(statement, "questions"));
			assertEquals(0, countRows(statement, "answers"));
		}
	}

	@Test
	void databaseFailureRollsBackEarlierQuestionAndAnswerInserts() throws Exception {
		Fixture fixture = createFixture("transaction-rollback.db", false);
		try (Connection connection = fixture.database().openConnection();
				Statement statement = connection.createStatement()) {
			statement.execute("""
					CREATE TRIGGER reject_second_legacy_question
					BEFORE INSERT ON questions
					WHEN NEW.question_code = '21a'
					BEGIN
					    SELECT RAISE(ABORT, 'deliberate legacy import failure');
					END
					""");
		}
		assertThrows(SQLException.class, () -> new LegacyQuestionMetadataImporter(fixture.database())
				.importWorkbook(fixture.workbookPath(), "Chemistry", "2019"));
		try (Connection connection = fixture.database().openConnection();
				Statement statement = connection.createStatement()) {
			assertEquals(0, countRows(statement, "questions"));
			assertEquals(0, countRows(statement, "answers"));
			assertEquals(0, countRows(statement, "source_questions"));
		}
	}

	@Test
	void findsEveryRequiredBookletWhenNoneArePresent() throws Exception {
		Fixture fixture = createFixture("no-booklets.db", false);
		try (Connection connection = fixture.database().openConnection();
				Statement statement = connection.createStatement()) {
			statement.executeUpdate("DELETE FROM exam_booklets");
		}
		List<LegacyBookletRequirement> missing = new LegacyQuestionMetadataImporter(fixture.database())
				.findMissingBooklets(fixture.workbookPath(), "Chemistry", "2019");
		assertEquals(2, missing.size());
		assertTrue(missing.contains(new LegacyBookletRequirement("QCAA", 2020, "MCQ booklet")));
		assertTrue(missing.contains(new LegacyBookletRequirement("QCAA", 2020, "Paper 1")));
	}

	@Test
	void findsMissingBookletsBeforeImport() throws Exception {
		Fixture fixture = createFixture("missing-booklets.db", false);
		try (Connection connection = fixture.database().openConnection();
				Statement statement = connection.createStatement()) {
			statement.executeUpdate("""
					DELETE FROM exam_booklets
					WHERE booklet_name = 'Paper 1'
					""");
		}
		List<LegacyBookletRequirement> missing = new LegacyQuestionMetadataImporter(fixture.database())
				.findMissingBooklets(fixture.workbookPath(), "Chemistry", "2019");
		assertEquals(1, missing.size());
		assertEquals("QCAA", missing.get(0).providerName());
		assertEquals(2020, missing.get(0).year());
		assertEquals("Paper 1", missing.get(0).bookletName());
	}

	@Test
	void importsLegacyMetadataWithoutCreatingQuestionRegions() throws Exception {
		Fixture fixture = createFixture("import.db", false);
		LegacyQuestionImportResult result = new LegacyQuestionMetadataImporter(fixture.database())
				.importWorkbook(fixture.workbookPath(), "Chemistry", "2019");
		assertEquals(2, result.insertedQuestions());
		assertEquals(0, result.existingQuestions());
		assertEquals(1, result.insertedAnswers());
		try (Connection connection = fixture.database().openConnection();
				Statement statement = connection.createStatement()) {
			try (ResultSet questions = statement.executeQuery("""
					SELECT
					    question_code,
					    marks,
					    preamble_capture_required,
					    source_question_id,
					    shared_context_id
					FROM questions
					ORDER BY question_code
										""")) {
				assertTrue(questions.next());
				assertEquals("1", questions.getString("question_code"));
				assertEquals(1, questions.getInt("marks"));
				assertEquals(0, questions.getInt("preamble_capture_required"));
				assertNull(questions.getObject("source_question_id"));
				assertNull(questions.getObject("shared_context_id"));
				assertTrue(questions.next());
				assertEquals("21a", questions.getString("question_code"));
				assertEquals(3, questions.getInt("marks"));
				assertEquals(1, questions.getInt("preamble_capture_required"));
				assertTrue(questions.getObject("source_question_id") != null);
				assertNull(questions.getObject("shared_context_id"));
			}
			assertEquals(1, countRows(statement, "source_questions"));
			assertEquals(0, countRows(statement, "shared_question_contexts"));
			try (ResultSet sourceQuestion = statement.executeQuery("""
					SELECT
					    source_question_code,
					    preamble_status
					FROM source_questions
					""")) {
				assertTrue(sourceQuestion.next());
				assertEquals("21", sourceQuestion.getString("source_question_code"));
				assertEquals("UNKNOWN", sourceQuestion.getString("preamble_status"));
			}
			assertEquals(0, countRows(statement, "question_regions"));
			assertEquals(1, countRows(statement, "answers"));
			try (ResultSet answer = statement.executeQuery("SELECT answer_text FROM answers")) {
				assertTrue(answer.next());
				assertEquals("B", answer.getString("answer_text"));
			}
		}
	}

	@Test
	void invalidClassificationLeavesWorkbookUnimported() throws Exception {
		Fixture fixture = createFixture("invalid-classification.db", true);
		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
				() -> new LegacyQuestionMetadataImporter(fixture.database()).importWorkbook(fixture.workbookPath(),
						"Chemistry", "2019"));
		assertTrue(exception.getMessage().contains("Unknown classification"));
		try (Connection connection = fixture.database().openConnection();
				Statement statement = connection.createStatement()) {
			assertEquals(0, countRows(statement, "questions"));
			assertEquals(0, countRows(statement, "answers"));
		}
	}

	@Test
	void repeatedImportIsIdempotent() throws Exception {
		Fixture fixture = createFixture("idempotent.db", false);
		LegacyQuestionMetadataImporter importer = new LegacyQuestionMetadataImporter(fixture.database());
		importer.importWorkbook(fixture.workbookPath(), "Chemistry", "2019");
		LegacyQuestionImportResult second = importer.importWorkbook(fixture.workbookPath(), "Chemistry", "2019");
		assertEquals(0, second.insertedQuestions());
		assertEquals(2, second.existingQuestions());
		assertEquals(0, second.insertedAnswers());
		try (Connection connection = fixture.database().openConnection();
				Statement statement = connection.createStatement()) {
			assertEquals(2, countRows(statement, "questions"));
			assertEquals(1, countRows(statement, "answers"));
			assertEquals(1, countRows(statement, "source_questions"));
		}
	}

	@Test
	void reportsNoMissingBookletsWhenAllRequiredBookletsExist() throws Exception {
		Fixture fixture = createFixture("all-booklets-present.db", false);
		List<LegacyBookletRequirement> missing = new LegacyQuestionMetadataImporter(fixture.database())
				.findMissingBooklets(fixture.workbookPath(), "Chemistry", "2019");
		assertTrue(missing.isEmpty());
	}

	private int countRows(Statement statement, String tableName) throws Exception {
		try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
			result.next();
			return result.getInt(1);
		}
	}

	private Fixture createFixture(String databaseName, boolean invalidSecondClassification) throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve(databaseName));
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SyllabusVersion syllabus = curriculumWriter.insertSyllabusVersion(chemistry, "2019", false);
		Unit unit1 = curriculumWriter.insertUnit(syllabus, "1", "Unit 1", 1);
		Topic topic1 = curriculumWriter.insertTopic(unit1, "1.1", "Topic 1", 1);
		curriculumWriter.insertSubtopic(topic1, "1.1.1", "Subtopic 1", 1);
		Unit unit2 = curriculumWriter.insertUnit(syllabus, "2", "Unit 2", 2);
		Topic topic2 = curriculumWriter.insertTopic(unit2, "2.3", "Topic 2.3", 1);
		curriculumWriter.insertSubtopic(topic2, "2.3.1", "Subtopic 2.3.1", 1);
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		SqliteExamImporter examImporter = new SqliteExamImporter(database, examWriter);
		examImporter.importExam(chemistry, "QCAA", 2020, "External Assessment", "MCQ booklet",
				"Chemistry/2020/mcq.pdf");
		examImporter.importExam(chemistry, "QCAA", 2020, "External Assessment", "Paper 1", "Chemistry/2020/paper1.pdf");
		Path workbookPath = createWorkbook(invalidSecondClassification);
		return new Fixture(database, workbookPath);
	}

	private Path createWorkbook(boolean invalidSecondClassification) throws Exception {
		Path path = tempDirectory.resolve("legacy-" + System.nanoTime() + ".xlsx");
		try (Workbook workbook = new XSSFWorkbook()) {
			Sheet sheet = workbook.createSheet("QCAA");
			Row header = sheet.createRow(0);
			header.createCell(0).setCellValue("Year");
			header.createCell(1).setCellValue("Paper");
			header.createCell(2).setCellValue("Question");
			header.createCell(3).setCellValue("Marks");
			header.createCell(4).setCellValue("Topic");
			header.createCell(5).setCellValue("Answer");
			header.createCell(6).setCellValue("Preamble");
			Row first = sheet.createRow(1);
			first.createCell(0).setCellValue(2020);
			first.createCell(1).setCellValue("MCQ");
			first.createCell(2).setCellValue(1);
			first.createCell(3).setCellValue(1);
			first.createCell(4).setCellValue("1.1.1");
			first.createCell(5).setCellValue("B");
			Row second = sheet.createRow(2);
			second.createCell(0).setCellValue(2020);
			second.createCell(1).setCellValue("1");
			second.createCell(2).setCellValue("21a");
			second.createCell(3).setCellValue(3);
			second.createCell(4).setCellValue(invalidSecondClassification ? "9.9.9" : "2.3.1");
			second.createCell(6).setCellValue(1);
			try (OutputStream output = Files.newOutputStream(path)) {
				workbook.write(output);
			}
		}
		return path;
	}

	private record Fixture(SqliteDatabase database, Path workbookPath) {
	}
}
