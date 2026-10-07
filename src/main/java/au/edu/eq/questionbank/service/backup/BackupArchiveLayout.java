package au.edu.eq.questionbank.service.backup;

/**
 * Defines the canonical paths used inside a question-bank backup archive.
 */
public final class BackupArchiveLayout {

	/**
	 * Archive entry containing the SQLite database snapshot.
	 */
	public static final String DATABASE_ENTRY = "questionbank.db";
	/**
	 * Archive entry describing backup format and contents.
	 */
	public static final String MANIFEST_ENTRY = "backup-manifest.properties";
	/**
	 * Archive directory containing all Subject-scoped managed source assets.
	 */
	public static final String SUBJECTS_DIRECTORY_ENTRY = "subjects/";

	private BackupArchiveLayout() {
	}
}
