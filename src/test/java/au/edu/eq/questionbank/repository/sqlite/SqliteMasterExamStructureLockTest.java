package au.edu.eq.questionbank.repository.sqlite;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.repository.assessment.SqliteMasterExamLockGuard;

class SqliteMasterExamStructureLockTest {

	@TempDir
	Path directory;

	@Test
	void activeAssignmentBlocksStructuralMutationsUntilClosure() throws Exception {
		SqliteDatabase database = new SqliteDatabase(directory.resolve("structure-lock.db"));
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					INSERT INTO subjects (id, subject_name)
					VALUES (1, 'Biology')
					""");
			statement.execute("""
					INSERT INTO exam_providers (id, provider_name)
					VALUES (1, 'QCAA')
					""");
			statement.execute("""
					INSERT INTO source_documents (id, relative_path)
					VALUES
					    (51, 'subjects/Biology/questions.pdf'),
					    (52, 'subjects/Biology/answers.pdf')
					""");
			statement.execute("""
					INSERT INTO exams (
					    id, subject_id, provider_id, exam_year,
					    exam_name, capture_state
					)
					VALUES
					    (31, 1, 1, 2025, 'External', 'ACTIVE'),
					    (32, 1, 1, 2024, 'External', 'ACTIVE')
					""");
			statement.execute("""
					INSERT INTO exam_booklets (
					    id, exam_id, source_document_id, booklet_name
					)
					VALUES (41, 31, 51, 'Paper 1')
					""");
			statement.execute("""
					INSERT INTO answer_files (
					    id, exam_id, source_document_id, answer_file_name
					)
					VALUES (61, 31, 52, 'Answers')
					""");
			String examPortableId;
			try (ResultSet result = statement.executeQuery("""
					SELECT portable_id
					FROM portable_entity_identities
					WHERE entity_type = 'EXAM'
					  AND local_id = 31
					""")) {
				assertTrue(result.next());
				examPortableId = result.getString(1);
			}
			statement.execute("""
					INSERT INTO exam_work_assignments (
					    assignment_id,
					    package_id,
					    baseline_snapshot_id,
					    master_installation_id,
					    subject_portable_id,
					    exam_portable_id,
					    contributor_id,
					    contributor_name,
					    issued_at,
					    archive_sha256,
					    records_sha256,
					    assets_sha256,
					    baseline_records,
					    baseline_assets
					)
					VALUES (
					    '11111111-1111-4111-8111-111111111111',
					    '22222222-2222-4222-8222-222222222222',
					    '33333333-3333-4333-8333-333333333333',
					    '44444444-4444-4444-8444-444444444444',
					    '55555555-5555-4555-8555-555555555555',
					    '%s',
					    '77777777-7777-4777-8777-777777777777',
					    'Contributor One',
					    '2026-11-18T00:00:00Z',
					    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
					    'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
					    'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc',
					    X'01',
					    X'02'
					)
					""".formatted(examPortableId));
			statement.execute("""
					INSERT INTO exam_work_assignment_states (
					    assignment_id, state, updated_at
					)
					VALUES (
					    '11111111-1111-4111-8111-111111111111',
					    'ACTIVE',
					    '2026-11-18T00:00:00Z'
					)
					""");
			SqliteMasterExamLockGuard guard = new SqliteMasterExamLockGuard(database);
			assertThrows(IllegalStateException.class, () -> guard.requireUnlocked(31));

			// An unrelated Exam remains editable even within
			// the same Subject and provider.
			guard.requireUnlocked(32);
			statement.execute("""
					UPDATE exams
					SET exam_name = 'Revised External'
					WHERE id = 32
					""");
			assertAssignedExamBlocked(statement, """
					UPDATE exams
					SET exam_name = 'Modified'
					WHERE id = 31
					""");
			assertAssignedExamBlocked(statement, """
					UPDATE exam_booklets
					SET booklet_name = 'Modified Booklet'
					WHERE id = 41
					""");
			assertAssignedExamBlocked(statement, """
					UPDATE answer_files
					SET answer_file_name = 'Modified Answers'
					WHERE id = 61
					""");
			assertAssignedExamBlocked(statement, """
					UPDATE source_documents
					SET relative_path = 'changed-questions.pdf'
					WHERE id = 51
					""");
			assertAssignedExamBlocked(statement, """
					DELETE FROM exam_booklets
					WHERE id = 41
					""");
			assertAssignedExamBlocked(statement, """
					INSERT INTO exam_booklets (
					    id, exam_id, source_document_id, booklet_name
					)
					VALUES (42, 31, 51, 'New Booklet')
					""");
			assertAssignedExamBlocked(statement, """
					UPDATE subjects
					SET subject_name = 'Renamed Biology'
					WHERE id = 1
					""");
			assertAssignedExamBlocked(statement, """
					UPDATE exam_providers
					SET provider_name = 'Renamed QCAA'
					WHERE id = 1
					""");

			// A coordinator closure atomically releases the Exam.
			statement.execute("""
					INSERT INTO exam_work_assignment_closures (
					    assignment_id,
					    closed_state,
					    closure_kind,
					    reason,
					    closed_at
					)
					VALUES (
					    '11111111-1111-4111-8111-111111111111',
					    'CANCELLED',
					    'WITHOUT_RETURN',
					    'Contributor unavailable',
					    '2026-11-18T01:00:00Z'
					)
					""");
			guard.requireUnlocked(31);
			statement.execute("""
					UPDATE exams
					SET exam_name = 'Available Again'
					WHERE id = 31
					""");
			statement.execute("""
					UPDATE source_documents
					SET relative_path = 'new-questions.pdf'
					WHERE id = 51
					""");
		}
		database.verifySchema();
		database.verifyIntegrity();
	}

	@Test
	void missingMasterStructureTriggerFailsVerification() throws Exception {
		SqliteDatabase database = new SqliteDatabase(directory.resolve("missing-guard.db"));
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					DROP TRIGGER trg_master_exam_structure_exams_update
					""");
		}
		SQLException failure = assertThrows(SQLException.class, database::verifySchema);
		assertTrue(failure.getMessage().contains("Missing master Exam structure trigger"));
	}

	private void assertAssignedExamBlocked(Statement statement, String sql) {
		SQLException failure = assertThrows(SQLException.class, () -> statement.execute(sql));
		assertTrue(failure.getMessage().contains("Assigned Exam is read-only on the master"), failure.getMessage());
	}
}
