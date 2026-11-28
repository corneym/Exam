package au.edu.eq.questionbank.repository.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.repository.sqlite.SqliteContributorAssignmentVisibilityRepository.State;
import au.edu.eq.questionbank.repository.sqlite.SqliteSubjectImportLedger.PackageEntry;
import au.edu.eq.questionbank.repository.sqlite.SqliteSubjectImportLedger.PackageOutcome;

/**
 * Verifies operating-context provenance and worker assignment visibility.
 */
class SqliteOperatingContextRepositoriesTest {

	@TempDir
	Path directory;

	@Test
	void contributorVisibilityFollowsPublishedReturnsAndLocalRemoval() throws Exception {
		SqliteDatabase database = database("contributor.db");
		insertSubject(database);
		insertExamDependencies(database);
		UUID activeId = UUID.randomUUID();
		UUID removedId = UUID.randomUUID();
		UUID submittedId = UUID.randomUUID();
		SessionEvidence active = insertSession(database, activeId);
		SessionEvidence removed = insertSession(database, removedId);
		SessionEvidence submitted = insertSession(database, submittedId);
		var visibility = new SqliteContributorAssignmentVisibilityRepository(database);
		assertEquals(State.ACTIVE, visibility.find(active.assignmentId()).orElseThrow());
		assertEquals(State.ACTIVE, visibility.find(removed.assignmentId()).orElseThrow());
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						INSERT INTO contributor_assignment_hidden (
						    assignment_id, removed_at
						)
						VALUES (?, ?)
						""")) {
			statement.setString(1, removed.assignmentId().toString());
			statement.setString(2, Instant.now().toString());
			assertEquals(1, statement.executeUpdate());
		}
		insertReturn(database, submitted);
		assertEquals(State.REMOVED, visibility.find(removed.assignmentId()).orElseThrow());
		assertEquals(State.SUBMITTED, visibility.find(submitted.assignmentId()).orElseThrow());
		assertEquals(List.of(active.assignmentId()), visibility.findActiveAssignmentIds());
		assertTrue(visibility.find(UUID.randomUUID()).isEmpty());
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						INSERT INTO contributor_assignment_hidden (
						    assignment_id, removed_at
						)
						VALUES (?, ?)
						""")) {
			statement.setString(1, submitted.assignmentId().toString());
			statement.setString(2, Instant.now().toString());
			assertThrows(SQLException.class, statement::executeUpdate);
		}
		database.verifySchema();
		database.verifyIntegrity();
	}

	@Test
	void importedAuthorityParticipatesInCallerRollback() throws Exception {
		SqliteDatabase database = database("rollback.db");
		insertSubject(database);
		var authority = new SqliteImportedSubjectAuthorityRepository(database);
		var ledger = new SqliteSubjectImportLedger(database);
		PackageEntry incoming = packageEntry(UUID.randomUUID(), UUID.randomUUID());
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			ledger.recordPackage(connection, incoming);
			authority.recordImported(connection, 1, incoming.packageId());
			connection.rollback();
		}
		assertTrue(authority.find(1).isEmpty());
		assertTrue(ledger.find(incoming.packageId()).isEmpty());
	}

	@Test
	void migrationRetainsExistingSubjectAsLocallyAuthoritative() throws Exception {
		SqliteDatabase database = database("local.db");
		insertSubject(database);
		var authority = new SqliteImportedSubjectAuthorityRepository(database);
		assertEquals(SqliteDatabase.latestSchemaVersion(), database.schemaVersion());
		assertFalse(authority.isImported(1));
		authority.requireLocallyAuthoritative(1);
		database.verifySchema();
		database.verifyIntegrity();
	}

	@Test
	void recordsImportedAuthorityAndAllowsOnlySameOriginRefresh() throws Exception {
		SqliteDatabase database = database("imported.db");
		insertSubject(database);
		var authority = new SqliteImportedSubjectAuthorityRepository(database);
		var ledger = new SqliteSubjectImportLedger(database);
		UUID origin = UUID.randomUUID();
		UUID subject = UUID.randomUUID();
		PackageEntry first = packageEntry(origin, subject);
		PackageEntry second = packageEntry(origin, subject);
		PackageEntry foreign = packageEntry(UUID.randomUUID(), subject);
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			assertTrue(ledger.recordPackage(connection, first));
			authority.recordImported(connection, 1, first.packageId());
			assertTrue(ledger.recordPackage(connection, second));
			authority.recordImported(connection, 1, second.packageId());
			assertTrue(ledger.recordPackage(connection, foreign));
			assertThrows(SQLException.class, () -> authority.recordImported(connection, 1, foreign.packageId()));
			connection.commit();
		}
		var stored = authority.find(1).orElseThrow();
		assertEquals(origin, stored.originInstallationId());
		assertEquals(subject, stored.originSubjectPortableId());
		assertEquals(second.packageId(), stored.latestPackageId());
		assertTrue(authority.isImported(1));
		assertThrows(IllegalStateException.class, () -> authority.requireLocallyAuthoritative(1));
		database.verifyIntegrity();
	}

	private SqliteDatabase database(String filename) throws SQLException {
		SqliteDatabase database = new SqliteDatabase(directory.resolve(filename));
		database.initialiseSchema();
		return database;
	}

	private void insertExamDependencies(SqliteDatabase database) throws SQLException {
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					INSERT INTO syllabus_versions (
					    id, subject_id, syllabus_name, is_current
					)
					VALUES (11, 1, 'Biology 2025', 1)
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
	}

	private void insertReturn(SqliteDatabase database, SessionEvidence evidence) throws SQLException {
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						INSERT INTO contributor_return_packages (
						    package_id, assignment_id, return_number, previous_package_id,
						    baseline_snapshot_id, master_installation_id, contributor_id,
						    origin_installation_id, archive_sha256, recorded_at,
						    assignment_package_id, assignment_archive_sha256,
						    subject_portable_id, exam_portable_id, issued_at, installed_at,
						    contribution_created_at
						)
						SELECT ?, session.assignment_id, 1, NULL,
						       session.baseline_snapshot_id,
						       session.master_installation_id,
						       session.contributor_id,
						       ?, ?, ?,
						       session.package_id,
						       session.archive_sha256,
						       session.subject_portable_id,
						       session.exam_portable_id,
						       session.issued_at,
						       session.installed_at,
						       ?
						FROM contributor_assignment_sessions session
						WHERE session.assignment_id = ?
						""")) {
			statement.setString(1, UUID.randomUUID().toString());
			statement.setString(2, UUID.randomUUID().toString());
			statement.setString(3, "d".repeat(64));
			statement.setString(4, Instant.now().toString());
			statement.setString(5, Instant.now().toString());
			statement.setString(6, evidence.assignmentId().toString());
			assertEquals(1, statement.executeUpdate());
		}
	}

	private SessionEvidence insertSession(SqliteDatabase database, UUID assignmentId) throws SQLException {
		SessionEvidence evidence = new SessionEvidence(assignmentId, UUID.randomUUID(), UUID.randomUUID(),
				UUID.randomUUID());
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
						VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?,
						        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
						""")) {
			statement.setString(1, evidence.assignmentId().toString());
			statement.setString(2, UUID.randomUUID().toString());
			statement.setString(3, evidence.baselineId().toString());
			statement.setString(4, evidence.masterId().toString());
			statement.setString(5, UUID.randomUUID().toString());
			statement.setString(6, UUID.randomUUID().toString());
			statement.setString(7, UUID.randomUUID().toString());
			statement.setString(8, evidence.contributorId().toString());
			statement.setString(9, "Test Contributor");
			statement.setLong(10, 1);
			statement.setLong(11, 31);
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
		return evidence;
	}

	private void insertSubject(SqliteDatabase database) throws SQLException {
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					INSERT INTO subjects (id, subject_name)
					VALUES (1, 'Biology')
					""");
		}
	}

	private PackageEntry packageEntry(UUID origin, UUID subject) {
		return new PackageEntry(UUID.randomUUID(), "a".repeat(64), origin, subject, "b".repeat(64), "c".repeat(64),
				PackageOutcome.APPLIED, directory.resolve("verified-backup.zip").toString(),
				"Approved independent Subject import");
	}

	private record SessionEvidence(UUID assignmentId, UUID baselineId, UUID masterId, UUID contributorId) {
	}
}
