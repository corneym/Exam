package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class AnswerCaptureValidatorTest {

	@Test
	void requiresAQuestionBeforeOtherAnswerContent() {
		AnswerCaptureValidator.State state = new AnswerCaptureValidator.State(false, true, "", 0);

		assertEquals("Select a question.", AnswerCaptureValidator.findError(state));
	}

	@Test
	void requiresCurrentRegionToBeAcceptedBeforeSaving() {
		AnswerCaptureValidator.State state = new AnswerCaptureValidator.State(true, true, "B", 1);

		assertEquals("The current answer selection has not been added.", AnswerCaptureValidator.findError(state));
	}

	@Test
	void requiresTextOrAtLeastOneAcceptedRegion() {
		AnswerCaptureValidator.State state = new AnswerCaptureValidator.State(true, false, "  ", 0);

		assertEquals("Enter answer text or add at least one answer region.", AnswerCaptureValidator.findError(state));
	}

	@Test
	void acceptsTextOnlyAndRegionOnlyAnswers() {
		assertNull(AnswerCaptureValidator.findError(new AnswerCaptureValidator.State(true, false, "B", 0)));
		assertNull(AnswerCaptureValidator.findError(new AnswerCaptureValidator.State(true, false, "", 1)));
	}
}
