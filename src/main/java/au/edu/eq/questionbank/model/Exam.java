package au.edu.eq.questionbank.model;

public class Exam {

	private final long id;
	private final String subject;
	private final int year;
	private final String name;
	private final SourceDocument sourceDocument;

	public Exam(long id, String subject, int year, String name, SourceDocument sourceDocument) {
		this.id = id;
		this.subject = subject;
		this.year = year;
		this.name = name;
		this.sourceDocument = sourceDocument;
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

	public String getSubject() {
		return subject;
	}

	public int getYear() {
		return year;
	}

}
