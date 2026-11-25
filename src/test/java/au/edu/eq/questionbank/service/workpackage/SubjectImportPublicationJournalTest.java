package au.edu.eq.questionbank.service.workpackage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
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
import au.edu.eq.questionbank.ManagedAssetPublicationLock;
import au.edu.eq.questionbank.ManagedDataLayout;
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

	/** A prepared but unpublished intent is safely removable on restart. */
	@Test
	void preparedIntentWithoutPublishedFilesCanBeRecovered() throws Exception {
		Fixture fixture = fixture();
		fixture.journal().prepare(fixture.staged(), fixture.backup());
		assertFalse(fixture.journal().recover(fixture.staged().packageId()));
		assertFalse(Files.exists(fixture.destination()));
		assertFalse(Files.exists(fixture.staged().directory()));
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

	@Test
	void refreshAdditionIsRolledBackWithoutACommittedPackage() throws Exception {
		Fixture fixture = fixture();
		publishRefresh(fixture, SubjectRefreshChangeClassifier.ChangeKind.ADD, false);
		assertArrayEquals(fixture.bytes(), Files.readAllBytes(fixture.destination()));
		assertFalse(fixture.journal().recover(fixture.staged().packageId()));
		assertFalse(Files.exists(fixture.destination()));
		assertFalse(Files.exists(fixture.staged().directory()));
	}

	@Test
	void refreshCommittedRemovalDoesNotRestoreOriginalFile() throws Exception {
		Fixture fixture = fixture(true);
		publishRefresh(fixture, SubjectRefreshChangeClassifier.ChangeKind.REMOVE, true);
		assertFalse(Files.exists(fixture.destination()));
		assertTrue(Files.exists(fixture.staged().directory().resolve("assets/original-00000.bin")));
		assertTrue(fixture.journal().recover(fixture.staged().packageId()));
		assertFalse(Files.exists(fixture.destination()));
		assertFalse(Files.exists(fixture.staged().directory()));
		assertEquals(0, sourceDocumentCount(fixture));
		new SqliteDatabase(fixture.config().databasePath()).verifyIntegrity();
	}

	@Test
	void refreshCommittedReplacementRetainsIncomingBytes() throws Exception {
		Fixture fixture = fixture(true);
		publishRefresh(fixture, SubjectRefreshChangeClassifier.ChangeKind.UPDATE, true);
		assertArrayEquals(fixture.bytes(), Files.readAllBytes(fixture.destination()));
		assertArrayEquals(originalBytes(),
				Files.readAllBytes(fixture.staged().directory().resolve("assets/original-00000.bin")));
		assertTrue(fixture.journal().recover(fixture.staged().packageId()));
		assertArrayEquals(fixture.bytes(), Files.readAllBytes(fixture.destination()));
		assertFalse(Files.exists(fixture.staged().directory()));
		assertEquals(1, sourceDocumentCount(fixture));
		new SqliteDatabase(fixture.config().databasePath()).verifyIntegrity();
	}

	@Test
	void refreshPreparationRejectsChangedOriginalBytes() throws Exception {
		Fixture fixture = fixture(true);
		byte[] independentlyChanged = "Independently changed PDF".getBytes(StandardCharsets.UTF_8);
		Files.write(fixture.destination(), independentlyChanged);
		try (var lock = ManagedAssetPublicationLock.acquire(fixture.config().dataRoot());
				var _ = new ManagedAssetRecoveryReferences(fixture.config(), lock)) {
			assertThrows(IOException.class, () -> fixture.journal().prepareRefresh(fixture.staged(), fixture.backup(),
					List.of(refreshChange(fixture, SubjectRefreshChangeClassifier.ChangeKind.UPDATE)), lock));
		}
		assertArrayEquals(independentlyChanged, Files.readAllBytes(fixture.destination()));
		assertFalse(Files.exists(fixture.staged().directory().resolve("publication.properties")));
		assertEquals(1, sourceDocumentCount(fixture));
	}

	@Test
	void refreshPreparationRejectsCrossSubjectDestination() throws Exception {
		Fixture fixture = fixture(true);
		String unrelatedPath = "subjects/Chemistry/exams/QCAA/2025/External Assessment/unrelated.pdf";
		Path unrelated = fixture.config().dataRoot().resolve(unrelatedPath);
		Files.createDirectories(unrelated.getParent());
		Files.write(unrelated, originalBytes());
		var change = new SubjectRefreshChangeClassifier.AssetChange(unrelatedPath,
				SubjectRefreshChangeClassifier.ChangeKind.REMOVE,
				new SubjectRefreshChangeClassifier.AssetContent(checksum(originalBytes()), originalBytes().length),
				null);
		try (var lock = ManagedAssetPublicationLock.acquire(fixture.config().dataRoot());
				var _ = new ManagedAssetRecoveryReferences(fixture.config(), lock)) {
			assertThrows(IOException.class,
					() -> fixture.journal().prepareRefresh(
							refreshStage(fixture, SubjectRefreshChangeClassifier.ChangeKind.REMOVE), fixture.backup(),
							List.of(change), lock));
		}
		assertArrayEquals(originalBytes(), Files.readAllBytes(unrelated));
		assertArrayEquals(originalBytes(), Files.readAllBytes(fixture.destination()));
		assertFalse(Files.exists(fixture.staged().directory().resolve("publication.properties")));
	}

	@Test
	void refreshPreparationRejectsUnapprovedStagedAssets() throws Exception {
		Fixture fixture = fixture();
		try (var lock = ManagedAssetPublicationLock.acquire(fixture.config().dataRoot());
				var _ = new ManagedAssetRecoveryReferences(fixture.config(), lock)) {
			assertThrows(IOException.class,
					() -> fixture.journal().prepareRefresh(fixture.staged(), fixture.backup(), List.of(), lock));
		}
		assertFalse(Files.exists(fixture.destination()));
		assertFalse(Files.exists(fixture.staged().directory().resolve("publication.properties")));
	}

	@Test
	void refreshPreparedReplacementPreservesOriginalWithoutPublication() throws Exception {
		Fixture fixture = fixture(true);
		try (var lock = ManagedAssetPublicationLock.acquire(fixture.config().dataRoot());
				var _ = new ManagedAssetRecoveryReferences(fixture.config(), lock)) {
			fixture.journal().prepareRefresh(fixture.staged(), fixture.backup(),
					List.of(refreshChange(fixture, SubjectRefreshChangeClassifier.ChangeKind.UPDATE)), lock);
			Path retained = fixture.staged().directory().resolve("assets/original-00000.bin");
			assertTrue(Files.isSameFile(retained, fixture.destination()));
			assertArrayEquals(originalBytes(), Files.readAllBytes(fixture.destination()));
		}
		assertFalse(fixture.journal().recover(fixture.staged().packageId()));
		assertArrayEquals(originalBytes(), Files.readAllBytes(fixture.destination()));
		assertFalse(Files.exists(fixture.staged().directory()));
		assertEquals(1, sourceDocumentCount(fixture));
	}

	@Test
	void refreshRecoveryRejectsCommittedRemovalWithRemainingReference() throws Exception {
		Fixture fixture = fixture(true);
		publishRefresh(fixture, SubjectRefreshChangeClassifier.ChangeKind.REMOVE, false);

		// Deliberately simulate an inconsistent committed package whose graph failed
		// to remove the authoritative reference.
		SqliteDatabase database = new SqliteDatabase(fixture.config().databasePath());
		try (var _ = ManagedAssetPublicationLock.acquire(fixture.config().dataRoot());
				Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			recordApplied(fixture, connection);
			connection.commit();
		}
		assertEquals(1, sourceDocumentCount(fixture));
		assertFalse(Files.exists(fixture.destination()));
		assertThrows(IOException.class, () -> fixture.journal().recover(fixture.staged().packageId()));
		assertTrue(Files.isRegularFile(fixture.staged().directory().resolve("publication.properties")));
		assertArrayEquals(originalBytes(),
				Files.readAllBytes(fixture.staged().directory().resolve("assets/original-00000.bin")));
	}

	@Test
	void refreshRecoveryRejectsIndependentlyReplacedDestination() throws Exception {
		Fixture fixture = fixture(true);
		publishRefresh(fixture, SubjectRefreshChangeClassifier.ChangeKind.UPDATE, false);

		// Identical bytes do not prove that the destination is still owned by
		// the interrupted refresh publication.
		Files.delete(fixture.destination());
		Files.write(fixture.destination(), fixture.bytes());
		assertThrows(IOException.class, () -> fixture.journal().recover(fixture.staged().packageId()));
		assertArrayEquals(fixture.bytes(), Files.readAllBytes(fixture.destination()));
		assertArrayEquals(originalBytes(),
				Files.readAllBytes(fixture.staged().directory().resolve("assets/original-00000.bin")));
		assertTrue(Files.isRegularFile(fixture.staged().directory().resolve("publication.properties")));
	}

	@Test
	void refreshRecoveryRejectsModifiedOriginalEvidence() throws Exception {
		Fixture fixture = fixture(true);
		publishRefresh(fixture, SubjectRefreshChangeClassifier.ChangeKind.UPDATE, false);
		Path retained = fixture.staged().directory().resolve("assets/original-00000.bin");
		Files.write(retained, "Corrupted original recovery evidence".getBytes(StandardCharsets.UTF_8));
		assertThrows(IOException.class, () -> fixture.journal().recover(fixture.staged().packageId()));
		assertArrayEquals(fixture.bytes(), Files.readAllBytes(fixture.destination()));
		assertTrue(Files.isRegularFile(fixture.staged().directory().resolve("publication.properties")));
	}

	@Test
	void refreshRemovalRecoveryResumesAfterOriginalWasRestored() throws Exception {
		Fixture fixture = fixture(true);
		publishRefresh(fixture, SubjectRefreshChangeClassifier.ChangeKind.REMOVE, false);
		Path retained = fixture.staged().directory().resolve("assets/original-00000.bin");

		// Simulate termination after restoration, but before journal cleanup.
		Files.createLink(fixture.destination(), retained);
		assertFalse(fixture.journal().recover(fixture.staged().packageId()));
		assertArrayEquals(originalBytes(), Files.readAllBytes(fixture.destination()));
		assertFalse(Files.exists(fixture.staged().directory()));
		assertEquals(1, sourceDocumentCount(fixture));
	}

	@Test
	void refreshRemovalRestoresOriginalAfterRollback() throws Exception {
		Fixture fixture = fixture(true);
		publishRefresh(fixture, SubjectRefreshChangeClassifier.ChangeKind.REMOVE, false);
		assertFalse(Files.exists(fixture.destination()));
		assertEquals(1, sourceDocumentCount(fixture));
		assertArrayEquals(originalBytes(),
				Files.readAllBytes(fixture.staged().directory().resolve("assets/original-00000.bin")));
		assertFalse(fixture.journal().recover(fixture.staged().packageId()));
		assertArrayEquals(originalBytes(), Files.readAllBytes(fixture.destination()));
		assertEquals(1, sourceDocumentCount(fixture));
		assertFalse(Files.exists(fixture.staged().directory()));
		new SqliteDatabase(fixture.config().databasePath()).verifyIntegrity();
	}

	@Test
	void refreshReplacementRecoveryResumesAfterTemporaryRestoreLink() throws Exception {
		Fixture fixture = fixture(true);
		publishRefresh(fixture, SubjectRefreshChangeClassifier.ChangeKind.UPDATE, false);
		Path assets = fixture.staged().directory().resolve("assets");
		Path retained = assets.resolve("original-00000.bin");

		// Simulate termination immediately before the atomic restoration move.
		Files.createLink(assets.resolve("restore-original-00000.bin"), retained);
		assertFalse(fixture.journal().recover(fixture.staged().packageId()));
		assertArrayEquals(originalBytes(), Files.readAllBytes(fixture.destination()));
		assertFalse(Files.exists(fixture.staged().directory()));
		assertEquals(1, sourceDocumentCount(fixture));
	}

	@Test
	void refreshReplacementRestoresOriginalAfterRollback() throws Exception {
		Fixture fixture = fixture(true);
		publishRefresh(fixture, SubjectRefreshChangeClassifier.ChangeKind.UPDATE, false);
		assertArrayEquals(fixture.bytes(), Files.readAllBytes(fixture.destination()));
		Path retained = fixture.staged().directory().resolve("assets/original-00000.bin");
		assertArrayEquals(originalBytes(), Files.readAllBytes(retained));
		assertFalse(Files.isSameFile(retained, fixture.destination()));
		assertFalse(fixture.journal().recover(fixture.staged().packageId()));
		assertArrayEquals(originalBytes(), Files.readAllBytes(fixture.destination()));
		assertFalse(Files.exists(fixture.staged().directory()));
		assertEquals(1, sourceDocumentCount(fixture));
		new SqliteDatabase(fixture.config().databasePath()).verifyIntegrity();
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

	private String checksum(byte[] bytes) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
	}

	/** Creates the existing non-overwriting publication test fixture. */
	private Fixture fixture() throws Exception {
		return fixture(false);
	}

	/**
	 * Creates a real incoming archive, verified full backup and private staged
	 * file.
	 *
	 * <p>
	 * Refresh fixtures contain an existing managed asset and authoritative database
	 * reference before backup creation.
	 */
	private Fixture fixture(boolean existingFile) throws Exception {
		ApplicationConfig config = ApplicationConfig.fromDataRoot(directory.resolve("master"));
		Files.createDirectories(config.dataRoot());
		SqliteDatabase master = new SqliteDatabase(config.databasePath());
		master.initialiseSchema();
		String managedPath = "subjects/Biology/exams/QCAA/2025/External Assessment/new-file.pdf";
		Path destination = config.dataRoot().resolve(managedPath);
		if (existingFile) {
			Files.createDirectories(destination.getParent());
			Files.write(destination, originalBytes());
			try (Connection connection = master.openConnection();
					var statement = connection
							.prepareStatement("INSERT INTO source_documents (relative_path) VALUES (?)")) {
				statement.setString(1, managedPath);
				statement.executeUpdate();
			}
		}
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
		String digest = checksum(bytes);
		Path stageFile = assetDirectory.resolve("00000.bin");
		Files.write(stageFile, bytes);
		StagedAsset asset = new StagedAsset(managedPath,
				"assets/sha256/" + digest.substring(0, 2) + "/" + digest + ".pdf", stageFile, digest, bytes.length,
				AssetKind.MANAGED_FILE);
		StagedPackage staged = new StagedPackage(packageId, "b".repeat(64), staging, stagedArchive, List.of(asset));
		return new Fixture(config, staged, backup, new SubjectImportPublicationJournal(config), destination, bytes);
	}

	private byte[] originalBytes() {
		return "original authoritative Biology PDF".getBytes(StandardCharsets.UTF_8);
	}

	/**
	 * Exercises publication under the actual shared lock and SQLite writer
	 * reservation, followed by a real transaction commit or rollback.
	 */
	private void publishRefresh(Fixture fixture, SubjectRefreshChangeClassifier.ChangeKind kind, boolean commit)
			throws Exception {
		SqliteDatabase database = new SqliteDatabase(fixture.config().databasePath());
		try (var lock = ManagedAssetPublicationLock.acquire(fixture.config().dataRoot());
				Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try (var statement = connection.createStatement()) {
				assertEquals(1, statement.executeUpdate("UPDATE installation_identity "
						+ "SET installation_id = installation_id " + "WHERE singleton_id = 1"));
			}
			fixture.journal().prepareRefresh(refreshStage(fixture, kind), fixture.backup(),
					List.of(refreshChange(fixture, kind)), lock);
			fixture.journal().publishFiles(fixture.staged().packageId());
			fixture.journal().verifyPublished(fixture.staged().packageId());
			if (kind == SubjectRefreshChangeClassifier.ChangeKind.REMOVE) {
				try (var statement = connection
						.prepareStatement("DELETE FROM source_documents WHERE relative_path = ?")) {
					statement.setString(1, fixture.staged().assets().getFirst().managedPath());
					assertEquals(1, statement.executeUpdate());
				}
			}
			if (commit) {
				recordApplied(fixture, connection);
				connection.commit();
			} else {
				connection.rollback();
			}
		}
	}

	private void recordApplied(Fixture fixture, Connection connection) throws Exception {
		SqliteDatabase database = new SqliteDatabase(fixture.config().databasePath());
		var fingerprint = new SubjectImportSnapshotFingerprint().inspect(fixture.staged().archive());
		var entry = new PackageEntry(fingerprint.packageId(), fingerprint.archiveSha256(),
				fingerprint.originInstallationId(), fingerprint.subjectId(), "a".repeat(64),
				fixture.staged().approvalSha256(), PackageOutcome.APPLIED, fixture.backup().backupPath().toString(),
				"Accepted Biology refresh test");
		assertTrue(new SqliteSubjectImportLedger(database).recordPackage(connection, entry));
	}

	private SubjectRefreshChangeClassifier.AssetChange refreshChange(Fixture fixture,
			SubjectRefreshChangeClassifier.ChangeKind kind) throws Exception {
		StagedAsset asset = fixture.staged().assets().getFirst();
		var original = kind == SubjectRefreshChangeClassifier.ChangeKind.ADD ? null
				: new SubjectRefreshChangeClassifier.AssetContent(checksum(originalBytes()), originalBytes().length);
		var incoming = kind == SubjectRefreshChangeClassifier.ChangeKind.REMOVE ? null
				: new SubjectRefreshChangeClassifier.AssetContent(asset.sha256(), asset.size());
		return new SubjectRefreshChangeClassifier.AssetChange(asset.managedPath(), kind, original, incoming);
	}

	private StagedPackage refreshStage(Fixture fixture, SubjectRefreshChangeClassifier.ChangeKind kind) {
		StagedPackage staged = fixture.staged();
		if (kind != SubjectRefreshChangeClassifier.ChangeKind.REMOVE) {
			return staged;
		}

		// Removals require original-file evidence, not an incoming staged file.
		return new StagedPackage(staged.packageId(), staged.approvalSha256(), staged.directory(), staged.archive(),
				List.of());
	}

	private int sourceDocumentCount(Fixture fixture) throws SQLException {
		SqliteDatabase database = new SqliteDatabase(fixture.config().databasePath());
		try (Connection connection = database.openConnection();
				var statement = connection.createStatement();
				var result = statement.executeQuery("SELECT COUNT(*) FROM source_documents")) {
			assertTrue(result.next());
			return result.getInt(1);
		}
	}

	/** Minimal verified journal test dependencies. */
	private record Fixture(ApplicationConfig config, StagedPackage staged, Certificate backup,
			SubjectImportPublicationJournal journal, Path destination, byte[] bytes) {
	}
}
