package au.edu.eq.questionbank.service.backup;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Resolves deterministic, filesystem-safe final paths for backup archives.
 */
public final class BackupPathResolver {

	/**
	 * Creates a resolver for timestamped backup archive destinations.
	 */
	public BackupPathResolver() {
	}

	private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter
			.ofPattern("uuuu-MM-dd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC);

	/**
	 * Resolves the final archive path for a backup request.
	 * <p>
	 * The generated archive is always an immediate child of the requested
	 * destination directory.
	 *
	 * @param request   backup request
	 * @param createdAt backup creation timestamp
	 * @return normalised absolute final archive path
	 * @throws NullPointerException     if either argument is {@code null}
	 * @throws IllegalArgumentException if an existing destination is not a
	 *                                  directory or the resolved output does not
	 *                                  remain within that directory
	 */
	public Path resolveBackupPath(BackupRequest request, Instant createdAt) {
		if (request == null) {
			throw new NullPointerException("request");
		}
		if (createdAt == null) {
			throw new NullPointerException("createdAt");
		}
		Path destinationDirectory = request.destinationDirectory();
		if (Files.exists(destinationDirectory) && !Files.isDirectory(destinationDirectory)) {
			throw new IllegalArgumentException("Backup destination is not a directory: " + destinationDirectory);
		}
		String fileName = "question-bank-" + request.kind().fileNameToken() + "-"
				+ TIMESTAMP_FORMATTER.format(createdAt) + ".zip";
		Path backupPath = destinationDirectory.resolve(fileName).normalize();
		if (!backupPath.startsWith(destinationDirectory) || !destinationDirectory.equals(backupPath.getParent())) {
			throw new IllegalArgumentException("Backup path must remain within the requested destination");
		}
		return backupPath;
	}
}
