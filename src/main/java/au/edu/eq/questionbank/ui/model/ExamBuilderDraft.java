package au.edu.eq.questionbank.ui.model;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.pdf.PrintableAssessmentPlan;
import au.edu.eq.questionbank.pdf.PrintableAssessmentWriter;

/**
 * Maintains the editable Question selections for a mock examination belonging
 * to one authoritative Working Subject.
 *
 * <p>
 * Selections are independent of the original examination papers. Individual
 * multipart Questions can be selected, omitted and reordered.
 *
 * <p>
 * This draft does not persist changes to Question Bank records. A printable
 * assessment plan is constructed when required.
 */
public final class ExamBuilderDraft {

	private final Map<Section, List<Question>> selections = new EnumMap<>(Section.class);
	private final Subject subject;

	/**
	 * Creates an empty examination draft.
	 *
	 * @param subject authoritative Working Subject
	 * @throws NullPointerException if subject is null
	 */
	public ExamBuilderDraft(Subject subject) {
		this.subject = Objects.requireNonNull(subject, "subject");
		for (Section section : Section.values()) {
			selections.put(section, new ArrayList<>());
		}
	}

	/**
	 * Adds a Question to the end of the specified section.
	 *
	 * @param section  destination section
	 * @param question Question to select
	 * @return true if the Question was added
	 */
	public boolean add(Section section, Question question) {
		if (!canAdd(section, question)) {
			return false;
		}
		selections.get(section).add(question);
		return true;
	}

	/**
	 * Constructs the printable assessment plan from the current ordered selections.
	 *
	 * @return validated and numbered assessment plan
	 * @throws IllegalArgumentException if the draft is empty or a selected Question
	 *                                  is invalid
	 */
	public PrintableAssessmentPlan buildPlan() {
		return new PrintableAssessmentPlan(selections.get(Section.MULTIPLE_CHOICE),
				selections.get(Section.PAPER_ONE_WRITTEN), selections.get(Section.PAPER_TWO_WRITTEN));
	}

	/**
	 * Determines whether a Question may be added to a section.
	 *
	 * <p>
	 * The Question must belong to the authoritative Subject, have the appropriate
	 * response type and have a complete Answer. A Question may appear only once in
	 * the examination.
	 *
	 * @param section  proposed destination section
	 * @param question candidate Question
	 * @return true if the Question may be selected
	 */
	public boolean canAdd(Section section, Question question) {
		if (section == null || question == null) {
			return false;
		}
		if (!subject.equals(question.getExam().getSubject())) {
			return false;
		}
		if (question.getResponseType() != section.responseType()) {
			return false;
		}
		if (!PrintableAssessmentWriter.canSelect(question)) {
			return false;
		}
		return !contains(question.getId());
	}

	/**
	 * Removes all selected Questions from the draft.
	 */
	public void clear() {
		for (List<Question> questions : selections.values()) {
			questions.clear();
		}
	}

	/**
	 * Determines whether a persistent Question identity is already selected in any
	 * examination section.
	 *
	 * @param questionId persistent Question identifier
	 * @return true if the Question is already selected
	 */
	public boolean contains(long questionId) {
		return selections.values().stream().flatMap(List::stream).anyMatch(question -> question.getId() == questionId);
	}

	/**
	 * Returns an immutable snapshot of the selected Questions in presentation
	 * order.
	 *
	 * @param section requested examination section
	 * @return immutable ordered Question list
	 * @throws NullPointerException if section is null
	 */
	public List<Question> getQuestions(Section section) {
		Objects.requireNonNull(section, "section");
		return List.copyOf(selections.get(section));
	}

	/**
	 * Returns the authoritative Subject of this draft.
	 *
	 * @return Working Subject
	 */
	public Subject getSubject() {
		return subject;
	}

	/**
	 * Returns the marks allocated to the selected Questions in a section.
	 *
	 * @param section requested examination section
	 * @return total selected marks
	 * @throws NullPointerException if section is null
	 */
	public int getTotalMarks(Section section) {
		return getQuestions(section).stream().mapToInt(Question::getMarks).sum();
	}

