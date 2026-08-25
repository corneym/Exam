package au.edu.eq.questionbank.ui;

final class QuestionCaptureValidator {

	record State(boolean examSet, String questionCode, boolean subjectSelected, boolean unitSelected,
			boolean topicSelected, boolean subtopicSelected, boolean currentSelectionPending, int acceptedRegionCount) {
	}

	private QuestionCaptureValidator() {
	}

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
		if (!state.subtopicSelected()) {
			return "Select a subtopic.";
		}
		if (state.currentSelectionPending()) {
			return "The current selection has not been added to Accepted regions.";
		}
		if (state.acceptedRegionCount() == 0) {
			return "Add at least one question region.";
		}
		return null;
	}
}
