package au.edu.eq.questionbank.service.curriculum;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.model.SyllabusVersion;

/**
 * Manages authoritative syllabus source PDFs.
 * <p>
 * New attachments use the canonical Subject-first hierarchy beneath
 * {@code subjects/<Subject>/curriculum/<Version>/sources} and persist portable
 * paths relative to the application data root.
 * <p>
 * Transitional stores may also resolve historical paths relative to the former
 * dedicated curriculum root. New writes never create those legacy persisted
 * paths.
 * <p>
 * UUID-qualified filenames ensure a newly attached source never overwrites the
 * PDF still referenced by persisted syllabus metadata.
 */
public final class CurriculumSourcePdfStore {

	private static final String SOURCES_DIRECTORY = "sources";
	private final Path curriculumDataRoot;
	private final ManagedDataLayout managedDataLayout;

	/**
	 * Creates transitional syllabus-PDF storage that writes Subject-first paths
	 * while retaining read access to legacy curriculum-root-relative paths.
	 *
	 * @param managedDataLayout  canonical application managed-data layout
	 * @param curriculumDataRoot former dedicated curriculum data root
	 * @throws NullPointerException if either argument is {@code null}
	 */
	public CurriculumSourcePdfStore(ManagedDataLayout managedDataLayout, Path curriculumDataRoot) {
		if (managedDataLayout == null) {
			throw new NullPointerException("managedDataLayout");
		}
		if (curriculumDataRoot == null) {
			throw new NullPointerException("curriculumDataRoot");
		}
		this.curriculumDataRoot = curriculumDataRoot.toAbsolutePath().normalize();
		this.managedDataLayout = managedDataLayout;
	}

	/**
	 * Creates managed syllabus-PDF storage using the legacy curriculum-root
	 * contract.
	 *
	 * @param curriculumDataRoot root against which legacy stored relative PDF paths
	 *                           are resolved
	 * @throws NullPointerException if {@code curriculumDataRoot} is {@code null}
	 */
	public CurriculumSourcePdfStore(Path curriculumDataRoot) {
		if (curriculumDataRoot == null) {
			throw new NullPointerException("curriculumDataRoot");
		}
		this.curriculumDataRoot = curriculumDataRoot.toAbsolutePath().normalize();
		managedDataLayout = null;
	}

	/**
	 * Deletes a managed PDF that has not been published successfully.
	 *
	 * @param relativePath persisted managed source-PDF path
	 * @throws IOException              if the file cannot be deleted
	 * @throws IllegalArgumentException if the path is invalid or escapes its
	 *                                  applicable managed root
	 */
	public void deleteManagedPdf(String relativePath) throws IOException {
		Files.deleteIfExists(resolveManagedPdf(relativePath));
	}

	/**
	 * Copies an authoritative syllabus PDF into a new managed location.
	 * <p>
	 * Each call creates a distinct UUID-qualified managed file. Existing managed
	 * PDFs are never overwritten by this method.
	 *
	 * @param syllabusVersion syllabus owning the source PDF
	 * @param sourcePdf       existing external or managed PDF
	 * @return portable persisted managed path
	 * @throws IOException              if the source cannot be read or the managed
	 *                                  copy cannot be written
	 * @throws NullPointerException     if either argument is {@code null}
	 * @throws IllegalArgumentException if the source is not a regular PDF file or
	 *                                  Subject/version identity is invalid
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
		String safeFileName = safeComponent(sourceFileName, "syllabus.pdf");
		if (!safeFileName.toLowerCase().endsWith(".pdf")) {
			safeFileName += ".pdf";
		}

		// A replacement never overwrites the file still referenced by persisted
		// syllabus metadata.
		String managedFileName = UUID.randomUUID() + "--" + safeFileName;
		if (managedDataLayout != null) {
			Path targetDirectory = managedDataLayout.curriculumSourceDirectory(syllabusVersion.getSubject().getName(),
					syllabusVersion.getName());
			Path target = targetDirectory.resolve(managedFileName).normalize();
			copyToManagedFile(source, target);

			// New attachments persist one canonical path relative to dataRoot.
			return managedDataLayout.relativePath(target);
		}

		// Retain the historical identity-qualified hierarchy only for legacy callers.
		String subjectDirectory = safeComponent(syllabusVersion.getSubject().getName(), "subject") + "--subject-"
				+ syllabusVersion.getSubject().getId();
		String versionDirectory = safeComponent(syllabusVersion.getName(), "version") + "--syllabus-"
				+ syllabusVersion.getId();
		Path relativePath = Path.of(subjectDirectory, versionDirectory, SOURCES_DIRECTORY, managedFileName);
		Path target = curriculumDataRoot.resolve(relativePath).normalize();
		requireInsideCurriculumRoot(target);
		copyToManagedFile(source, target);
		return portablePath(relativePath);
	}

	/**
	 * Resolves a persisted managed curriculum PDF path.
	 * <p>
	 * Subject-first paths are resolved through the application data-root boundary.
	 * Transitional stores continue to resolve older curriculum-root-relative paths
	 * until the migration slice rewrites them.
	 *
	 * @param relativePath portable persisted managed path
	 * @return normalised absolute managed path
	 * @throws IllegalArgumentException if the path is blank, absolute or escapes
	 *                                  its applicable managed root
	 */
	public Path resolveManagedPdf(String relativePath) {
		if (relativePath == null || relativePath.isBlank()) {
			throw new IllegalArgumentException("relativePath must not be blank");
		}
		String portablePath = relativePath.replace('\\', '/');
		if (managedDataLayout != null && isSubjectFirstPath(portablePath)) {

			// ManagedDataLayout provides the shared absolute/traversal containment
			// boundary for all newly persisted curriculum source paths.
			return managedDataLayout.resolve(portablePath);
		}
		Path stored = Path.of(portablePath);
		if (stored.isAbsolute() || stored.getRoot() != null) {
			throw new IllegalArgumentException("Managed curriculum PDF path must be relative");
		}
		Path resolved = curriculumDataRoot.resolve(stored).normalize();
		requireInsideCurriculumRoot(resolved);
		return resolved;
	}

	private void copyToManagedFile(Path source, Path target) throws IOException {
		Path parent = target.getParent();
		Files.createDirectories(parent);

		// Stage beside the destination so an atomic move is possible on supported
		// filesystems.
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

	private boolean isSubjectFirstPath(String relativePath) {
		String[] components = relativePath.split("/");

		// Canonical source paths have:
		// subjects/<Subject>/curriculum/<Version>/sources/<filename>
		return components.length >= 6 && "subjects".equals(components[0]) && "curriculum".equals(components[2])
				&& "sources".equals(components[4]);
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
