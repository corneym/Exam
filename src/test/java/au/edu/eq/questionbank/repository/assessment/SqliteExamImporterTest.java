package au.edu.eq.questionbank.repository.assessment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamBookletQuestionFormat;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class SqliteExamImporterTest {

	@TempDir
	Path tempDirectory;

	@Test
	void importsAndReusesExplicitBookletQuestionFormat() throws Exception {
		Path databasePath = tempDirectory.resolve("booklet-format.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		Subject chemistry = new SqliteCurriculumWriter(database).insertSubject("Chemistry");
		SqliteExamWriter writer = new SqliteExamWriter(database);
		SqliteExamImporter importer = new SqliteExamImporter(database, writer);

		// New imports must persist the format explicitly selected for this booklet.
		ExamBooklet first = importer.importExam(chemistry, "QCAA", 2025, "External Assessment", "Paper 1",
				"Chemistry/QCAA/2025/paper1.pdf", ExamBookletQuestionFormat.MIXED);
		assertEquals(ExamBookletQuestionFormat.MIXED, first.getQuestionFormat());

		// Reopening the same persisted booklet must recover its stored format rather
		// than replacing it with UNSPECIFIED or the caller's current selection.
		ExamBooklet second = importer.importExam(chemistry, "QCAA", 2025, "External Assessment", "Paper 1",
				"Chemistry/QCAA/2025/paper1.pdf", ExamBookletQuestionFormat.WRITTEN_RESPONSE);
		assertEquals(first.getId(), second.getId());
		assertEquals(ExamBookletQuestionFormat.MIXED, second.getQuestionFormat());
	}

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
