package au.edu.eq.questionbank.model;

/**
 * User-declared structural capture state of an {@link Exam}.
 */
public enum ExamCaptureState {

	/**
	 * The Exam structure may still be created or corrected.
	 */
	ACTIVE,

	/**
	 * The user has declared the Exam structure complete.
	 * <p>
	 * Structural changes require explicit reactivation. Question-bank content that
	 * does not alter Exam structure may still be corrected separately.
	 */
	COMPLETE
}
