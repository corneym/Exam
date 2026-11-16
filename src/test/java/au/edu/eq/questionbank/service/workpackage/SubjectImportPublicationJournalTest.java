package au.edu.eq.questionbank.service.workpackage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.ManagedAssetPublicationLock;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.repository.sqlite.SqliteSubjectImportLedger;
import au.edu.eq.questionbank.repository.sqlite.SqliteSubjectImportLedger.PackageEntry;
import au.edu.eq.questionbank.repository.sqlite.SqliteSubjectImportLedger.PackageOutcome;
import au.edu.eq.questionbank.service.workpackage.SubjectImportAssetStager.AssetKind;
import au.edu.eq.questionbank.service.workpackage.SubjectImportAssetStager.StagedAsset;
import au.edu.eq.questionbank.service.workpackage.SubjectImportAssetStager.StagedPackage;
import au.edu.eq.questionbank.service.workpackage.SubjectImportBackupGate.Certificate;

/**
 * Verifies restart-safe publication without replacing existing master files.
 */
class SubjectImportPublicationJournalTest {

	@TempDir
	Path directory;

	@Test
	void authoritativeSourceReferenceRetainsAnUncommittedPublication() throws Exception {
		Fixture fixture = fixture();
		fixture.journal().prepare(fixture.staged(), fixture.backup());
		fixture.journal().publishFiles(fixture.staged().packageId());
		SqliteDatabase database = new SqliteDatabase(fixture.config().databasePath());
		try (var connection = database.openConnection();
				var statement = connection
						.prepareStatement("INSERT INTO source_documents (relative_path) VALUES (?)")) {
			statement.setString(1, fixture.staged().assets().getFirst().managedPath().replace("exams/", "exams/./"));
			statement.executeUpdate();
		}
		assertFalse(fixture.journal().recover(fixture.staged().packageId()));
		assertArrayEquals(fixture.bytes(), Files.readAllBytes(fixture.destination()));
		assertFalse(Files.exists(fixture.staged().directory()));
	}

	@Test
	void unsafeAuthoritativeReferenceStopsRecoveryBeforeDeletingAnything() throws Exception {
		Fixture fixture = fixture();
		fixture.journal().prepare(fixture.staged(), fixture.backup());
		fixture.journal().publishFiles(fixture.staged().packageId());
		SqliteDatabase database = new SqliteDatabase(fixture.config().databasePath());
		try (var connection = database.openConnection(); var statement = connection.createStatement()) {
			statement.executeUpdate("INSERT INTO source_documents (relative_path) VALUES ('../unknown.pdf')");
		}
		assertThrows(java.io.IOException.class, () -> fixture.journal().recover(fixture.staged().packageId()));
		assertArrayEquals(fixture.bytes(), Files.readAllBytes(fixture.destination()));
		assertTrue(Files.isRegularFile(fixture.staged().directory().resolve("publication.properties")));
	}

	@Test
	void missingAuthoritativeDatabaseCannotBeMistakenForNoReferences() throws Exception {
		Fixture fixture = fixture();
		fixture.journal().prepare(fixture.staged(), fixture.backup());
		fixture.journal().publishFiles(fixture.staged().packageId());
		Files.move(fixture.config().databasePath(), directory.resolve("unavailable.db"));
		assertThrows(java.io.IOException.class, () -> fixture.journal().recover(fixture.staged().packageId()));
		assertFalse(Files.exists(fixture.config().databasePath()));
		assertArrayEquals(fixture.bytes(), Files.readAllBytes(fixture.destination()));
		assertTrue(Files.exists(fixture.staged().directory()));
	}

	@Test
	void recoveryResumesAfterAnOwnedFileWasDeletedBeforeStageCleanup() throws Exception {
		Fixture fixture = fixture();
		fixture.journal().prepare(fixture.staged(), fixture.backup());
		fixture.journal().publishFiles(fixture.staged().packageId());

		// Simulate termination after managed unlink but before durable evidence
		// cleanup.
		Files.delete(fixture.destination());
		assertFalse(fixture.journal().recover(fixture.staged().packageId()));
		assertFalse(Files.exists(fixture.staged().directory()));
		new SubjectImportCoordinator(fixture.config())
				.recoverPending(fixture.config().dataRoot().resolve(".subject-import-staging"));
		assertFalse(Files.exists(fixture.destination()));
	}

