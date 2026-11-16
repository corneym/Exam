package au.edu.eq.questionbank.repository.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
import au.edu.eq.questionbank.service.workpackage.ContributorAssignmentDashboardContextResolver;
import au.edu.eq.questionbank.service.workpackage.SqliteContributorAssignmentSessionRepository;

class ContributorAssignmentDashboardContextResolverTest {

	@TempDir
	Path directory;

	@Test
	void ordinaryModeHasNoAssignmentContext() throws Exception {
		SqliteDatabase database = fixture();
		var sessions = new SqliteContributorAssignmentSessionRepository(database);
		UUID originalId = sessions.active().orElseThrow().assignmentId();
		sessions.clearActive();
		var resolver = new ContributorAssignmentDashboardContextResolver(database);
		assertTrue(resolver.active().isEmpty());

		// Exiting assignment mode must not delete its saved session.
		assertTrue(sessions.find(originalId).isPresent());
		var restored = resolver.require(originalId);
		assertEquals(31L, restored.session().examLocalId());
	}

	@Test
	void restoresAssignedSubjectAndExam() throws Exception {
		SqliteDatabase database = fixture();
		var resolver = new ContributorAssignmentDashboardContextResolver(database);
		var context = resolver.active().orElseThrow();
		assertEquals(1L, context.subject().getId());
		assertEquals("Biology", context.subject().getName());
		assertEquals(31L, context.session().examLocalId());
		assertEquals(context.subject().getId(), context.session().subjectLocalId());
	}

	@Test
	void selectionSurvivesNewRepositoryInstances() throws Exception {
		SqliteDatabase database = fixture();
		UUID assignmentId = new SqliteContributorAssignmentSessionRepository(database).active().orElseThrow()
				.assignmentId();
		SqliteDatabase reopened = new SqliteDatabase(directory.resolve("context.db"));
		var resolver = new ContributorAssignmentDashboardContextResolver(reopened);
		assertEquals(assignmentId, resolver.active().orElseThrow().session().assignmentId());
		assertFalse(resolver.active().isEmpty());
	}

	@Test
	void switchingAssignmentsAndExitingPreservesBothSessions() throws Exception {
		SqliteDatabase database = fixture();
		var sessions = new SqliteContributorAssignmentSessionRepository(database);
		UUID firstId = sessions.active().orElseThrow().assignmentId();
		UUID secondId = UUID.randomUUID();
		UUID secondExamPortableId = new SqlitePortableIdentityRepository(database).requireByLocalId(EntityType.EXAM, 32)
				.portableId();

		// Install a second independent assignment for another
		// Biology Exam, retaining its original source dependencies.
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						INSERT INTO contributor_assignment_sessions (
						    assignment_id, package_id,
						    baseline_snapshot_id,
						    master_installation_id,
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
						SELECT
						    ?, ?, ?,
						    master_installation_id,
						    subject_portable_id, ?,
						    current_syllabus_portable_id,
						    legacy_syllabus_portable_id,
						    ?, 'Second contributor',
						    subject_local_id, 32,
						    current_syllabus_local_id,
						    legacy_syllabus_local_id,
						    issued_at, installed_at,
						    archive_sha256, records_sha256,
						    assets_sha256, baseline_records,
						    baseline_assets
						FROM contributor_assignment_sessions
						WHERE assignment_id = ?
						""")) {
			statement.setString(1, secondId.toString());
			statement.setString(2, UUID.randomUUID().toString());
			statement.setString(3, UUID.randomUUID().toString());
			statement.setString(4, secondExamPortableId.toString());
			statement.setString(5, UUID.randomUUID().toString());
			statement.setString(6, firstId.toString());
			assertEquals(1, statement.executeUpdate());
		}
		var resolver = new ContributorAssignmentDashboardContextResolver(database);
		assertEquals(31L, resolver.active().orElseThrow().session().examLocalId());
		sessions.select(secondId);
		assertEquals(secondId, resolver.active().orElseThrow().session().assignmentId());
		assertEquals(32L, resolver.active().orElseThrow().session().examLocalId());

		// Exit mode without deleting either installed assignment.
		sessions.clearActive();
		assertTrue(resolver.active().isEmpty());
		assertEquals(2, sessions.findAll().size());

		// Re-enter each assignment and recover its original Exam.
		sessions.select(firstId);
		assertEquals(31L, resolver.active().orElseThrow().session().examLocalId());
		sessions.select(secondId);
		assertEquals(32L, resolver.active().orElseThrow().session().examLocalId());
		database.verifyIntegrity();
	}

	private SqliteDatabase fixture() throws Exception {
		return SqliteAssignmentGuardFixture.create(directory.resolve("context.db"), false);
	}
}
