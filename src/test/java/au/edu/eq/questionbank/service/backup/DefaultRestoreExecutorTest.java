package au.edu.eq.questionbank.service.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.stream.Stream;

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
			/*
			 * Simulate failure after successful preparation. Managed roots will already
			 * have been published before database validation detects this corruption.
			 */
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
