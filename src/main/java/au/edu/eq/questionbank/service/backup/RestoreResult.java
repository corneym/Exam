package au.edu.eq.questionbank.service.backup;

import java.nio.file.Path;

/**
 * Describes a successfully applied restore.
 *
 * @param restoredManifest restored backup metadata
 * @param safetyBackupPath pre-restore full safety backup
 */
public record RestoreResult(BackupManifest restoredManifest, Path safetyBackupPath) {

	/**
	 * Validates successful restore result data.
	 */
	public RestoreResult {
		if (restoredManifest == null) {
			throw new NullPointerException("restoredManifest");
		}
		if (safetyBackupPath == null) {
			throw new NullPointerException("safetyBackupPath");
		}
		safetyBackupPath = safetyBackupPath.toAbsolutePath().normalize();
	}
}
