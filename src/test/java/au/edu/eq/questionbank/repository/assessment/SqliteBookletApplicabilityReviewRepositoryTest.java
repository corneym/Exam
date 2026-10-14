package au.edu.eq.questionbank.repository.assessment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class SqliteBookletApplicabilityReviewRepositoryTest {

	// Test fixtures and collaborators.
	private SqliteDatabase database;
	private BookletApplicabilityReviewRepository repository;
	@TempDir
	Path tempDir;

	@Test
	void cannotReviewNonexistentBooklet() {
		assertThrows(IllegalStateException.class, () -> repository.setReviewed(999, true));
	}

	@Test
	void curriculumMappingChangeInvalidatesSubjectReviews() throws Exception {
		insertQuestionFixture();
		repository.setReviewed(10, true);
		repository.setReviewed(20, true);
		repository.setReviewed(30, true);
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.executeUpdate("""
					INSERT INTO curriculum_mappings
					    (source_node_id, target_node_id, mapping_status)
					VALUES (100, 101, 'CONFIRMED')
					""");
		}

		// All three booklets belong to the Chemistry Subject,
		// including the booklet belonging to the second Exam.
		assertFalse(repository.isReviewed(10));
		assertFalse(repository.isReviewed(20));
		assertFalse(repository.isReviewed(30));
	}

	@Test
	void deletingBookletCascadesToItsReview() throws Exception {
		repository.setReviewed(10, true);
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.executeUpdate("""
					DELETE FROM exam_booklets
					WHERE id = 10
					""");
		}
		assertFalse(repository.isReviewed(10));
		assertEquals(Set.of(), repository.findReviewedBookletIds(1));
	}

	@Test
	void editingBookletMetadataInvalidatesOnlyItsReview() throws Exception {
		repository.setReviewed(10, true);
		repository.setReviewed(20, true);
		repository.setReviewed(30, true);
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.executeUpdate("""
					UPDATE exam_booklets
					SET expected_question_count = 2
					WHERE id = 10
					""");
		}
		assertFalse(repository.isReviewed(10));
		assertTrue(repository.isReviewed(20));
		assertTrue(repository.isReviewed(30));
	}

	@Test
	void examQueryDoesNotIncludeOtherExamsBooklets() {
		repository.setReviewed(10, true);
		repository.setReviewed(20, true);
		repository.setReviewed(30, true);
		assertEquals(Set.of(10L, 20L), repository.findReviewedBookletIds(1));
		assertEquals(Set.of(30L), repository.findReviewedBookletIds(2));
	}

	@BeforeEach
	void initialise() throws Exception {
		database = new SqliteDatabase(tempDir.resolve("reviews.db"));
		database.initialiseSchema();
		repository = new SqliteBookletApplicabilityReviewRepository(database);
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					INSERT INTO subjects (id, subject_name)
					VALUES (1, 'Chemistry')
					""");
			statement.execute("""
					INSERT INTO exam_providers (id, provider_name)
					VALUES (1, 'QCAA')
					""");
			statement.execute("""
					INSERT INTO source_documents (id, relative_path)
					VALUES
					    (1, 'paper1.pdf'),
					    (2, 'paper2.pdf'),
					    (3, 'paper3.pdf')
					""");
			statement.execute("""
					INSERT INTO exams
					    (id, subject_id, provider_id, exam_year, exam_name)
					VALUES
					    (1, 1, 1, 2025, 'External Assessment'),
					    (2, 1, 1, 2024, 'External Assessment')
					""");
			statement.execute("""
					INSERT INTO exam_booklets
					    (id, exam_id, source_document_id, booklet_name)
					VALUES
					    (10, 1, 1, 'Paper 1'),
					    (20, 1, 2, 'Paper 2'),
					    (30, 2, 3, 'Paper 1')
					""");
		}
	}

	@Test
	void missingInvalidationTriggerFailsSchemaVerification() throws Exception {
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					DROP TRIGGER app_review_questions_update
					""");
		}
		assertThrows(SQLException.class, database::verifySchema);
	}

	@Test
	void questionEditsAndOutputDecisionsInvalidateReview() throws Exception {
		insertQuestionFixture();
		repository.setReviewed(10, true);
		repository.setReviewed(20, true);
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.executeUpdate("""
					UPDATE questions
					SET question_text = 'Revised wording'
					WHERE id = 300
					""");
		}
		assertFalse(repository.isReviewed(10));
		assertTrue(repository.isReviewed(20));
		repository.setReviewed(10, true);
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.executeUpdate("""
					INSERT INTO question_output_exclusions
					    (question_id, current_curriculum_node_id)
					VALUES (300, 101)
					""");
		}
		assertFalse(repository.isReviewed(10));
		repository.setReviewed(10, true);
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.executeUpdate("""
					DELETE FROM question_output_exclusions
					WHERE question_id = 300
					  AND current_curriculum_node_id = 101
					""");
		}
		assertFalse(repository.isReviewed(10));
	}

	@Test
	void readingQuestionsDoesNotInvalidateReview() throws Exception {
		insertQuestionFixture();
		repository.setReviewed(10, true);
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT id, question_text
						FROM questions
						WHERE booklet_id = 10
						""")) {
			assertTrue(result.next());
			assertEquals(300, result.getLong("id"));
		}

		// Merely viewing or searching a Question is not a material edit.
		assertTrue(repository.isReviewed(10));
	}

	@Test
	void rejectsInvalidIdentifiers() {
		assertThrows(IllegalArgumentException.class, () -> repository.isReviewed(0));
		assertThrows(IllegalArgumentException.class, () -> repository.setReviewed(-1, true));
		assertThrows(IllegalArgumentException.class, () -> repository.findReviewedBookletIds(0));
	}

	@Test
	void repeatedConfirmationAndClearingAreIdempotent() {
		repository.setReviewed(10, true);
		repository.setReviewed(10, true);
		assertEquals(Set.of(10L), repository.findReviewedBookletIds(1));
		repository.setReviewed(10, false);
		repository.setReviewed(10, false);
		assertFalse(repository.isReviewed(10));
		assertEquals(Set.of(), repository.findReviewedBookletIds(1));
	}

	@Test
	void reviewsStartUncheckedAndArePersisted() {
		assertFalse(repository.isReviewed(10));
		assertEquals(Set.of(), repository.findReviewedBookletIds(1));
		repository.setReviewed(10, true);
		assertTrue(repository.isReviewed(10));
		assertEquals(Set.of(10L), repository.findReviewedBookletIds(1));

		// Reconstruct the repository to demonstrate persistence
		// independently of any in-memory object state.
		BookletApplicabilityReviewRepository reopened = new SqliteBookletApplicabilityReviewRepository(database);
		assertTrue(reopened.isReviewed(10));
	}

	@Test
	void sourceDocumentReplacementInvalidatesAffectedBooklet() throws Exception {
		repository.setReviewed(10, true);
		repository.setReviewed(20, true);
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.executeUpdate("""
					UPDATE source_documents
					SET relative_path = 'paper1-replaced.pdf'
					WHERE id = 1
					""");
		}
		assertFalse(repository.isReviewed(10));
		assertTrue(repository.isReviewed(20));
	}

	@Test
	void subjectQueryReturnsReviewsAcrossAllItsExams() {
		assertEquals(Set.of(), repository.findReviewedBookletIdsForSubject(1));
		repository.setReviewed(10, true);
		repository.setReviewed(20, true);
		repository.setReviewed(30, true);

		// All three fixture booklets belong to Chemistry, despite
		// the second Exam having a different year.
		assertEquals(Set.of(10L, 20L, 30L), repository.findReviewedBookletIdsForSubject(1));
		repository.setReviewed(20, false);
		assertEquals(Set.of(10L, 30L), repository.findReviewedBookletIdsForSubject(1));
	}

	private void insertQuestionFixture() throws Exception {
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					INSERT INTO syllabus_versions
					    (id, subject_id, syllabus_name, is_current)
					VALUES (10, 1, '2025', 1)
					""");
			statement.execute("""
					INSERT INTO curriculum_nodes
					    (id, syllabus_version_id, parent_id,
					     curriculum_code, curriculum_name,
					     curriculum_level, display_order)
					VALUES
					    (100, 10, NULL, '1.1.1',
					     'Descriptor 1', 'DESCRIPTOR', 0),
					    (101, 10, NULL, '1.1.2',
					     'Descriptor 2', 'DESCRIPTOR', 1)
					""");
			statement.execute("""
					INSERT INTO questions
					    (id, booklet_id, classification_node_id,
					     question_code, question_text, marks,
					     shared_context_capture_required)
					VALUES
					    (300, 10, 100, '1',
					     'Original question', 1, 0)
					""");
		}
	}
}
