package au.edu.eq.questionbank.model;

/**
 * Records whether a multipart source question has shared preamble content. This
 * source-level state is distinct from a legacy question's indication that
 * preamble capture is still required.
 */
public enum PreambleStatus {
	/**
	 * The preamble state has not yet been explicitly resolved, including records
	 * migrated from before the state was stored.
	 */
	UNKNOWN,
	/**
	 * The source question explicitly has no shared preamble.
	 */
	NONE,
	/**
	 * The source question has shared preamble content.
	 */
	PRESENT
}
