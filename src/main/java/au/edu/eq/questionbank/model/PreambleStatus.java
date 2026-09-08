package au.edu.eq.questionbank.model;

/**
 * Records whether a multipart source question has shared preamble content.
 */
public enum PreambleStatus {
	/**
	 * The preamble state has not yet been explicitly resolved.
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
