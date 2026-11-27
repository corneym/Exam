package au.edu.eq.questionbank.ui.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.SourceQuestion;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.pdf.PrintableAssessmentPlan;
import au.edu.eq.questionbank.pdf.PrintableAssessmentWriter;

/**
 * Maintains the editable Question selections for a mock examination belonging
 * to one authoritative Working Subject.
 *
 * <p>
 * A top-level Question is the indivisible selection unit. All constituent parts
 * of a multipart Question are added, removed, reordered and transferred
 * together.
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
	 * Adds a standalone Question to the specified section.
	 *
	 * <p>
	 * Questions with a SourceQuestion identity must be added through the
	 * whole-question selection API.
	 *
	 * @param section  destination examination section
	 * @param question standalone Question
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
	 * Atomically adds multiple whole Questions to a section.
	 *
	 * <p>
	 * Different constituent parts of the same multipart Question represent one
	 * selection. Repeated identical Question identifiers and Questions already
	 * selected in this draft are rejected.
	 *
	 * @param section    destination examination section
	 * @param candidates selected Questions or constituent parts
	 * @param corpus     complete, unfiltered stored Question collection
	 * @return true if the complete selection was added
	 * @throws NullPointerException if candidates or corpus is null
	 */
	public boolean addSelections(Section section, List<Question> candidates, List<Question> corpus) {
		Objects.requireNonNull(candidates, "candidates");
		Objects.requireNonNull(corpus, "corpus");
		if (section == null || candidates.isEmpty()) {
			return false;
		}
		ExamBuilderDraft staged = new ExamBuilderDraft(subject);
		staged.replaceWith(this);
		Set<Long> identifiers = new HashSet<>();

		// Stage the complete batch so a later invalid Question
		// cannot leave a partially modified examination.
		for (Question candidate : candidates) {
			if (candidate == null || !identifiers.add(candidate.getId()) || contains(candidate.getId())) {
				return false;
			}

			// An earlier constituent may already have added
			// this entire multipart Question to the staged draft.
			if (staged.contains(candidate.getId())) {
				continue;
			}
			if (!staged.addWholeQuestion(section, candidate, corpus)) {
				return false;
			}
		}
		replaceWith(staged);
		return true;
	}

	/**
	 * Adds an entire top-level Question to a section.
	 *
	 * @param section  destination examination section
	 * @param question selected Question or constituent part
	 * @param corpus   complete, unfiltered stored Question collection
	 * @return true if the entire Question was added
	 * @throws NullPointerException if corpus is null
	 */
	public boolean addWholeQuestion(Section section, Question question, List<Question> corpus) {
		List<Question> group = eligibleWholeQuestion(section, question, corpus);
		if (group.isEmpty()) {
			return false;
		}
		selections.get(section).addAll(group);
		return true;
	}

	/**
	 * Finds the adjacent top-level Question in a section.
	 *
	 * @param section    examination section
	 * @param questionId selected Question identifier
	 * @param direction  -1 for previous or 1 for next
	 * @return adjacent record index, or -1 if movement is unavailable
	 */
	public int adjacentWholeQuestionIndex(Section section, long questionId, int direction) {
		if (section == null || (direction != -1 && direction != 1)) {
			return -1;
		}
		List<Question> questions = selections.get(section);
		int selectedIndex = indexOf(questions, questionId);
		if (selectedIndex < 0) {
			return -1;
		}
		Question selected = questions.get(selectedIndex);
		int first = selectedIndex;
		int last = selectedIndex;
		for (int index = 0; index < questions.size(); index++) {
			if (sameWholeQuestion(selected, questions.get(index))) {
				first = Math.min(first, index);
				last = Math.max(last, index);
			}
		}
		int destination = direction < 0 ? first - 1 : last + 1;
		return destination >= 0 && destination < questions.size() ? destination : -1;
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
	 * Determines whether a standalone Question may be added.
	 *
	 * <p>
	 * Questions with a SourceQuestion identity require whole-question eligibility
	 * validation instead.
	 *
	 * @param section  proposed destination section
	 * @param question candidate standalone Question
	 * @return true if the standalone Question is eligible
	 */
	public boolean canAdd(Section section, Question question) {
		return question != null && question.getSourceQuestion() == null && canAddRecord(section, question);
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
	 * Resolves the complete eligible top-level Question represented by a selected
	 * Question or constituent part.
	 *
	 * @param section  destination examination section
	 * @param question selected Question or constituent part
	 * @param corpus   complete, unfiltered stored Question collection
	 * @return immutable ordered constituent Questions, or an empty list if the
	 *         selection is not eligible
	 * @throws NullPointerException if corpus is null
	 */
	public List<Question> eligibleWholeQuestion(Section section, Question question, List<Question> corpus) {
		Objects.requireNonNull(corpus, "corpus");
		if (section == null || question == null) {
			return List.of();
		}
		if (!subject.equals(question.getExam().getSubject())) {
			return List.of();
		}
		boolean alreadySelected = selections.values().stream().flatMap(List::stream)
				.anyMatch(selected -> sameWholeQuestion(selected, question));
		if (alreadySelected) {
			return List.of();
		}

		// Resolve membership using persistent source identity.
		// Printed codes determine constituent order only.
		List<Question> group = corpus.stream().filter(candidate -> sameWholeQuestion(candidate, question))
				.sorted(Comparator.comparing(Question::getQuestionCode, String.CASE_INSENSITIVE_ORDER)
						.thenComparingLong(Question::getId))
				.toList();
		if (group.stream().noneMatch(candidate -> candidate.getId() == question.getId())) {
			return List.of();
		}
		Set<Long> identifiers = new HashSet<>();

		// Every constituent must be eligible before any
		// part of the top-level Question can be selected.
		for (Question candidate : group) {
			if (!identifiers.add(candidate.getId()) || !canAddRecord(section, candidate)) {
				return List.of();
			}
		}
		return group;
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
	 * Returns the number of selected top-level Questions.
	 *
	 * <p>
	 * Each multipart Question counts once, irrespective of how many constituent
	 * Question records it contains.
	 *
	 * @return selected top-level Question count
	 */
	public int getTopLevelSelectedCount() {
		return (int) selections.values().stream().flatMap(List::stream).map(question -> {
			SourceQuestion source = question.getSourceQuestion();
			if (source == null) {
				return "question:" + question.getId();
			}
			return "source:" + question.getBooklet().getId() + ":" + source.getId();
		}).distinct().count();
	}

	/**
	 * Returns the marks allocated to a section.
	 *
	 * @param section requested examination section
	 * @return total selected marks
	 * @throws NullPointerException if section is null
	 */
	public int getTotalMarks(Section section) {
		return getQuestions(section).stream().mapToInt(Question::getMarks).sum();
	}

	/**
	 * Returns the number of selected Question records.
	 *
	 * <p>
	 * Multipart constituents count individually here. Use
	 * {@link #getTopLevelSelectedCount()} for the number of selected top-level
	 * Questions.
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
	 * Moves an entire top-level Question within its section.
	 *
	 * @param section          section containing the Question
	 * @param questionId       persistent Question identifier
	 * @param destinationIndex zero-based destination record index
	 * @return true if the entire Question was moved
	 * @throws NullPointerException if section is null
	 */
	public boolean move(Section section, long questionId, int destinationIndex) {
		Objects.requireNonNull(section, "section");
		List<Question> questions = selections.get(section);
		int originalIndex = indexOf(questions, questionId);
		if (originalIndex < 0 || destinationIndex < 0 || destinationIndex >= questions.size()) {
			return false;
		}
		Question selected = questions.get(originalIndex);
		Question destination = questions.get(destinationIndex);
		if (sameWholeQuestion(selected, destination)) {
			return false;
		}
		boolean movingDown = destinationIndex > originalIndex;
		List<Question> movingGroup = selectedWholeQuestion(questions, selected);
		questions.removeIf(question -> sameWholeQuestion(question, selected));
		int insertionIndex = indexOf(questions, destination.getId());
		if (movingDown) {

			// Insert after the entire destination group.
			for (int index = insertionIndex; index < questions.size(); index++) {
				if (sameWholeQuestion(destination, questions.get(index))) {
					insertionIndex = index;
				}
			}
			insertionIndex++;
		} else {

			// Insert before the entire destination group.
			for (int index = 0; index < insertionIndex; index++) {
				if (sameWholeQuestion(destination, questions.get(index))) {
					insertionIndex = index;
					break;
				}
			}
		}
		questions.addAll(insertionIndex, movingGroup);
		return true;
	}

	/**
	 * Removes an entire selected top-level Question.
	 *
	 * @param section    section containing the Question
	 * @param questionId persistent Question identifier
	 * @return true if the entire Question was removed
	 * @throws NullPointerException if section is null
	 */
	public boolean remove(Section section, long questionId) {
		Objects.requireNonNull(section, "section");
		List<Question> questions = selections.get(section);
		int index = indexOf(questions, questionId);
		if (index < 0) {
			return false;
		}
		Question selected = questions.get(index);
		return questions.removeIf(question -> sameWholeQuestion(question, selected));
	}

	/**
	 * Replaces the current selections with a validated draft belonging to the same
	 * Subject.
	 *
	 * <p>
	 * Selection lists are copied. The source draft remains independently editable.
	 *
	 * @param replacement validated replacement draft
	 * @throws NullPointerException     if replacement is null
	 * @throws IllegalArgumentException if the Subjects differ
	 */
	public void replaceWith(ExamBuilderDraft replacement) {
		Objects.requireNonNull(replacement, "replacement");
		if (!subject.equals(replacement.getSubject())) {
			throw new IllegalArgumentException("Draft Subjects differ.");
		}
		if (replacement == this) {
			return;
		}
		for (Section section : Section.values()) {
			List<Question> target = selections.get(section);
			target.clear();
			target.addAll(replacement.getQuestions(section));
		}
	}

	/**
	 * Transfers an entire selected top-level Question to another compatible
	 * section.
	 *
	 * @param source      original examination section
	 * @param destination destination examination section
	 * @param questionId  persistent Question identifier
	 * @return true if the entire Question was transferred
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
		Question selected = sourceQuestions.get(index);
		List<Question> movingGroup = selectedWholeQuestion(sourceQuestions, selected);

		// Validate the complete destination group
		// before modifying either section.
		for (Question question : movingGroup) {
			if (question.getResponseType() != destination.responseType()
					|| !PrintableAssessmentWriter.canSelect(question)) {
				return false;
			}
		}
		sourceQuestions.removeIf(question -> sameWholeQuestion(question, selected));
		selections.get(destination).addAll(movingGroup);
		return true;
	}

	private boolean canAddRecord(Section section, Question question) {
		return section != null && question != null && subject.equals(question.getExam().getSubject())
				&& question.getResponseType() == section.responseType() && PrintableAssessmentWriter.canSelect(question)
				&& !contains(question.getId());
	}

	private int indexOf(List<Question> questions, long questionId) {
		for (int index = 0; index < questions.size(); index++) {
			if (questions.get(index).getId() == questionId) {
				return index;
			}
		}
		return -1;
	}

	private boolean sameWholeQuestion(Question first, Question second) {
		if (first.getBooklet().getId() != second.getBooklet().getId()) {
			return false;
		}
		SourceQuestion firstSource = first.getSourceQuestion();
		SourceQuestion secondSource = second.getSourceQuestion();
		if (firstSource == null || secondSource == null) {
			return first.getId() == second.getId();
		}
		return firstSource.getId() == secondSource.getId();
	}

	private List<Question> selectedWholeQuestion(List<Question> questions, Question selected) {
		return questions.stream().filter(question -> sameWholeQuestion(question, selected)).toList();
	}

	/**
	 * Identifies the independently ordered examination sections.
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
