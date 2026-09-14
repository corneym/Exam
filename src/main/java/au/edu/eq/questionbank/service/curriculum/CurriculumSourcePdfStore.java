package au.edu.eq.questionbank.service.curriculum;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import au.edu.eq.questionbank.model.SyllabusVersion;

/**
 * Manages authoritative syllabus PDFs beneath the configured curriculum data
 * root.
 * <p>
 * Database values use portable forward-slash relative paths. Absolute machine
 * paths are never persisted by this service.
 */
public final class CurriculumSourcePdfStore {

	private static final String SOURCES_DIRECTORY = "sources";
	private final Path curriculumDataRoot;

	public CurriculumSourcePdfStore(Path curriculumDataRoot) {
		if (curriculumDataRoot == null) {
			throw new NullPointerException("curriculumDataRoot");
		}
		this.curriculumDataRoot = curriculumDataRoot.toAbsolutePath().normalize();
	}

	/**
	 * Copies an authoritative external syllabus PDF into managed curriculum
	 * storage.
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
		String subjectDirectory = safeComponent(syllabusVersion.getSubject().getName(),
				"subject-" + syllabusVersion.getSubject().getId());
		String versionDirectory = safeComponent(syllabusVersion.getName(), "version-" + syllabusVersion.getId());
		String managedFileName = safeComponent(sourceFileName, "syllabus.pdf");
		if (!managedFileName.toLowerCase().endsWith(".pdf")) {
			managedFileName += ".pdf";
		}
		Path relativePath = Path.of(subjectDirectory, versionDirectory, SOURCES_DIRECTORY, managedFileName);
		Path target = curriculumDataRoot.resolve(relativePath).normalize();
		requireInsideCurriculumRoot(target);
		if (source.equals(target)) {
			return portablePath(relativePath);
		}
		Path parent = target.getParent();
		Files.createDirectories(parent);
		Path temporary = Files.createTempFile(parent, ".curriculum-source-", ".tmp");
		boolean moved = false;
		try {
			Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
			try {
				Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
			}
			moved = true;
		} finally {
			if (!moved) {
				Files.deleteIfExists(temporary);
			}
		}
		return portablePath(relativePath);
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
