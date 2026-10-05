package au.edu.eq.questionbank.service.audit;

/**
 * Reports MCQ explanation-region coverage for one booklet.
 * <p>
 * Explanation coverage is not ordinary A-D Answer completeness. It becomes
 * required Exam-completion work only when the booklet's assigned AnswerFile
 * explicitly declares that explanation material exists.
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

		// The count remains independent of ordinary Answer completeness, but every
		// missing item becomes required lifecycle work for an explanation-capable
		// booklet.
		return eligibleQuestionCount - capturedExplanationCount;
	}
}
