package au.edu.eq.questionbank.repository.assessment;

import au.edu.eq.questionbank.model.Question;

/**
 * Result of correcting legacy question metadata.
 *
 * @param question        reloaded persisted question
 * @param preambleOutcome effect, if any, of correcting the legacy preamble flag
 */
public record LegacyQuestionMetadataUpdateResult(Question question, PreambleOutcome preambleOutcome) {

	/**
	 * Creates a correction result containing the reloaded question and capture outcome.
	 *
	 * @param question        reloaded persisted question
	 * @param preambleOutcome effect, if any, of correcting the legacy preamble flag
	 */
	public LegacyQuestionMetadataUpdateResult {
		if (question == null) {
			throw new NullPointerException("question");
		}
		if (preambleOutcome == null) {
			throw new NullPointerException("preambleOutcome");
		}
	}

	/**
	 * Effect of a legacy preamble correction on captured source regions.
	 */
	public enum PreambleOutcome {
		/** Captured regions were retained without conversion. */
		NO_CAPTURE_CHANGE,
		/** Shared-context regions were copied into the question's ordinary regions. */
		CONVERTED_SHARED_CONTEXT_TO_QUESTION_REGIONS
	}
}
