package au.edu.eq.questionbank.service.workpackage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.ApplicationVersion;
import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.repository.identity.SqliteExamAssignmentGraphReader;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
import au.edu.eq.questionbank.repository.identity.SqliteSubjectGraphReader.SubjectGraph;
import au.edu.eq.questionbank.repository.identity.SqliteSubjectPortableRecordReader;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.workpackage.ExamWorkAssetIndex.Reference;

/**
 * Integration tests for complete, read-only Subject snapshot export.
 *
 * <p>
 * The test fixtures use the current migrated SQLite schema and real temporary
 * Subject-first directories. They cover portable graph preservation, multiple
 * syllabus versions, shared AnswerFiles, ordered mixed content, embedded
 * images, retained workbooks and rejected missing source assets.
 *
 * <p>
 * Archives are opened independently after export to verify that the portable
 * package contains the expected records and binary references. The tests also
 * check that unrelated Subjects and existing managed files are unaffected.
 */
class SubjectSnapshotExporterTest {

	private static final byte[] PNG = Base64.getDecoder().decode(
			"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwC" + "AAAAC0lEQVR42mP8/x8AAwMCAO+/WZcAAAAASUVORK5CYII=");
	@TempDir
	Path directory;

	@Test
	void assignmentArchiveContainsWholeExamAndImmutableBaseline() throws Exception {
		Fixture fixture = createAssignmentFixture("issued-assignment");
		Path archive = fixture.exportDirectory().resolve("biology-assignment.examwork");
		WorkAssignmentExporter.Request request = assignmentRequest(archive, true);
		ExamWorkManifest manifest = new WorkAssignmentExporter(fixture.database(), fixture.layout()).export(request);
		assertEquals(ExamWorkPackageType.WORK_ASSIGNMENT, manifest.packageType());
		assertEquals(request.assignmentId(), manifest.assignmentId());
		assertNotNull(manifest.baselineSnapshotId());
		assertEquals(manifest.originInstallationId(), manifest.intendedMasterInstallationId());
		assertEquals(ExamWorkManifest.ScopeLevel.EXAMS, manifest.scope().level());
		assertEquals(1, manifest.scope().examIds().size());
		assertTrue(manifest.scope().bookletIds().isEmpty());
		List<ExchangeRecord> records = readArchiveRecords(archive, "records/subject-graph.jsonl");
		List<ExchangeRecord> baseline = readArchiveRecords(archive, "baseline/initial-records.jsonl");
		assertEquals(records, baseline);
		assertEquals(1, count(records, "SUBJECT"));
		assertEquals(1, count(records, "EXAM"));
		assertEquals(2, count(records, "EXAM_BOOKLET"));
		assertEquals(1, count(records, "ANSWER_FILE"));
		assertEquals(2, count(records, "SOURCE_DOCUMENT"));
		assertEquals(1, count(records, "QUESTION"));
		assertEquals(1, count(records, "ANSWER"));
		assertEquals(1, count(records, "QUESTION_IMAGE"));
		assertEquals(1, count(records, "SHARED_QUESTION_CONTEXT"));
		ExchangeRecord exam = findRecord(records, "EXAM");
		assertEquals(manifest.scope().examIds().getFirst(), exam.recordId());
		List<ExchangeRecord> booklets = records.stream().filter(record -> record.recordType().equals("EXAM_BOOKLET"))
				.toList();
		String firstAnswerFile = booklets.get(0).values().path("answer_file_id").asText();
		String secondAnswerFile = booklets.get(1).values().path("answer_file_id").asText();

		// Both Booklets reference one shared AnswerFile.
		assertEquals(firstAnswerFile, secondAnswerFile);
		assertEquals(findRecord(records, "ANSWER_FILE").recordId().toString(), firstAnswerFile);
		assertEquals(manifest, new ExamWorkArchiveValidator().validate(archive, ApplicationVersion.current(),
				SqliteDatabase.latestSchemaVersion()));
	}

	@Test
	void assignmentArchiveExcludesUnassignedExamAndFiles() throws Exception {
		Fixture fixture = createAssignmentFixture("assignment-scope");
		execute(fixture.database(), """
				INSERT INTO source_documents (id, relative_path)
				VALUES (
				    54,
				    'subjects/Biology/exams/unassigned.pdf'
				)
				""", """
				INSERT INTO exams (
				    id, subject_id, provider_id,
				    exam_year, exam_name
				)
				VALUES (
				    32, 1, 1, 2026, 'Unassigned Examination'
				)
				""", """
				INSERT INTO exam_booklets (
				    id, exam_id, source_document_id, booklet_name
				)
				VALUES (
				    64, 32, 54, 'Unassigned Paper'
				)
				""");

		// There is deliberately no unassigned.pdf on disk.
		// It must not be needed for the selected Exam.
		Path archive = fixture.exportDirectory().resolve("isolated-assignment.examwork");
		new WorkAssignmentExporter(fixture.database(), fixture.layout()).export(assignmentRequest(archive, true));
		List<ExchangeRecord> records = readArchiveRecords(archive, "records/subject-graph.jsonl");
		assertEquals(1, count(records, "EXAM"));
		assertEquals(2, count(records, "EXAM_BOOKLET"));
		assertEquals(2, count(records, "SOURCE_DOCUMENT"));
		ExamWorkAssetIndex assets = readAssetIndex(archive);
		assertFalse(assets.references().stream()
				.anyMatch(reference -> reference.managedPath().endsWith("/unassigned.pdf")));
		assertFalse(assets.references().stream().anyMatch(reference -> reference.usage().equals("INVENTORIED")));
		assertEquals(2,
				assets.references().stream().filter(reference -> reference.usage().equals("SOURCE_PDF")).count());
	}

	@Test
	void assignmentArchiveIdentifiesCurrentAndLegacySyllabuses() throws Exception {
		Fixture fixture = createAssignmentFixture("assignment-syllabuses");
		Path archive = fixture.exportDirectory().resolve("syllabus-assignment.examwork");
		ExamWorkManifest manifest = new WorkAssignmentExporter(fixture.database(), fixture.layout())
				.export(assignmentRequest(archive, true));
		List<ExchangeRecord> contexts = readArchiveRecords(archive, "records/assignment-context.jsonl");
		assertEquals(1, contexts.size());
		ExchangeRecord context = contexts.getFirst();
		assertEquals("WORK_ASSIGNMENT_CONTEXT", context.recordType());
		assertEquals(manifest.assignmentId(), context.recordId());
		assertEquals(manifest.baselineSnapshotId(), context.revisionId());
		assertEquals(manifest.baselineSnapshotId().toString(), context.values().path("baseline_snapshot_id").asText());
		assertTrue(context.values().path("legacy_capture_enabled").asBoolean());
		assertEquals("Contributor One", context.values().path("contributor_name").asText());
		List<ExchangeRecord> records = readArchiveRecords(archive, "records/subject-graph.jsonl");
		String currentId = context.values().path("current_syllabus_id").asText();
		String legacyId = context.values().path("legacy_syllabus_id").asText();
		ExchangeRecord current = records.stream().filter(record -> record.recordType().equals("SYLLABUS_VERSION"))
				.filter(record -> record.recordId().toString().equals(currentId)).findFirst().orElseThrow();
		ExchangeRecord historical = records.stream().filter(record -> record.recordType().equals("SYLLABUS_VERSION"))
				.filter(record -> record.recordId().toString().equals(legacyId)).findFirst().orElseThrow();
		assertEquals(1, current.values().path("is_current").asInt());
		assertEquals(0, historical.values().path("is_current").asInt());
		assertEquals("Biology 2019", historical.values().path("syllabus_name").asText());
	}

