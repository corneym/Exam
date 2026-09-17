package au.edu.eq.questionbank.model;

/**
 * A source file containing answers, worked solutions or marking material for an
 * {@link Exam}.
 */
public class AnswerFile {

	private final long id;
	private final Exam exam;
	private final String name;
	private final SourceDocument sourceDocument;

	/**
	 * Creates an answer file belonging to an exam.
	 *
	 * @param id             the positive persistent answer-file identifier
	 * @param exam           the exam to which the answers belong
	 * @param name           the non-blank answer-file name
	 * @param sourceDocument the source file containing the answers
	 * @throws IllegalArgumentException if {@code id} is not positive or
	 *                                  {@code name} is blank
	 * @throws NullPointerException     if {@code exam} or {@code sourceDocument} is
	 *                                  {@code null}
	 */
	public AnswerFile(long id, Exam exam, String name, SourceDocument sourceDocument) {

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

		this.id = id;
		this.exam = exam;
		this.name = name;
		this.sourceDocument = sourceDocument;
	}

	/**
	 * Returns the exam whose answers this file contains.
	 *
	 * @return owning examination
	 */
	public Exam getExam() {
		return exam;
	}

	/**
	 * Returns the persistent identity of this answer file.
	 *
	 * @return positive answer-file identifier
	 */
	public long getId() {
		return id;
	}

	/**
	 * Returns the label used to distinguish this exam's answer files.
	 *
	 * @return non-blank answer-file name
	 */
	public String getName() {
		return name;
	}

	/**
	 * Returns the original document containing the answer material.
	 *
	 * @return managed source-document reference
	 */
	public SourceDocument getSourceDocument() {
		return sourceDocument;
	}
}
