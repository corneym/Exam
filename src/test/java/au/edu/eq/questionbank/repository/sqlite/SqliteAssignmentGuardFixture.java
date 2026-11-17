package au.edu.eq.questionbank.repository.sqlite;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.UUID;

import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;

final class SqliteAssignmentGuardFixture {

	private SqliteAssignmentGuardFixture() {
	}

	static SqliteDatabase create(Path path, boolean permitLegacy) throws SQLException {
		SqliteDatabase database = new SqliteDatabase(path);
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					INSERT INTO subjects (id, subject_name)
					VALUES (1, 'Biology'), (2, 'Chemistry')
					""");
			statement.execute("""
					INSERT INTO syllabus_versions (
					    id, subject_id, syllabus_name, is_current
					)
					VALUES
					    (11, 1, 'Biology 2025', 1),
					    (12, 1, 'Biology 2019', 0),
					    (21, 2, 'Chemistry 2025', 1)
					""");
			statement.execute("""
					INSERT INTO curriculum_nodes (
					    id, syllabus_version_id, parent_id,
					    curriculum_code, curriculum_name,
					    curriculum_level, display_order
					)
					VALUES
					    (101, 11, NULL, 'B25', 'Biology current', 'UNIT', 0),
					    (103, 11, 101, 'B25.T1', 'Biology Topic', 'TOPIC', 0),
					    (104, 11, 103, 'B25.T1.S1', 'Biology Subtopic', 'SUBTOPIC', 0),
					    (102, 12, NULL, 'B19', 'Biology legacy', 'UNIT', 0),
					    (105, 12, 102, 'B19.T1', 'Legacy Topic', 'TOPIC', 0),
					    (106, 12, 105, 'B19.T1.S1', 'Legacy Subtopic', 'SUBTOPIC', 0),
					    (201, 21, NULL, 'C25', 'Chemistry current', 'UNIT', 0)
					""");
			statement.execute("""
					INSERT INTO exam_providers (id, provider_name)
					VALUES (1, 'QCAA')
					""");
			statement.execute("""
					INSERT INTO source_documents (id, relative_path)
					VALUES (51, 'subjects/Biology/exams/test.pdf')
					""");
			statement.execute("""
					INSERT INTO exams (
					    id, subject_id, provider_id,
					    exam_year, exam_name
					)
					VALUES
					    (31, 1, 1, 2025, 'Assigned Exam'),
					    (32, 1, 1, 2024, 'Other Biology Exam'),
					    (41, 2, 1, 2025, 'Chemistry Exam')
					""");
			statement.execute("""
					INSERT INTO answer_files (
					    id, exam_id, source_document_id, answer_file_name
					)
					VALUES
					    (81, 31, 51, 'Assigned answers'),
					    (82, 32, 51, 'Other answers'),
					    (91, 41, 51, 'Chemistry answers')
					""");
			statement.execute("""
					INSERT INTO exam_booklets (
					    id, exam_id, source_document_id,
					    answer_file_id, booklet_name
					)
					VALUES
					    (61, 31, 51, 81, 'Assigned Booklet'),
					    (62, 32, 51, 82, 'Other Booklet'),
					    (71, 41, 51, 91, 'Chemistry Booklet')
					""");
			statement.execute("""
					INSERT INTO source_questions (
					    id, booklet_id, source_question_code
					)
					VALUES (401, 61, '1'), (402, 62, '1')
					""");
			statement.execute("""
					INSERT INTO shared_question_contexts (
					    id, booklet_id, context_label
					)
					VALUES
					    (301, 61, 'Assigned context'),
					    (302, 62, 'Other context')
					""");
			statement.execute("""
					INSERT INTO questions (
					    id, booklet_id, classification_node_id,
					    question_code, question_text, marks,
					    shared_context_capture_required
					)
					VALUES
					    (1, 61, 101, '1', 'Assigned Question', 2, 0),
					    (2, 62, 101, '1', 'Other Question', 2, 0),
					    (3, 71, 201, '1', 'Chemistry Question', 2, 0)
					""");
			statement.execute("""
					INSERT INTO answers (
					    id, question_id, answer_text
					)
					VALUES
					    (501, 1, 'Assigned response'),
					    (502, 2, 'Other response')
					""");
		}
		var identities = new SqlitePortableIdentityRepository(database);
		UUID subjectId = identities.requireByLocalId(EntityType.SUBJECT, 1).portableId();
		UUID examId = identities.requireByLocalId(EntityType.EXAM, 31).portableId();
		UUID syllabusId = identities.requireByLocalId(EntityType.SYLLABUS_VERSION, 11).portableId();
		UUID legacyId = identities.requireByLocalId(EntityType.SYLLABUS_VERSION, 12).portableId();
		UUID assignmentId = UUID.randomUUID();
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						INSERT INTO contributor_assignment_sessions (
						    assignment_id, package_id,
						    baseline_snapshot_id, master_installation_id,
						    subject_portable_id, exam_portable_id,
						    current_syllabus_portable_id,
						    legacy_syllabus_portable_id,
						    contributor_id, contributor_name,
						    subject_local_id, exam_local_id,
						    current_syllabus_local_id,
						    legacy_syllabus_local_id,
						    issued_at, installed_at,
						    archive_sha256, records_sha256,
						    assets_sha256, baseline_records,
						    baseline_assets
						)
						VALUES (
						    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
						    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
						)
						""")) {
			statement.setString(1, assignmentId.toString());
			statement.setString(2, UUID.randomUUID().toString());
			statement.setString(3, UUID.randomUUID().toString());
			statement.setString(4, UUID.randomUUID().toString());
			statement.setString(5, subjectId.toString());
			statement.setString(6, examId.toString());
			statement.setString(7, syllabusId.toString());
			if (permitLegacy) {
				statement.setString(8, legacyId.toString());
			} else {
				statement.setNull(8, Types.VARCHAR);
			}
			statement.setString(9, UUID.randomUUID().toString());
			statement.setString(10, "Test Contributor");
			statement.setLong(11, 1);
			statement.setLong(12, 31);
			statement.setLong(13, 11);
			if (permitLegacy) {
				statement.setLong(14, 12);
			} else {
				statement.setNull(14, Types.BIGINT);
			}
			statement.setString(15, Instant.now().toString());
			statement.setString(16, Instant.now().toString());
			statement.setString(17, "a".repeat(64));
			statement.setString(18, "b".repeat(64));
			statement.setString(19, "c".repeat(64));
			statement.setBytes(20, new byte[] { 1 });
			statement.setBytes(21, new byte[] { 2 });
			statement.executeUpdate();
		}
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						UPDATE contributor_assignment_selection
						SET assignment_id = ?,
						    updated_at = ?
						WHERE singleton_id = 1
						""")) {
			statement.setString(1, assignmentId.toString());
			statement.setString(2, Instant.now().toString());
			statement.executeUpdate();
		}
		return database;
	}
}
