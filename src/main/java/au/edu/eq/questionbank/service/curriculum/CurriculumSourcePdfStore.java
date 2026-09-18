package au.edu.eq.questionbank.service.curriculum;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

import au.edu.eq.questionbank.model.SyllabusVersion;

/**
 * Manages authoritative syllabus PDFs beneath the configured curriculum data
 * root.
 * <p>
 * Database values use portable forward-slash relative paths. Absolute machine
 * paths are never persisted by this service.
 * <p>
 * Managed attachment paths include persistent curriculum identity and a unique
 * file name so that one attachment never overwrites another before its metadata
 * has been successfully published.
 */
public final class CurriculumSourcePdfStore {

	private static final String SOURCES_DIRECTORY = "sources";
	private final Path curriculumDataRoot;

	/**
	 * Creates managed syllabus-PDF storage beneath the configured curriculum root.
	 *
	 * @param curriculumDataRoot root against which stored relative PDF paths are
	 *                           resolved
	 */
	public CurriculumSourcePdfStore(Path curriculumDataRoot) {
		if (curriculumDataRoot == null) {
			throw new NullPointerException("curriculumDataRoot");
		}
		this.curriculumDataRoot = curriculumDataRoot.toAbsolutePath().normalize();
	}

	/**
	 * Deletes a managed PDF that has not been published successfully.
	 *
	 * @param relativePath portable path relative to the curriculum data root
	 * @throws IOException              if the file cannot be deleted
	 * @throws IllegalArgumentException if the path is invalid or escapes the
	 *                                  curriculum root
	 */
	public void deleteManagedPdf(String relativePath) throws IOException {
		Files.deleteIfExists(resolveManagedPdf(relativePath));
	}

	/**
	 * Copies an authoritative syllabus PDF into a new managed location.
	 * <p>
	 * Each call creates a distinct managed file. Existing managed PDFs are never
	 * replaced by this method.
	 *
	 * @param syllabusVersion syllabus owning the source PDF
	 * @param sourcePdf       existing external or managed PDF
	 * @return portable path relative to the curriculum data root
	 * @throws IOException              if the source cannot be read or the managed
	 *                                  copy cannot be written
	 * @throws IllegalArgumentException if the source is not a regular PDF file
	 */
	public String managePdf(SyllabusVersion syllabusVersion, Path sourcePdf) throws IOException {
		if (syllabusVersion == null) {
			throw new NullPointerException("syllabusVersion");
		}
		if (sourcePdf == null) {
			throw new NullPointerException("sourcePdf");
		}
		Path source = sourcePdf.toAbsolutePath().normalize();
		if (!Files.isRegularFile(source)) {
			throw new IllegalArgumentException("Syllabus PDF must be an existing regular file: " + source);
		}
		String sourceFileName = source.getFileName().toString();
		if (!sourceFileName.toLowerCase().endsWith(".pdf")) {
			throw new IllegalArgumentException("Syllabus source file must be a PDF: " + source);
		}
		// Persistent IDs distinguish names that sanitise to the same directory component.
		String subjectDirectory = safeComponent(syllabusVersion.getSubject().getName(), "subject") + "--subject-"
				+ syllabusVersion.getSubject().getId();
		String versionDirectory = safeComponent(syllabusVersion.getName(), "version") + "--syllabus-"
				+ syllabusVersion.getId();
		String safeFileName = safeComponent(sourceFileName, "syllabus.pdf");
		if (!safeFileName.toLowerCase().endsWith(".pdf")) {
			safeFileName += ".pdf";
		}
		// A replacement must not overwrite the file still referenced by stored metadata.
		String managedFileName = UUID.randomUUID() + "--" + safeFileName;
		Path relativePath = Path.of(subjectDirectory, versionDirectory, SOURCES_DIRECTORY, managedFileName);
		Path target = curriculumDataRoot.resolve(relativePath).normalize();
		requireInsideCurriculumRoot(target);
		copyToManagedFile(source, target);
		return portablePath(relativePath);
	}

	private void copyToManagedFile(Path source, Path target) throws IOException {
		Path parent = target.getParent();
		Files.createDirectories(parent);
		// Stage beside the destination so an atomic move is possible on supported filesystems.
		Path temporary = Files.createTempFile(parent, ".curriculum-source-", ".tmp");
		boolean moved = false;
		try {
			Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
			try {
				Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(temporary, target);
			}
			moved = true;
		} finally {
			if (!moved) {
				Files.deleteIfExists(temporary);
			}
		}
	}

	/**
	 * Resolves a stored managed relative path against this curriculum data root.
	 *
	 * @param relativePath portable stored relative path
	 * @return normalised absolute managed path
	 * @throws IllegalArgumentException if the path is blank, absolute or escapes
	 *                                  the curriculum root
	 */
	public Path resolveManagedPdf(String relativePath) {
		if (relativePath == null || relativePath.isBlank()) {
			throw new IllegalArgumentException("relativePath must not be blank");
		}
		Path stored = Path.of(relativePath);
		if (stored.isAbsolute()) {
			throw new IllegalArgumentException("Managed curriculum PDF path must be relative");
		}
		// Collapse parent segments before checking containment of the untrusted stored path.
		Path resolved = curriculumDataRoot.resolve(stored).normalize();
		requireInsideCurriculumRoot(resolved);
		return resolved;
	}

	private String portablePath(Path relativePath) {
		return relativePath.toString().replace(File.separatorChar, '/');
	}

	private void requireInsideCurriculumRoot(Path path) {
		if (!path.startsWith(curriculumDataRoot)) {
			throw new IllegalArgumentException("Managed curriculum path escapes curriculum data root");
		}
	}

	private String safeComponent(String value, String fallback) {
		String safe = value.trim().replaceAll("[^\\p{L}\\p{N}._-]+", "-").replaceAll("-{2,}", "-")
				.replaceAll("^[.-]+|[.-]+$", "");
		if (safe.isBlank()) {
			return fallback;
		}
		return safe;
	}
}
