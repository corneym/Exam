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
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.node.ObjectNode;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.ApplicationVersion;
import au.edu.eq.questionbank.ManagedAssetPublicationLock;
import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.TestPdf;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.SharedQuestionContextRegion;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.repository.assessment.SqliteExamWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteQuestionCaptureService;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumRepository;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.document.SourceDocumentHashService;
import au.edu.eq.questionbank.service.workpackage.SubjectImportAssetStager.AssetKind;
import au.edu.eq.questionbank.service.workpackage.SubjectImportAssetStager.StagedAsset;
import au.edu.eq.questionbank.service.workpackage.SubjectImportAssetStager.StagedPackage;

class WorkAssignmentInstallationCoordinatorTest {

	@TempDir
	Path directory;

	private static void change(List<ExchangeRecord> records, String type, Consumer<ObjectNode> edit) {
		ExchangeRecord record = records.stream().filter(candidate -> candidate.recordType().equals(type)).findFirst()
				.orElseThrow();
		ObjectNode values = (ObjectNode) record.values();
		edit.accept(values);
		records.set(records.indexOf(record), new ExchangeRecord(record.recordVersion(), record.recordType(),
				record.recordId(), record.revisionId(), record.baselineRevisionId(), values));
	}

	@Test
	void assignmentResolvesInterruptedSubjectPublicationBeforeReusingItsDestination() throws Exception {
		Fixture fixture = fixture();
		Path unrelated = preserveUnrelatedSubject(fixture);
		StagedPackage interrupted = publishSubjectWithoutCommit(fixture);
		var installer = new WorkAssignmentInstallationCoordinator(fixture.contributor());
		var installed = installer.install(fixture.archive());
		assertFalse(installed.cleanupPending());
		assertFalse(Files.exists(interrupted.directory()));
		assertAllCommittedFilesVerified(fixture);
		recoverAll(fixture);
		assertAllCommittedFilesVerified(fixture);
		assertTrue(installer.install(fixture.archive()).replay());
		assertUnrelatedSubjectUnchanged(fixture, unrelated);
	}

	@Test
	void bothImportersAndDirectRecoveryRequireTheSamePublicationLock() throws Exception {
		Fixture fixture = fixture();
		StagedPackage interrupted = publishSubjectWithoutCommit(fixture);
		try (var _ = ManagedAssetPublicationLock.acquire(fixture.contributor().dataRoot())) {
			assertThrows(IOException.class,
					() -> new WorkAssignmentInstallationCoordinator(fixture.contributor()).install(fixture.archive()));
			assertThrows(IOException.class, () -> recoverAll(fixture));
			assertThrows(IOException.class,
					() -> new SubjectImportPublicationJournal(fixture.contributor()).recover(interrupted.packageId()));
		}
		assertTrue(Files.exists(interrupted.directory()));
		assertEquals(0, count(fixture.contributorDatabase(), "SELECT COUNT(*) FROM contributor_assignment_sessions"));
		recoverAll(fixture);
		assertFalse(Files.exists(interrupted.directory()));
	}

	@Test
	void bothUncommittedJournalsRecoverWhenAssignmentHadReusedSubjectFiles() throws Exception {
		Fixture fixture = fixture();
		StagedPackage subject = publishSubjectWithoutCommit(fixture);

		// Model two pending journals left by the former separate-lock protocol.
		var assignment = new WorkAssignmentArchiveStager(fixture.contributor()).stage(fixture.archive());
		var extraction = new WorkAssignmentAssetExtractor(fixture.contributor()).extract(assignment);
		try (var _ = ManagedAssetPublicationLock.acquire(fixture.contributor().dataRoot())) {
			var journal = new WorkAssignmentPublicationJournal(fixture.contributor());
			journal.prepare(extraction);
			journal.publishFiles(assignment.inspection().manifest().packageId());
		}
		recoverAll(fixture);
		assertFalse(Files.exists(subject.directory()));
		assertFalse(Files.exists(assignment.directory()));
		for (var asset : subject.assets()) {
			assertFalse(Files.exists(fixture.contributor().dataRoot().resolve(asset.managedPath())));
		}
		assertEquals(0, count(fixture.contributorDatabase(), "SELECT COUNT(*) FROM source_documents"));
		new WorkAssignmentInstallationCoordinator(fixture.contributor()).install(fixture.archive());
		assertAllCommittedFilesVerified(fixture);
	}

