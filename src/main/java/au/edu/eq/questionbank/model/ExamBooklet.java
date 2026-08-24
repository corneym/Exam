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

	/**
	 * Creates a booklet belonging to an exam.
	 *
	 * @param id             the positive persistent booklet identifier
	 * @param exam           the exam to which the booklet belongs
	 * @param name           the non-blank booklet name
	 * @param sourceDocument the source file containing the booklet
	 * @throws IllegalArgumentException if {@code id} is not positive or
	 *                                  {@code name} is blank
	 * @throws NullPointerException     if {@code exam} or
	 *                                  {@code sourceDocument} is {@code null}
	 */
	public ExamBooklet(long id, Exam exam, String name, SourceDocument sourceDocument) {
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

	public Exam getExam() {
		return exam;
	}

	public long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public SourceDocument getSourceDocument() {
		return sourceDocument;
	}
}
