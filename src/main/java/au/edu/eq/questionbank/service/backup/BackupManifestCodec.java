package au.edu.eq.questionbank.service.backup;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Reads and writes the versioned properties manifest stored in a backup
 * archive.
 * <p>
 * Version 1 uses a deterministic UTF-8 properties representation without the
 * timestamp comment normally added by {@link Properties#store}.
 */
public final class BackupManifestCodec {

	/**
	 * Creates a codec for the backup manifest properties format.
	 */
	public BackupManifestCodec() {
	}

	private static final String APPLICATION_VERSION_PROPERTY = "application.version";
	private static final String ARCHIVE_ENTRIES_PROPERTY = "archive.entries";
	private static final String BACKUP_KIND_PROPERTY = "backup.kind";
	private static final String CREATED_AT_PROPERTY = "created.at";
	private static final String DATABASE_SCHEMA_VERSION_PROPERTY = "database.schema.version";
	private static final String FORMAT_VERSION_PROPERTY = "backup.format.version";

	/**
	 * Reads and validates a backup manifest.
	 *
	 * @param input manifest input
	 * @return validated manifest metadata
	 * @throws IOException           if the manifest cannot be read
	 * @throws BackupFormatException if required metadata is missing, malformed,
	 *                               inconsistent, or uses an unsupported format
	 *                               version
	 * @throws NullPointerException  if {@code input} is {@code null}
	 */
	public BackupManifest read(InputStream input) throws IOException, BackupFormatException {
		if (input == null) {
			throw new NullPointerException("input");
		}
		Properties properties = new Properties();

		// Use UTF-8 while leaving stream ownership with the caller.
		Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8);
		properties.load(reader);
		int formatVersion = readPositiveInteger(properties, FORMAT_VERSION_PROPERTY);
		if (formatVersion != BackupManifest.CURRENT_FORMAT_VERSION) {
			throw new BackupFormatException("Unsupported backup format version " + formatVersion
					+ "; supported version is " + BackupManifest.CURRENT_FORMAT_VERSION);
		}
		BackupKind kind = readBackupKind(properties);
		Instant createdAt = readCreatedAt(properties);
		int databaseSchemaVersion = readPositiveInteger(properties, DATABASE_SCHEMA_VERSION_PROPERTY);
		String applicationVersion = readRequiredProperty(properties, APPLICATION_VERSION_PROPERTY);
		validateArchiveEntries(properties, kind);
		return new BackupManifest(formatVersion, kind, createdAt, databaseSchemaVersion, applicationVersion);
	}

	/**
	 * Writes a manifest using the current deterministic backup format.
	 * <p>
	 * The supplied output stream remains caller-owned and is not closed.
	 *
	 * @param manifest manifest metadata
	 * @param output   destination stream
	 * @throws IOException          if the manifest cannot be written
	 * @throws NullPointerException if either argument is {@code null}
	 */
	public void write(BackupManifest manifest, OutputStream output) throws IOException {
		if (manifest == null) {
			throw new NullPointerException("manifest");
		}
		if (output == null) {
			throw new NullPointerException("output");
		}
		if (manifest.formatVersion() != BackupManifest.CURRENT_FORMAT_VERSION) {
			throw new IllegalArgumentException(
					"Cannot write unsupported backup format version " + manifest.formatVersion());
		}

		// Write properties in a fixed order without an automatically generated comment
		// timestamp.
		StringBuilder content = new StringBuilder();
		appendProperty(content, FORMAT_VERSION_PROPERTY, Integer.toString(manifest.formatVersion()));
		appendProperty(content, BACKUP_KIND_PROPERTY, manifest.kind().name());
		appendProperty(content, CREATED_AT_PROPERTY, manifest.createdAt().toString());
		appendProperty(content, DATABASE_SCHEMA_VERSION_PROPERTY, Integer.toString(manifest.databaseSchemaVersion()));
		appendProperty(content, APPLICATION_VERSION_PROPERTY, manifest.applicationVersion());
		appendProperty(content, ARCHIVE_ENTRIES_PROPERTY, String.join(",", manifest.expectedArchiveEntries()));
		output.write(content.toString().getBytes(StandardCharsets.UTF_8));
	}

	private void appendProperty(StringBuilder content, String key, String value) {
		content.append(key).append('=').append(escapePropertyValue(value)).append('\n');
	}

	private String escapePropertyValue(String value) {

		// Escape backslashes and control characters so Properties.load reconstructs the
		// value.
		StringBuilder escaped = new StringBuilder();
		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			if (character == '\\') {
				escaped.append("\\\\");
			} else if (character == '\n') {
				escaped.append("\\n");
			} else if (character == '\r') {
				escaped.append("\\r");
			} else if (character == '\t') {
				escaped.append("\\t");
			} else {
				escaped.append(character);
			}
		}
		return escaped.toString();
	}

	private BackupKind readBackupKind(Properties properties) throws BackupFormatException {
		String value = readRequiredProperty(properties, BACKUP_KIND_PROPERTY);
		try {
			return BackupKind.valueOf(value);
		} catch (IllegalArgumentException e) {
			throw new BackupFormatException("Invalid backup kind: " + value, e);
		}
	}

	private Instant readCreatedAt(Properties properties) throws BackupFormatException {
		String value = readRequiredProperty(properties, CREATED_AT_PROPERTY);
		try {
			return Instant.parse(value);
		} catch (DateTimeParseException e) {
			throw new BackupFormatException("Invalid backup creation timestamp: " + value, e);
		}
	}

	private int readPositiveInteger(Properties properties, String propertyName) throws BackupFormatException {
		String value = readRequiredProperty(properties, propertyName);
		try {
			int number = Integer.parseInt(value);
			if (number < 1) {
				throw new BackupFormatException(propertyName + " must be positive");
			}
			return number;
		} catch (NumberFormatException e) {
			throw new BackupFormatException("Invalid integer property " + propertyName + ": " + value, e);
		}
	}

	private String readRequiredProperty(Properties properties, String propertyName) throws BackupFormatException {
		String value = properties.getProperty(propertyName);
		if (value == null || value.isBlank()) {
			throw new BackupFormatException("Missing required backup manifest property: " + propertyName);
		}
		return value.trim();
	}

	private void validateArchiveEntries(Properties properties, BackupKind kind) throws BackupFormatException {
		String value = readRequiredProperty(properties, ARCHIVE_ENTRIES_PROPERTY);
		String[] parts = value.split(",", -1);
		List<String> actualEntries = new ArrayList<>();
		for (String part : parts) {
			if (part.isBlank()) {
				throw new BackupFormatException("Backup manifest contains a blank archive entry");
			}
			actualEntries.add(part.trim());
		}

		// Require the declared layout to match the backup kind, including entry order.
		if (!actualEntries.equals(kind.requiredArchiveEntries())) {
			throw new BackupFormatException("Backup manifest archive entries do not match backup kind " + kind);
		}
	}
}