	@Test
	void committedAssignmentPublicationSurvivesSubjectRecoveryAndReplay() throws Exception {
		Fixture fixture = fixture();
		var staged = new WorkAssignmentArchiveStager(fixture.contributor()).stage(fixture.archive());
		var extraction = new WorkAssignmentAssetExtractor(fixture.contributor()).extract(staged);
		var journal = new WorkAssignmentPublicationJournal(fixture.contributor());
		try (var _ = ManagedAssetPublicationLock.acquire(fixture.contributor().dataRoot())) {
			journal.prepare(extraction);
			journal.publishFiles(staged.inspection().manifest().packageId());
		}

		// Preserve publication ownership evidence while a real installation commits
		// the graph and immutable session. Restoring it simulates interrupted cleanup.
		Path saved = directory.resolve("committed-assignment-evidence");
		Files.move(staged.directory(), saved);
		var installer = new WorkAssignmentInstallationCoordinator(fixture.contributor());
		installer.install(fixture.archive());
		Files.move(saved, staged.directory());
		recoverAll(fixture);
		assertFalse(Files.exists(staged.directory()));
		assertAllCommittedFilesVerified(fixture);
		assertTrue(installer.install(fixture.archive()).replay());
		recoverAll(fixture);
		assertAllCommittedFilesVerified(fixture);
	}

	@Test
	void contributorMcqContinuationSurvivesRestartAndIsConsumed() throws Exception {
		Fixture fixture = fixture();
		new WorkAssignmentInstallationCoordinator(fixture.contributor()).install(fixture.archive());
		SqliteDatabase database = fixture.contributorDatabase();
		var sessions = new SqliteContributorAssignmentSessionRepository(database);
		assertTrue(sessions.active().isPresent());

		// Use the actual installed syllabus and Exam, rather than assuming
		// contributor-local database identities match coordinator identities.
		var curriculum = new SqliteCurriculumRepository(database);
		Subject subject = curriculum.findAllSubjects().getFirst();
		var currentSyllabus = curriculum.findVersionsForSubject(subject).stream().filter(SyllabusVersion::isCurrent)
				.findFirst().orElseThrow();
		var classification = curriculum.findReviewNodes(currentSyllabus).getFirst();
		var exams = new SqliteExamWriter(database);
		Exam exam = exams.findExamsForSubject(subject).getFirst();
		ExamBooklet booklet = exams.findExamBooklets(exam).stream()
				.filter(candidate -> candidate.getName().equals("Paper 1")).findFirst().orElseThrow();
		var firstService = new SqliteQuestionCaptureService(database);
		Question questionFive = firstService
				.save(new SqliteQuestionCaptureService.Request(SqliteQuestionCaptureService.Operation.NEW, booklet,
						null, "Q5", 1, List.of(new QuestionRegion(booklet, 1, 0.10, 0.30, 0.70, 0.12)), classification,
						QuestionResponseType.MULTIPLE_CHOICE, null,
						new SqliteQuestionCaptureService.PendingSharedContext("MCQ stimulus",
								List.of(new SharedQuestionContextRegion(1, 0.10, 0.10, 0.80, 0.15))),
						true));
		assertTrue(questionFive.hasSharedContext());
		long contextId = questionFive.getSharedContext().getId();

		// Recreate the capture service to prove continuation is persisted.
		var reopenedService = new SqliteQuestionCaptureService(database);
		assertEquals(contextId, reopenedService.findPendingMcqSharedContext(booklet).orElseThrow().getId());
		Question questionSix = reopenedService
				.save(new SqliteQuestionCaptureService.Request(SqliteQuestionCaptureService.Operation.NEW, booklet,
						null, "Q6", 1, List.of(new QuestionRegion(booklet, 1, 0.10, 0.48, 0.70, 0.12)), classification,
						QuestionResponseType.MULTIPLE_CHOICE, null, null, true));
		assertTrue(questionSix.hasSharedContext());
		assertEquals(contextId, questionSix.getSharedContext().getId());
		assertEquals(contextId, reopenedService.findPendingMcqSharedContext(booklet).orElseThrow().getId());

		// The next MCQ consumes continuation when it is not renewed.
		var finalService = new SqliteQuestionCaptureService(database);
		Question questionSeven = finalService
				.save(new SqliteQuestionCaptureService.Request(SqliteQuestionCaptureService.Operation.NEW, booklet,
						null, "Q7", 1, List.of(new QuestionRegion(booklet, 1, 0.10, 0.66, 0.70, 0.12)), classification,
						QuestionResponseType.MULTIPLE_CHOICE, null, null, false));
		assertTrue(questionSeven.hasSharedContext());
		assertEquals(contextId, questionSeven.getSharedContext().getId());
		assertTrue(finalService.findPendingMcqSharedContext(booklet).isEmpty());

		// Contributor capture must not change authoritative Booklet structure.
		assertEquals(2, count(database, "SELECT COUNT(*) FROM exam_booklets"));
		assertEquals(1, count(database, "SELECT COUNT(*) FROM exam_booklets WHERE booklet_name = 'Paper 1'"));
		assertEquals(4, count(database, "SELECT COUNT(*) FROM questions"));
		database.verifyIntegrity();
	}

