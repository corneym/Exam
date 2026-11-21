package au.edu.eq.questionbank.service.workpackage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.TestPdf;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
import au.edu.eq.questionbank.repository.sqlite.SqliteContributorAssignmentVisibilityRepository;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

public class ContributionSnapshotReaderTest {

	@TempDir
	Path directory;

	@Test
	void exportsOneFinalNewQuestionContributionAndRetainsBaseline() throws Exception {
		Fixture fixture = fixture();
		SqliteDatabase database = fixture.contributorDatabase();
		var sessions = new SqliteContributorAssignmentSessionRepository(database);
		var exporter = new ContributionExporter(database, new ManagedDataLayout(directory.resolve("contributor")));
		var ledger = new SqliteContributionReturnLedger(database);
		var original = snapshot(fixture, "original-export-snapshot.db");
		ExchangeRecord issuedQuestion = findRecord(original.baselineRecords(), "QUESTION");
		long bookletId = localId(database, "EXAM_BOOKLET",
				UUID.fromString(issuedQuestion.values().path("booklet_id").asText()));
		long classificationId = localId(database, "CURRICULUM_NODE",
				UUID.fromString(issuedQuestion.values().path("classification_node_id").asText()));
		byte[] issuedBaseline = sessions.readBaseline(fixture.assignmentId()).records();
		executeOne(database, """
				INSERT INTO questions (
				    booklet_id, classification_node_id,
				    question_code, question_text, marks,
				    shared_context_capture_required
				)
				VALUES (?, ?, '2', 'New worker Question', 3, 0)
				""", bookletId, classificationId);
		Path archive = directory.resolve("final-contribution.examwork");
		ExamWorkManifest manifest = exporter.export(fixture.assignmentId(), archive);
		assertEquals(ExamWorkPackageType.CONTRIBUTION, manifest.packageType());
		assertEquals(fixture.assignmentId(), manifest.assignmentId());
		assertTrue(Files.isRegularFile(archive));
		new ExamWorkArchiveValidator().validate(archive, manifest.producerApplicationVersion(),
				manifest.producerSchemaVersion());
		assertArrayEquals(issuedBaseline, archiveEntryBytes(archive, "baseline/initial-records.jsonl"));
		var codec = new ExamWorkJsonlCodec();
		List<ExchangeRecord> changes = codec.readRecords(
				new java.io.ByteArrayInputStream(archiveEntryBytes(archive, "records/contribution-changes.jsonl")));
		assertEquals(1, changes.size());
		assertEquals("QUESTION", changes.getFirst().recordType());
		assertEquals("New worker Question", changes.getFirst().values().path("question_text").asText());
		assertEquals(null, changes.getFirst().baselineRevisionId());
		ExchangeRecord context = codec.readRecords(
				new java.io.ByteArrayInputStream(archiveEntryBytes(archive, "records/contribution-context.jsonl")))
				.getFirst();
		assertEquals(1, context.values().path("return_number").asInt());
		assertTrue(context.values().path("previous_return_package_id").isNull());
		assertEquals(1, ledger.findLatest(fixture.assignmentId()).orElseThrow().returnNumber());
		assertTrue(sessions.active().isEmpty());
		assertArrayEquals(issuedBaseline, sessions.readBaseline(fixture.assignmentId()).records());
		assertThrows(IllegalStateException.class,
				() -> exporter.export(fixture.assignmentId(), directory.resolve("another-contribution.examwork")));
	}

