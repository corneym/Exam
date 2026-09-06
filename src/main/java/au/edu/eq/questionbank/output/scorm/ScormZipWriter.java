package au.edu.eq.questionbank.output.scorm;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes a validated SCORM package directory as a portable ZIP archive.
 */
public final class ScormZipWriter {

	private List<Path> collectPackageFiles(Path root) throws IOException {
		List<Path> discoveredPaths;

		try (Stream<Path> stream = Files.walk(root)) {
			discoveredPaths = stream.toList();
		}

		List<Path> packageFiles = new ArrayList<Path>();
		Set<String> entryNames = new HashSet<String>();

		for (Path path : discoveredPaths) {
			if (Files.isSymbolicLink(path)) {
				throw new IOException("SCORM package must not contain symbolic links: " + path);
			}

			if (Files.isDirectory(path)) {
				continue;
			}

			if (!Files.isRegularFile(path)) {
				throw new IOException("SCORM package contains a non-regular file: " + path);
			}

			String entryName = toEntryName(root, path);

			if (!entryNames.add(entryName)) {
				throw new IOException("Duplicate SCORM ZIP entry: " + entryName);
			}

			packageFiles.add(path);
		}

		packageFiles.sort(Comparator.comparing(path -> {
			try {
				return toEntryName(root, path);
			} catch (IOException exception) {
				throw new IllegalStateException(exception);
			}
		}));

		return packageFiles;
	}

	private void promote(Path stagingZip, Path destination) throws IOException {
		try {
			Files.move(stagingZip, destination, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException exception) {
			Files.move(stagingZip, destination);
		}
	}

	private String toEntryName(Path root, Path file) throws IOException {
		Path relativePath = root.relativize(file).normalize();

		if (relativePath.getNameCount() == 0 || relativePath.startsWith("..")) {
			throw new IOException("SCORM package file is outside the package root: " + file);
		}

		String entryName = relativePath.toString().replace(File.separatorChar, '/');

		if (entryName.isBlank() || entryName.startsWith("/") || entryName.contains("\\")) {
			throw new IOException("Invalid SCORM ZIP entry path: " + entryName);
		}

		return entryName;
	}

	/**
	 * Writes all regular package files to a deterministic staging archive and
	 * publishes it only after the archive closes successfully.
	 *
	 * @param packageRoot validated package directory
	 * @param destinationZip final ZIP path, which must not already exist
	 * @return the published ZIP path
	 * @throws IOException if the package is unsafe or the archive cannot be written
	 *                     or published
	 * @throws NullPointerException if either path is null
	 */
	public Path write(Path packageRoot, Path destinationZip) throws IOException {
		if (packageRoot == null) {
			throw new NullPointerException("packageRoot");
		}

		if (destinationZip == null) {
			throw new NullPointerException("destinationZip");
		}

		Path root = packageRoot.toAbsolutePath().normalize();
		Path destination = destinationZip.toAbsolutePath().normalize();

		if (!Files.isDirectory(root)) {
			throw new IOException("SCORM package root does not exist: " + root);
		}

		if (Files.isSymbolicLink(root)) {
			throw new IOException("SCORM package root must not be a symbolic link: " + root);
		}

		if (destination.startsWith(root)) {
			throw new IOException("SCORM ZIP destination must not be inside the package root: " + destination);
		}

		if (Files.exists(destination)) {
			throw new IOException("SCORM ZIP destination already exists: " + destination);
		}

		List<Path> packageFiles = collectPackageFiles(root);

		if (packageFiles.isEmpty()) {
			throw new IOException("SCORM package contains no files: " + root);
		}

		Path destinationParent = destination.getParent();
		Files.createDirectories(destinationParent);

		Path stagingZip = Files.createTempFile(destinationParent, "scorm-", ".staging.zip");

		boolean promoted = false;

		try {
			writeArchive(root, packageFiles, stagingZip);
			promote(stagingZip, destination);
			promoted = true;

			return destination;
		} finally {
			if (!promoted) {
				Files.deleteIfExists(stagingZip);
			}
		}
	}

	private void writeArchive(Path root, List<Path> packageFiles, Path stagingZip) throws IOException {
		try (ZipOutputStream output = new ZipOutputStream(
				Files.newOutputStream(stagingZip, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE),
				StandardCharsets.UTF_8)) {

			for (Path packageFile : packageFiles) {
				String entryName = toEntryName(root, packageFile);

				ZipEntry entry = new ZipEntry(entryName);

				/*
				 * Do not copy filesystem timestamps into the package. A fixed timestamp makes
				 * repeated generation from identical files deterministic.
				 */
				entry.setTime(0L);

				output.putNextEntry(entry);
				Files.copy(packageFile, output);
				output.closeEntry();
			}
		}
	}
}
