package au.edu.eq.questionbank.service.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.ApplicationConfig;

class BackupContractTest {

	@TempDir
	Path tempDir;

	@Test
	void automaticBackupKindDefinesDatabaseOnlyPackageContract() {
		assertEquals(List.of("backup-manifest.properties", "questionbank.db"),
				BackupKind.AUTOMATIC_DATABASE.requiredArchiveEntries());
	}

	@Test
	void automaticRequestUsesConfiguredAutomaticBackupDirectory() {
		ApplicationConfig config = ApplicationConfig.fromDataRoot(tempDir.resolve("data"));
		BackupRequest request = BackupRequest.automaticDatabase(config);
		assertEquals(BackupKind.AUTOMATIC_DATABASE, request.kind());
		assertEquals(config.dataRoot().resolve("backups").resolve("automatic").toAbsolutePath().normalize(),
				request.destinationDirectory());
	}

	@Test
	void currentManifestUsesBackupFormatVersionOne() {
		Instant createdAt = Instant.parse("2026-09-04T08:00:00Z");
		BackupManifest manifest = BackupManifest.current(BackupKind.AUTOMATIC_DATABASE, createdAt, 4, "0.0.1-SNAPSHOT");
		assertEquals(1, manifest.formatVersion());
		assertEquals(BackupKind.AUTOMATIC_DATABASE, manifest.kind());
		assertEquals(createdAt, manifest.createdAt());
		assertEquals(4, manifest.databaseSchemaVersion());
		assertEquals("0.0.1-SNAPSHOT", manifest.applicationVersion());
		assertEquals(BackupKind.AUTOMATIC_DATABASE.requiredArchiveEntries(), manifest.expectedArchiveEntries());
	}

	@Test
	void fullBackupKindDefinesCompleteManagedPackageContract() {
		assertEquals(List.of("backup-manifest.properties", "questionbank.db", "pdf/", "curriculum/"),
				BackupKind.FULL.requiredArchiveEntries());
	}

	@Test
	void fullRequestNormalisesDestinationDirectory() {
		Path destination = tempDir.resolve("archive").resolve("..").resolve("backups");
		BackupRequest request = BackupRequest.full(destination);
		assertEquals(BackupKind.FULL, request.kind());
		assertEquals(tempDir.resolve("backups").toAbsolutePath().normalize(), request.destinationDirectory());
	}

	@Test
	void manifestRejectsInvalidRequiredValues() {
		Instant createdAt = Instant.parse("2026-09-04T08:00:00Z");
		assertThrows(IllegalArgumentException.class,
				() -> new BackupManifest(0, BackupKind.FULL, createdAt, 4, "Development build"));
		assertThrows(NullPointerException.class, () -> new BackupManifest(1, null, createdAt, 4, "Development build"));
		assertThrows(NullPointerException.class,
				() -> new BackupManifest(1, BackupKind.FULL, null, 4, "Development build"));
		assertThrows(IllegalArgumentException.class,
				() -> new BackupManifest(1, BackupKind.FULL, createdAt, 0, "Development build"));
		assertThrows(IllegalArgumentException.class, () -> new BackupManifest(1, BackupKind.FULL, createdAt, 4, " "));
	}

	@Test
	void pathResolverCreatesDeterministicImmediateChildPath() {
		BackupRequest request = BackupRequest.full(tempDir);
		BackupPathResolver resolver = new BackupPathResolver();
		Path result = resolver.resolveBackupPath(request, Instant.parse("2026-09-04T18:05:00.123Z"));
		assertEquals(tempDir.resolve("question-bank-full-2026-09-04T180500123Z.zip").toAbsolutePath().normalize(),
				result);
		assertEquals(request.destinationDirectory(), result.getParent());
	}

	@Test
	void pathResolverRejectsExistingNonDirectoryDestination() throws IOException {
		Path destination = tempDir.resolve("not-a-directory");
		Files.writeString(destination, "test");
		BackupRequest request = BackupRequest.full(destination);
		BackupPathResolver resolver = new BackupPathResolver();
		assertThrows(IllegalArgumentException.class,
				() -> resolver.resolveBackupPath(request, Instant.parse("2026-09-04T18:05:00Z")));
	}

	@Test
	void requestRejectsNullArguments() {
		assertThrows(NullPointerException.class, () -> new BackupRequest(null, tempDir));
		assertThrows(NullPointerException.class, () -> new BackupRequest(BackupKind.FULL, null));
		assertThrows(NullPointerException.class, () -> BackupRequest.automaticDatabase(null));
	}
}
