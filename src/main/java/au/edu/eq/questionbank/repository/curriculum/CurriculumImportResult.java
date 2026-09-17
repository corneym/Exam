package au.edu.eq.questionbank.repository.curriculum;

import au.edu.eq.questionbank.model.SyllabusVersion;

/**
 * Result of importing a syllabus and its curriculum hierarchy.
 *
 * @param syllabusVersion the newly stored or already matching syllabus version
 * @param imported        {@code true} when new rows were stored; {@code false}
 *                        when identical data was already present
 */
public record CurriculumImportResult(SyllabusVersion syllabusVersion, boolean imported) {

	/**
	 * Creates an import outcome for a non-null persisted syllabus.
	 *
	 * @param syllabusVersion the newly stored or already matching syllabus version
	 * @param imported        {@code true} when new rows were stored; {@code false}
	 *                        when identical data was already present
	 */
	public CurriculumImportResult {
		if (syllabusVersion == null) {
			throw new NullPointerException("syllabusVersion");
		}
	}
}
