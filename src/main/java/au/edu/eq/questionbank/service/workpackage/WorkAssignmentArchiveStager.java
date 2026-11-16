package au.edu.eq.questionbank.service.workpackage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Objects;
import java.util.stream.Stream;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.ApplicationVersion;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

/**
 * Creates a private, verified copy of a contributor's whole-Exam assignment.
 *
 * <p>
 * Staging is distinct from coordinator Subject-import staging. It neither
 * publishes managed files nor writes domain records. A package-specific
 * directory is never reused silently after an interrupted installation.
 *
 * <p>
 * The copied archive, rather than the external source, is authoritative for
 * later asset extraction and graph installation.
 */
public final class WorkAssignmentArchiveStager {

	private static final String STAGING_DIRECTORY = ".assignment-install-staging";
	private final ApplicationConfig config;

	/**
	 * Creates a contributor assignment archive stager.
	 *
	 * @param config contributor application's filesystem configuration
	 * @throws NullPointerException if config is null
	 */
	public WorkAssignmentArchiveStager(ApplicationConfig config) {
		this.config = Objects.requireNonNull(config, "config");
	}

	/**
	 * Discards a private stage before any publication has begun.
	 *
	 * <p>
	 * This method is not a crash-recovery or publication rollback API. A later
	 * publisher must retain staged evidence while its journal requires recovery.
	 *
	 * @param staged stage belonging to this configured data root
	 * @throws IOException          if stage ownership is invalid or deletion fails
	 * @throws NullPointerException if staged is null
	 */
	public void discard(StagedAssignment staged) throws IOException {
		Objects.requireNonNull(staged, "staged");
		Path expected = stagingRoot().resolve(staged.inspection().manifest().packageId().toString());
		if (!expected.equals(staged.directory()) || !staged.archive().equals(expected.resolve("incoming.examwork"))) {
			throw new IOException("Cannot discard a foreign assignment stage");
		}
		if (Files.exists(expected.resolve("publication.properties"), LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Assignment stage has publication evidence");
		}
		deleteDirectory(expected);
	}

	/**
	 * Copies and independently validates a WORK_ASSIGNMENT archive.
	 *
	 * <p>
	 * The source is inspected before copying. The private copy is inspected again
	 * and must have the same complete SHA-256 and manifest.
	 *
	 * <p>
	 * An existing staging directory for the package is an error. It must first be
	 * resolved by the future installation recovery workflow.
	 *
	 * @param incomingArchive external assignment archive
	 * @return independently validated private staging evidence
	 * @throws IOException          if validation, copying, directory safety or
	 *                              archive consistency fails
	 * @throws NullPointerException if incomingArchive is null
	 */
	public StagedAssignment stage(Path incomingArchive) throws IOException {
		Objects.requireNonNull(incomingArchive, "incomingArchive");
		Path source = incomingArchive.toAbsolutePath().normalize();
		if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Assignment source must be an ordinary file");
		}
		WorkAssignmentPackageInspector inspector = new WorkAssignmentPackageInspector();
		var original = inspector.inspect(source, ApplicationVersion.current(), SqliteDatabase.latestSchemaVersion());
		Path directory = stagingRoot().resolve(original.manifest().packageId().toString());

		// CREATE_DIRECTORY fails rather than reusing incomplete evidence.
		Files.createDirectory(directory);
		try {
			Path copy = directory.resolve("incoming.examwork");
			Files.copy(source, copy, LinkOption.NOFOLLOW_LINKS);
			if (!Files.isRegularFile(copy, LinkOption.NOFOLLOW_LINKS)) {
				throw new IOException("Private assignment copy is not a regular file");
			}
			var verified = inspector.inspect(copy, ApplicationVersion.current(), SqliteDatabase.latestSchemaVersion());

			// Detect source replacement between the two inspections.
			if (!original.archiveSha256().equals(verified.archiveSha256())
					|| !original.manifest().equals(verified.manifest())) {
				throw new IOException("Assignment changed while staging");
			}
			return new StagedAssignment(directory, copy, verified);
		} catch (IOException | RuntimeException failure) {
			try {
				deleteDirectory(directory);
			} catch (IOException cleanupFailure) {
				failure.addSuppressed(cleanupFailure);
			}
			throw failure;
		}
	}

	private void deleteDirectory(Path directory) throws IOException {
		if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Assignment staging directory is unsafe");
		}
		try (Stream<Path> paths = Files.walk(directory)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(path);
			}
		}
	}

	private Path stagingRoot() throws IOException {
		Path root = config.dataRoot();
		if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Contributor data root is not an ordinary directory");
		}
		Path staging = root.resolve(STAGING_DIRECTORY);
		if (!Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
			Files.createDirectory(staging);
		}
		if (!Files.isDirectory(staging, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Contributor staging root is unsafe");
		}
		return staging;
	}

	/**
	 * Private, validated evidence for one assignment package.
	 *
	 * @param directory  private package staging directory
	 * @param archive    verified private archive copy
	 * @param inspection complete validated assignment inspection
	 */
	public record StagedAssignment(Path directory, Path archive, WorkAssignmentPackageInspector.Inspection inspection) {

		/**
		 * Validates and normalises staged paths and inspection metadata.
		 *
		 * @throws NullPointerException     if a required argument is null
		 * @throws IllegalArgumentException if the archive is outside its staging
		 *                                  directory
		 */
		public StagedAssignment {
			Objects.requireNonNull(directory, "directory");
			Objects.requireNonNull(archive, "archive");
			Objects.requireNonNull(inspection, "inspection");
			directory = directory.toAbsolutePath().normalize();
			archive = archive.toAbsolutePath().normalize();
			if (!archive.equals(directory.resolve("incoming.examwork"))) {
				throw new IllegalArgumentException("Unexpected staged assignment archive path");
			}
		}
	}
}
