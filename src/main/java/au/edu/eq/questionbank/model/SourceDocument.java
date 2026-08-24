package au.edu.eq.questionbank.model;

/**
 * Identifies an underlying source file from which examination content is read.
 * <p>
 * The path is stored relative to the application's configured data root so
 * question-bank data remains portable. Consumers must resolve it through a
 * containment-checking boundary such as
 * {@link au.edu.eq.questionbank.pdf.PdfStore}.
 */
public class SourceDocument {
	private final long id;
	private final String relativePath;

	/**
	 * Creates a source-document reference.
	 *
	 * @param id           the persistent source-document identifier
	 * @param relativePath the source file path relative to the configured data
	 *                     root
	 */
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
