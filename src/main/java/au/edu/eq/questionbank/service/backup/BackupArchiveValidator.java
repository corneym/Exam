package au.edu.eq.questionbank.service.backup;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

final class BackupArchiveValidator {

	private final BackupManifestCodec manifestCodec;

	BackupArchiveValidator(BackupManifestCodec manifestCodec) {
		if (manifestCodec == null) {
			throw new NullPointerException("manifestCodec");
		}
		this.manifestCodec = manifestCodec;
	}

	BackupManifest validate(Path archivePath) throws IOException, SQLException, BackupFormatException {
		if (archivePath == null) {
			throw new NullPointerException("archivePath");
		}
		if (!Files.isRegularFile(archivePath)) {
			throw new BackupFormatException("Backup archive does not exist: " + archivePath);
		}
		try (ZipFile archive = new ZipFile(archivePath.toFile())) {
			validateEntryNames(archive);
			BackupManifest manifest = readManifest(archive);
			validateRequiredEntries(archive, manifest);
			validateAllowedEntries(archive, manifest);
			validateDatabase(archive, manifest);
			return manifest;
		}
	}

	private BackupManifest readManifest(ZipFile archive) throws IOException, BackupFormatException {
		ZipEntry entry = archive.getEntry(BackupArchiveLayout.MANIFEST_ENTRY);
		if (entry == null || entry.isDirectory()) {
			throw new BackupFormatException("Backup manifest is missing");
		}
		try (InputStream input = archive.getInputStream(entry)) {
			return manifestCodec.read(input);
		}
	}

	private void validateAllowedEntries(ZipFile archive, BackupManifest manifest) throws BackupFormatException {
		Enumeration<? extends ZipEntry> entries = archive.entries();
		while (entries.hasMoreElements()) {
			String name = entries.nextElement().getName();
			if (BackupArchiveLayout.MANIFEST_ENTRY.equals(name) || BackupArchiveLayout.DATABASE_ENTRY.equals(name)) {
				continue;
			}
			if (manifest.kind() == BackupKind.FULL && (name.startsWith(BackupArchiveLayout.PDF_DIRECTORY_ENTRY)
					|| name.startsWith(BackupArchiveLayout.CURRICULUM_DIRECTORY_ENTRY))) {
				continue;
			}
			throw new BackupFormatException("Unexpected backup archive entry: " + name);
		}
	}

	private void validateDatabase(ZipFile archive, BackupManifest manifest)
			throws IOException, SQLException, BackupFormatException {
		ZipEntry databaseEntry = archive.getEntry(BackupArchiveLayout.DATABASE_ENTRY);
		if (databaseEntry == null || databaseEntry.isDirectory()) {
			throw new BackupFormatException("Backup database is missing");
		}
		Path temporaryDatabase = Files.createTempFile("question-bank-backup-validation-", ".db");
		try {
			try (InputStream input = archive.getInputStream(databaseEntry)) {
				Files.copy(input, temporaryDatabase, StandardCopyOption.REPLACE_EXISTING);
			}
			SqliteDatabase database = new SqliteDatabase(temporaryDatabase);
			int actualSchemaVersion = database.schemaVersion();
			if (actualSchemaVersion != manifest.databaseSchemaVersion()) {
				throw new BackupFormatException("Backup database schema version " + actualSchemaVersion
						+ " does not match manifest schema version " + manifest.databaseSchemaVersion());
			}
			database.verifySchema();
			database.verifyIntegrity();
		} finally {
			Files.deleteIfExists(temporaryDatabase);
		}
	}

	private void validateEntryNames(ZipFile archive) throws BackupFormatException {
		Set<String> names = new HashSet<>();
		Enumeration<? extends ZipEntry> entries = archive.entries();
		while (entries.hasMoreElements()) {
			ZipEntry entry = entries.nextElement();
			String name = entry.getName();
			validateSafeEntryName(name);
			if (!names.add(name)) {
				throw new BackupFormatException("Duplicate backup archive entry: " + name);
			}
		}
	}

	private void validateRequiredEntries(ZipFile archive, BackupManifest manifest) throws BackupFormatException {
		for (String requiredEntry : manifest.expectedArchiveEntries()) {
			ZipEntry entry = archive.getEntry(requiredEntry);
			if (entry == null) {
				throw new BackupFormatException("Backup archive is missing required entry: " + requiredEntry);
			}
			if (requiredEntry.endsWith("/") && !entry.isDirectory()) {
				throw new BackupFormatException("Backup archive entry must be a directory: " + requiredEntry);
			}
		}
	}

	private void validateSafeEntryName(String entryName) throws BackupFormatException {
		if (entryName == null || entryName.isBlank()) {
			throw new BackupFormatException("Backup archive contains a blank entry name");
		}
		if (entryName.startsWith("/") || entryName.contains("\\")) {
			throw new BackupFormatException("Unsafe backup archive entry: " + entryName);
		}
		String logicalName = entryName;
		if (logicalName.endsWith("/")) {
			logicalName = logicalName.substring(0, logicalName.length() - 1);
		}
		if (logicalName.isBlank()) {
			throw new BackupFormatException("Unsafe backup archive entry: " + entryName);
		}
		String[] parts = logicalName.split("/", -1);
		for (int index = 0; index < parts.length; index++) {
			String part = parts[index];
			if (part.isBlank() || ".".equals(part) || "..".equals(part)) {
				throw new BackupFormatException("Unsafe backup archive entry: " + entryName);
			}
			if (index == 0 && part.length() >= 2 && Character.isLetter(part.charAt(0)) && part.charAt(1) == ':') {
				throw new BackupFormatException("Unsafe backup archive entry: " + entryName);
			}
		}
	}
}
