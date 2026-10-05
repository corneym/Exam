package au.edu.eq.questionbank.ui.capture;

/**
 * Applies save-time validation to the transient question-capture state.
 */
final class QuestionCaptureValidator {

	private QuestionCaptureValidator() {
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
		if (state.acceptedContentCount() == 0) {
			return "Add at least one question region or pasted image.";
		}
		if (state.marks().isBlank()) {
			return "Enter marks.";
		}

		// Use the same guarded conversion that persistence later uses so validation
		// and save-time interpretation of the entered marks cannot diverge.
		if (parsePositiveMarks(state.marks()) == null) {
			return "Marks must be a positive whole number.";
		}
		return null;
	}

	static Integer parsePositiveMarks(String marks) {
		if (marks == null || marks.isBlank()) {
			return null;
		}
		try {

			// Convert user-entered marks at one guarded boundary. Callers receive null
			// rather than allowing malformed numeric input to escape as an exception.
			int parsedMarks = Integer.parseInt(marks.strip());
			return parsedMarks > 0 ? parsedMarks : null;
		} catch (NumberFormatException exception) {
			return null;
		}
	}

	/**
	 * Values required to decide whether the current Question can be saved.
	 *
	 * @param examSet                 whether an Exam booklet is active
	 * @param questionCode            entered Question code
	 * @param marks                   entered mark value
	 * @param subjectSelected         whether Subject is selected
	 * @param unitSelected            whether Unit is selected
	 * @param topicSelected           whether Topic is selected
	 * @param classificationSelected  whether the final classification is selected
	 * @param currentSelectionPending whether an unaccepted PDF rectangle exists
	 * @param acceptedContentCount    number of accepted Question body parts
	 */
	record State(boolean examSet, String questionCode, String marks, boolean subjectSelected, boolean unitSelected,
			boolean topicSelected, boolean classificationSelected, boolean currentSelectionPending,
			int acceptedContentCount) {
	}
}
