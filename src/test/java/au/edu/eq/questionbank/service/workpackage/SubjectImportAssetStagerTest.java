package au.edu.eq.questionbank.service.workpackage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import org.apache.commons.compress.archivers.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.repository.sqlite.SqliteSubjectImportLedger;
import au.edu.eq.questionbank.service.workpackage.CurriculumReconciler.Classification;
import au.edu.eq.questionbank.service.workpackage.CurriculumReconciler.Proposal;
import au.edu.eq.questionbank.service.workpackage.ExamWorkAssetIndex.Reference;
import au.edu.eq.questionbank.service.workpackage.SubjectImportAssetStager.AssetKind;
import au.edu.eq.questionbank.service.workpackage.SubjectImportAssetStager.StagedPackage;
import au.edu.eq.questionbank.service.workpackage.SubjectImportBackupGate.Certificate;
import au.edu.eq.questionbank.service.workpackage.SubjectImportSnapshotFingerprint.Fingerprint;
import au.edu.eq.questionbank.service.workpackage.SubjectReconciliationPreviewService.AssetEvidence;
import au.edu.eq.questionbank.service.workpackage.SubjectReconciliationPreviewService.Result;
import au.edu.eq.questionbank.service.workpackage.SubjectReconciliationReview.Decision;

/**
 * Integration tests for non-publishing Subject import asset staging.
 *
 * <p>
 * Tests use disposable source and master databases. A real exported ExamWork
 * archive supplies the asset index and verified ZIP bytes.
 */
class SubjectImportAssetStagerTest {

	@TempDir
	Path directory;

	/**
	 * A damaged backup cannot authorise staging even when the contributor archive
	 * itself remains valid.
	 */
	@Test
	void changedFullBackupPreventsStaging() throws Exception {
		Fixture fixture = fixture();
		Certificate backup = backup(fixture);
		Files.writeString(backup.backupPath(), "Modified backup", StandardCharsets.UTF_8);
		assertThrows(IOException.class,
				() -> new SubjectImportAssetStager(fixture.master()).stage(fixture.plan(), backup, fixture.archive()));
		assertFalse(Files.exists(stagingDirectory(fixture)));
	}

	/**
	 * Changing the contributor archive after approval must prevent staging, leaving
	 * no published master files.
	 */
	@Test
	void changedIncomingArchiveIsRejected() throws Exception {
		Fixture fixture = fixture();
		Certificate backup = backup(fixture);
		Files.writeString(fixture.archive(), "Modified contributor archive", StandardCharsets.UTF_8);
		assertThrows(IOException.class,
				() -> new SubjectImportAssetStager(fixture.master()).stage(fixture.plan(), backup, fixture.archive()));
		assertFalse(Files.exists(stagingDirectory(fixture)));
		assertFalse(Files.exists(fixture.master().dataRoot().resolve(fixture.reference().managedPath())));
	}

	/**
	 * Embedded image references must be treated as database BLOBs, not as ordinary
	 * managed file publication targets.
	 */
	@Test
	void embeddedPngHasDatabaseBlobDestination() {
		UUID owner = UUID.randomUUID();
		String digest = "a".repeat(64);
		Reference reference = new Reference("assets/sha256/aa/" + digest + ".png", digest, 4, "image/png",
				"subjects/Biology/exams/_examwork_embedded/" + "QUESTION_IMAGE/" + owner + ".png", "QUESTION_IMAGE",
				owner, "EMBEDDED_QUESTION_PNG");
		assertEquals(AssetKind.EMBEDDED_PNG, SubjectImportAssetStager.kindFor(reference));
	}

	/**
	 * An existing destination cannot be overwritten by an approved new asset, and
	 * failed staging must not affect master content.
	 */
	@Test
	void existingMasterAssetIsNeverOverwritten() throws Exception {
		Fixture fixture = fixture();
		Path destination = fixture.master().dataRoot().resolve(fixture.reference().managedPath());
		Files.createDirectories(destination.getParent());
		byte[] masterBytes = "Existing master PDF".getBytes(StandardCharsets.UTF_8);
		Files.write(destination, masterBytes);
		Certificate backup = backup(fixture);
		assertThrows(IOException.class,
				() -> new SubjectImportAssetStager(fixture.master()).stage(fixture.plan(), backup, fixture.archive()));
		assertArrayEquals(masterBytes, Files.readAllBytes(destination));
		assertFalse(Files.exists(stagingDirectory(fixture)));
		new SqliteDatabase(fixture.master().databasePath()).verifyIntegrity();
	}

	/**
	 * A second staging attempt cannot overwrite a package directory from an earlier
	 * operation.
	 */
	@Test
	void repeatedStagingRequiresRecoveryFirst() throws Exception {
		Fixture fixture = fixture();
		Certificate backup = backup(fixture);
		SubjectImportAssetStager stager = new SubjectImportAssetStager(fixture.master());
		StagedPackage first = stager.stage(fixture.plan(), backup, fixture.archive());
		assertThrows(IOException.class, () -> stager.stage(fixture.plan(), backup, fixture.archive()));
		assertTrue(Files.isRegularFile(first.archive()));
		assertTrue(Files.isRegularFile(first.assets().getFirst().stagedPath()));
	}

