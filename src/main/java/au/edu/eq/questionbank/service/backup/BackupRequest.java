package au.edu.eq.questionbank.service.backup;

import java.nio.file.Path;

import au.edu.eq.questionbank.ApplicationConfig;

/**
 * Describes a requested backup operation.
 *
 * @param kind                 kind of backup to create
 * @param destinationDirectory directory beneath which the backup archive will
 *                             be created
 */
public record BackupRequest(BackupKind kind, Path destinationDirectory) {

	/**
	 * Validates and normalises the requested destination.
	 */
	public BackupRequest {
		if (kind == null) {
			throw new NullPointerException("kind");
		}
		if (destinationDirectory == null) {
			throw new NullPointerException("destinationDirectory");
		}
		destinationDirectory = destinationDirectory.toAbsolutePath().normalize();
	}

	/**
	 * Creates the standard automatic database-backup request.
	 *
	 * @param config application filesystem configuration
	 * @return automatic database-backup request
	 */
	public static BackupRequest automaticDatabase(ApplicationConfig config) {
		if (config == null) {
			throw new NullPointerException("config");
		}
		Path destinationDirectory = config.dataRoot().resolve("backups").resolve("automatic");
		return new BackupRequest(BackupKind.AUTOMATIC_DATABASE, destinationDirectory);
	}

	/**
	 * Creates a full-backup request for a caller-selected destination directory.
	 *
	 * @param destinationDirectory destination directory
	 * @return full-backup request
	 */
	public static BackupRequest full(Path destinationDirectory) {
		return new BackupRequest(BackupKind.FULL, destinationDirectory);
	}
}
