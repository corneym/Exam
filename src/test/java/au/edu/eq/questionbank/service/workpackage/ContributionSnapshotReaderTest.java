package au.edu.eq.questionbank.service.workpackage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

public class ContributionSnapshotReaderTest {

	@TempDir
	Path directory;

	@Test
	void exportsFourPartialPackagesWithOriginalBaselineAndLineage() throws Exception {
		Fixture fixture = fixture();
		SqliteDatabase contributorDatabase = fixture.contributorDatabase();
		var sessions = new SqliteContributorAssignmentSessionRepository(contributorDatabase);
		var ledger = new SqliteContributionReturnLedger(contributorDatabase);
		ContributionExporter exporter = new ContributionExporter(contributorDatabase,
				new ManagedDataLayout(directory.resolve("contributor")));
		var initial = snapshot(fixture, "export-initial-snapshot.db");
		ExchangeRecord question = findRecord(initial.baselineRecords(), "QUESTION");
		ExchangeRecord answer = findRecord(initial.baselineRecords(), "ANSWER");
		long questionLocalId = localId(contributorDatabase, "QUESTION", question.recordId());
		long answerLocalId = localId(contributorDatabase, "ANSWER", answer.recordId());
		long bookletLocalId = localId(contributorDatabase, "EXAM_BOOKLET",
				UUID.fromString(question.values().path("booklet_id").asText()));
		long classificationLocalId = localId(contributorDatabase, "CURRICULUM_NODE",
				UUID.fromString(question.values().path("classification_node_id").asText()));
		byte[] originalBaseline = sessions.readBaseline(fixture.assignmentId()).records();
		UUID previousPackageId = null;
		List<Integer> expectedCandidateCounts = List.of(1, 2, 3, 3);
		for (int round = 1; round <= 4; round++) {
			switch (round) {
			case 1 -> executeOne(contributorDatabase, """
					UPDATE questions
					SET question_text = 'First contribution'
					WHERE id = ?
					""", questionLocalId);
			case 2 -> executeOne(contributorDatabase, """
					UPDATE answers
					SET answer_text = 'Second contribution answer'
					WHERE id = ?
					""", answerLocalId);
			case 3 -> executeOne(contributorDatabase, """
					INSERT INTO questions (
					    booklet_id,
					    classification_node_id,
					    question_code,
					    question_text,
					    marks,
					    shared_context_capture_required
					)
					VALUES (
					    ?, ?, '2',
					    'Third contribution question', 3, 0
					)
					""", bookletLocalId, classificationLocalId);
			case 4 -> executeOne(contributorDatabase, """
					UPDATE questions
					SET question_text = 'Fourth contribution revision'
					WHERE id = ?
					""", questionLocalId);
			default -> throw new AssertionError(round);
			}
			Path archive = directory.resolve("contribution-" + round + ".examwork");
			ExamWorkManifest manifest = exporter.export(fixture.assignmentId(), archive);
			assertEquals(ExamWorkPackageType.CONTRIBUTION, manifest.packageType());
			assertEquals(fixture.assignmentId(), manifest.assignmentId());
			assertTrue(Files.isRegularFile(archive));
			new ExamWorkArchiveValidator().validate(archive, manifest.producerApplicationVersion(),
					manifest.producerSchemaVersion());
			assertArrayEquals(originalBaseline, archiveEntryBytes(archive, "baseline/initial-records.jsonl"));
			var codec = new ExamWorkJsonlCodec();
			List<ExchangeRecord> changes = codec.readRecords(
					new java.io.ByteArrayInputStream(archiveEntryBytes(archive, "records/contribution-changes.jsonl")));
			assertEquals(expectedCandidateCounts.get(round - 1).intValue(), changes.size());
			List<ExchangeRecord> contexts = codec.readRecords(
					new java.io.ByteArrayInputStream(archiveEntryBytes(archive, "records/contribution-context.jsonl")));
			assertEquals(1, contexts.size());
			ExchangeRecord context = contexts.getFirst();
			assertEquals("CONTRIBUTION_CONTEXT", context.recordType());
			assertEquals(manifest.packageId(), context.recordId());
			assertEquals(fixture.assignmentId().toString(), context.values().path("assignment_id").asText());
			assertEquals(round, context.values().path("return_number").asInt());
			assertEquals(changes.size(), context.values().path("candidate_count").asInt());
			if (previousPackageId == null) {
				assertTrue(context.values().path("previous_return_package_id").isNull());
			} else {
				assertEquals(previousPackageId.toString(),
						context.values().path("previous_return_package_id").asText());
			}
			var recorded = ledger.find(manifest.packageId()).orElseThrow();
			assertEquals(round, recorded.returnNumber());
			assertEquals(previousPackageId, recorded.previousPackageId());
			previousPackageId = manifest.packageId();
		}
		assertEquals(4, ledger.findLatest(fixture.assignmentId()).orElseThrow().returnNumber());
		assertArrayEquals(originalBaseline, sessions.readBaseline(fixture.assignmentId()).records());
		assertEquals(fixture.assignmentId(), sessions.active().orElseThrow().assignmentId());
	}

