package au.edu.eq.questionbank.model;

/**
 * Exam-level planning counts for expected and currently available source
 * assets.
 * <p>
 * Expected counts are user-declared planning metadata. Available counts are
 * derived from authoritative persisted ExamBooklet and AnswerFile rows.
 *
 * @param expectedQuestionBookletCount  expected Question booklets, or
 *                                      {@code null} when not yet established
 * @param availableQuestionBookletCount currently persisted Question booklets
 * @param expectedAnswerFileCount       expected Answer/marking files, or
 *                                      {@code null} when not yet established
 * @param availableAnswerFileCount      currently persisted Answer/marking files
 */
public record ExamAssetExpectations(Integer expectedQuestionBookletCount, int availableQuestionBookletCount,
		Integer expectedAnswerFileCount, int availableAnswerFileCount) {

	/**
	 * Validates Exam asset-planning values.
	 */
	public ExamAssetExpectations {
		if (expectedQuestionBookletCount != null && expectedQuestionBookletCount < 1) {
			throw new IllegalArgumentException("expectedQuestionBookletCount must be positive when supplied");
		}
		if (availableQuestionBookletCount < 0) {
			throw new IllegalArgumentException("availableQuestionBookletCount must not be negative");
		}
		if (expectedAnswerFileCount != null && expectedAnswerFileCount < 0) {
			throw new IllegalArgumentException("expectedAnswerFileCount must not be negative when supplied");
		}
		if (availableAnswerFileCount < 0) {
			throw new IllegalArgumentException("availableAnswerFileCount must not be negative");
		}
	}
}
