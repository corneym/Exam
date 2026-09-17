package au.edu.eq.questionbank.pdf;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves stored source-document paths beneath a configured PDF data root.
 * <p>
 * Stored paths are treated as untrusted: they must be relative and their
 * normalized resolved paths must remain inside the configured root. Containment
 * is lexical: these checks do not resolve symbolic links or junctions.
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
	 * Imports an examination PDF into the standard subject/provider/year hierarchy.
	 *
	 * @param sourcePath   the PDF selected by the user
	 * @param subjectName  the subject name
	 * @param providerName the examination provider
	 * @param year         the examination year
	 * @return the absolute stored PDF path; an existing byte-identical file is
	 *         reused
	 * @throws IOException              if the source is not a regular file, the
	 *                                  file cannot be copied, or different bytes
	 *                                  already occupy the required destination
	 * @throws NullPointerException     if {@code sourcePath} is {@code null}
	 * @throws IllegalArgumentException if a directory component is blank, contains
	 *                                  a path separator, or {@code year} is not
	 *                                  positive
	 */
	public Path importExamPdf(Path sourcePath, String subjectName, String providerName, int year) throws IOException {
		if (sourcePath == null) {
			throw new NullPointerException("sourcePath");
		}
		String subjectDirectory = validateDirectoryName(subjectName, "subjectName");
		String providerDirectory = validateDirectoryName(providerName, "providerName");
		if (year < 1) {
			throw new IllegalArgumentException("year must be positive");
		}
		Path source = sourcePath.toAbsolutePath().normalize();
		if (!Files.isRegularFile(source)) {
			throw new IOException("Exam PDF source is not a regular file: " + source);
		}
		Path destinationDirectory = pdfRoot.resolve(subjectDirectory).resolve(providerDirectory)
				.resolve(Integer.toString(year)).normalize();
		if (!destinationDirectory.startsWith(pdfRoot)) {
			throw new IllegalArgumentException("PDF destination must remain within the configured data root");
		}
		Path destination = destinationDirectory.resolve(source.getFileName()).normalize();
		if (source.equals(destination)) {
			return destination;
		}
		Files.createDirectories(destinationDirectory);
		if (!Files.exists(destination)) {
			return Files.copy(source, destination);
		}
		if (Files.isSameFile(source, destination)) {
			return destination;
		}
		if (Files.mismatch(source, destination) == -1) {
			return destination;
		}
		throw new FileAlreadyExistsException(destination.toString(), source.toString(),
				"A different PDF with the same filename already exists in the exam directory");
	}

	/**
	 * Lexically resolves a stored relative path beneath this store's data root.
	 * This does not check existence or resolve filesystem links.
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

	private String validateDirectoryName(String value, String fieldName) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(fieldName + " must not be blank");
		}
		String trimmed = value.trim();
		if (".".equals(trimmed) || "..".equals(trimmed) || trimmed.contains("/") || trimmed.contains("\\")) {
			throw new IllegalArgumentException(fieldName + " is not a valid directory name: " + value);
		}
		return trimmed;
	}
}
