package au.edu.eq.questionbank.service.backup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

/**
 * Default implementation of validated restore preparation.
 */
public final class DefaultRestoreService implements RestoreService {

	private final BackupArchiveStager archiveStager;
	private final BackupArchiveValidator archiveValidator;
	private final ApplicationConfig config;
	private final BackupFilesystemSafety filesystemSafety;

	/**
	 * Creates a restore service for the current application data location.
	 *
	 * @param config application filesystem configuration
	 */
	public DefaultRestoreService(ApplicationConfig config) {
		if (config == null) {
			throw new NullPointerException("config");
		}
		this.config = config;
		BackupManifestCodec manifestCodec = new BackupManifestCodec();
		this.archiveValidator = new BackupArchiveValidator(manifestCodec);
		this.archiveStager = new BackupArchiveStager();
		this.filesystemSafety = new BackupFilesystemSafety();
	}

	@Override
	public RestorePreparation prepareRestore(Path backupPath) throws RestoreException {
		if (backupPath == null) {
			throw new NullPointerException("backupPath");
		}
		Path normalisedBackupPath = backupPath.toAbsolutePath().normalize();
		Path stagingRoot = null;
		try {
			filesystemSafety.validateApplicationLayout(config);
			BackupManifest manifest = archiveValidator.validate(normalisedBackupPath);
			Files.createDirectories(config.dataRoot());
			stagingRoot = Files.createTempDirectory(config.dataRoot(), ".question-bank-restore-");
			archiveStager.stage(normalisedBackupPath, stagingRoot);
			validateStagedDatabase(stagingRoot, manifest);
			validateStagedManagedRoots(stagingRoot, manifest);
			return new RestorePreparation(normalisedBackupPath, manifest, stagingRoot);
		} catch (IOException | SQLException | BackupFormatException e) {
			if (stagingRoot != null) {
				cleanupAfterFailure(stagingRoot, e);
			}
			throw new RestoreException("Unable to prepare backup for restore", e);
		}
	}

	private void cleanupAfterFailure(Path stagingRoot, Throwable failure) {
		try {
			RestoreFiles.deleteTree(stagingRoot);
		} catch (IOException cleanupFailure) {
			failure.addSuppressed(cleanupFailure);
		}
	}

	private void validateStagedDatabase(Path stagingRoot, BackupManifest manifest)
			throws IOException, SQLException, BackupFormatException {
		Path stagedDatabasePath = stagingRoot.resolve(BackupArchiveLayout.DATABASE_ENTRY);
		if (!Files.isRegularFile(stagedDatabasePath)) {
			throw new BackupFormatException("Staged backup database is missing");
		}
		SqliteDatabase database = new SqliteDatabase(stagedDatabasePath);
		int schemaVersion = database.schemaVersion();
		if (schemaVersion != manifest.databaseSchemaVersion()) {
			throw new BackupFormatException("Staged database schema version " + schemaVersion
					+ " does not match manifest schema version " + manifest.databaseSchemaVersion());
		}
		database.verifyMigrationCompatibility();
	}

	private void validateStagedManagedRoots(Path stagingRoot, BackupManifest manifest) throws BackupFormatException {
		Path pdfRoot = stagingRoot.resolve("pdf");
		Path curriculumRoot = stagingRoot.resolve("curriculum");
		if (manifest.kind() == BackupKind.FULL) {
			if (!Files.isDirectory(pdfRoot)) {
				throw new BackupFormatException("Full backup is missing staged PDF hierarchy");
			}
			if (!Files.isDirectory(curriculumRoot)) {
				throw new BackupFormatException("Full backup is missing staged curriculum hierarchy");
			}
			return;
		}
		if (Files.exists(pdfRoot) || Files.exists(curriculumRoot)) {
			throw new BackupFormatException("Database-only backup contains managed data");
		}
	}
}
