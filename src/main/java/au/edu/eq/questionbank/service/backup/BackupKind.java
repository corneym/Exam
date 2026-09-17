package au.edu.eq.questionbank.service.backup;

import java.util.List;

/**
 * Identifies the supported kinds of question-bank backup.
 */
public enum BackupKind {

	/**
	 * Automatic backup containing the database and manifest only.
	 */
	AUTOMATIC_DATABASE("auto", List.of(BackupArchiveLayout.MANIFEST_ENTRY, BackupArchiveLayout.DATABASE_ENTRY)),
	/**
	 * Full backup containing the database, manifest and managed source files.
	 */
	FULL("full", List.of(BackupArchiveLayout.MANIFEST_ENTRY, BackupArchiveLayout.DATABASE_ENTRY,
			BackupArchiveLayout.PDF_DIRECTORY_ENTRY, BackupArchiveLayout.CURRICULUM_DIRECTORY_ENTRY));

	private final String fileNameToken;
	private final List<String> requiredArchiveEntries;

	BackupKind(String fileNameToken, List<String> requiredArchiveEntries) {
		this.fileNameToken = fileNameToken;
		this.requiredArchiveEntries = requiredArchiveEntries;
	}

	/**
	 * Returns the short token used in generated backup filenames.
	 *
	 * @return the filename token
	 */
	public String fileNameToken() {
		return fileNameToken;
	}

	/**
	 * Returns the archive entries required for this backup kind.
	 *
	 * @return the immutable required-entry list
	 */
	public List<String> requiredArchiveEntries() {
		return requiredArchiveEntries;
	}
}
