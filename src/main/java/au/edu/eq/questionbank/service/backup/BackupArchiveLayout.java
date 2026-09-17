package au.edu.eq.questionbank.service.backup;

/**
 * Defines the canonical paths used inside a question-bank backup archive.
 */
public final class BackupArchiveLayout {

	/**
	 * Archive directory containing managed curriculum source files.
	 */
	public static final String CURRICULUM_DIRECTORY_ENTRY = "curriculum/";
	/**
	 * Archive entry containing the SQLite database snapshot.
	 */
	public static final String DATABASE_ENTRY = "questionbank.db";
	/**
	 * Archive entry describing backup format and contents.
	 */
	public static final String MANIFEST_ENTRY = "backup-manifest.properties";
	/**
	 * Archive directory containing managed examination and answer PDFs.
	 */
	public static final String PDF_DIRECTORY_ENTRY = "pdf/";

	private BackupArchiveLayout() {
	}
}
