package au.edu.eq.questionbank.repository.curriculum;

import au.edu.eq.questionbank.service.curriculum.CurriculumAuthoringSession;

/**
 * Persistence boundary for saving a resumable curriculum-authoring session.
 */
public interface CurriculumAuthoringWriter {

	void save(CurriculumAuthoringSession session);
}
