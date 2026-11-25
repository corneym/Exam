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
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.repository.identity.SqlitePortableAliasRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
import au.edu.eq.questionbank.repository.sqlite.SqliteSubjectImportLedger.CoordinatorDecision;
import au.edu.eq.questionbank.repository.sqlite.SqliteSubjectImportLedger.EntryKind;
import au.edu.eq.questionbank.repository.sqlite.SqliteSubjectImportLedger.ItemEntry;
import au.edu.eq.questionbank.repository.sqlite.SqliteSubjectImportLedger.ItemOutcome;
import au.edu.eq.questionbank.repository.sqlite.SqliteSubjectImportLedger.OriginatingSnapshotEvidence;
import au.edu.eq.questionbank.repository.sqlite.SqliteSubjectImportLedger.PackageEntry;
import au.edu.eq.questionbank.repository.sqlite.SqliteSubjectImportLedger.PackageOutcome;

class SqliteSubjectSnapshotFreshnessSchemaTest {

	@TempDir
	Path directory;

	@Test
	void failedV49MigrationRollsBackAllObjectsAndCanBeRetried() throws Exception {
		SqliteDatabase database = database("retry.db");
		var entry = appliedEntry();
		var ledger = new SqliteSubjectImportLedger(database);
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			assertTrue(ledger.recordPackage(connection, entry));
			connection.commit();
		}
		reconstructV48(database);
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					CREATE TRIGGER test_reject_v49
					BEFORE UPDATE OF version ON schema_version
					WHEN NEW.version = 49
					BEGIN
					    SELECT RAISE(ABORT, 'Injected v49 failure');
					END
					""");
		}
		assertThrows(SQLException.class, database::initialiseSchema);
		assertEquals(48, database.schemaVersion());
		assertNoV49Objects(database);
		assertEquals(entry, ledger.find(entry.packageId()).orElseThrow());
		database.verifySchema();
		database.verifyIntegrity();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("DROP TRIGGER test_reject_v49");
		}
		database.initialiseSchema();
		assertEquals(49, database.schemaVersion());
		assertEquals(entry, ledger.find(entry.packageId()).orElseThrow());
		assertTrue(ledger.findOriginatingSnapshotEvidence(entry.packageId()).isEmpty());
		database.verifySchema();
		database.verifyIntegrity();
	}

	@Test
	void freshSchemaPersistsEvidenceAtomicallyAndProtectsHistory() throws Exception {
		SqliteDatabase database = database("fresh.db");
		assertEquals(49, database.schemaVersion());
		var ledger = new SqliteSubjectImportLedger(database);
		var entry = appliedEntry();
		var evidence = new OriginatingSnapshotEvidence(Instant.parse("2026-11-20T01:02:03.123456789Z"), "e".repeat(64));
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			assertTrue(ledger.recordPackage(connection, entry, evidence));
			assertEquals(evidence, ledger.findOriginatingSnapshotEvidence(connection, entry.packageId()).orElseThrow());
			connection.rollback();
		}
		assertTrue(ledger.find(entry.packageId()).isEmpty());
		assertTrue(ledger.findOriginatingSnapshotEvidence(entry.packageId()).isEmpty());
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			assertTrue(ledger.recordPackage(connection, entry, evidence));
			assertFalse(ledger.recordPackage(connection, entry, evidence));
			assertThrows(SQLException.class, () -> ledger.recordPackage(connection, entry,
					new OriginatingSnapshotEvidence(evidence.createdAt().plusSeconds(1), evidence.contentSha256())));
			connection.commit();
		}
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {

			// This entry has no referencing authority or decisions, so its own
			// immutability triggers must reject both operations.
			assertThrows(SQLException.class, () -> statement.executeUpdate("""
					UPDATE subject_import_packages
					SET originating_snapshot_content_sha256 = '%s'
					WHERE package_id = '%s'
					""".formatted("f".repeat(64), entry.packageId())));
			assertThrows(SQLException.class, () -> statement.executeUpdate("""
					DELETE FROM subject_import_packages WHERE package_id = '%s'
					""".formatted(entry.packageId())));
		}
		database.initialiseSchema();
		SqliteDatabase reopened = new SqliteDatabase(directory.resolve("fresh.db"));
		assertEquals(entry, new SqliteSubjectImportLedger(reopened).find(entry.packageId()).orElseThrow());
		assertEquals(evidence, new SqliteSubjectImportLedger(reopened)
				.findOriginatingSnapshotEvidence(entry.packageId()).orElseThrow());
		reopened.verifySchema();
		reopened.verifyIntegrity();
	}

	@Test
	void historicalCleanupAlsoRemovesFreshnessObjectsBeforeV27() throws Exception {
		SqliteDatabase database = database("earlier.db");
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			HistoricalSchemaFixture.removeVersion27Objects(statement);
			assertEquals(1, statement.executeUpdate("UPDATE schema_version SET version = 26 WHERE version = 49"));
		}
		assertEquals(26, database.schemaVersion());
		assertNoV49Objects(database);
		database.verifySchema();
		database.initialiseSchema();
		assertEquals(49, database.schemaVersion());
		database.verifySchema();
		database.verifyIntegrity();
	}

	@Test
	void historicalV48UpgradePreservesAuthorityIdentityAliasesAndLedger() throws Exception {
		SqliteDatabase database = database("upgrade.db");
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("INSERT INTO subjects (id, subject_name) VALUES (1, 'Biology'), (2, 'Chemistry')");
		}
		var identities = new SqlitePortableIdentityRepository(database);
		var biology = identities.requireByLocalId(EntityType.SUBJECT, 1L);
		var chemistry = identities.requireByLocalId(EntityType.SUBJECT, 2L);
		var aliases = new SqlitePortableAliasRepository(database);
		var authorities = new SqliteImportedSubjectAuthorityRepository(database);
		var ledger = new SqliteSubjectImportLedger(database);
		var entry = appliedEntry();
		var item = new ItemEntry(entry.packageId(), EntryKind.RECORD, "SUBJECT:" + entry.subjectPortableId(), "NEW",
				CoordinatorDecision.ACCEPT, ItemOutcome.INSERTED, biology.portableId(), biology.revisionId(),
				"Imported Biology");
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			assertTrue(ledger.recordPackage(connection, entry));
			ledger.recordItem(connection, item);
			aliases.approveAlias(connection, entry.originInstallationId(), EntityType.SUBJECT,
					entry.subjectPortableId(), biology.portableId(), "Approved import");
			authorities.recordImported(connection, 1L, entry.packageId());
			connection.commit();
		}
		var authority = authorities.find(1L).orElseThrow();
		reconstructV48(database);
		assertNoV49Objects(database);
		database.verifySchema();
		database.verifyMigrationCompatibility();
		assertEquals(48, database.schemaVersion());
		assertNoV49Objects(database);
		database.initialiseSchema();
		assertEquals(49, database.schemaVersion());
		assertEquals(entry, ledger.find(entry.packageId()).orElseThrow());
		assertEquals(List.of(item), ledger.findItems(entry.packageId()));
		assertTrue(ledger.findOriginatingSnapshotEvidence(entry.packageId()).isEmpty());
		assertEquals(authority, authorities.find(1L).orElseThrow());
		assertFalse(authorities.isImported(2L));
		assertEquals(biology, identities.requireByLocalId(EntityType.SUBJECT, 1L));
		assertEquals(chemistry, identities.requireByLocalId(EntityType.SUBJECT, 2L));
		assertEquals(biology.portableId(), aliases
				.findAlias(entry.originInstallationId(), EntityType.SUBJECT, entry.subjectPortableId()).orElseThrow());
		database.initialiseSchema();
		database.verifySchema();
		database.verifyIntegrity();
	}

	@Test
	void schemaVerificationRejectsMissingFreshnessColumns() throws Exception {
		SqliteDatabase database = database("missing-columns.db");
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			HistoricalSchemaFixture.removeVersion49Objects(statement);
		}
		assertEquals(49, database.schemaVersion());
		assertThrows(SQLException.class, database::verifySchema);
	}

	@Test
	void schemaVerificationRejectsMissingFreshnessTrigger() throws Exception {
		SqliteDatabase database = database("missing-trigger.db");
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("DROP TRIGGER trg_subject_import_freshness_no_update");
		}
		assertThrows(SQLException.class, database::verifySchema);
	}

	@Test
	void sqlRejectsPartialAndMalformedOriginatingEvidence() throws Exception {
		SqliteDatabase database = database("invalid-evidence.db");
		var entry = appliedEntry();
		var ledger = new SqliteSubjectImportLedger(database);
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			assertTrue(ledger.recordPackage(connection, entry));
			connection.commit();
		}
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			assertThrows(SQLException.class, () -> statement.executeUpdate("""
					UPDATE subject_import_packages
					SET originating_snapshot_created_at = '2026-11-20T01:02:03Z'
					WHERE package_id = '%s'
					""".formatted(entry.packageId())));
			assertThrows(SQLException.class, () -> statement.executeUpdate("""
					UPDATE subject_import_packages
					SET originating_snapshot_created_at = '2026-11-20T01:02:03Z',
					    originating_snapshot_content_sha256 = 'invalid'
					WHERE package_id = '%s'
					""".formatted(entry.packageId())));
		}
		assertTrue(ledger.findOriginatingSnapshotEvidence(entry.packageId()).isEmpty());
		database.verifySchema();
		database.verifyIntegrity();
	}

	private PackageEntry appliedEntry() {
		return new PackageEntry(UUID.randomUUID(), "a".repeat(64), UUID.randomUUID(), UUID.randomUUID(), "b".repeat(64),
				"c".repeat(64), PackageOutcome.APPLIED, directory.resolve("backup.zip").toString(),
				"Approved Subject snapshot");
	}

	private void assertNoV49Objects(SqliteDatabase database) throws SQLException {
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			try (ResultSet rows = statement.executeQuery("PRAGMA table_info(subject_import_packages)")) {
				while (rows.next()) {
					assertFalse(rows.getString("name").startsWith("originating_snapshot_"));
				}
			}
			try (ResultSet rows = statement.executeQuery("""
					SELECT name FROM sqlite_master
					WHERE type = 'trigger' AND name GLOB 'trg_subject_import_freshness_*'
					""")) {
				assertFalse(rows.next());
			}
		}
	}

	private SqliteDatabase database(String name) throws SQLException {
		SqliteDatabase database = new SqliteDatabase(directory.resolve(name));
		database.initialiseSchema();
		return database;
	}

	private void reconstructV48(SqliteDatabase database) throws SQLException {
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try (Statement statement = connection.createStatement()) {
				HistoricalSchemaFixture.removeVersion49Objects(statement);
				assertEquals(1, statement.executeUpdate("UPDATE schema_version SET version = 48 WHERE version = 49"));
				connection.commit();
			} catch (SQLException | RuntimeException failure) {
				connection.rollback();
				throw failure;
			}
		}
	}
}
