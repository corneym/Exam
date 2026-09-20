package au.edu.eq.questionbank.ui.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class QuestionCaptureValidatorTest {

	private QuestionCaptureValidator.State state(boolean examSet, String questionCode, boolean subjectSelected,
			boolean unitSelected, boolean topicSelected, boolean classificationSelected,
			boolean currentSelectionPending, int acceptedRegionCount) {
		return new QuestionCaptureValidator.State(examSet, questionCode, "1", subjectSelected, unitSelected,
				topicSelected, classificationSelected, currentSelectionPending, acceptedRegionCount);
	}

	@Test
	void acceptsCompleteQuestionCaptureState() {
		QuestionCaptureValidator.State state = state(true, "Q1", true, true, true, true, false, 1);
		assertNull(QuestionCaptureValidator.findError(state));
	}

	@Test
	void reportsMissingQuestionInputsInWorkflowOrder() {
		assertEquals("Set the exam details first.",
				QuestionCaptureValidator.findError(state(false, "", false, false, false, false, false, 0)));
		assertEquals("Enter a question code.",
				QuestionCaptureValidator.findError(state(true, " ", true, true, true, true, false, 1)));
		assertEquals("Select a subject.",
				QuestionCaptureValidator.findError(state(true, "Q1", false, false, false, false, false, 0)));
		assertEquals("Select a unit.",
				QuestionCaptureValidator.findError(state(true, "Q1", true, false, false, false, false, 0)));
		assertEquals("Select a topic.",
				QuestionCaptureValidator.findError(state(true, "Q1", true, true, false, false, false, 0)));
		assertEquals("Select a subtopic or descriptor.",
				QuestionCaptureValidator.findError(state(true, "Q1", true, true, true, false, false, 0)));
	}

	@Test
	void requiresAtLeastOneAcceptedRegion() {
		QuestionCaptureValidator.State state = state(true, "Q1", true, true, true, true, false, 0);
		assertEquals("Add at least one question region.", QuestionCaptureValidator.findError(state));
	}

	@Test
	void requiresPendingSelectionToBeAccepted() {
		QuestionCaptureValidator.State state = state(true, "Q1", true, true, true, true, true, 1);
		assertEquals("The current selection has not been added to Accepted regions.",
				QuestionCaptureValidator.findError(state));
	}

	@Test
	void requiresValidMarks() {
		assertEquals("Enter marks.", QuestionCaptureValidator
				.findError(new QuestionCaptureValidator.State(true, "Q1", "", true, true, true, true, false, 1)));
		assertEquals("Marks must be a positive whole number.", QuestionCaptureValidator
				.findError(new QuestionCaptureValidator.State(true, "Q1", "0", true, true, true, true, false, 1)));
		assertEquals("Marks must be a positive whole number.", QuestionCaptureValidator
				.findError(new QuestionCaptureValidator.State(true, "Q1", "abc", true, true, true, true, false, 1)));
	}
}
