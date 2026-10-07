package au.edu.eq.questionbank.service.backup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.ManagedDataLayout;

final class BackupFilesystemSafety {

	void validateApplicationLayout(ApplicationConfig config) throws IOException {
		ManagedDataLayout layout = new ManagedDataLayout(config.dataRoot());
		Path subjectsRoot = resolvePhysicalPath(layout.subjectsRoot());
		Path databasePath = resolvePhysicalPath(config.databasePath());
		Path dataRoot = resolvePhysicalPath(config.dataRoot());
		Path backupRoot = resolvePhysicalPath(config.dataRoot().resolve("backups"));

		// The database and backup hierarchy are application-level data and must
		// remain outside the portable Subject package.
		rejectContainingPath(subjectsRoot, databasePath, "Database must not be inside the managed Subject hierarchy");
		rejectContainingPath(subjectsRoot, dataRoot,
				"Managed Subject hierarchy must not contain the application data root");
		rejectOverlap(subjectsRoot, backupRoot, "Managed Subject hierarchy must not overlap the backup hierarchy");
	}

	void validateBackupDestination(ApplicationConfig config, Path destination) throws IOException {
		validateApplicationLayout(config);
		ManagedDataLayout layout = new ManagedDataLayout(config.dataRoot());
		Path resolvedDestination = resolvePhysicalPath(destination);
		Path subjectsRoot = resolvePhysicalPath(layout.subjectsRoot());
		if (isWithin(resolvedDestination, subjectsRoot)) {
			throw new IOException("Backup destination must not be inside the managed Subject hierarchy");
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

		// Resolve the nearest existing ancestor, then reattach components that have
		// not yet been created. This prevents symlink aliases from hiding overlap.
		Path resolved = existing.toRealPath();
		for (Path missingPart : missingParts) {
			resolved = resolved.resolve(missingPart);
		}
		return resolved.normalize();
	}
}
