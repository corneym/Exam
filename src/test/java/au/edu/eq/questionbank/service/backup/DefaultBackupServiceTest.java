package au.edu.eq.questionbank.service.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class DefaultBackupServiceTest {

	@TempDir
	Path tempDir;

	@Test
	void automaticDatabaseBackupExcludesManagedFiles() throws Exception {
		ApplicationConfig config = ApplicationConfig.fromDataRoot(tempDir.resolve("data"));
		Files.createDirectories(config.pdfDataRoot());
		Files.writeString(config.pdfDataRoot().resolve("exam.pdf"), "pdf-data");
		SqliteDatabase database = new SqliteDatabase(config.databasePath());
		database.initialiseSchema();
		DefaultBackupService service = new DefaultBackupService(config, "Development build",
				Clock.fixed(Instant.parse("2026-09-05T02:00:00Z"), ZoneOffset.UTC));
		BackupResult result = service.createBackup(BackupRequest.automaticDatabase(config));
		try (ZipFile archive = new ZipFile(result.backupPath().toFile())) {
			assertNotNull(archive.getEntry(BackupArchiveLayout.MANIFEST_ENTRY));
			assertNotNull(archive.getEntry(BackupArchiveLayout.DATABASE_ENTRY));
			assertNull(archive.getEntry(BackupArchiveLayout.PDF_DIRECTORY_ENTRY));
			assertNull(archive.getEntry("pdf/exam.pdf"));
		}
	}

	@Test
	void createsValidatedFullBackupContainingManagedData() throws Exception {
		ApplicationConfig config = ApplicationConfig.fromDataRoot(tempDir.resolve("data"));
		Files.createDirectories(config.pdfDataRoot());
		Files.createDirectories(config.curriculumDataRoot());
		Files.writeString(config.pdfDataRoot().resolve("exam.pdf"), "pdf-data");
		Files.writeString(config.curriculumDataRoot().resolve("chemistry.txt"), "curriculum-data");
		SqliteDatabase database = new SqliteDatabase(config.databasePath());
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					INSERT INTO subjects (subject_name)
					VALUES ('Chemistry')
					""");
		}
		Clock clock = Clock.fixed(Instant.parse("2026-09-05T00:00:00Z"), ZoneOffset.UTC);
		DefaultBackupService service = new DefaultBackupService(config, "0.0.1-SNAPSHOT", clock);
		Path destination = tempDir.resolve("backups");
		BackupResult result = service.createBackup(BackupRequest.full(destination));
		assertEquals(destination.resolve("question-bank-full-2026-09-05T000000000Z.zip").toAbsolutePath().normalize(),
				result.backupPath());
		assertEquals(BackupKind.FULL, result.manifest().kind());
		assertTrue(Files.isRegularFile(result.backupPath()));
		try (ZipFile archive = new ZipFile(result.backupPath().toFile())) {
			assertNotNull(archive.getEntry(BackupArchiveLayout.MANIFEST_ENTRY));
			assertNotNull(archive.getEntry(BackupArchiveLayout.DATABASE_ENTRY));
			assertNotNull(archive.getEntry(BackupArchiveLayout.PDF_DIRECTORY_ENTRY));
			assertNotNull(archive.getEntry("pdf/exam.pdf"));
			assertNotNull(archive.getEntry(BackupArchiveLayout.CURRICULUM_DIRECTORY_ENTRY));
			assertNotNull(archive.getEntry("curriculum/chemistry.txt"));
		}
	}

	@Test
	void failedBackupDoesNotPublishFinalArchive() throws Exception {
		ApplicationConfig config = ApplicationConfig.fromDataRoot(tempDir.resolve("data"));
		Files.createDirectories(config.pdfDataRoot());
		Path curriculumParent = config.curriculumDataRoot().getParent();
		Files.createDirectories(curriculumParent);
		Files.writeString(config.curriculumDataRoot(), "not-a-directory");
		SqliteDatabase database = new SqliteDatabase(config.databasePath());
		database.initialiseSchema();
		DefaultBackupService service = new DefaultBackupService(config, "Development build",
				Clock.fixed(Instant.parse("2026-09-05T03:00:00Z"), ZoneOffset.UTC));
		Path destination = tempDir.resolve("backups");
		assertThrows(BackupException.class, () -> service.createBackup(BackupRequest.full(destination)));
		Path finalPath = destination.resolve("question-bank-full-2026-09-05T030000000Z.zip");
		assertFalse(Files.exists(finalPath));
	}

	@Test
	void fullBackupContainsEmptyManagedRootEntries() throws Exception {
		ApplicationConfig config = ApplicationConfig.fromDataRoot(tempDir.resolve("data"));
		Files.createDirectories(config.dataRoot());
		SqliteDatabase database = new SqliteDatabase(config.databasePath());
		database.initialiseSchema();
		DefaultBackupService service = new DefaultBackupService(config, "Development build",
				Clock.fixed(Instant.parse("2026-09-05T01:00:00Z"), ZoneOffset.UTC));
		BackupResult result = service.createBackup(BackupRequest.full(tempDir.resolve("backups")));
		try (ZipFile archive = new ZipFile(result.backupPath().toFile())) {
			ZipEntry pdf = archive.getEntry(BackupArchiveLayout.PDF_DIRECTORY_ENTRY);
			ZipEntry curriculum = archive.getEntry(BackupArchiveLayout.CURRICULUM_DIRECTORY_ENTRY);
			assertNotNull(pdf);
			assertTrue(pdf.isDirectory());
			assertNotNull(curriculum);
			assertTrue(curriculum.isDirectory());
		}
	}

	@Test
	void rejectsFullBackupInsideManagedPdfHierarchy() throws Exception {
		ApplicationConfig config = ApplicationConfig.fromDataRoot(tempDir.resolve("data"));
		Files.createDirectories(config.dataRoot());
		SqliteDatabase database = new SqliteDatabase(config.databasePath());
		database.initialiseSchema();
		DefaultBackupService service = new DefaultBackupService(config, "Development build");
		assertThrows(BackupException.class,
				() -> service.createBackup(BackupRequest.full(config.pdfDataRoot().resolve("backups"))));
	}
}
