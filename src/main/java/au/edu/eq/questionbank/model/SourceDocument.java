package au.edu.eq.questionbank.model;

public class SourceDocument {
	private final long id;
	private final String relativePath;

	public SourceDocument(long id, String relativePath) {
		this.id = id;
		this.relativePath = relativePath;
	}

	public long getId() {
		return id;
	}

	public String getRelativePath() {
		return relativePath;
	}
}