	@Test
	void duplicateAssignmentIdentityWithDifferentPackageIsRejected() throws Exception {
		Fixture fixture = fixture();
		var installer = new WorkAssignmentInstallationCoordinator(fixture.contributor());
		installer.install(fixture.archive());
		Path differentArchive = directory.resolve("different-issuance.examwork");

		// Same assignment UUID, but independently issued
		// package UUID and baseline snapshot.
		export(fixture, fixture.assignmentId(), differentArchive);
		assertThrows(IOException.class, () -> installer.install(differentArchive));
		assertEquals(1, count(fixture.contributorDatabase(), "SELECT COUNT(*) FROM contributor_assignment_sessions"));
		fixture.contributorDatabase().verifyIntegrity();
	}

	@Test
	void graphConflictRollsBackAndRemovesNewlyPublishedPdfs() throws Exception {
		Fixture fixture = fixture();

		// An independently created local Subject has the same
		// name but a different canonical portable UUID.
		// Installation must not merge it without authorisation.
		execute(fixture.contributorDatabase(), """
				INSERT INTO subjects (id, subject_name)
				VALUES (77, 'Biology')
				""");
		var installer = new WorkAssignmentInstallationCoordinator(fixture.contributor());
		assertThrows(SQLException.class, () -> installer.install(fixture.archive()));
		assertEquals(1, count(fixture.contributorDatabase(), "SELECT COUNT(*) FROM subjects"));
		assertEquals(0, count(fixture.contributorDatabase(), "SELECT COUNT(*) FROM exams"));
		assertEquals(0, count(fixture.contributorDatabase(), "SELECT COUNT(*) FROM contributor_assignment_sessions"));
		Path question = fixture.contributor().dataRoot()
				.resolve("subjects/Biology/exams/QCAA/2025/External Assessment/questions.pdf");
		Path answer = fixture.contributor().dataRoot()
				.resolve("subjects/Biology/exams/QCAA/2025/External Assessment/answers.pdf");

		// Both files were newly published before the database
		// conflict. Recovery must remove both owned hard links.
		assertFalse(Files.exists(question));
		assertFalse(Files.exists(answer));
		assertEquals(1, count(fixture.contributorDatabase(), """
				SELECT COUNT(*)
				FROM subjects
				WHERE id = 77
				  AND subject_name = 'Biology'
				"""));
		fixture.contributorDatabase().verifyIntegrity();
	}

	@Test
	void independentAssignmentsCanShareUnchangedSourceFiles() throws Exception {
		Fixture fixture = fixture();
		var installer = new WorkAssignmentInstallationCoordinator(fixture.contributor());
		installer.install(fixture.archive());
		UUID secondAssignmentId = UUID.randomUUID();
		Path secondArchive = directory.resolve("second-assignment.examwork");
		export(fixture, secondAssignmentId, secondArchive);
		var second = installer.install(secondArchive);
		assertFalse(second.replay());
		assertEquals(2, count(fixture.contributorDatabase(), "SELECT COUNT(*) FROM contributor_assignment_sessions"));

		// An Exam with two Booklets and one shared AnswerFile
		// must not be duplicated when another assignment uses it.
		assertEquals(1, count(fixture.contributorDatabase(), "SELECT COUNT(*) FROM exams"));
		assertEquals(2, count(fixture.contributorDatabase(), "SELECT COUNT(*) FROM exam_booklets"));
		assertEquals(1, count(fixture.contributorDatabase(), "SELECT COUNT(*) FROM answer_files"));
		var sessions = new SqliteContributorAssignmentSessionRepository(fixture.contributorDatabase());
		assertTrue(sessions.find(fixture.assignmentId()).isPresent());
		assertTrue(sessions.find(secondAssignmentId).isPresent());
		assertTrue(sessions.active().isPresent());
		fixture.contributorDatabase().verifyIntegrity();
	}

