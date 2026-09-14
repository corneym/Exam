package au.edu.eq.questionbank.repository.curriculum;

import java.util.List;

import au.edu.eq.questionbank.model.SyllabusVersion;

/**
 * Persistence reads required specifically for resumable curriculum authoring.
 */
public interface CurriculumAuthoringRepository {

	/**
	 * Returns every persisted curriculum node belonging to a syllabus version.
	 *
	 * @param syllabusVersion syllabus being opened for editing
	 * @return persisted curriculum nodes
	 */
	List<PersistedCurriculumNode> findNodesForVersion(SyllabusVersion syllabusVersion);
}
