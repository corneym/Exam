package au.edu.eq.questionbank.service.migration;

/**
 * Summary of one successfully published Subject-first data migration.
 *
 * @param copiedFiles                  destination files created during this run
 * @param reusedFiles                  byte-identical destination files reused
 * @param updatedExamPaths             SourceDocument paths rewritten
 * @param updatedCurriculumSourcePaths syllabus source-PDF paths rewritten
 */
public record DataLayoutMigrationResult(int copiedFiles, int reusedFiles, int updatedExamPaths,
		int updatedCurriculumSourcePaths) {

	/**
	 * Validates migration result counts.
	 */
	public DataLayoutMigrationResult {
		if (copiedFiles < 0 || reusedFiles < 0 || updatedExamPaths < 0 || updatedCurriculumSourcePaths < 0) {
			throw new IllegalArgumentException("Migration result counts must not be negative");
		}
	}
}
