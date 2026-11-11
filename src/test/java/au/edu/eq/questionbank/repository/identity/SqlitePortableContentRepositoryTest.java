package au.edu.eq.questionbank.repository.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.repository.identity.SqlitePortableContentRepository.Snapshot;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class SqlitePortableContentRepositoryTest {

	@TempDir
	Path tempDirectory;

	@Test
	void canonicalQuestionUsesPortableReferencesAndOrderedChildren() throws Exception {
		SqliteDatabase database = createDatabase("graph.db");
		seedGraph(database);
		SqlitePortableContentRepository content = new SqlitePortableContentRepository(database);
		SqlitePortableIdentityRepository identities = new SqlitePortableIdentityRepository(database);
		Snapshot question = content.snapshot(EntityType.QUESTION, 1);
		assertEquals(identities.requireByLocalId(EntityType.QUESTION, 1).portableId(), question.record().recordId());
		assertEquals(identities.requireByLocalId(EntityType.EXAM_BOOKLET, 1).portableId().toString(),
				question.record().values().path("booklet_id").asText());
		assertEquals(identities.requireByLocalId(EntityType.CURRICULUM_NODE, 1).portableId().toString(),
				question.record().values().path("classification_node_id").asText());
		assertFalse(question.record().values().has("id"));
		assertEquals(0, question.record().values().path("question_regions").get(0).path("region_order").asInt());
		assertEquals("PDF_REGION",
				question.record().values().path("question_content_parts").get(0).path("content_type").asText());
		assertEquals("IMAGE",
				question.record().values().path("question_content_parts").get(1).path("content_type").asText());
		assertEquals(identities.requireByLocalId(EntityType.QUESTION_IMAGE, 1).portableId().toString(),
				question.record().values().path("question_content_parts").get(1).path("image_id").asText());
		assertEquals("039058c6f2c0cb492c533b0a4d14ef77" + "cc0f78abccced5287d84a1a2011cfb81",
				question.record().values().path("question_images").get(0).path("image_png").asText());
		assertEquals(64, question.contentSha256().length());
		assertFalse(question.record().values().toString().contains("subjects/Biology"));
	}

	@Test
	void collidingNumericIdsAcrossDatabasesDoNotCreateMatchingIdentity() throws Exception {
		SqliteDatabase first = createDatabase("first.db");
		SqliteDatabase second = createDatabase("second.db");
		seedGraph(first);
		seedGraph(second);
		SqlitePortableContentRepository firstContent = new SqlitePortableContentRepository(first);
		SqlitePortableContentRepository secondContent = new SqlitePortableContentRepository(second);
		Snapshot firstQuestion = firstContent.snapshot(EntityType.QUESTION, 1);
		Snapshot secondQuestion = secondContent.snapshot(EntityType.QUESTION, 1);
		assertNotEquals(firstQuestion.record().recordId(), secondQuestion.record().recordId());
		assertNotEquals(firstQuestion.record().values().path("booklet_id").asText(),
				secondQuestion.record().values().path("booklet_id").asText());

		// Different cross-installation relationships are not
		// silently interpreted as the same graph.
		assertNotEquals(firstQuestion.contentSha256(), secondQuestion.contentSha256());
	}

	@Test
	void contentChangesAlterFingerprintButNotEntityIdentity() throws Exception {
		SqliteDatabase database = createDatabase("changes.db");
		seedGraph(database);
		SqlitePortableContentRepository content = new SqlitePortableContentRepository(database);
		Snapshot before = content.snapshot(EntityType.QUESTION, 1);
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					UPDATE questions
					SET question_text = 'Revised wording'
					WHERE id = 1
					""");
		}
		Snapshot after = content.snapshot(EntityType.QUESTION, 1);
		assertEquals(before.record().recordId(), after.record().recordId());
		assertNotEquals(before.contentSha256(), after.contentSha256());
		assertNotEquals(before.record().revisionId(), after.record().revisionId());
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					UPDATE questions
					SET question_text = 'Revised wording'
					WHERE id = 1
					""");
		}
		Snapshot repeated = content.snapshot(EntityType.QUESTION, 1);
		assertEquals(after.contentSha256(), repeated.contentSha256());
		assertEquals(after.record().revisionId(), repeated.record().revisionId());
	}

	@Test
	void filesystemPathChangesDoNotChangeContentFingerprint() throws Exception {
		SqliteDatabase database = createDatabase("paths.db");
		seedGraph(database);
		SqlitePortableContentRepository content = new SqlitePortableContentRepository(database);
		Snapshot sourceBefore = content.snapshot(EntityType.SOURCE_DOCUMENT, 1);
		Snapshot syllabusBefore = content.snapshot(EntityType.SYLLABUS_VERSION, 1);
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					UPDATE source_documents
					SET relative_path =
					    'subjects/Biology/new-location/questions.pdf'
					WHERE id = 1
					""");
			statement.execute("""
					UPDATE syllabus_versions
					SET source_pdf_path = 'D:/NewMachine/Biology.pdf'
					WHERE id = 1
					""");
		}
		Snapshot sourceAfter = content.snapshot(EntityType.SOURCE_DOCUMENT, 1);
		Snapshot syllabusAfter = content.snapshot(EntityType.SYLLABUS_VERSION, 1);
		assertEquals(sourceBefore.contentSha256(), sourceAfter.contentSha256());
		assertEquals(syllabusBefore.contentSha256(), syllabusAfter.contentSha256());
		assertNotEquals(sourceBefore.record().revisionId(), sourceAfter.record().revisionId());
		assertFalse(syllabusAfter.record().values().has("source_pdf_path"));
		assertFalse(sourceAfter.record().values().has("relative_path"));
	}

	@Test
	void identicalPdfHashesDoNotMergeLogicalSourceIdentities() throws Exception {
		SqliteDatabase database = createDatabase("documents.db");
		seedGraph(database);
		SqlitePortableContentRepository content = new SqlitePortableContentRepository(database);
		Snapshot questionDocument = content.snapshot(EntityType.SOURCE_DOCUMENT, 1);
		Snapshot answerDocument = content.snapshot(EntityType.SOURCE_DOCUMENT, 2);

		// Equal bytes can produce equal content fingerprints.
		assertEquals(questionDocument.contentSha256(), answerDocument.contentSha256());

		// They are still distinct logical source records.
		assertNotEquals(questionDocument.record().recordId(), answerDocument.record().recordId());
		Snapshot booklet = content.snapshot(EntityType.EXAM_BOOKLET, 1);
		Snapshot answerFile = content.snapshot(EntityType.ANSWER_FILE, 1);
		assertNotEquals(booklet.record().values().path("source_document_id").asText(),
				answerFile.record().values().path("source_document_id").asText());
	}

	@Test
	void orderedChildChangesAlterOwningFingerprint() throws Exception {
		SqliteDatabase database = createDatabase("ordering.db");
		seedGraph(database);
		SqlitePortableContentRepository content = new SqlitePortableContentRepository(database);
		Snapshot before = content.snapshot(EntityType.QUESTION, 1);
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					DELETE FROM question_content_parts
					WHERE question_id = 1
					""");
			statement.execute("""
					INSERT INTO question_content_parts (
					    question_id, content_order,
					    content_type, image_id
					)
					VALUES (1, 0, 'IMAGE', 1)
					""");
			statement.execute("""
					INSERT INTO question_content_parts (
					    question_id, content_order,
					    content_type, region_order
					)
					VALUES (1, 1, 'PDF_REGION', 0)
					""");
		}
		Snapshot reordered = content.snapshot(EntityType.QUESTION, 1);
		assertEquals(before.record().recordId(), reordered.record().recordId());
		assertNotEquals(before.contentSha256(), reordered.contentSha256());
		assertEquals("IMAGE",
				reordered.record().values().path("question_content_parts").get(0).path("content_type").asText());
	}

	private SqliteDatabase createDatabase(String filename) throws SQLException {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve(filename));
		database.initialiseSchema();
		return database;
	}

	private void seedGraph(SqliteDatabase database) throws SQLException {
		String hash = "a".repeat(64);
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
					    1, 1, NULL,
					    'U1', 'Unit 1', 'UNIT', 0
					)
					""");
			statement.execute("""
					INSERT INTO exam_providers (id, provider_name)
					VALUES (1, 'QCAA')
					""");
			try (var insert = connection.prepareStatement("""
					INSERT INTO source_documents (
					    id, relative_path, content_sha256
					)
					VALUES (?, ?, ?)
					""")) {
				insert.setLong(1, 1);
				insert.setString(2, "subjects/Biology/questions.pdf");
				insert.setString(3, hash);
				insert.executeUpdate();
				insert.setLong(1, 2);
				insert.setString(2, "subjects/Biology/answers.pdf");
				insert.setString(3, hash);
				insert.executeUpdate();
			}
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
					INSERT INTO questions (
					    id, booklet_id, classification_node_id,
					    question_code, question_text, marks,
					    shared_context_capture_required
					)
					VALUES (
					    1, 1, 1,
					    '1', 'Original wording', 1, 0
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
		}
	}
}
