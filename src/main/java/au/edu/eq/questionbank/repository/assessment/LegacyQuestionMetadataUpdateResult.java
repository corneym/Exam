package au.edu.eq.questionbank.repository.assessment;

import au.edu.eq.questionbank.model.Question;

/**
 * Result of correcting legacy question metadata.
 *
 * @param question        reloaded persisted question
 * @param preambleOutcome effect, if any, of correcting the legacy preamble flag
 */
public record LegacyQuestionMetadataUpdateResult(Question question, PreambleOutcome preambleOutcome) {

	public LegacyQuestionMetadataUpdateResult {
		if (question == null) {
			throw new NullPointerException("question");
		}
		if (preambleOutcome == null) {
			throw new NullPointerException("preambleOutcome");
		}
	}

	public enum PreambleOutcome {
		NO_CAPTURE_CHANGE, CONVERTED_SHARED_CONTEXT_TO_QUESTION_REGIONS
	}
}
