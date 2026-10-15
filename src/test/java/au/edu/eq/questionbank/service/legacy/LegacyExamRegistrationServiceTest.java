package au.edu.eq.questionbank.service.legacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.importer.legacy.LegacyExamIdentity;
import au.edu.eq.questionbank.importer.legacy.LegacyExamKey;
import au.edu.eq.questionbank.importer.legacy.LegacyQuestionMetadataImporter;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.repository.assessment.SqliteExamWriter;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class LegacyExamRegistrationServiceTest {

	@TempDir
	Path tempDirectory;

	@Test
	void failedMultiExamRegistrationRollsBackAllCreations() throws Exception {

		Fixture fixture = createFixture("rollback.db");

		Path workbook = createWorkbook("multiple-sheets.xlsx",
				new String[][] { { "QCAA", "4.1.2" }, { "QCAA Sample", "4.1.2" } });

		// Force failure after the first Exam has been inserted.
		// The transaction must roll back both examination
		// records and any provider created during registration.
		try (Connection connection = fixture.database().openConnection();
				Statement statement = connection.createStatement()) {

			statement.execute("""
					CREATE TRIGGER reject_sample_exam
					BEFORE INSERT ON exams
					WHEN NEW.exam_name = 'Sample Examination'
					BEGIN
					    SELECT RAISE(
					        ABORT,
					        'deliberate sample exam failure'
					    );
					END
					""");
		}

		Map<LegacyExamKey, LegacyExamIdentity> mappings = Map.of(new LegacyExamKey("QCAA", 2020),
				new LegacyExamIdentity("QCAA", "External Assessment"), new LegacyExamKey("QCAA Sample", 2020),
				new LegacyExamIdentity("QCAA", "Sample Examination"));

		LegacyExamRegistrationService service = new LegacyExamRegistrationService(fixture.database());

		assertThrows(SQLException.class, () -> service.prepareExams(workbook, fixture.subject(), "2019", mappings));

		SqliteExamWriter writer = new SqliteExamWriter(fixture.database());

		assertTrue(writer.findExamsForSubject(fixture.subject()).isEmpty());

		try (Connection connection = fixture.database().openConnection();
				Statement statement = connection.createStatement();
				ResultSet providers = statement.executeQuery("SELECT COUNT(*) FROM exam_providers")) {

			assertTrue(providers.next());
			assertEquals(0, providers.getInt(1));
		}
	}

	@Test
	void invalidClassificationCreatesNoExaminations() throws Exception {

		Fixture fixture = createFixture("invalid-classification.db");

		Path workbook = createWorkbook("invalid-classification.xlsx", new String[][] { { "QCAA Mock", "9.9.9" } });

		LegacyExamRegistrationService service = new LegacyExamRegistrationService(fixture.database());

		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> service
				.prepareExams(workbook, fixture.subject(), "2019", mapping("QCAA Mock", "Mock Examination")));

		assertTrue(exception.getMessage().contains("Unknown classification"));

		assertTrue(new SqliteExamWriter(fixture.database()).findExamsForSubject(fixture.subject()).isEmpty());
	}

	@Test
	void registersThreeSeparateQCAAExaminationsAndReusesAssets() throws Exception {

		Fixture fixture = createFixture("three-exams.db");

		LegacyExamRegistrationService service = new LegacyExamRegistrationService(fixture.database());

		SqliteExamWriter writer = new SqliteExamWriter(fixture.database());

		String[] worksheets = { "QCAA", "QCAA Mock", "QCAA Sample" };

		String[] examNames = { "External Assessment", "Mock Examination", "Sample Examination" };

		List<Exam> registered = new ArrayList<>();

		for (int index = 0; index < worksheets.length; index++) {
			Path workbook = createWorkbook("qcaa-" + index + ".xlsx",
					new String[][] { { worksheets[index], "4.1.2" } });

			Map<LegacyExamKey, LegacyExamIdentity> mapping = mapping(worksheets[index], examNames[index]);

			List<Exam> exams = service.prepareExams(workbook, fixture.subject(), "2019", mapping);

			assertEquals(1, exams.size());

			Exam exam = exams.getFirst();

			assertEquals("QCAA", exam.getProvider().getName());
			assertEquals(2020, exam.getYear());
			assertEquals(examNames[index], exam.getName());

			// Registration creates the Exam but never invents
			// PDF assets or question booklets.
			assertTrue(writer.findExamBooklets(exam).isEmpty());

			registered.add(exam);
		}

		assertEquals(3, writer.findExamsForSubject(fixture.subject()).size());

		// Simulate an already attached Mock Examination booklet.
		Exam mockExam = registered.get(1);

		SourceDocument source = writer.insertSourceDocument("Chemistry/QCAA/2020/mock-mcq.pdf");

		ExamBooklet booklet = writer.insertExamBooklet(mockExam, source, "MCQ booklet");

		Path mockWorkbook = createWorkbook("qcaa-mock-reimport.xlsx", new String[][] { { "QCAA Mock", "4.1.2" } });

		Map<LegacyExamKey, LegacyExamIdentity> mockMapping = mapping("QCAA Mock", "Mock Examination");

		Exam reused = service.prepareExams(mockWorkbook, fixture.subject(), "2019", mockMapping).getFirst();

		assertEquals(mockExam.getId(), reused.getId());

		// Registration must preserve the existing booklet
		// and its source-document relationship.
		List<ExamBooklet> booklets = writer.findExamBooklets(reused);

		assertEquals(1, booklets.size());
		assertEquals(booklet.getId(), booklets.getFirst().getId());

		assertEquals(3, writer.findExamsForSubject(fixture.subject()).size());

		// The mapped importer must now recognise the Mock
		// booklet rather than confusing it with an External one.
		assertTrue(new LegacyQuestionMetadataImporter(fixture.database())
				.findMissingBookletsMapped(mockWorkbook, "Chemistry", "2019", mockMapping).isEmpty());
	}

	private Fixture createFixture(String databaseName) throws Exception {

		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve(databaseName));

		database.initialiseSchema();

		SqliteCurriculumWriter curriculum = new SqliteCurriculumWriter(database);

		Subject chemistry = curriculum.insertSubject("Chemistry");

		SyllabusVersion syllabus = curriculum.insertSyllabusVersion(chemistry, "2019", false);

		Unit unit = curriculum.insertUnit(syllabus, "4", "Unit 4", 1);

		Topic topic = curriculum.insertTopic(unit, "4.1", "Topic 4.1", 1);

		curriculum.insertSubtopic(topic, "4.1.2", "Subtopic 4.1.2", 1);

		return new Fixture(database, chemistry);
	}

	private Path createWorkbook(String fileName, String[][] worksheets) throws Exception {

		Path path = tempDirectory.resolve(fileName);

		try (Workbook workbook = new XSSFWorkbook()) {
			for (String[] specification : worksheets) {
				Sheet sheet = workbook.createSheet(specification[0]);

				String[] headings = { "Year", "Paper", "Question", "Marks", "Topic", "Answer", "Preamble" };

				Row header = sheet.createRow(0);

				for (int column = 0; column < headings.length; column++) {
					header.createCell(column).setCellValue(headings[column]);
				}

				Row question = sheet.createRow(1);

				question.createCell(0).setCellValue(2020);
				question.createCell(1).setCellValue("MCQ");
				question.createCell(2).setCellValue(1);
				question.createCell(3).setCellValue(1);
				question.createCell(4).setCellValue(specification[1]);
				question.createCell(5).setCellValue("C");
			}

			try (OutputStream output = Files.newOutputStream(path)) {
				workbook.write(output);
			}
		}

		return path;
	}

	private Map<LegacyExamKey, LegacyExamIdentity> mapping(String worksheetName, String examName) {

		return Map.of(new LegacyExamKey(worksheetName, 2020), new LegacyExamIdentity("QCAA", examName));
	}

	private record Fixture(SqliteDatabase database, Subject subject) {
	}
}
