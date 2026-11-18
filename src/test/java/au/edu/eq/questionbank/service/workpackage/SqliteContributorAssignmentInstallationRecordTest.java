package au.edu.eq.questionbank.service.workpackage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.workpackage.ExamWorkManifest.Scope;
import au.edu.eq.questionbank.service.workpackage.ExamWorkManifest.ScopeLevel;

class SqliteContributorAssignmentInstallationRecordTest {

	@TempDir
	Path directory;

	@Test
	void duplicateInstallationDoesNotRewriteBaseline() throws Exception {
		SqliteDatabase database = database();
		var sessions = new SqliteContributorAssignmentSessionRepository(database);
		var inspection = inspection(database);
		var localIds = new SqliteContributorAssignmentSessionRepository.LocalIds(1, 31, 11, null);
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			sessions.registerInstalled(connection, inspection, localIds);
			connection.commit();
		}
		byte[] original = sessions.readBaseline(inspection.manifest().assignmentId()).records();

		// Ordinary work can change a material record revision after
		// installation. Reopening the same assignment must remain valid.
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					UPDATE exams
					SET exam_name = 'Changed after installation'
					WHERE id = 31
					""");
		}
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			sessions.registerInstalled(connection, inspection, localIds);
			connection.commit();
		}
		assertEquals(1, sessions.findAll().size());
		assertArrayEquals(original, sessions.readBaseline(inspection.manifest().assignmentId()).records());
	}

	@Test
	void refusesChangedOriginalRecordRevision() throws Exception {
		SqliteDatabase database = database();
		var sessions = new SqliteContributorAssignmentSessionRepository(database);
		var inspection = inspection(database);
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					UPDATE exams
					SET exam_name = 'Changed before registration'
					WHERE id = 31
					""");
		}
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			assertThrows(SQLException.class, () -> sessions.registerInstalled(connection, inspection,
					new SqliteContributorAssignmentSessionRepository.LocalIds(1, 31, 11, null)));
			connection.rollback();
		}
		assertTrue(sessions.findAll().isEmpty());
	}

	@Test
	void refusesToRegisterWrongLocalExam() throws Exception {
		SqliteDatabase database = database();
		var sessions = new SqliteContributorAssignmentSessionRepository(database);
		var inspection = inspection(database);
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			assertThrows(SQLException.class, () -> sessions.registerInstalled(connection, inspection,
					new SqliteContributorAssignmentSessionRepository.LocalIds(1, 32, 11, null)));
			connection.rollback();
		}
		assertTrue(sessions.findAll().isEmpty());
	}

	@Test
	void registersBaselineInInstallationTransaction() throws Exception {
		SqliteDatabase database = database();
		var sessions = new SqliteContributorAssignmentSessionRepository(database);
		var inspection = inspection(database);
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			var registered = sessions.registerInstalled(connection, inspection,
					new SqliteContributorAssignmentSessionRepository.LocalIds(1, 31, 11, null));
			assertEquals(inspection.manifest().assignmentId(), registered.assignmentId());
			connection.commit();
		}
		assertEquals(1, sessions.findAll().size());

		// Installation does not silently activate contributor mode.
		assertTrue(sessions.active().isEmpty());
		var baseline = sessions.readBaseline(inspection.manifest().assignmentId());
		var decoded = new ExamWorkJsonlCodec().readRecords(new ByteArrayInputStream(baseline.records()));

		// The JSONL codec writes records in canonical type/UUID order,
		// not the original dependency order of the test fixture.
		List<ExchangeRecord> expected = inspection.baseline().stream().sorted(
				Comparator.comparing(ExchangeRecord::recordType).thenComparing(record -> record.recordId().toString()))
				.toList();

		// Compare complete records, including portable identity,
		// revision identity and all domain values.
		assertEquals(expected, decoded);

		// Baseline access must return independent byte arrays.
		byte[] original = baseline.records();
		byte[] modified = baseline.records();
		modified[0] = (byte) (modified[0] ^ 0x01);
		assertArrayEquals(original, baseline.records());
		sessions.select(inspection.manifest().assignmentId());
		assertEquals(inspection.manifest().assignmentId(), sessions.active().orElseThrow().assignmentId());
	}

	private SqliteDatabase database() throws Exception {
		SqliteDatabase database = new SqliteDatabase(directory.resolve("contributor.db"));
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					INSERT INTO subjects (id, subject_name)
					VALUES (1, 'Chemistry')
					""");
			statement.execute("""
					INSERT INTO syllabus_versions (
					    id, subject_id, syllabus_name, is_current
					)
					VALUES (11, 1, 'Chemistry 2025', 1)
					""");
			statement.execute("""
					INSERT INTO curriculum_nodes (
					    id, syllabus_version_id, parent_id,
					    curriculum_code, curriculum_name,
					    curriculum_level, display_order
					)
					VALUES (
					    12, 11, NULL, 'U1', 'Unit One', 'UNIT', 0
					)
					""");
			statement.execute("""
					INSERT INTO exam_providers (id, provider_name)
					VALUES (21, 'QCAA')
					""");
			statement.execute("""
					INSERT INTO exams (
					    id, subject_id, provider_id,
					    exam_year, exam_name
					)
					VALUES
					    (31, 1, 21, 2025, 'Chemistry Exam'),
					    (32, 1, 21, 2024, 'Other Exam')
					""");
			statement.execute(
					"UPDATE exams SET expected_question_booklet_count = 1, expected_answer_file_count = 0 WHERE id = 31");
			statement.execute(
					"INSERT INTO source_documents (id, relative_path) VALUES (41, 'subjects/Chemistry/exams/questions.pdf')");
			statement.execute(
					"INSERT INTO exam_booklets (id, exam_id, source_document_id, booklet_name) VALUES (51, 31, 41, 'Paper 1')");
		}
		return database;
	}

	private WorkAssignmentPackageInspector.Inspection inspection(SqliteDatabase database) throws Exception {
		var identities = new SqlitePortableIdentityRepository(database);
		var subject = identities.requireByLocalId(EntityType.SUBJECT, 1);
		var syllabus = identities.requireByLocalId(EntityType.SYLLABUS_VERSION, 11);
		var node = identities.requireByLocalId(EntityType.CURRICULUM_NODE, 12);
		var provider = identities.requireByLocalId(EntityType.EXAM_PROVIDER, 21);
		var exam = identities.requireByLocalId(EntityType.EXAM, 31);
		var source = identities.requireByLocalId(EntityType.SOURCE_DOCUMENT, 41);
		var booklet = identities.requireByLocalId(EntityType.EXAM_BOOKLET, 51);
		UUID assignmentId = UUID.randomUUID();
		UUID baselineId = UUID.randomUUID();
		UUID masterId = UUID.randomUUID();
		Instant issued = Instant.parse("2026-11-16T00:00:00Z");
		var manifest = new ExamWorkManifest(1, UUID.randomUUID(), ExamWorkPackageType.WORK_ASSIGNMENT, issued, masterId,
				masterId, subject.portableId(), "Chemistry", assignmentId, baselineId, "1.0.1", "1.0.1", 29, 29,
				new Scope(ScopeLevel.EXAMS, List.of(exam.portableId()), List.of()), List.of());
		ObjectNode subjectValues = values();
		subjectValues.put("subject_name", "Chemistry");
		ObjectNode syllabusValues = values();
		syllabusValues.put("subject_id", subject.portableId().toString());
		syllabusValues.put("syllabus_name", "Chemistry 2025");
		syllabusValues.put("is_current", 1);
		ObjectNode nodeValues = values();
		nodeValues.put("syllabus_version_id", syllabus.portableId().toString());
		ObjectNode providerValues = values();
		providerValues.put("provider_name", "QCAA");
		ObjectNode examValues = values();
		examValues.put("subject_id", subject.portableId().toString());
		examValues.put("provider_id", provider.portableId().toString());
		examValues.put("expected_question_booklet_count", 1);
		examValues.put("expected_answer_file_count", 0);
		ObjectNode bookletValues = values();
		bookletValues.put("exam_id", exam.portableId().toString());
		bookletValues.put("source_document_id", source.portableId().toString());
		List<ExchangeRecord> records = List.of(record("SUBJECT", subject, subjectValues),
				record("SYLLABUS_VERSION", syllabus, syllabusValues), record("CURRICULUM_NODE", node, nodeValues),
				record("EXAM_PROVIDER", provider, providerValues), record("EXAM", exam, examValues),
				record("SOURCE_DOCUMENT", source, values()), record("EXAM_BOOKLET", booklet, bookletValues));
		ObjectNode contextValues = values();
		contextValues.put("assignment_id", assignmentId.toString());
		contextValues.put("baseline_snapshot_id", baselineId.toString());
		contextValues.put("master_installation_id", masterId.toString());
		contextValues.put("subject_id", subject.portableId().toString());
		contextValues.put("exam_id", exam.portableId().toString());
		contextValues.put("current_syllabus_id", syllabus.portableId().toString());
		contextValues.put("issued_at", issued.toString());
		contextValues.put("legacy_capture_enabled", false);
		contextValues.put("contributor_id", UUID.randomUUID().toString());
		contextValues.put("contributor_name", "Test Contributor");
		var context = new ExchangeRecord(1, "WORK_ASSIGNMENT_CONTEXT", assignmentId, baselineId, null, contextValues);
		return new WorkAssignmentPackageInspector.Inspection(manifest, records, records, context,
				new ExamWorkAssetIndex(
						List.of(new ExamWorkAssetIndex.Reference("assets/sha256/aa/" + "a".repeat(64) + ".pdf",
								"a".repeat(64), 1, "application/pdf", "subjects/Chemistry/exams/questions.pdf",
								"SOURCE_DOCUMENT", source.portableId(), "SOURCE_PDF"))),
				"a".repeat(64));
	}

	private ExchangeRecord record(String type, SqlitePortableIdentityRepository.Identity identity, ObjectNode values) {
		return new ExchangeRecord(1, type, identity.portableId(), identity.revisionId(), null, values);
	}

	private ObjectNode values() {
		return JsonNodeFactory.instance.objectNode();
	}
}
