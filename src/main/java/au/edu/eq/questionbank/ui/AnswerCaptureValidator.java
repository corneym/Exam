package au.edu.eq.questionbank.ui;

import au.edu.eq.questionbank.model.QuestionResponseType;

/**
 * Applies save-time validation to the transient answer-capture state.
 */
final class AnswerCaptureValidator {

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
		if (state.responseType() == null || state.responseType() == QuestionResponseType.UNKNOWN) {
			return "Resolve the question response type before capturing an answer.";
		}
		if (state.currentSelectionPending()) {
			return "The current answer selection has not been added.";
		}
		return switch (state.responseType()) {
		case MULTIPLE_CHOICE -> validateMultipleChoiceAnswer(state.answerText());
		case WRITTEN_RESPONSE -> validateWrittenResponse(state.acceptedRegionCount());
		case UNKNOWN -> "Resolve the question response type before capturing an answer.";
		};
	}

	private static String validateMultipleChoiceAnswer(String answerText) {
		if (answerText == null) {
			return "Select one multiple-choice answer: A, B, C or D.";
		}
		String answer = answerText.trim();
		if (!answer.equalsIgnoreCase("A") && !answer.equalsIgnoreCase("B") && !answer.equalsIgnoreCase("C")
				&& !answer.equalsIgnoreCase("D")) {
			return "Select one multiple-choice answer: A, B, C or D.";
		}
		return null;
	}

	private static String validateWrittenResponse(int acceptedRegionCount) {
		if (acceptedRegionCount < 1) {
			return "Add at least one answer region for a written-response question.";
		}
		return null;
	}

	/**
	 * Values required to decide whether the current answer can be saved.
	 */
	record State(boolean questionSelected, QuestionResponseType responseType, boolean currentSelectionPending,
			String answerText, int acceptedRegionCount) {
	}
}
