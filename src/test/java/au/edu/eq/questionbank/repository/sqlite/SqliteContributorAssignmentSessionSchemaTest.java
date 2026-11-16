package au.edu.eq.questionbank.repository.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;

class SqliteContributorAssignmentSessionSchemaTest {

	@TempDir
	Path directory;

	@Test
	void detectsMissingSessionProtectionTrigger() throws Exception {
		SqliteDatabase database = new SqliteDatabase(directory.resolve("missing-trigger.db"));
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					DROP TRIGGER
					trg_contributor_assignment_session_no_update
					""");
		}
		SQLException failure = assertThrows(SQLException.class, database::initialiseSchema);
		assertTrue(failure.getMessage().contains("Missing contributor assignment trigger"));
	}

	@Test
	void migratesVersion28ToVersion29() throws Exception {
		SqliteDatabase database = new SqliteDatabase(directory.resolve("migration.db"));
		database.initialiseSchema();
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				try (Statement statement = connection.createStatement()) {
					HistoricalSchemaFixture.removeVersion29Objects(statement);
					statement.execute("""
							UPDATE schema_version
							SET version = 28
							WHERE version = 29
							""");
				}
				connection.commit();
			} catch (SQLException failure) {
				connection.rollback();
				throw failure;
			}
		}
		assertEquals(28, database.schemaVersion());
		database.verifySchema();
		database.initialiseSchema();
		assertEquals(29, database.schemaVersion());
		database.verifySchema();
		database.verifyIntegrity();
		try (Connection connection = database.openConnection()) {
			assertNull(activeAssignment(connection));
			SqliteContributorAssignmentSessionTriggers.verify(connection);
		}
	}

	@Test
	void persistsSwitchingAndExitingAcrossConnections() throws Exception {
		SqliteDatabase database = populatedDatabase("switching.db");
		String first = UUID.randomUUID().toString();
		String second = UUID.randomUUID().toString();
		insertAssignment(database, first, 31);
		insertAssignment(database, second, 32);
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						UPDATE contributor_assignment_selection
						SET assignment_id = ?,
						    updated_at = ?
						WHERE singleton_id = 1
						""")) {
			statement.setString(1, first);
			statement.setString(2, Instant.now().toString());
			assertEquals(1, statement.executeUpdate());
			assertEquals(first, activeAssignment(connection));
			statement.setString(1, second);
			statement.setString(2, Instant.now().toString());
			assertEquals(1, statement.executeUpdate());
			assertEquals(second, activeAssignment(connection));
			statement.setNull(1, java.sql.Types.VARCHAR);
			statement.setString(2, Instant.now().toString());
			assertEquals(1, statement.executeUpdate());
			assertNull(activeAssignment(connection));
		}

		// Exiting assignment mode must not remove either installation.
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT COUNT(*)
						FROM contributor_assignment_sessions
						""")) {
			assertTrue(result.next());
			assertEquals(2, result.getInt(1));
		}

		// Reopen through a new database boundary, as on application restart.
		SqliteDatabase reopened = new SqliteDatabase(databasePath("switching.db"));
		reopened.verifySchema();
		try (Connection connection = reopened.openConnection()) {
			assertNull(activeAssignment(connection));
		}
	}

	@Test
	void preventsMutationOrDeletionOfInstalledEvidence() throws Exception {
		SqliteDatabase database = populatedDatabase("immutable.db");
		String assignmentId = UUID.randomUUID().toString();
		insertAssignment(database, assignmentId, 31);
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			assertThrows(SQLException.class, () -> statement.execute("""
					UPDATE contributor_assignment_sessions
					SET contributor_name = 'Changed'
					"""));
			assertThrows(SQLException.class, () -> statement.execute("""
					UPDATE contributor_assignment_sessions
					SET baseline_records = X'FF'
					"""));
			assertThrows(SQLException.class, () -> statement.execute("""
					DELETE FROM contributor_assignment_sessions
					"""));
			assertThrows(SQLException.class, () -> statement.execute("""
					DELETE FROM contributor_assignment_selection
					"""));
			assertThrows(SQLException.class, () -> statement.execute("""
					UPDATE contributor_assignment_selection
					SET singleton_id = 2
					"""));
		}
	}

	@Test
	void rejectsMissingAssignmentSelection() throws Exception {
		SqliteDatabase database = populatedDatabase("selection-fk.db");
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						UPDATE contributor_assignment_selection
						SET assignment_id = ?
						WHERE singleton_id = 1
						""")) {
			statement.setString(1, UUID.randomUUID().toString());
			assertThrows(SQLException.class, statement::executeUpdate);
			assertNull(activeAssignment(connection));
		}
	}

	private String activeAssignment(Connection connection) throws SQLException {
		try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("""
				SELECT assignment_id
				FROM contributor_assignment_selection
				WHERE singleton_id = 1
				""")) {
			assertTrue(result.next());
			String assignmentId = result.getString(1);
			assertFalse(result.next());
			return assignmentId;
		}
	}

	private Path databasePath(String filename) {
		return directory.resolve(filename);
	}

	private void insertAssignment(SqliteDatabase database, String assignmentId, long examId) throws Exception {
		SqlitePortableIdentityRepository identities = new SqlitePortableIdentityRepository(database);
		UUID subjectPortableId = identities.requireByLocalId(EntityType.SUBJECT, 1).portableId();
		UUID examPortableId = identities.requireByLocalId(EntityType.EXAM, examId).portableId();
		UUID syllabusPortableId = identities.requireByLocalId(EntityType.SYLLABUS_VERSION, 11).portableId();
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
			statement.setString(1, assignmentId);
			statement.setString(2, UUID.randomUUID().toString());
			statement.setString(3, UUID.randomUUID().toString());
			statement.setString(4, UUID.randomUUID().toString());
			statement.setString(5, subjectPortableId.toString());
			statement.setString(6, examPortableId.toString());
			statement.setString(7, syllabusPortableId.toString());
			statement.setString(8, UUID.randomUUID().toString());
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

	private SqliteDatabase populatedDatabase(String filename) throws Exception {
		SqliteDatabase database = new SqliteDatabase(databasePath(filename));
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
					    (31, 1, 21, 2025, 'External Assessment A'),
					    (32, 1, 21, 2024, 'External Assessment B')
					""");
		}
		return database;
	}
}
