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
	void preparesDatabaseOnlyBackupWithoutSubjectRoot() throws Exception {
		ApplicationConfig config = ApplicationConfig.fromDataRoot(tempDir.resolve("data"));
		Files.createDirectories(config.dataRoot());
		SqliteDatabase database = new SqliteDatabase(config.databasePath());
		database.initialiseSchema();
		BackupResult backup = new DefaultBackupService(config, "Test")
				.createBackup(BackupRequest.automaticDatabase(config));
		try (RestorePreparation preparation = new DefaultRestoreService(config).prepareRestore(backup.backupPath())) {
			assertEquals(BackupKind.AUTOMATIC_DATABASE, preparation.manifest().kind());
			assertTrue(Files.isRegularFile(preparation.databasePath()));
			assertFalse(Files.exists(preparation.subjectsRoot()));
		}
	}

	@Test
	void preparesFullSubjectFirstBackupWithoutChangingCurrentData() throws Exception {
		ApplicationConfig sourceConfig = ApplicationConfig.fromDataRoot(tempDir.resolve("source"));
		Path sourceExam = sourceConfig.dataRoot()
				.resolve("subjects/Chemistry/exams/QCAA/2025/External Assessment/exam.pdf");
		Path sourceCurriculum = sourceConfig.dataRoot()
				.resolve("subjects/Chemistry/curriculum/2025/sources/syllabus.pdf");
		Path sourceLegacy = sourceConfig.dataRoot().resolve("subjects/Chemistry/legacy/2019/questions.xlsx");
		Files.createDirectories(sourceExam.getParent());
		Files.createDirectories(sourceCurriculum.getParent());
		Files.createDirectories(sourceLegacy.getParent());
		Files.writeString(sourceExam, "original-pdf");
		Files.writeString(sourceCurriculum, "original-curriculum");
		Files.writeString(sourceLegacy, "original-legacy");
		SqliteDatabase sourceDatabase = new SqliteDatabase(sourceConfig.databasePath());
		sourceDatabase.initialiseSchema();
		BackupResult backup = new DefaultBackupService(sourceConfig, "Test")
				.createBackup(BackupRequest.full(tempDir.resolve("backups")));
		ApplicationConfig targetConfig = ApplicationConfig.fromDataRoot(tempDir.resolve("target"));
		Files.createDirectories(targetConfig.dataRoot());
		Path currentMarker = targetConfig.dataRoot().resolve("current-marker.txt");
		Files.writeString(currentMarker, "unchanged");
		try (RestorePreparation preparation = new DefaultRestoreService(targetConfig)
				.prepareRestore(backup.backupPath())) {
			assertEquals(BackupKind.FULL, preparation.manifest().kind());
			assertTrue(Files.isRegularFile(preparation.databasePath()));
			assertEquals("original-pdf", Files.readString(
					preparation.subjectsRoot().resolve("Chemistry/exams/QCAA/2025/External Assessment/exam.pdf")));
			assertEquals("original-curriculum", Files
					.readString(preparation.subjectsRoot().resolve("Chemistry/curriculum/2025/sources/syllabus.pdf")));
			assertEquals("original-legacy",
					Files.readString(preparation.subjectsRoot().resolve("Chemistry/legacy/2019/questions.xlsx")));
			assertEquals("unchanged", Files.readString(currentMarker));
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
			output.putNextEntry(new ZipEntry(BackupArchiveLayout.SUBJECTS_DIRECTORY_ENTRY));
			output.closeEntry();
			CRC32 crc = new CRC32();
			crc.update(managedContent);
			ZipEntry managedEntry = new ZipEntry(
					BackupArchiveLayout.SUBJECTS_DIRECTORY_ENTRY + "Chemistry/exams/exam.bin");
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
	void rejectsRetiredFormatOneFullBackup() throws Exception {
		Path databasePath = tempDir.resolve("format-one.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		Path archivePath = tempDir.resolve("format-one-full.zip");
		try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archivePath))) {
			output.putNextEntry(new ZipEntry(BackupArchiveLayout.MANIFEST_ENTRY));
			String manifest = """
					backup.format.version=1
					backup.kind=FULL
					created.at=2026-10-07T00:00:00Z
					database.schema.version=19
					application.version=0.2
					archive.entries=backup-manifest.properties,questionbank.db,pdf/,curriculum/
					""";
			output.write(manifest.getBytes(StandardCharsets.UTF_8));
			output.closeEntry();
			output.putNextEntry(new ZipEntry(BackupArchiveLayout.DATABASE_ENTRY));
			Files.copy(databasePath, output);
			output.closeEntry();
			output.putNextEntry(new ZipEntry("pdf/"));
			output.closeEntry();
			output.putNextEntry(new ZipEntry("curriculum/"));
			output.closeEntry();
		}
		ApplicationConfig config = ApplicationConfig.fromDataRoot(tempDir.resolve("target"));
		RestoreException exception = assertThrows(RestoreException.class,
				() -> new DefaultRestoreService(config).prepareRestore(archivePath));
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
