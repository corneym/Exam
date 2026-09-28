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

	// The expected count describes top-level numbered Questions, not individually
	// captured multipart Question records such as 21a, 21b and 21c.
	private final Integer expectedQuestionCount;

	/**
	 * Creates a booklet whose question format and expected Question count have not
	 * been explicitly recorded.
	 *
	 * @param id             the positive persistent booklet identifier
	 * @param exam           the exam to which the booklet belongs
	 * @param name           the non-blank booklet name
	 * @param sourceDocument the source file containing the booklet
	 */
	public ExamBooklet(long id, Exam exam, String name, SourceDocument sourceDocument) {

		// Existing callers cannot supply metadata that was not previously recorded.
		this(id, exam, name, sourceDocument, ExamBookletQuestionFormat.UNSPECIFIED, null);
	}

	/**
	 * Creates a booklet with an explicit Question format but no recorded expected
	 * Question count.
	 *
	 * @param id             the positive persistent booklet identifier
	 * @param exam           the exam to which the booklet belongs
	 * @param name           the non-blank booklet name
	 * @param sourceDocument the source file containing the booklet
	 * @param questionFormat the kinds of Questions expected in this booklet
	 */
	public ExamBooklet(long id, Exam exam, String name, SourceDocument sourceDocument,
			ExamBookletQuestionFormat questionFormat) {

		// A missing expected count means it has not yet been deliberately established.
		this(id, exam, name, sourceDocument, questionFormat, null);
	}

	/**
	 * Creates a booklet with its persisted capture-planning metadata.
	 *
	 * @param id                    the positive persistent booklet identifier
	 * @param exam                  the exam to which the booklet belongs
	 * @param name                  the non-blank booklet name
	 * @param sourceDocument        the source file containing the booklet
	 * @param questionFormat        the kinds of Questions expected in this booklet
	 * @param expectedQuestionCount expected top-level Question count, or
	 *                              {@code null} when it has not been established
	 * @throws IllegalArgumentException if {@code id} is not positive, {@code name}
	 *                                  is blank, or a supplied expected count is
	 *                                  not positive
	 * @throws NullPointerException     if {@code exam}, {@code sourceDocument}, or
	 *                                  {@code questionFormat} is {@code null}
	 */
	public ExamBooklet(long id, Exam exam, String name, SourceDocument sourceDocument,
			ExamBookletQuestionFormat questionFormat, Integer expectedQuestionCount) {
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
		if (expectedQuestionCount != null && expectedQuestionCount < 1) {
			throw new IllegalArgumentException("expectedQuestionCount must be positive when supplied");
		}
		this.id = id;
		this.exam = exam;
		this.name = name;
		this.sourceDocument = sourceDocument;
		this.questionFormat = questionFormat;
		this.expectedQuestionCount = expectedQuestionCount;
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
	 * Returns the expected number of top-level numbered Questions.
	 *
	 * @return positive expected count, or {@code null} when not yet recorded
	 */
	public Integer getExpectedQuestionCount() {
		return expectedQuestionCount;
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

	/**
	 * Returns whether an expected top-level Question count has been established.
	 *
	 * @return whether an expected count is present
	 */
	public boolean hasExpectedQuestionCount() {
		return expectedQuestionCount != null;
	}
}