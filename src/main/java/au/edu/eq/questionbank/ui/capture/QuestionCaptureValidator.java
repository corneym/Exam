package au.edu.eq.questionbank.ui.capture;

/**
 * Applies save-time validation to the transient question-capture state.
 */
final class QuestionCaptureValidator {

	/**
	 * Values required to decide whether the current question can be saved.
	 */
	record State(boolean examSet, String questionCode, String marks, boolean subjectSelected, boolean unitSelected,
			boolean topicSelected, boolean classificationSelected, boolean currentSelectionPending,
			int acceptedRegionCount) {
	}

	/**
	 * Returns the first workflow error, or {@code null} when the state is valid.
	 *
	 * @param state the current capture state
	 * @return a user-facing validation message, or {@code null}
	 */
	static String findError(State state) {
		if (!state.examSet()) {
			return "Set the exam details first.";
		}
		if (state.questionCode().isBlank()) {
			return "Enter a question code.";
		}
		if (!state.subjectSelected()) {
			return "Select a subject.";
		}
		if (!state.unitSelected()) {
			return "Select a unit.";
		}
		if (!state.topicSelected()) {
			return "Select a topic.";
		}
		if (!state.classificationSelected()) {
			return "Select a subtopic or descriptor.";
		}
		if (state.currentSelectionPending()) {
			return "The current selection has not been added to Accepted regions.";
		}
		if (state.acceptedRegionCount() == 0) {
			return "Add at least one question region.";
		}
		if (state.marks().isBlank()) {
			return "Enter marks.";
		}
		try {
			if (Integer.parseInt(state.marks()) < 1) {
				return "Marks must be a positive whole number.";
			}
		} catch (NumberFormatException e) {
			return "Marks must be a positive whole number.";
		}
		return null;
	}

	private QuestionCaptureValidator() {
	}
}
