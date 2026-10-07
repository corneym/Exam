package au.edu.eq.questionbank.pdf;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;

import au.edu.eq.questionbank.ManagedDataLayout;

/**
 * Resolves stored source-document paths beneath a configured PDF data root.
 * <p>
 * Stored paths are treated as untrusted: they must be relative and their
 * normalized resolved paths must remain inside the configured root. Containment
 * is lexical: these checks do not resolve symbolic links or junctions.
 */
public class PdfStore {

	private final ManagedDataLayout managedDataLayout;
	private final Path pdfRoot;

	/**
	 * Creates a store using only the Subject-first managed-data layout.
	 *
	 * @param managedDataLayout canonical application managed-data layout
	 * @throws NullPointerException if {@code managedDataLayout} is {@code null}
	 */
	public PdfStore(ManagedDataLayout managedDataLayout) {
		if (managedDataLayout == null) {
			throw new NullPointerException("managedDataLayout");
		}
		this.managedDataLayout = managedDataLayout;
		pdfRoot = null;
	}

	/**
	 * Creates a transitional store that writes Subject-first paths while retaining
	 * read access to legacy PDF-root-relative paths.
	 *
	 * @param managedDataLayout canonical application managed-data layout
	 * @param legacyPdfRoot     former dedicated PDF data root
	 * @throws NullPointerException if either argument is {@code null}
	 */
	public PdfStore(ManagedDataLayout managedDataLayout, Path legacyPdfRoot) {
		if (managedDataLayout == null) {
			throw new NullPointerException("managedDataLayout");
		}
		if (legacyPdfRoot == null) {
			throw new NullPointerException("legacyPdfRoot");
		}
		this.managedDataLayout = managedDataLayout;
		pdfRoot = legacyPdfRoot.toAbsolutePath().normalize();
	}

	/**
	 * Creates a store using the legacy PDF-root-relative storage contract.
	 * <p>
	 * This constructor remains temporarily available while existing installations
	 * and callers are migrated to the Subject-first managed-data layout.
	 *
	 * @param pdfRoot legacy directory containing source examination PDFs
	 * @throws NullPointerException if {@code pdfRoot} is {@code null}
	 */
	public PdfStore(Path pdfRoot) {
		if (pdfRoot == null) {
			throw new NullPointerException("pdfRoot");
		}
		managedDataLayout = null;
		this.pdfRoot = pdfRoot.toAbsolutePath().normalize();
	}

	/**
	 * Deletes one managed PDF using the same containment checks as ordinary source
	 * resolution.
	 *
	 * @param relativePath managed data-root-relative PDF path
	 * @return whether a file existed and was deleted
	 * @throws IOException              if deletion fails
	 * @throws NullPointerException     if {@code relativePath} is {@code null}
	 * @throws IllegalArgumentException if the path is invalid or escapes the store
	 */
	public boolean deleteManagedPdf(String relativePath) throws IOException {
		Path managedPath = resolve(relativePath);

		// Never accept an arbitrary filesystem path for deletion. resolve() proves the
		// persisted path remains lexically beneath the configured PDF root.
		return Files.deleteIfExists(managedPath);
	}

	/**
	 * Imports an examination PDF using the legacy Subject/Provider/Year hierarchy.
	 * <p>
	 * This overload exists only while legacy callers are migrated. A Subject-first
	 * store requires the assessment-name overload.
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
	 * @throws IllegalArgumentException if a directory component or year is invalid
	 * @throws IllegalStateException    if this store uses the Subject-first layout
	 */
	public Path importExamPdf(Path sourcePath, String subjectName, String providerName, int year) throws IOException {
		if (managedDataLayout != null) {
			throw new IllegalStateException("Subject-first Exam storage requires an assessment name");
		}
		Path destination = managedDestination(sourcePath, subjectName, providerName, year);
		return copyToManagedDestination(sourcePath, destination);
	}

