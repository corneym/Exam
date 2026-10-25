package au.edu.eq.questionbank.diagnostics;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Exports and merges machine-specific historical summaries.
 *
 * Existing differing files are never overwritten.
 */
public final class PerformanceHistoryTransfer {

	private static final long MAX_ARCHIVE_BYTES = 250L * 1024 * 1024;
	private static final long MAX_ENTRY_BYTES = 32L * 1024 * 1024;
	private static final int MAX_ENTRIES = 10000;
	private static final Pattern CHECKPOINT_FILE = Pattern.compile("checkpoint-[0-9]{6}\\.csv");

	/**
	 * Creates a utility for exporting and importing permanent performance-history
	 * archives.
	 */
	public PerformanceHistoryTransfer() {
	}

	private static boolean validRelativePath(Path relative) {
		if (relative.isAbsolute() || relative.getNameCount() != 3) {
			return false;
		}
		try {
			String machine = relative.getName(0).toString();
			String session = relative.getName(1).toString();
			if (!UUID.fromString(machine).toString().equals(machine)
					|| !UUID.fromString(session).toString().equals(session)) {
				return false;
			}
			String name = relative.getFileName().toString();
			return name.equals("corpus-checkpoints.csv") || name.equals("session.summary-complete")
					|| name.equals(PerformanceHistoryIntegrity.MANIFEST_NAME)
					|| CHECKPOINT_FILE.matcher(name).matches();
		} catch (IllegalArgumentException invalid) {
			return false;
		}
	}

	/**
	 * Exports completed historical summaries to a new ZIP archive.
	 *
	 * @param historyRoot permanent history directory
	 * @param archive     destination ZIP path, which must not exist
	 * @return number of exported files
	 * @throws IOException if export fails
	 */
	public int exportArchive(Path historyRoot, Path archive) throws IOException {
		if (!Files.isDirectory(historyRoot)) {
			throw new IOException("No historical summaries to export");
		}
		List<Path> files = new ArrayList<>();
		try (Stream<Path> candidates = Files.walk(historyRoot, 3)) {
			for (Path file : candidates.filter(Files::isRegularFile).toList()) {
				Path relative = historyRoot.relativize(file);
				if (validRelativePath(relative)
						&& Files.isRegularFile(file.getParent().resolve("session.summary-complete"))) {
					files.add(file);
				}
			}
		}
		if (files.isEmpty()) {
			throw new IOException("No completed historical sessions to export");
		}
		files.sort(Comparator.comparing(p -> historyRoot.relativize(p).toString()));
		Path parent = archive.toAbsolutePath().getParent();
		Files.createDirectories(parent);
		boolean created = false;
		try {
			OutputStream output = Files.newOutputStream(archive, StandardOpenOption.CREATE_NEW);
			created = true;
			try (ZipOutputStream zip = new ZipOutputStream(output)) {
				for (Path file : files) {
					String name = historyRoot.relativize(file).toString().replace('\\', '/');
					zip.putNextEntry(new ZipEntry(name));
					Files.copy(file, zip);
					zip.closeEntry();
				}
			}
			return files.size();
		} catch (IOException | RuntimeException failure) {
			if (created) {
				try {
					Files.deleteIfExists(archive);
				} catch (IOException cleanupFailure) {
					failure.addSuppressed(cleanupFailure);
				}
			}
			throw failure;
		}
	}

	/**
	 * Imports a historical archive without replacing differing files. Identical
	 * duplicates are accepted, allowing repeated transfers.
	 *
	 * @param historyRoot permanent history directory
	 * @param archive     previously exported ZIP archive
	 * @return number of newly imported files
	 * @throws IOException if validation or import fails
	 */
	public int importArchive(Path historyRoot, Path archive) throws IOException {
		Path stage = Files.createTempDirectory("eqb-history-import-");
		Path normalizedStage = stage.normalize();
		try {
			long totalBytes = 0;
			int entries = 0;
			try (InputStream input = Files.newInputStream(archive); ZipInputStream zip = new ZipInputStream(input)) {
				ZipEntry entry;
				while ((entry = zip.getNextEntry()) != null) {
					if (entry.isDirectory()) {
						throw new IOException("Unexpected archive directory");
					}
					Path relative = Path.of(entry.getName());
					if (relative.isAbsolute() || !validRelativePath(relative) || ++entries > MAX_ENTRIES) {
						throw new IOException("Invalid history archive entry");
					}
					Path prepared = normalizedStage.resolve(relative).normalize();
					if (!prepared.startsWith(normalizedStage)) {
						throw new IOException("Invalid history archive entry");
					}
					Files.createDirectories(prepared.getParent());
					long size = 0;
					try (OutputStream out = Files.newOutputStream(prepared, StandardOpenOption.CREATE_NEW)) {
						byte[] buffer = new byte[8192];
						int count;
						while ((count = zip.read(buffer)) != -1) {
							size += count;
							totalBytes += count;
							if (size > MAX_ENTRY_BYTES || totalBytes > MAX_ARCHIVE_BYTES) {
								throw new IOException("History archive too large");
							}
							out.write(buffer, 0, count);
						}
					}
					zip.closeEntry();
				}
			}
			if (entries == 0) {
				throw new IOException("History archive is empty");
			}

			// Validate the staged sessions before importing anything.
			try (Stream<Path> directories = Files.walk(stage, 2)) {
				for (Path session : directories.filter(Files::isDirectory)
						.filter(dir -> stage.relativize(dir).getNameCount() == 2).toList()) {
					if (!Files.isRegularFile(session.resolve("session.summary-complete"))
							|| !Files.isRegularFile(session.resolve("corpus-checkpoints.csv"))) {
						throw new IOException("Incomplete historical session archive");
					}

					// A modern archive's manifest must verify before any file
					// is copied into the permanent history. Older archives without
					// this optional manifest remain importable.
					if (Files.exists(session.resolve(PerformanceHistoryIntegrity.MANIFEST_NAME))) {
						new PerformanceHistoryIntegrity().verify(session);
					}
				}
			}
			List<Path> files;
			try (Stream<Path> candidates = Files.walk(stage)) {
				files = candidates.filter(Files::isRegularFile)
						.sorted(Comparator.comparing(
								(Path file) -> file.getFileName().toString().equals("session.summary-complete")))
						.toList();
			}
			int imported = 0;
			for (Path source : files) {
				Path destination = historyRoot.resolve(stage.relativize(source));
				Files.createDirectories(destination.getParent());
				if (!Files.exists(destination)) {
					try {
						Files.copy(source, destination);
						imported++;
					} catch (java.nio.file.FileAlreadyExistsException concurrent) {

						// Reconcile a concurrent import by comparing
						// the actual file contents.
					}
				}
				if (!Files.isRegularFile(destination) || Files.mismatch(source, destination) != -1) {
					throw new IOException("Conflicting history file: " + destination.getFileName());
				}
			}
			return imported;
		} finally {
			try (Stream<Path> paths = Files.walk(stage)) {
				for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
					Files.deleteIfExists(path);
				}
			}
		}
	}
}
