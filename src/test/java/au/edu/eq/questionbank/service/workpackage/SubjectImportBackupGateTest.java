package au.edu.eq.questionbank.service.workpackage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.backup.BackupException;
import au.edu.eq.questionbank.service.workpackage.SubjectImportBackupGate.Certificate;

/**
 * Verifies complete, non-destructive import backup preparation.
 *
 * <p>
 * All tests use disposable SQLite databases and managed files.
 */
class SubjectImportBackupGateTest {

	@TempDir
	Path directory;

	/**
	 * A verified backup for one incoming package cannot be reused as approval
	 * evidence for another package.
	 */
	@Test
	void certificateIsBoundToItsIncomingPackage() throws Exception {
		ApplicationConfig config = fixture();
		UUID firstPackage = UUID.randomUUID();
		SubjectImportBackupGate gate = new SubjectImportBackupGate(config);
		Certificate certificate = gate.createVerifiedFullBackup(firstPackage, directory.resolve("identity-backups"));
		assertThrows(IllegalArgumentException.class, () -> gate.requireIntact(UUID.randomUUID(), certificate));
	}

	/**
	 * A backup changed after validation must no longer authorise any publication.
	 */
	@Test
	void changedBackupInvalidatesCertificate() throws Exception {
		ApplicationConfig config = fixture();
		UUID packageId = UUID.randomUUID();
		SubjectImportBackupGate gate = new SubjectImportBackupGate(config);
		Certificate certificate = gate.createVerifiedFullBackup(packageId, directory.resolve("tamper-backups"));
		Files.writeString(certificate.backupPath(), "modified archive", StandardCharsets.UTF_8);
		assertThrows(IOException.class, () -> gate.requireIntact(packageId, certificate));
	}

	/**
	 * A verified import backup must include both the authoritative database and
	 * retained managed Subject files.
	 */
	@Test
	void createsVerifiedFullBackupBeforeAnyImportMutation() throws Exception {
		ApplicationConfig config = fixture();
		Path pdf = config.dataRoot().resolve("subjects/Biology/exams/QCAA/2025/questions.pdf");
		byte[] original = Files.readAllBytes(pdf);
		UUID packageId = UUID.randomUUID();
		SubjectImportBackupGate gate = new SubjectImportBackupGate(config);
		Certificate certificate = gate.createVerifiedFullBackup(packageId, directory.resolve("external-backups"));
		assertEquals(packageId, certificate.packageId());
		assertEquals(SqliteDatabase.latestSchemaVersion(), certificate.schemaVersion());
		assertTrue(Files.isRegularFile(certificate.backupPath()));
		gate.requireIntact(packageId, certificate);
		try (ZipFile archive = new ZipFile(certificate.backupPath().toFile())) {
			assertNotNull(archive.getEntry("questionbank.db"));
			assertNotNull(archive.getEntry("subjects/Biology/exams/QCAA/2025/questions.pdf"));
		}
		assertArrayEquals(original, Files.readAllBytes(pdf));
		SqliteDatabase database = new SqliteDatabase(config.databasePath());
		database.verifyIntegrity();
	}

	/**
	 * An import backup cannot be written beneath the managed Subject hierarchy
	 * because it would pollute or recursively depend on the files it is meant to
	 * protect.
	 */
	@Test
	void rejectsBackupDestinationInsideManagedSubjectTree() throws Exception {
		ApplicationConfig config = fixture();
		SubjectImportBackupGate gate = new SubjectImportBackupGate(config);
		Path unsafe = config.dataRoot().resolve("subjects/Biology/backups");
		assertThrows(BackupException.class, () -> gate.createVerifiedFullBackup(UUID.randomUUID(), unsafe));
	}

	/**
	 * Creates a realistic, disposable Biology master database and PDF.
	 */
	private ApplicationConfig fixture() throws Exception {
		ApplicationConfig config = ApplicationConfig.fromDataRoot(directory.resolve("data"));
		Files.createDirectories(config.dataRoot());
		SqliteDatabase database = new SqliteDatabase(config.databasePath());
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					INSERT INTO subjects (subject_name)
					VALUES ('Biology')
					""");
		}
		Path pdf = config.dataRoot().resolve("subjects/Biology/exams/QCAA/2025/questions.pdf");
		Files.createDirectories(pdf.getParent());
		Files.write(pdf, "Existing master Biology PDF".getBytes(StandardCharsets.UTF_8));
		return config;
	}
}