	@Test
	void assignmentArchiveRetainsBaselineAfterMasterEdits() throws Exception {
		Fixture fixture = createAssignmentFixture("assignment-concurrency");
		Path archive = fixture.exportDirectory().resolve("immutable-baseline.examwork");
		new WorkAssignmentExporter(fixture.database(), fixture.layout()).export(assignmentRequest(archive, true));
		List<ExchangeRecord> baselineBefore = readArchiveRecords(archive, "baseline/initial-records.jsonl");
		ExamWorkAssetIndex assetsBefore = readAssetIndex(archive);

		// The coordinator remains free to change master records.
		execute(fixture.database(), """
				UPDATE questions
				SET question_text = 'Coordinator changed this question'
				WHERE id = 81
				""");
		Path source = fixture.layout().resolve("subjects/Biology/exams/questions.pdf");
		Files.writeString(source, "Coordinator replaced the question document");
		List<ExchangeRecord> baselineAfter = readArchiveRecords(archive, "baseline/initial-records.jsonl");
		ExamWorkAssetIndex assetsAfter = readAssetIndex(archive);
		assertEquals(baselineBefore, baselineAfter);
		assertEquals(assetsBefore, assetsAfter);
		Reference packagedPdf = assetsAfter.references().stream()
				.filter(reference -> reference.managedPath().endsWith("/questions.pdf")).findFirst().orElseThrow();

		// Original source bytes remain in the issued archive.
		try (ZipFile zip = new ZipFile(archive.toFile())) {
			ZipEntry entry = zip.getEntry(packagedPdf.archivePath());
			assertNotNull(entry);
			try (var input = zip.getInputStream(entry)) {
				assertEquals("Biology questions PDF", new String(input.readAllBytes(), StandardCharsets.UTF_8));
			}
		}
	}

	@Test
	void assignmentAssetsAreImmutableAfterMasterFileChanges() throws Exception {
		Fixture fixture = createFixture("assignment-immutable");
		seedCompleteSubject(fixture);
		SubjectAssetCollector.CollectedAssets assets = collectAssignedExamAssets(fixture,
				"assignment-immutable-snapshot.db");
		Reference questionSource = assets.index().references().stream()
				.filter(ref -> ref.managedPath().endsWith("/questions.pdf")).findFirst().orElseThrow();
		Path stagedCopy = assets.stagedFiles().get(questionSource.archivePath());
		assertNotNull(stagedCopy);
		assertTrue(Files.isRegularFile(stagedCopy));
		byte[] originalBytes = Files.readAllBytes(stagedCopy);
		assertEquals(sha256(originalBytes), questionSource.sha256());

		// Simulate a concurrent coordinator changing the master PDF
		// after the assignment's asset copy was staged.
		Path masterPdf = fixture.layout().resolve("subjects/Biology/exams/questions.pdf");
		Files.writeString(masterPdf, "Changed after assignment asset collection");
		assertEquals("Changed after assignment asset collection", Files.readString(masterPdf));

		// The contributor's staged baseline remains byte-identical.
		assertArrayEquals(originalBytes, Files.readAllBytes(stagedCopy));
		assertEquals(sha256(Files.readAllBytes(stagedCopy)), questionSource.sha256());
	}

	@Test
	void assignmentAssetsExcludeOtherExamsAndUnreferencedFiles() throws Exception {
		Fixture fixture = createFixture("assigned-assets");
		seedCompleteSubject(fixture);

		// Another Exam belongs to the same Subject, but must not
		// contribute files to the selected assignment.
		execute(fixture.database(), """
				INSERT INTO source_documents (id, relative_path)
				VALUES (
				    54,
				    'subjects/Biology/exams/unassigned.pdf'
				)
				""", """
				INSERT INTO exams (
				    id, subject_id, provider_id, exam_year, exam_name
				)
				VALUES (
				    32, 1, 1, 2026, 'Unassigned Examination'
				)
				""", """
				INSERT INTO exam_booklets (
				    id, exam_id, source_document_id, booklet_name
				)
				VALUES (
				    64, 32, 54, 'Unassigned Paper'
				)
				""");

		// Do not create the unassigned PDF. Its absence must not
		// prevent a valid different Exam being packaged.
		SubjectAssetCollector.CollectedAssets assets = collectAssignedExamAssets(fixture,
				"assigned-assets-snapshot.db");
		List<Reference> references = assets.index().references();
		assertEquals(5, references.size());
		assertEquals(2, references.stream().filter(ref -> ref.usage().equals("SOURCE_PDF")).count());
		assertEquals(1, references.stream().filter(ref -> ref.usage().equals("SYLLABUS_PDF")).count());
		assertEquals(2, references.stream().filter(ref -> ref.usage().startsWith("EMBEDDED_")).count());
		assertEquals(0, references.stream().filter(ref -> ref.usage().equals("INVENTORIED")).count());
		assertTrue(references.stream().anyMatch(ref -> ref.managedPath().endsWith("/questions.pdf")));
		assertTrue(references.stream().anyMatch(ref -> ref.managedPath().endsWith("/answers.pdf")));
		assertTrue(references.stream().anyMatch(ref -> ref.managedPath().endsWith("/syllabus.pdf")));
		assertFalse(references.stream().anyMatch(ref -> ref.managedPath().endsWith("/unassigned.pdf")));

		// Retained Subject files are not Exam assignment dependencies.
		assertFalse(references.stream().anyMatch(ref -> ref.managedPath().endsWith("/retained.xlsx")));
		assertFalse(references.stream().anyMatch(ref -> ref.managedPath().endsWith("/legacy.xlsx")));
		assertFalse(references.stream().anyMatch(ref -> ref.managedPath().endsWith("/unreferenced-notes.txt")));
	}

	@Test
	void assignmentAssetsRejectMissingRequiredPdf() throws Exception {
		Fixture fixture = createFixture("assignment-missing-source");
		seedCompleteSubject(fixture);
		Path questionPdf = fixture.layout().resolve("subjects/Biology/exams/questions.pdf");
		Files.delete(questionPdf);
		IOException failure = assertThrows(IOException.class,
				() -> collectAssignedExamAssets(fixture, "assignment-missing-source-snapshot.db"));
		assertNotNull(failure.getMessage());
		assertTrue(failure.getMessage().contains("managed asset"));
	}

	@Test
	void assignmentExportDoesNotOverwriteExistingPackage() throws Exception {
		Fixture fixture = createAssignmentFixture("assignment-overwrite");
		Path archive = fixture.exportDirectory().resolve("existing-assignment.examwork");
		byte[] existing = "Existing package must remain intact".getBytes(StandardCharsets.UTF_8);
		Files.write(archive, existing);
		assertThrows(IOException.class, () -> new WorkAssignmentExporter(fixture.database(), fixture.layout())
				.export(assignmentRequest(archive, true)));
		assertArrayEquals(existing, Files.readAllBytes(archive));
	}

	@Test
	void assignmentExportRejectsInvalidPrerequisites() throws Exception {
		Fixture fixture = createAssignmentFixture("assignment-blocked");
		execute(fixture.database(), """
				UPDATE syllabus_versions
				SET is_current = 0
				WHERE id = 12
				""");
		Path archive = fixture.exportDirectory().resolve("blocked-assignment.examwork");
		WorkAssignmentExporter exporter = new WorkAssignmentExporter(fixture.database(), fixture.layout());
		assertThrows(IOException.class, () -> exporter.export(assignmentRequest(archive, true)));
		assertFalse(Files.exists(archive));

		// Restore the current syllabus, then remove the original
		// 2019 identity required by legacy classification.
		execute(fixture.database(), """
				UPDATE syllabus_versions
				SET is_current = 1
				WHERE id = 12
				""", """
				UPDATE syllabus_versions
				SET syllabus_name = 'Biology 2018'
				WHERE id = 11
				""");
		assertThrows(IOException.class, () -> exporter.export(assignmentRequest(archive, true)));
		assertFalse(Files.exists(archive));
	}

	@Test
	void assignmentExportRejectsMissingSourceAsset() throws Exception {
		Fixture fixture = createAssignmentFixture("assignment-missing-pdf");
		Path answerPdf = fixture.layout().resolve("subjects/Biology/exams/answers.pdf");
		Files.delete(answerPdf);
		Path archive = fixture.exportDirectory().resolve("missing-pdf.examwork");
		assertThrows(IOException.class, () -> new WorkAssignmentExporter(fixture.database(), fixture.layout())
				.export(assignmentRequest(archive, true)));
		assertFalse(Files.exists(archive));
	}

