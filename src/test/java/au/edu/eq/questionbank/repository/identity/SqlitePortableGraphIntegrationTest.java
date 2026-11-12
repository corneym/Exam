package au.edu.eq.questionbank.repository.identity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.repository.identity.SqlitePortableContentRepository.Snapshot;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.workpackage.ExamWorkJsonlCodec;
import au.edu.eq.questionbank.service.workpackage.ExchangeRecord;

class SqlitePortableGraphIntegrationTest {

	@TempDir
	Path tempDirectory;

	@Test
	void approvedAliasesAndIdentityGraphSurviveDatabaseSnapshot() throws Exception {
		SqliteDatabase database = createDatabase("master.db");
		seedGraph(database);
		SqlitePortableIdentityRepository identities = new SqlitePortableIdentityRepository(database);
		SqlitePortableContentRepository content = new SqlitePortableContentRepository(database);
		UUID installationId = identities.installationId();
		UUID canonicalSubjectId = identities.requireByLocalId(EntityType.SUBJECT, 1).portableId();
		UUID originInstallation = UUID.randomUUID();
		UUID originSubjectId = UUID.randomUUID();
		SqlitePortableAliasRepository aliases = new SqlitePortableAliasRepository(database);
		aliases.approveAlias(originInstallation, EntityType.SUBJECT, originSubjectId, canonicalSubjectId,
				"Reviewed Biology source correspondence");
		Snapshot originalSubject = content.snapshot(EntityType.SUBJECT, 1);
		Snapshot originalQuestion = content.snapshot(EntityType.QUESTION, 1);
		Path snapshotPath = tempDirectory.resolve("snapshot.db");
		database.createConsistentSnapshot(snapshotPath);
		SqliteDatabase restored = new SqliteDatabase(snapshotPath);
		restored.verifySchema();
		restored.verifyIntegrity();
		SqlitePortableIdentityRepository restoredIdentities = new SqlitePortableIdentityRepository(restored);
		SqlitePortableAliasRepository restoredAliases = new SqlitePortableAliasRepository(restored);
		SqlitePortableContentRepository restoredContent = new SqlitePortableContentRepository(restored);
		assertEquals(installationId, restoredIdentities.installationId());
		assertEquals(originalSubject, restoredContent.snapshot(EntityType.SUBJECT, 1));
		assertEquals(originalQuestion, restoredContent.snapshot(EntityType.QUESTION, 1));
		assertEquals(canonicalSubjectId,
				restoredAliases.findAlias(originInstallation, EntityType.SUBJECT, originSubjectId).orElseThrow());

		// Snapshot restore is the same corpus lineage. It must not
		// manufacture new UUIDs.
		assertEquals(originalQuestion.record().recordId(),
				restoredIdentities.requireByLocalId(EntityType.QUESTION, 1).portableId());
	}

