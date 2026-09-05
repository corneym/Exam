package au.edu.eq.questionbank.service.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class DefaultRestoreServiceTest {

	@TempDir
	Path tempDir;

	@Test
	void closingPreparationDeletesStagingArea() throws Exception {
		ApplicationConfig config = ApplicationConfig.fromDataRoot(tempDir.resolve("data"));
		Files.createDirectories(config.dataRoot());
		SqliteDatabase database = new SqliteDatabase(config.databasePath());
		database.initialiseSchema();
		BackupResult backup = new DefaultBackupService(config, "Test")
				.createBackup(BackupRequest.automaticDatabase(config));
		RestorePreparation preparation = new DefaultRestoreService(config).prepareRestore(backup.backupPath());
		Path stagingRoot = preparation.stagingRoot();
		assertTrue(Files.isDirectory(stagingRoot));
		preparation.close();
		assertFalse(Files.exists(stagingRoot));
	}

	@Test
	void preparesDatabaseOnlyBackupWithoutManagedRoots() throws Exception {
		ApplicationConfig config = ApplicationConfig.fromDataRoot(tempDir.resolve("data"));
		Files.createDirectories(config.dataRoot());
		SqliteDatabase database = new SqliteDatabase(config.databasePath());
		database.initialiseSchema();
		DefaultBackupService backupService = new DefaultBackupService(config, "Test");
		BackupResult backup = backupService.createBackup(BackupRequest.automaticDatabase(config));
		DefaultRestoreService restoreService = new DefaultRestoreService(config);
		try (RestorePreparation preparation = restoreService.prepareRestore(backup.backupPath())) {
			assertEquals(BackupKind.AUTOMATIC_DATABASE, preparation.manifest().kind());
			assertTrue(Files.isRegularFile(preparation.databasePath()));
			assertFalse(Files.exists(preparation.pdfRoot()));
			assertFalse(Files.exists(preparation.curriculumRoot()));
		}
	}

	@Test
	void preparesFullBackupWithoutChangingCurrentData() throws Exception {
		ApplicationConfig sourceConfig = ApplicationConfig.fromDataRoot(tempDir.resolve("source"));
		Files.createDirectories(sourceConfig.pdfDataRoot());
		Files.createDirectories(sourceConfig.curriculumDataRoot());
		Files.writeString(sourceConfig.pdfDataRoot().resolve("exam.pdf"), "original-pdf");
		Files.writeString(sourceConfig.curriculumDataRoot().resolve("chemistry.xlsx"), "original-curriculum");
		SqliteDatabase sourceDatabase = new SqliteDatabase(sourceConfig.databasePath());
		sourceDatabase.initialiseSchema();
		Path backupDirectory = tempDir.resolve("backups");
		DefaultBackupService backupService = new DefaultBackupService(sourceConfig, "Test");
		BackupResult backup = backupService.createBackup(BackupRequest.full(backupDirectory));
		ApplicationConfig targetConfig = ApplicationConfig.fromDataRoot(tempDir.resolve("target"));
		Files.createDirectories(targetConfig.dataRoot());
		Files.writeString(targetConfig.dataRoot().resolve("current-marker.txt"), "unchanged");
		DefaultRestoreService restoreService = new DefaultRestoreService(targetConfig);
		try (RestorePreparation preparation = restoreService.prepareRestore(backup.backupPath())) {
			assertEquals(BackupKind.FULL, preparation.manifest().kind());
			assertTrue(Files.isRegularFile(preparation.databasePath()));
			assertEquals("original-pdf", Files.readString(preparation.pdfRoot().resolve("exam.pdf")));
			assertEquals("original-curriculum",
					Files.readString(preparation.curriculumRoot().resolve("chemistry.xlsx")));
			assertEquals("unchanged", Files.readString(targetConfig.dataRoot().resolve("current-marker.txt")));
		}
	}

	@Test
	void rejectsCorruptBackupDatabase() throws Exception {
		Path archivePath = tempDir.resolve("corrupt.zip");
		BackupManifest manifest = BackupManifest.current(BackupKind.AUTOMATIC_DATABASE,
				Instant.parse("2026-09-05T03:00:00Z"), 4, "Test");
		BackupManifestCodec codec = new BackupManifestCodec();
		try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archivePath))) {
			output.putNextEntry(new ZipEntry(BackupArchiveLayout.MANIFEST_ENTRY));
			codec.write(manifest, output);
			output.closeEntry();
			output.putNextEntry(new ZipEntry(BackupArchiveLayout.DATABASE_ENTRY));
			output.write("not a sqlite database".getBytes(StandardCharsets.UTF_8));
			output.closeEntry();
		}
		ApplicationConfig config = ApplicationConfig.fromDataRoot(tempDir.resolve("target"));
		DefaultRestoreService service = new DefaultRestoreService(config);
		RestoreException exception = assertThrows(RestoreException.class, () -> service.prepareRestore(archivePath));
		assertFalse(exception.applicationMustExit());
	}

	@Test
	void rejectsUnsafeArchiveEntry() throws Exception {
		Path archivePath = tempDir.resolve("unsafe.zip");
		try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archivePath))) {
			output.putNextEntry(new ZipEntry("../outside.txt"));
			output.write("bad".getBytes(StandardCharsets.UTF_8));
			output.closeEntry();
		}
		ApplicationConfig config = ApplicationConfig.fromDataRoot(tempDir.resolve("data"));
		DefaultRestoreService service = new DefaultRestoreService(config);
		RestoreException exception = assertThrows(RestoreException.class, () -> service.prepareRestore(archivePath));
		assertFalse(exception.applicationMustExit());
		assertFalse(Files.exists(tempDir.resolve("outside.txt")));
	}
}
