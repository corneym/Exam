package au.edu.eq.questionbank.service.backup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

import au.edu.eq.questionbank.ApplicationConfig;

final class BackupFilesystemSafety {

	void validateApplicationLayout(ApplicationConfig config) throws IOException {

		// Compare physical locations so path aliases cannot conceal overlapping managed
		// roots.
		Path pdfRoot = resolvePhysicalPath(config.pdfDataRoot());
		Path curriculumRoot = resolvePhysicalPath(config.curriculumDataRoot());
		Path databasePath = resolvePhysicalPath(config.databasePath());
		Path dataRoot = resolvePhysicalPath(config.dataRoot());
		Path backupRoot = resolvePhysicalPath(config.dataRoot().resolve("backups"));
		rejectOverlap(pdfRoot, curriculumRoot, "PDF and curriculum roots must not overlap");
		rejectContainingPath(pdfRoot, databasePath, "Database must not be inside the managed PDF hierarchy");
		rejectContainingPath(curriculumRoot, databasePath,
				"Database must not be inside the managed curriculum hierarchy");
		rejectContainingPath(pdfRoot, dataRoot, "Managed PDF hierarchy must not contain the application data root");
		rejectContainingPath(curriculumRoot, dataRoot,
				"Managed curriculum hierarchy must not contain the application data root");
		rejectOverlap(pdfRoot, backupRoot, "Managed PDF hierarchy must not overlap the backup hierarchy");
		rejectOverlap(curriculumRoot, backupRoot, "Managed curriculum hierarchy must not overlap the backup hierarchy");
	}

	void validateBackupDestination(ApplicationConfig config, Path destination) throws IOException {
		validateApplicationLayout(config);
		Path resolvedDestination = resolvePhysicalPath(destination);
		Path pdfRoot = resolvePhysicalPath(config.pdfDataRoot());
		Path curriculumRoot = resolvePhysicalPath(config.curriculumDataRoot());
		if (isWithin(resolvedDestination, pdfRoot)) {
			throw new IOException("Backup destination must not be inside the managed PDF hierarchy");
		}
		if (isWithin(resolvedDestination, curriculumRoot)) {
			throw new IOException("Backup destination must not be inside the managed curriculum hierarchy");
		}
	}

	private boolean isWithin(Path candidate, Path root) {
		return candidate.startsWith(root);
	}

	private void rejectContainingPath(Path container, Path contained, String message) throws IOException {
		if (contained.startsWith(container)) {
			throw new IOException(message);
		}
	}

	private void rejectOverlap(Path first, Path second, String message) throws IOException {
		if (first.startsWith(second) || second.startsWith(first)) {
			throw new IOException(message);
		}
	}

	private Path resolvePhysicalPath(Path path) throws IOException {
		Path normalised = path.toAbsolutePath().normalize();
		Path existing = normalised;
		Deque<Path> missingParts = new ArrayDeque<>();
		while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
			Path fileName = existing.getFileName();
			if (fileName != null) {
				missingParts.addFirst(fileName);
			}
			existing = existing.getParent();
		}
		if (existing == null) {
			return normalised;
		}

		// Resolve the nearest existing ancestor, then reattach components that have not
		// been created yet.
		Path resolved = existing.toRealPath();
		for (Path missingPart : missingParts) {
			resolved = resolved.resolve(missingPart);
		}
		return resolved.normalize();
	}
}
