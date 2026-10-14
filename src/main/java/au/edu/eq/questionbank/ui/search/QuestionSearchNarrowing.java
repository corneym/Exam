package au.edu.eq.questionbank.ui.search;

import java.util.Objects;
import java.util.function.Predicate;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.Question;

/**
 * Immutable constraint applied to every Question Search result generation.
 * <p>
 * A narrowing is independent of the Search scope. It therefore applies equally
 * to Current Syllabus and All Questions retrieval and remains active when
 * Search refreshes after editing a Question.
 */
public final class QuestionSearchNarrowing {

	private static final QuestionSearchNarrowing UNRESTRICTED = new QuestionSearchNarrowing("", _ -> true, false);
	private final boolean descriptorAssignmentQueue;
	private final String description;
	private final Predicate<Question> predicate;

	private QuestionSearchNarrowing(String description, Predicate<Question> predicate,
			boolean descriptorAssignmentQueue) {
		this.description = Objects.requireNonNull(description, "description");
		this.predicate = Objects.requireNonNull(predicate, "predicate");
		this.descriptorAssignmentQueue = descriptorAssignmentQueue;
	}

	/**
	 * Creates a narrowing for one Exam booklet.
	 *
	 * @param booklet booklet whose Questions may be shown
	 * @return immutable booklet narrowing
	 * @throws NullPointerException if {@code booklet} is {@code null}
	 */
	public static QuestionSearchNarrowing forBooklet(ExamBooklet booklet) {
		Objects.requireNonNull(booklet, "booklet");
		long bookletId = booklet.getId();
		Exam exam = booklet.getExam();
		String description = "Booklet: %s %d — %s — %s".formatted(exam.getProvider().getName(), exam.getYear(),
				exam.getName(), booklet.getName());
		return new QuestionSearchNarrowing(description, question -> question.getBooklet().getId() == bookletId, false);
	}

	/**
	 * Creates a narrowing for one Exam.
	 *
	 * @param exam Exam whose Questions may be shown
	 * @return immutable Exam narrowing
	 * @throws NullPointerException if {@code exam} is {@code null}
	 */
	public static QuestionSearchNarrowing forExam(Exam exam) {
		Objects.requireNonNull(exam, "exam");
		long examId = exam.getId();
		String description = "Exam: %s %d — %s".formatted(exam.getProvider().getName(), exam.getYear(), exam.getName());
		return new QuestionSearchNarrowing(description, question -> question.getExam().getId() == examId, false);
	}

	/**
	 * Creates the outstanding Descriptor-assignment queue for one booklet.
	 *
	 * The constraint combines persistent booklet identity with the current stored
	 * classification. A successfully classified Question therefore leaves the queue
	 * on the next authoritative retrieval.
	 *
	 * @param booklet booklet whose unclassified Questions require attention
	 * @return immutable Descriptor-assignment queue constraint
	 */
	public static QuestionSearchNarrowing forMissingDescriptors(ExamBooklet booklet) {
		QuestionSearchNarrowing bookletScope = forBooklet(booklet);
		String description = "Assign Descriptors — " + bookletScope.description();
		return new QuestionSearchNarrowing(description, question -> bookletScope.includes(question)
				&& question.getClassification().getLevel() != CurriculumLevel.DESCRIPTOR, true);
	}

	/**
	 * Returns the normal unrestricted Search context.
	 *
	 * @return shared unrestricted narrowing
	 */
	public static QuestionSearchNarrowing unrestricted() {
		return UNRESTRICTED;
	}

	/**
	 * Returns the user-facing description of this narrowing.
	 *
	 * @return user-facing description, or an empty String when unrestricted
	 */
	public String description() {
		return description;
	}

	/**
	 * Tests whether a Question belongs to this narrowing.
	 *
	 * @param question Question to test
	 * @return {@code true} when the Question may appear
	 * @throws NullPointerException if {@code question} is {@code null}
	 */
	public boolean includes(Question question) {
		return predicate.test(Objects.requireNonNull(question, "question"));
	}

	/**
	 * Indicates whether Search is operating as a shrinking Descriptor queue rather
	 * than an ordinary inspection workflow.
	 *
	 * @return true for the booklet-scoped Descriptor-assignment queue
	 */
	public boolean isDescriptorAssignmentQueue() {
		return descriptorAssignmentQueue;
	}

	/**
	 * Returns whether this narrowing restricts the complete Working Subject.
	 *
	 * @return {@code true} when the narrowing is restricted
	 */
	public boolean isRestricted() {
		return this != UNRESTRICTED;
	}
}
