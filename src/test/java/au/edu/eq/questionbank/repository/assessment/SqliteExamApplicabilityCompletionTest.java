package au.edu.eq.questionbank.repository.assessment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamCaptureState;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class SqliteExamApplicabilityCompletionTest {

	@TempDir
	Path tempDir;
	private SqliteDatabase database;
	private SqliteExamWriter examWriter;
	private BookletApplicabilityReviewRepository reviewRepository;
	private Exam exam;

	@Test
	void changingToActiveNeverRequiresReviews() throws Exception {
		Exam active = examWriter.setExamCaptureState(exam, ExamCaptureState.ACTIVE);
		assertEquals(ExamCaptureState.ACTIVE, active.getCaptureState());
	}

	@Test
	void clearingReviewBlocksSubsequentCompletion() throws Exception {
		reviewRepository.setReviewed(10, true);
		reviewRepository.setReviewed(20, true);
		Exam completed = examWriter.setExamCaptureState(exam, ExamCaptureState.COMPLETE);
		reviewRepository.setReviewed(20, false);
		Exam active = examWriter.setExamCaptureState(completed, ExamCaptureState.ACTIVE);
		assertEquals(ExamCaptureState.ACTIVE, active.getCaptureState());
		assertThrows(IllegalStateException.class,
				() -> examWriter.setExamCaptureState(active, ExamCaptureState.COMPLETE));
		assertEquals("ACTIVE", persistedState());
	}

	@Test
	void directCompletionRequiresEveryBookletReview() throws Exception {
		assertThrows(IllegalStateException.class,
				() -> examWriter.setExamCaptureState(exam, ExamCaptureState.COMPLETE));
		reviewRepository.setReviewed(10, true);
		assertThrows(IllegalStateException.class,
				() -> examWriter.setExamCaptureState(exam, ExamCaptureState.COMPLETE));
		reviewRepository.setReviewed(20, true);
		Exam completed = examWriter.setExamCaptureState(exam, ExamCaptureState.COMPLETE);
		assertEquals(ExamCaptureState.COMPLETE, completed.getCaptureState());
		assertEquals("COMPLETE", persistedState());
	}

	@Test
	void failureDoesNotChangePersistedExamState() throws Exception {
		assertThrows(IllegalStateException.class,
				() -> examWriter.setExamCaptureState(exam, ExamCaptureState.COMPLETE));
		assertTrue("ACTIVE".equals(persistedState()));
	}

	@BeforeEach
	void initialise() throws Exception {
		database = new SqliteDatabase(tempDir.resolve("completion.db"));
		database.initialiseSchema();
		examWriter = new SqliteExamWriter(database);
		reviewRepository = new SqliteBookletApplicabilityReviewRepository(database);
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
					    (2, 'paper2.pdf')
					""");
			statement.execute("""
					INSERT INTO exams
					    (id, subject_id, provider_id, exam_year,
					     exam_name, expected_question_booklet_count)
					VALUES
					    (1, 1, 1, 2025, 'External Assessment', 2)
					""");
			statement.execute("""
					INSERT INTO exam_booklets
					    (id, exam_id, source_document_id, booklet_name)
					VALUES
					    (10, 1, 1, 'Paper 1'),
					    (20, 1, 2, 'Paper 2')
					""");
		}
		Subject subject = new Subject(1, "Chemistry");
		exam = examWriter.findExamsForSubject(subject).getFirst();
	}

	@Test
	void missingExpectedBookletCountBlocksCompletion() throws Exception {
		reviewRepository.setReviewed(10, true);
		reviewRepository.setReviewed(20, true);
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.executeUpdate("""
					UPDATE exams
					SET expected_question_booklet_count = NULL
					WHERE id = 1
					""");
		}
		assertThrows(IllegalStateException.class,
				() -> examWriter.setExamCaptureState(exam, ExamCaptureState.COMPLETE));
	}

	private String persistedState() throws Exception {
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT capture_state
						FROM exams
						WHERE id = 1
						""")) {
			if (!result.next()) {
				throw new IllegalStateException("Missing Exam fixture");
			}
			return result.getString("capture_state");
		}
	}
}