	/**
	 * Only verified approved assets are staged; neither the managed Subjects tree
	 * nor the import ledger changes.
	 */
	@Test
	void stagesApprovedAssetWithoutPublishingIt() throws Exception {
		Fixture fixture = fixture();
		Certificate backup = backup(fixture);
		StagedPackage staged = new SubjectImportAssetStager(fixture.master()).stage(fixture.plan(), backup,
				fixture.archive());
		assertEquals(fixture.plan().reviewedIncoming().packageId(), staged.packageId());
		assertEquals(fixture.plan().approvalSha256(), staged.approvalSha256());
		assertEquals(1, staged.assets().size());
		var asset = staged.assets().getFirst();
		assertEquals(AssetKind.MANAGED_FILE, asset.kind());
		assertEquals(fixture.reference().managedPath(), asset.managedPath());
		assertEquals(fixture.reference().sha256(), asset.sha256());
		assertArrayEquals(fixture.sourceBytes(), Files.readAllBytes(asset.stagedPath()));
		assertEquals(fixture.plan().reviewedIncoming().archiveSha256(),
				new SubjectImportSnapshotFingerprint().inspect(staged.archive()).archiveSha256());
		Path masterDestination = fixture.master().dataRoot().resolve(fixture.reference().managedPath());
		assertFalse(Files.exists(masterDestination));
		SqliteDatabase master = new SqliteDatabase(fixture.master().databasePath());
		assertTrue(new SqliteSubjectImportLedger(master).find(staged.packageId()).isEmpty());
		master.verifyIntegrity();
	}

	/**
	 * Creates a verified full master backup for this test.
	 */
	private Certificate backup(Fixture fixture) throws Exception {
		return new SubjectImportBackupGate(fixture.master())
				.createVerifiedFullBackup(fixture.plan().reviewedIncoming().packageId(), directory.resolve("backups"));
	}

	/**
	 * Builds an approved plan using an actual contributor archive containing one
	 * Biology Subject and one inventoried PDF.
	 */
	private Fixture fixture() throws Exception {
		ApplicationConfig source = ApplicationConfig.fromDataRoot(directory.resolve("source"));
		Files.createDirectories(source.dataRoot());
		SqliteDatabase sourceDatabase = new SqliteDatabase(source.databasePath());
		sourceDatabase.initialiseSchema();
		try (Connection connection = sourceDatabase.openConnection();
				Statement statement = connection.createStatement()) {
			statement.execute("""
					INSERT INTO subjects (id, subject_name)
					VALUES (1, 'Biology')
					""");
		}
		Path sourceFile = source.dataRoot().resolve("subjects/Biology/exams/retained.pdf");
		Files.createDirectories(sourceFile.getParent());
		byte[] sourceBytes = "Contributor Biology PDF".getBytes(StandardCharsets.UTF_8);
		Files.write(sourceFile, sourceBytes);
		Path archive = directory.resolve("biology.examwork");
		ExamWorkManifest manifest = new SubjectSnapshotExporter(sourceDatabase,
				new ManagedDataLayout(source.dataRoot())).export(1, archive);
		Reference reference;
		try (ZipFile zip = ZipFile.builder().setPath(archive).get();
				InputStream input = zip.getInputStream(zip.getEntry(ExamWorkAssetIndex.INDEX_PATH))) {
			ExamWorkAssetIndex index = new ExamWorkJsonlCodec().readAssetIndex(input);
			reference = index.references().stream()
					.filter(item -> item.managedPath().equals("subjects/Biology/exams/retained.pdf")).findFirst()
					.orElseThrow();
		}
		Fingerprint incoming = new SubjectImportSnapshotFingerprint().inspect(archive);
		Fingerprint masterFingerprint = new Fingerprint(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				"Biology", "a".repeat(64), "a".repeat(64));
		Proposal subject = new Proposal("SUBJECT", manifest.subjectId(), null, null, null, Classification.NEW,
				"New contributor Biology Subject");
		AssetEvidence asset = new AssetEvidence(reference.managedPath(), reference.usage(), reference.ownerType(),
				reference.ownerId(), null, reference.sha256(), null, Classification.NEW, "New contributor PDF asset");
		SubjectReconciliationReview review = new SubjectReconciliationReview(new Result(masterFingerprint.packageId(),
				incoming.packageId(), "Biology", List.of(subject), List.of(), List.of(asset), List.of()));
		review.decide(SubjectReconciliationReview.recordKey(subject.recordType(), subject.incomingId()),
				Decision.ACCEPT, "Coordinator approved new Biology Subject");
		review.decide(SubjectReconciliationReview.assetKey(asset), Decision.ACCEPT,
				"Coordinator approved incoming Biology PDF");
		SubjectImportApprovalPlan plan = SubjectImportApprovalPlan.prepare(review, masterFingerprint, incoming);
		ApplicationConfig master = ApplicationConfig.fromDataRoot(directory.resolve("master"));
		Files.createDirectories(master.dataRoot());
		new SqliteDatabase(master.databasePath()).initialiseSchema();
		return new Fixture(master, archive, plan, reference, sourceBytes);
	}

	/**
	 * Returns the deterministic private staging directory of a fixture.
	 */
	private Path stagingDirectory(Fixture fixture) {
		return fixture.master().dataRoot().resolve(".subject-import-staging")
				.resolve(fixture.plan().reviewedIncoming().packageId().toString());
	}

	/**
	 * Complete disposable integration-test inputs.
	 *
	 * @param master      master application configuration
	 * @param archive     exported incoming Subject archive
	 * @param plan        fully approved non-destructive import plan
	 * @param reference   packaged inventoried PDF reference
	 * @param sourceBytes original contributor PDF bytes
	 */
	private record Fixture(ApplicationConfig master, Path archive, SubjectImportApprovalPlan plan, Reference reference,
			byte[] sourceBytes) {
	}
}
