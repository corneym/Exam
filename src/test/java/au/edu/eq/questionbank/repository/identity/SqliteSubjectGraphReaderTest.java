package au.edu.eq.questionbank.repository.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
import au.edu.eq.questionbank.repository.identity.SqliteSubjectGraphReader.SubjectGraph;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

public class SqliteSubjectGraphReaderTest {

	@TempDir
	Path directory;

	@Test
	void detachedSnapshotDoesNotObserveLaterLiveChanges() throws Exception {
		SqliteDatabase database = createDatabase("live-changes.db");
		seedPopulatedSubjects(database);
		SqliteSubjectGraphReader originalReader = snapshotReader(database, "before-changes.db");
		assertEquals(List.of(31L), originalReader.read(1).ids(EntityType.EXAM));
		execute(database, """
				INSERT INTO exams (
				    id, subject_id, provider_id,
				    exam_year, exam_name
				)
				VALUES (
				    32, 1, 1, 2026, 'Additional Examination'
				)
				""");

		// A detached SQLite snapshot must not observe
		// subsequently committed writes to the live corpus.
		assertEquals(List.of(31L), originalReader.read(1).ids(EntityType.EXAM));
		SqliteSubjectGraphReader updatedReader = snapshotReader(database, "after-changes.db");
		assertEquals(List.of(31L, 32L), updatedReader.read(1).ids(EntityType.EXAM));
	}

	@Test
	void discoversCompleteSubjectEntitySetWithoutOtherSubjects() throws Exception {
		SqliteDatabase database = createDatabase("populated.db");
		seedPopulatedSubjects(database);
		SqliteSubjectGraphReader reader = snapshotReader(database, "populated-snapshot.db");
		SubjectGraph biology = reader.read(1);
		assertEquals(1L, biology.subjectId());
		assertEquals("Biology", biology.subjectName());
		Map<EntityType, List<Long>> expected = Map.ofEntries(Map.entry(EntityType.ANSWER, List.of(141L)),
				Map.entry(EntityType.ANSWER_FILE, List.of(71L)),
				Map.entry(EntityType.CURRICULUM_MAPPING, List.of(111L)),
				Map.entry(EntityType.CURRICULUM_NODE, List.of(101L, 102L)), Map.entry(EntityType.EXAM, List.of(31L)),
				Map.entry(EntityType.EXAM_BOOKLET, List.of(61L, 62L)), Map.entry(EntityType.EXAM_PROVIDER, List.of(1L)),
				Map.entry(EntityType.QUESTION, List.of(81L)), Map.entry(EntityType.QUESTION_IMAGE, List.of(131L)),
				Map.entry(EntityType.SHARED_QUESTION_CONTEXT, List.of(121L)),
				Map.entry(EntityType.SOURCE_DOCUMENT, List.of(51L, 52L)),
				Map.entry(EntityType.SOURCE_QUESTION, List.of(91L)), Map.entry(EntityType.SUBJECT, List.of(1L)),
				Map.entry(EntityType.SYLLABUS_VERSION, List.of(11L, 12L)));
		for (EntityType type : EntityType.values()) {
			assertEquals(expected.get(type), biology.ids(type), "Incorrect Subject graph selection for " + type);
		}
		SubjectGraph chemistry = reader.read(2);
		assertEquals("Chemistry", chemistry.subjectName());
		assertEquals(List.of(41L), chemistry.ids(EntityType.EXAM));
		assertEquals(List.of(63L), chemistry.ids(EntityType.EXAM_BOOKLET));
		assertEquals(List.of(53L), chemistry.ids(EntityType.SOURCE_DOCUMENT));
		assertEquals(List.of(82L), chemistry.ids(EntityType.QUESTION));
		assertTrue(chemistry.ids(EntityType.ANSWER_FILE).isEmpty());
		assertTrue(chemistry.ids(EntityType.CURRICULUM_MAPPING).isEmpty());
	}

	@Test
	void emptySubjectContainsOnlyItsSubjectRecord() throws Exception {
		SqliteDatabase database = createDatabase("empty.db");
		execute(database, """
				INSERT INTO subjects (id, subject_name)
				VALUES (1, 'Biology')
				""");
		SqliteSubjectGraphReader reader = snapshotReader(database, "empty-snapshot.db");
		SubjectGraph graph = reader.read(1);
		assertEquals("Biology", graph.subjectName());
		for (EntityType type : EntityType.values()) {
			List<Long> expected = type == EntityType.SUBJECT ? List.of(1L) : List.of();
			assertEquals(expected, graph.ids(type), "Unexpected records for " + type);
		}
		assertThrows(SQLException.class, () -> reader.read(999));
	}

	@Test
	void rejectsCrossSubjectCurriculumMapping() throws Exception {
		SqliteDatabase database = createDatabase("cross-mapping.db");
		seedPopulatedSubjects(database);
		execute(database, """
				INSERT INTO curriculum_mappings (
				    id, source_node_id,
				    target_node_id, mapping_status
				)
				VALUES (
				    112, 101, 201, 'SUGGESTED'
				)
				""");
		SqliteSubjectGraphReader reader = snapshotReader(database, "cross-mapping-snapshot.db");
		SQLException failure = assertThrows(SQLException.class, () -> reader.read(1));
		assertTrue(failure.getMessage().contains("cross-Subject curriculum mapping"));
	}