	/**
	 * Imports an examination PDF into the canonical Subject-first hierarchy.
	 *
	 * @param sourcePath     the PDF selected by the user
	 * @param subjectName    owning Subject name
	 * @param providerName   examination provider
	 * @param year           examination year
	 * @param assessmentName assessment name
	 * @return absolute stored PDF path; an existing byte-identical file is reused
	 * @throws IOException              if the source is not a regular file, the
	 *                                  file cannot be copied, or different bytes
	 *                                  already occupy the required destination
	 * @throws NullPointerException     if {@code sourcePath} is {@code null}
	 * @throws IllegalArgumentException if a managed directory component or year is
	 *                                  invalid
	 * @throws IllegalStateException    if this store was created with a legacy PDF
	 *                                  root
	 */
	public Path importExamPdf(Path sourcePath, String subjectName, String providerName, int year, String assessmentName)
			throws IOException {
		Path destination = managedDestination(sourcePath, subjectName, providerName, year, assessmentName);
		return copyToManagedDestination(sourcePath, destination);
	}

	/**
	 * Calculates the legacy managed destination for a source PDF without copying or
	 * moving the file.
	 *
	 * @param sourcePath   source whose filename will be retained
	 * @param subjectName  owning subject
	 * @param providerName examination provider
	 * @param year         examination year
	 * @return normalized absolute legacy destination
	 * @throws NullPointerException     if {@code sourcePath} is {@code null}
	 * @throws IllegalArgumentException if a directory component or year is invalid
	 * @throws IllegalStateException    if this store uses the Subject-first layout
	 */
	public Path managedDestination(Path sourcePath, String subjectName, String providerName, int year) {
		if (managedDataLayout != null) {
			throw new IllegalStateException("Subject-first Exam storage requires an assessment name");
		}
		if (sourcePath == null) {
			throw new NullPointerException("sourcePath");
		}
		String subjectDirectory = validateDirectoryName(subjectName, "subjectName");
		String providerDirectory = validateDirectoryName(providerName, "providerName");
		if (year < 1) {
			throw new IllegalArgumentException("year must be positive");
		}
		Path filename = sourcePath.toAbsolutePath().normalize().getFileName();
		if (filename == null) {
			throw new IllegalArgumentException("sourcePath must identify a file");
		}
		Path destination = pdfRoot.resolve(subjectDirectory).resolve(providerDirectory).resolve(Integer.toString(year))
				.resolve(filename).normalize();
		if (!destination.startsWith(pdfRoot)) {
			throw new IllegalArgumentException("PDF destination must remain within the configured data root");
		}
		return destination;
	}

	/**
	 * Calculates the canonical Subject-first destination for a source PDF without
	 * copying or moving the file.
	 *
	 * @param sourcePath     source whose filename will be retained
	 * @param subjectName    owning Subject name
	 * @param providerName   examination provider
	 * @param year           examination year
	 * @param assessmentName assessment name
	 * @return normalized absolute destination beneath the application data root
	 * @throws NullPointerException     if {@code sourcePath} is {@code null}
	 * @throws IllegalArgumentException if a managed directory component or year is
	 *                                  invalid
	 * @throws IllegalStateException    if this store was created with a legacy PDF
	 *                                  root
	 */
	public Path managedDestination(Path sourcePath, String subjectName, String providerName, int year,
			String assessmentName) {
		if (sourcePath == null) {
			throw new NullPointerException("sourcePath");
		}
		ManagedDataLayout layout = requireSubjectFirstLayout();
		Path filename = sourcePath.toAbsolutePath().normalize().getFileName();
		if (filename == null) {
			throw new IllegalArgumentException("sourcePath must identify a file");
		}

		// ManagedDataLayout owns every directory component. PdfStore contributes only
		// the source filename to the already-contained Exam directory.
		return layout.examDirectory(subjectName, providerName, year, assessmentName).resolve(filename).normalize();
	}

