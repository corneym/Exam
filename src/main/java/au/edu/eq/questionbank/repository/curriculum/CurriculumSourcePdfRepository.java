package au.edu.eq.questionbank.repository.curriculum;

import au.edu.eq.questionbank.model.SyllabusVersion;

/**
 * Persistence boundary for the authoritative managed syllabus PDF.
 */
public interface CurriculumSourcePdfRepository {

	/**
	 * Stores the relative path of the managed authoritative syllabus PDF.
	 *
	 * @param syllabusVersion syllabus being edited
	 * @param relativePath    path relative to the curriculum data root
	 * @return updated syllabus-version snapshot
	 */
	SyllabusVersion updateSourcePdfPath(SyllabusVersion syllabusVersion, String relativePath);
}
