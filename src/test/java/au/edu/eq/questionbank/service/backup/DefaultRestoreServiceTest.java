package au.edu.eq.questionbank.service.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.zip.CRC32;
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
	void rejectsBackupWithMalformedCurrentSchema() throws Exception {
		Path databasePath = tempDir.resolve("malformed.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("PRAGMA foreign_keys = OFF");
			statement.execute("DROP TABLE answers");
			statement.execute("""
					CREATE TABLE answers (
					    id INTEGER PRIMARY KEY,
					    question_id INTEGER NOT NULL UNIQUE,
					    answer_text TEXT
					)
					""");
		}
		Path archivePath = tempDir.resolve("malformed-schema.zip");
		BackupManifest manifest = BackupManifest.current(BackupKind.AUTOMATIC_DATABASE,
				Instant.parse("2026-09-05T04:00:00Z"), 4, "Test");
		BackupManifestCodec codec = new BackupManifestCodec();
		try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archivePath))) {
			output.putNextEntry(new ZipEntry(BackupArchiveLayout.MANIFEST_ENTRY));
			codec.write(manifest, output);
			output.closeEntry();
			output.putNextEntry(new ZipEntry(BackupArchiveLayout.DATABASE_ENTRY));
			Files.copy(databasePath, output);
			output.closeEntry();
		}
		ApplicationConfig config = ApplicationConfig.fromDataRoot(tempDir.resolve("target"));
		DefaultRestoreService service = new DefaultRestoreService(config);
		RestoreException exception = assertThrows(RestoreException.class, () -> service.prepareRestore(archivePath));
		assertFalse(exception.applicationMustExit());
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
	void rejectsFullBackupWithCorruptManagedEntry() throws Exception {
		Path databasePath = tempDir.resolve("archive-database.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		Path archivePath = tempDir.resolve("corrupt-managed-entry.zip");
		byte[] managedContent = "managed-pdf-content".getBytes(StandardCharsets.UTF_8);
		BackupManifest manifest = BackupManifest.current(BackupKind.FULL, Instant.parse("2026-09-05T05:00:00Z"),
				SqliteDatabase.latestSchemaVersion(), "Test");
		BackupManifestCodec codec = new BackupManifestCodec();
		try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archivePath))) {
			output.putNextEntry(new ZipEntry(BackupArchiveLayout.MANIFEST_ENTRY));
			codec.write(manifest, output);
			output.closeEntry();
			output.putNextEntry(new ZipEntry(BackupArchiveLayout.DATABASE_ENTRY));
			Files.copy(databasePath, output);
			output.closeEntry();
			output.putNextEntry(new ZipEntry(BackupArchiveLayout.PDF_DIRECTORY_ENTRY));
			output.closeEntry();
			output.putNextEntry(new ZipEntry(BackupArchiveLayout.CURRICULUM_DIRECTORY_ENTRY));
			output.closeEntry();
			CRC32 crc = new CRC32();
			crc.update(managedContent);
			ZipEntry managedEntry = new ZipEntry(BackupArchiveLayout.PDF_DIRECTORY_ENTRY + "exam.bin");
			managedEntry.setMethod(ZipEntry.STORED);
			managedEntry.setSize(managedContent.length);
			managedEntry.setCompressedSize(managedContent.length);
			managedEntry.setCrc(crc.getValue());
			output.putNextEntry(managedEntry);
			output.write(managedContent);
			output.closeEntry();
		}
		byte[] archiveBytes = Files.readAllBytes(archivePath);
		int contentOffset = indexOf(archiveBytes, managedContent);
		assertTrue(contentOffset >= 0);
		archiveBytes[contentOffset] = (byte) (archiveBytes[contentOffset] ^ 1);
		Files.write(archivePath, archiveBytes);
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

	private int indexOf(byte[] source, byte[] target) {
		for (int sourceIndex = 0; sourceIndex <= source.length - target.length; sourceIndex++) {
			boolean matches = true;
			for (int targetIndex = 0; targetIndex < target.length; targetIndex++) {
				if (source[sourceIndex + targetIndex] != target[targetIndex]) {
					matches = false;
					break;
				}
			}
			if (matches) {
				return sourceIndex;
			}
		}
		return -1;
	}
}
