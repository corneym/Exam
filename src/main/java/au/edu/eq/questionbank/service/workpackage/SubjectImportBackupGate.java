package au.edu.eq.questionbank.service.workpackage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.ApplicationVersion;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.backup.BackupException;
import au.edu.eq.questionbank.service.backup.BackupKind;
import au.edu.eq.questionbank.service.backup.BackupRequest;
import au.edu.eq.questionbank.service.backup.BackupResult;
import au.edu.eq.questionbank.service.backup.DefaultBackupService;

/**
 * Establishes verified full-backup evidence before Subject import.
 *
 * <p>
 * A successful certificate identifies a fully validated application backup
 * containing the SQLite snapshot and the managed Subject tree.
 *
 * <p>
 * The certificate is bound to the incoming package UUID. Publication must
 * recheck its checksum and must not treat a database-only automatic backup as
 * sufficient recovery evidence.
 *
 * <p>
 * This service never changes imported Subject records or assets.
 */
public final class SubjectImportBackupGate {

	private final ApplicationConfig config;

	/**
	 * Creates the full-backup gate for the authoritative data root.
	 *
	 * @param config active application filesystem configuration
	 */
	public SubjectImportBackupGate(ApplicationConfig config) {
		this.config = Objects.requireNonNull(config, "config");
	}

	/**
	 * Creates and verifies a recoverable full backup for an approved import.
	 *
	 * <p>
	 * The existing backup service validates ZIP entries and the SQLite snapshot
	 * before publishing its final backup archive.
	 *
	 * @param packageId       approved incoming package UUID
	 * @param backupDirectory destination outside the managed Subject tree
	 * @return immutable verified-backup certificate
	 * @throws BackupException if full backup creation or validation fails
	 * @throws IOException     if final checksum verification fails
	 */
	public Certificate createVerifiedFullBackup(UUID packageId, Path backupDirectory)
			throws BackupException, IOException {
		Objects.requireNonNull(packageId, "packageId");
		Objects.requireNonNull(backupDirectory, "backupDirectory");
		DefaultBackupService service = new DefaultBackupService(config, ApplicationVersion.current());
		BackupResult result = service.createBackup(BackupRequest.full(backupDirectory));
		if (result.manifest().kind() != BackupKind.FULL) {
			throw new IllegalStateException("Subject import requires a FULL application backup");
		}
		if (result.manifest().databaseSchemaVersion() != SqliteDatabase.latestSchemaVersion()) {
			throw new IllegalStateException("Import backup schema does not match the active application");
		}
		Certificate certificate = new Certificate(packageId, result.backupPath(), sha256(result.backupPath()),
				result.manifest().databaseSchemaVersion());
		requireIntact(packageId, certificate);
		return certificate;
	}

	/**
	 * Requires the verified full backup to remain byte-for-byte intact.
	 *
	 * <p>
	 * This check must run again immediately before an import publisher starts
	 * exposing managed files or committing SQLite changes.
	 *
	 * @param packageId   incoming package being published
	 * @param certificate previously created full-backup certificate
	 * @throws IOException              if the backup is missing, replaced or
	 *                                  changed
	 * @throws IllegalArgumentException if the certificate belongs to another
	 *                                  package
	 */
	public void requireIntact(UUID packageId, Certificate certificate) throws IOException {
		Objects.requireNonNull(packageId, "packageId");
		Objects.requireNonNull(certificate, "certificate");
		if (!packageId.equals(certificate.packageId())) {
			throw new IllegalArgumentException("Backup certificate belongs to another import package");
		}
		if (!Files.isRegularFile(certificate.backupPath(), LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Verified import backup is no longer an ordinary file");
		}
		if (!sha256(certificate.backupPath()).equals(certificate.archiveSha256())) {
			throw new IOException("Verified import backup has changed since creation");
		}
	}

	/**
	 * Calculates a file's complete SHA-256 digest.
	 */
	private String sha256(Path file) throws IOException {
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
		try (InputStream input = Files.newInputStream(file)) {
			byte[] buffer = new byte[8192];
			int count;
			while ((count = input.read(buffer)) != -1) {
				digest.update(buffer, 0, count);
			}
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	/**
	 * Immutable evidence of a verified full application backup.
	 *
	 * @param packageId     incoming package UUID
	 * @param backupPath    published full-backup archive path
	 * @param archiveSha256 verified complete archive SHA-256
	 * @param schemaVersion database schema version stored in the backup
	 */
	public record Certificate(UUID packageId, Path backupPath, String archiveSha256, int schemaVersion) {

		/**
		 * Validates the immutable certificate fields.
		 */
		public Certificate {
			Objects.requireNonNull(packageId, "packageId");
			Objects.requireNonNull(backupPath, "backupPath");
			backupPath = backupPath.toAbsolutePath().normalize();
			if (archiveSha256 == null || !archiveSha256.matches("[0-9a-f]{64}")) {
				throw new IllegalArgumentException("Full-backup checksum must be SHA-256");
			}
			if (schemaVersion < 1) {
				throw new IllegalArgumentException("Backup schema version must be positive");
			}
		}
	}
}
