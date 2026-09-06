package au.edu.eq.questionbank.service.backup;

/**
 * Application-level boundary for creating validated question-bank backups.
 * <p>
 * Implementations are responsible for creating a consistent database snapshot,
 * constructing the requested archive, validating it, and publishing the final
 * backup only after successful validation.
 */
public interface BackupService {

	/**
	 * Creates and validates a backup.
	 *
	 * @param request requested backup kind and destination
	 * @return successful published backup
	 * @throws BackupException      if the backup cannot be completed and validated
	 * @throws NullPointerException if {@code request} is {@code null}
	 */
	BackupResult createBackup(BackupRequest request) throws BackupException;
}