	@Test
	void assignmentGraphProducesClosedPortableRecordReferences() throws Exception {
		Fixture fixture = createFixture("assignment-record-closure");
		seedCompleteSubject(fixture);
		Path snapshot = directory.resolve("assignment-record-closure-snapshot.db");
		fixture.database().createConsistentSnapshot(snapshot);
		SqliteExamAssignmentGraphReader reader = new SqliteExamAssignmentGraphReader(snapshot);
		SubjectGraph graph = reader.read(1, 31);

		// The existing portable reader checks every recognised UUID
		// relationship, including ordered content-part dependencies.
		List<ExchangeRecord> records = new SqliteSubjectPortableRecordReader(snapshot).read(graph);
		assertEquals(1, records.stream().filter(record -> record.recordType().equals("EXAM")).count());
		assertEquals(2, records.stream().filter(record -> record.recordType().equals("EXAM_BOOKLET")).count());
		assertEquals(1, records.stream().filter(record -> record.recordType().equals("ANSWER_FILE")).count());
		assertEquals(1, records.stream().filter(record -> record.recordType().equals("QUESTION")).count());
		assertEquals(1,
				records.stream().filter(record -> record.recordType().equals("SHARED_QUESTION_CONTEXT")).count());
		assertEquals(1, records.stream().filter(record -> record.recordType().equals("ANSWER")).count());
		assertEquals(2, records.stream().filter(record -> record.recordType().equals("SYLLABUS_VERSION")).count());

		// Every selected portable relationship was resolved.
		assertTrue(records.stream().allMatch(record -> record.recordId() != null));
	}

	@Test
	void cannotCloseWithoutReturnAfterContributionIntake() throws Exception {
		Fixture fixture = createAssignmentFixture("received-return-closure");
		Path archive = fixture.exportDirectory().resolve("received-return-closure.examwork");
		WorkAssignmentLedger.Assignment issued = new WorkAssignmentIssuanceService(fixture.database(), fixture.layout())
				.issue(assignmentRequest(archive, false));
		String insert = """
				INSERT INTO contribution_intake_packages (
				    package_id,
				    assignment_id,
				    return_number,
				    previous_package_id,
				    baseline_snapshot_id,
				    master_installation_id,
				    contributor_id,
				    origin_installation_id,
				    archive_sha256,
				    intake_status,
				    received_at
				)
				VALUES (
				    '11111111-1111-4111-8111-111111111111',
				    '%s',
				    1,
				    NULL,
				    '%s',
				    '%s',
				    '%s',
				    '99999999-9999-4999-8999-999999999999',
				    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
				    'READY_FOR_REVIEW',
				    '2026-11-18T00:00:00Z'
				)
				""".formatted(issued.assignmentId(), issued.baselineSnapshotId(), issued.masterInstallationId(),
				issued.contributorId());
		execute(fixture.database(), insert);
		WorkAssignmentLedger ledger = new WorkAssignmentLedger(fixture.database());
		assertThrows(IllegalStateException.class,
				() -> ledger.closeWithoutReturn(issued.assignmentId(), "No work received"));
		assertEquals(WorkAssignmentLedger.State.ACTIVE, ledger.find(issued.assignmentId()).orElseThrow().state());
		assertEquals(issued.assignmentId(),
				ledger.findActiveForExam(issued.examPortableId()).orElseThrow().assignmentId());
	}

	@Test
	void completedExamCannotReceiveAssignment() throws Exception {
		Fixture fixture = createAssignmentFixture("completed-exam-issue");
		execute(fixture.database(), """
				UPDATE exams
				SET capture_state = 'COMPLETE'
				WHERE id = 31
				""");
		Path archive = fixture.exportDirectory().resolve("completed-exam.examwork");
		SQLException failure = assertThrows(SQLException.class,
				() -> new WorkAssignmentIssuanceService(fixture.database(), fixture.layout())
						.issue(assignmentRequest(archive, false)));
		assertTrue(failure.getMessage().contains("Completed Exam cannot be assigned"));
		assertFalse(Files.exists(archive));
	}

	@Test
	void contributorPreflightAcceptsValidWholeExamAssignment() throws Exception {
		Fixture fixture = createAssignmentFixture("contributor-inspection");
		Path archive = fixture.exportDirectory().resolve("contributor.examwork");
		ExamWorkManifest issued = new WorkAssignmentExporter(fixture.database(), fixture.layout())
				.export(assignmentRequest(archive, true));
		WorkAssignmentPackageInspector.Inspection inspection = new WorkAssignmentPackageInspector().inspect(archive,
				ApplicationVersion.current(), SqliteDatabase.latestSchemaVersion());
		assertEquals(issued, inspection.manifest());
		assertEquals(inspection.records(), inspection.baseline());
		assertEquals(1, inspection.records().stream().filter(record -> record.recordType().equals("EXAM")).count());
		assertEquals(2,
				inspection.records().stream().filter(record -> record.recordType().equals("EXAM_BOOKLET")).count());
		assertEquals(issued.assignmentId(), inspection.context().recordId());
		assertTrue(inspection.archiveSha256().matches("[0-9a-f]{64}"));
	}

	@Test
	void contributorPreflightRejectsOrdinarySubjectSnapshot() throws Exception {
		Fixture fixture = createAssignmentFixture("contributor-snapshot-rejection");
		Path archive = fixture.exportDirectory().resolve("subject-only.examwork");
		new SubjectSnapshotExporter(fixture.database(), fixture.layout()).export(1, archive);
		PackageFormatException failure = assertThrows(PackageFormatException.class,
				() -> new WorkAssignmentPackageInspector().inspect(archive, ApplicationVersion.current(),
						SqliteDatabase.latestSchemaVersion()));
		assertTrue(failure.getMessage().contains("WORK_ASSIGNMENT"));
	}

	@Test
	void coordinatorClosureDoesNotRewriteBaseline() throws Exception {
		Fixture fixture = createAssignmentFixture("assignment-completion");
		Path archive = fixture.exportDirectory().resolve("completion.examwork");
		WorkAssignmentLedger.Assignment issued = new WorkAssignmentIssuanceService(fixture.database(), fixture.layout())
				.issue(assignmentRequest(archive, false));
		WorkAssignmentLedger ledger = new WorkAssignmentLedger(fixture.database());
		WorkAssignmentLedger.Baseline before = ledger.readBaseline(issued.assignmentId());
		WorkAssignmentLedger.Assignment closed = ledger.closeWithoutReturn(issued.assignmentId(),
				"Contributor is no longer available");
		assertEquals(WorkAssignmentLedger.State.CANCELLED, closed.state());
		WorkAssignmentLedger.Baseline after = ledger.readBaseline(issued.assignmentId());
		assertArrayEquals(before.records(), after.records());
		assertArrayEquals(before.assets(), after.assets());
		assertTrue(ledger.findActiveForExam(issued.examPortableId()).isEmpty());
		assertThrows(IllegalStateException.class,
				() -> ledger.closeWithoutReturn(issued.assignmentId(), "Duplicate closure"));
	}

	@Test
	void duplicateAssignmentRegistrationIsRejected() throws Exception {
		Fixture fixture = createAssignmentFixture("assignment-duplicate");
		Path archive = fixture.exportDirectory().resolve("duplicate.examwork");
		WorkAssignmentLedger.Assignment issued = new WorkAssignmentIssuanceService(fixture.database(), fixture.layout())
				.issue(assignmentRequest(archive, true));
		WorkAssignmentLedger ledger = new WorkAssignmentLedger(fixture.database());
		WorkAssignmentLedger.Baseline before = ledger.readBaseline(issued.assignmentId());

		// The same issued archive cannot create a second assignment.
		assertThrows(SQLException.class, () -> ledger.register(archive));
		WorkAssignmentLedger.Baseline after = ledger.readBaseline(issued.assignmentId());
		assertArrayEquals(before.records(), after.records());
		assertArrayEquals(before.assets(), after.assets());
		assertEquals(WorkAssignmentLedger.State.ACTIVE, ledger.find(issued.assignmentId()).orElseThrow().state());
	}

