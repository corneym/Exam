package au.edu.eq.questionbank.service.backup;

import java.time.Instant;
import java.util.List;

/**
 * Versioned metadata describing a backup archive.
 *
 * @param formatVersion         backup package format version
 * @param kind                  kind of backup
 * @param createdAt             creation timestamp
 * @param databaseSchemaVersion schema version stored in the database snapshot
 * @param applicationVersion    application version that created the backup
 */
public record BackupManifest(int formatVersion, BackupKind kind, Instant createdAt, int databaseSchemaVersion,
		String applicationVersion) {

	/**
	 * Current Subject-first backup-package format version.
	 * <p>
	 * Format 2 replaces the format-1 full-backup {@code pdf/} and
	 * {@code curriculum/} roots with one {@code subjects/} tree. Format 1 is
	 * deliberately unsupported after the Sprint 14 compatibility boundary.
	 */
	public static final int CURRENT_FORMAT_VERSION = 2;

	/**
	 * Validates backup manifest metadata.
	 */
	public BackupManifest {
		if (formatVersion < 1) {
			throw new IllegalArgumentException("formatVersion must be positive");
		}
		if (kind == null) {
			throw new NullPointerException("kind");
		}
		if (createdAt == null) {
			throw new NullPointerException("createdAt");
		}
		if (databaseSchemaVersion < 1) {
			throw new IllegalArgumentException("databaseSchemaVersion must be positive");
		}
		if (applicationVersion == null) {
			throw new NullPointerException("applicationVersion");
		}
		if (applicationVersion.isBlank()) {
			throw new IllegalArgumentException("applicationVersion must not be blank");
		}
		applicationVersion = applicationVersion.trim();
	}

	/**
	 * Creates manifest metadata using the current Subject-first backup format.
	 *
	 * @param kind                  backup kind
	 * @param createdAt             creation timestamp
	 * @param databaseSchemaVersion database schema version
	 * @param applicationVersion    application version
	 * @return current-format manifest metadata
	 */
	public static BackupManifest current(BackupKind kind, Instant createdAt, int databaseSchemaVersion,
			String applicationVersion) {
		return new BackupManifest(CURRENT_FORMAT_VERSION, kind, createdAt, databaseSchemaVersion, applicationVersion);
	}

	/**
	 * Returns the archive entries required by this manifest's backup kind.
	 *
	 * @return immutable required-entry list
	 */
	public List<String> expectedArchiveEntries() {
		return kind.requiredArchiveEntries();
	}
}
