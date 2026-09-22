package au.edu.eq.questionbank.model;

/**
 * The common source-question identity for one or more independently stored
 * question parts within an examination booklet.
 * <p>
 * For example, questions {@code 21a}, {@code 21b} and {@code 21c} may all
 * belong to source question {@code 21}.
 */
public final class SourceQuestion {

	private final long id;
	private final ExamBooklet booklet;
	private final SharedContextStatus sharedContextStatus;
	private final String sourceQuestionCode;

	/**
	 * Creates a persisted source-question identity whose shared context state has
	 * not yet been resolved.
	 *
	 * @param id                 the positive persistent identifier
	 * @param booklet            the examination booklet containing the question
	 *                           parts
	 * @param sourceQuestionCode the common source code, such as {@code 21} for
	 *                           parts {@code 21a} and {@code 21b}
	 * @throws IllegalArgumentException if {@code id} is not positive or the source
	 *                                  code is blank
	 * @throws NullPointerException     if {@code booklet} is {@code null}
	 */
	public SourceQuestion(long id, ExamBooklet booklet, String sourceQuestionCode) {
		this(id, booklet, sourceQuestionCode, SharedContextStatus.UNKNOWN);
	}

	/**
	 * Creates a persisted source-question identity with an explicit context state.
	 *
	 * @param id                  the positive persistent identifier
	 * @param booklet             the examination booklet containing the question
	 *                            parts
	 * @param sourceQuestionCode  the common source code
	 * @param sharedContextStatus whether shared context content is unresolved,
	 *                            absent or present
	 * @throws IllegalArgumentException if {@code id} is not positive or the source
	 *                                  code is blank
	 * @throws NullPointerException     if {@code booklet} or
	 *                                  {@code sharedContextStatus} is {@code null}
	 */
	public SourceQuestion(long id, ExamBooklet booklet, String sourceQuestionCode,
			SharedContextStatus sharedContextStatus) {
		if (id < 1) {
			throw new IllegalArgumentException("id must be positive");
		}
		if (booklet == null) {
			throw new NullPointerException("booklet");
		}
		if (sourceQuestionCode == null || sourceQuestionCode.isBlank()) {
			throw new IllegalArgumentException("sourceQuestionCode must not be blank");
		}
		if (sharedContextStatus == null) {
			throw new NullPointerException("sharedContextStatus");
		}
		this.id = id;
		this.booklet = booklet;
		this.sourceQuestionCode = sourceQuestionCode;
		this.sharedContextStatus = sharedContextStatus;
	}

	/**
	 * Returns the booklet containing this source-question group.
	 *
	 * @return the booklet that owns this source-question identity
	 */
	public ExamBooklet getBooklet() {
		return booklet;
	}

	/**
	 * Returns the persistent identity shared by captured parts of this source
	 * question.
	 *
	 * @return the positive persistent identifier
	 */
	public long getId() {
		return id;
	}

	/**
	 * Returns the recorded presence or absence of a shared context.
	 *
	 * @return the persisted shared-context state
	 */
	public SharedContextStatus getSharedContextStatus() {
		return sharedContextStatus;
	}

	/**
	 * Returns the original question code common to the captured parts.
	 *
	 * @return the common question code within the owning booklet
	 */
	public String getSourceQuestionCode() {
		return sourceQuestionCode;
	}
}
