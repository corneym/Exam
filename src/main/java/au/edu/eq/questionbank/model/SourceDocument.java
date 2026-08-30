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
	 * @throws IllegalArgumentException if {@code id} is not positive or
	 *                                  {@code relativePath} is null or blank
	 */
	public SourceDocument(long id, String relativePath) {
		if (id < 1) {
			throw new IllegalArgumentException("id must be positive");
		}
		if (relativePath == null || relativePath.isBlank()) {
			throw new IllegalArgumentException("relativePath must not be blank");
		}
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
