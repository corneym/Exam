package au.edu.eq.questionbank.service.migration;

/**
 * Explicit ownership supplied for a pre-Sprint-14 curriculum workbook whose
 * former flat managed location did not persist Subject/version provenance.
 *
 * @param legacyRelativePath path relative to the former curriculum root
 * @param subjectName        persisted owning Subject
 * @param syllabusVersion    persisted owning syllabus-version name
 */
public record CurriculumWorkbookMigrationAssignment(String legacyRelativePath, String subjectName,
		String syllabusVersion) {

	/**
	 * Validates an explicit workbook assignment.
	 */
	public CurriculumWorkbookMigrationAssignment {
		if (legacyRelativePath == null || legacyRelativePath.isBlank()) {
			throw new IllegalArgumentException("legacyRelativePath must not be blank");
		}
		if (subjectName == null || subjectName.isBlank()) {
			throw new IllegalArgumentException("subjectName must not be blank");
		}
		if (syllabusVersion == null || syllabusVersion.isBlank()) {
			throw new IllegalArgumentException("syllabusVersion must not be blank");
		}
		legacyRelativePath = legacyRelativePath.strip();
		subjectName = subjectName.strip();
		syllabusVersion = syllabusVersion.strip();
	}
}
