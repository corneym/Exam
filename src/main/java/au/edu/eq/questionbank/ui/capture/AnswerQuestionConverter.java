package au.edu.eq.questionbank.ui.capture;

import au.edu.eq.questionbank.model.Question;
import javafx.util.StringConverter;

/**
 * Formats Questions for Answer-capture selectors.
 */
final class AnswerQuestionConverter extends StringConverter<Question> {

	@Override
	public Question fromString(String string) {

		// Answer-capture selectors are selection-only; display text is never parsed
		// back into a Question.
		return null;
	}

	@Override
	public String toString(Question question) {
		if (question == null) {
			return "";
		}

		// Include enough persisted source identity to distinguish Questions belonging
		// to different Exams or booklets while retaining their Answer state.
		String answerState = question.hasAnswer() ? " — answered" : "";
		return String.format("%s %d — %s — %s — %s%s", question.getExam().getProvider().getName(),
				question.getExam().getYear(), question.getBooklet().getName(), question.getQuestionCode(),
				marksLabel(question.getMarks()), answerState);
	}

	private String marksLabel(int marks) {

		// Keep the selector wording grammatically correct without coupling this
		// formatter to AnswerCapturePane presentation helpers.
		return marks == 1 ? "1 mark" : marks + " marks";
	}
}
