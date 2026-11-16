package au.edu.eq.questionbank.service.workpackage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class SqliteContributorAssignmentSessionRepositoryTest {

	@TempDir
	Path directory;

	@Test
	void loadsGuardFromSelectedAssignmentOnly() throws Exception {
		SqliteDatabase database = database("guard.db");
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();
		seedSession(database, first, 31);
		seedSession(database, second, 32);
		var sessions = new SqliteContributorAssignmentSessionRepository(database);
		assertTrue(sessions.activeGuard().isEmpty());
		sessions.select(first);
		SqliteAssignmentMutationGuard firstGuard = sessions.activeGuard().orElseThrow();
		firstGuard.requireSubject(1);
		firstGuard.requireExam(31);
		firstGuard.requireSyllabusVersion(11);
		assertThrows(IllegalStateException.class, () -> firstGuard.requireExam(32));
		sessions.select(second);
		SqliteAssignmentMutationGuard secondGuard = sessions.activeGuard().orElseThrow();
		secondGuard.requireExam(32);
		assertThrows(IllegalStateException.class, () -> secondGuard.requireExam(31));
		sessions.clearActive();
		assertTrue(sessions.activeGuard().isEmpty());
	}

	@Test
	void persistsSelectionAndExitAcrossRestarts() throws Exception {
		SqliteDatabase database = database("restart.db");
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();
		seedSession(database, first, 31);
		seedSession(database, second, 32);
		var sessions = new SqliteContributorAssignmentSessionRepository(database);
		assertTrue(sessions.active().isEmpty());
		assertEquals(2, sessions.findAll().size());
		sessions.select(first);
		assertEquals(first, sessions.active().orElseThrow().assignmentId());
		SqliteDatabase reopened = new SqliteDatabase(directory.resolve("restart.db"));
		var restarted = new SqliteContributorAssignmentSessionRepository(reopened);
		assertEquals(first, restarted.active().orElseThrow().assignmentId());
		restarted.select(second);
		assertEquals(second, sessions.active().orElseThrow().assignmentId());
		restarted.clearActive();
		assertTrue(sessions.active().isEmpty());
		assertEquals(2, sessions.findAll().size());

		// Exiting is not completion or deletion.
		sessions.select(first);
		assertEquals(first, sessions.active().orElseThrow().assignmentId());
	}

	@Test
	void refusesToSelectUnknownAssignment() throws Exception {
		SqliteDatabase database = database("unknown.db");
		UUID known = UUID.randomUUID();
		seedSession(database, known, 31);
		var sessions = new SqliteContributorAssignmentSessionRepository(database);
		sessions.select(known);
		assertThrows(IllegalArgumentException.class, () -> sessions.select(UUID.randomUUID()));
		assertEquals(known, sessions.active().orElseThrow().assignmentId());
	}

	@Test
	void returnsImmutableListOfStoredAssignments() throws Exception {
		SqliteDatabase database = database("immutable-list.db");
		UUID first = UUID.randomUUID();
		seedSession(database, first, 31);
		var sessions = new SqliteContributorAssignmentSessionRepository(database);
		List<SqliteContributorAssignmentSessionRepository.Session> found = sessions.findAll();
		assertEquals(1, found.size());
		assertEquals(first, found.getFirst().assignmentId());
		assertThrows(UnsupportedOperationException.class, () -> found.clear());
		assertTrue(sessions.find(first).isPresent());
		assertFalse(sessions.find(UUID.randomUUID()).isPresent());
	}

	private SqliteDatabase database(String filename) throws Exception {
		SqliteDatabase database = new SqliteDatabase(directory.resolve(filename));
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					INSERT INTO subjects (id, subject_name)
					VALUES (1, 'Chemistry')
					""");
			statement.execute("""
					INSERT INTO syllabus_versions (
					    id, subject_id, syllabus_name, is_current
					)
					VALUES (11, 1, 'Chemistry 2025', 1)
					""");
			statement.execute("""
					INSERT INTO exam_providers (id, provider_name)
					VALUES (21, 'QCAA')
					""");
			statement.execute("""
					INSERT INTO exams (
					    id, subject_id, provider_id,
					    exam_year, exam_name
					)
					VALUES
					    (31, 1, 21, 2025, 'Exam A'),
					    (32, 1, 21, 2024, 'Exam B')
					""");
		}
		return database;
	}

	private void seedSession(SqliteDatabase database, UUID assignmentId, long examId) throws Exception {
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						INSERT INTO contributor_assignment_sessions (
						    assignment_id,
						    package_id,
						    baseline_snapshot_id,
						    master_installation_id,
						    subject_portable_id,
						    exam_portable_id,
						    current_syllabus_portable_id,
						    contributor_id,
						    contributor_name,
						    subject_local_id,
						    exam_local_id,
						    current_syllabus_local_id,
						    issued_at,
						    installed_at,
						    archive_sha256,
						    records_sha256,
						    assets_sha256,
						    baseline_records,
						    baseline_assets
						)
						VALUES (
						    ?, ?, ?, ?, ?, ?, ?, ?, ?,
						    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
						)
						""")) {
			for (int index = 1; index <= 8; index++) {
				statement.setString(index, index == 1 ? assignmentId.toString() : UUID.randomUUID().toString());
			}
			statement.setString(9, "Test Contributor");
			statement.setLong(10, 1);
			statement.setLong(11, examId);
			statement.setLong(12, 11);
			statement.setString(13, Instant.now().toString());
			statement.setString(14, Instant.now().toString());
			statement.setString(15, "a".repeat(64));
			statement.setString(16, "b".repeat(64));
			statement.setString(17, "c".repeat(64));
			statement.setBytes(18, new byte[] { 1 });
			statement.setBytes(19, new byte[] { 2 });
			assertEquals(1, statement.executeUpdate());
		}
	}
}