	@Test
	void installsCompleteExamAndReplaysWithoutDuplicatingWork() throws Exception {
		Fixture fixture = fixture();
		var installer = new WorkAssignmentInstallationCoordinator(fixture.contributor());
		var first = installer.install(fixture.archive());
		assertFalse(first.replay());
		assertFalse(first.cleanupPending());
		assertEquals(fixture.assignmentId(), first.assignmentId());
		assertEquals(1, count(fixture.contributorDatabase(), "SELECT COUNT(*) FROM exams"));
		assertEquals(2, count(fixture.contributorDatabase(), "SELECT COUNT(*) FROM exam_booklets"));
		assertEquals(1, count(fixture.contributorDatabase(), "SELECT COUNT(*) FROM answer_files"));
		assertEquals(1, count(fixture.contributorDatabase(), "SELECT COUNT(*) FROM questions"));
		assertEquals(1, count(fixture.contributorDatabase(), "SELECT COUNT(*) FROM answers"));
		assertEquals(1, count(fixture.contributorDatabase(), "SELECT COUNT(*) FROM contributor_assignment_sessions"));
		var sessions = new SqliteContributorAssignmentSessionRepository(fixture.contributorDatabase());
		assertTrue(sessions.find(fixture.assignmentId()).isPresent());
		assertTrue(sessions.active().isPresent());
		Path question = fixture.contributor().dataRoot()
				.resolve("subjects/Biology/exams/QCAA/2025/External Assessment/questions.pdf");
		Path answer = fixture.contributor().dataRoot()
				.resolve("subjects/Biology/exams/QCAA/2025/External Assessment/answers.pdf");
		assertArrayEquals(Files.readAllBytes(fixture.sourceQuestionPdf()), Files.readAllBytes(question));
		assertArrayEquals(Files.readAllBytes(fixture.sourceAnswerPdf()), Files.readAllBytes(answer));

		// Completed installation must not leave an outstanding
		// publication journal or private package stage.
		assertFalse(Files.exists(fixture.contributor().dataRoot().resolve(".assignment-install-staging")
				.resolve(first.packageId().toString())));
		var repeated = installer.install(fixture.archive());
		assertTrue(repeated.replay());
		assertFalse(repeated.cleanupPending());
		assertEquals(1, count(fixture.contributorDatabase(), "SELECT COUNT(*) FROM exams"));
		assertEquals(2, count(fixture.contributorDatabase(), "SELECT COUNT(*) FROM exam_booklets"));
		assertEquals(1, count(fixture.contributorDatabase(), "SELECT COUNT(*) FROM contributor_assignment_sessions"));
		fixture.contributorDatabase().verifyIntegrity();
	}

	@Test
	void issuanceFailureLeavesNoRegisteredAssignment() throws Exception {
		Fixture fixture = fixture();
		Path destination = directory.resolve("invalid-assignment.examwork");

		// Required source PDFs belong to the coordinator.
		// A missing PDF must block issuance before registration.
		Files.delete(fixture.sourceAnswerPdf());
		WorkAssignmentExporter.Request request = new WorkAssignmentExporter.Request(fixture.exam(), UUID.randomUUID(),
				UUID.randomUUID(), "Integration Contributor", false, destination);
		WorkAssignmentIssuanceService issuer = new WorkAssignmentIssuanceService(fixture.sourceDatabase(),
				new ManagedDataLayout(fixture.source().dataRoot()));
		IOException failure = assertThrows(IOException.class, () -> issuer.issue(request));
		assertTrue(failure.getMessage().contains("missing or unreadable"));
		assertFalse(Files.exists(destination));
		assertEquals(0, count(fixture.sourceDatabase(), "SELECT COUNT(*) " + "FROM exam_work_assignments"));
	}

