package au.edu.eq.questionbank.model;

/**
 * Describes the kinds of Questions expected in an examination booklet.
 * <p>
 * New booklets are explicitly classified as multiple-choice, written-response
 * or mixed. {@link #UNSPECIFIED} preserves existing booklet data for which that
 * information was never recorded.
 */
public enum ExamBookletQuestionFormat {

	/**
	 * The booklet contains multiple-choice Questions.
	 */
	MULTIPLE_CHOICE,
	/**
	 * The booklet contains written-response Questions.
	 */
	WRITTEN_RESPONSE,
	/**
	 * The booklet contains both multiple-choice and written-response Questions.
	 */
	MIXED,
	/**
	 * The booklet predates explicit question-format metadata.
	 */
	UNSPECIFIED;

	@Override
	public String toString() {

		// Present readable labels in UI controls while persistence continues to use
		// name(), so the stored database values remain unchanged.
		return switch (this) {
		case MULTIPLE_CHOICE -> "Multiple Choice";
		case WRITTEN_RESPONSE -> "Written Response";
		case MIXED -> "Both";
		case UNSPECIFIED -> "Not recorded";
		};
	}
}
