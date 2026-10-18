package au.edu.eq.questionbank.ui.capture;

import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.Question;
import javafx.util.StringConverter;

/**
 * Displays imported/incomplete Questions in the capture-work queue.
 */
final class ImportedQuestionStringConverter extends StringConverter<Question> {

	@Override
	public Question fromString(String text) {

		// The ComboBox is selection-only, so displayed text is never parsed back
		// into a Question.
		return null;
	}

	@Override
	public String toString(Question question) {
		if (question == null) {
			return "";
		}
		ExamBooklet booklet = question.getBooklet();
		Exam exam = booklet.getExam();

		// Include the assessment name because External, Mock and Sample
		// can share Provider, Year, booklet labels and Question codes.
		return String.format("%s %d — %s — %s — %s — %d mark(s)", exam.getProvider().getName(), exam.getYear(),
				exam.getName(), booklet.getName(), question.getQuestionCode(), question.getMarks());
	}
}