	@Test
	void preservesBaselineAndRejectsChangesToIssuedQuestions() throws Exception {
		Fixture fixture = fixture();
		SqliteDatabase database = fixture.contributorDatabase();
		var sessions = new SqliteContributorAssignmentSessionRepository(database);
		byte[] issuedBaseline = sessions.readBaseline(fixture.assignmentId()).records();
		var original = snapshot(fixture, "baseline-unchanged.db");
		ExchangeRecord issuedQuestion = findRecord(original.baselineRecords(), "QUESTION");
		long bookletId = localId(database, "EXAM_BOOKLET",
				UUID.fromString(issuedQuestion.values().path("booklet_id").asText()));
		long classificationId = localId(database, "CURRICULUM_NODE",
				UUID.fromString(issuedQuestion.values().path("classification_node_id").asText()));
		executeOne(database, """
				INSERT INTO questions (
				    booklet_id, classification_node_id,
				    question_code, question_text, marks,
				    shared_context_capture_required
				)
				VALUES (?, ?, '2', 'First worker draft', 3, 0)
				""", bookletId, classificationId);
		var first = snapshot(fixture, "first-worker-draft.db");
		ExchangeRecord added = first.delta().candidates().stream()
				.filter(record -> "QUESTION".equals(record.recordType())).findFirst().orElseThrow();
		long newQuestionId = localId(database, "QUESTION", added.recordId());
		executeOne(database, """
				UPDATE questions
				SET question_text = 'Corrected worker draft'
				WHERE id = ?
				""", newQuestionId);
		var corrected = snapshot(fixture, "corrected-worker-draft.db");
		assertEquals(1, corrected.delta().candidates().size());
		assertEquals(added.recordId(), corrected.delta().candidates().getFirst().recordId());
		assertEquals("Corrected worker draft",
				corrected.delta().candidates().getFirst().values().path("question_text").asText());
		assertArrayEquals(issuedBaseline, sessions.readBaseline(fixture.assignmentId()).records());

		// This direct SQL write exercises the export boundary; the application
		// must separately block the edit before it reaches persistence.
		long issuedQuestionId = localId(database, "QUESTION", issuedQuestion.recordId());
		executeOne(database, """
				UPDATE questions
				SET question_text = 'Unauthorised baseline modification'
				WHERE id = ?
				""", issuedQuestionId);
		assertThrows(PackageFormatException.class, () -> snapshot(fixture, "invalid-baseline-change.db"));
		assertArrayEquals(issuedBaseline, sessions.readBaseline(fixture.assignmentId()).records());
	}

	@Test
	void readsAnInstalledAssignmentAfterSelectionIsCleared() throws Exception {
		Fixture fixture = fixture();
		SqliteContributorAssignmentSessionRepository sessions = new SqliteContributorAssignmentSessionRepository(
				fixture.contributorDatabase());
		sessions.clearActive();
		assertTrue(sessions.active().isEmpty());
		assertTrue(sessions.find(fixture.assignmentId()).isPresent());
		var result = snapshot(fixture, "inactive-snapshot.db");
		assertEquals(fixture.assignmentId(), result.session().assignmentId());
		assertFalse(result.baselineRecords().isEmpty());
		assertEquals(result.baselineRecords(), result.currentRecords());
		assertTrue(result.delta().candidates().isEmpty());
	}

	@Test
	void rejectsEmptyExportAndDoesNotPublishTwice() throws Exception {
		Fixture fixture = fixture();
		SqliteDatabase database = fixture.contributorDatabase();
		ContributionExporter exporter = new ContributionExporter(database,
				new ManagedDataLayout(directory.resolve("contributor")));
		Path destination = directory.resolve("single-contribution.examwork");
		assertThrows(IOException.class, () -> exporter.export(fixture.assignmentId(), destination));
		assertFalse(Files.exists(destination));
		var original = snapshot(fixture, "empty-check.db");
		ExchangeRecord question = findRecord(original.baselineRecords(), "QUESTION");
		long bookletId = localId(database, "EXAM_BOOKLET",
				UUID.fromString(question.values().path("booklet_id").asText()));
		long nodeId = localId(database, "CURRICULUM_NODE",
				UUID.fromString(question.values().path("classification_node_id").asText()));
		executeOne(database, """
				INSERT INTO questions (
				    booklet_id, classification_node_id,
				    question_code, question_text, marks,
				    shared_context_capture_required
				)
				VALUES (?, ?, '2', 'Submitted new Question', 2, 0)
				""", bookletId, nodeId);
		ExamWorkManifest first = exporter.export(fixture.assignmentId(), destination);
		assertTrue(Files.isRegularFile(destination));
		assertThrows(IOException.class, () -> exporter.export(fixture.assignmentId(), destination));
		assertThrows(IllegalStateException.class,
				() -> exporter.export(fixture.assignmentId(), directory.resolve("second-submission.examwork")));
		var recorded = new SqliteContributionReturnLedger(database).findLatest(fixture.assignmentId()).orElseThrow();
		assertEquals(first.packageId(), recorded.packageId());
		assertEquals(1, recorded.returnNumber());
	}