	/**
	 * Verifies export is observational and leaves existing Subject records, other
	 * Subjects and source files unchanged.
	 */
	@Test
	void exportDoesNotModifyDatabaseOrManagedSources() throws Exception {
		Fixture fixture = createFixture("unchanged");
		seedCompleteSubject(fixture);
		Path source = fixture.layout().dataRoot().resolve("subjects/Biology/exams/questions.pdf");
		byte[] before = Files.readAllBytes(source);
		SqlitePortableIdentityRepository identities = new SqlitePortableIdentityRepository(fixture.database());
		UUID beforeIdentity = identities.requireByLocalId(EntityType.QUESTION, 81).portableId();
		Path archive = fixture.exportDirectory().resolve("unchanged.examwork");
		exporter(fixture).export(1, archive);
		assertArrayEquals(before, Files.readAllBytes(source));
		assertEquals(beforeIdentity, identities.requireByLocalId(EntityType.QUESTION, 81).portableId());
		assertEquals(2, countSubjectExams(fixture.database()));
		assertEquals("subjects/Biology/exams/questions.pdf", sourcePath(fixture.database(), 51));
		assertEquals("subjects/Chemistry/exams/questions.pdf", sourcePath(fixture.database(), 53));
		assertTrue(Files.isRegularFile(archive));
	}

	/**
	 * Verifies the minimum valid package contains one Subject record and an empty
	 * asset index.
	 */
	@Test
	void exportsEmptySubjectWithNoAssets() throws Exception {
		Fixture fixture = createFixture("empty");
		execute(fixture.database(), """
				INSERT INTO subjects (id, subject_name)
				VALUES (1, 'Biology')
				""");
		Path archive = fixture.exportDirectory().resolve("empty.examwork");
		ExamWorkManifest manifest = exporter(fixture).export(1, archive);
		assertEquals(ExamWorkPackageType.SUBJECT_SNAPSHOT, manifest.packageType());
		assertEquals("Biology", manifest.subjectName());
		assertTrue(Files.isRegularFile(archive));
		List<ExchangeRecord> records = readRecords(archive);
		assertEquals(1, records.size());
		assertEquals("SUBJECT", records.getFirst().recordType());
		ExamWorkAssetIndex index = readAssetIndex(archive);
		assertTrue(index.references().isEmpty());
		assertEquals(manifest, new ExamWorkArchiveValidator().validate(archive, ApplicationVersion.current(),
				SqliteDatabase.latestSchemaVersion()));
	}

	@Test
	void exportsExamWorkExtensionAndValidatesIt() throws Exception {
		Fixture fixture = createFixture("modern-extension");
		execute(fixture.database(), """
				INSERT INTO subjects (id, subject_name)
				VALUES (1, 'Biology')
				""");
		Path archive = fixture.exportDirectory().resolve("Biology_SubjectSnapshot_20261112-164700.examwork");
		ExamWorkManifest manifest = exporter(fixture).export(1, archive);
		assertTrue(Files.isRegularFile(archive));
		assertEquals(manifest, new ExamWorkArchiveValidator().validate(archive, ApplicationVersion.current(),
				SqliteDatabase.latestSchemaVersion()));
	}

	/**
	 * Verifies multiple syllabus versions and the complete mixed-content graph
	 * survive export while other Subjects remain excluded.
	 */
	@Test
	void exportsFullGraphAndPreservesSharedRelationships() throws Exception {
		Fixture fixture = createFixture("complete");
		seedCompleteSubject(fixture);
		Path archive = fixture.exportDirectory().resolve("biology.examwork");
		ExamWorkManifest manifest = exporter(fixture).export(1, archive);
		List<ExchangeRecord> records = readRecords(archive);
		assertEquals(22, records.size());
		assertEquals(2, count(records, "SYLLABUS_VERSION"));
		assertEquals(6, count(records, "CURRICULUM_NODE"));
		assertEquals(2, count(records, "EXAM_BOOKLET"));
		assertEquals(1, count(records, "ANSWER_FILE"));
		assertEquals(1, count(records, "QUESTION_IMAGE"));
		assertEquals(1, count(records, "SHARED_QUESTION_CONTEXT"));
		assertEquals(1, count(records, "CURRICULUM_MAPPING"));
		Set<String> subjectNames = records.stream().filter(record -> record.recordType().equals("SUBJECT"))
				.map(record -> record.values().path("subject_name").asText()).collect(Collectors.toSet());
		assertEquals(Set.of("Biology"), subjectNames);
		List<ExchangeRecord> booklets = records.stream().filter(record -> record.recordType().equals("EXAM_BOOKLET"))
				.toList();

		// Two separate Booklets share one logical AnswerFile.
		String firstAnswerFile = booklets.get(0).values().path("answer_file_id").asText();
		String secondAnswerFile = booklets.get(1).values().path("answer_file_id").asText();
		assertEquals(firstAnswerFile, secondAnswerFile);
		ExchangeRecord answerFile = findRecord(records, "ANSWER_FILE");
		assertEquals(answerFile.recordId().toString(), firstAnswerFile);
		ExchangeRecord question = findRecord(records, "QUESTION");
		assertEquals(1, question.values().path("question_regions").size());
		assertEquals(2, question.values().path("question_content_parts").size());
		assertEquals("PDF_REGION",
				question.values().path("question_content_parts").get(0).path("content_type").asText());
		assertEquals("IMAGE", question.values().path("question_content_parts").get(1).path("content_type").asText());
		assertEquals(1, question.values().path("question_output_exclusions").size());
		ExchangeRecord context = findRecord(records, "SHARED_QUESTION_CONTEXT");
		assertEquals(2, context.values().path("shared_question_context_content_parts").size());
		assertEquals("PDF_REGION",
				context.values().path("shared_question_context_content_parts").get(0).path("content_type").asText());
		assertEquals("IMAGE",
				context.values().path("shared_question_context_content_parts").get(1).path("content_type").asText());
		ExchangeRecord answer = findRecord(records, "ANSWER");
		assertEquals(1, answer.values().path("answer_regions").size());
		ExchangeRecord exam = findRecord(records, "EXAM");
		assertEquals("COMPLETE", exam.values().path("capture_state").asText());
		assertEquals(2, exam.values().path("expected_question_booklet_count").asInt());
		assertEquals(1, count(records, "EXAM_PROVIDER"));
		assertEquals(2, count(records, "SOURCE_DOCUMENT"));
		assertEquals(manifest, new ExamWorkArchiveValidator().validate(archive, ApplicationVersion.current(),
				SqliteDatabase.latestSchemaVersion()));
	}

	@Test
	void failedIssueLeavesNoPublishedArchive() throws Exception {
		Fixture fixture = createAssignmentFixture("failed-issue");

		// Destroy the explicit current-syllabus selection.
		execute(fixture.database(), """
				UPDATE syllabus_versions
				SET is_current = 0
				WHERE id = 12
				""");
		Path archive = fixture.exportDirectory().resolve("invalid-issue.examwork");
		assertThrows(IOException.class, () -> new WorkAssignmentIssuanceService(fixture.database(), fixture.layout())
				.issue(assignmentRequest(archive, true)));
		assertFalse(Files.exists(archive));
	}

	/**
	 * Verifies that referenced assets and retained unreferenced files are present,
	 * content-addressed and attributed correctly.
	 */
	@Test
	void inventoriesWorkbooksPdfsAndEmbeddedImages() throws Exception {
		Fixture fixture = createFixture("inventory");
		seedCompleteSubject(fixture);
		Path archive = fixture.exportDirectory().resolve("assets.examwork");
		exporter(fixture).export(1, archive);
		ExamWorkAssetIndex index = readAssetIndex(archive);
		assertEquals(8, index.references().size());
		assertEquals(3,
				index.references().stream().filter(reference -> reference.usage().equals("INVENTORIED")).count());
		assertEquals(2,
				index.references().stream().filter(reference -> reference.usage().startsWith("EMBEDDED_")).count());
		assertEquals(2,
				index.references().stream().filter(reference -> reference.usage().equals("SOURCE_PDF")).count());
		assertEquals(1,
				index.references().stream().filter(reference -> reference.usage().equals("SYLLABUS_PDF")).count());
		assertTrue(
				index.references().stream().anyMatch(reference -> reference.managedPath().endsWith("/retained.xlsx")));
		assertTrue(index.references().stream().anyMatch(reference -> reference.managedPath().endsWith("/legacy.xlsx")));
		assertTrue(index.references().stream()
				.anyMatch(reference -> reference.managedPath().endsWith("/unreferenced-notes.txt")));
		try (ZipFile zip = new ZipFile(archive.toFile())) {
			for (Reference reference : index.references()) {
				ZipEntry entry = zip.getEntry(reference.archivePath());
				assertNotNull(entry, "Missing binary archive entry: " + reference.archivePath());
				assertEquals(reference.size(), entry.getSize());
			}
		}

		// Both embedded PNG usages must contain the original bytes.
		try (ZipFile zip = new ZipFile(archive.toFile())) {
			for (Reference reference : index.references()) {
				if (!reference.usage().startsWith("EMBEDDED_")) {
					continue;
				}
				ZipEntry entry = zip.getEntry(reference.archivePath());
				try (var input = zip.getInputStream(entry)) {
					assertArrayEquals(PNG, input.readAllBytes());
				}
			}
		}
	}