	@Test
	void issuedAssignmentInstallsAndRestoresWithoutChangingBaseline() throws Exception {
		Fixture fixture = fixture();
		UUID assignmentId = UUID.randomUUID();
		Path issuedPath = directory.resolve("coordinator-issued.examwork");
		WorkAssignmentExporter.Request request = new WorkAssignmentExporter.Request(fixture.exam(), assignmentId,
				UUID.randomUUID(), "Integration Contributor", false, issuedPath);
		WorkAssignmentIssuanceService issuer = new WorkAssignmentIssuanceService(fixture.sourceDatabase(),
				new ManagedDataLayout(fixture.source().dataRoot()));
		WorkAssignmentLedger.Assignment issued = issuer.issue(request);
		assertEquals(assignmentId, issued.assignmentId());
		assertEquals(WorkAssignmentLedger.State.ACTIVE, issued.state());
		assertTrue(Files.isRegularFile(issuedPath));
		WorkAssignmentLedger ledger = new WorkAssignmentLedger(fixture.sourceDatabase());
		WorkAssignmentLedger.Baseline original = ledger.readBaseline(assignmentId);
		WorkAssignmentInstallationCoordinator installer = new WorkAssignmentInstallationCoordinator(
				fixture.contributor());
		var installed = installer.install(issuedPath);
		assertFalse(installed.replay());
		assertEquals(assignmentId, installed.assignmentId());

		// The same Exam is installed with both Question Booklets
		// and their shared AnswerFile.
		assertEquals(1, count(fixture.contributorDatabase(), "SELECT COUNT(*) FROM exams"));
		assertEquals(2, count(fixture.contributorDatabase(), "SELECT COUNT(*) FROM exam_booklets"));
		assertEquals(1, count(fixture.contributorDatabase(), "SELECT COUNT(*) FROM answer_files"));
		var sessions = new SqliteContributorAssignmentSessionRepository(fixture.contributorDatabase());
		assertEquals(assignmentId, sessions.active().orElseThrow().assignmentId());
		var contributorBaseline = sessions.readBaseline(assignmentId);
		assertArrayEquals(original.records(), contributorBaseline.records());
		assertArrayEquals(original.assets(), contributorBaseline.assets());

		// An ACTIVE assignment makes the coordinator's Exam
		// read-only. An attempted edit must be rejected.
		SQLException blocked = assertThrows(SQLException.class, () -> execute(fixture.sourceDatabase(), """
				UPDATE questions
				SET question_text = 'Edited after assignment issuance'
				WHERE id = 81
				"""));
		assertTrue(blocked.getMessage().contains("Assigned Exam capture is read-only on the master"));

		// The attempted mutation must not alter the master Question,
		// the issued baseline, or the ACTIVE assignment state.
		assertEquals(1, count(fixture.sourceDatabase(), """
				SELECT COUNT(*)
				FROM questions
				WHERE id = 81
				  AND question_text = 'Biology question'
				"""));
		assertArrayEquals(original.records(), ledger.readBaseline(assignmentId).records());
		assertArrayEquals(original.assets(), ledger.readBaseline(assignmentId).assets());
		assertEquals(WorkAssignmentLedger.State.ACTIVE, ledger.find(assignmentId).orElseThrow().state());

		// Clear selection without deleting the installed assignment.
		sessions.clearActive();
		assertTrue(sessions.active().isEmpty());
		assertTrue(sessions.find(assignmentId).isPresent());

		// Reopen the same contributor database to simulate restart.
		SqliteDatabase reopened = new SqliteDatabase(fixture.contributor().databasePath());
		var reopenedSessions = new SqliteContributorAssignmentSessionRepository(reopened);
		assertTrue(reopenedSessions.active().isEmpty());
		reopenedSessions.select(assignmentId);
		assertEquals(assignmentId, reopenedSessions.active().orElseThrow().assignmentId());

		// Re-importing the original package must be idempotent.
		var replay = installer.install(issuedPath);
		assertTrue(replay.replay());
		assertEquals(1, count(reopened, """
				SELECT COUNT(*)
				FROM contributor_assignment_sessions
				"""));
		assertArrayEquals(original.records(), reopenedSessions.readBaseline(assignmentId).records());
		assertArrayEquals(original.assets(), reopenedSessions.readBaseline(assignmentId).assets());
		fixture.sourceDatabase().verifyIntegrity();
		reopened.verifyIntegrity();
	}

	@Test
	void recoveryPreservesSubjectPublicationAlreadyAdoptedByCommittedAssignment() throws Exception {
		Fixture fixture = fixture();
		Path unrelated = preserveUnrelatedSubject(fixture);
		StagedPackage interrupted = publishSubjectWithoutCommit(fixture);

		// Recreate the pre-correction sequence: the old installer only inspected its
		// own journal root. Temporarily hide Subject evidence from the new barrier.
		Path saved = directory.resolve("pre-upgrade-subject-evidence");
		Files.move(interrupted.directory(), saved);
		var installer = new WorkAssignmentInstallationCoordinator(fixture.contributor());
		installer.install(fixture.archive());
		Files.move(saved, interrupted.directory());
		execute(fixture.contributorDatabase(), "UPDATE questions SET question_text = 'Contributor work'");
		var sessions = new SqliteContributorAssignmentSessionRepository(fixture.contributorDatabase());
		var baseline = sessions.readBaseline(fixture.assignmentId());
		assertEquals(0, count(fixture.contributorDatabase(), "SELECT COUNT(*) FROM subject_import_packages"));
		recoverAll(fixture);
		assertFalse(Files.exists(interrupted.directory()));
		assertAllCommittedFilesVerified(fixture);
		recoverAll(fixture);
		assertTrue(installer.install(fixture.archive()).replay());
		assertEquals(1, count(fixture.contributorDatabase(),
				"SELECT COUNT(*) FROM questions WHERE question_text = 'Contributor work'"));
		assertArrayEquals(baseline.records(), sessions.readBaseline(fixture.assignmentId()).records());
		assertArrayEquals(baseline.assets(), sessions.readBaseline(fixture.assignmentId()).assets());
		assertUnrelatedSubjectUnchanged(fixture, unrelated);
	}

	@Test
	void rejectsBookletWithoutRequiredSharedAnswerLink() throws Exception {
		rejects(records -> change(records, "EXAM_BOOKLET", values -> values.remove("answer_file_id")));
	}

