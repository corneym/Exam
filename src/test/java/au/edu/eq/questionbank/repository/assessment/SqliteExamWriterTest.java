package au.edu.eq.questionbank.repository.assessment;

import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.Subject;

class SqliteExamWriterTest {

	@TempDir
	Path tempDirectory;

	@Test
	void findsExistingExamMetadata() throws Exception {
		Path databasePath = tempDirectory.resolve("existing-exam.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SqliteExamWriter writer = new SqliteExamWriter(database);
		ExamProvider provider = writer.insertExamProvider("QCAA");
		SourceDocument sourceDocument = writer.insertSourceDocument("Chemistry/2019/paper1.pdf");
		Exam exam = writer.insertExam(chemistry, provider, 2019, "External Assessment");
		ExamBooklet booklet = writer.insertExamBooklet(exam, sourceDocument, "Paper 1");
		try (Connection connection = database.openConnection()) {
			assertEquals(provider.getId(), writer.findExamProviderByName(connection, "QCAA").getId());
			assertEquals(sourceDocument.getId(),
					writer.findSourceDocumentByPath(connection, "Chemistry/2019/paper1.pdf").getId());
			assertEquals(exam.getId(),
					writer.findExam(connection, chemistry, provider, 2019, "External Assessment").getId());
			assertEquals(booklet.getId(), writer.findExamBooklet(connection, exam, "Paper 1", sourceDocument).getId());
		}
	}

	@Test
	void insertsCompleteExamMetadata() throws Exception {
		Path databasePath = tempDirectory.resolve("questionbank.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SqliteExamWriter writer = new SqliteExamWriter(database);
		ExamProvider provider = writer.insertExamProvider("QCAA");
		SourceDocument sourceDocument = writer.insertSourceDocument("Chemistry/2019/paper1.pdf");
		Exam exam = writer.insertExam(chemistry, provider, 2019, "External Assessment");
		ExamBooklet booklet = writer.insertExamBooklet(exam, sourceDocument, "Paper 1");

		assertTrue(provider.getId() > 0);
		assertTrue(sourceDocument.getId() > 0);
		assertTrue(exam.getId() > 0);
		assertTrue(booklet.getId() > 0);
		assertEquals("Chemistry", exam.getSubject().getName());
		assertEquals("QCAA", exam.getProvider().getName());
		assertEquals(2019, exam.getYear());
		assertEquals("Paper 1", booklet.getName());
		assertEquals("Chemistry/2019/paper1.pdf", booklet.getSourceDocument().getRelativePath());

		SqliteDatabase reopenedDatabase = new SqliteDatabase(databasePath);
		try (Connection connection = reopenedDatabase.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT subjects.id AS subject_id,
						       subjects.subject_name,
						       exam_providers.id AS provider_id,
						       exam_providers.provider_name,
						       exams.id AS exam_id,
						       exams.exam_year,
						       exams.exam_name,
						       exam_booklets.id AS booklet_id,
						       exam_booklets.booklet_name,
						       source_documents.id AS source_document_id,
						       source_documents.relative_path
						FROM exam_booklets
						JOIN exams ON exams.id = exam_booklets.exam_id
						JOIN subjects ON subjects.id = exams.subject_id
						JOIN exam_providers ON exam_providers.id = exams.provider_id
						JOIN source_documents ON source_documents.id = exam_booklets.source_document_id
						WHERE exam_booklets.id = ?
						""")) {
			statement.setLong(1, booklet.getId());
			try (ResultSet result = statement.executeQuery()) {
				assertTrue(result.next());
				assertEquals(chemistry.getId(), result.getLong("subject_id"));
				assertEquals("Chemistry", result.getString("subject_name"));
				assertEquals(provider.getId(), result.getLong("provider_id"));
				assertEquals("QCAA", result.getString("provider_name"));
				assertEquals(exam.getId(), result.getLong("exam_id"));
				assertEquals(2019, result.getInt("exam_year"));
				assertEquals("External Assessment", result.getString("exam_name"));
				assertEquals(booklet.getId(), result.getLong("booklet_id"));
				assertEquals("Paper 1", result.getString("booklet_name"));
				assertEquals(sourceDocument.getId(), result.getLong("source_document_id"));
				assertEquals("Chemistry/2019/paper1.pdf", result.getString("relative_path"));
				assertFalse(result.next());
			}
		}
	}

	@Test
	void rejectsDuplicateExamMetadataNaturalKeys() throws Exception {
		Path databasePath = tempDirectory.resolve("duplicates.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		Subject chemistry = new SqliteCurriculumWriter(database).insertSubject("Chemistry");
		SqliteExamWriter writer = new SqliteExamWriter(database);
		ExamProvider provider = writer.insertExamProvider("QCAA");
		SourceDocument sourceDocument = writer.insertSourceDocument("Chemistry/2019/paper1.pdf");
		Exam exam = writer.insertExam(chemistry, provider, 2019, "External Assessment");
		writer.insertExamBooklet(exam, sourceDocument, "Paper 1");

		assertThrows(SQLException.class, () -> writer.insertExamProvider("QCAA"));
		assertThrows(SQLException.class,
				() -> writer.insertSourceDocument("Chemistry/2019/paper1.pdf"));
		assertThrows(SQLException.class,
				() -> writer.insertExam(chemistry, provider, 2019, "External Assessment"));
		assertThrows(SQLException.class, () -> writer.insertExamBooklet(exam,
				writer.insertSourceDocument("Chemistry/2019/paper1-alternate.pdf"), "Paper 1"));
	}
}
