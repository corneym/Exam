package au.edu.eq.questionbank.repository.assessment;

import au.edu.eq.questionbank.model.Question;

/**
 * Result of correcting legacy question metadata.
 *
 * @param question             reloaded persisted question
 * @param sharedContextOutcome effect, if any, of correcting the legacy shared
 *                             context flag
 */
public record LegacyQuestionMetadataUpdateResult(Question question, SharedContextOutcome sharedContextOutcome) {

	/**
	 * Creates a correction result containing the reloaded question and capture
	 * outcome.
	 *
	 * @param question             reloaded persisted question
	 * @param sharedContextOutcome effect, if any, of correcting the legacy shared
	 *                             context flag
	 */
	public LegacyQuestionMetadataUpdateResult {
		if (question == null) {
			throw new NullPointerException("question");
		}
		if (sharedContextOutcome == null) {
			throw new NullPointerException("sharedContextOutcome");
		}
	}

	/**
	 * Effect of a legacy shared context correction on captured source regions.
	 */
	public enum SharedContextOutcome {
		/** Captured regions were retained without conversion. */
		NO_CAPTURE_CHANGE,
		/** Shared-context regions were copied into the question's ordinary regions. */
		CONVERTED_SHARED_CONTEXT_TO_QUESTION_REGIONS
	}
}
