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
	 * Full backup containing the database, manifest and complete Subject-first
	 * managed source tree.
	 */
	FULL("full", List.of(BackupArchiveLayout.MANIFEST_ENTRY, BackupArchiveLayout.DATABASE_ENTRY,
			BackupArchiveLayout.SUBJECTS_DIRECTORY_ENTRY));

	private final String fileNameToken;
	private final List<String> requiredArchiveEntries;

	BackupKind(String fileNameToken, List<String> requiredArchiveEntries) {
		this.fileNameToken = fileNameToken;
		this.requiredArchiveEntries = requiredArchiveEntries;
	}

	/**
	 * Returns the short token used in generated backup filenames.
	 *
	 * @return filename token
	 */
	public String fileNameToken() {
		return fileNameToken;
	}

	/**
	 * Returns the archive entries required for this backup kind.
	 *
	 * @return immutable required-entry list
	 */
	public List<String> requiredArchiveEntries() {
		return requiredArchiveEntries;
	}
}
