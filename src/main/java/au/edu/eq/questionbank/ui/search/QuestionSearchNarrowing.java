package au.edu.eq.questionbank.ui.search;

import java.util.Objects;
import java.util.function.Predicate;

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

	private static final QuestionSearchNarrowing UNRESTRICTED = new QuestionSearchNarrowing("", _ -> true);
	private final String description;
	private final Predicate<Question> predicate;

	private QuestionSearchNarrowing(String description, Predicate<Question> predicate) {
		this.description = Objects.requireNonNull(description, "description");
		this.predicate = Objects.requireNonNull(predicate, "predicate");
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

		// Persistent identity is used because repository reads may reconstruct the
		// domain objects represented by the Dashboard selection.
		return new QuestionSearchNarrowing(description, question -> question.getBooklet().getId() == bookletId);
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

		// Match persistent identity rather than Java object identity so the narrowing
		// remains valid across independent repository reads.
		return new QuestionSearchNarrowing(description, question -> question.getExam().getId() == examId);
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
	 * @return user-facing description of this narrowing, or an empty String when
	 *         unrestricted
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
	 * @return whether this narrowing restricts the complete Working Subject
	 */
	public boolean isRestricted() {
		return this != UNRESTRICTED;
	}
}
