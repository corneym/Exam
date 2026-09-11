package au.edu.eq.questionbank.service.backup;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Represents a validated backup that has been safely staged for restoration.
 * <p>
 * Closing a preparation discards its staged files without changing the current
 * question-bank data.
 */
public final class RestorePreparation implements AutoCloseable {

	private final Path backupPath;
	private final BackupManifest manifest;
	private final Path stagingRoot;

	RestorePreparation(Path backupPath, BackupManifest manifest, Path stagingRoot) {
		if (backupPath == null) {
			throw new NullPointerException("backupPath");
		}
		if (manifest == null) {
			throw new NullPointerException("manifest");
		}
		if (stagingRoot == null) {
			throw new NullPointerException("stagingRoot");
		}
		this.backupPath = backupPath.toAbsolutePath().normalize();
		this.manifest = manifest;
		this.stagingRoot = stagingRoot.toAbsolutePath().normalize();
	}

	/**
	 * Returns the original backup archive.
	 *
	 * @return normalised absolute backup path
	 */
	public Path backupPath() {
		return backupPath;
	}

	@Override
	public void close() throws IOException {
		RestoreFiles.deleteTree(stagingRoot);
	}

	/**
	 * Returns the validated backup manifest.
	 *
	 * @return backup manifest
	 */
	public BackupManifest manifest() {
		return manifest;
	}

	Path curriculumRoot() {
		return stagingRoot.resolve("curriculum");
	}

	Path databasePath() {
		return stagingRoot.resolve(BackupArchiveLayout.DATABASE_ENTRY);
	}

	Path pdfRoot() {
		return stagingRoot.resolve("pdf");
	}

	Path stagingRoot() {
		return stagingRoot;
	}

}