	/**
	 * Returns the number of selected Question records across all examination
	 * sections.
	 *
	 * <p>
	 * Individual multipart Questions count separately here. Generated top-level
	 * Question counts are provided by the printable assessment plan.
	 *
	 * @return number of selected Question records
	 */
	public int getTotalSelectedCount() {
		return selections.values().stream().mapToInt(List::size).sum();
	}

	/**
	 * Determines whether the draft has no selected Questions.
	 *
	 * @return true if the draft is empty
	 */
	public boolean isEmpty() {
		return getTotalSelectedCount() == 0;
	}

	/**
	 * Moves a selected Question to another position within its current section.
	 *
	 * @param section          section containing the Question
	 * @param questionId       persistent Question identifier
	 * @param destinationIndex zero-based destination index
	 * @return true if the Question was moved
	 * @throws NullPointerException if section is null
	 */
	public boolean move(Section section, long questionId, int destinationIndex) {
		Objects.requireNonNull(section, "section");
		List<Question> questions = selections.get(section);
		int originalIndex = indexOf(questions, questionId);
		if (originalIndex < 0 || destinationIndex < 0 || destinationIndex >= questions.size()
				|| originalIndex == destinationIndex) {
			return false;
		}
		Question question = questions.remove(originalIndex);
		questions.add(destinationIndex, question);
		return true;
	}

	/**
	 * Removes a selected Question from a section.
	 *
	 * @param section    section containing the Question
	 * @param questionId persistent Question identifier
	 * @return true if the Question was removed
	 * @throws NullPointerException if section is null
	 */
	public boolean remove(Section section, long questionId) {
		Objects.requireNonNull(section, "section");
		List<Question> questions = selections.get(section);
		int index = indexOf(questions, questionId);
		if (index < 0) {
			return false;
		}
		questions.remove(index);
		return true;
	}

	/**
	 * Transfers a selected Question to the end of another compatible examination
	 * section.
	 *
	 * <p>
	 * The original selection remains unchanged if the transfer is not permitted.
	 *
	 * @param source      original examination section
	 * @param destination destination examination section
	 * @param questionId  persistent Question identifier
	 * @return true if the Question was transferred
	 * @throws NullPointerException if either section is null
	 */
	public boolean transfer(Section source, Section destination, long questionId) {
		Objects.requireNonNull(source, "source");
		Objects.requireNonNull(destination, "destination");
		if (source == destination) {
			return false;
		}
		List<Question> sourceQuestions = selections.get(source);
		int index = indexOf(sourceQuestions, questionId);
		if (index < 0) {
			return false;
		}
		Question question = sourceQuestions.get(index);
		if (question.getResponseType() != destination.responseType()
				|| !PrintableAssessmentWriter.canSelect(question)) {
			return false;
		}

		// Validate the destination before changing either list.
		sourceQuestions.remove(index);
		selections.get(destination).add(question);
		return true;
	}

	private int indexOf(List<Question> questions, long questionId) {
		for (int index = 0; index < questions.size(); index++) {
			if (questions.get(index).getId() == questionId) {
				return index;
			}
		}
		return -1;
	}

	/**
	 * Identifies the three independently ordered examination sections.
	 */
	public enum Section {

		/** Paper 1 multiple-choice Questions. */
		MULTIPLE_CHOICE("Paper 1 — Multiple choice", QuestionResponseType.MULTIPLE_CHOICE),
		/** Paper 1 short-response Questions. */
		PAPER_ONE_WRITTEN("Paper 1 — Short response", QuestionResponseType.WRITTEN_RESPONSE),
		/** Paper 2 short-response Questions. */
		PAPER_TWO_WRITTEN("Paper 2 — Short response", QuestionResponseType.WRITTEN_RESPONSE);

		private final String displayName;
		private final QuestionResponseType responseType;

		Section(String displayName, QuestionResponseType responseType) {
			this.displayName = displayName;
			this.responseType = responseType;
		}

		/**
		 * Returns the response type accepted by this section.
		 *
		 * @return permitted Question response type
		 */
		public QuestionResponseType responseType() {
			return responseType;
		}

		/**
		 * Returns the examination section display name.
		 *
		 * @return section display name
		 */
		@Override
		public String toString() {
			return displayName;
		}
	}
}