	@Test
	void issueRegistersAnImmutableWholeExamBaseline() throws Exception {
		Fixture fixture = createAssignmentFixture("registered-assignment");
		Path archive = fixture.exportDirectory().resolve("registered.examwork");

		WorkAssignmentExporter.Request request = assignmentRequest(archive, true);

		WorkAssignmentLedger.Assignment issued = new WorkAssignmentIssuanceService(fixture.database(), fixture.layout())
				.issue(request);

		assertEquals(request.assignmentId(), issued.assignmentId());
		assertEquals(WorkAssignmentLedger.State.ACTIVE, issued.state());

		WorkAssignmentLedger ledger = new WorkAssignmentLedger(fixture.database());

		WorkAssignmentLedger.Baseline baseline = ledger.readBaseline(issued.assignmentId());

		// The published archive and the retained SQLite baseline
		// must contain identical immutable records and asset references.
		try (ZipFile zip = new ZipFile(archive.toFile())) {
			ZipEntry recordsEntry = zip.getEntry("baseline/initial-records.jsonl");

			ZipEntry assetsEntry = zip.getEntry(ExamWorkAssetIndex.INDEX_PATH);

			assertNotNull(recordsEntry);
			assertNotNull(assetsEntry);

			try (var input = zip.getInputStream(recordsEntry)) {
				assertArrayEquals(input.readAllBytes(), baseline.records());
			}

			try (var input = zip.getInputStream(assetsEntry)) {
				assertArrayEquals(input.readAllBytes(), baseline.assets());
			}
		}

		// An active assignment protects the authoritative Exam.
		SQLException blocked = assertThrows(SQLException.class, () -> execute(fixture.database(), """
				UPDATE exams
				SET exam_name = 'Changed by coordinator'
				WHERE id = 31
				"""));

		assertTrue(blocked.getMessage().contains("Assigned Exam is read-only on the master"));

		assertEquals(WorkAssignmentLedger.State.ACTIVE, ledger.find(issued.assignmentId()).orElseThrow().state());

		// The coordinator explicitly closes the assignment.
		WorkAssignmentLedger.Assignment closed = ledger.closeWithoutReturn(issued.assignmentId(),
				"Contributor unavailable");

		assertEquals(WorkAssignmentLedger.State.CANCELLED, closed.state());

		assertTrue(ledger.findActiveForExam(issued.examPortableId()).isEmpty());

		// The master can now be changed. This must not rewrite
		// the baseline belonging to the cancelled assignment.
		execute(fixture.database(), """
				UPDATE questions
				SET question_text = 'Changed after assignment closure'
				WHERE id = 81
				""");

		WorkAssignmentLedger.Baseline retained = ledger.readBaseline(issued.assignmentId());

		assertArrayEquals(baseline.records(), retained.records());
		assertArrayEquals(baseline.assets(), retained.assets());

		assertEquals(WorkAssignmentLedger.State.CANCELLED, ledger.find(issued.assignmentId()).orElseThrow().state());
	}

	/**
	 * Verifies an incorrect stored source digest causes a failure instead of
	 * publication with an inconsistent asset reference.
	 */
	@Test
	void mismatchedSourceHashFailsWithoutPublishing() throws Exception {
		Fixture fixture = createFixture("digest");
		seedCompleteSubject(fixture);
		execute(fixture.database(), """
				UPDATE source_documents
				SET content_sha256 =
				    '00000000000000000000000000000000'
				    || '00000000000000000000000000000000'
				WHERE id = 51
				""");
		Path archive = fixture.exportDirectory().resolve("digest.examwork");
		IOException failure = assertThrows(IOException.class, () -> exporter(fixture).export(1, archive));
		assertTrue(failure.getMessage().contains("Stored SHA-256 differs"));
		assertFalse(Files.exists(archive));
	}

	/**
	 * Verifies an absent referenced PDF prevents successful publication.
	 */
	@Test
	void missingReferencedPdfFailsWithoutPublishing() throws Exception {
		Fixture fixture = createFixture("missing");
		seedCompleteSubject(fixture);
		Files.delete(fixture.layout().dataRoot().resolve("subjects/Biology/exams/questions.pdf"));
		Path archive = fixture.exportDirectory().resolve("missing.examwork");
		IOException failure = assertThrows(IOException.class, () -> exporter(fixture).export(1, archive));
		assertTrue(failure.getMessage().contains("Missing or invalid managed asset"));
		assertFalse(Files.exists(archive));
		assertEquals(2, countSubjectExams(fixture.database()));
	}

	@Test
	void onlyOneActiveAssignmentAndReissueAfterClosure() throws Exception {
		Fixture fixture = createAssignmentFixture("multiple-assignments");
		Path firstArchive = fixture.exportDirectory().resolve("first.examwork");
		Path secondArchive = fixture.exportDirectory().resolve("second.examwork");
		WorkAssignmentExporter.Request first = assignmentRequest(firstArchive, true);
		WorkAssignmentExporter.Request second = new WorkAssignmentExporter.Request(first.exam(), UUID.randomUUID(),
				UUID.randomUUID(), "Contributor Two", false, secondArchive);
		WorkAssignmentIssuanceService issuance = new WorkAssignmentIssuanceService(fixture.database(),
				fixture.layout());
		WorkAssignmentLedger ledger = new WorkAssignmentLedger(fixture.database());
		WorkAssignmentLedger.Assignment firstIssued = issuance.issue(first);
		WorkAssignmentLedger.Baseline firstBaseline = ledger.readBaseline(firstIssued.assignmentId());
		IOException blocked = assertThrows(IOException.class, () -> issuance.issue(second));
		assertTrue(blocked.getMessage().contains("already has an active work assignment"));
		assertFalse(Files.exists(secondArchive));
		assertEquals(firstIssued.assignmentId(),
				ledger.findActiveForExam(firstIssued.examPortableId()).orElseThrow().assignmentId());
		ledger.closeWithoutReturn(firstIssued.assignmentId(), "Reassigned to a different contributor");
		assertTrue(ledger.findActiveForExam(firstIssued.examPortableId()).isEmpty());

		// The second package must reflect the latest authoritative
		// master data, rather than reusing the first baseline.
		execute(fixture.database(), """
				UPDATE questions
				SET question_text = 'Coordinator revised the question'
				WHERE id = 81
				""");
		WorkAssignmentLedger.Assignment secondIssued = issuance.issue(second);
		WorkAssignmentLedger.Baseline secondBaseline = ledger.readBaseline(secondIssued.assignmentId());
		assertEquals(firstIssued.examPortableId(), secondIssued.examPortableId());
		assertFalse(firstIssued.assignmentId().equals(secondIssued.assignmentId()));
		assertFalse(Arrays.equals(firstBaseline.records(), secondBaseline.records()));
		assertEquals(WorkAssignmentLedger.State.CANCELLED,
				ledger.find(firstIssued.assignmentId()).orElseThrow().state());
		assertEquals(WorkAssignmentLedger.State.ACTIVE, ledger.find(secondIssued.assignmentId()).orElseThrow().state());
		assertEquals(2, ledger.findForExam(firstIssued.examPortableId()).size());
	}

