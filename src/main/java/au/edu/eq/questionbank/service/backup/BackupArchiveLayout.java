package au.edu.eq.questionbank.service.backup;

/**
 * Defines the canonical paths used inside a question-bank backup archive.
 */
public final class BackupArchiveLayout {

	public static final String CURRICULUM_DIRECTORY_ENTRY = "curriculum/";
	public static final String DATABASE_ENTRY = "questionbank.db";
	public static final String MANIFEST_ENTRY = "backup-manifest.properties";
	public static final String PDF_DIRECTORY_ENTRY = "pdf/";

	private BackupArchiveLayout() {
	}
}
