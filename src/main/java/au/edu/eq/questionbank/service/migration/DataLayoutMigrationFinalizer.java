package au.edu.eq.questionbank.service.migration;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

/**
 * Finalises a published Subject-first migration by preserving the former
 * managed roots in a recovery archive and removing their old active locations.
 */
public final class DataLayoutMigrationFinalizer {

	private static final String ARCHIVE_DIRECTORY = "migration-archive";
	private static final String LEGACY_LAYOUT_DIRECTORY = "pre-subject-first";
	private final ApplicationConfig config;
	private final SqliteDatabase database;
	private final ManagedDataLayout managedDataLayout;

	/**
	 * Creates a migration finaliser.
	 *
	 * @param config   application paths
	 * @param database current database
	 */
	public DataLayoutMigrationFinalizer(ApplicationConfig config, SqliteDatabase database) {
		if (config == null) {
			throw new NullPointerException("config");
		}
		if (database == null) {
			throw new NullPointerException("database");
		}
		this.config = config;
		this.database = database;
		managedDataLayout = new ManagedDataLayout(config.dataRoot());
	}

	/**
	 * Verifies that no persisted managed reference depends on a legacy root, then
	 * preserves those roots in a recovery archive.
	 *
	 * @return archive operation counts
	 * @throws IOException  if archive publication or cleanup fails
	 * @throws SQLException if persistence cannot be verified
	 */
	public DataLayoutMigrationFinalizationResult finalizeMigration() throws IOException, SQLException {
		verifyPublishedReferences();
		Path archiveRoot = config.dataRoot().resolve(ARCHIVE_DIRECTORY).resolve(LEGACY_LAYOUT_DIRECTORY)
				.toAbsolutePath().normalize();
		ArchiveCounts pdfCounts = archiveLegacyRoot(config.pdfDataRoot(), archiveRoot.resolve("pdf"));
		ArchiveCounts curriculumCounts = archiveLegacyRoot(config.curriculumDataRoot(),
				archiveRoot.resolve("curriculum"));
		verifyPublishedReferences();
		return new DataLayoutMigrationFinalizationResult(pdfCounts.copied() + curriculumCounts.copied(),
				pdfCounts.reused() + curriculumCounts.reused());
	}

	private boolean archiveFile(Path source, Path destination) throws IOException {
		if (Files.exists(destination)) {
			if (!Files.isRegularFile(destination)) {
				throw new IOException("Migration archive destination is not a regular file: " + destination);
			}
			if (Files.mismatch(source, destination) != -1) {
				throw new IOException("Migration archive contains different bytes: " + destination);
			}
			return true;
		}
		Path parent = destination.getParent();
		if (parent == null) {
			throw new IOException("Migration archive destination has no parent: " + destination);
		}
		Files.createDirectories(parent);
		Path temporary = parent.resolve(".archive-" + UUID.randomUUID() + ".tmp");
		try {
			Files.copy(source, temporary);
			if (Files.mismatch(source, temporary) != -1) {
				throw new IOException("Migration archive copy failed verification: " + destination);
			}
			try {
				Files.move(temporary, destination);
			} catch (FileAlreadyExistsException race) {
				Files.deleteIfExists(temporary);
				if (!Files.isRegularFile(destination) || Files.mismatch(source, destination) != -1) {
					throw new IOException("Concurrent migration archive destination differs: " + destination);
				}
				return true;
			}
			return false;
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	private ArchiveCounts archiveLegacyRoot(Path sourceRoot, Path destinationRoot) throws IOException {
		Path source = sourceRoot.toAbsolutePath().normalize();
		Path destination = destinationRoot.toAbsolutePath().normalize();
		if (!Files.exists(source)) {
			return new ArchiveCounts(0, 0);
		}
		if (Files.isSymbolicLink(source)) {
			throw new IOException("Legacy managed root is a symbolic link: " + source);
		}
		List<Path> paths;
		try (var walk = Files.walk(source)) {
			paths = walk.sorted(Comparator.comparingInt(Path::getNameCount)).toList();
		}
		int copied = 0;
		int reused = 0;
		for (Path path : paths) {
			if (Files.isSymbolicLink(path)) {
				throw new IOException("Legacy managed data contains a symbolic link: " + path);
			}
			Path relative = source.relativize(path);
			Path target = destination.resolve(relative).normalize();
			if (!target.startsWith(destination)) {
				throw new IOException("Legacy archive destination escaped its root: " + target);
			}
			if (Files.isDirectory(path)) {
				Files.createDirectories(target);
				continue;
			}
			if (!Files.isRegularFile(path)) {
				throw new IOException("Unsupported legacy filesystem entry: " + path);
			}
			if (archiveFile(path, target)) {
				reused++;
			} else {
				copied++;
			}
		}

		// Delete the former active tree only after every entry has been copied or
		// verified in the recovery archive.
		List<Path> deleteOrder = paths.stream().sorted(Comparator.comparingInt(Path::getNameCount).reversed()).toList();
		for (Path path : deleteOrder) {
			Files.delete(path);
		}
		return new ArchiveCounts(copied, reused);
	}

	private void verifyPublishedReferences() throws SQLException {
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			try (ResultSet result = statement.executeQuery("""
					SELECT id, relative_path
					FROM source_documents
					ORDER BY id
					""")) {
				while (result.next()) {
					verifySubjectFirstReference("SourceDocument " + result.getLong("id"),
							result.getString("relative_path"));
				}
			}
			try (ResultSet result = statement.executeQuery("""
					SELECT id, source_pdf_path
					FROM syllabus_versions
					WHERE source_pdf_path IS NOT NULL
					ORDER BY id
					""")) {
				while (result.next()) {
					verifySubjectFirstReference("Syllabus " + result.getLong("id"),
							result.getString("source_pdf_path"));
				}
			}
		}
	}

	private void verifySubjectFirstReference(String description, String relativePath) {
		String portable = relativePath.replace('\\', '/');
		if (!portable.startsWith("subjects/")) {
			throw new IllegalStateException(description + " still depends on legacy path semantics: " + relativePath);
		}
		Path resolved = managedDataLayout.resolve(portable);
		if (!resolved.startsWith(managedDataLayout.subjectsRoot())) {
			throw new IllegalStateException(description + " resolves outside the Subject-first tree");
		}
		if (!Files.isRegularFile(resolved)) {
			throw new IllegalStateException(description + " resolves to a missing managed file: " + resolved);
		}
	}

	private record ArchiveCounts(int copied, int reused) {
	}
}