	/**
	 * Verifies that Subject snapshot export preserves a long alternating sequence
	 * of PDF regions and embedded PNG images.
	 *
	 * <p>
	 * Question and Shared Context content are deliberately inserted into SQLite out
	 * of content order. The exported JSONL must retain the authoritative
	 * content_order sequence, the correct PDF region references and the correct
	 * portable image references.
	 *
	 * <p>
	 * Three distinct PNG byte sequences are each used by both the Question and
	 * Shared Context. The package must retain six logical image references while
	 * storing only three distinct physical PNG archive entries.
	 *
	 * @throws Exception if fixture creation, export or archive validation fails
	 */
	@Test
	void preservesLongAlternatingPdfAndPngSequence() throws Exception {
		Fixture fixture = createFixture("alternating-content");
		seedCompleteSubject(fixture);
		SqliteDatabase database = fixture.database();
		byte[] redPng = pngPixel(0xFFCC2200);
		byte[] bluePng = pngPixel(0xFF0055CC);

		// Insert PDF regions in reverse order to prove that export
		// uses region_order rather than SQLite insertion order.
		execute(database, """
				INSERT INTO question_regions (
				    question_id, region_order, booklet_id,
				    page_number, x, y, width, height
				)
				VALUES
				    (81, 2, 61, 4, 0.1, 0.2, 0.4, 0.3),
				    (81, 1, 61, 3, 0.2, 0.1, 0.5, 0.4)
				""", """
				INSERT INTO shared_question_context_regions (
				    shared_context_id, region_order,
				    page_number, x, y, width, height
				)
				VALUES
				    (121, 2, 3, 0.1, 0.2, 0.4, 0.3),
				    (121, 1, 2, 0.2, 0.1, 0.5, 0.4)
				""");

		// The existing fixture contains Question image 131 and
		// Shared Context image_order 0, both using PNG.
		// Add two more distinct PNGs to each owner in reverse order.
		try (Connection connection = database.openConnection();
				PreparedStatement questionStatement = connection.prepareStatement("""
						INSERT INTO question_images (
						    id, question_id, image_png
						)
						VALUES (?, 81, ?)
						""");
				PreparedStatement contextStatement = connection.prepareStatement("""
						INSERT INTO shared_question_context_images (
						    shared_context_id, image_order, image_png
						)
						VALUES (121, ?, ?)
						""")) {
			for (int imageOrder = 2; imageOrder >= 1; imageOrder--) {
				byte[] bytes = imageOrder == 1 ? redPng : bluePng;
				questionStatement.setLong(1, 131L + imageOrder);
				questionStatement.setBytes(2, bytes);
				questionStatement.executeUpdate();
				contextStatement.setInt(1, imageOrder);
				contextStatement.setBytes(2, bytes);
				contextStatement.executeUpdate();
			}
		}

		// Append the remaining content parts out of sequence.
		// The intended sequence is PDF, IMAGE, PDF, IMAGE,
		// PDF, IMAGE for both owners.
		execute(database, """
				INSERT INTO question_content_parts (
				    question_id, content_order, content_type,
				    region_order, image_id
				)
				VALUES
				    (81, 5, 'IMAGE', NULL, 133),
				    (81, 4, 'PDF_REGION', 2, NULL),
				    (81, 3, 'IMAGE', NULL, 132),
				    (81, 2, 'PDF_REGION', 1, NULL)
				""", """
				INSERT INTO shared_question_context_content_parts (
				    shared_context_id, content_order, content_type,
				    region_order, image_order
				)
				VALUES
				    (121, 5, 'IMAGE', NULL, 2),
				    (121, 4, 'PDF_REGION', 2, NULL),
				    (121, 3, 'IMAGE', NULL, 1),
				    (121, 2, 'PDF_REGION', 1, NULL)
				""");
		SqlitePortableIdentityRepository identities = new SqlitePortableIdentityRepository(database);
		List<String> expectedQuestionImageIds = List.of(
				identities.requireByLocalId(EntityType.QUESTION_IMAGE, 131).portableId().toString(),
				identities.requireByLocalId(EntityType.QUESTION_IMAGE, 132).portableId().toString(),
				identities.requireByLocalId(EntityType.QUESTION_IMAGE, 133).portableId().toString());
		Path archive = fixture.exportDirectory().resolve("alternating-content.examwork");
		ExamWorkManifest manifest = exporter(fixture).export(1, archive);

		// Independently validate the published archive.
		assertEquals(manifest, new ExamWorkArchiveValidator().validate(archive, ApplicationVersion.current(),
				SqliteDatabase.latestSchemaVersion()));
		List<ExchangeRecord> records = readRecords(archive);
		assertEquals(3, count(records, "QUESTION_IMAGE"));
		ExchangeRecord question = findRecord(records, "QUESTION");
		ExchangeRecord context = findRecord(records, "SHARED_QUESTION_CONTEXT");
		var questionRegions = question.values().path("question_regions");
		var questionParts = question.values().path("question_content_parts");
		var contextRegions = context.values().path("shared_question_context_regions");
		var contextImages = context.values().path("shared_question_context_images");
		var contextParts = context.values().path("shared_question_context_content_parts");
		assertEquals(3, questionRegions.size());
		assertEquals(3, contextRegions.size());
		assertEquals(3, contextImages.size());
		assertEquals(6, questionParts.size());
		assertEquals(6, contextParts.size());
		List<byte[]> expectedImages = List.of(PNG, redPng, bluePng);

		// Confirm that PDF region ordering remains authoritative
		// independently of the content-part sequence.
		for (int regionOrder = 0; regionOrder < 3; regionOrder++) {
			assertEquals(regionOrder, questionRegions.get(regionOrder).path("region_order").asInt());
			assertEquals(regionOrder, contextRegions.get(regionOrder).path("region_order").asInt());
			assertEquals(regionOrder, contextImages.get(regionOrder).path("image_order").asInt());
			assertEquals(sha256(expectedImages.get(regionOrder)),
					contextImages.get(regionOrder).path("image_png").asText());
		}

		// Verify each position, its content type, and the
		// precise referenced region or portable image identity.
		for (int position = 0; position < 6; position++) {
			var questionPart = questionParts.get(position);
			var contextPart = contextParts.get(position);
			assertEquals(position, questionPart.path("content_order").asInt());
			assertEquals(position, contextPart.path("content_order").asInt());
			if (position % 2 == 0) {
				int regionOrder = position / 2;
				assertEquals("PDF_REGION", questionPart.path("content_type").asText());
				assertEquals("PDF_REGION", contextPart.path("content_type").asText());
				assertEquals(regionOrder, questionPart.path("region_order").asInt());
				assertEquals(regionOrder, contextPart.path("region_order").asInt());
				assertTrue(questionPart.path("image_id").isNull());
				assertTrue(contextPart.path("image_order").isNull());
			} else {
				int imageOrder = position / 2;
				assertEquals("IMAGE", questionPart.path("content_type").asText());
				assertEquals("IMAGE", contextPart.path("content_type").asText());
				assertEquals(expectedQuestionImageIds.get(imageOrder), questionPart.path("image_id").asText());
				assertEquals(imageOrder, contextPart.path("image_order").asInt());
				assertTrue(questionPart.path("region_order").isNull());
				assertTrue(contextPart.path("region_order").isNull());
			}
		}

		// Check the independently represented QuestionImage records.
		for (int imageOrder = 0; imageOrder < 3; imageOrder++) {
			String portableId = expectedQuestionImageIds.get(imageOrder);
			String expectedHash = sha256(expectedImages.get(imageOrder));
			ExchangeRecord imageRecord = records.stream().filter(record -> record.recordType().equals("QUESTION_IMAGE"))
					.filter(record -> record.recordId().toString().equals(portableId)).findFirst().orElseThrow();
			assertEquals(expectedHash, imageRecord.values().path("image_png").asText());
		}
		ExamWorkAssetIndex index = readAssetIndex(archive);
		List<Reference> pngReferences = index.references().stream()
				.filter(reference -> reference.usage().startsWith("EMBEDDED_")).toList();
		assertEquals(6, pngReferences.size());
		assertEquals(3,
				pngReferences.stream().filter(reference -> reference.usage().equals("EMBEDDED_QUESTION_PNG")).count());
		assertEquals(3,
				pngReferences.stream().filter(reference -> reference.usage().equals("EMBEDDED_CONTEXT_PNG")).count());

		// Six logical usages share three physical content-addressed
		// PNG assets. The index must retain all six references.
		assertEquals(3, pngReferences.stream().map(Reference::archivePath).distinct().count());

		// Verify each physical asset's actual archive bytes
		// against the expected PNG SHA-256 and original content.
		try (ZipFile zip = new ZipFile(archive.toFile())) {
			for (Reference reference : pngReferences) {
				byte[] expected = null;
				for (byte[] candidate : expectedImages) {
					if (sha256(candidate).equals(reference.sha256())) {
						expected = candidate;
						break;
					}
				}
				assertNotNull(expected, "Unexpected embedded image digest: " + reference.sha256());
				ZipEntry entry = zip.getEntry(reference.archivePath());
				assertNotNull(entry);
				try (var input = zip.getInputStream(entry)) {
					assertArrayEquals(expected, input.readAllBytes());
				}
			}
		}
	}

