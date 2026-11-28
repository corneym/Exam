package au.edu.eq.questionbank.repository.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
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

class SqliteCorpusDisposalSchemaTest {

	@TempDir
	Path directory;

	@Test
	void failedVersion52MigrationRollsBackAndCanRetry() throws Exception {
		SqliteDatabase database = new SqliteDatabase(directory.resolve("v52-rollback.db"));
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			HistoricalSchemaFixture.removeVersion52Objects(statement);
			assertEquals(1, statement.executeUpdate("UPDATE schema_version SET version = 51 WHERE version = 52"));
			statement.execute("""
					CREATE TRIGGER reject_version52
					BEFORE UPDATE OF version ON schema_version
					WHEN NEW.version = 52
					BEGIN
					    SELECT RAISE(ABORT, 'simulated version 52 failure');
					END
					""");
		}
		assertThrows(SQLException.class, database::initialiseSchema);
		assertEquals(51, database.schemaVersion());
		assertTrue(tableExists(database, "mock_examinations"));
		assertFalse(tableExists(database, "corpus_disposal_operations"));
		assertFalse(tableExists(database, "portable_revision_history"));
		database.verifySchema();
		database.verifyIntegrity();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("DROP TRIGGER reject_version52");
		}
		database.initialiseSchema();
		assertEquals(52, database.schemaVersion());
		assertTrue(tableExists(database, "corpus_disposal_operations"));
		assertTrue(tableExists(database, "portable_revision_history"));
		database.verifySchema();
		database.verifyIntegrity();
	}

	@Test
	void freshSchemaAndReopenContainVerifiedVersion52Objects() throws Exception {
		Path path = directory.resolve("fresh-v52.db");
		SqliteDatabase database = new SqliteDatabase(path);
		database.initialiseSchema();
		assertEquals(52, database.schemaVersion());
		assertTrue(tableExists(database, "corpus_disposal_durable_states"));
		assertTrue(tableExists(database, "corpus_disposal_entities"));
		assertTrue(tableExists(database, "corpus_disposal_retired_entities"));
		assertTrue(tableExists(database, "corpus_disposal_assets"));
		assertTrue(tableExists(database, "portable_entity_cache_evictions"));
		database.verifySchema();
		database.verifyIntegrity();
		SqliteDatabase reopened = new SqliteDatabase(path);
		reopened.initialiseSchema();
		assertEquals(52, reopened.schemaVersion());
		reopened.verifySchema();
		reopened.verifyIntegrity();
	}

	@Test
	void releasingStateCannotCommitAndExactReleaseCannotRemainEnabled() throws Exception {
		SqliteDatabase database = SqliteAssignmentGuardFixture.create(directory.resolve("release-permit.db"), false);
		UUID assignmentId = assignmentId(database);
		UUID returnId = insertTerminalReturn(database, assignmentId);
		UUID operationId = insertOperation(database, assignmentId, returnId);
		UUID secondScope = UUID.randomUUID();
		UUID secondReturn = insertDetachedReturn(database, assignmentId, secondScope);
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			assertThrows(SQLException.class, () -> insertOperation(connection, UUID.randomUUID(), secondScope,
					secondReturn, "DATABASE_RELEASING"));
			connection.rollback();
		}
		UUID secondOperation = insertOperation(database, secondScope, secondReturn);
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			setReleasing(connection, operationId);
			assertThrows(SQLException.class, () -> setReleasing(connection, secondOperation));
			connection.rollback();
		}
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			setReleasing(connection, operationId);
			assertThrows(SQLException.class, connection::commit);
			connection.rollback();
		}
		assertEquals("PLANNED", operationState(database, operationId));
		assertTrue(sessionExists(database, assignmentId));
		assertThrows(SQLException.class, () -> deleteSessionDirectly(database, assignmentId));
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				setReleasing(connection, operationId);
				try (PreparedStatement clear = connection.prepareStatement("""
						UPDATE contributor_assignment_selection
						SET assignment_id = NULL,
						    updated_at = ?
						WHERE singleton_id = 1
						  AND assignment_id = ?
						""")) {
					clear.setString(1, Instant.now().toString());
					clear.setString(2, assignmentId.toString());
					clear.executeUpdate();
				}
				try (PreparedStatement delete = connection.prepareStatement("""
						DELETE FROM contributor_assignment_sessions
						WHERE assignment_id = ?
						""")) {
					delete.setString(1, assignmentId.toString());
					assertEquals(1, SqliteDirectChanges.executeUpdate(delete));
				}
				try (PreparedStatement released = connection.prepareStatement("""
						UPDATE corpus_disposal_operations
						SET state = 'DATABASE_RELEASED',
						    updated_at = ?,
						    database_released_at = ?
						WHERE operation_id = ?
						  AND state = 'DATABASE_RELEASING'
						""")) {
					String now = Instant.now().toString();
					released.setString(1, now);
					released.setString(2, now);
					released.setString(3, operationId.toString());
					assertEquals(1, SqliteDirectChanges.executeUpdate(released));
				}
				connection.commit();
			} catch (Exception failure) {
				connection.rollback();
				throw failure;
			}
		}
		assertFalse(sessionExists(database, assignmentId));
		assertEquals("DATABASE_RELEASED", operationState(database, operationId));
		database.verifySchema();
		database.verifyIntegrity();
	}

	@Test
	void revisionHistoryBackfillsAndRetainsEveryObservedRevision() throws Exception {
		SqliteDatabase database = new SqliteDatabase(directory.resolve("revision-history.db"));
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			HistoricalSchemaFixture.removeVersion52Objects(statement);
			assertEquals(1, statement.executeUpdate("UPDATE schema_version SET version = 51 WHERE version = 52"));
			statement.execute("INSERT INTO subjects (id, subject_name) VALUES (1, 'Biology')");
		}
		String originalRevision = scalar(database, """
				SELECT revision_id
				FROM portable_entity_identities
				WHERE entity_type = 'SUBJECT' AND local_id = 1
				""");
		database.initialiseSchema();
		assertEquals(originalRevision, scalar(database, """
				SELECT revision_id
				FROM portable_revision_history
				WHERE revision_id = '%s'
				""".formatted(originalRevision)));
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("UPDATE subjects SET subject_name = 'Life Science' WHERE id = 1");
			statement.execute("UPDATE subjects SET subject_name = 'Biological Science' WHERE id = 1");
		}
		assertEquals(3, count(database, """
				SELECT COUNT(*)
				FROM portable_revision_history history
				JOIN portable_entity_identities live
				  ON live.portable_id = history.portable_id
				WHERE live.entity_type = 'SUBJECT'
				  AND live.local_id = 1
				"""));
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("DELETE FROM subjects WHERE id = 1");
		}
		assertEquals(1, count(database, "SELECT COUNT(*) FROM portable_entity_retirements"));
		assertEquals(0, count(database, "SELECT COUNT(*) FROM portable_entity_cache_evictions"));
		assertEquals(3, count(database, "SELECT COUNT(*) FROM portable_revision_history"));
		database.verifySchema();
		database.verifyIntegrity();
	}

	@Test
	void migrationRetainsEveryImmutableAssignmentBaselineRevision() throws Exception {
		SqliteDatabase database = SqliteAssignmentGuardFixture.create(directory.resolve("baseline-history.db"), false);
		String questionId = scalar(database, """
				SELECT portable_id
				FROM portable_entity_identities
				WHERE entity_type = 'QUESTION'
				  AND local_id = 1
				""");
		String originalRevision = scalar(database, """
				SELECT revision_id
				FROM portable_entity_identities
				WHERE entity_type = 'QUESTION'
				  AND local_id = 1
				""");
		byte[] baseline = ("""
				{"recordVersion":1,"recordType":"QUESTION","recordId":"%s","revisionId":"%s","baselineRevisionId":null,"values":{}}
				"""
				.formatted(questionId, originalRevision)).getBytes(StandardCharsets.UTF_8);
		try (Connection connection = database.openConnection(); Statement schema = connection.createStatement()) {
			schema.execute("DROP TRIGGER trg_contributor_assignment_session_no_update");
			try (PreparedStatement statement = connection
					.prepareStatement("UPDATE contributor_assignment_sessions SET baseline_records = ?")) {
				statement.setBytes(1, baseline);
				assertEquals(1, statement.executeUpdate());
			}
			schema.execute("""
					CREATE TRIGGER trg_contributor_assignment_session_no_update
					BEFORE UPDATE ON contributor_assignment_sessions
					BEGIN
					    SELECT RAISE(
					        ABORT,
					        'Installed assignment evidence cannot be modified'
					    );
					END
					""");
		}
		UUID replacementRevision = UUID.randomUUID();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			HistoricalSchemaFixture.removeVersion52Objects(statement);
			assertEquals(1, statement.executeUpdate("UPDATE schema_version SET version = 51 WHERE version = 52"));
		}
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						UPDATE portable_entity_identities
						SET revision_id = ?
						WHERE entity_type = 'QUESTION'
						  AND portable_id = ?
						""")) {
			statement.setString(1, replacementRevision.toString());
			statement.setString(2, questionId);
			assertEquals(1, statement.executeUpdate());
		}
		database.initialiseSchema();
		assertEquals(1, count(database, """
				SELECT COUNT(*)
				FROM portable_revision_history
				WHERE revision_id = '%s'
				  AND portable_id = '%s'
				  AND entity_type = 'QUESTION'
				""".formatted(originalRevision, questionId)));
		assertEquals(1, count(database, """
				SELECT COUNT(*)
				FROM portable_revision_history
				WHERE revision_id = '%s'
				  AND portable_id = '%s'
				  AND entity_type = 'QUESTION'
				""".formatted(replacementRevision, questionId)));
		database.verifySchema();
		database.verifyIntegrity();
	}

	@Test
	void historicalPortableIdentityCannotBeReusedForAnotherEntityType() throws Exception {
		SqliteDatabase database = new SqliteDatabase(directory.resolve("cross-entity-history.db"));
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("INSERT INTO subjects (id, subject_name) VALUES (1, 'Physics'), (2, 'Biology')");
			statement.execute("""
					INSERT INTO syllabus_versions (id, subject_id, syllabus_name, is_current)
					VALUES (11, 2, 'Biology 2026', 1)
					""");
		}
		String formerSubjectId = scalar(database, """
				SELECT portable_id
				FROM portable_entity_identities
				WHERE entity_type = 'SUBJECT' AND local_id = 1
				""");
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("DELETE FROM subjects WHERE id = 1")) {
			assertEquals(1, statement.executeUpdate());
		}
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try (Statement statement = connection.createStatement()) {
				statement.execute("DROP TRIGGER app_portable_identity_immutable");
				assertThrows(SQLException.class, () -> statement.executeUpdate("""
						UPDATE portable_entity_identities
						SET portable_id = '%s'
						WHERE entity_type = 'SYLLABUS_VERSION' AND local_id = 11
						""".formatted(formerSubjectId)));
			} finally {
				connection.rollback();
			}
		}
		database.verifySchema();
		database.verifyIntegrity();
	}

	private UUID assignmentId(SqliteDatabase database) throws SQLException {
		return UUID.fromString(scalar(database, "SELECT assignment_id FROM contributor_assignment_sessions"));
	}

	private int count(SqliteDatabase database, String sql) throws SQLException {
		return Integer.parseInt(scalar(database, sql));
	}

	private void deleteSessionDirectly(SqliteDatabase database, UUID assignmentId) throws SQLException {
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection
						.prepareStatement("DELETE FROM contributor_assignment_sessions WHERE assignment_id = ?")) {
			statement.setString(1, assignmentId.toString());
			statement.executeUpdate();
		}
	}

	private UUID insertDetachedReturn(SqliteDatabase database, UUID sourceAssignmentId, UUID detachedAssignmentId)
			throws SQLException {
		UUID returnId = UUID.randomUUID();
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						INSERT INTO contributor_return_packages (
						    package_id, assignment_id, return_number, previous_package_id,
						    baseline_snapshot_id, master_installation_id, contributor_id,
						    origin_installation_id, archive_sha256, recorded_at,
						    assignment_package_id, assignment_archive_sha256,
						    subject_portable_id, exam_portable_id,
						    issued_at, installed_at, contribution_created_at
						)
						SELECT ?, ?, 1, NULL,
						       baseline_snapshot_id, master_installation_id, contributor_id,
						       origin_installation_id, archive_sha256, recorded_at,
						       assignment_package_id, assignment_archive_sha256,
						       subject_portable_id, exam_portable_id,
						       issued_at, installed_at, contribution_created_at
						FROM contributor_return_packages
						WHERE assignment_id = ?
						""")) {
			statement.setString(1, returnId.toString());
			statement.setString(2, detachedAssignmentId.toString());
			statement.setString(3, sourceAssignmentId.toString());
			assertEquals(1, statement.executeUpdate());
		}
		return returnId;
	}

	private UUID insertOperation(SqliteDatabase database, UUID assignmentId, UUID returnId) throws SQLException {
		UUID operationId = UUID.randomUUID();
		try (Connection connection = database.openConnection()) {
			insertOperation(connection, operationId, assignmentId, returnId, "PLANNED");
		}
		return operationId;
	}

	private void insertOperation(Connection connection, UUID operationId, UUID assignmentId, UUID returnId,
			String state) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO corpus_disposal_operations (
				    operation_id, policy, policy_version, scope_id, reason,
				    terminal_return_package_id, assignment_package_id,
				    assignment_archive_sha256, plan_sha256,
				    state, planned_at, updated_at
				)
				SELECT ?, 'CONTRIBUTOR_ASSIGNMENT', 1, ?, 'SUBMITTED',
				       package.package_id, package.assignment_package_id,
				       package.assignment_archive_sha256, ?, ?, ?, ?
				FROM contributor_return_packages package
				WHERE package.package_id = ?
				""")) {
			String now = Instant.now().toString();
			statement.setString(1, operationId.toString());
			statement.setString(2, assignmentId.toString());
			statement.setString(3, "d".repeat(64));
			statement.setString(4, state);
			statement.setString(5, now);
			statement.setString(6, now);
			statement.setString(7, returnId.toString());
			assertEquals(1, statement.executeUpdate());
		}
	}

	private UUID insertTerminalReturn(SqliteDatabase database, UUID assignmentId) throws SQLException {
		UUID returnId = UUID.randomUUID();
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						INSERT INTO contributor_return_packages (
						    package_id, assignment_id, return_number, previous_package_id,
						    baseline_snapshot_id, master_installation_id, contributor_id,
						    origin_installation_id, archive_sha256, recorded_at,
						    assignment_package_id, assignment_archive_sha256,
						    subject_portable_id, exam_portable_id,
						    issued_at, installed_at, contribution_created_at
						)
						SELECT ?, session.assignment_id, 1, NULL,
						       session.baseline_snapshot_id, session.master_installation_id,
						       session.contributor_id, installation.installation_id,
						       ?, ?, session.package_id, session.archive_sha256,
						       session.subject_portable_id, session.exam_portable_id,
						       session.issued_at, session.installed_at, ?
						FROM contributor_assignment_sessions session
						CROSS JOIN installation_identity installation
						WHERE session.assignment_id = ?
						  AND installation.singleton_id = 1
						""")) {
			String now = Instant.now().toString();
			statement.setString(1, returnId.toString());
			statement.setString(2, "e".repeat(64));
			statement.setString(3, now);
			statement.setString(4, now);
			statement.setString(5, assignmentId.toString());
			assertEquals(1, statement.executeUpdate());
		}
		return returnId;
	}

	private String operationState(SqliteDatabase database, UUID operationId) throws SQLException {
		return scalar(database,
				"SELECT state FROM corpus_disposal_operations WHERE operation_id = '" + operationId + "'");
	}

	private String scalar(SqliteDatabase database, String sql) throws SQLException {
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery(sql)) {
			assertTrue(result.next());
			String value = result.getString(1);
			assertFalse(result.next());
			return value;
		}
	}

	private boolean sessionExists(SqliteDatabase database, UUID assignmentId) throws SQLException {
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection
						.prepareStatement("SELECT 1 FROM contributor_assignment_sessions WHERE assignment_id = ?")) {
			statement.setString(1, assignmentId.toString());
			try (ResultSet result = statement.executeQuery()) {
				return result.next();
			}
		}
	}

	private void setReleasing(Connection connection, UUID operationId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				UPDATE corpus_disposal_operations
				SET state = 'DATABASE_RELEASING',
				    updated_at = ?
				WHERE operation_id = ?
				  AND state = 'PLANNED'
				""")) {
			statement.setString(1, Instant.now().toString());
			statement.setString(2, operationId.toString());
			assertEquals(1, SqliteDirectChanges.executeUpdate(statement));
		}
	}

	private boolean tableExists(SqliteDatabase database, String table) throws SQLException {
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT 1
						FROM sqlite_master
						WHERE type = 'table'
						  AND name = ?
						""")) {
			statement.setString(1, table);
			try (ResultSet result = statement.executeQuery()) {
				return result.next();
			}
		}
	}
}
