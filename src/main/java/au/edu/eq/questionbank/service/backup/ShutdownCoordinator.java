package au.edu.eq.questionbank.service.backup;

import java.io.IOException;

/**
 * Coordinates backup-aware application shutdown independently of JavaFX.
 * <p>
 * A normal shutdown creates one automatic database backup, applies retention,
 * then closes application resources. A failed backup leaves the application
 * open so the caller may retry, cancel, or explicitly exit without backup.
 */
public final class ShutdownCoordinator {

	private final BackupRequest automaticBackupRequest;
	private final BackupService backupService;
	private final AutomaticBackupRetention retention;
	private final AutoCloseable resources;
	private boolean backupCompleted;
	private boolean readyToExit;

	/**
	 * Creates a backup-aware shutdown coordinator.
	 *
	 * @param backupService          automatic backup service
	 * @param automaticBackupRequest automatic database-backup request
	 * @param retention              automatic-backup retention policy
	 * @param resources              application resources that must be closed
	 *                               before exit
	 */
	public ShutdownCoordinator(BackupService backupService, BackupRequest automaticBackupRequest,
			AutomaticBackupRetention retention, AutoCloseable resources) {
		if (backupService == null) {
			throw new NullPointerException("backupService");
		}
		if (automaticBackupRequest == null) {
			throw new NullPointerException("automaticBackupRequest");
		}
		if (retention == null) {
			throw new NullPointerException("retention");
		}
		if (resources == null) {
			throw new NullPointerException("resources");
		}
		if (automaticBackupRequest.kind() != BackupKind.AUTOMATIC_DATABASE) {
			throw new IllegalArgumentException("Shutdown backup request must be AUTOMATIC_DATABASE");
		}
		this.backupService = backupService;
		this.automaticBackupRequest = automaticBackupRequest;
		this.retention = retention;
		this.resources = resources;
	}

	/**
	 * Prepares the application for a deliberate exit without creating an automatic
	 * backup.
	 *
	 * @return shutdown outcome
	 */
	public synchronized ShutdownResult exitWithoutBackup() {
		if (readyToExit) {
			return new ShutdownResult(ShutdownStatus.ALREADY_READY, null);
		}
		try {
			resources.close();
		} catch (Exception e) {
			return new ShutdownResult(ShutdownStatus.RESOURCE_CLOSE_FAILED, e);
		}
		readyToExit = true;
		return new ShutdownResult(ShutdownStatus.READY_TO_EXIT, null);
	}

	/**
	 * Returns whether shutdown preparation has completed successfully.
	 *
	 * @return {@code true} when application resources have been closed and exit is
	 *         permitted
	 */
	public synchronized boolean isReadyToExit() {
		return readyToExit;
	}

	/**
	 * Prepares the application for a normal backup-aware exit.
	 * <p>
	 * A failed backup does not close application resources. Calling this method
	 * again retries the failed backup. Once the backup succeeds it is not repeated,
	 * even if resource closing subsequently has to be retried.
	 *
	 * @return shutdown outcome
	 */
	public synchronized ShutdownResult prepareForExit() {
		if (readyToExit) {
			return new ShutdownResult(ShutdownStatus.ALREADY_READY, null);
		}
		Throwable retentionWarning = null;
		if (!backupCompleted) {
			try {
				backupService.createBackup(automaticBackupRequest);
				backupCompleted = true;
			} catch (BackupException e) {
				return new ShutdownResult(ShutdownStatus.BACKUP_FAILED, e);
			}
			try {
				retention.prune(automaticBackupRequest.destinationDirectory());
			} catch (IOException e) {
				retentionWarning = e;
			}
		}
		try {
			resources.close();
		} catch (Exception e) {
			return new ShutdownResult(ShutdownStatus.RESOURCE_CLOSE_FAILED, e);
		}
		readyToExit = true;
		if (retentionWarning != null) {
			return new ShutdownResult(ShutdownStatus.READY_TO_EXIT_WITH_RETENTION_WARNING, retentionWarning);
		}
		return new ShutdownResult(ShutdownStatus.READY_TO_EXIT, null);
	}
}