	@Test
	void registersAssignmentWithPortableSourceDocumentsWithoutManagedPaths() throws Exception {
		Fixture fixture = createAssignmentFixture("portable-source-registration");
		Path archive = fixture.exportDirectory().resolve("portable-source-registration.examwork");
		WorkAssignmentExporter.Request request = assignmentRequest(archive, true);

		// Export the same whole-Exam package that the coordinator
		// registers during ordinary assignment issuance.
		new WorkAssignmentExporter(fixture.database(), fixture.layout()).export(request);
		List<ExchangeRecord> records = readRecords(archive);
		List<ExchangeRecord> sourceDocuments = records.stream()
				.filter(record -> record.recordType().equals("SOURCE_DOCUMENT")).toList();
		assertEquals(2, sourceDocuments.size());

		// Portable records omit machine-local managed paths by design.
		// The asset-reference index owns those paths instead.
		for (ExchangeRecord document : sourceDocuments) {
			assertFalse(document.values().has("relative_path"));
			long references = readAssetIndex(archive).references().stream()
					.filter(reference -> "SOURCE_PDF".equals(reference.usage()))
					.filter(reference -> "SOURCE_DOCUMENT".equals(reference.ownerType()))
					.filter(reference -> document.recordId().equals(reference.ownerId())).count();
			assertEquals(1, references);
		}
		WorkAssignmentLedger.Assignment issued = new WorkAssignmentLedger(fixture.database()).register(archive);
		assertEquals(request.assignmentId(), issued.assignmentId());
		assertEquals(WorkAssignmentLedger.State.ACTIVE, issued.state());
	}

	/**
	 * Verifies an existing destination cannot be replaced by a later export.
	 */
	@Test
	void rejectsExistingDestinationWithoutOverwriting() throws Exception {
		Fixture fixture = createFixture("existing");
		execute(fixture.database(), """
				INSERT INTO subjects (id, subject_name)
				VALUES (1, 'Biology')
				""");
		Path archive = fixture.exportDirectory().resolve("existing.examwork");
		byte[] original = "existing package".getBytes(StandardCharsets.UTF_8);
		Files.write(archive, original);
		assertThrows(IOException.class, () -> exporter(fixture).export(1, archive));
		assertArrayEquals(original, Files.readAllBytes(archive));
	}

	@Test
	void rejectsSubjectSnapshotAsWorkAssignment() throws Exception {
		Fixture fixture = createAssignmentFixture("assignment-package-type");
		Path archive = fixture.exportDirectory().resolve("subject-snapshot.examwork");
		new SubjectSnapshotExporter(fixture.database(), fixture.layout()).export(1, archive);
		WorkAssignmentLedger ledger = new WorkAssignmentLedger(fixture.database());
		PackageFormatException failure = assertThrows(PackageFormatException.class, () -> ledger.register(archive));
		assertTrue(failure.getMessage().contains("WORK_ASSIGNMENT"));
	}

	private WorkAssignmentExporter.Request assignmentRequest(Path destination, boolean legacyCaptureEnabled) {
		Exam exam = new Exam(31, new Subject(1, "Biology"), new ExamProvider(1, "QCAA"), 2025, "External Assessment");
		return new WorkAssignmentExporter.Request(exam, UUID.fromString("f5324089-e12c-4f98-a017-7bca42bf4b35"),
				UUID.fromString("bcb630ef-c798-48ae-8615-71d61e4d44f4"), "Contributor One", legacyCaptureEnabled,
				destination);
	}

	private SubjectAssetCollector.CollectedAssets collectAssignedExamAssets(Fixture fixture, String snapshotName)
			throws Exception {
		Path snapshot = directory.resolve(snapshotName);
		fixture.database().createConsistentSnapshot(snapshot);
		SqliteExamAssignmentGraphReader reader = new SqliteExamAssignmentGraphReader(snapshot);
		SubjectGraph graph = reader.read(1, 31);
		Path staging = Files.createTempDirectory(directory, "assignment-assets-stage-");
		SubjectAssetCollector collector = new SubjectAssetCollector(SqliteDatabase.readOnly(snapshot), fixture.layout(),
				staging);
		return collector.collectAssignment(graph);
	}

	private long count(List<ExchangeRecord> records, String type) {
		return records.stream().filter(record -> record.recordType().equals(type)).count();
	}

