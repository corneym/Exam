package au.edu.eq.questionbank.service.backup;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import au.edu.eq.questionbank.ApplicationConfig;

final class BackupArchiveWriter {

	private final BackupManifestCodec manifestCodec;

	BackupArchiveWriter(BackupManifestCodec manifestCodec) {
		if (manifestCodec == null) {
			throw new NullPointerException("manifestCodec");
		}
		this.manifestCodec = manifestCodec;
	}

	void write(Path archivePath, BackupManifest manifest, Path databaseSnapshot, ApplicationConfig config)
			throws IOException {
		try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archivePath))) {
			writeManifest(output, manifest);
			writeFile(output, BackupArchiveLayout.DATABASE_ENTRY, databaseSnapshot);
			if (manifest.kind() == BackupKind.FULL) {
				writeManagedTree(output, BackupArchiveLayout.PDF_DIRECTORY_ENTRY, config.pdfDataRoot());
				writeManagedTree(output, BackupArchiveLayout.CURRICULUM_DIRECTORY_ENTRY, config.curriculumDataRoot());
			}
		}
	}

	private void writeDirectoryEntry(ZipOutputStream output, String entryName) throws IOException {
		ZipEntry entry = new ZipEntry(entryName);
		output.putNextEntry(entry);
		output.closeEntry();
	}

	private void writeFile(ZipOutputStream output, String entryName, Path sourcePath) throws IOException {
		ZipEntry entry = new ZipEntry(entryName);
		output.putNextEntry(entry);
		Files.copy(sourcePath, output);
		output.closeEntry();
	}

	private void writeManagedTree(ZipOutputStream output, String archiveRoot, Path sourceRoot) throws IOException {
		writeDirectoryEntry(output, archiveRoot);
		if (!Files.exists(sourceRoot)) {
			return;
		}
		if (!Files.isDirectory(sourceRoot)) {
			throw new IOException("Managed data root is not a directory: " + sourceRoot);
		}
		List<Path> paths;
		try (Stream<Path> stream = Files.walk(sourceRoot)) {
			paths = stream.sorted().toList();
		}
		for (Path path : paths) {
			if (path.equals(sourceRoot)) {
				continue;
			}
			if (Files.isSymbolicLink(path)) {
				throw new IOException("Managed data contains a symbolic link: " + path);
			}
			Path relativePath = sourceRoot.relativize(path);
			String relativeName = relativePath.toString().replace(File.separatorChar, '/');
			String archiveName = archiveRoot + relativeName;
			if (Files.isDirectory(path)) {
				writeDirectoryEntry(output, archiveName + "/");
			} else if (Files.isRegularFile(path)) {
				writeFile(output, archiveName, path);
			} else {
				throw new IOException("Unsupported managed data entry: " + path);
			}
		}
	}

	private void writeManifest(ZipOutputStream output, BackupManifest manifest) throws IOException {
		ZipEntry entry = new ZipEntry(BackupArchiveLayout.MANIFEST_ENTRY);
		output.putNextEntry(entry);
		manifestCodec.write(manifest, output);
		output.closeEntry();
	}
}