	@Test
	void referenceSnapshotBlocksConcurrentDatabaseAdoptionUntilRecoveryEnds() throws Exception {
		Fixture fixture = fixture();
		SqliteDatabase database = new SqliteDatabase(fixture.config().databasePath());
		try (var lock = ManagedAssetPublicationLock.acquire(fixture.config().dataRoot());
				var references = new ManagedAssetRecoveryReferences(fixture.config(), lock);
				var other = database.openConnection();
				var statement = other.createStatement()) {
			statement.execute("PRAGMA busy_timeout = 1");
			assertFalse(references.contains(fixture.destination()));
			SQLException busy = assertThrows(SQLException.class,
					() -> statement.executeUpdate("INSERT INTO source_documents (relative_path) "
							+ "VALUES ('subjects/Biology/exams/QCAA/2025/External Assessment/new.pdf')"));
			assertEquals(5, busy.getErrorCode());
		}
		try (var connection = database.openConnection(); var statement = connection.createStatement()) {
			statement.executeUpdate("INSERT INTO source_documents (relative_path) "
					+ "VALUES ('subjects/Biology/exams/QCAA/2025/External Assessment/new.pdf')");
			try (var rows = statement.executeQuery("SELECT COUNT(*) FROM source_documents")) {
				assertTrue(rows.next());
				assertEquals(1, rows.getInt(1));
			}
		}
	}

	/** A destination independently replaced after publication is never deleted. */
	@Test
	void alteredPublicationIsNotRemovedByRecovery() throws Exception {
		Fixture fixture = fixture();
		fixture.journal().prepare(fixture.staged(), fixture.backup());
		fixture.journal().publishFiles(fixture.staged().packageId());
		Files.delete(fixture.destination());
		Files.write(fixture.destination(), fixture.bytes());
		assertThrows(java.io.IOException.class, () -> fixture.journal().recover(fixture.staged().packageId()));
		assertArrayEquals(fixture.bytes(), Files.readAllBytes(fixture.destination()));
		assertTrue(Files.exists(fixture.staged().directory()));
	}