	@Test
	void brokenPortableReferencesCannotBeSilentlyExported() throws Exception {
		SqliteDatabase database = createDatabase("broken.db");
		seedGraph(database);

		// Simulate corruption of the identity registry in an isolated
		// test database, without deleting its domain entity.
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					DELETE FROM portable_entity_identities
					WHERE entity_type = 'EXAM_BOOKLET'
					  AND local_id = 1
					""");
		}
		SqlitePortableContentRepository content = new SqlitePortableContentRepository(database);
		assertThrows(SQLException.class, () -> content.snapshot(EntityType.QUESTION, 1));
	}

	@Test
	void changingPathCanAdvanceRevisionWithoutChangingContentHash() throws Exception {
		SqliteDatabase database = createDatabase("causality.db");
		seedGraph(database);
		SqlitePortableContentRepository content = new SqlitePortableContentRepository(database);
		Snapshot before = content.snapshot(EntityType.SOURCE_DOCUMENT, 1);
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					UPDATE source_documents
					SET relative_path =
					    'subjects/Biology/relocated/questions.pdf'
					WHERE id = 1
					""");
		}
		Snapshot after = content.snapshot(EntityType.SOURCE_DOCUMENT, 1);
		assertEquals(before.record().recordId(), after.record().recordId());
		assertEquals(before.contentSha256(), after.contentSha256());

		// Content equality and causal revision are different concepts.
		assertNotEquals(before.record().revisionId(), after.record().revisionId());
		ExamWorkJsonlCodec codec = new ExamWorkJsonlCodec();
		ByteArrayOutputStream beforeOutput = new ByteArrayOutputStream();
		ByteArrayOutputStream afterOutput = new ByteArrayOutputStream();
		codec.writeRecords(List.of(before.record()), beforeOutput);
		codec.writeRecords(List.of(after.record()), afterOutput);
		assertNotEquals(new String(beforeOutput.toByteArray(), java.nio.charset.StandardCharsets.UTF_8),
				new String(afterOutput.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
	}

	@Test
	void portableGraphRecordsRoundTripThroughCanonicalJsonl() throws Exception {
		SqliteDatabase database = createDatabase("jsonl.db");
		seedGraph(database);
		SqlitePortableContentRepository content = new SqlitePortableContentRepository(database);
		List<ExchangeRecord> records = graphRecords(content);
		ExamWorkJsonlCodec codec = new ExamWorkJsonlCodec();
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		codec.writeRecords(records, output);
		byte[] encoded = output.toByteArray();
		List<ExchangeRecord> decoded = codec.readRecords(new ByteArrayInputStream(encoded));
		assertEquals(records.size(), decoded.size());
		ByteArrayOutputStream reencoded = new ByteArrayOutputStream();
		codec.writeRecords(decoded, reencoded);
		assertArrayEquals(encoded, reencoded.toByteArray());

		// The codec preserves all portable references, content
		// arrays and causal revisions without local integer keys.
		ExchangeRecord question = decoded.stream().filter(record -> record.recordType().equals("QUESTION")).findFirst()
				.orElseThrow();
		SqlitePortableIdentityRepository identities = new SqlitePortableIdentityRepository(database);
		assertEquals(identities.requireByLocalId(EntityType.EXAM_BOOKLET, 1).portableId().toString(),
				question.values().path("booklet_id").asText());
		assertEquals(identities.requireByLocalId(EntityType.CURRICULUM_NODE, 1).portableId().toString(),
				question.values().path("classification_node_id").asText());
		assertEquals(1, question.values().path("question_regions").size());
		assertEquals(2, question.values().path("question_content_parts").size());
		assertEquals(1, question.recordVersion());
		assertTrue(question.revisionId() != null);
	}

	@Test
	void uncommittedChangesDoNotLeakIntoPortableSnapshots() throws Exception {
		SqliteDatabase database = createDatabase("rollback.db");
		seedGraph(database);
		SqlitePortableContentRepository content = new SqlitePortableContentRepository(database);
		Snapshot before = content.snapshot(EntityType.QUESTION, 1);
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try (Statement statement = connection.createStatement()) {
				statement.execute("""
						UPDATE questions
						SET marks = 5
						WHERE id = 1
						""");
			}
			connection.rollback();
		}
		Snapshot after = content.snapshot(EntityType.QUESTION, 1);
		assertEquals(before, after);
	}

	private SqliteDatabase createDatabase(String filename) throws SQLException {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve(filename));
		database.initialiseSchema();
		return database;
	}

	private List<ExchangeRecord> graphRecords(SqlitePortableContentRepository content) throws SQLException {
		List<ExchangeRecord> records = new ArrayList<>();
		records.add(content.snapshot(EntityType.SUBJECT, 1).record());
		records.add(content.snapshot(EntityType.SYLLABUS_VERSION, 1).record());
		records.add(content.snapshot(EntityType.CURRICULUM_NODE, 1).record());
		records.add(content.snapshot(EntityType.EXAM_PROVIDER, 1).record());
		records.add(content.snapshot(EntityType.SOURCE_DOCUMENT, 1).record());
		records.add(content.snapshot(EntityType.SOURCE_DOCUMENT, 2).record());
		records.add(content.snapshot(EntityType.EXAM, 1).record());
		records.add(content.snapshot(EntityType.EXAM_BOOKLET, 1).record());
		records.add(content.snapshot(EntityType.ANSWER_FILE, 1).record());
		records.add(content.snapshot(EntityType.SOURCE_QUESTION, 1).record());
		records.add(content.snapshot(EntityType.SHARED_QUESTION_CONTEXT, 1).record());
		records.add(content.snapshot(EntityType.QUESTION, 1).record());
		records.add(content.snapshot(EntityType.QUESTION_IMAGE, 1).record());
		records.add(content.snapshot(EntityType.ANSWER, 1).record());
		return List.copyOf(records);
	}

	private void seedGraph(SqliteDatabase database) throws SQLException {
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					INSERT INTO subjects (id, subject_name)
					VALUES (1, 'Biology')
					""");
			statement.execute("""
					INSERT INTO syllabus_versions (
					    id, subject_id, syllabus_name, is_current
					)
					VALUES (1, 1, 'Biology 2025', 1)
					""");
			statement.execute("""
					INSERT INTO curriculum_nodes (
					    id, syllabus_version_id, parent_id,
					    curriculum_code, curriculum_name,
					    curriculum_level, display_order
					)
					VALUES (
					    1, 1, NULL, 'U1', 'Unit 1', 'UNIT', 0
					)
					""");
			statement.execute("""
					INSERT INTO exam_providers (id, provider_name)
					VALUES (1, 'QCAA')
					""");
			statement.execute("""
					INSERT INTO source_documents (
					    id, relative_path, content_sha256
					)
					VALUES (
					    1, 'subjects/Biology/questions.pdf',
					    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
					)
					""");
			statement.execute("""
					INSERT INTO source_documents (
					    id, relative_path, content_sha256
					)
					VALUES (
					    2, 'subjects/Biology/answers.pdf',
					    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
					)
					""");
			statement.execute("""
					INSERT INTO exams (
					    id, subject_id, provider_id,
					    exam_year, exam_name
					)
					VALUES (
					    1, 1, 1, 2025, 'External Assessment'
					)
					""");
			statement.execute("""
					INSERT INTO exam_booklets (
					    id, exam_id, source_document_id, booklet_name
					)
					VALUES (1, 1, 1, 'Paper 1')
					""");
			statement.execute("""
					INSERT INTO answer_files (
					    id, exam_id, source_document_id,
					    answer_file_name
					)
					VALUES (1, 1, 2, 'Marking guide')
					""");
			statement.execute("""
					INSERT INTO source_questions (
					    id, booklet_id, source_question_code
					)
					VALUES (1, 1, '1')
					""");
			statement.execute("""
					INSERT INTO shared_question_contexts (
					    id, booklet_id, context_label
					)
					VALUES (1, 1, 'Stimulus')
					""");
			statement.execute("""
					INSERT INTO questions (
					    id, booklet_id, classification_node_id,
					    question_code, question_text, marks,
					    shared_context_capture_required,
					    source_question_id, shared_context_id
					)
					VALUES (
					    1, 1, 1,
					    '1', 'Example question', 1,
					    0, 1, 1
					)
					""");
			statement.execute("""
					INSERT INTO question_regions (
					    question_id, region_order, booklet_id,
					    page_number, x, y, width, height
					)
					VALUES (
					    1, 0, 1,
					    2, 0.10, 0.20, 0.50, 0.30
					)
					""");
			statement.execute("""
					INSERT INTO question_images (
					    id, question_id, image_png
					)
					VALUES (1, 1, X'010203')
					""");
			statement.execute("""
					INSERT INTO question_content_parts (
					    question_id, content_order,
					    content_type, region_order
					)
					VALUES (1, 0, 'PDF_REGION', 0)
					""");
			statement.execute("""
					INSERT INTO question_content_parts (
					    question_id, content_order,
					    content_type, image_id
					)
					VALUES (1, 1, 'IMAGE', 1)
					""");
			statement.execute("""
					INSERT INTO answers (
					    id, question_id, answer_text
					)
					VALUES (1, 1, 'Example answer')
					""");
		}
	}
}
