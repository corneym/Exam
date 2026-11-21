package au.edu.eq.questionbank.repository.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
import au.edu.eq.questionbank.service.workpackage.WorkAssignmentLedger;

/**
 * Verifies that coordinator Exam read-only state follows durable active locks.
 */
class SqliteCoordinatorExamLockRepositoryTest {

	@TempDir
	Path directory;

	@Test
	void issuingAndCancellingAssignmentChangesExamLockVisibility() throws Exception {
		SqliteDatabase database = new SqliteDatabase(directory.resolve("locks.db"));
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					INSERT INTO subjects (id, subject_name)
					VALUES (1, 'Biology')
					""");
			statement.execute("""
					INSERT INTO exam_providers (id, provider_name)
					VALUES (21, 'QCAA')
					""");
			statement.execute("""
					INSERT INTO exams (
					    id, subject_id, provider_id, exam_year, exam_name
					)
					VALUES (31, 1, 21, 2025, 'External Assessment')
					""");
		}
		var identities = new SqlitePortableIdentityRepository(database);
		UUID subjectId = identities.requireByLocalId(EntityType.SUBJECT, 1).portableId();
		UUID examId = identities.requireByLocalId(EntityType.EXAM, 31).portableId();
		UUID assignmentId = UUID.randomUUID();
		SqliteCoordinatorExamLockRepository locks = new SqliteCoordinatorExamLockRepository(database);
		assertTrue(locks.find(31).isEmpty());
		assertTrue(locks.findForSubject(1).isEmpty());
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try (PreparedStatement statement = connection.prepareStatement("""
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
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
					""")) {
				statement.setString(1, assignmentId.toString());
				statement.setString(2, UUID.randomUUID().toString());
				statement.setString(3, UUID.randomUUID().toString());
				statement.setString(4, identities.installationId().toString());
				statement.setString(5, subjectId.toString());
				statement.setString(6, examId.toString());
				statement.setString(7, UUID.randomUUID().toString());
				statement.setString(8, "Test Contributor");
				statement.setString(9, Instant.now().toString());
				statement.setString(10, "a".repeat(64));
				statement.setString(11, "b".repeat(64));
				statement.setString(12, "c".repeat(64));
				statement.setBytes(13, new byte[] { 1 });
				statement.setBytes(14, new byte[] { 2 });
				assertEquals(1, statement.executeUpdate());
			}
			try (PreparedStatement statement = connection.prepareStatement("""
					INSERT INTO exam_work_assignment_states (
					    assignment_id, state, updated_at
					)
					VALUES (?, 'ACTIVE', ?)
					""")) {
				statement.setString(1, assignmentId.toString());
				statement.setString(2, Instant.now().toString());
				assertEquals(1, statement.executeUpdate());
			}
			connection.commit();
		}
		var locked = locks.find(31).orElseThrow();
		assertEquals(assignmentId, locked.assignmentId());
		assertEquals("Test Contributor", locked.contributorName());
		assertEquals(locked, locks.findForSubject(1).get(31L));
		new WorkAssignmentLedger(database).closeWithoutReturn(assignmentId, "Assignment withdrawn by coordinator");
		assertTrue(locks.find(31).isEmpty());
		assertTrue(locks.findForSubject(1).isEmpty());
		assertFalse(new WorkAssignmentLedger(database).find(assignmentId).isEmpty());
		database.verifySchema();
		database.verifyIntegrity();
	}
}
