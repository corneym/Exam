package au.edu.eq.questionbank.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.Subject;

class SqliteExamImporterTest {

	@TempDir
	Path tempDirectory;

	@Test
	void reusesExistingExamMetadata() throws Exception {
		Path databasePath = tempDirectory.resolve("questionbank.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SqliteExamWriter writer = new SqliteExamWriter(database);
		SqliteExamImporter importer = new SqliteExamImporter(database, writer);
		ExamBooklet first = importer.importExam(chemistry, "QCAA", 2019, "External Assessment", "Paper 1",
				"Chemistry/2019/paper1.pdf");
		ExamBooklet second = importer.importExam(chemistry, "QCAA", 2019, "External Assessment", "Paper 1",
				"Chemistry/2019/paper1.pdf");
		assertEquals(first.getId(), second.getId());
		assertEquals(first.getExam().getId(), second.getExam().getId());
		assertEquals(first.getExam().getProvider().getId(), second.getExam().getProvider().getId());
		assertEquals(first.getSourceDocument().getId(), second.getSourceDocument().getId());
	}
}