	@Test
	void removesUnchangedAssignmentAndAllowsReimportButRejectsCapturedWork() throws Exception {
		Fixture fixture = fixture();
		SqliteDatabase database = fixture.contributorDatabase();
		var removal = new ContributorAssignmentRemovalService(database);
		var sessions = new SqliteContributorAssignmentSessionRepository(database);
		var visibility = new SqliteContributorAssignmentVisibilityRepository(database);
		UUID assignmentId = fixture.assignmentId();
		removal.removeUnchanged(assignmentId);
		assertEquals(SqliteContributorAssignmentVisibilityRepository.State.REMOVED,
				visibility.find(assignmentId).orElseThrow());
		assertTrue(sessions.active().isEmpty());
		ApplicationConfig contributor = ApplicationConfig.fromDataRoot(directory.resolve("contributor"));
		new WorkAssignmentInstallationCoordinator(contributor).install(directory.resolve("assignment.examwork"));
		assertEquals(SqliteContributorAssignmentVisibilityRepository.State.ACTIVE,
				visibility.find(assignmentId).orElseThrow());
		assertEquals(assignmentId, sessions.active().orElseThrow().assignmentId());
		var before = snapshot(fixture, "removal-before-new-work.db");
		ExchangeRecord issued = findRecord(before.baselineRecords(), "QUESTION");
		long bookletId = localId(database, "EXAM_BOOKLET",
				UUID.fromString(issued.values().path("booklet_id").asText()));
		long nodeId = localId(database, "CURRICULUM_NODE",
				UUID.fromString(issued.values().path("classification_node_id").asText()));
		executeOne(database, """
				INSERT INTO questions (
				    booklet_id, classification_node_id,
				    question_code, question_text, marks,
				    shared_context_capture_required
				)
				VALUES (?, ?, '2', 'Unsumbitted worker capture', 2, 0)
				""", bookletId, nodeId);
		assertThrows(IllegalStateException.class, () -> removal.removeUnchanged(assignmentId));
		assertEquals(SqliteContributorAssignmentVisibilityRepository.State.ACTIVE,
				visibility.find(assignmentId).orElseThrow());
		database.verifyIntegrity();
	}

	@Test
	void stableSnapshotDoesNotObserveLaterNewQuestionCapture() throws Exception {
		Fixture fixture = fixture();
		SqliteDatabase database = fixture.contributorDatabase();
		Path originalPath = directory.resolve("stable-before-capture.db");
		database.createConsistentSnapshot(originalPath);
		ContributionSnapshotReader reader = new ContributionSnapshotReader(originalPath);
		var before = reader.read(fixture.assignmentId());
		assertTrue(before.delta().candidates().isEmpty());
		ExchangeRecord issuedQuestion = findRecord(before.baselineRecords(), "QUESTION");
		long bookletId = localId(database, "EXAM_BOOKLET",
				UUID.fromString(issuedQuestion.values().path("booklet_id").asText()));
		long nodeId = localId(database, "CURRICULUM_NODE",
				UUID.fromString(issuedQuestion.values().path("classification_node_id").asText()));
		executeOne(database, """
				INSERT INTO questions (
				    booklet_id, classification_node_id,
				    question_code, question_text, marks,
				    shared_context_capture_required
				)
				VALUES (?, ?, '2', 'Captured after snapshot', 2, 0)
				""", bookletId, nodeId);
		var unchanged = reader.read(fixture.assignmentId());
		assertTrue(unchanged.delta().candidates().isEmpty());
		assertEquals(before.currentRecords(), unchanged.currentRecords());
		var refreshed = snapshot(fixture, "stable-after-capture.db");
		assertEquals(1, refreshed.delta().candidates().size());
		assertEquals("Captured after snapshot",
				refreshed.delta().candidates().getFirst().values().path("question_text").asText());
		assertEquals(before.baselineRecords(), refreshed.baselineRecords());
	}

