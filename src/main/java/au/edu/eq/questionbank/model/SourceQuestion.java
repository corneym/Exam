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

	public SourceQuestion(long id, ExamBooklet booklet, String sourceQuestionCode) {
		this(id, booklet, sourceQuestionCode, PreambleStatus.UNKNOWN);
	}

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

	public ExamBooklet getBooklet() {
		return booklet;
	}

	public long getId() {
		return id;
	}

	public PreambleStatus getPreambleStatus() {
		return preambleStatus;
	}

	public String getSourceQuestionCode() {
		return sourceQuestionCode;
	}
}
