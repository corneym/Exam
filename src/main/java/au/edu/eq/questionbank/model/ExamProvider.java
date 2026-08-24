package au.edu.eq.questionbank.model;

public class ExamProvider {
	private final long id;
	private final String name;

	public ExamProvider(long id, String name) {
		this.id = id;
		this.name = name;
	}

	public long getId() {
		return id;
	}

	public String getName() {
		return name;
	}
}
