package au.edu.eq.questionbank.ui.search;

/**
 * Defines whether Search Questions is evaluating current-curriculum
 * applicability or displaying the complete stored Question bank.
 */
enum QuestionSearchScope {

	CURRENT_SYLLABUS("Current syllabus"), ALL_QUESTIONS("All Questions");

	private final String displayText;

	QuestionSearchScope(String displayText) {

		// Keep the user-facing wording with the scope rather than duplicating labels
		// in the pane when the scope selector is added.
		this.displayText = displayText;
	}

	String displayText() {
		return displayText;
	}
}
