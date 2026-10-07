package au.edu.eq.questionbank.service.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
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
	void automaticDatabaseBackupExcludesManagedSubjectFiles() throws Exception {
		ApplicationConfig config = ApplicationConfig.fromDataRoot(tempDir.resolve("data"));
		Path subjectsRoot = config.dataRoot().resolve("subjects");
		Path examPdf = subjectsRoot.resolve("Chemistry/exams/QCAA/2025/External Assessment/exam.pdf");
		Files.createDirectories(examPdf.getParent());
		Files.writeString(examPdf, "pdf-data");
		SqliteDatabase database = new SqliteDatabase(config.databasePath());
		database.initialiseSchema();
		DefaultBackupService service = new DefaultBackupService(config, "Development build",
				Clock.fixed(Instant.parse("2026-09-05T02:00:00Z"), ZoneOffset.UTC));
		BackupResult result = service.createBackup(BackupRequest.automaticDatabase(config));
		try (ZipFile archive = new ZipFile(result.backupPath().toFile())) {
			assertNotNull(archive.getEntry(BackupArchiveLayout.MANIFEST_ENTRY));
			assertNotNull(archive.getEntry(BackupArchiveLayout.DATABASE_ENTRY));
			assertNull(archive.getEntry(BackupArchiveLayout.SUBJECTS_DIRECTORY_ENTRY));
			assertNull(archive.getEntry("subjects/Chemistry/exams/QCAA/2025/External Assessment/exam.pdf"));
		}
	}

	@Test
	void createsValidatedFullBackupContainingSubjectScopedManagedData() throws Exception {
		ApplicationConfig config = ApplicationConfig.fromDataRoot(tempDir.resolve("data"));
		Path subjectsRoot = config.dataRoot().resolve("subjects");
		Path exam = subjectsRoot.resolve("Chemistry/exams/QCAA/2025/External Assessment/exam.pdf");
		Path curriculum = subjectsRoot.resolve("Chemistry/curriculum/2025/sources/syllabus.pdf");
		Path legacy = subjectsRoot.resolve("Chemistry/legacy/2019/questions.xlsx");
		Files.createDirectories(exam.getParent());
		Files.createDirectories(curriculum.getParent());
		Files.createDirectories(legacy.getParent());
		Files.writeString(exam, "pdf-data");
		Files.writeString(curriculum, "curriculum-data");
		Files.writeString(legacy, "legacy-data");
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
		assertEquals(2, result.manifest().formatVersion());
		try (ZipFile archive = new ZipFile(result.backupPath().toFile())) {
			assertNotNull(archive.getEntry(BackupArchiveLayout.MANIFEST_ENTRY));
			assertNotNull(archive.getEntry(BackupArchiveLayout.DATABASE_ENTRY));
			assertNotNull(archive.getEntry(BackupArchiveLayout.SUBJECTS_DIRECTORY_ENTRY));
			assertNotNull(archive.getEntry("subjects/Chemistry/exams/QCAA/2025/External Assessment/exam.pdf"));
			assertNotNull(archive.getEntry("subjects/Chemistry/curriculum/2025/sources/syllabus.pdf"));
			assertNotNull(archive.getEntry("subjects/Chemistry/legacy/2019/questions.xlsx"));
			assertNull(archive.getEntry("pdf/"));
			assertNull(archive.getEntry("curriculum/"));
		}
	}

	@Test
	void failedBackupDoesNotPublishFinalArchive() throws Exception {
		ApplicationConfig config = ApplicationConfig.fromDataRoot(tempDir.resolve("data"));
		Files.createDirectories(config.dataRoot());
		Path subjectsRoot = config.dataRoot().resolve("subjects");
		Files.writeString(subjectsRoot, "not-a-directory");
		SqliteDatabase database = new SqliteDatabase(config.databasePath());
		database.initialiseSchema();
		DefaultBackupService service = new DefaultBackupService(config, "Development build",
				Clock.fixed(Instant.parse("2026-09-05T03:00:00Z"), ZoneOffset.UTC));
		Path destination = tempDir.resolve("backups");
		assertThrows(BackupException.class, () -> service.createBackup(BackupRequest.full(destination)));
		assertFalse(Files.exists(destination.resolve("question-bank-full-2026-09-05T030000000Z.zip")));
	}

	@Test
	void fullBackupContainsEmptySubjectsRootEntry() throws Exception {
		ApplicationConfig config = ApplicationConfig.fromDataRoot(tempDir.resolve("data"));
		Files.createDirectories(config.dataRoot());
		SqliteDatabase database = new SqliteDatabase(config.databasePath());
		database.initialiseSchema();
		DefaultBackupService service = new DefaultBackupService(config, "Development build",
				Clock.fixed(Instant.parse("2026-09-05T01:00:00Z"), ZoneOffset.UTC));
		BackupResult result = service.createBackup(BackupRequest.full(tempDir.resolve("backups")));
		try (ZipFile archive = new ZipFile(result.backupPath().toFile())) {
			ZipEntry subjects = archive.getEntry(BackupArchiveLayout.SUBJECTS_DIRECTORY_ENTRY);
			assertNotNull(subjects);
			assertTrue(subjects.isDirectory());
		}
	}

	@Test
	void rejectsBackupDestinationAliasedIntoSubjectRoot() throws Exception {
		Path dataRoot = Files.createDirectories(tempDir.resolve("alias"));
		ApplicationConfig config = ApplicationConfig.fromDataRoot(dataRoot);
		Path subjectsRoot = Files.createDirectories(dataRoot.resolve("subjects"));
		SqliteDatabase database = new SqliteDatabase(config.databasePath());
		database.initialiseSchema();
		Path alias = tempDir.resolve("subjects-alias");
		try {
			Files.createSymbolicLink(alias, subjectsRoot);
		} catch (UnsupportedOperationException | IOException | SecurityException exception) {
			return;
		}
		DefaultBackupService service = new DefaultBackupService(config, "Test");
		assertThrows(BackupException.class, () -> service.createBackup(BackupRequest.full(alias.resolve("backups"))));
	}

	@Test
	void rejectsFullBackupInsideManagedSubjectHierarchy() throws Exception {
		ApplicationConfig config = ApplicationConfig.fromDataRoot(tempDir.resolve("data"));
		Files.createDirectories(config.dataRoot());
		SqliteDatabase database = new SqliteDatabase(config.databasePath());
		database.initialiseSchema();
		DefaultBackupService service = new DefaultBackupService(config, "Development build");
		assertThrows(BackupException.class, () -> service.createBackup(
				BackupRequest.full(config.dataRoot().resolve("subjects").resolve("Chemistry").resolve("backups"))));
	}
}