	private int countSubjectExams(SqliteDatabase database) throws SQLException {
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT COUNT(*)
						FROM exams
						""")) {
			result.next();
			return result.getInt(1);
		}
	}

	private Fixture createAssignmentFixture(String name) throws Exception {
		Fixture fixture = createFixture(name);
		seedCompleteSubject(fixture);

		// Assignment tests require an editable Exam. The generic
		// Subject snapshot fixture deliberately seeds a COMPLETE Exam.
		execute(fixture.database(), """
				UPDATE exams
				SET capture_state = 'ACTIVE',
				    expected_question_booklet_count = 2,
				    expected_answer_file_count = 1
				WHERE id = 31
				""");
		return fixture;
	}

	private Fixture createFixture(String name) throws Exception {
		Path root = directory.resolve(name);
		Files.createDirectories(root);
		ManagedDataLayout layout = new ManagedDataLayout(root);
		SqliteDatabase database = new SqliteDatabase(root.resolve("questionbank.db"));
		database.initialiseSchema();
		Path exports = directory.resolve(name + "-exports");
		Files.createDirectories(exports);
		return new Fixture(database, layout, exports);
	}

	private void createManagedFile(ManagedDataLayout layout, String relativePath, String contents) throws IOException {
		Path destination = layout.resolve(relativePath);
		Files.createDirectories(destination.getParent());
		Files.writeString(destination, contents, StandardCharsets.UTF_8);
	}

	private void execute(SqliteDatabase database, String... statements) throws SQLException {
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			for (String sql : statements) {
				statement.execute(sql);
			}
		}
	}

	private SubjectSnapshotExporter exporter(Fixture fixture) {
		return new SubjectSnapshotExporter(fixture.database(), fixture.layout());
	}

	private ExchangeRecord findRecord(List<ExchangeRecord> records, String type) {
		return records.stream().filter(record -> record.recordType().equals(type)).findFirst().orElseThrow();
	}

	/**
	 * Creates an actual single-pixel PNG with the supplied ARGB colour.
	 *
	 * @param argb packed alpha, red, green and blue channel values
	 * @return complete encoded PNG file bytes
	 * @throws IOException if the PNG cannot be encoded
	 */
	private byte[] pngPixel(int argb) throws IOException {
		BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
		image.setRGB(0, 0, argb);
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		if (!ImageIO.write(image, "png", output)) {
			throw new IOException("PNG encoder is unavailable");
		}
		return output.toByteArray();
	}

	private List<ExchangeRecord> readArchiveRecords(Path archive, String entryPath) throws IOException {
		try (ZipFile zip = new ZipFile(archive.toFile())) {
			ZipEntry entry = zip.getEntry(entryPath);
			assertNotNull(entry, "Missing archive entry: " + entryPath);
			try (var input = zip.getInputStream(entry)) {
				return new ExamWorkJsonlCodec().readRecords(input);
			}
		}
	}

	private ExamWorkAssetIndex readAssetIndex(Path archive) throws Exception {
		try (ZipFile zip = new ZipFile(archive.toFile())) {
			ZipEntry entry = zip.getEntry(ExamWorkAssetIndex.INDEX_PATH);
			assertNotNull(entry);
			try (var input = zip.getInputStream(entry)) {
				return new ExamWorkJsonlCodec().readAssetIndex(input);
			}
		}
	}

	private List<ExchangeRecord> readRecords(Path archive) throws Exception {
		try (ZipFile zip = new ZipFile(archive.toFile())) {
			ZipEntry entry = zip.getEntry("records/subject-graph.jsonl");
			assertNotNull(entry);
			try (var input = zip.getInputStream(entry)) {
				return new ExamWorkJsonlCodec().readRecords(input);
			}
		}
	}

	private void seedCompleteSubject(Fixture fixture) throws Exception {
		ManagedDataLayout layout = fixture.layout();
		createManagedFile(layout, "subjects/Biology/exams/questions.pdf", "Biology questions PDF");
		createManagedFile(layout, "subjects/Biology/exams/answers.pdf", "Biology answers PDF");
		createManagedFile(layout, "subjects/Biology/curriculum/Biology 2025/sources/syllabus.pdf",
				"Biology syllabus PDF");
		createManagedFile(layout, "subjects/Biology/curriculum/Biology 2025/workbooks/retained.xlsx",
				"Retained workbook");
		createManagedFile(layout, "subjects/Biology/legacy/Biology 2019/legacy.xlsx", "Legacy workbook");
		createManagedFile(layout, "subjects/Biology/exams/unreferenced-notes.txt", "Retained examination notes");
		SqliteDatabase database = fixture.database();
		execute(database, """
				INSERT INTO subjects (id, subject_name)
				VALUES
				    (1, 'Biology'),
				    (2, 'Chemistry')
				""", """
				INSERT INTO syllabus_versions (
				    id, subject_id, syllabus_name,
				    is_current, source_pdf_path
				)
				VALUES
				    (11, 1, 'Biology 2019', 0, NULL),
				    (
				        12, 1, 'Biology 2025', 1,
				        'subjects/Biology/curriculum/Biology 2025/sources/syllabus.pdf'
				    ),
				    (21, 2, 'Chemistry 2025', 1, NULL)
				""", """
				INSERT INTO curriculum_nodes (
				    id, syllabus_version_id, parent_id,
				    curriculum_code, curriculum_name,
				    curriculum_level, display_order
				)
				VALUES
				    (101, 11, NULL, 'B19U1', 'Biology 2019 Unit',
				     'UNIT', 0),
				    (103, 11, 101, 'B19U1T1', 'Biology 2019 Topic',
				     'TOPIC', 0),
				    (104, 11, 103, 'B19U1T1S1', 'Biology 2019 Subtopic',
				     'SUBTOPIC', 0),
				    (102, 12, NULL, 'B25U1', 'Biology 2025 Unit',
				     'UNIT', 0),
				    (105, 12, 102, 'B25U1T1', 'Biology 2025 Topic',
				     'TOPIC', 0),
				    (106, 12, 105, 'B25U1T1S1', 'Biology 2025 Subtopic',
				     'SUBTOPIC', 0),
				    (201, 21, NULL, 'C25U1', 'Chemistry Unit',
				     'UNIT', 0)
				""", """
				INSERT INTO curriculum_mappings (
				    id, source_node_id, target_node_id,
				    mapping_status
				)
				VALUES (111, 101, 102, 'CONFIRMED')
				""", """
				INSERT INTO curriculum_mapping_reviews (
				    source_node_id, target_syllabus_version_id,
				    review_outcome
				)
				VALUES (101, 12, 'MATCHED')
				""", """
				INSERT INTO exam_providers (id, provider_name)
				VALUES (1, 'QCAA'), (2, 'NEAP')
				""", """
				INSERT INTO source_documents (
				    id, relative_path
				)
				VALUES
				    (51, 'subjects/Biology/exams/questions.pdf'),
				    (52, 'subjects/Biology/exams/answers.pdf'),
				    (53, 'subjects/Chemistry/exams/questions.pdf')
				""", """
				INSERT INTO exams (
				    id, subject_id, provider_id, exam_year,
				    exam_name, capture_state,
				    expected_question_booklet_count
				)
				VALUES
				    (
				        31, 1, 1, 2025, 'External Assessment',
				        'COMPLETE', 2
				    ),
				    (
				        41, 2, 2, 2025, 'Chemistry Examination',
				        'ACTIVE', 1
				    )
				""", """
				INSERT INTO exam_booklets (
				    id, exam_id, source_document_id,
				    booklet_name
				)
				VALUES
				    (61, 31, 51, 'Paper 1'),
				    (62, 31, 51, 'Paper 2'),
				    (63, 41, 53, 'Chemistry Paper')
				""", """
				INSERT INTO answer_files (
				    id, exam_id, source_document_id,
				    answer_file_name
				)
				VALUES (71, 31, 52, 'Marking Guide')
				""", """
				UPDATE exam_booklets
				SET answer_file_id = 71
				WHERE id IN (61, 62)
				""", """
				INSERT INTO source_questions (
				    id, booklet_id, source_question_code
				)
				VALUES (91, 61, '1')
				""", """
				INSERT INTO shared_question_contexts (
				    id, booklet_id, context_label
				)
				VALUES (121, 61, 'Shared stimulus')
				""", """
				INSERT INTO questions (
				    id, booklet_id, classification_node_id,
				    question_code, question_text, marks,
				    shared_context_capture_required,
				    source_question_id, shared_context_id
				)
				VALUES (
				    81, 61, 106, '1',
				    'Biology question', 2, 0, 91, 121
				)
				""", """
				INSERT INTO question_regions (
				    question_id, region_order, booklet_id,
				    page_number, x, y, width, height
				)
				VALUES (
				    81, 0, 61, 2, 0.1, 0.2, 0.5, 0.3
				)
				""", """
				INSERT INTO question_content_parts (
				    question_id, content_order,
				    content_type, region_order
				)
				VALUES (81, 0, 'PDF_REGION', 0)
				""", """
				INSERT INTO question_output_exclusions (
				    question_id, current_curriculum_node_id
				)
				VALUES (81, 106)
				""", """
				INSERT INTO shared_question_context_regions (
				    shared_context_id, region_order,
				    page_number, x, y, width, height
				)
				VALUES (
				    121, 0, 1, 0.1, 0.1, 0.4, 0.4
				)
				""", """
				INSERT INTO shared_question_context_content_parts (
				    shared_context_id, content_order,
				    content_type, region_order
				)
				VALUES (121, 0, 'PDF_REGION', 0)
				""", """
				INSERT INTO answers (
				    id, question_id, answer_text
				)
				VALUES (141, 81, 'Biology answer')
				""", """
				INSERT INTO answer_regions (
				    answer_id, region_order, answer_file_id,
				    page_number, x, y, width, height
				)
				VALUES (
				    141, 0, 71, 3, 0.1, 0.2, 0.4, 0.3
				)
				""");
		try (Connection connection = database.openConnection()) {
			try (PreparedStatement statement = connection.prepareStatement("""
					INSERT INTO question_images (
					    id, question_id, image_png
					)
					VALUES (131, 81, ?)
					""")) {
				statement.setBytes(1, PNG);
				statement.executeUpdate();
			}
			try (PreparedStatement statement = connection.prepareStatement("""
					INSERT INTO shared_question_context_images (
					    shared_context_id, image_order,
					    image_png
					)
					VALUES (121, 0, ?)
					""")) {
				statement.setBytes(1, PNG);
				statement.executeUpdate();
			}
		}
		execute(database, """
				INSERT INTO question_content_parts (
				    question_id, content_order,
				    content_type, image_id
				)
				VALUES (81, 1, 'IMAGE', 131)
				""", """
				INSERT INTO shared_question_context_content_parts (
				    shared_context_id, content_order,
				    content_type, image_order
				)
				VALUES (121, 1, 'IMAGE', 0)
				""", """
				INSERT INTO booklet_applicability_reviews (
				    booklet_id
				)
				VALUES (61), (62)
				""");
	}

	/**
	 * Calculates the lower-case SHA-256 digest of supplied binary content.
	 *
	 * @param bytes binary content to hash
	 * @return 64-character lower-case hexadecimal SHA-256
	 */
	private String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private String sourcePath(SqliteDatabase database, long id) throws SQLException {
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT relative_path
						FROM source_documents
						WHERE id = ?
						""")) {
			statement.setLong(1, id);
			try (ResultSet result = statement.executeQuery()) {
				assertTrue(result.next());
				return result.getString(1);
			}
		}
	}

	/**
	 * Holds an isolated export test database and its managed layout.
	 *
	 * @param database        initialised SQLite database
	 * @param layout          Subject-first data layout
	 * @param exportDirectory directory for test archives
	 */
	private record Fixture(SqliteDatabase database, ManagedDataLayout layout, Path exportDirectory) {
	}
}
