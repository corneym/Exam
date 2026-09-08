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
	private final PreambleStatus preambleStatus;
	private final String sourceQuestionCode;

	/**
	 * Creates a persisted source-question identity whose preamble state has not yet
	 * been resolved.
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
		this(id, booklet, sourceQuestionCode, PreambleStatus.UNKNOWN);
	}

	/**
	 * Creates a persisted source-question identity with an explicit preamble state.
	 *
	 * @param id                 the positive persistent identifier
	 * @param booklet            the examination booklet containing the question
	 *                           parts
	 * @param sourceQuestionCode the common source code
	 * @param preambleStatus     whether shared preamble content is unresolved,
	 *                           absent or present
	 * @throws IllegalArgumentException if {@code id} is not positive or the source
	 *                                  code is blank
	 * @throws NullPointerException     if {@code booklet} or
	 *                                  {@code preambleStatus} is {@code null}
	 */
	public SourceQuestion(long id, ExamBooklet booklet, String sourceQuestionCode, PreambleStatus preambleStatus) {
		if (id < 1) {
			throw new IllegalArgumentException("id must be positive");
		}
		if (booklet == null) {
			throw new NullPointerException("booklet");
		}
		if (sourceQuestionCode == null || sourceQuestionCode.isBlank()) {
			throw new IllegalArgumentException("sourceQuestionCode must not be blank");
		}
		if (preambleStatus == null) {
			throw new NullPointerException("preambleStatus");
		}
		this.id = id;
		this.booklet = booklet;
		this.sourceQuestionCode = sourceQuestionCode;
		this.preambleStatus = preambleStatus;
	}

	/**
	 * @return the booklet that owns this source-question identity
	 */
	public ExamBooklet getBooklet() {
		return booklet;
	}

	/**
	 * @return the positive persistent identifier
	 */
	public long getId() {
		return id;
	}

	/**
	 * @return the persisted shared-preamble state
	 */
	public PreambleStatus getPreambleStatus() {
		return preambleStatus;
	}

	/**
	 * @return the common question code within the owning booklet
	 */
	public String getSourceQuestionCode() {
		return sourceQuestionCode;
	}
}
