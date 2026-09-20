package au.edu.eq.questionbank.repository.curriculum;

import au.edu.eq.questionbank.service.curriculum.CurriculumAuthoringSession;

/**
 * Persistence boundary for saving a resumable curriculum-authoring session.
 */
public interface CurriculumAuthoringWriter {

	/**
	 * Persists the complete draft of an editable authoring session in one
	 * transaction. Existing nodes retain their persistent IDs; new nodes are
	 * inserted and removed nodes are deleted only when unreferenced by other
	 * application data. Persistence bindings are updated only after commit. The
	 * persisted node state must still match the session's load or last-save
	 * snapshot; a stale session is rejected before mutation and must be reloaded.
	 * This operation does not finalise the curriculum or attach a source PDF.
	 *
	 * @param session session containing the complete draft to save
	 * @throws NullPointerException     if {@code session} is {@code null}
	 * @throws IllegalArgumentException if the draft is structurally invalid
	 * @throws IllegalStateException    if the syllabus is not editable, a deletion
	 *                                  is prohibited, or persistence fails
	 */
	void save(CurriculumAuthoringSession session);
}
