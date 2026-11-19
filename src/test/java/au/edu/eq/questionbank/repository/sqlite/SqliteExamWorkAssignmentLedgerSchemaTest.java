package au.edu.eq.questionbank.repository.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteExamWorkAssignmentLedgerSchemaTest {

	@TempDir
	Path directory;

	@Test
	void alteredLedgerProtectionTriggerFailsSchemaVerification() throws Exception {
		SqliteDatabase database = new SqliteDatabase(directory.resolve("altered-ledger-trigger.db"));
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					DROP TRIGGER trg_exam_work_assignments_no_update
					""");

			// The name and owning table remain correct, but the
			// replacement no longer protects immutable baseline data.
			statement.execute("""
					CREATE TRIGGER trg_exam_work_assignments_no_update
					BEFORE UPDATE ON exam_work_assignments
					BEGIN
					    SELECT 1;
					END
					""");
		}
		SQLException failure = assertThrows(SQLException.class, database::verifySchema);
		assertTrue(failure.getMessage()
				.contains("Altered assignment protection trigger: " + "trg_exam_work_assignments_no_update"));
	}

	@Test
	void createsLatestSchemaWithProtectedCoordinatorLedger() throws Exception {
		SqliteDatabase database = new SqliteDatabase(directory.resolve("ledger-schema.db"));
		database.initialiseSchema();
		assertEquals(SqliteDatabase.latestSchemaVersion(), database.schemaVersion());
		try (Connection connection = database.openConnection()) {
			SqliteExamWorkAssignmentLedgerTriggers.verify(connection);
			SqliteContributorAssignmentSessionTriggers.verify(connection);
			SqliteAssignmentStructureTriggers.verify(connection);
		}
	}

	@Test
	void detectsMissingProtectionTriggerOnReinitialisation() throws Exception {
		SqliteDatabase database = new SqliteDatabase(directory.resolve("missing-trigger.db"));
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					DROP TRIGGER
					trg_exam_work_assignments_no_update
					""");
		}
		SQLException failure = assertThrows(SQLException.class, database::initialiseSchema);
		assertTrue(failure.getMessage().contains("Missing assignment protection trigger"));
	}

	@Test
	void migratesHistoricalVersion27FixtureThroughLatestSchema() throws Exception {
		SqliteDatabase database = new SqliteDatabase(directory.resolve("version-27-upgrade.db"));
		database.initialiseSchema();
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try (Statement statement = connection.createStatement()) {
				HistoricalSchemaFixture.removeVersion28Objects(statement);
				statement.execute("""
						UPDATE schema_version
						SET version = 27
						WHERE version = %d
						""".formatted(SqliteDatabase.latestSchemaVersion()));
				connection.commit();
			} catch (SQLException failure) {
				connection.rollback();
				throw failure;
			}
		}
		assertEquals(27, database.schemaVersion());
		database.verifySchema();
		database.initialiseSchema();
		assertEquals(SqliteDatabase.latestSchemaVersion(), database.schemaVersion());
		try (Connection connection = database.openConnection()) {
			SqliteExamWorkAssignmentLedgerTriggers.verify(connection);
			SqliteContributorAssignmentSessionTriggers.verify(connection);
			SqliteAssignmentStructureTriggers.verify(connection);
		}
		database.verifySchema();
		database.verifyIntegrity();
	}

	@Test
	void migratesVersion37ToAuditedAssignmentLifecycle() throws Exception {
		SqliteDatabase database = new SqliteDatabase(directory.resolve("version-38-upgrade.db"));
		database.initialiseSchema();
		assertEquals(SqliteDatabase.latestSchemaVersion(), database.schemaVersion());
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try (Statement statement = connection.createStatement()) {
				HistoricalSchemaFixture.removeVersion38Objects(statement);
				statement.execute("""
						UPDATE schema_version
						SET version = 37
						WHERE version = %d
						""".formatted(SqliteDatabase.latestSchemaVersion()));
				connection.commit();
			} catch (SQLException | RuntimeException failure) {
				connection.rollback();
				throw failure;
			}
		}
		assertEquals(37, database.schemaVersion());
		database.verifySchema();
		database.initialiseSchema();
		assertEquals(SqliteDatabase.latestSchemaVersion(), database.schemaVersion());
		database.verifySchema();
		database.verifyIntegrity();
	}

	@Test
	void migratesVersion38ToMasterStructureGuards() throws Exception {
		SqliteDatabase database = new SqliteDatabase(directory.resolve("version-39-upgrade.db"));
		database.initialiseSchema();
		assertEquals(SqliteDatabase.latestSchemaVersion(), database.schemaVersion());
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try (Statement statement = connection.createStatement()) {
				HistoricalSchemaFixture.removeVersion39Objects(statement);
				statement.execute("""
						UPDATE schema_version
						SET version = 38
						WHERE version = %d
						""".formatted(SqliteDatabase.latestSchemaVersion()));
				connection.commit();
			} catch (SQLException | RuntimeException failure) {
				connection.rollback();
				throw failure;
			}
		}
		assertEquals(38, database.schemaVersion());
		database.verifySchema();
		database.initialiseSchema();
		assertEquals(SqliteDatabase.latestSchemaVersion(), database.schemaVersion());
		database.verifySchema();
		database.verifyIntegrity();
	}

	@Test
	void migratesVersion39ToMasterCaptureAndReturnGuards() throws Exception {
		SqliteDatabase database = new SqliteDatabase(directory.resolve("version-40-upgrade.db"));
		database.initialiseSchema();
		assertEquals(SqliteDatabase.latestSchemaVersion(), database.schemaVersion());
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try (Statement statement = connection.createStatement()) {
				HistoricalSchemaFixture.removeVersion40Objects(statement);
				statement.execute("""
						UPDATE schema_version
						SET version = 39
						WHERE version = %d
						""".formatted(SqliteDatabase.latestSchemaVersion()));
				connection.commit();
			} catch (SQLException | RuntimeException failure) {
				connection.rollback();
				throw failure;
			}
		}
		assertEquals(39, database.schemaVersion());
		database.verifySchema();
		database.initialiseSchema();
		assertEquals(SqliteDatabase.latestSchemaVersion(), database.schemaVersion());
		database.verifySchema();
		database.verifyIntegrity();
	}

	@Test
	void missingMasterCaptureTriggerFailsSchemaVerification() throws Exception {
		SqliteDatabase database = new SqliteDatabase(directory.resolve("missing-capture-guard.db"));
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					DROP TRIGGER trg_master_capture_questions_update
					""");
		}
		SQLException failure = assertThrows(SQLException.class, database::verifySchema);
		assertTrue(failure.getMessage().contains("Missing master capture lock trigger"));
	}

	@Test
	void missingMasterContributionPermitTriggerFailsVerification() throws Exception {
		SqliteDatabase database = new SqliteDatabase(directory.resolve("missing-application-guard.db"));
		database.initialiseSchema();
		assertEquals(SqliteDatabase.latestSchemaVersion(), database.schemaVersion());
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					DROP TRIGGER trg_master_application_permit_validate
					""");
		}
		SQLException failure = assertThrows(SQLException.class, database::verifySchema);
		assertTrue(failure.getMessage().contains("Missing master contribution application trigger"));
		assertTrue(failure.getMessage().contains("trg_master_application_permit_validate"));
	}

	@Test
	void missingMasterPdfRegionPermitTriggerFailsVerification() throws Exception {
		SqliteDatabase database = new SqliteDatabase(directory.resolve("missing-region-permit.db"));
		database.initialiseSchema();
		assertEquals(SqliteDatabase.latestSchemaVersion(), database.schemaVersion());
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					DROP TRIGGER trg_master_region_permit_validate
					""");
		}
		SQLException failure = assertThrows(SQLException.class, database::verifySchema);
		assertTrue(failure.getMessage().contains("Missing master PDF-region authorisation trigger"));
		assertTrue(failure.getMessage().contains("trg_master_region_permit_validate"));
	}

	@Test
	void preventsChangesToIssuedBaselineAndTerminalState() throws Exception {
		SqliteDatabase database = new SqliteDatabase(directory.resolve("protected-baseline.db"));
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
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
					    '66666666-6666-4666-8666-666666666666',
					    '77777777-7777-4777-8777-777777777777',
					    'Contributor One',
					    '2026-11-16T00:00:00Z',
					    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
					    'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
					    'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc',
					    X'01',
					    X'02'
					)
					""");
			statement.execute("""
					INSERT INTO exam_work_assignment_states (
					    assignment_id,
					    state,
					    updated_at
					)
					VALUES (
					    '11111111-1111-4111-8111-111111111111',
					    'ACTIVE',
					    '2026-11-16T00:00:00Z'
					)
					""");
			assertThrows(SQLException.class, () -> statement.execute("""
					UPDATE exam_work_assignments
					SET contributor_name = 'Changed'
					"""));
			assertThrows(SQLException.class, () -> statement.execute("""
					DELETE FROM exam_work_assignments
					"""));

			// Direct lifecycle updates are no longer permitted.
			assertThrows(SQLException.class, () -> statement.execute("""
					UPDATE exam_work_assignment_states
					SET state = 'COMPLETE',
					    updated_at = '2026-11-17T00:00:00Z'
					"""));
			assertThrows(SQLException.class, () -> statement.execute("""
					DELETE FROM exam_work_assignment_exam_locks
					"""));

			// Inserting closure evidence performs the permitted
			// transition and unlocks the Exam atomically.
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
					    '2026-11-17T00:00:00Z'
					)
					""");
			try (ResultSet result = statement.executeQuery("""
					SELECT state
					FROM exam_work_assignment_states
					WHERE assignment_id =
					    '11111111-1111-4111-8111-111111111111'
					""")) {
				assertTrue(result.next());
				assertEquals("CANCELLED", result.getString(1));
			}
			try (ResultSet result = statement.executeQuery("""
					SELECT COUNT(*)
					FROM exam_work_assignment_exam_locks
					""")) {
				assertTrue(result.next());
				assertEquals(0, result.getInt(1));
			}
			assertThrows(SQLException.class, () -> statement.execute("""
					UPDATE exam_work_assignment_states
					SET state = 'ACTIVE'
					"""));
			assertThrows(SQLException.class, () -> statement.execute("""
					UPDATE exam_work_assignment_closures
					SET reason = 'Changed'
					"""));
			assertThrows(SQLException.class, () -> statement.execute("""
					DELETE FROM exam_work_assignment_closures
					"""));
			assertThrows(SQLException.class, () -> statement.execute("""
					DELETE FROM exam_work_assignment_states
					WHERE assignment_id =
					    '11111111-1111-4111-8111-111111111111'
					"""));
		}
		database.verifySchema();
		database.verifyIntegrity();
	}

	@Test
	void unexpectedLedgerProtectionTriggerFailsSchemaVerification() throws Exception {
		SqliteDatabase database = new SqliteDatabase(directory.resolve("unexpected-ledger-trigger.db"));
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {

			// Additional triggers in the protected namespace must be
			// rejected rather than silently accepted.
			statement.execute("""
					CREATE TRIGGER trg_exam_work_assignment_unexpected_guard
					BEFORE UPDATE ON exam_work_assignment_states
					BEGIN
					    SELECT 1;
					END
					""");
		}
		SQLException failure = assertThrows(SQLException.class, database::verifySchema);
		assertTrue(failure.getMessage()
				.contains("Unexpected assignment protection trigger: " + "trg_exam_work_assignment_unexpected_guard"));
	}

	@Test
	void version28SqlResourceInstallsExactLedgerProtectionTriggers() throws Exception {
		SqliteDatabase database = new SqliteDatabase(directory.resolve("v28-resource.db"));
		database.initialiseSchema();
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				try (Statement statement = connection.createStatement()) {

					// Reconstruct the original version-27 state
					// in a disposable test database.
					HistoricalSchemaFixture.removeVersion28Objects(statement);
					statement.execute("""
							UPDATE schema_version
							SET version = 27
							WHERE version = %d
							""".formatted(SqliteDatabase.latestSchemaVersion()));
				}

				// Install the schema and protection triggers exclusively
				// through the versioned SQL migration resource.
				SqlScriptExecutor.execute(connection, SqlResourceLoader.load("/db/migration-v27-to-v28.sql"));

				// Check the SQL-installed definitions against the
				// original Java definitions, without using install().
				SqliteExamWorkAssignmentLedgerTriggers.verify(connection);
				try (Statement statement = connection.createStatement(); var result = statement.executeQuery("""
						SELECT COUNT(*)
						FROM sqlite_master
						WHERE type = 'trigger'
						  AND name GLOB 'trg_exam_work_assignment*'
						""")) {
					assertTrue(result.next());
					assertEquals(5, result.getInt(1));
				}
				connection.commit();
			} catch (Exception failure) {
				connection.rollback();
				throw failure;
			}
		}
		assertEquals(28, database.schemaVersion());
		database.verifySchema();

		// Continue the historical database through every remaining
		// migration to the unchanged latest schema version.
		database.initialiseSchema();
		assertEquals(SqliteDatabase.latestSchemaVersion(), database.schemaVersion());
		database.verifySchema();
		database.verifyIntegrity();
	}
}
