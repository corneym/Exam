package au.edu.eq.questionbank.service.backup;

/**
 * Describes the outcome of a backup-aware application shutdown attempt.
 */
public enum ShutdownStatus {
	/** Backup and resource closure completed successfully. */
	READY_TO_EXIT,
	/** Shutdown may proceed, but old-backup retention could not be completed. */
	READY_TO_EXIT_WITH_RETENTION_WARNING,
	/** The shutdown backup failed and requires a user decision. */
	BACKUP_FAILED,
	/** Application resources could not be closed successfully. */
	RESOURCE_CLOSE_FAILED,
	/** A previous shutdown attempt already prepared the application to exit. */
	ALREADY_READY
}
