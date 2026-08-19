package au.edu.eq.questionbank.pdf;

import java.nio.file.Path;
import java.util.Objects;

public class PdfStore {

	private final Path pdfRoot;

	public PdfStore(Path pdfRoot) {
		Objects.requireNonNull(pdfRoot, "pdfRoot");
		this.pdfRoot = pdfRoot.toAbsolutePath().normalize();
	}

	public Path resolve(String relativePath) {
		Objects.requireNonNull(relativePath, "relativePath");
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
