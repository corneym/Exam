package au.edu.eq.questionbank.service.workpackage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.pdf.PdfStore;
import au.edu.eq.questionbank.service.workpackage.ExamWorkAssetIndex.Reference;

/**
 * Inspects managed-file destinations before contributor assignment installation
 * without modifying existing files.
 *
 * <p>
 * Existing source files may be reused only when their complete bytes match the
 * issued size and SHA-256. New files require publication by a later
 * transactional installation service. Embedded image assets are database BLOBs,
 * not managed-file publication targets.
 *
 * <p>
 * The caller must first validate the complete WORK_ASSIGNMENT archive,
 * including its logical asset index and whole-Exam scope. A successful
 * inspection is not permission to publish without rechecking the destination
 * immediately before writing.
 */
public final class WorkAssignmentManagedAssetGate {

	private final ManagedDataLayout layout;

	/**
	 * Creates a read-only destination inspector.
	 *
	 * @param config contributor installation configuration
	 * @throws NullPointerException if config is null
	 */
	public WorkAssignmentManagedAssetGate(ApplicationConfig config) {
		Objects.requireNonNull(config, "config");
		layout = new ManagedDataLayout(config.dataRoot());
	}

	/**
	 * /** Classifies all validated assignment assets without publishing them.
	 *
	 * <p>
	 * The same managed file may be referenced by multiple Booklets or assignments,
	 * provided its expected bytes are identical.
	 *
	 * <p>
	 * Exam source PDFs must occupy the owning Subject's Exam namespace and resolve
	 * to the same managed destination used by PdfStore.
	 *
	 * @param subjectName Subject identified by the assignment manifest
	 * @param index       previously validated whole-Exam asset index
	 * @return immutable list of per-reference destination decisions
	 * @throws IOException              if an existing file differs, a path is
	 *                                  unsafe, a linked path is encountered, or
	 *                                  asset metadata is invalid
	 * @throws NullPointerException     if an argument is null
	 * @throws IllegalArgumentException if subjectName is blank
	 */
	public List<Decision> inspect(String subjectName, ExamWorkAssetIndex index) throws IOException {
		Objects.requireNonNull(subjectName, "subjectName");
		Objects.requireNonNull(index, "index");
		if (subjectName.isBlank()) {
			throw new IllegalArgumentException("Assignment Subject name must not be blank");
		}
		Path root = layout.dataRoot();
		if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Contributor data root is not an ordinary directory");
		}
		String subjectPrefix = "subjects/" + subjectName + "/";
		List<Decision> decisions = new ArrayList<>();
		PdfStore pdfStore = new PdfStore(layout);
		for (Reference reference : index.references()) {
			if (!reference.managedPath().startsWith(subjectPrefix)) {
				throw new IOException("Assignment asset belongs to another Subject: " + reference.managedPath());
			}
			Disposition disposition;
			Path destination = null;
			switch (reference.usage()) {
			case "SOURCE_PDF", "SYLLABUS_PDF" -> {
				if (!"application/pdf".equals(reference.mediaType())) {
					throw new IOException("Managed source document is not a PDF");
				}
				destination = layout.resolve(reference.managedPath());
				if ("SOURCE_PDF".equals(reference.usage())) {

					// Assignment publication and ordinary PDF access
					// must agree on the exact managed destination.
					try {
						Path resolved = pdfStore.resolve(reference.managedPath());
						if (!resolved.equals(destination) || !destination.startsWith(layout.examsDirectory(subjectName))
								|| destination.equals(layout.examsDirectory(subjectName))) {
							throw new IOException(
									"Invalid assignment Exam PDF destination: " + reference.managedPath());
						}
					} catch (IllegalArgumentException failure) {
						throw new IOException("Invalid assignment Exam PDF destination: " + reference.managedPath(),
								failure);
					}
				}
				disposition = inspectManagedFile(destination, reference.size(), reference.sha256());
			}
			case "EMBEDDED_QUESTION_PNG", "EMBEDDED_CONTEXT_PNG" -> {
				if (!"image/png".equals(reference.mediaType())
						|| !reference.managedPath().startsWith(subjectPrefix + "exams/_examwork_embedded/")) {
					throw new IOException("Invalid assignment embedded image path");
				}

				// The image bytes are installed into SQLite.
				// No synthetic PNG file is published.
				disposition = Disposition.EMBEDDED;
			}
			default -> throw new IOException("Unsupported assignment asset usage: " + reference.usage());
			}
			decisions.add(new Decision(reference, disposition, destination));
		}
		return List.copyOf(decisions);
	}

	private Disposition inspectManagedFile(Path destination, long expectedSize, String expectedSha256)
			throws IOException {
		Path root = layout.dataRoot();
		if (destination.equals(root) || !destination.startsWith(root)) {
			throw new IOException("Assignment destination escapes the data root");
		}
		Path current = root;

		// Reject symbolic links and non-directory ancestors.
		// A later publisher must repeat this check immediately
		// before CREATE_NEW or identical-file reuse.
		for (Path component : root.relativize(destination)) {
			current = current.resolve(component);
			if (Files.isSymbolicLink(current)) {
				throw new IOException("Assignment asset path contains a symbolic link: " + current);
			}
			if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
				continue;
			}
			if (!current.equals(destination) && !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
				throw new IOException("Assignment asset ancestor is not a directory: " + current);
			}
		}
		if (!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
			return Disposition.CREATE;
		}
		if (!Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Assignment asset destination is not a regular file: " + destination);
		}
		if (Files.size(destination) != expectedSize || !sha256(destination).equals(expectedSha256)) {
			throw new IOException("Existing assignment asset has different bytes: " + destination);
		}
		return Disposition.REUSE;
	}

	private String sha256(Path file) throws IOException {
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
		try (InputStream input = Files.newInputStream(file)) {
			byte[] buffer = new byte[8192];
			int length;
			while ((length = input.read(buffer)) != -1) {
				digest.update(buffer, 0, length);
			}
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	/**
	 * Required handling of an assignment asset.
	 */
	public enum Disposition {
		/** The target file does not exist and must be created safely. */
		CREATE,
		/** An existing file has exactly the expected size and SHA-256. */
		REUSE,
		/** The asset is an embedded SQLite image rather than a file. */
		EMBEDDED
	}

	/**
	 * Read-only assessment of one logical assignment asset.
	 *
	 * @param reference   validated package asset reference
	 * @param disposition required installation handling
	 * @param destination absolute managed-file destination, or null for embedded
	 *                    images
	 */
	public record Decision(Reference reference, Disposition disposition, Path destination) {

		/**
		 * Validates and normalises a destination decision.
		 *
		 * @throws NullPointerException     if required metadata is absent
		 * @throws IllegalArgumentException if destination and disposition are
		 *                                  inconsistent
		 */
		public Decision {
			Objects.requireNonNull(reference, "reference");
			Objects.requireNonNull(disposition, "disposition");
			if ((disposition == Disposition.EMBEDDED) != (destination == null)) {
				throw new IllegalArgumentException("Embedded images must not have file destinations");
			}
			if (destination != null) {
				destination = destination.toAbsolutePath().normalize();
			}
		}
	}
}
