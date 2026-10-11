package au.edu.eq.questionbank.repository.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BookletApplicabilityReviewSchemaTest {

	@TempDir
	Path tempDir;

	@Test
	void newDatabaseStartsWithNoImplicitBookletReviews() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("reviews.db"));
		database.initialiseSchema();

		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {

			// A confirmation must be a separately persisted human decision.
			// Existing Question and Exam metadata must not imply a review.
			try (ResultSet result = statement.executeQuery("""
					SELECT COUNT(*)
					FROM booklet_applicability_reviews
					""")) {
				assertTrue(result.next());
				assertEquals(0, result.getInt(1));
			}
		}
	}

	@Test
	void reviewRecordIsUniqueAndBelongsToAnExistingBooklet() throws Exception {

		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("reviews.db"));
		database.initialiseSchema();

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
					VALUES (1, 'paper.pdf')
					""");
			statement.execute("""
					INSERT INTO exams
					    (id, subject_id, provider_id, exam_year, exam_name)
					VALUES
					    (1, 1, 1, 2025, 'External Assessment')
					""");
			statement.execute("""
					INSERT INTO exam_booklets
					    (id, exam_id, source_document_id, booklet_name)
					VALUES
					    (1, 1, 1, 'Paper 1')
					""");

			// A row represents an explicit positive confirmation.
			statement.execute("""
					INSERT INTO booklet_applicability_reviews (booklet_id)
					VALUES (1)
					""");

			assertThrows(SQLException.class, () -> statement.execute("""
					INSERT INTO booklet_applicability_reviews (booklet_id)
					VALUES (1)
					"""));

			assertThrows(SQLException.class, () -> statement.execute("""
					INSERT INTO booklet_applicability_reviews (booklet_id)
					VALUES (999)
					"""));

			// Clearing a review does not delete the underlying booklet.
			assertEquals(1, statement.executeUpdate("""
					DELETE FROM booklet_applicability_reviews
					WHERE booklet_id = 1
					"""));

			try (ResultSet result = statement.executeQuery("""
					SELECT COUNT(*)
					FROM booklet_applicability_reviews
					""")) {
				assertTrue(result.next());
				assertEquals(0, result.getInt(1));
			}

			try (ResultSet result = statement.executeQuery("""
					SELECT id
					FROM exam_booklets
					WHERE id = 1
					""")) {
				assertTrue(result.next());
				assertEquals(1, result.getLong(1));
				assertFalse(result.next());
			}
		}
	}
}
