package au.edu.eq.questionbank.service.audit;

/**
 * Reports optional MCQ explanation-region coverage for one booklet.
 *
 * @param explanationCapable       whether the booklet's assigned AnswerFile
 *                                 declares that it contains explanations
 * @param eligibleQuestionCount    answered MCQs eligible for explanation
 *                                 capture
 * @param capturedExplanationCount eligible MCQs with one or more explanation
 *                                 regions already captured
 */
public record McqExplanationCoverage(boolean explanationCapable, int eligibleQuestionCount,
		int capturedExplanationCount) {

	/**
	 * Validates one immutable explanation-coverage snapshot.
	 */
	public McqExplanationCoverage {
		if (eligibleQuestionCount < 0) {
			throw new IllegalArgumentException("eligibleQuestionCount must not be negative");
		}
		if (capturedExplanationCount < 0) {
			throw new IllegalArgumentException("capturedExplanationCount must not be negative");
		}
		if (capturedExplanationCount > eligibleQuestionCount) {
			throw new IllegalArgumentException("capturedExplanationCount cannot exceed eligibleQuestionCount");
		}
		if (!explanationCapable && (eligibleQuestionCount != 0 || capturedExplanationCount != 0)) {

			// A booklet without an explanation-capable AnswerFile cannot participate in
			// the retrofit explanation workflow.
			throw new IllegalArgumentException("Non-explanation-capable booklets cannot report explanation candidates");
		}
	}

	/**
	 * Returns eligible MCQs that do not yet have captured explanation regions.
	 *
	 * @return outstanding optional explanation count
	 */
	public int missingExplanationCount() {

		// Missing explanation material is coverage information only; it is not an
		// ordinary Answer-completeness problem.
		return eligibleQuestionCount - capturedExplanationCount;
	}
}
