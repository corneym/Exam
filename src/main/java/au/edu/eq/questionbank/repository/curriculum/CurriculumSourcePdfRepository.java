package au.edu.eq.questionbank.repository.curriculum;

import au.edu.eq.questionbank.model.SyllabusVersion;

/**
 * Persistence boundary for the authoritative managed syllabus PDF.
 */
public interface CurriculumSourcePdfRepository {

	/**
	 * Checks whether coordinator-owned syllabus PDF attachment is permitted.
	 *
	 * <p>
	 * The default is intended for repositories without a contributor assignment
	 * database, including test doubles. Persistent production repositories must
	 * override this method to enforce assignment mode before filesystem
	 * publication.
	 *
	 * @throws IllegalStateException if attachment is prohibited
	 */
	default void requireCoordinatorStructureEdit() {

		// Non-SQLite repositories do not maintain contributor selection.
	}

	/**
	 * Stores the relative path of the managed authoritative syllabus PDF.
	 *
	 * @param syllabusVersion syllabus being edited
	 * @param relativePath    path relative to the curriculum data root
	 * @return updated syllabus-version snapshot
	 */
	SyllabusVersion updateSourcePdfPath(SyllabusVersion syllabusVersion, String relativePath);
}