	@ParameterizedTest
	@ValueSource(strings = { "assignment_id", "baseline_snapshot_id", "master_installation_id", "subject_id", "exam_id",
			"current_syllabus_id", "issued_at" })
	void rejectsInconsistentAssignmentContext(String field) throws Exception {
		Fixture fixture = fixture();
		Path archive = PortableArchiveEditor.rewriteRecords(fixture.archive(), directory.resolve("context.examwork"),
				"records/assignment-context.jsonl", records -> change(records, "WORK_ASSIGNMENT_CONTEXT",
						values -> values.put(field, UUID.randomUUID().toString())));
		assertRejectedBeforePublication(fixture, archive);
	}

	@ParameterizedTest
	@ValueSource(strings = { "expected_question_booklet_count", "expected_answer_file_count" })
	void rejectsIncorrectDeclaredCounts(String field) throws Exception {
		rejects(records -> change(records, "EXAM", values -> values.put(field, 99)));
	}

	@Test
	void rejectsInstallationIntoOriginatingCoordinator() throws Exception {
		Fixture fixture = fixture();
		var installer = new WorkAssignmentInstallationCoordinator(fixture.source());
		IOException failure = assertThrows(IOException.class, () -> installer.install(fixture.archive()));
		assertTrue(failure.getMessage().contains("originating coordinator installation"));

		// Rejection must occur before changing the master database
		// or replacing its authoritative source PDFs.
		assertEquals(1, count(fixture.sourceDatabase(), "SELECT COUNT(*) FROM exams"));
		assertEquals(0, count(fixture.sourceDatabase(), "SELECT COUNT(*) " + "FROM contributor_assignment_sessions"));
		assertTrue(Files.isRegularFile(fixture.sourceQuestionPdf()));
		assertTrue(Files.isRegularFile(fixture.sourceAnswerPdf()));
		fixture.sourceDatabase().verifyIntegrity();
	}

	@Test
	void rejectsManifestIntendedMasterDisagreeingWithOriginAndContext() throws Exception {
		Fixture fixture = fixture();
		Path archive = PortableArchiveEditor.rewriteContents(fixture.archive(), directory.resolve("master.examwork"),
				_ -> {
				},
				manifest -> new ExamWorkManifest(manifest.formatVersion(), manifest.packageId(), manifest.packageType(),
						manifest.createdAt(), manifest.originInstallationId(), UUID.randomUUID(), manifest.subjectId(),
						manifest.subjectName(), manifest.assignmentId(), manifest.baselineSnapshotId(),
						manifest.producerApplicationVersion(), manifest.minimumReaderApplicationVersion(),
						manifest.producerSchemaVersion(), manifest.minimumReaderSchemaVersion(), manifest.scope(),
						manifest.entries()));
		assertRejectedBeforePublication(fixture, archive);
	}

	@Test
	void rejectsMissingAnswerFileCount() throws Exception {
		rejects(records -> change(records, "EXAM", values -> values.remove("expected_answer_file_count")));
	}

	@Test
	void rejectsNonPdfAssetWithMatchingManifestIndexAndSourceHashes() throws Exception {
		Fixture fixture = fixture();
		Path archive = PortableArchiveEditor.replaceQuestionPdf(fixture.archive(),
				directory.resolve("invalid-pdf.examwork"),
				"%PDF-1.4\nNot a PDF document".getBytes(StandardCharsets.UTF_8));
		assertRejectedBeforePublication(fixture, archive);
	}

	@Test
	void rejectsOmittedBookletEvenWhenItsPdfAndSharedAnswerRemain() throws Exception {
		rejects(records -> records.removeIf(record -> record.recordType().equals("EXAM_BOOKLET")
				&& record.values().path("booklet_name").asText().equals("Paper 2")));
	}

	@Test
	void rejectsSubjectNameDisagreeingWithManifestAndAssetPaths() throws Exception {
		rejects(records -> change(records, "SUBJECT", values -> values.put("subject_name", "Physics")));
	}

	private void assertAllCommittedFilesVerified(Fixture fixture) throws Exception {
		var hashes = new SourceDocumentHashService();
		try (var connection = fixture.contributorDatabase().openConnection();
				var statement = connection.createStatement();
				var rows = statement.executeQuery("SELECT relative_path, content_sha256 FROM source_documents")) {
			int verified = 0;
			while (rows.next()) {
				Path file = new ManagedDataLayout(fixture.contributor().dataRoot()).resolve(rows.getString(1));
				assertTrue(Files.isRegularFile(file), rows.getString(1));
				assertEquals(rows.getString(2), hashes.sha256(file));
				verified++;
			}
			assertEquals(2, verified);
		}
		assertEquals(1, count(fixture.contributorDatabase(), "SELECT COUNT(*) FROM contributor_assignment_sessions"));
		fixture.contributorDatabase().verifyIntegrity();
	}

