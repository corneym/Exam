package au.edu.eq.questionbank.model;

/**
 * Defines the required Answer representation for a Question.
 */
public enum QuestionResponseType {
	/**
	 * The required Answer is one multiple-choice option.
	 */
	MULTIPLE_CHOICE,
	/**
	 * The required Answer is captured from marking material as one or more regions.
	 */
	WRITTEN_RESPONSE,
	/**
	 * The response type has not yet been deliberately established.
	 */
	UNKNOWN
}
