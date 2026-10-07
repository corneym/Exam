package au.edu.eq.questionbank.service.migration;

/**
 * Result of preserving and removing legacy managed roots after successful
 * Subject-first publication.
 *
 * @param archivedFiles      legacy files copied to the recovery archive
 * @param reusedArchiveFiles byte-identical archive files reused on re-run
 */
public record DataLayoutMigrationFinalizationResult(int archivedFiles, int reusedArchiveFiles) {

	/**
	 * Validates finalisation result counts.
	 *
	 * @throws IllegalArgumentException if either count is negative
	 */
	public DataLayoutMigrationFinalizationResult {
		if (archivedFiles < 0 || reusedArchiveFiles < 0) {
			throw new IllegalArgumentException("Archive counts must not be negative");
		}
	}
}
