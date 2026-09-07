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
	private final String sourceQuestionCode;

	public SourceQuestion(long id, ExamBooklet booklet, String sourceQuestionCode) {
		if (id < 1) {
			throw new IllegalArgumentException("id must be positive");
		}
		if (booklet == null) {
			throw new NullPointerException("booklet");
		}
		if (sourceQuestionCode == null || sourceQuestionCode.isBlank()) {
			throw new IllegalArgumentException("sourceQuestionCode must not be blank");
		}
		this.id = id;
		this.booklet = booklet;
		this.sourceQuestionCode = sourceQuestionCode;
	}

	public ExamBooklet getBooklet() {
		return booklet;
	}

	public long getId() {
		return id;
	}

	public String getSourceQuestionCode() {
		return sourceQuestionCode;
	}
}
