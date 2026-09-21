package au.edu.eq.questionbank.model;

/**
 * One document or booklet belonging to an {@link Exam}.
 * <p>
 * An exam may be distributed as several booklets, such as a question booklet,
 * response booklet or stimulus book. Each booklet identifies the
 * {@link SourceDocument} containing its pages.
 */
public class ExamBooklet {

	private final long id;
	private final Exam exam;
	private final String name;
	private final SourceDocument sourceDocument;

	// Question format belongs to the booklet because separate booklets from one
	// Exam may contain different styles of Question.
	private final ExamBookletQuestionFormat questionFormat;

	/**
	 * Creates a booklet whose question format has not been explicitly recorded.
	 * <p>
	 * This constructor preserves compatibility with existing persisted and test
	 * data created before booklet question-format metadata was introduced.
	 *
	 * @param id             the positive persistent booklet identifier
	 * @param exam           the exam to which the booklet belongs
	 * @param name           the non-blank booklet name
	 * @param sourceDocument the source file containing the booklet
	 */
	public ExamBooklet(long id, Exam exam, String name, SourceDocument sourceDocument) {

		// Existing callers cannot supply information that was never previously
		// recorded, so retain that distinction rather than guessing a format.
		this(id, exam, name, sourceDocument, ExamBookletQuestionFormat.UNSPECIFIED);
	}

	/**
	 * Creates a booklet belonging to an exam with an explicit question format.
	 *
	 * @param id             the positive persistent booklet identifier
	 * @param exam           the exam to which the booklet belongs
	 * @param name           the non-blank booklet name
	 * @param sourceDocument the source file containing the booklet
	 * @param questionFormat the kinds of Questions expected in this booklet
	 * @throws IllegalArgumentException if {@code id} is not positive or
	 *                                  {@code name} is blank
	 * @throws NullPointerException     if {@code exam}, {@code sourceDocument} or
	 *                                  {@code questionFormat} is {@code null}
	 */
	public ExamBooklet(long id, Exam exam, String name, SourceDocument sourceDocument,
			ExamBookletQuestionFormat questionFormat) {
		if (id < 1) {
			throw new IllegalArgumentException("id must be positive");
		}
		if (exam == null) {
			throw new NullPointerException("exam");
		}
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("name must not be blank");
		}
		if (sourceDocument == null) {
			throw new NullPointerException("sourceDocument");
		}
		if (questionFormat == null) {
			throw new NullPointerException("questionFormat");
		}
		this.id = id;
		this.exam = exam;
		this.name = name;
		this.sourceDocument = sourceDocument;

		// Store the booklet-level capture hint independently from any individual
		// Question response type.
		this.questionFormat = questionFormat;
	}

	/**
	 * Returns the examination containing this booklet.
	 *
	 * @return owning examination
	 */
	public Exam getExam() {
		return exam;
	}

	/**
	 * Returns the persistent identity of this examination booklet.
	 *
	 * @return positive booklet identifier
	 */
	public long getId() {
		return id;
	}

	/**
	 * Returns the label distinguishing this booklet within its exam.
	 *
	 * @return non-blank booklet name
	 */
	public String getName() {
		return name;
	}

	/**
	 * Returns the kinds of Questions expected in this booklet.
	 *
	 * @return persisted booklet question format
	 */
	public ExamBookletQuestionFormat getQuestionFormat() {
		return questionFormat;
	}

	/**
	 * Returns the original document from which booklet regions are captured.
	 *
	 * @return managed source-document reference
	 */
	public SourceDocument getSourceDocument() {
		return sourceDocument;
	}
}
