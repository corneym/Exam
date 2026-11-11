package au.edu.eq.questionbank.repository.sqlite;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqlitePortableIdentityAuditTest {

	@TempDir
	Path tempDirectory;

	@Test
	void deletionAndLocalIdReusePassIntegrityAudit() throws Exception {
		SqliteDatabase database = createDatabase("reuse.db");
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					INSERT INTO subjects (id, subject_name)
					VALUES (1, 'Biology')
					""");
			statement.execute("""
					DELETE FROM subjects WHERE id = 1
					""");
			statement.execute("""
					INSERT INTO subjects (id, subject_name)
					VALUES (1, 'Chemistry')
					""");
		}
		assertDoesNotThrow(database::verifySchema);
		assertDoesNotThrow(database::verifyIntegrity);
	}

	@Test
	void missingIdentityCausesVerificationFailure() throws Exception {
		SqliteDatabase database = createDatabase("missing.db");
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					INSERT INTO subjects (id, subject_name)
					VALUES (1, 'Biology')
					""");
			statement.execute("""
					DELETE FROM portable_entity_identities
					WHERE entity_type = 'SUBJECT'
					  AND local_id = 1
					""");
		}
		SQLException failure = assertThrows(SQLException.class, database::verifySchema);
		assertTrue(failure.getMessage().contains("Missing active portable identity"));
	}

	@Test
	void orphanIdentityCausesVerificationFailure() throws Exception {
		SqliteDatabase database = createDatabase("orphan.db");
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					INSERT INTO portable_entity_identities (
					    entity_type, local_id, portable_id, revision_id
					)
					VALUES (
					    'SUBJECT', 999,
					    'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
					    'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
					)
					""");
		}
		SQLException failure = assertThrows(SQLException.class, database::verifySchema);
		assertTrue(failure.getMessage().contains("Orphan portable identity"));
	}

	@Test
	void retiredPortableUuidCannotBeReusedUnnoticed() throws Exception {
		SqliteDatabase database = createDatabase("retired.db");
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					INSERT INTO subjects (id, subject_name)
					VALUES (1, 'Biology')
					""");
			statement.execute("""
					DELETE FROM subjects WHERE id = 1
					""");
			statement.execute("""
					INSERT INTO subjects (id, subject_name)
					VALUES (1, 'Chemistry')
					""");

			// Simulate a corrupted registry that reclaims a retired
			// UUID for an unrelated current record.
			statement.execute("""
					DELETE FROM portable_entity_identities
					WHERE entity_type = 'SUBJECT'
					  AND local_id = 1
					""");
			statement.execute("""
					INSERT INTO portable_entity_identities (
					    entity_type, local_id, portable_id, revision_id
					)
					SELECT
					    'SUBJECT',
					    1,
					    portable_id,
					    lower(hex(randomblob(4))) || '-' ||
					    lower(hex(randomblob(2))) || '-4' ||
					    substr(lower(hex(randomblob(2))), 2) || '-' ||
					    '8' ||
					    substr(lower(hex(randomblob(2))), 2) || '-' ||
					    lower(hex(randomblob(6)))
					FROM portable_entity_retirements
					LIMIT 1
					""");
		}
		SQLException failure = assertThrows(SQLException.class, database::verifySchema);
		assertTrue(failure.getMessage().contains("Active identity conflicts"));
	}

	@Test
	void startupDoesNotRepairMissingIdentitiesImplicitly() throws Exception {
		SqliteDatabase database = createDatabase("startup.db");
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					INSERT INTO subjects (id, subject_name)
					VALUES (1, 'Biology')
					""");
			statement.execute("""
					DELETE FROM portable_entity_identities
					WHERE entity_type = 'SUBJECT'
					  AND local_id = 1
					""");
		}
		assertThrows(SQLException.class, database::initialiseSchema);

		// The failed startup must preserve its original schema
		// version, rather than running another identity backfill.
		assertTrue(database.schemaVersion() == SqliteDatabase.latestSchemaVersion());
	}

	private SqliteDatabase createDatabase(String filename) throws SQLException {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve(filename));
		database.initialiseSchema();
		return database;
	}
}
