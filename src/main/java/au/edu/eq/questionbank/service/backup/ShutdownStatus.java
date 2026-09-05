package au.edu.eq.questionbank.service.backup;

/**
 * Describes the outcome of a backup-aware application shutdown attempt.
 */
public enum ShutdownStatus {
	READY_TO_EXIT, READY_TO_EXIT_WITH_RETENTION_WARNING, BACKUP_FAILED, RESOURCE_CLOSE_FAILED, ALREADY_READY
}
