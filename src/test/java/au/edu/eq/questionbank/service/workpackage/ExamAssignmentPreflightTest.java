package au.edu.eq.questionbank.service.workpackage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class ExamAssignmentPreflightTest {

	@TempDir
	Path directory;

	@Test
	void acceptsWholeExamWithCurrentAndLegacySyllabuses() throws Exception {
		Fixture fixture = fixture();
		ExamAssignmentPreflight.Result result = fixture.preflight().check(fixture.exam(), true);
		assertEquals(1, result.booklets().size());
		assertEquals(1, result.answerFiles().size());
		assertEquals("2025", result.currentSyllabus().getName());
		assertTrue(result.currentSyllabus().isCurrent());
		assertEquals("2019", result.legacy2019Syllabus().getName());
	}

	@Test
	void rejectsMissingAnswerPdf() throws Exception {
		Fixture fixture = fixture();
		Files.delete(fixture.answerPdf());
		IOException failure = assertThrows(IOException.class, () -> fixture.preflight().check(fixture.exam(), true));
		assertTrue(failure.getMessage().contains("missing or unreadable"));
	}

	@Test
	void rejectsMissingOrAmbiguousCurrentSyllabus() throws Exception {
		Fixture fixture = fixture();
		execute(fixture.database(), """
				UPDATE syllabus_versions
				SET is_current = 0
				WHERE id = 12
				""");
		assertThrows(IOException.class, () -> fixture.preflight().check(fixture.exam(), false));
		execute(fixture.database(), """
				UPDATE syllabus_versions
				SET is_current = 1
				WHERE id IN (11, 12)
				""");
		IOException failure = assertThrows(IOException.class, () -> fixture.preflight().check(fixture.exam(), false));
		assertTrue(failure.getMessage().contains("exactly one"));
	}

	@Test
	void rejectsMissingOrIncorrectAssetExpectations() throws Exception {
		Fixture fixture = fixture();
		execute(fixture.database(), """
				UPDATE exams
				SET expected_question_booklet_count = 2
				WHERE id = 31
				""");
		IOException mismatch = assertThrows(IOException.class, () -> fixture.preflight().check(fixture.exam(), true));
		assertTrue(mismatch.getMessage().contains("booklet count"));
		execute(fixture.database(), """
				UPDATE exams
				SET expected_question_booklet_count = 1,
				    expected_answer_file_count = NULL
				WHERE id = 31
				""");
		IOException missing = assertThrows(IOException.class, () -> fixture.preflight().check(fixture.exam(), true));
		assertTrue(missing.getMessage().contains("Answer file count"));
	}

	@Test
	void rejectsSyllabusWithoutCurriculum() throws Exception {
		Fixture fixture = fixture();
		execute(fixture.database(), """
				DELETE FROM curriculum_nodes
				WHERE syllabus_version_id = 12
				""");
		IOException failure = assertThrows(IOException.class, () -> fixture.preflight().check(fixture.exam(), false));
		assertTrue(failure.getMessage().contains("no usable"));
	}

	@Test
	void rejectsUnregisteredExam() throws Exception {
		Fixture fixture = fixture();
		Exam unknown = new Exam(999, new Subject(1, "Biology"), new ExamProvider(1, "QCAA"), 2025, "Unknown Exam");
		assertThrows(IllegalArgumentException.class, () -> fixture.preflight().check(unknown, false));
	}

	@Test
	void requiresOriginal2019ForLegacyCapture() throws Exception {
		Fixture fixture = fixture();
		execute(fixture.database(), """
				UPDATE syllabus_versions
				SET syllabus_name = '2018'
				WHERE id = 11
				""");
		IOException failure = assertThrows(IOException.class, () -> fixture.preflight().check(fixture.exam(), true));
		assertTrue(failure.getMessage().contains("original 2019"));
		ExamAssignmentPreflight.Result nonLegacy = fixture.preflight().check(fixture.exam(), false);
		assertNull(nonLegacy.legacy2019Syllabus());
		assertEquals("2025", nonLegacy.currentSyllabus().getName());
	}

	private void execute(SqliteDatabase database, String sql) throws Exception {
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}

	private Fixture fixture() throws Exception {
		ManagedDataLayout layout = new ManagedDataLayout(directory.resolve("data"));
		SqliteDatabase database = new SqliteDatabase(directory.resolve("questionbank.db"));
		database.initialiseSchema();
		Path questionPdf = layout.resolve("subjects/Biology/exams/questions.pdf");
		Path answerPdf = layout.resolve("subjects/Biology/exams/answers.pdf");
		Files.createDirectories(questionPdf.getParent());
		Files.writeString(questionPdf, "Question PDF fixture");
		Files.writeString(answerPdf, "Answer PDF fixture");
		execute(database, """
				INSERT INTO subjects (id, subject_name)
				VALUES (1, 'Biology')
				""");
		execute(database, """
				INSERT INTO syllabus_versions (
				    id, subject_id, syllabus_name, is_current
				)
				VALUES
				    (11, 1, '2019', 0),
				    (12, 1, '2025', 1)
				""");
		execute(database, """
				INSERT INTO curriculum_nodes (
				    id, syllabus_version_id, parent_id,
				    curriculum_code, curriculum_name,
				    curriculum_level, display_order
				)
				VALUES
				    (101, 11, NULL, '1', 'Legacy Unit', 'UNIT', 0),
				    (102, 12, NULL, '1', 'Current Unit', 'UNIT', 0)
				""");
		execute(database, """
				INSERT INTO exam_providers (id, provider_name)
				VALUES (1, 'QCAA')
				""");
		execute(database, """
				INSERT INTO source_documents (id, relative_path)
				VALUES
				    (51, 'subjects/Biology/exams/questions.pdf'),
				    (52, 'subjects/Biology/exams/answers.pdf')
				""");
		execute(database, """
				INSERT INTO exams (
				    id, subject_id, provider_id, exam_year,
				    exam_name, expected_question_booklet_count,
				    expected_answer_file_count
				)
				VALUES
				    (31, 1, 1, 2025, 'External Assessment', 1, 1)
				""");
		execute(database, """
				INSERT INTO exam_booklets (
				    id, exam_id, source_document_id, booklet_name
				)
				VALUES (61, 31, 51, 'Paper 1')
				""");
		execute(database, """
				INSERT INTO answer_files (
				    id, exam_id, source_document_id, answer_file_name
				)
				VALUES (71, 31, 52, 'Marking Guide')
				""");
		execute(database, """
				UPDATE exam_booklets
				SET answer_file_id = 71
				WHERE id = 61
				""");
		Exam exam = new Exam(31, new Subject(1, "Biology"), new ExamProvider(1, "QCAA"), 2025, "External Assessment");
		return new Fixture(new ExamAssignmentPreflight(database, layout), database, exam, questionPdf, answerPdf);
	}

	private record Fixture(ExamAssignmentPreflight preflight, SqliteDatabase database, Exam exam, Path questionPdf,
			Path answerPdf) {
	}
}
