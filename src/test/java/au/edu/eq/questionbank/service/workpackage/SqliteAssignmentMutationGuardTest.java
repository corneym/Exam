package au.edu.eq.questionbank.service.workpackage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class SqliteAssignmentMutationGuardTest {

	@TempDir
	Path directory;

	@Test
	void acceptsCaptureAndClassificationWithinAssignedExam() throws Exception {
		SqliteDatabase database = createDatabase();
		SqliteAssignmentMutationGuard guard = new SqliteAssignmentMutationGuard(1, 31, Set.of(11L, 12L));
		try (Connection connection = database.openConnection()) {
			assertDoesNotThrow(() -> guard.requireSubject(1));
			assertDoesNotThrow(() -> guard.requireExam(31));
			assertDoesNotThrow(() -> guard.requireBooklet(connection, 61));
			assertDoesNotThrow(() -> guard.requireQuestion(connection, 81));
			assertDoesNotThrow(() -> guard.requireAnswer(connection, 141));
			assertDoesNotThrow(() -> guard.requireAnswerFile(connection, 71));
			assertDoesNotThrow(() -> guard.requireSourceQuestion(connection, 91));
			assertDoesNotThrow(() -> guard.requireSharedContext(connection, 121));
			assertDoesNotThrow(() -> guard.requireClassificationNode(connection, 101));
			assertDoesNotThrow(() -> guard.requireClassificationNode(connection, 102));
		}
	}

	@Test
	void rejectsAuthoritativeStructuralMutations() {
		SqliteAssignmentMutationGuard guard = new SqliteAssignmentMutationGuard(1, 31, Set.of(12L));
		assertThrows(IllegalStateException.class, guard::denyAuthoritativeStructureChange);
	}

	@Test
	void rejectsCaptureAndClassificationOutsideAssignedExam() throws Exception {
		SqliteDatabase database = createDatabase();
		SqliteAssignmentMutationGuard guard = new SqliteAssignmentMutationGuard(1, 31, Set.of(11L, 12L));
		try (Connection connection = database.openConnection()) {
			assertThrows(IllegalStateException.class, () -> guard.requireSubject(2));
			assertThrows(IllegalStateException.class, () -> guard.requireExam(41));
			assertThrows(IllegalStateException.class, () -> guard.requireBooklet(connection, 63));
			assertThrows(IllegalStateException.class, () -> guard.requireQuestion(connection, 83));
			assertThrows(IllegalStateException.class, () -> guard.requireAnswer(connection, 143));
			assertThrows(IllegalStateException.class, () -> guard.requireAnswerFile(connection, 73));
			assertThrows(IllegalStateException.class, () -> guard.requireSourceQuestion(connection, 93));
			assertThrows(IllegalStateException.class, () -> guard.requireSharedContext(connection, 123));
			assertThrows(IllegalStateException.class, () -> guard.requireClassificationNode(connection, 103));
			assertThrows(IllegalStateException.class, () -> guard.requireClassificationNode(connection, 201));
		}
	}

	@Test
	void rejectsUnknownRecordsAndUnauthorisedSyllabusVersions() throws Exception {
		SqliteDatabase database = createDatabase();
		SqliteAssignmentMutationGuard guard = new SqliteAssignmentMutationGuard(1, 31, Set.of(12L));
		try (Connection connection = database.openConnection()) {
			assertThrows(IllegalStateException.class, () -> guard.requireBooklet(connection, 999));
			assertThrows(IllegalStateException.class, () -> guard.requireQuestion(connection, 999));
			assertThrows(IllegalStateException.class, () -> guard.requireClassificationNode(connection, 999));

			// The current syllabus is permitted.
			assertDoesNotThrow(() -> guard.requireSyllabusVersion(12));

			// The original 2019 syllabus is excluded when
			// legacy capture is disabled.
			assertThrows(IllegalStateException.class, () -> guard.requireSyllabusVersion(11));
		}
	}

	@Test
	void retainsAnImmutableSetOfPermittedSyllabuses() {
		Set<Long> allowed = new HashSet<>(Set.of(11L, 12L));
		SqliteAssignmentMutationGuard guard = new SqliteAssignmentMutationGuard(1, 31, allowed);
		allowed.clear();
		assertDoesNotThrow(() -> guard.requireSyllabusVersion(11));
		assertDoesNotThrow(() -> guard.requireSyllabusVersion(12));
		assertThrows(IllegalStateException.class, () -> guard.requireSyllabusVersion(21));
	}

	private SqliteDatabase createDatabase() throws Exception {
		SqliteDatabase database = new SqliteDatabase(directory.resolve("assignment-guard.db"));
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					INSERT INTO subjects (id, subject_name)
					VALUES
					    (1, 'Biology'),
					    (2, 'Chemistry')
					""");
			statement.execute("""
					INSERT INTO syllabus_versions (
					    id, subject_id, syllabus_name, is_current
					)
					VALUES
					    (11, 1, 'Biology 2019', 0),
					    (12, 1, 'Biology 2025', 1),
					    (13, 1, 'Biology 2022', 0),
					    (21, 2, 'Chemistry 2025', 1)
					""");
			statement.execute("""
					INSERT INTO curriculum_nodes (
					    id, syllabus_version_id, parent_id,
					    curriculum_code, curriculum_name,
					    curriculum_level, display_order
					)
					VALUES
					    (101, 11, NULL, 'B19', 'Legacy unit', 'UNIT', 0),
					    (102, 12, NULL, 'B25', 'Current unit', 'UNIT', 0),
					    (103, 13, NULL, 'B22', 'Other unit', 'UNIT', 0),
					    (201, 21, NULL, 'C25', 'Chemistry unit', 'UNIT', 0)
					""");
			statement.execute("""
					INSERT INTO exam_providers (id, provider_name)
					VALUES (1, 'QCAA')
					""");
			statement.execute("""
					INSERT INTO source_documents (id, relative_path)
					VALUES
					    (51, 'subjects/Biology/exams/questions.pdf'),
					    (52, 'subjects/Biology/exams/answers.pdf'),
					    (53, 'subjects/Chemistry/exams/questions.pdf'),
					    (54, 'subjects/Chemistry/exams/answers.pdf')
					""");
			statement.execute("""
					INSERT INTO exams (
					    id, subject_id, provider_id, exam_year, exam_name
					)
					VALUES
					    (31, 1, 1, 2025, 'Biology assessment'),
					    (41, 2, 1, 2025, 'Chemistry assessment')
					""");
			statement.execute("""
					INSERT INTO exam_booklets (
					    id, exam_id, source_document_id, booklet_name
					)
					VALUES
					    (61, 31, 51, 'Biology Paper'),
					    (63, 41, 53, 'Chemistry Paper')
					""");
			statement.execute("""
					INSERT INTO answer_files (
					    id, exam_id, source_document_id, answer_file_name
					)
					VALUES
					    (71, 31, 52, 'Biology answers'),
					    (73, 41, 54, 'Chemistry answers')
					""");
			statement.execute("""
					INSERT INTO source_questions (
					    id, booklet_id, source_question_code
					)
					VALUES
					    (91, 61, '1'),
					    (93, 63, '1')
					""");
			statement.execute("""
					INSERT INTO shared_question_contexts (
					    id, booklet_id, context_label
					)
					VALUES
					    (121, 61, 'Biology stimulus'),
					    (123, 63, 'Chemistry stimulus')
					""");
			statement.execute("""
					INSERT INTO questions (
					    id, booklet_id, classification_node_id,
					    question_code, question_text, marks,
					    shared_context_capture_required
					)
					VALUES
					    (81, 61, 102, '1', 'Biology question', 2, 0),
					    (83, 63, 201, '1', 'Chemistry question', 2, 0)
					""");
			statement.execute("""
					INSERT INTO answers (
					    id, question_id, answer_text
					)
					VALUES
					    (141, 81, 'Biology answer'),
					    (143, 83, 'Chemistry answer')
					""");
		}
		return database;
	}
}
