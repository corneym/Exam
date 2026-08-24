package au.edu.eq.questionbank.model;

public class Exam {

	private final long id;
	private final Subject subject;
	private final ExamProvider provider;
	private final int year;
	private final String name;

	public Exam(long id, Subject subject, ExamProvider provider, int year, String name) {
		this.id = id;
		this.subject = subject;
		this.provider = provider;
		this.year = year;
		this.name = name;
	}

	public long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public Subject getSubject() {
		return subject;
	}

	public int getYear() {
		return year;
	}
}
