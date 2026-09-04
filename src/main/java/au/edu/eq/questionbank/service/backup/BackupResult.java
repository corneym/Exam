package au.edu.eq.questionbank.service.backup;

import java.nio.file.Path;

/**
 * Describes a successfully completed and validated backup.
 *
 * @param backupPath final published backup archive
 * @param manifest   manifest describing the archive
 */
public record BackupResult(Path backupPath, BackupManifest manifest) {

	/**
	 * Validates successful backup result data.
	 */
	public BackupResult {
		if (backupPath == null) {
			throw new NullPointerException("backupPath");
		}
		if (manifest == null) {
			throw new NullPointerException("manifest");
		}
		backupPath = backupPath.toAbsolutePath().normalize();
	}
}