	private byte[] archiveEntryBytes(Path archive, String entryName) throws IOException {
		try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(archive.toFile())) {
			var entry = zip.getEntry(entryName);
			if (entry == null) {
				throw new IOException("Missing contribution entry: " + entryName);
			}
			try (var input = zip.getInputStream(entry)) {
				return input.readAllBytes();
			}
		}
	}

	private void execute(SqliteDatabase database, String sql) throws SQLException {
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}

	private void executeOne(SqliteDatabase database, String sql, long... parameters) throws SQLException {
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement(sql)) {
			for (int index = 0; index < parameters.length; index++) {
				statement.setLong(index + 1, parameters[index]);
			}
			int affected = statement.executeUpdate();

			// Never let a regression test silently succeed
			// when its intended database mutation did nothing.
			if (affected != 1) {
				throw new SQLException("Expected exactly one affected row, found " + affected);
			}
		}
	}

	private ExchangeRecord findRecord(List<ExchangeRecord> records, String recordType) {
		return records.stream().filter(record -> record.recordType().equals(recordType)).findFirst().orElseThrow();
	}

	private Fixture fixture() throws Exception {
		ApplicationConfig coordinator = ApplicationConfig.fromDataRoot(directory.resolve("coordinator"));
		ApplicationConfig contributor = ApplicationConfig.fromDataRoot(directory.resolve("contributor"));
		Files.createDirectories(coordinator.dataRoot());
		Files.createDirectories(contributor.dataRoot());
		SqliteDatabase coordinatorDatabase = new SqliteDatabase(coordinator.databasePath());
		SqliteDatabase contributorDatabase = new SqliteDatabase(contributor.databasePath());
		coordinatorDatabase.initialiseSchema();
		contributorDatabase.initialiseSchema();
		Path questionPdf = coordinator.dataRoot().resolve("subjects/Biology/exams/questions.pdf");
		Path answerPdf = coordinator.dataRoot().resolve("subjects/Biology/exams/answers.pdf");
		Files.createDirectories(questionPdf.getParent());
		Files.write(questionPdf, TestPdf.bytes("Question PDF content"));
		Files.write(answerPdf, TestPdf.bytes("Answer PDF content"));
		execute(coordinatorDatabase, """
				INSERT INTO subjects (id, subject_name)
				VALUES (1, 'Biology')
				""");
		execute(coordinatorDatabase, """
				INSERT INTO syllabus_versions (
				    id, subject_id, syllabus_name, is_current
				)
				VALUES (11, 1, 'Biology 2025', 1)
				""");
		execute(coordinatorDatabase, """
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
		execute(coordinatorDatabase, """
				INSERT INTO exam_providers (id, provider_name)
				VALUES (21, 'QCAA')
				""");
		execute(coordinatorDatabase, """
				INSERT INTO source_documents (id, relative_path)
				VALUES
				    (51, 'subjects/Biology/exams/questions.pdf'),
				    (52, 'subjects/Biology/exams/answers.pdf')
				""");
		execute(coordinatorDatabase, """
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
		execute(coordinatorDatabase, """
				INSERT INTO answer_files (
				    id, exam_id, source_document_id, answer_file_name
				)
				VALUES (71, 31, 52, 'Marking Guide')
				""");
		execute(coordinatorDatabase, """
				INSERT INTO exam_booklets (
				    id, exam_id, source_document_id,
				    answer_file_id, booklet_name
				)
				VALUES
				    (61, 31, 51, 71, 'Paper 1'),
				    (62, 31, 51, 71, 'Paper 2')
				""");
		execute(coordinatorDatabase, """
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
		execute(coordinatorDatabase, """
				INSERT INTO answers (
				    id, question_id, answer_text
				)
				VALUES (91, 81, 'Biology answer')
				""");
		Exam exam = new Exam(31, new Subject(1, "Biology"), new ExamProvider(21, "QCAA"), 2025, "External Assessment");
		UUID assignmentId = UUID.randomUUID();
		Path archive = directory.resolve("assignment.examwork");
		WorkAssignmentExporter exporter = new WorkAssignmentExporter(coordinatorDatabase,
				new ManagedDataLayout(coordinator.dataRoot()));
		exporter.export(new WorkAssignmentExporter.Request(exam, assignmentId, UUID.randomUUID(),
				"Integration Contributor", false, archive));
		new WorkAssignmentInstallationCoordinator(contributor).install(archive);
		return new Fixture(contributorDatabase, assignmentId);
	}

	private long localId(SqliteDatabase database, String recordType, UUID portableId) throws SQLException {
		SqlitePortableIdentityRepository identities = new SqlitePortableIdentityRepository(database);
		EntityType type = EntityType.valueOf(recordType);
		return identities.findByPortableId(type, portableId)
				.orElseThrow(
						() -> new SQLException("Missing contributor-local identity: " + recordType + ":" + portableId))
				.localId();
	}

	private ContributionSnapshotReader.Snapshot snapshot(Fixture fixture, String filename) throws Exception {
		Path path = directory.resolve(filename);
		fixture.contributorDatabase().createConsistentSnapshot(path);
		return new ContributionSnapshotReader(path).read(fixture.assignmentId());
	}

	private record Fixture(SqliteDatabase contributorDatabase, UUID assignmentId) {
	}
}
