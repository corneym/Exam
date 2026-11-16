package au.edu.eq.questionbank.repository.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteExamWorkAssignmentLedgerSchemaTest {

	@TempDir
	Path directory;

	@Test
	void createsVersion28WithProtectedLedger() throws Exception {
		SqliteDatabase database = new SqliteDatabase(directory.resolve("ledger-schema.db"));
		database.initialiseSchema();
		assertEquals(28, database.schemaVersion());
		try (Connection connection = database.openConnection()) {
			SqliteExamWorkAssignmentLedgerTriggers.verify(connection);
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
	void migratesHistoricalVersion27FixtureToVersion28() throws Exception {
		SqliteDatabase database = new SqliteDatabase(directory.resolve("version-27-to-28.db"));
		database.initialiseSchema();

		// Reconstruct a genuine version-27 schema in a disposable
		// database. Merely changing schema_version would leave the
		// version-28 tables present and invalidate the migration test.
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				try (Statement statement = connection.createStatement()) {
					HistoricalSchemaFixture.removeVersion28Objects(statement);
					statement.execute("""
							UPDATE schema_version
							SET version = 27
							WHERE version = 28
							""");
				}
				connection.commit();
			} catch (SQLException failure) {
				connection.rollback();
				throw failure;
			}
		}
		assertEquals(27, database.schemaVersion());

		// The reconstructed v27 database must be internally valid.
		database.verifySchema();

		// Exercise the production migration and its Java-installed
		// protection triggers.
		database.initialiseSchema();
		assertEquals(SqliteDatabase.latestSchemaVersion(), database.schemaVersion());
		try (Connection connection = database.openConnection()) {
			SqliteExamWorkAssignmentLedgerTriggers.verify(connection);
		}
		database.verifySchema();
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
					    assignment_id, state, updated_at
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
			statement.execute("""
					UPDATE exam_work_assignment_states
					SET state = 'COMPLETE',
					    updated_at = '2026-11-17T00:00:00Z'
					""");
			assertThrows(SQLException.class, () -> statement.execute("""
					UPDATE exam_work_assignment_states
					SET state = 'ACTIVE'
					"""));
			assertThrows(SQLException.class, () -> statement.execute("""
					DELETE FROM exam_work_assignment_states
					WHERE assignment_id =
					    '11111111-1111-4111-8111-111111111111'
					"""));
		}
	}
}
