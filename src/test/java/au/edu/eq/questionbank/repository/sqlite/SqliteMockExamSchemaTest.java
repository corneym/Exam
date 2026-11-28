package au.edu.eq.questionbank.repository.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteMockExamSchemaTest {

	@TempDir
	Path tempDirectory;

	@Test
	void freshSchemaContainsVerifiedExaminationStructures() throws Exception {
		SqliteDatabase database = createDatabase("fresh.db");
		assertEquals(SqliteDatabase.latestSchemaVersion(), database.schemaVersion());
		database.verifySchema();
		database.verifyIntegrity();
		database.initialiseSchema();
		assertEquals(SqliteDatabase.latestSchemaVersion(), database.schemaVersion());
		database.verifySchema();
		database.verifyIntegrity();
	}

	@Test
	void historicalCleanupRemovesV51BeforeEarlierMigration() throws Exception {
		SqliteDatabase database = createDatabase("earlier.db");
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			HistoricalSchemaFixture.removeVersion27Objects(statement);
			assertEquals(1, statement.executeUpdate("""
					UPDATE schema_version
					SET version = 26
					WHERE version = %d
					""".formatted(SqliteDatabase.latestSchemaVersion())));
		}
		assertEquals(26, database.schemaVersion());
		assertNoV51Objects(database);
		database.verifySchema();
		database.initialiseSchema();
		assertEquals(SqliteDatabase.latestSchemaVersion(), database.schemaVersion());
		database.verifySchema();
		database.verifyIntegrity();
	}

	@Test
	void migrationFailureRollsBackAllV51ObjectsAndCanBeRetried() throws Exception {
		SqliteDatabase database = createDatabase("retry.db");
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			HistoricalSchemaFixture.removeVersion51Objects(statement);
			assertEquals(1, statement.executeUpdate("""
					UPDATE schema_version
					SET version = 50
					WHERE version = %d
					""".formatted(SqliteDatabase.latestSchemaVersion())));
			statement.execute("""
					CREATE TRIGGER test_reject_v51
					BEFORE UPDATE OF version ON schema_version
					WHEN NEW.version = 51
					BEGIN
					    SELECT RAISE(ABORT, 'Injected v51 migration failure');
					END
					""");
		}
		database.verifySchema();
		assertThrows(SQLException.class, database::initialiseSchema);
		assertEquals(50, database.schemaVersion());
		assertNoV51Objects(database);
		database.verifySchema();
		database.verifyIntegrity();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("DROP TRIGGER test_reject_v51");
		}
		database.initialiseSchema();
		assertEquals(SqliteDatabase.latestSchemaVersion(), database.schemaVersion());
		database.verifySchema();
		database.verifyIntegrity();
	}

	@Test
	void schemaVerificationRejectsAlteredReservationIndex() throws Exception {
		SqliteDatabase database = createDatabase("altered-index.db");
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("DROP INDEX ux_mock_exam_active_reservation");

			// Omitting the partial predicate would incorrectly
			// prevent reuse after a manual annual reset.
			statement.execute("""
					CREATE UNIQUE INDEX ux_mock_exam_active_reservation
					ON mock_exam_reservations (
					    subject_portable_id,
					    cohort_year,
					    entity_type,
					    entity_portable_id
					)
					""");
		}
		SQLException failure = assertThrows(SQLException.class, database::verifySchema);
		assertTrue(failure.getMessage()
				.contains("Altered mock examination schema object: " + "ux_mock_exam_active_reservation"));
	}

	@Test
	void schemaVerificationRejectsMissingFinalisationTrigger() throws Exception {
		SqliteDatabase database = createDatabase("missing-trigger.db");
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("DROP TRIGGER trg_mock_exam_finalise");
		}
		SQLException failure = assertThrows(SQLException.class, database::verifySchema);
		assertTrue(failure.getMessage().contains("Missing mock examination lifecycle trigger"));
	}

	private void assertNoV51Objects(SqliteDatabase database) throws SQLException {
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery("""
						SELECT name
						FROM sqlite_master
						WHERE name IN (
						    'mock_examinations',
						    'mock_exam_selections',
						    'mock_exam_reservations',
						    'ux_mock_exam_active_reservation'
						)
						   OR name GLOB 'trg_mock_exam_*'
						""")) {
			assertFalse(rows.next());
		}
	}

	private SqliteDatabase createDatabase(String filename) throws SQLException {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve(filename));
		database.initialiseSchema();
		return database;
	}
}
