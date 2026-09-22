package au.edu.eq.questionbank.output.scorm;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.output.revision.RevisionExportProgressListener;
import au.edu.eq.questionbank.output.revision.RevisionExportRequest;
import au.edu.eq.questionbank.output.revision.RevisionExportResult;
import au.edu.eq.questionbank.output.revision.RevisionExportService;

/**
 * Coordinates generation of a complete SCORM 1.2 revision ZIP.
 */
public final class ScormExportService {

	private final RevisionExportService revisionExportService;
	private final ScormManifestWriter manifestWriter;
	private final ScormSchemaSupport schemaSupport;
	private final ScormPackageValidator packageValidator;
	private final ScormZipWriter zipWriter;

	/**
	 * Creates the SCORM orchestration service from the existing revision exporter
	 * and package-specific collaborators.
	 *
	 * @param revisionExportService static revision-content generator
	 * @param manifestWriter        SCORM manifest writer
	 * @param schemaSupport         bundled SCORM support-file copier
	 * @param packageValidator      staged-package validator
	 * @param zipWriter             final ZIP writer
	 * @throws NullPointerException if any collaborator is null
	 */
	public ScormExportService(RevisionExportService revisionExportService, ScormManifestWriter manifestWriter,
			ScormSchemaSupport schemaSupport, ScormPackageValidator packageValidator, ScormZipWriter zipWriter) {
		if (revisionExportService == null) {
			throw new NullPointerException("revisionExportService");
		}
		if (manifestWriter == null) {
			throw new NullPointerException("manifestWriter");
		}
		if (schemaSupport == null) {
			throw new NullPointerException("schemaSupport");
		}
		if (packageValidator == null) {
			throw new NullPointerException("packageValidator");
		}
		if (zipWriter == null) {
			throw new NullPointerException("zipWriter");
		}
		this.revisionExportService = revisionExportService;
		this.manifestWriter = manifestWriter;
		this.schemaSupport = schemaSupport;
		this.packageValidator = packageValidator;
		this.zipWriter = zipWriter;
	}

	/**
	 * Generates and publishes a SCORM package without progress notifications.
	 *
	 * @param request export Subject and destination
	 * @return the completed export result
	 * @throws IOException          if content generation, validation, packaging,
	 *                              publication or workspace cleanup fails
	 * @throws NullPointerException if {@code request} is null
	 */
	public ScormExportResult export(ScormExportRequest request) throws IOException {
		return export(request, (_, _, _) -> {
		});
	}

	/**
	 * Generates the static revision content in a private workspace, adds and
	 * validates the SCORM files, then publishes the completed ZIP.
	 *
	 * @param request  export Subject and destination
	 * @param progress listener for revision and packaging progress
	 * @return the completed export result
	 * @throws IOException          if content generation, validation, packaging,
	 *                              publication or workspace cleanup fails
	 * @throws NullPointerException if the request or listener is null
	 */
	public ScormExportResult export(ScormExportRequest request, RevisionExportProgressListener progress)
			throws IOException {
		if (request == null) {
			throw new NullPointerException("request");
		}
		if (progress == null) {
			throw new NullPointerException("progress");
		}
		Path destination = request.getDestination().toAbsolutePath().normalize();
		validateDestination(destination);
		Path parent = destination.getParent();
		if (parent == null) {
			throw new IOException("SCORM export destination must have a parent directory: " + destination);
		}
		Files.createDirectories(parent);
		Path workspace = Files.createTempDirectory(parent, ".scorm-work-");
		Throwable failure = null;
		try {
			Path revisionRoot = workspace.resolve("revision");
			progress.update("Generating revision content...", 0, 0);

			// SCORM must use exactly the same grouping semantics as the static HTML
			// revision site it packages.
			RevisionExportRequest revisionRequest;
			if (request.hasGroupingMode()) {
				revisionRequest = new RevisionExportRequest(request.getSubject(), revisionRoot,
						request.getGroupingMode());
			} else {
				revisionRequest = new RevisionExportRequest(request.getSubject(), revisionRoot);
			}
			RevisionExportResult revisionResult = revisionExportService.export(revisionRequest, progress);

			// Inventory learning content before adding schemas and the manifest, which are
			// package infrastructure.
			List<Path> contentFiles = collectContentFiles(revisionRoot);
			progress.update("Adding SCORM support files...", 0, 0);
			schemaSupport.copyTo(revisionRoot);
			progress.update("Writing SCORM manifest...", 0, 0);
			String manifestIdentifier = "eq-question-bank-subject-" + request.getSubject().getId();
			String title = request.getSubject().getName() + " Revision";
			manifestWriter.write(revisionRoot, manifestIdentifier, title, Path.of("index.html"), contentFiles);
			progress.update("Validating SCORM package...", 0, 0);

			// Validate the assembled directory before the ZIP writer stages and publishes
			// the archive.
			packageValidator.validate(revisionRoot);
			progress.update("Creating SCORM ZIP...", 0, 0);
			zipWriter.write(revisionRoot, destination);
			progress.update("SCORM export complete.", 1, 1);
			return new ScormExportResult(destination, revisionResult.getStatistics());
		} catch (IOException | RuntimeException exception) {
			failure = exception;
			throw exception;
		} finally {
			try {
				deleteRecursively(workspace);
			} catch (IOException cleanupException) {
				if (failure != null) {

					// Preserve the export error while retaining evidence that workspace cleanup
					// also failed.
					failure.addSuppressed(cleanupException);
				} else {
					throw cleanupException;
				}
			}
		}
	}

	/**
	 * Returns whether Descriptor grouping is available for the Subject's current
	 * revision corpus.
	 *
	 * @param subject subject being considered for SCORM export
	 * @return true when every renderable placement has Descriptor coverage
	 */
	public boolean isDescriptorGroupingAvailable(Subject subject) {
		return revisionExportService.isDescriptorGroupingAvailable(subject);
	}

	private List<Path> collectContentFiles(Path revisionRoot) throws IOException {
		Path root = revisionRoot.toAbsolutePath().normalize();
		try (Stream<Path> paths = Files.walk(root)) {
			return paths.filter(Files::isRegularFile).map(root::relativize)
					.sorted(Comparator.comparing(this::portablePath)).toList();
		}
	}

	private void deleteRecursively(Path root) throws IOException {
		if (!Files.exists(root)) {
			return;
		}
		try (Stream<Path> paths = Files.walk(root)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		}
	}

	private String portablePath(Path path) {
		return path.toString().replace(File.separatorChar, '/');
	}

	private void validateDestination(Path destination) throws IOException {
		if (Files.exists(destination)) {
			throw new IOException("SCORM export destination already exists: " + destination);
		}
		Path fileName = destination.getFileName();
		if (fileName == null || fileName.toString().isBlank()) {
			throw new IOException("SCORM export destination must name a ZIP file");
		}
		if (!fileName.toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
			throw new IOException("SCORM export destination must use the .zip extension: " + destination);
		}
	}
}
