package au.edu.eq.questionbank.pdf;

import java.util.ArrayList;
import java.util.List;

import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.SourceQuestion;
import au.edu.eq.questionbank.model.SourceQuestionCodeParser;

/**
 * Assigns sequential examination numbering to selected Questions without
 * modifying their original identities.
 */
public final class PrintableQuestionNumbering {

	private PrintableQuestionNumbering() {
	}

	/**
	 * Assigns Question numbers to one ordered booklet, starting at one.
	 *
	 * @param questions selected Questions in presentation order
	 * @return immutable list of numbered Questions
	 */
	public static List<NumberedQuestion> number(List<Question> questions) {
		return number(questions, 1);
	}

	/**
	 * Assigns Question numbers to an ordered selection, starting at the supplied
	 * number.
	 *
	 * <p>
	 * Adjacent selected parts belonging to the same source Question share a number.
	 * A single selected part becomes a standalone Question.
	 *
	 * @param questions   selected Questions in presentation order
	 * @param firstNumber first generated Question number
	 * @return immutable list of numbered Questions
	 * @throws IllegalArgumentException if firstNumber is not positive
	 */
	public static List<NumberedQuestion> number(List<Question> questions, int firstNumber) {
		if (firstNumber < 1) {
			throw new IllegalArgumentException("First Question number must be positive.");
		}
		List<NumberedQuestion> numbered = new ArrayList<>();
		int questionNumber = firstNumber;
		int start = 0;
		while (start < questions.size()) {
			Question first = questions.get(start);
			int end = start + 1;

			// Consecutive selected parts from the same source
			// Question share one generated Question number.
			while (end < questions.size() && sameMultipartSource(first, questions.get(end))) {
				end++;
			}
			boolean multipart = end - start > 1;
			for (int index = start; index < end; index++) {
				Question question = questions.get(index);
				String replacementLabel = multipart ? "(" + (char) ('a' + index - start) + ")" : "";
				numbered.add(
						new NumberedQuestion(question, questionNumber, originalPartLabel(question), replacementLabel));
			}
			questionNumber++;
			start = end;
		}
		return List.copyOf(numbered);
	}

	private static String originalPartLabel(Question question) {
		String code = question.getQuestionCode().trim();
		if (SourceQuestionCodeParser.derive(code) == null) {
			return "";
		}
		return "(" + code.charAt(code.length() - 1) + ")";
	}

	private static boolean sameMultipartSource(Question first, Question second) {
		SourceQuestion firstSource = first.getSourceQuestion();
		SourceQuestion secondSource = second.getSourceQuestion();
		return firstSource != null && secondSource != null && firstSource.getId() == secondSource.getId()
				&& !originalPartLabel(first).isEmpty() && !originalPartLabel(second).isEmpty();
	}

	/**
	 * A selected Question and its generated examination identifier.
	 *
	 * @param question             original Question
	 * @param number               generated top-level Question number
	 * @param originalPartLabel    original printed part identifier, or an empty
	 *                             string
	 * @param replacementPartLabel generated part identifier, or an empty string
	 */
	public record NumberedQuestion(Question question, int number, String originalPartLabel,
			String replacementPartLabel) {

		/**
		 * Returns the complete generated Question identifier.
		 *
		 * @return identifier such as {@code 1} or {@code 2(a)}
		 */
		public String label() {
			return number + replacementPartLabel;
		}
	}
}
