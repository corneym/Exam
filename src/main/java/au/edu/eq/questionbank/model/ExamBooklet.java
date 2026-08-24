package au.edu.eq.questionbank.model;

public class ExamBooklet {

	private final long id;
	private final Exam exam;
	private final String name;
	private final SourceDocument sourceDocument;

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
