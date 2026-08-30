package au.edu.eq.questionbank.ui;

/**
 * Applies save-time validation to the transient answer-capture state.
 */
final class AnswerCaptureValidator {

	/**
	 * Values required to decide whether the current answer can be saved.
	 */
	record State(boolean questionSelected, boolean currentSelectionPending, String answerText,
			int acceptedRegionCount) {
	}

	private AnswerCaptureValidator() {
	}

	/**
	 * Returns the first workflow error, or {@code null} when the state is valid.
	 *
	 * @param state the current capture state
	 * @return a user-facing validation message, or {@code null}
	 */
	static String findError(State state) {
		if (!state.questionSelected()) {
			return "Select a question.";
		}
		if (state.currentSelectionPending()) {
			return "The current answer selection has not been added.";
		}
		if (state.answerText().isBlank() && state.acceptedRegionCount() == 0) {
			return "Enter answer text or add at least one answer region.";
		}
		return null;
	}
}
