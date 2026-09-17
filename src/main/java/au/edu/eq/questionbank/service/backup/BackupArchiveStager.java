package au.edu.eq.questionbank.service.backup;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class BackupArchiveStager {

	void stage(Path archivePath, Path stagingRoot) throws IOException, BackupFormatException {
		try (ZipFile archive = new ZipFile(archivePath.toFile())) {
			Enumeration<? extends ZipEntry> entries = archive.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				Path destination = resolveDestination(stagingRoot, entry.getName());
				if (entry.isDirectory()) {
					Files.createDirectories(destination);
					continue;
				}
				Path parent = destination.getParent();
				if (parent != null) {
					Files.createDirectories(parent);
				}
				try (InputStream input = archive.getInputStream(entry)) {
					Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
	}

	private Path resolveDestination(Path stagingRoot, String entryName) throws BackupFormatException {
		Path normalisedRoot = stagingRoot.toAbsolutePath().normalize();

		// Recheck containment at extraction even though the archive has already been
		// validated.
		Path destination = normalisedRoot.resolve(entryName).normalize();
		if (!destination.startsWith(normalisedRoot)) {
			throw new BackupFormatException("Unsafe backup archive entry: " + entryName);
		}
		return destination;
	}
}
