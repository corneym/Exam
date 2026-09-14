package au.edu.eq.questionbank.model;

/**
 * Authoring lifecycle state of a persisted syllabus curriculum.
 */
public enum CurriculumStatus {
	/**
	 * The curriculum may still be edited or completed.
	 */
	IN_PROGRESS,
	/**
	 * The curriculum has been checked and accepted as complete.
	 */
	FINAL
}