	/** A confirmed SQLite commit leaves published master files in place. */
	@Test
	void committedPackageRetainsFilesAndRemovesStaging() throws Exception {
		Fixture fixture = fixture();
		fixture.journal().prepare(fixture.staged(), fixture.backup());
		fixture.journal().publishFiles(fixture.staged().packageId());
		SqliteDatabase database = new SqliteDatabase(fixture.config().databasePath());
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			var fingerprint = new SubjectImportSnapshotFingerprint().inspect(fixture.staged().archive());
			var entry = new PackageEntry(fingerprint.packageId(), fingerprint.archiveSha256(),
					fingerprint.originInstallationId(), fingerprint.subjectId(), "a".repeat(64),
					fixture.staged().approvalSha256(), PackageOutcome.APPLIED, fixture.backup().backupPath().toString(),
					"Accepted Biology test import");
			assertTrue(new SqliteSubjectImportLedger(database).recordPackage(connection, entry));
			connection.commit();
		}
		assertTrue(fixture.journal().recover(fixture.staged().packageId()));
		assertArrayEquals(fixture.bytes(), Files.readAllBytes(fixture.destination()));
		assertFalse(Files.exists(fixture.staged().directory()));
	}

	/** Publication must never replace an independently existing managed file. */
	@Test
	void existingDestinationPreventsHardLinkPublication() throws Exception {
		Fixture fixture = fixture();
		fixture.journal().prepare(fixture.staged(), fixture.backup());
		Files.createDirectories(fixture.destination().getParent());
		byte[] old = "Master file is authoritative".getBytes(StandardCharsets.UTF_8);
		Files.write(fixture.destination(), old);
		assertThrows(java.io.IOException.class, () -> fixture.journal().publishFiles(fixture.staged().packageId()));
		assertArrayEquals(old, Files.readAllBytes(fixture.destination()));
	}

	/** An uncommitted journal must remove only files linked from its stage. */
	@Test
	void interruptedUncommittedPublicationRemovesItsOwnFiles() throws Exception {
		Fixture fixture = fixture();
		fixture.journal().prepare(fixture.staged(), fixture.backup());
		fixture.journal().publishFiles(fixture.staged().packageId());
		assertArrayEquals(fixture.bytes(), Files.readAllBytes(fixture.destination()));
		assertTrue(fixture.journal().recover(fixture.staged().packageId()) == false);
		assertFalse(Files.exists(fixture.destination()));
		assertFalse(Files.exists(fixture.staged().directory()));
		new SqliteDatabase(fixture.config().databasePath()).verifyIntegrity();
	}

	/** A prepared but unpublished intent is safely removable on restart. */
	@Test
	void preparedIntentWithoutPublishedFilesCanBeRecovered() throws Exception {
		Fixture fixture = fixture();
		fixture.journal().prepare(fixture.staged(), fixture.backup());
		assertFalse(fixture.journal().recover(fixture.staged().packageId()));
		assertFalse(Files.exists(fixture.destination()));
		assertFalse(Files.exists(fixture.staged().directory()));
	}

	/** Creates a real package, full backup and private staged file. */
	private Fixture fixture() throws Exception {
		ApplicationConfig config = ApplicationConfig.fromDataRoot(directory.resolve("master"));
		Files.createDirectories(config.dataRoot());
		SqliteDatabase master = new SqliteDatabase(config.databasePath());
		master.initialiseSchema();
		ApplicationConfig source = ApplicationConfig.fromDataRoot(directory.resolve("source"));
		Files.createDirectories(source.dataRoot());
		SqliteDatabase sourceDatabase = new SqliteDatabase(source.databasePath());
		sourceDatabase.initialiseSchema();
		try (Connection connection = sourceDatabase.openConnection(); var statement = connection.createStatement()) {
			statement.execute("INSERT INTO subjects (id, subject_name) VALUES (1, 'Biology')");
		}
		Path archive = directory.resolve("incoming.examwork");
		ExamWorkManifest manifest = new SubjectSnapshotExporter(sourceDatabase,
				new ManagedDataLayout(source.dataRoot())).export(1, archive);
		UUID packageId = manifest.packageId();
		Certificate backup = new SubjectImportBackupGate(config).createVerifiedFullBackup(packageId,
				directory.resolve("backups"));
		Path staging = config.dataRoot().resolve(".subject-import-staging").resolve(packageId.toString());
		Path assetDirectory = Files.createDirectories(staging.resolve("assets"));
		Path stagedArchive = staging.resolve("incoming.examwork");
		Files.copy(archive, stagedArchive);
		byte[] bytes = "new independent Biology PDF".getBytes(StandardCharsets.UTF_8);
		String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		Path stageFile = assetDirectory.resolve("00000.bin");
		Files.write(stageFile, bytes);
		String managedPath = "subjects/Biology/exams/QCAA/2025/External Assessment/new-file.pdf";
		StagedAsset asset = new StagedAsset(managedPath,
				"assets/sha256/" + digest.substring(0, 2) + "/" + digest + ".pdf", stageFile, digest, bytes.length,
				AssetKind.MANAGED_FILE);
		StagedPackage staged = new StagedPackage(packageId, "b".repeat(64), staging, stagedArchive, List.of(asset));
		return new Fixture(config, staged, backup, new SubjectImportPublicationJournal(config),
				config.dataRoot().resolve(managedPath), bytes);
	}

	/** Minimal verified journal test dependencies. */
	private record Fixture(ApplicationConfig config, StagedPackage staged, Certificate backup,
			SubjectImportPublicationJournal journal, Path destination, byte[] bytes) {
	}
}