	@Test
	void rejectsCrossSubjectCurriculumReview() throws Exception {
		SqliteDatabase database = createDatabase("cross-review.db");
		seedPopulatedSubjects(database);
		execute(database, """
				INSERT INTO curriculum_mapping_reviews (
				    source_node_id,
				    target_syllabus_version_id,
				    review_outcome
				)
				VALUES (
				    101, 21, 'NO_MATCH'
				)
				""");
		SqliteSubjectGraphReader reader = snapshotReader(database, "cross-review-snapshot.db");
		SQLException failure = assertThrows(SQLException.class, () -> reader.read(1));
		assertTrue(failure.getMessage().contains("cross-Subject curriculum mapping review"));
	}

	private SqliteDatabase createDatabase(String filename) throws SQLException {
		SqliteDatabase database = new SqliteDatabase(directory.resolve(filename));
		database.initialiseSchema();
		return database;
	}

	private void execute(SqliteDatabase database, String... sqlStatements) throws SQLException {
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			for (String sql : sqlStatements) {
				statement.execute(sql);
			}
		}
	}

	private void seedPopulatedSubjects(SqliteDatabase database) throws SQLException {
		execute(database, """
				INSERT INTO subjects (id, subject_name)
				VALUES
				    (1, 'Biology'),
				    (2, 'Chemistry')
				""", """
				INSERT INTO syllabus_versions (
				    id, subject_id, syllabus_name, is_current
				)
				VALUES
				    (11, 1, 'Biology 2019', 0),
				    (12, 1, 'Biology 2025', 1),
				    (21, 2, 'Chemistry 2025', 1)
				""", """
				INSERT INTO curriculum_nodes (
				    id, syllabus_version_id, parent_id,
				    curriculum_code, curriculum_name,
				    curriculum_level, display_order
				)
				VALUES
				    (101, 11, NULL,
				     'BIO19-U1', 'Biology 2019 Unit 1', 'UNIT', 0),

				    (102, 12, NULL,
				     'BIO25-U1', 'Biology 2025 Unit 1', 'UNIT', 0),

				    (201, 21, NULL,
				     'CHEM25-U1', 'Chemistry Unit 1', 'UNIT', 0)
				""", """
				INSERT INTO curriculum_mappings (
				    id, source_node_id,
				    target_node_id, mapping_status
				)
				VALUES
				    (111, 101, 102, 'CONFIRMED')
				""", """
				INSERT INTO curriculum_mapping_reviews (
				    source_node_id,
				    target_syllabus_version_id,
				    review_outcome
				)
				VALUES
				    (101, 12, 'MATCHED')
				""", """
				INSERT INTO exam_providers (
				    id, provider_name
				)
				VALUES
				    (1, 'QCAA'),
				    (2, 'NEAP')
				""", """
				INSERT INTO source_documents (
				    id, relative_path, content_sha256
				)
				VALUES
				    (51, 'subjects/Biology/exams/questions.pdf', NULL),
				    (52, 'subjects/Biology/exams/answers.pdf', NULL),
				    (53, 'subjects/Chemistry/exams/questions.pdf', NULL)
				""", """
				INSERT INTO exams (
				    id, subject_id, provider_id,
				    exam_year, exam_name
				)
				VALUES
				    (31, 1, 1, 2025, 'External Assessment'),
				    (41, 2, 2, 2025, 'Chemistry Examination')
				""", """
				INSERT INTO exam_booklets (
				    id, exam_id, source_document_id, booklet_name
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
				VALUES
				    (71, 31, 52, 'Marking Guide')
				""", """
				UPDATE exam_booklets
				SET answer_file_id = 71
				WHERE id IN (61, 62)
				""", """
				INSERT INTO source_questions (
				    id, booklet_id, source_question_code
				)
				VALUES
				    (91, 61, '1')
				""", """
				INSERT INTO shared_question_contexts (
				    id, booklet_id, context_label
				)
				VALUES
				    (121, 61, 'Shared stimulus')
				""", """
				INSERT INTO questions (
				    id, booklet_id, classification_node_id,
				    question_code, question_text, marks,
				    shared_context_capture_required,
				    source_question_id, shared_context_id
				)
				VALUES
				    (81, 61, 102, '1',
				     'Biology question', 2, 0, 91, 121),

				    (82, 63, 201, '1',
				     'Chemistry question', 1, 0, NULL, NULL)
				""", """
				INSERT INTO question_images (
				    id, question_id, image_png
				)
				VALUES
				    (131, 81, X'89504E47010203')
				""", """
				INSERT INTO answers (
				    id, question_id, answer_text
				)
				VALUES
				    (141, 81, 'Biology answer')
				""");
	}

	private SqliteSubjectGraphReader snapshotReader(SqliteDatabase database, String snapshotFilename) throws Exception {
		Path snapshotPath = directory.resolve(snapshotFilename);
		database.createConsistentSnapshot(snapshotPath);
		return new SqliteSubjectGraphReader(snapshotPath);
	}
}
