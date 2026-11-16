package au.edu.eq.questionbank.repository.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteAssignmentScopeTriggersTest {

	@TempDir
	Path directory;

	@Test
	void explicitlyPermittedLegacySyllabusCanClassify() throws Exception {
		SqliteDatabase database = fixture(true);
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					UPDATE questions
					SET classification_node_id = 102
					WHERE id = 1
					""");
			assertBlocked(statement, """
					UPDATE questions
					SET classification_node_id = 201
					WHERE id = 1
					""");
		}
		database.verifyIntegrity();
	}

	@Test
	void migratesVersion30ToVersion31() throws Exception {
		SqliteDatabase database = fixture(false);
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try (Statement statement = connection.createStatement()) {
				HistoricalSchemaFixture.removeAssignmentScopeObjects(statement);
				statement.execute("""
						UPDATE schema_version
						SET version = 30
						WHERE version = 31
						""");
				connection.commit();
			} catch (SQLException failure) {
				connection.rollback();
				throw failure;
			}
		}
		assertEquals(30, database.schemaVersion());
		database.verifySchema();
		database.initialiseSchema();
		assertEquals(31, database.schemaVersion());
		database.verifySchema();
		try (Connection connection = database.openConnection()) {
			SqliteAssignmentStructureTriggers.verify(connection);
			SqliteAssignmentScopeTriggers.verify(connection);
		}
		database.verifyIntegrity();
	}

	@Test
	void missingScopeTriggerFailsSchemaVerification() throws Exception {
		SqliteDatabase database = fixture(false);
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					DROP TRIGGER
					trg_contributor_scope_questions_update
					""");
		}
		SQLException failure = assertThrows(SQLException.class, database::verifySchema);
		assertTrue(failure.getMessage().contains("Missing contributor scope trigger"));
	}

	@Test
	void ordinaryModeDoesNotRestrictExistingQuestionUpdates() throws Exception {
		SqliteDatabase database = fixture(false);
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					UPDATE contributor_assignment_selection
					SET assignment_id = NULL
					WHERE singleton_id = 1
					""");
			statement.execute("""
					UPDATE questions
					SET question_text = 'Normal mode edit'
					WHERE id = 2
					""");
		}
		database.verifyIntegrity();
	}

	@Test
	void permitsCaptureClassificationAndReviewsInAssignedExam() throws Exception {
		SqliteDatabase database = fixture(false);
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					INSERT INTO source_questions (
					    id, booklet_id, source_question_code
					)
					VALUES (403, 61, '2')
					""");
			statement.execute("""
					INSERT INTO shared_question_contexts (
					    id, booklet_id, context_label
					)
					VALUES (303, 61, 'New assigned context')
					""");
			statement.execute("""
					INSERT INTO questions (
					    id, booklet_id, classification_node_id,
					    question_code, question_text, marks,
					    shared_context_capture_required,
					    source_question_id, shared_context_id
					)
					VALUES (
					    4, 61, 101, '2', 'New Question', 3, 0,
					    403, 303
					)
					""");
			statement.execute("""
					INSERT INTO question_regions (
					    question_id, region_order, booklet_id,
					    page_number, x, y, width, height
					)
					VALUES (4, 0, 61, 1, 0.1, 0.1, 0.4, 0.4)
					""");
			statement.execute("""
					INSERT INTO question_images (
					    id, question_id, image_png
					)
					VALUES (901, 4, X'01')
					""");
			statement.execute("""
					INSERT INTO question_content_parts (
					    question_id, content_order,
					    content_type, region_order
					)
					VALUES (4, 0, 'PDF_REGION', 0)
					""");
			statement.execute("""
					INSERT INTO question_content_parts (
					    question_id, content_order,
					    content_type, image_id
					)
					VALUES (4, 1, 'IMAGE', 901)
					""");
			statement.execute("""
					INSERT INTO shared_question_context_regions (
					    shared_context_id, region_order,
					    page_number, x, y, width, height
					)
					VALUES (303, 0, 1, 0.1, 0.1, 0.4, 0.4)
					""");
			statement.execute("""
					INSERT INTO shared_question_context_content_parts (
					    shared_context_id, content_order,
					    content_type, region_order
					)
					VALUES (303, 0, 'PDF_REGION', 0)
					""");
			statement.execute("""
					INSERT INTO answers (
					    id, question_id, answer_text
					)
					VALUES (503, 4, 'New answer')
					""");
			statement.execute("""
					INSERT INTO answer_regions (
					    answer_id, region_order, answer_file_id,
					    page_number, x, y, width, height
					)
					VALUES (503, 0, 81, 1, 0.1, 0.1, 0.4, 0.4)
					""");
			statement.execute("""
					INSERT INTO question_output_exclusions (
					    question_id, current_curriculum_node_id
					)
					VALUES (4, 101)
					""");
			statement.execute("""
					UPDATE questions
					SET question_text = 'Reclassified work'
					WHERE id = 1
					""");

			// The review is recorded after all content edits.
			statement.execute("""
					INSERT INTO booklet_applicability_reviews (
					    booklet_id
					)
					VALUES (61)
					""");
		}
		database.verifyIntegrity();
	}

	@Test
	void rejectsOtherExamCaptureAndReassignment() throws Exception {
		SqliteDatabase database = fixture(false);
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			assertBlocked(statement, """
					INSERT INTO questions (
					    id, booklet_id, classification_node_id,
					    question_code, question_text, marks,
					    shared_context_capture_required
					)
					VALUES (9, 62, 101, '9', 'Outside', 2, 0)
					""");
			assertBlocked(statement, """
					UPDATE questions
					SET question_text = 'Illegal change'
					WHERE id = 2
					""");
			assertBlocked(statement, """
					UPDATE questions
					SET booklet_id = 62,
					    question_code = '99'
					WHERE id = 1
					""");
			assertBlocked(statement, """
					DELETE FROM questions
					WHERE id = 2
					""");
			assertBlocked(statement, """
					UPDATE answers
					SET answer_text = 'Outside Exam'
					WHERE id = 502
					""");
			assertBlocked(statement, """
					UPDATE answers
					SET question_id = 3
					WHERE id = 501
					""");
			assertBlocked(statement, """
					INSERT INTO question_regions (
					    question_id, region_order, booklet_id,
					    page_number, x, y, width, height
					)
					VALUES (2, 0, 62, 1, 0.1, 0.1, 0.3, 0.3)
					""");
			assertBlocked(statement, """
					INSERT INTO shared_question_context_images (
					    shared_context_id, image_order, image_png
					)
					VALUES (302, 0, X'01')
					""");
			assertBlocked(statement, """
					INSERT INTO booklet_applicability_reviews (
					    booklet_id
					)
					VALUES (62)
					""");
		}
		database.verifyIntegrity();
	}

	@Test
	void rejectsUnassignedSyllabusesAndCrossBookletLinks() throws Exception {
		SqliteDatabase database = fixture(false);
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			assertBlocked(statement, """
					UPDATE questions
					SET classification_node_id = 102
					WHERE id = 1
					""");
			assertBlocked(statement, """
					UPDATE questions
					SET classification_node_id = 201
					WHERE id = 1
					""");
			assertBlocked(statement, """
					UPDATE questions
					SET source_question_id = 402
					WHERE id = 1
					""");
			assertBlocked(statement, """
					UPDATE questions
					SET shared_context_id = 302
					WHERE id = 1
					""");
			assertBlocked(statement, """
					INSERT INTO question_output_exclusions (
					    question_id, current_curriculum_node_id
					)
					VALUES (1, 201)
					""");
			assertBlocked(statement, """
					INSERT INTO answer_regions (
					    answer_id, region_order, answer_file_id,
					    page_number, x, y, width, height
					)
					VALUES (501, 0, 82, 1, 0.1, 0.1, 0.3, 0.3)
					""");
		}
		database.verifyIntegrity();
	}

	private void assertBlocked(Statement statement, String sql) {
		SQLException failure = assertThrows(SQLException.class, () -> statement.execute(sql));
		assertTrue(failure.getMessage().contains("Capture or classification is outside selected assignment"),
				failure.getMessage());
	}

	private SqliteDatabase fixture(boolean legacy) throws Exception {
		return SqliteAssignmentGuardFixture.create(directory.resolve("scope.db"), legacy);
	}
}
