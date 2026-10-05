package au.edu.eq.questionbank.service.audit;

/**
 * Calculated booklet-level audit findings that require user attention.
 * <p>
 * These findings are advisory. They do not change the user-declared lifecycle
 * state of the owning Exam.
 */
public enum BookletCorpusFinding {
	/**
	 * The Question source PDF recorded for the booklet is not currently available.
	 */
	MISSING_QUESTION_PDF,
	/**
	 * The user-declared expected top-level Question count differs from the
	 * encountered source/top-level Question count.
	 */
	EXPECTED_TOP_LEVEL_QUESTION_COUNT_MISMATCH;

	// Booklet findings describe calculated observations only; Exam lifecycle
	// remains
	// controlled explicitly by the user.
}
