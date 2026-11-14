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
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.repository.sqlite.SqliteSubjectImportLedger.CoordinatorDecision;
import au.edu.eq.questionbank.repository.sqlite.SqliteSubjectImportLedger.EntryKind;
import au.edu.eq.questionbank.repository.sqlite.SqliteSubjectImportLedger.ItemEntry;
import au.edu.eq.questionbank.repository.sqlite.SqliteSubjectImportLedger.ItemOutcome;
import au.edu.eq.questionbank.repository.sqlite.SqliteSubjectImportLedger.PackageEntry;
import au.edu.eq.questionbank.repository.sqlite.SqliteSubjectImportLedger.PackageOutcome;

/**
 * Verifies the Subject import-ledger migration, transaction handling,
 * idempotency and durable rejection provenance.
 */
class SqliteSubjectImportLedgerTest {

	@TempDir
	Path directory;

	/**
	 * Verifies that successful package and decision records survive closing and
	 * reopening the database.
	 */
	@Test
	void committedOutcomeIsDurableAndInspectable() throws Exception {
		SqliteDatabase database = database("committed.db");
		SqliteSubjectImportLedger ledger = new SqliteSubjectImportLedger(database);
		PackageEntry entry = appliedEntry();
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			assertTrue(ledger.recordPackage(connection, entry));
			ledger.recordItem(connection,
					new ItemEntry(entry.packageId(), EntryKind.RECORD, "QUESTION:" + UUID.randomUUID(), "CONFLICT",
							CoordinatorDecision.REJECT, ItemOutcome.REJECTED, null, null,
							"Conflicting Question content retained for review"));
			connection.commit();
		}
		assertEquals(entry, ledger.find(entry.packageId()).orElseThrow());
		assertEquals(1, ledger.findItems(entry.packageId()).size());
		assertEquals(ItemOutcome.REJECTED, ledger.findItems(entry.packageId()).getFirst().outcome());
		database.verifyIntegrity();
	}

	/**
	 * Verifies foreign-key enforcement prevents orphaned results.
	 */
	@Test
	void decisionWithoutPackageIsRejected() throws Exception {
		SqliteDatabase database = database("foreign-key.db");
		SqliteSubjectImportLedger ledger = new SqliteSubjectImportLedger(database);
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			assertThrows(SQLException.class,
					() -> ledger.recordItem(connection,
							new ItemEntry(UUID.randomUUID(), EntryKind.RECORD, "QUESTION:" + UUID.randomUUID(),
									"CONFLICT", CoordinatorDecision.REJECT, ItemOutcome.REJECTED, null, null,
									"Unknown incoming Question")));
			connection.rollback();
		}
		database.verifyIntegrity();
	}

	/**
	 * Verifies repeated package IDs cannot cause duplicate ledger rows or silently
	 * identify different archive contents.
	 */
	@Test
	void duplicatePackageIsIdempotentAndCollisionIsRejected() throws Exception {
		SqliteDatabase database = database("duplicate.db");
		SqliteSubjectImportLedger ledger = new SqliteSubjectImportLedger(database);
		PackageEntry entry = appliedEntry();
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			assertTrue(ledger.recordPackage(connection, entry));
			assertFalse(ledger.recordPackage(connection, entry));
			PackageEntry changed = new PackageEntry(entry.packageId(), "b".repeat(64), entry.originInstallationId(),
					entry.subjectPortableId(), entry.masterStateSha256(), entry.approvalSha256(), entry.outcome(),
					entry.backupArchivePath(), entry.approvalNote());
			assertThrows(SQLException.class, () -> ledger.recordPackage(connection, changed));
			connection.commit();
		}
		assertEquals(entry, ledger.find(entry.packageId()).orElseThrow());
	}

	/**
	 * Confirms an interrupted version-27 schema migration rolls back before
	 * retrying the normal migration.
	 */
	@Test
	void failedSchemaMigrationIsRecoverable() throws Exception {
		SqliteDatabase database = database("migration.db");
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			HistoricalSchemaFixture.removeVersion27Objects(statement);
			statement.execute("UPDATE schema_version SET version = 26");
			statement.execute("""
					CREATE TRIGGER reject_v27
					BEFORE UPDATE OF version ON schema_version
					WHEN NEW.version = 27
					BEGIN
					    SELECT RAISE(ABORT, 'simulated v27 failure');
					END
					""");
		}
		assertEquals(26, database.schemaVersion());
		assertThrows(SQLException.class, database::initialiseSchema);
		assertEquals(26, database.schemaVersion());
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT COUNT(*)
						FROM sqlite_master
						WHERE type = 'table'
						  AND name = 'subject_import_packages'
						""")) {
			assertTrue(result.next());
			assertEquals(0, result.getInt(1));
		}
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("DROP TRIGGER reject_v27");
		}
		database.initialiseSchema();
		assertEquals(27, database.schemaVersion());
		database.verifySchema();
		database.verifyIntegrity();
	}

	/**
	 * Verifies a genuine v26-to-v27 migration preserves existing Subject data and
	 * its previously assigned portable identity.
	 */
	@Test
	void schemaMigrationPreservesExistingSubjects() throws Exception {
		SqliteDatabase database = database("preserve.db");
		String originalPortableId;
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					INSERT INTO subjects (id, subject_name)
					VALUES (10, 'Biology')
					""");
			try (ResultSet result = statement.executeQuery("""
					SELECT portable_id
					FROM portable_entity_identities
					WHERE entity_type = 'SUBJECT'
					  AND local_id = 10
					""")) {
				assertTrue(result.next());
				originalPortableId = result.getString(1);
			}

			// Reconstruct the exact immediately preceding schema.
			// This is a disposable test fixture, not production data.
			HistoricalSchemaFixture.removeVersion27Objects(statement);
			statement.execute("UPDATE schema_version SET version = 26");
		}
		assertEquals(26, database.schemaVersion());
		database.verifySchema();
		database.initialiseSchema();
		assertEquals(27, database.schemaVersion());
		database.verifySchema();
		database.verifyIntegrity();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			try (ResultSet result = statement.executeQuery("""
					SELECT subject_name
					FROM subjects
					WHERE id = 10
					""")) {
				assertTrue(result.next());
				assertEquals("Biology", result.getString(1));
			}
			try (ResultSet result = statement.executeQuery("""
					SELECT portable_id
					FROM portable_entity_identities
					WHERE entity_type = 'SUBJECT'
					  AND local_id = 10
					""")) {
				assertTrue(result.next());
				assertEquals(originalPortableId, result.getString(1));
			}
		}
	}

	/**
	 * Verifies a rolled-back transaction leaves neither a package outcome nor an
	 * orphaned decision.
	 */
	@Test
	void transactionRollbackRemovesAllLedgerWrites() throws Exception {
		SqliteDatabase database = database("rollback.db");
		SqliteSubjectImportLedger ledger = new SqliteSubjectImportLedger(database);
		PackageEntry entry = appliedEntry();
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			ledger.recordPackage(connection, entry);
			ledger.recordItem(connection,
					new ItemEntry(entry.packageId(), EntryKind.RECORD, "SUBJECT:" + UUID.randomUUID(), "IDENTICAL",
							CoordinatorDecision.ACCEPT, ItemOutcome.ALIAS_LINKED, UUID.randomUUID(), UUID.randomUUID(),
							"Approved matching Biology Subject"));
			connection.rollback();
		}
		assertTrue(ledger.find(entry.packageId()).isEmpty());
		assertTrue(ledger.findItems(entry.packageId()).isEmpty());
		database.verifyIntegrity();
	}

	/**
	 * Produces representative applied-package provenance.
	 */
	private PackageEntry appliedEntry() {
		return new PackageEntry(UUID.randomUUID(), "a".repeat(64), UUID.randomUUID(), UUID.randomUUID(), "c".repeat(64),
				"d".repeat(64), PackageOutcome.APPLIED, directory.resolve("verified-backup.zip").toString(),
				"Coordinator approved the Biology consolidation");
	}

	/**
	 * Creates an initialised disposable database.
	 */
	private SqliteDatabase database(String name) throws SQLException {
		SqliteDatabase database = new SqliteDatabase(directory.resolve(name));
		database.initialiseSchema();
		return database;
	}
}
