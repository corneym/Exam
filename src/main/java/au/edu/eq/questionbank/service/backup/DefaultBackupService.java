package au.edu.eq.questionbank.service.backup;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

/**
 * Default application-level implementation of validated question-bank backup
 * creation.
 */
public final class DefaultBackupService implements BackupService {

	private final ApplicationConfig config;
	private final String applicationVersion;
	private final BackupArchiveValidator archiveValidator;
	private final BackupArchiveWriter archiveWriter;
	private final Clock clock;
	private final BackupPathResolver pathResolver;
	private final BackupFilesystemSafety filesystemSafety;

	/**
	 * Creates a backup service using the system UTC clock.
	 *
	 * @param config             application filesystem configuration
	 * @param applicationVersion application version recorded in manifests
	 */
	public DefaultBackupService(ApplicationConfig config, String applicationVersion) {
		this(config, applicationVersion, Clock.systemUTC());
	}

	DefaultBackupService(ApplicationConfig config, String applicationVersion, Clock clock) {
		if (config == null) {
			throw new NullPointerException("config");
		}
		if (applicationVersion == null) {
			throw new NullPointerException("applicationVersion");
		}
		if (applicationVersion.isBlank()) {
			throw new IllegalArgumentException("applicationVersion must not be blank");
		}
		if (clock == null) {
			throw new NullPointerException("clock");
		}
		this.config = config;
		this.applicationVersion = applicationVersion.trim();
		this.clock = clock;
		BackupManifestCodec manifestCodec = new BackupManifestCodec();
		this.archiveValidator = new BackupArchiveValidator(manifestCodec);
		this.archiveWriter = new BackupArchiveWriter(manifestCodec);
		this.pathResolver = new BackupPathResolver();
		this.filesystemSafety = new BackupFilesystemSafety();
	}

	@Override
	public BackupResult createBackup(BackupRequest request) throws BackupException {
		if (request == null) {
			throw new NullPointerException("request");
		}
		try {
			validateDestination(request);
			Instant createdAt = clock.instant();
			Path finalBackupPath = pathResolver.resolveBackupPath(request, createdAt);
			if (Files.exists(finalBackupPath)) {
				throw new IOException("Backup already exists: " + finalBackupPath);
			}
			Files.createDirectories(request.destinationDirectory());

			// Stage beside the destination so publication can use a same-filesystem move.
			Path workDirectory = Files.createTempDirectory(request.destinationDirectory(),
					".question-bank-backup-work-");
			Path temporaryArchive = Files.createTempFile(request.destinationDirectory(), ".question-bank-backup-",
					".tmp");
			try {
				BackupManifest validatedManifest = createValidatedArchive(request, createdAt, workDirectory,
						temporaryArchive);
				deleteTree(workDirectory);

				// Expose the final archive name only after validation and snapshot cleanup
				// succeed.
				publish(temporaryArchive, finalBackupPath);
				return new BackupResult(finalBackupPath, validatedManifest);
			} catch (IOException | SQLException | BackupFormatException | RuntimeException e) {
				cleanupAfterFailure(workDirectory, temporaryArchive, e);
				throw e;
			}
		} catch (IOException | SQLException | BackupFormatException e) {
			throw new BackupException("Unable to create " + request.kind() + " backup", e);
		}
	}

	private BackupManifest createValidatedArchive(BackupRequest request, Instant createdAt, Path workDirectory,
			Path temporaryArchive) throws IOException, SQLException, BackupFormatException {

		// Archive a consistent SQLite snapshot, then reopen the ZIP to validate
		// everything written.
		Path snapshotPath = workDirectory.resolve(BackupArchiveLayout.DATABASE_ENTRY);
		SqliteDatabase sourceDatabase = new SqliteDatabase(config.databasePath());
		sourceDatabase.createConsistentSnapshot(snapshotPath);
		SqliteDatabase snapshotDatabase = new SqliteDatabase(snapshotPath);
		BackupManifest manifest = BackupManifest.current(request.kind(), createdAt, snapshotDatabase.schemaVersion(),
				applicationVersion);
		archiveWriter.write(temporaryArchive, manifest, snapshotPath, config);
		return archiveValidator.validate(temporaryArchive);
	}

	private void cleanupAfterFailure(Path workDirectory, Path temporaryArchive, Throwable failure) {
		try {
			deleteTree(workDirectory);
		} catch (IOException cleanupFailure) {
			failure.addSuppressed(cleanupFailure);
		}
		try {
			Files.deleteIfExists(temporaryArchive);
		} catch (IOException cleanupFailure) {
			failure.addSuppressed(cleanupFailure);
		}
	}

	private void deleteTree(Path root) throws IOException {
		if (root == null || !Files.exists(root)) {
			return;
		}
		List<Path> paths;
		try (Stream<Path> stream = Files.walk(root)) {
			paths = stream.sorted(Comparator.reverseOrder()).toList();
		}
		for (Path path : paths) {
			Files.deleteIfExists(path);
		}
	}

	private void publish(Path temporaryArchive, Path finalBackupPath) throws IOException {
		try {
			Files.move(temporaryArchive, finalBackupPath, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(temporaryArchive, finalBackupPath);
		}
	}

	private void validateDestination(BackupRequest request) throws BackupException {
		try {
			filesystemSafety.validateBackupDestination(config, request.destinationDirectory());
		} catch (IOException e) {
			throw new BackupException("Unsafe backup filesystem layout", e);
		}
	}
}
