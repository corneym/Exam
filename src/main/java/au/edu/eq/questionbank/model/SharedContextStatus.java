package au.edu.eq.questionbank.model;

/**
 * Records whether a multipart source question has shared context content. This
 * source-level state is distinct from a legacy question's indication that
 * context capture is still required.
 */
public enum SharedContextStatus {
	/**
	 * The shared context state has not yet been explicitly resolved, including
	 * records migrated from before the state was stored.
	 */
	UNKNOWN,
	/**
	 * The source question explicitly has no shared context.
	 */
	NONE,
	/**
	 * The source question has shared context content.
	 */
	PRESENT
}