	private void assertRejectedBeforePublication(Fixture fixture, Path archive) throws Exception {
		Path unrelated = preserveUnrelatedSubject(fixture);
		new ExamWorkArchiveValidator().validate(archive, ApplicationVersion.current(),
				SqliteDatabase.latestSchemaVersion());
		assertThrows(PackageFormatException.class,
				() -> new WorkAssignmentInstallationCoordinator(fixture.contributor()).install(archive));
		assertEquals(1, count(fixture.contributorDatabase(), "SELECT COUNT(*) FROM subjects"));
		for (String table : List.of("exams", "exam_booklets", "source_documents", "contributor_assignment_sessions")) {
			assertEquals(0, count(fixture.contributorDatabase(), "SELECT COUNT(*) FROM " + table), table);
		}
		assertFalse(Files.exists(fixture.contributor().dataRoot().resolve("subjects/Biology")));
		assertEquals("Unrelated authoritative material", Files.readString(unrelated));
	}

	private void assertUnrelatedSubjectUnchanged(Fixture fixture, Path unrelated) throws Exception {
		assertEquals("Unrelated authoritative material", Files.readString(unrelated));
		assertEquals(1, count(fixture.contributorDatabase(),
				"SELECT COUNT(*) FROM subjects WHERE id = 77 AND subject_name = 'Chemistry'"));
	}

