package au.edu.eq.questionbank.service.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class BackupManifestCodecTest {

	private final BackupManifestCodec codec = new BackupManifestCodec();

	@Test
	void readAndWriteRejectNullArguments() {
		assertThrows(NullPointerException.class, () -> codec.read(null));
		assertThrows(NullPointerException.class, () -> codec.write(null, new ByteArrayOutputStream()));
		BackupManifest manifest = BackupManifest.current(BackupKind.FULL, Instant.parse("2026-09-04T08:00:00Z"), 4,
				"Development build");
		assertThrows(NullPointerException.class, () -> codec.write(manifest, null));
	}

	@Test
	void readsAFullBackupManifest() throws Exception {
		String manifestText = """
				backup.format.version=1
				backup.kind=FULL
				created.at=2026-09-04T08:00:00Z
				database.schema.version=4
				application.version=Development build
				archive.entries=backup-manifest.properties,questionbank.db,pdf/,curriculum/
				""";
		BackupManifest manifest = codec.read(new ByteArrayInputStream(manifestText.getBytes(StandardCharsets.UTF_8)));
		assertEquals(1, manifest.formatVersion());
		assertEquals(BackupKind.FULL, manifest.kind());
		assertEquals(Instant.parse("2026-09-04T08:00:00Z"), manifest.createdAt());
		assertEquals(4, manifest.databaseSchemaVersion());
		assertEquals("Development build", manifest.applicationVersion());
	}

	@Test
	void rejectsAMissingRequiredProperty() {
		String manifestText = """
				backup.format.version=1
				backup.kind=AUTOMATIC_DATABASE
				created.at=2026-09-04T08:00:00Z
				database.schema.version=4
				archive.entries=backup-manifest.properties,questionbank.db
				""";
		assertThrows(BackupFormatException.class,
				() -> codec.read(new ByteArrayInputStream(manifestText.getBytes(StandardCharsets.UTF_8))));
	}

	@Test
	void rejectsAnInvalidBackupKind() {
		String manifestText = """
				backup.format.version=1
				backup.kind=SOMETHING_ELSE
				created.at=2026-09-04T08:00:00Z
				database.schema.version=4
				application.version=Development build
				archive.entries=backup-manifest.properties,questionbank.db
				""";
		assertThrows(BackupFormatException.class,
				() -> codec.read(new ByteArrayInputStream(manifestText.getBytes(StandardCharsets.UTF_8))));
	}

	@Test
	void rejectsAnInvalidCreationTimestamp() {
		String manifestText = """
				backup.format.version=1
				backup.kind=AUTOMATIC_DATABASE
				created.at=not-a-timestamp
				database.schema.version=4
				application.version=Development build
				archive.entries=backup-manifest.properties,questionbank.db
				""";
		assertThrows(BackupFormatException.class,
				() -> codec.read(new ByteArrayInputStream(manifestText.getBytes(StandardCharsets.UTF_8))));
	}

	@Test
	void rejectsANonPositiveDatabaseSchemaVersion() {
		String manifestText = """
				backup.format.version=1
				backup.kind=AUTOMATIC_DATABASE
				created.at=2026-09-04T08:00:00Z
				database.schema.version=0
				application.version=Development build
				archive.entries=backup-manifest.properties,questionbank.db
				""";
		assertThrows(BackupFormatException.class,
				() -> codec.read(new ByteArrayInputStream(manifestText.getBytes(StandardCharsets.UTF_8))));
	}

	@Test
	void rejectsAnUnsupportedFutureFormatVersion() {
		String manifestText = """
				backup.format.version=2
				backup.kind=AUTOMATIC_DATABASE
				created.at=2026-09-04T08:00:00Z
				database.schema.version=4
				application.version=Development build
				archive.entries=backup-manifest.properties,questionbank.db
				""";
		assertThrows(BackupFormatException.class,
				() -> codec.read(new ByteArrayInputStream(manifestText.getBytes(StandardCharsets.UTF_8))));
	}

	@Test
	void rejectsArchiveEntriesThatDoNotMatchBackupKind() {
		String manifestText = """
				backup.format.version=1
				backup.kind=AUTOMATIC_DATABASE
				created.at=2026-09-04T08:00:00Z
				database.schema.version=4
				application.version=Development build
				archive.entries=backup-manifest.properties,questionbank.db,pdf/,curriculum/
				""";
		assertThrows(BackupFormatException.class,
				() -> codec.read(new ByteArrayInputStream(manifestText.getBytes(StandardCharsets.UTF_8))));
	}

	@Test
	void roundTripsAnAutomaticDatabaseManifest() throws Exception {
		BackupManifest original = BackupManifest.current(BackupKind.AUTOMATIC_DATABASE,
				Instant.parse("2026-09-04T08:00:00.125Z"), 4, "0.0.1-SNAPSHOT");
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		codec.write(original, output);
		BackupManifest restored = codec.read(new ByteArrayInputStream(output.toByteArray()));
		assertEquals(original, restored);
	}

	@Test
	void writeRejectsANonCurrentFormatVersion() {
		BackupManifest manifest = new BackupManifest(2, BackupKind.FULL, Instant.parse("2026-09-04T08:00:00Z"), 4,
				"Development build");
		assertThrows(IllegalArgumentException.class, () -> codec.write(manifest, new ByteArrayOutputStream()));
	}

	@Test
	void writesDeterministicVersionOneManifest() throws Exception {
		BackupManifest manifest = BackupManifest.current(BackupKind.FULL, Instant.parse("2026-09-04T08:00:00Z"), 4,
				"0.0.1-SNAPSHOT");
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		codec.write(manifest, output);
		String expected = """
				backup.format.version=1
				backup.kind=FULL
				created.at=2026-09-04T08:00:00Z
				database.schema.version=4
				application.version=0.0.1-SNAPSHOT
				archive.entries=backup-manifest.properties,questionbank.db,pdf/,curriculum/
				""";
		assertEquals(expected, output.toString(StandardCharsets.UTF_8));
	}
}
