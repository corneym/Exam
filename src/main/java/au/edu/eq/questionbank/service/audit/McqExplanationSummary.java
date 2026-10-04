package au.edu.eq.questionbank.service.audit;

/**
 * Aggregate MCQ explanation coverage across one Exam.
 *
 * @param explanationCapableBookletCount booklets whose assigned AnswerFile
 *                                       declares explanation material
 * @param eligibleQuestionCount          answered MCQs eligible for explanation
 *                                       capture
 * @param capturedExplanationCount       eligible MCQs with captured explanation
 *                                       regions
 */
public record McqExplanationSummary(int explanationCapableBookletCount, int eligibleQuestionCount,
		int capturedExplanationCount) {

	/**
	 * Validates aggregate MCQ explanation coverage.
	 */
	public McqExplanationSummary {
		if (explanationCapableBookletCount < 0) {
			throw new IllegalArgumentException("explanationCapableBookletCount must not be negative");
		}
		if (eligibleQuestionCount < 0) {
			throw new IllegalArgumentException("eligibleQuestionCount must not be negative");
		}
		if (capturedExplanationCount < 0) {
			throw new IllegalArgumentException("capturedExplanationCount must not be negative");
		}
		if (capturedExplanationCount > eligibleQuestionCount) {
			throw new IllegalArgumentException("capturedExplanationCount cannot exceed eligibleQuestionCount");
		}
	}

	/**
	 * Returns eligible MCQs that have no captured explanation regions.
	 *
	 * @return outstanding optional explanation count
	 */
	public int missingExplanationCount() {

		// Ordinary A-D Answer completeness remains unchanged. This separate count
		// identifies outstanding explanation work required by explanation-capable
		// booklets.
		return eligibleQuestionCount - capturedExplanationCount;
	}
}