	private int count(SqliteDatabase database, String sql) throws SQLException {
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery(sql)) {
			assertTrue(result.next());
			return result.getInt(1);
		}
	}

	private void execute(SqliteDatabase database, String sql) throws SQLException {
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}

	private void export(Fixture fixture, UUID assignmentId, Path destination) throws Exception {
		new WorkAssignmentExporter(fixture.sourceDatabase(), new ManagedDataLayout(fixture.source().dataRoot()))
				.export(new WorkAssignmentExporter.Request(fixture.exam(), assignmentId, UUID.randomUUID(),
						"Integration Contributor", false, destination));
	}

	private Fixture fixture() throws Exception {
		ApplicationConfig source = ApplicationConfig.fromDataRoot(directory.resolve("source"));
		ApplicationConfig contributor = ApplicationConfig.fromDataRoot(directory.resolve("contributor"));
		Files.createDirectories(source.dataRoot());
		Files.createDirectories(contributor.dataRoot());
		SqliteDatabase sourceDatabase = new SqliteDatabase(source.databasePath());
		SqliteDatabase contributorDatabase = new SqliteDatabase(contributor.databasePath());
		sourceDatabase.initialiseSchema();
		contributorDatabase.initialiseSchema();
		Path questionPdf = source.dataRoot()
				.resolve("subjects/Biology/exams/QCAA/2025/External Assessment/questions.pdf");
		Path answerPdf = source.dataRoot().resolve("subjects/Biology/exams/QCAA/2025/External Assessment/answers.pdf");
		Files.createDirectories(questionPdf.getParent());
		Files.write(questionPdf, TestPdf.bytes("Question PDF test content"));
		Files.write(answerPdf, TestPdf.bytes("Answer PDF test content"));
		execute(sourceDatabase, """
				INSERT INTO subjects (id, subject_name)
				VALUES (1, 'Biology')
				""");
		execute(sourceDatabase, """
				INSERT INTO syllabus_versions (
				    id, subject_id, syllabus_name, is_current
				)
				VALUES (11, 1, 'Biology 2025', 1)
				""");
		execute(sourceDatabase, """
				INSERT INTO curriculum_nodes (
				    id, syllabus_version_id, parent_id,
				    curriculum_code, curriculum_name,
				    curriculum_level, display_order
				)
				VALUES
				    (101, 11, NULL, 'B25U1', 'Biology Unit', 'UNIT', 0),
				    (102, 11, 101, 'B25U1T1', 'Biology Topic', 'TOPIC', 0),
				    (103, 11, 102, 'B25U1T1D1', 'Biology Descriptor', 'DESCRIPTOR', 0)
				""");
		execute(sourceDatabase, """
				INSERT INTO exam_providers (id, provider_name)
				VALUES (21, 'QCAA')
				""");
		execute(sourceDatabase, """
				INSERT INTO source_documents (id, relative_path)
				VALUES
				    (51, 'subjects/Biology/exams/QCAA/2025/External Assessment/questions.pdf'),
				    (52, 'subjects/Biology/exams/QCAA/2025/External Assessment/answers.pdf')
				""");
		try (var connection = sourceDatabase.openConnection();
				var statement = connection
						.prepareStatement("UPDATE source_documents SET content_sha256 = ? WHERE id = ?")) {
			statement.setString(1, new SourceDocumentHashService().sha256(questionPdf));
			statement.setLong(2, 51);
			statement.executeUpdate();
			statement.setString(1, new SourceDocumentHashService().sha256(answerPdf));
			statement.setLong(2, 52);
			statement.executeUpdate();
		}
		execute(sourceDatabase, """
				INSERT INTO exams (
				    id, subject_id, provider_id,
				    exam_year, exam_name,
				    expected_question_booklet_count,
				    expected_answer_file_count
				)
				VALUES (
				    31, 1, 21, 2025, 'External Assessment',
				    2, 1
				)
				""");
		execute(sourceDatabase, """
				INSERT INTO answer_files (
				    id, exam_id, source_document_id, answer_file_name
				)
				VALUES (71, 31, 52, 'Marking Guide')
				""");
		execute(sourceDatabase, """
				INSERT INTO exam_booklets (
				    id, exam_id, source_document_id,
				    answer_file_id, booklet_name
				)
				VALUES
				    (61, 31, 51, 71, 'Paper 1'),
				    (62, 31, 51, 71, 'Paper 2')
				""");
		execute(sourceDatabase, """
				INSERT INTO questions (
				    id, booklet_id, classification_node_id,
				    question_code, question_text, marks,
				    shared_context_capture_required
				)
				VALUES (
				    81, 61, 103,
				    '1', 'Biology question', 2, 0
				)
				""");
		execute(sourceDatabase, """
				INSERT INTO answers (
				    id, question_id, answer_text
				)
				VALUES (91, 81, 'Biology answer')
				""");
		Exam exam = new Exam(31, new Subject(1, "Biology"), new ExamProvider(21, "QCAA"), 2025, "External Assessment");
		UUID assignmentId = UUID.randomUUID();
		Path archive = directory.resolve("issued-assignment.examwork");
		Fixture fixture = new Fixture(source, contributor, sourceDatabase, contributorDatabase, exam, assignmentId,
				archive, questionPdf, answerPdf);
		export(fixture, assignmentId, archive);
		return fixture;
	}

	private Path preserveUnrelatedSubject(Fixture fixture) throws Exception {
		execute(fixture.contributorDatabase(), "INSERT INTO subjects (id, subject_name) VALUES (77, 'Chemistry')");
		Path unrelated = fixture.contributor().dataRoot().resolve("subjects/Chemistry/keep.pdf");
		Files.createDirectories(unrelated.getParent());
		Files.writeString(unrelated, "Unrelated authoritative material");
		return unrelated;
	}

	private StagedPackage publishSubjectWithoutCommit(Fixture fixture) throws Exception {
		Path incoming = directory.resolve("subject-snapshot.examwork");
		var manifest = new SubjectSnapshotExporter(fixture.sourceDatabase(),
				new ManagedDataLayout(fixture.source().dataRoot())).export(1, incoming);
		var backup = new SubjectImportBackupGate(fixture.contributor()).createVerifiedFullBackup(manifest.packageId(),
				directory.resolve("backups"));
		Path stage = fixture.contributor().dataRoot().resolve(".subject-import-staging")
				.resolve(manifest.packageId().toString());
		Files.createDirectories(stage.resolve("assets"));
		Path archive = Files.copy(incoming, stage.resolve("incoming.examwork"));
		List<StagedAsset> assets = new ArrayList<>();
		for (Path source : List.of(fixture.sourceQuestionPdf(), fixture.sourceAnswerPdf())) {
			String hash = new SourceDocumentHashService().sha256(source);
			Path copy = Files.copy(source, stage.resolve("assets/%05d.bin".formatted(assets.size())));
			String managed = new ManagedDataLayout(fixture.source().dataRoot()).relativePath(source);
			assets.add(new StagedAsset(managed, "assets/sha256/" + hash.substring(0, 2) + "/" + hash + ".pdf", copy,
					hash, Files.size(copy), AssetKind.MANAGED_FILE));
		}
		var staged = new StagedPackage(manifest.packageId(), "b".repeat(64), stage, archive, assets);
		try (var _ = ManagedAssetPublicationLock.acquire(fixture.contributor().dataRoot())) {
			var journal = new SubjectImportPublicationJournal(fixture.contributor());
			journal.prepare(staged, backup);
			journal.publishFiles(staged.packageId());
		}
		return staged;
	}

	private void recoverAll(Fixture fixture) throws Exception {
		new SubjectImportCoordinator(fixture.contributor())
				.recoverPending(fixture.contributor().dataRoot().resolve(".subject-import-staging"));
	}

	private void rejects(Consumer<List<ExchangeRecord>> edit) throws Exception {
		Fixture fixture = fixture();
		Path archive = PortableArchiveEditor.rewrite(fixture.archive(), directory.resolve("crafted.examwork"), edit);
		assertRejectedBeforePublication(fixture, archive);
	}

	private record Fixture(ApplicationConfig source, ApplicationConfig contributor, SqliteDatabase sourceDatabase,
			SqliteDatabase contributorDatabase, Exam exam, UUID assignmentId, Path archive, Path sourceQuestionPdf,
			Path sourceAnswerPdf) {
	}
}
