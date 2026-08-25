package au.edu.eq.questionbank.ui;

final class AnswerCaptureValidator {

	record State(boolean questionSelected, boolean currentSelectionPending, String answerText,
			int acceptedRegionCount) {
	}

	private AnswerCaptureValidator() {
	}

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
