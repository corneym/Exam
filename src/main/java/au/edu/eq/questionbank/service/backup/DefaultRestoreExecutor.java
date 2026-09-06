package au.edu.eq.questionbank.service.backup;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

/**
 * Default implementation of destructive restore application.
 * <p>
 * A full safety backup is created before current data is changed. Active
 * resources are then closed, the staged backup is installed and the resulting
 * live data is validated. If installation fails, automatic rollback is
 * attempted from the safety backup.
 */
public final class DefaultRestoreExecutor implements RestoreExecutor {

	private final ApplicationConfig config;
	private final BackupService backupService;
	private final RestoreService restoreService;

	/**
	 * Creates a restore executor.
	 *
	 * @param config             application filesystem configuration
	 * @param applicationVersion application version recorded in the safety backup
	 */
	public DefaultRestoreExecutor(ApplicationConfig config, String applicationVersion) {
		if (config == null) {
			throw new NullPointerException("config");
		}
		if (applicationVersion == null) {
			throw new NullPointerException("applicationVersion");
		}
		if (applicationVersion.isBlank()) {
			throw new IllegalArgumentException("applicationVersion must not be blank");
		}
		this.config = config;
		this.backupService = new DefaultBackupService(config, applicationVersion);
		this.restoreService = new DefaultRestoreService(config);
	}

	DefaultRestoreExecutor(ApplicationConfig config, BackupService backupService, RestoreService restoreService) {
		if (config == null) {
			throw new NullPointerException("config");
		}
		if (backupService == null) {
			throw new NullPointerException("backupService");
		}
		if (restoreService == null) {
			throw new NullPointerException("restoreService");
		}
		this.config = config;
		this.backupService = backupService;
		this.restoreService = restoreService;
	}

	@Override
	public RestoreResult applyRestore(RestorePreparation preparation, AutoCloseable resources) throws RestoreException {
		if (preparation == null) {
			throw new NullPointerException("preparation");
		}
		if (resources == null) {
			throw new NullPointerException("resources");
		}
		BackupResult safetyBackup = createSafetyBackup();
		try {
			resources.close();
		} catch (Exception e) {
			throw new RestoreException(
					"Current data was not changed because application resources could not be closed. "
							+ "A pre-restore safety backup was created at " + safetyBackup.backupPath(),
					e, true);
		}
		try {
			replaceLiveData(preparation);
			verifyLiveData(preparation.manifest());
			return new RestoreResult(preparation.manifest(), safetyBackup.backupPath());
		} catch (IOException | SQLException | RuntimeException restoreFailure) {
			boolean rollbackSucceeded = rollback(safetyBackup.backupPath(), restoreFailure);
			String message;
			if (rollbackSucceeded) {
				message = "Restore failed. The previous data was restored from the pre-restore safety backup. "
						+ "The application must now close. " + "Safety backup: " + safetyBackup.backupPath();
			} else {
				message = "Restore failed and automatic rollback also failed. " + "The application must now close. "
						+ "Do not continue using the current data. " + "The pre-restore safety backup is available at "
						+ safetyBackup.backupPath();
			}
			throw new RestoreException(message, restoreFailure, true);
		}
	}

	private RestoreException createRestoreException(String message, Throwable cause) {
		return new RestoreException(message, cause);
	}

	private BackupResult createSafetyBackup() throws RestoreException {
		Path safetyBackupDirectory = config.dataRoot().resolve("backups").resolve("pre-restore");
		try {
			return backupService.createBackup(BackupRequest.full(safetyBackupDirectory));
		} catch (BackupException e) {
			throw createRestoreException(
					"Restore was cancelled because the pre-restore safety backup could not be created.", e);
		}
	}

	private void deleteDatabaseSidecars() throws IOException {
		Path databasePath = config.databasePath();
		Files.deleteIfExists(Path.of(databasePath + "-wal"));
		Files.deleteIfExists(Path.of(databasePath + "-shm"));
	}

	private void moveDirectory(Path source, Path destination) throws IOException {
		try {
			Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(source, destination);
		}
	}

	private void publishDatabase(RestorePreparation preparation) throws IOException, SQLException {
		Path stagedDatabase = preparation.databasePath();
		if (!Files.isRegularFile(stagedDatabase)) {
			throw new IOException("Staged restore database is missing: " + stagedDatabase);
		}
		Path databaseParent = config.databasePath().getParent();
		Files.createDirectories(databaseParent);
		Path temporaryDatabase = Files.createTempFile(databaseParent, ".question-bank-restore-db-", ".tmp");
		try {
			Files.copy(stagedDatabase, temporaryDatabase, StandardCopyOption.REPLACE_EXISTING);
			SqliteDatabase temporary = new SqliteDatabase(temporaryDatabase);
			int actualSchemaVersion = temporary.schemaVersion();
			if (actualSchemaVersion != preparation.manifest().databaseSchemaVersion()) {
				throw new SQLException("Restore database schema version " + actualSchemaVersion
						+ " does not match backup manifest schema version "
						+ preparation.manifest().databaseSchemaVersion());
			}
			temporary.verifySchema();
			temporary.verifyIntegrity();
			deleteDatabaseSidecars();
			try {
				Files.move(temporaryDatabase, config.databasePath(), StandardCopyOption.ATOMIC_MOVE,
						StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(temporaryDatabase, config.databasePath(), StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temporaryDatabase);
		}
	}

	private void publishManagedRoot(Path stagedRoot, Path liveRoot) throws IOException {
		if (!Files.isDirectory(stagedRoot)) {
			throw new IOException("Staged managed data root is missing: " + stagedRoot);
		}
		RestoreFiles.deleteTree(liveRoot);
		Path parent = liveRoot.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		moveDirectory(stagedRoot, liveRoot);
	}

	private void replaceLiveData(RestorePreparation preparation) throws IOException, SQLException {
		if (preparation.manifest().kind() == BackupKind.FULL) {
			publishManagedRoot(preparation.pdfRoot(), config.pdfDataRoot());
			publishManagedRoot(preparation.curriculumRoot(), config.curriculumDataRoot());
		}
		publishDatabase(preparation);
	}

	private boolean rollback(Path safetyBackupPath, Throwable restoreFailure) {
		try (RestorePreparation rollbackPreparation = restoreService.prepareRestore(safetyBackupPath)) {
			replaceLiveData(rollbackPreparation);
			verifyLiveData(rollbackPreparation.manifest());
			return true;
		} catch (Exception rollbackFailure) {
			restoreFailure.addSuppressed(rollbackFailure);
			return false;
		}
	}

	private void verifyLiveData(BackupManifest manifest) throws SQLException, IOException {
		SqliteDatabase database = new SqliteDatabase(config.databasePath());
		int schemaVersion = database.schemaVersion();
		if (schemaVersion != manifest.databaseSchemaVersion()) {
			throw new SQLException("Restored database schema version " + schemaVersion
					+ " does not match backup manifest schema version " + manifest.databaseSchemaVersion());
		}
		database.verifySchema();
		database.verifyIntegrity();
		if (manifest.kind() == BackupKind.FULL) {
			if (!Files.isDirectory(config.pdfDataRoot())) {
				throw new IOException("Restored PDF hierarchy is missing");
			}
			if (!Files.isDirectory(config.curriculumDataRoot())) {
				throw new IOException("Restored curriculum hierarchy is missing");
			}
		}
	}
}
