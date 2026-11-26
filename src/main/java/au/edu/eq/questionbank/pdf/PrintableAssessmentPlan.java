package au.edu.eq.questionbank.pdf;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.service.audit.QuestionCorpusAudit;

/**
 * Holds the ordered Question selections and generated numbering for a mock
 * examination.
 *
 * <p>
 * Paper 1 contains multiple-choice and short-response Questions using
 * continuous numbering. Paper 2 contains short-response Questions with
 * independent numbering.
 *
 * <p>
 * The numbered selections are immutable and are shared by student booklet and
 * marking guide generation.
 */
public final class PrintableAssessmentPlan {

	private final List<PrintableQuestionNumbering.NumberedQuestion> multipleChoice;
	private final List<PrintableQuestionNumbering.NumberedQuestion> paperOne;
	private final List<PrintableQuestionNumbering.NumberedQuestion> paperTwo;

	/**
	 * Creates and numbers the three examination selections.
	 *
	 * @param multipleChoice ordered Paper 1 multiple-choice Questions
	 * @param paperOne       ordered Paper 1 short-response Questions
	 * @param paperTwo       ordered Paper 2 short-response Questions
	 * @throws IllegalArgumentException if no Questions are selected, a Question is
	 *                                  duplicated, a response type is incorrect, or
	 *                                  an Answer is incomplete
	 * @throws NullPointerException     if a selection or its Question is null
	 */
	public PrintableAssessmentPlan(List<Question> multipleChoice, List<Question> paperOne, List<Question> paperTwo) {
		List<Question> mcqSelection = List.copyOf(multipleChoice);
		List<Question> paperOneSelection = List.copyOf(paperOne);
		List<Question> paperTwoSelection = List.copyOf(paperTwo);
		if (mcqSelection.isEmpty() && paperOneSelection.isEmpty() && paperTwoSelection.isEmpty()) {
			throw new IllegalArgumentException("Select at least one Question.");
		}
		Set<Long> selectedIds = new HashSet<>();
		validateSelection(mcqSelection, QuestionResponseType.MULTIPLE_CHOICE, selectedIds);
		validateSelection(paperOneSelection, QuestionResponseType.WRITTEN_RESPONSE, selectedIds);
		validateSelection(paperTwoSelection, QuestionResponseType.WRITTEN_RESPONSE, selectedIds);
		this.multipleChoice = mcqSelection.stream()
				.map(question -> new PrintableQuestionNumbering.NumberedQuestion(question,
						mcqSelection.indexOf(question) + 1, "", ""))
				.toList();

		// Paper 1 short-response numbering continues after
		// the final multiple-choice Question.
		this.paperOne = PrintableQuestionNumbering.number(paperOneSelection, mcqSelection.size() + 1);

		// Paper 2 is numbered independently.
		this.paperTwo = PrintableQuestionNumbering.number(paperTwoSelection, 1);
	}

	private static int countQuestions(List<PrintableQuestionNumbering.NumberedQuestion> questions) {

		// Multiple selected parts of one generated Question
		// count as one top-level Question on the cover.
		return (int) questions.stream().mapToInt(PrintableQuestionNumbering.NumberedQuestion::number).distinct()
				.count();
	}

	private static int totalMarks(List<PrintableQuestionNumbering.NumberedQuestion> questions) {
		return questions.stream().mapToInt(item -> item.question().getMarks()).sum();
	}

	private static void validateSelection(List<Question> questions, QuestionResponseType expectedType,
			Set<Long> selectedIds) {
		for (Question question : questions) {
			if (question.getResponseType() != expectedType) {
				throw new IllegalArgumentException(
						"Incorrect response type for Question " + question.getQuestionCode());
			}

			// Reuse the existing authoritative Answer
			// completeness rules.
			if (!QuestionCorpusAudit.assess(question).answerComplete()) {
				throw new IllegalArgumentException("Question has no complete Answer: " + question.getQuestionCode());
			}
			if (!selectedIds.add(question.getId())) {
				throw new IllegalArgumentException("Question is already selected: " + question.getQuestionCode());
			}
		}
	}

	/**
	 * Returns the numbered multiple-choice selection.
	 *
	 * @return immutable ordered multiple-choice Questions
	 */
	public List<PrintableQuestionNumbering.NumberedQuestion> getMultipleChoice() {
		return multipleChoice;
	}

	/**
	 * Returns the number of selected multiple-choice Questions.
	 *
	 * @return multiple-choice Question count
	 */
	public int getMultipleChoiceCount() {
		return multipleChoice.size();
	}

	/**
	 * Returns the marks allocated to multiple-choice Questions.
	 *
	 * @return total multiple-choice marks
	 */
	public int getMultipleChoiceMarks() {
		return totalMarks(multipleChoice);
	}

	/**
	 * Returns the numbered Paper 1 short-response selection.
	 *
	 * @return immutable ordered Paper 1 short-response Questions
	 */
	public List<PrintableQuestionNumbering.NumberedQuestion> getPaperOne() {
		return paperOne;
	}

	/**
	 * Returns the number of top-level Paper 1 short-response Questions.
	 *
	 * @return Paper 1 short-response Question count
	 */
	public int getPaperOneQuestionCount() {
		return countQuestions(paperOne);
	}

	/**
	 * Returns the marks allocated to Paper 1 short-response Questions.
	 *
	 * @return Paper 1 short-response marks
	 */
	public int getPaperOneShortResponseMarks() {
		return totalMarks(paperOne);
	}

	/**
	 * Returns the total marks allocated to Paper 1.
	 *
	 * @return combined multiple-choice and short-response marks
	 */
	public int getPaperOneTotalMarks() {
		return getMultipleChoiceMarks() + getPaperOneShortResponseMarks();
	}

	/**
	 * Returns the numbered Paper 2 short-response selection.
	 *
	 * @return immutable ordered Paper 2 Questions
	 */
	public List<PrintableQuestionNumbering.NumberedQuestion> getPaperTwo() {
		return paperTwo;
	}

	/**
	 * Returns the number of top-level Paper 2 Questions.
	 *
	 * @return Paper 2 Question count
	 */
	public int getPaperTwoQuestionCount() {
		return countQuestions(paperTwo);
	}

	/**
	 * Returns the total marks allocated to Paper 2.
	 *
	 * @return Paper 2 total marks
	 */
	public int getPaperTwoTotalMarks() {
		return totalMarks(paperTwo);
	}

	/**
	 * Returns the total marks allocated to the mock examination.
	 *
	 * @return combined Paper 1 and Paper 2 marks
	 */
	public int getTotalMarks() {
		return getPaperOneTotalMarks() + getPaperTwoTotalMarks();
	}
}
