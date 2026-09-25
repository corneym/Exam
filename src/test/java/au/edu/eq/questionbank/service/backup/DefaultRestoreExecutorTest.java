package au.edu.eq.questionbank.service.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class DefaultRestoreExecutorTest {

	@TempDir
	Path tempDir;

	@Test
	void databaseOnlyRestorePreservesManagedData() throws Exception {
		ApplicationConfig sourceConfig = createDataSet("source", "Restored Chemistry", "unused-pdf",
				"unused-curriculum");
		BackupResult databaseBackup = new DefaultBackupService(sourceConfig, "Test")
				.createBackup(BackupRequest.automaticDatabase(sourceConfig));
		ApplicationConfig targetConfig = createDataSet("target", "Original Chemistry", "keep-pdf", "keep-curriculum");
		DefaultRestoreService restoreService = new DefaultRestoreService(targetConfig);
		try (RestorePreparation preparation = restoreService.prepareRestore(databaseBackup.backupPath())) {
			new DefaultRestoreExecutor(targetConfig, "Test").applyRestore(preparation, new CountingCloseable());
		}
		assertEquals("Restored Chemistry", readOnlySubjectName(targetConfig.databasePath()));
		assertEquals("keep-pdf", Files.readString(targetConfig.pdfDataRoot().resolve("exam.pdf")));
		assertEquals("keep-curriculum", Files.readString(targetConfig.curriculumDataRoot().resolve("curriculum.txt")));
	}

	@Test
	void failedReplacementRollsBackOriginalData() throws Exception {
		ApplicationConfig sourceConfig = createDataSet("source", "New Chemistry", "new-pdf", "new-curriculum");
		BackupResult sourceBackup = new DefaultBackupService(sourceConfig, "Test")
				.createBackup(BackupRequest.full(tempDir.resolve("source-backups")));
		ApplicationConfig targetConfig = createDataSet("target", "Old Chemistry", "old-pdf", "old-curriculum");
		DefaultRestoreService restoreService = new DefaultRestoreService(targetConfig);
		try (RestorePreparation preparation = restoreService.prepareRestore(sourceBackup.backupPath())) {

			// Simulate failure after successful preparation. Managed roots will already
			// have been published before database validation detects this corruption.
			Files.writeString(preparation.databasePath(), "deliberately corrupt");
			RestoreException exception = assertThrows(RestoreException.class,
					() -> new DefaultRestoreExecutor(targetConfig, "Test").applyRestore(preparation,
							new CountingCloseable()));
			assertTrue(exception.applicationMustExit());
		}
		assertEquals("Old Chemistry", readOnlySubjectName(targetConfig.databasePath()));
		assertEquals("old-pdf", Files.readString(targetConfig.pdfDataRoot().resolve("exam.pdf")));
		assertEquals("old-curriculum", Files.readString(targetConfig.curriculumDataRoot().resolve("curriculum.txt")));
	}

	@Test
	void fullRestoreReplacesDatabaseAndManagedDataAndCreatesSafetyBackup() throws Exception {
		ApplicationConfig sourceConfig = createDataSet("source", "Restored Chemistry", "new-pdf", "new-curriculum");
		BackupResult sourceBackup = new DefaultBackupService(sourceConfig, "Test")
				.createBackup(BackupRequest.full(tempDir.resolve("source-backups")));
		ApplicationConfig targetConfig = createDataSet("target", "Original Chemistry", "old-pdf", "old-curriculum");
		DefaultRestoreService restoreService = new DefaultRestoreService(targetConfig);
		CountingCloseable resources = new CountingCloseable();
		try (RestorePreparation preparation = restoreService.prepareRestore(sourceBackup.backupPath())) {
			RestoreResult result = new DefaultRestoreExecutor(targetConfig, "Test").applyRestore(preparation,
					resources);
			assertEquals(1, resources.closeCount);
			assertEquals(BackupKind.FULL, result.restoredManifest().kind());
			assertTrue(Files.isRegularFile(result.safetyBackupPath()));
			assertEquals("new-pdf", Files.readString(targetConfig.pdfDataRoot().resolve("exam.pdf")));
			assertEquals("new-curriculum",
					Files.readString(targetConfig.curriculumDataRoot().resolve("curriculum.txt")));
			assertEquals("Restored Chemistry", readOnlySubjectName(targetConfig.databasePath()));
		}
	}

	@Test
	void preRestoreSafetyBackupContainsOriginalData() throws Exception {
		ApplicationConfig sourceConfig = createDataSet("source", "New Chemistry", "new-pdf", "new-curriculum");
		BackupResult sourceBackup = new DefaultBackupService(sourceConfig, "Test")
				.createBackup(BackupRequest.full(tempDir.resolve("source-backups")));
		ApplicationConfig targetConfig = createDataSet("target", "Old Chemistry", "old-pdf", "old-curriculum");
		DefaultRestoreService restoreService = new DefaultRestoreService(targetConfig);
		Path safetyBackupPath;
		try (RestorePreparation preparation = restoreService.prepareRestore(sourceBackup.backupPath())) {
			RestoreResult result = new DefaultRestoreExecutor(targetConfig, "Test").applyRestore(preparation,
					new CountingCloseable());
			safetyBackupPath = result.safetyBackupPath();
		}
		try (RestorePreparation safetyPreparation = restoreService.prepareRestore(safetyBackupPath)) {
			assertEquals(BackupKind.FULL, safetyPreparation.manifest().kind());
			assertEquals("old-pdf", Files.readString(safetyPreparation.pdfRoot().resolve("exam.pdf")));
			assertEquals("Old Chemistry", readOnlySubjectName(safetyPreparation.databasePath()));
		}
	}

	@Test
	void resourceCloseFailureLeavesCurrentDataUntouched() throws Exception {
		ApplicationConfig sourceConfig = createDataSet("source", "New Chemistry", "new-pdf", "new-curriculum");
		BackupResult sourceBackup = new DefaultBackupService(sourceConfig, "Test")
				.createBackup(BackupRequest.full(tempDir.resolve("source-backups")));
		ApplicationConfig targetConfig = createDataSet("target", "Old Chemistry", "old-pdf", "old-curriculum");
		CountingCloseable resources = new CountingCloseable();
		resources.failOnClose = true;
		DefaultRestoreService restoreService = new DefaultRestoreService(targetConfig);
		try (RestorePreparation preparation = restoreService.prepareRestore(sourceBackup.backupPath())) {
			RestoreException exception = assertThrows(RestoreException.class,
					() -> new DefaultRestoreExecutor(targetConfig, "Test").applyRestore(preparation, resources));
			assertTrue(exception.applicationMustExit());
		}
		assertEquals("Old Chemistry", readOnlySubjectName(targetConfig.databasePath()));
		assertEquals("old-pdf", Files.readString(targetConfig.pdfDataRoot().resolve("exam.pdf")));
		Path safetyDirectory = targetConfig.dataRoot().resolve("backups").resolve("pre-restore");
		try (Stream<Path> stream = Files.list(safetyDirectory)) {
			assertEquals(1, stream.filter(Files::isRegularFile).count());
		}
	}

	@Test
	void restoresVersion11BackupThenMigratesAndReopensAtLatestSchema() throws Exception {
		Path legacyDatabasePath = tempDir.resolve("legacy-v11.db");
		createVersion11Database(legacyDatabasePath);
		Path backupPath = tempDir.resolve("legacy-v11-backup.zip");
		createVersion11Backup(legacyDatabasePath, backupPath);
		ApplicationConfig targetConfig = createDataSet("target", "Original Chemistry", "keep-pdf", "keep-curriculum");
		DefaultRestoreService restoreService = new DefaultRestoreService(targetConfig);
		try (RestorePreparation preparation = restoreService.prepareRestore(backupPath)) {

			// Preparation proves that the legacy database can reach the current schema,
			// but the staged database itself must remain at the backed-up version.
			assertEquals(11, new SqliteDatabase(preparation.databasePath()).schemaVersion());
			new DefaultRestoreExecutor(targetConfig, "Test").applyRestore(preparation, new CountingCloseable());
		}
		SqliteDatabase restoredDatabase = new SqliteDatabase(targetConfig.databasePath());

		// Restore publishes the original database unchanged. Normal application
		// initialisation is responsible for performing the real sequential migration.
		assertEquals(11, restoredDatabase.schemaVersion());
		assertEquals("Legacy Chemistry", readOnlySubjectName(targetConfig.databasePath()));
		restoredDatabase.initialiseSchema();

		// Reopen through a fresh database boundary so successful assertions cannot
		// depend on state retained by the object that performed the migration.
		SqliteDatabase reopenedDatabase = new SqliteDatabase(targetConfig.databasePath());
		assertEquals(SqliteDatabase.latestSchemaVersion(), reopenedDatabase.schemaVersion());
		reopenedDatabase.verifySchema();
		reopenedDatabase.verifyIntegrity();
		assertEquals("Legacy Chemistry", readOnlySubjectName(targetConfig.databasePath()));
		assertTrue(tableExists(reopenedDatabase, "question_output_exclusions"));

		// Version 13 renamed the two remaining physical "preamble" columns without
		// changing their stored values.
		assertTrue(columnExists(reopenedDatabase, "questions", "shared_context_capture_required"));
		assertFalse(columnExists(reopenedDatabase, "questions", "preamble_capture_required"));
		assertTrue(columnExists(reopenedDatabase, "source_questions", "shared_context_status"));
		assertFalse(columnExists(reopenedDatabase, "source_questions", "preamble_status"));
		assertEquals(1, readIntegerValue(reopenedDatabase, "questions", "shared_context_capture_required"));
		assertEquals("PRESENT", readTextValue(reopenedDatabase, "source_questions", "shared_context_status"));
	}

	private boolean columnExists(SqliteDatabase database, String tableName, String columnName) throws Exception {
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("PRAGMA table_info(" + tableName + ")")) {
			while (result.next()) {
				if (columnName.equals(result.getString("name"))) {
					return true;
				}
			}
			return false;
		}
	}

	private ApplicationConfig createDataSet(String directoryName, String subjectName, String pdfContents,
			String curriculumContents) throws Exception {
		ApplicationConfig config = ApplicationConfig.fromDataRoot(tempDir.resolve(directoryName));
		Files.createDirectories(config.pdfDataRoot());
		Files.createDirectories(config.curriculumDataRoot());
		Files.writeString(config.pdfDataRoot().resolve("exam.pdf"), pdfContents);
		Files.writeString(config.curriculumDataRoot().resolve("curriculum.txt"), curriculumContents);
		SqliteDatabase database = new SqliteDatabase(config.databasePath());
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("INSERT INTO subjects (subject_name) VALUES ('" + subjectName.replace("'", "''") + "')");
		}
		return config;
	}

	private void createVersion11Backup(Path databasePath, Path backupPath) throws Exception {
		BackupManifest manifest = BackupManifest.current(BackupKind.AUTOMATIC_DATABASE,
				Instant.parse("2026-09-26T00:00:00Z"), 11, "Test");
		try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(backupPath))) {

			// Construct the same archive layout consumed by the production restore
			// service while retaining the deliberately old database schema.
			output.putNextEntry(new ZipEntry(BackupArchiveLayout.MANIFEST_ENTRY));
			new BackupManifestCodec().write(manifest, output);
			output.closeEntry();
			output.putNextEntry(new ZipEntry(BackupArchiveLayout.DATABASE_ENTRY));
			Files.copy(databasePath, output);
			output.closeEntry();
		}
	}

	private void createVersion11Database(Path databasePath) throws Exception {
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {

			// Seed values that migration v12->v13 must preserve while renaming the
			// physical Shared Context columns.
			statement.execute("INSERT INTO subjects (subject_name) VALUES ('Legacy Chemistry')");
			statement.execute("""
					INSERT INTO syllabus_versions (
					    subject_id,
					    syllabus_name,
					    is_current,
					    curriculum_status
					)
					VALUES (1, '2019', 0, 'IN_PROGRESS')
					""");
			statement.execute("""
					INSERT INTO curriculum_nodes (
					    syllabus_version_id,
					    parent_id,
					    curriculum_code,
					    curriculum_name,
					    curriculum_level,
					    display_order
					)
					VALUES (1, NULL, '1', 'Legacy unit', 'UNIT', 1)
					""");
			statement.execute("INSERT INTO exam_providers (provider_name) VALUES ('QCAA')");
			statement.execute("""
					INSERT INTO source_documents (relative_path)
					VALUES ('Chemistry/2019/paper1.pdf')
					""");
			statement.execute("""
					INSERT INTO exams (
					    subject_id,
					    provider_id,
					    exam_year,
					    exam_name
					)
					VALUES (1, 1, 2019, 'External Assessment')
					""");
			statement.execute("""
					INSERT INTO exam_booklets (
					    exam_id,
					    source_document_id,
					    booklet_name,
					    question_format
					)
					VALUES (1, 1, 'Paper 1', 'UNSPECIFIED')
					""");
			statement.execute("""
					INSERT INTO source_questions (
					    booklet_id,
					    source_question_code,
					    shared_context_status
					)
					VALUES (1, 'Q1', 'PRESENT')
					""");
			statement.execute("""
					INSERT INTO questions (
					    booklet_id,
					    classification_node_id,
					    question_code,
					    question_text,
					    marks,
					    shared_context_capture_required,
					    response_type,
					    source_question_id
					)
					VALUES (1, 1, 'Q1', '', 2, 1, 'WRITTEN_RESPONSE', 1)
					""");

			// Reverse only migrations 12 and 13 so the fixture is a structurally valid
			// version-11 database containing real legacy data.
			statement.execute("""
					ALTER TABLE questions
					RENAME COLUMN shared_context_capture_required
					TO preamble_capture_required
					""");
			statement.execute("""
					ALTER TABLE source_questions
					RENAME COLUMN shared_context_status
					TO preamble_status
					""");
			statement.execute("DROP TABLE question_output_exclusions");
			statement.execute("UPDATE schema_version SET version = 11");
		}

		// Validate the constructed legacy fixture against the production version-11
		// schema contract before using it as backup input.
		assertEquals(11, database.schemaVersion());
		database.verifySchema();
		database.verifyIntegrity();
	}

	private int readIntegerValue(SqliteDatabase database, String tableName, String columnName) throws Exception {
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("SELECT " + columnName + " FROM " + tableName + " LIMIT 1")) {
			assertTrue(result.next());
			return result.getInt(1);
		}
	}

	private String readOnlySubjectName(Path databasePath) throws Exception {
		SqliteDatabase database = new SqliteDatabase(databasePath);
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT subject_name
						FROM subjects
						ORDER BY id
						LIMIT 1
						""")) {
			assertTrue(result.next());
			return result.getString("subject_name");
		}
	}

	private String readTextValue(SqliteDatabase database, String tableName, String columnName) throws Exception {
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("SELECT " + columnName + " FROM " + tableName + " LIMIT 1")) {
			assertTrue(result.next());
			return result.getString(1);
		}
	}

	private boolean tableExists(SqliteDatabase database, String tableName) throws Exception {
		try (Connection connection = database.openConnection(); var statement = connection.prepareStatement("""
				SELECT 1
				FROM sqlite_master
				WHERE type = 'table'
				  AND name = ?
				""")) {
			statement.setString(1, tableName);
			try (ResultSet result = statement.executeQuery()) {
				return result.next();
			}
		}
	}

	private static final class CountingCloseable implements AutoCloseable {

		private int closeCount;
		private boolean failOnClose;

		@Override
		public void close() throws Exception {
			closeCount++;
			if (failOnClose) {
				throw new IOException("Deliberate close failure");
			}
		}
	}
}
