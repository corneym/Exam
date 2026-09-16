package au.edu.eq.questionbank.service.audit;

/**
 * A corpus-completeness problem that requires user attention.
 */
public enum QuestionCorpusProblem {
	/**
	 * The Question has no captured source region.
	 */
	MISSING_QUESTION_SOURCE,
	/**
	 * The Question's required Answer representation is incomplete.
	 */
	MISSING_ANSWER,
	/**
	 * Historical metadata indicates shared introductory material is required, but
	 * no shared context is linked.
	 */
	UNRESOLVED_SHARED_CONTEXT,
	/**
	 * The Question's response type has not yet been resolved.
	 */
	UNKNOWN_RESPONSE_TYPE
}
