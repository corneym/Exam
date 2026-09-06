package au.edu.eq.questionbank.service.backup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Applies bounded retention to successfully published automatic database
 * backups.
 */
public final class AutomaticBackupRetention {

	public static final int DEFAULT_RETENTION_LIMIT = 10;
	private static final Pattern AUTOMATIC_BACKUP_FILE_NAME = Pattern
			.compile("^question-bank-auto-" + "\\d{4}-\\d{2}-\\d{2}" + "T\\d{9}Z" + "\\.zip$");
	private final int retentionLimit;

	/**
	 * Creates the standard automatic-backup retention policy.
	 */
	public AutomaticBackupRetention() {
		this(DEFAULT_RETENTION_LIMIT);
	}

	AutomaticBackupRetention(int retentionLimit) {
		if (retentionLimit < 1) {
			throw new IllegalArgumentException("retentionLimit must be positive");
		}
		this.retentionLimit = retentionLimit;
	}

	/**
	 * Deletes automatic backup archives older than the configured retention limit.
	 * <p>
	 * Only files matching the application's automatic-backup filename pattern are
	 * considered. Other files in the directory are left untouched.
	 *
	 * @param automaticBackupDirectory automatic-backup directory
	 * @throws IOException          if the directory cannot be inspected or an
	 *                              expired backup cannot be deleted
	 * @throws NullPointerException if {@code automaticBackupDirectory} is
	 *                              {@code null}
	 */
	public void prune(Path automaticBackupDirectory) throws IOException {
		if (automaticBackupDirectory == null) {
			throw new NullPointerException("automaticBackupDirectory");
		}
		Path directory = automaticBackupDirectory.toAbsolutePath().normalize();
		if (!Files.exists(directory)) {
			return;
		}
		if (!Files.isDirectory(directory)) {
			throw new IOException("Automatic backup location is not a directory: " + directory);
		}
		List<Path> automaticBackups;
		try (Stream<Path> stream = Files.list(directory)) {
			automaticBackups = stream.filter(this::isAutomaticBackup).sorted(this::compareNewestFirst).toList();
		}
		for (int index = retentionLimit; index < automaticBackups.size(); index++) {
			Files.delete(automaticBackups.get(index));
		}
	}

	private int compareNewestFirst(Path first, Path second) {
		String firstName = first.getFileName().toString();
		String secondName = second.getFileName().toString();
		return secondName.compareTo(firstName);
	}

	private boolean isAutomaticBackup(Path path) {
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
			return false;
		}
		String fileName = path.getFileName().toString();
		return AUTOMATIC_BACKUP_FILE_NAME.matcher(fileName).matches();
	}
}
