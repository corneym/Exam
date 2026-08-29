package au.edu.eq.questionbank.pdf;

import java.nio.file.Path;

/**
 * Resolves stored source-document paths beneath a configured PDF data root.
 * <p>
 * Stored paths are treated as untrusted: they must be relative and their
 * normalized resolved paths must remain inside the configured root.
 */
public class PdfStore {

	private final Path pdfRoot;

	/**
	 * Creates a store rooted at an absolute, normalized form of {@code pdfRoot}.
	 *
	 * @param pdfRoot the directory containing source examination PDFs
	 * @throws NullPointerException if {@code pdfRoot} is {@code null}
	 */
	public PdfStore(Path pdfRoot) {
		if (pdfRoot == null) {
			throw new NullPointerException("pdfRoot");
		}
		this.pdfRoot = pdfRoot.toAbsolutePath().normalize();
	}

	/**
	 * Safely resolves a stored relative path beneath this store's data root.
	 *
	 * @param relativePath a non-blank, data-root-relative source path
	 * @return the normalized absolute path beneath the configured root
	 * @throws NullPointerException     if {@code relativePath} is {@code null}
	 * @throws IllegalArgumentException if the path is blank, absolute, or escapes
	 *                                  the configured root after normalization
	 */
	public Path resolve(String relativePath) {
		if (relativePath == null) {
			throw new NullPointerException("relativePath");
		}
		if (relativePath.isBlank()) {
			throw new IllegalArgumentException("PDF path must not be blank");
		}

		Path path = Path.of(relativePath);
		if (path.isAbsolute() || path.getRoot() != null) {
			throw new IllegalArgumentException("PDF path must be relative: " + relativePath);
		}

		Path resolved = pdfRoot.resolve(path).normalize();
		if (!resolved.startsWith(pdfRoot)) {
			throw new IllegalArgumentException("PDF path must remain within the configured data root: " + relativePath);
		}
		return resolved;
	}
}
