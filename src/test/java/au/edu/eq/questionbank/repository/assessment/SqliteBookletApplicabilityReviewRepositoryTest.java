package au.edu.eq.questionbank.repository.assessment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class SqliteBookletApplicabilityReviewRepositoryTest {

	@TempDir
	Path tempDir;
	private SqliteDatabase database;
	private BookletApplicabilityReviewRepository repository;

	@Test
	void cannotReviewNonexistentBooklet() {
		assertThrows(IllegalStateException.class, () -> repository.setReviewed(999, true));
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
}
