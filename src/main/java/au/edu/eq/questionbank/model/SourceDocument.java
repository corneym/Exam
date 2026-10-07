package au.edu.eq.questionbank.model;

/**
 * Identifies an underlying source file from which examination content is read.
 * <p>
 * Newly managed source documents store portable paths relative to the
 * application's data root. During migration, older Exam PDF references may
 * still contain paths relative to the former dedicated PDF root.
 * <p>
 * Consumers must resolve persisted paths through a containment-checking
 * boundary such as {@link au.edu.eq.questionbank.pdf.PdfStore}; they must not
 * concatenate persisted path text directly with filesystem roots.
 */
public class SourceDocument {

	private final long id;
	private final String relativePath;
	private final String contentSha256;

	/**
	 * Creates a source-document reference.
	 *
	 * @param id           the persistent source-document identifier
	 * @param relativePath portable persisted managed-source path
	 * @throws IllegalArgumentException if {@code id} is not positive or
	 *                                  {@code relativePath} is null or blank
	 */
	public SourceDocument(long id, String relativePath) {
		this(id, relativePath, null);
	}

	/**
	 * Creates a source-document reference with an optional content hash.
	 *
	 * @param id            the persistent source-document identifier
	 * @param relativePath  portable persisted managed-source path
	 * @param contentSha256 canonical lower-case SHA-256 hexadecimal digest, or
	 *                      {@code null} when the document has not yet been hashed
	 * @throws IllegalArgumentException if {@code id} is not positive,
	 *                                  {@code relativePath} is null or blank, or a
	 *                                  supplied hash is not exactly 64 lower-case
	 *                                  hexadecimal characters
	 */
	public SourceDocument(long id, String relativePath, String contentSha256) {
		if (id < 1) {
			throw new IllegalArgumentException("id must be positive");
		}
		if (relativePath == null || relativePath.isBlank()) {
			throw new IllegalArgumentException("relativePath must not be blank");
		}
		if (contentSha256 != null && !contentSha256.matches("[0-9a-f]{64}")) {
			throw new IllegalArgumentException(
					"contentSha256 must contain exactly 64 lower-case hexadecimal characters");
		}
		this.id = id;

		// Retain the stored reference; filesystem resolution and containment checks
		// belong to the PDF store.
		this.relativePath = relativePath;

		// NULL deliberately represents a legacy or not-yet-inspected source document.
		this.contentSha256 = contentSha256;
	}

	/**
	 * Returns the persisted SHA-256 identity of the source bytes when known.
	 *
	 * @return canonical lower-case SHA-256 digest, or {@code null} when no hash has
	 *         yet been recorded
	 */
	public String getContentSha256() {
		return contentSha256;
	}

	/**
	 * Returns the persistent identity of this source-document reference.
	 *
	 * @return positive source-document identifier
	 */
	public long getId() {
		return id;
	}

	/**
	 * Returns the stored managed-source path for resolution through a
	 * containment-checking store.
	 *
	 * @return persisted portable source path, as supplied
	 */
	public String getRelativePath() {
		return relativePath;
	}
}