	@Test
	void keepsTheOriginalBaselineAcrossFourSnapshots() throws Exception {
		Fixture fixture = fixture();
		SqliteContributorAssignmentSessionRepository sessions = new SqliteContributorAssignmentSessionRepository(
				fixture.contributorDatabase());
		byte[] originalBaseline = sessions.readBaseline(fixture.assignmentId()).records();

		// Snapshot 1: no contributor modifications.
		var first = snapshot(fixture, "snapshot-1.db");
		assertTrue(first.delta().candidates().isEmpty());
		ExchangeRecord originalQuestion = findRecord(first.baselineRecords(), "QUESTION");
		ExchangeRecord originalAnswer = findRecord(first.baselineRecords(), "ANSWER");

		// Resolve portable identities into the contributor's IDs.
		// The coordinator's original integer IDs are not portable.
		long questionLocalId = localId(fixture.contributorDatabase(), "QUESTION", originalQuestion.recordId());
		long answerLocalId = localId(fixture.contributorDatabase(), "ANSWER", originalAnswer.recordId());
		long bookletLocalId = localId(fixture.contributorDatabase(), "EXAM_BOOKLET",
				UUID.fromString(originalQuestion.values().path("booklet_id").asText()));
		long classificationLocalId = localId(fixture.contributorDatabase(), "CURRICULUM_NODE",
				UUID.fromString(originalQuestion.values().path("classification_node_id").asText()));

		// Snapshot 2: an existing Question is edited.
		executeOne(fixture.contributorDatabase(), """
				UPDATE questions
				SET question_text = 'Contributor edited question'
				WHERE id = ?
				""", questionLocalId);
		var second = snapshot(fixture, "snapshot-2.db");
		ExchangeRecord changedQuestion = second.delta().candidates().stream()
				.filter(record -> record.recordType().equals("QUESTION")).findFirst().orElseThrow();
		assertEquals("Contributor edited question", changedQuestion.values().path("question_text").asText());
		assertEquals(originalQuestion.revisionId(), changedQuestion.baselineRevisionId());
		assertNotEquals(originalQuestion.revisionId(), changedQuestion.revisionId());

		// Snapshot 3: capture another Question using the
		// contributor's own local Booklet and syllabus IDs.
		executeOne(fixture.contributorDatabase(), """
				INSERT INTO questions (
				    booklet_id, classification_node_id,
				    question_code, question_text, marks,
				    shared_context_capture_required
				)
				VALUES (
				    ?, ?,
				    '2', 'New contributor question', 3, 0
				)
				""", bookletLocalId, classificationLocalId);
		var third = snapshot(fixture, "snapshot-3.db");
		assertEquals(2,
				third.delta().candidates().stream().filter(record -> record.recordType().equals("QUESTION")).count());
		assertEquals(1,
				third.delta().candidates().stream()
						.filter(record -> record.recordType().equals("QUESTION") && record.baselineRevisionId() == null)
						.count());

		// Snapshot 4: edit the existing Answer.
		executeOne(fixture.contributorDatabase(), """
				UPDATE answers
				SET answer_text = 'Contributor edited answer'
				WHERE id = ?
				""", answerLocalId);
		var fourth = snapshot(fixture, "snapshot-4.db");
		assertTrue(fourth.delta().candidates().stream().anyMatch(
				record -> record.recordType().equals("ANSWER") && record.recordId().equals(originalAnswer.recordId())));
		assertTrue(fourth.delta().candidates().stream().anyMatch(record -> record.recordType().equals("QUESTION")
				&& record.recordId().equals(changedQuestion.recordId())));

		// Every comparison still uses the original issued baseline.
		assertEquals(first.baselineRecords(), fourth.baselineRecords());
		assertArrayEquals(originalBaseline, sessions.readBaseline(fixture.assignmentId()).records());
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
	void rejectsEmptyAndOverwritingContributionExports() throws Exception {
		Fixture fixture = fixture();
		ContributionExporter exporter = new ContributionExporter(fixture.contributorDatabase(),
				new ManagedDataLayout(directory.resolve("contributor")));
		var ledger = new SqliteContributionReturnLedger(fixture.contributorDatabase());
		Path destination = directory.resolve("single-contribution.examwork");
		assertThrows(IOException.class, () -> exporter.export(fixture.assignmentId(), destination));
		assertFalse(Files.exists(destination));
		assertTrue(ledger.findLatest(fixture.assignmentId()).isEmpty());
		var original = snapshot(fixture, "export-empty-check.db");
		ExchangeRecord question = findRecord(original.baselineRecords(), "QUESTION");
		long questionId = localId(fixture.contributorDatabase(), "QUESTION", question.recordId());
		executeOne(fixture.contributorDatabase(), """
				UPDATE questions
				SET question_text = 'Exportable question'
				WHERE id = ?
				""", questionId);
		ExamWorkManifest first = exporter.export(fixture.assignmentId(), destination);
		assertTrue(Files.isRegularFile(destination));
		assertThrows(IOException.class, () -> exporter.export(fixture.assignmentId(), destination));
		assertEquals(first.packageId(), ledger.findLatest(fixture.assignmentId()).orElseThrow().packageId());
		assertEquals(1, ledger.findLatest(fixture.assignmentId()).orElseThrow().returnNumber());
	}

	@Test
	void snapshotDoesNotObserveLaterLiveDatabaseChanges() throws Exception {
		Fixture fixture = fixture();
		Path originalPath = directory.resolve("stable-before-edit.db");
		fixture.contributorDatabase().createConsistentSnapshot(originalPath);
		ContributionSnapshotReader reader = new ContributionSnapshotReader(originalPath);
		var before = reader.read(fixture.assignmentId());
		assertTrue(before.delta().candidates().isEmpty());
		ExchangeRecord originalQuestion = findRecord(before.baselineRecords(), "QUESTION");
		long questionLocalId = localId(fixture.contributorDatabase(), "QUESTION", originalQuestion.recordId());

		// Modify the live contributor database after the
		// first immutable snapshot has been created.
		executeOne(fixture.contributorDatabase(), """
				UPDATE questions
				SET question_text = 'Edited after SQLite snapshot'
				WHERE id = ?
				""", questionLocalId);

		// Re-reading the original snapshot must not observe
		// the later live database modification.
		var original = reader.read(fixture.assignmentId());
		assertTrue(original.delta().candidates().isEmpty());
		assertEquals(before.currentRecords(), original.currentRecords());

		// A fresh snapshot must contain the modification.
		var updated = snapshot(fixture, "stable-after-edit.db");
		ExchangeRecord changedQuestion = updated.delta().candidates().stream()
				.filter(record -> record.recordType().equals("QUESTION")).findFirst().orElseThrow();
		assertEquals(originalQuestion.recordId(), changedQuestion.recordId());
		assertEquals("Edited after SQLite snapshot", changedQuestion.values().path("question_text").asText());
		assertEquals(originalQuestion.revisionId(), changedQuestion.baselineRevisionId());
		assertEquals(original.baselineRecords(), updated.baselineRecords());
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
		Files.writeString(questionPdf, "Question PDF content");
		Files.writeString(answerPdf, "Answer PDF content");
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