	/**
	 * Converts a managed PDF path to its persisted relative representation.
	 *
	 * @param managedPath absolute managed PDF path
	 * @return portable path relative to the applicable managed-data root
	 * @throws NullPointerException     if {@code managedPath} is {@code null}
	 * @throws IllegalArgumentException if the path is not contained by this store
	 */
	public String relativePath(Path managedPath) {
		if (managedPath == null) {
			throw new NullPointerException("managedPath");
		}
		if (managedDataLayout != null) {
			return managedDataLayout.relativePath(managedPath);
		}
		if (!managedPath.isAbsolute()) {
			throw new IllegalArgumentException("Managed PDF path must be absolute: " + managedPath);
		}
		Path normalizedPath = managedPath.normalize();
		if (normalizedPath.equals(pdfRoot) || !normalizedPath.startsWith(pdfRoot)) {
			throw new IllegalArgumentException(
					"Managed PDF path must be beneath the configured PDF root: " + managedPath);
		}
		return pdfRoot.relativize(normalizedPath).toString().replace('\\', '/');
	}

	/**
	 * Lexically resolves a stored managed PDF path.
	 * <p>
	 * Subject-first paths are relative to the application data root. During the
	 * migration transition, older paths may instead remain relative to the former
	 * dedicated PDF root.
	 *
	 * @param relativePath persisted managed source path
	 * @return normalized absolute managed path
	 * @throws NullPointerException     if {@code relativePath} is {@code null}
	 * @throws IllegalArgumentException if the path is blank, absolute, or escapes
	 *                                  its applicable managed root
	 */
	public Path resolve(String relativePath) {
		if (relativePath == null) {
			throw new NullPointerException("relativePath");
		}
		if (relativePath.isBlank()) {
			throw new IllegalArgumentException("PDF path must not be blank");
		}

		// New persisted Exam paths have an explicit subjects/ namespace, so they can
		// be distinguished deterministically from legacy PDF-root-relative paths.
		if (managedDataLayout != null && isSubjectFirstPath(relativePath)) {
			return managedDataLayout.resolve(relativePath);
		}
		if (pdfRoot == null) {

			// A non-transitional Subject-first store has no legacy root. Let the common
			// managed-data boundary perform containment validation.
			return managedDataLayout.resolve(relativePath);
		}
		Path path = Path.of(relativePath);
		if (path.isAbsolute() || path.getRoot() != null) {
			throw new IllegalArgumentException("PDF path must be relative: " + relativePath);
		}

		// Legacy persisted paths remain relative to the former PDF root only until
		// the dedicated migration slice rewrites them.
		Path resolved = pdfRoot.resolve(path).normalize();
		if (!resolved.startsWith(pdfRoot)) {
			throw new IllegalArgumentException("PDF path must remain within the configured data root: " + relativePath);
		}
		return resolved;
	}

	private Path copyToManagedDestination(Path sourcePath, Path destination) throws IOException {
		if (sourcePath == null) {
			throw new NullPointerException("sourcePath");
		}
		Path source = sourcePath.toAbsolutePath().normalize();
		if (!Files.isRegularFile(source)) {
			throw new IOException("Exam PDF source is not a regular file: " + source);
		}
		Path destinationDirectory = destination.getParent();

		// A source already at its authoritative managed destination needs no copy.
		if (source.equals(destination)) {
			return destination;
		}
		Files.createDirectories(destinationDirectory);
		if (!Files.exists(destination)) {

			// Never replace a managed source as a side effect of ordinary import.
			return Files.copy(source, destination);
		}
		if (Files.isSameFile(source, destination) || Files.mismatch(source, destination) == -1) {
			return destination;
		}
		throw new FileAlreadyExistsException(destination.toString(), source.toString(),
				"A different PDF with the same filename already exists in the exam directory");
	}

	private boolean isSubjectFirstPath(String relativePath) {
		String portablePath = relativePath.replace('\\', '/');
		return portablePath.equals("subjects") || portablePath.startsWith("subjects/");
	}

	private ManagedDataLayout requireSubjectFirstLayout() {
		if (managedDataLayout == null) {
			throw new IllegalStateException("PdfStore was created with a legacy PDF root");
		}
		return managedDataLayout;
	}

	private String validateDirectoryName(String value, String fieldName) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(fieldName + " must not be blank");
		}
		String trimmed = value.trim();

		// Subject and provider labels each supply one directory component, not a
		// relative path.
		if (".".equals(trimmed) || "..".equals(trimmed) || trimmed.contains("/") || trimmed.contains("\\")) {
			throw new IllegalArgumentException(fieldName + " is not a valid directory name: " + value);
		}
		return trimmed;
	}
}
