package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import au.edu.eq.questionbank.model.QuestionResponseType;

class AnswerCaptureValidatorTest {

	@Test
	void multipleChoiceDoesNotRequireAnswerRegion() {
		assertNull(AnswerCaptureValidator.findError(
				new AnswerCaptureValidator.State(true, QuestionResponseType.MULTIPLE_CHOICE, false, "A", 0)));
		assertNull(AnswerCaptureValidator.findError(
				new AnswerCaptureValidator.State(true, QuestionResponseType.MULTIPLE_CHOICE, false, "d", 0)));
	}

	@Test
	void multipleChoiceRequiresOneOfABCD() {
		assertEquals("Select one multiple-choice answer: A, B, C or D.", AnswerCaptureValidator
				.findError(new AnswerCaptureValidator.State(true, QuestionResponseType.MULTIPLE_CHOICE, false, "", 0)));
		assertEquals("Select one multiple-choice answer: A, B, C or D.", AnswerCaptureValidator.findError(
				new AnswerCaptureValidator.State(true, QuestionResponseType.MULTIPLE_CHOICE, false, "explanation", 0)));
	}

	@Test
	void requiresAQuestionBeforeOtherAnswerContent() {
		AnswerCaptureValidator.State state = new AnswerCaptureValidator.State(false, null, true, "", 0);
		assertEquals("Select a question.", AnswerCaptureValidator.findError(state));
	}

	@Test
	void requiresCurrentRegionToBeAcceptedBeforeSavingWrittenResponse() {
		AnswerCaptureValidator.State state = new AnswerCaptureValidator.State(true,
				QuestionResponseType.WRITTEN_RESPONSE, true, "", 1);
		assertEquals("The current answer selection has not been added.", AnswerCaptureValidator.findError(state));
	}

	@Test
	void unknownResponseTypeMustBeResolvedFirst() {
		AnswerCaptureValidator.State state = new AnswerCaptureValidator.State(true, QuestionResponseType.UNKNOWN, false,
				"", 0);
		assertEquals("Resolve the question response type before capturing an answer.",
				AnswerCaptureValidator.findError(state));
	}

	@Test
	void writtenResponseAcceptsRegionWithoutText() {
		assertNull(AnswerCaptureValidator.findError(
				new AnswerCaptureValidator.State(true, QuestionResponseType.WRITTEN_RESPONSE, false, "", 1)));
	}

	@Test
	void writtenResponseRequiresAtLeastOneAnswerRegion() {
		assertEquals("Add at least one answer region for a written-response question.",
				AnswerCaptureValidator.findError(
						new AnswerCaptureValidator.State(true, QuestionResponseType.WRITTEN_RESPONSE, false, "", 0)));
		assertEquals("Add at least one answer region for a written-response question.",
				AnswerCaptureValidator.findError(new AnswerCaptureValidator.State(true,
						QuestionResponseType.WRITTEN_RESPONSE, false, "text alone does not count", 0)));
	}
}
