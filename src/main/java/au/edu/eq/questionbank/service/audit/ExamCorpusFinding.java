package au.edu.eq.questionbank.service.audit;

/**
 * Calculated Exam-level audit findings that require investigation.
 * <p>
 * Findings do not alter the user-declared Exam capture state.
 */
public enum ExamCorpusFinding {

	/**
	 * The number of persisted Question booklets differs from the user's expected
	 * Question-booklet count.
	 */
	EXPECTED_QUESTION_BOOKLET_COUNT_MISMATCH,

	/**
	 * The number of persisted Answer/marking files differs from the user's expected
	 * Answer-file count.
	 */
	EXPECTED_ANSWER_FILE_COUNT_MISMATCH;

	// Exam findings remain advisory so ACTIVE/COMPLETE always retains its explicit
	// user-declared meaning.
}
