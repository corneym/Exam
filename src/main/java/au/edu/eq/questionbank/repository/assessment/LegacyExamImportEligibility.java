package au.edu.eq.questionbank.repository.assessment;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

/**
 * Prevents legacy metadata from overwriting an Exam that has progressed to
 * Question capture, descriptor classification or explicit human review.
 * Unchanged legacy-metadata-only ACTIVE Exams remain eligible.
 */
public final class LegacyExamImportEligibility {

	private final SqliteDatabase database;

	/**
	 * Creates an eligibility reader for the authoritative database.
	 *
	 * @param database initialised SQLite database used to inspect persisted Exams
	 * @throws NullPointerException if {@code database} is null
	 */
	public LegacyExamImportEligibility(SqliteDatabase database) {
		this.database = Objects.requireNonNull(database, "database");
	}

	/**
	 * Evaluates the same rule within the caller's database transaction.
	 *
	 * @param connection existing connection
	 * @param examId     persisted Exam ID
	 * @return rejection reason, or null
	 * @throws SQLException if persistence cannot be inspected
	 */
	public static String rejectionReason(Connection connection, long examId) throws SQLException {
		Objects.requireNonNull(connection, "connection");
		if (examId < 1) {
			throw new IllegalArgumentException("examId must be positive");
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT capture_state FROM exams WHERE id = ?
				""")) {
			statement.setLong(1, examId);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new IllegalArgumentException("Exam does not exist: " + examId);
				}
				if ("COMPLETE".equals(result.getString("capture_state"))) {
					return "This Exam is COMPLETE. Legacy metadata must not be reimported into a completed Exam.";
				}
			}
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT
				    EXISTS (
				        SELECT 1 FROM booklet_applicability_reviews review
				        JOIN exam_booklets booklet ON booklet.id = review.booklet_id
				        WHERE booklet.exam_id = ?
				    ) AS reviewed,
				    EXISTS (
				        SELECT 1 FROM questions question
				        JOIN exam_booklets booklet ON booklet.id = question.booklet_id
				        JOIN curriculum_nodes classification
				          ON classification.id = question.classification_node_id
				        WHERE booklet.exam_id = ?
				          AND (
				              classification.curriculum_level = 'DESCRIPTOR'
				              OR TRIM(COALESCE(question.question_text, '')) <> ''
				              OR EXISTS (SELECT 1 FROM question_regions region
				                         WHERE region.question_id = question.id)
				              OR EXISTS (SELECT 1 FROM question_images image
				                         WHERE image.question_id = question.id)
				              OR EXISTS (SELECT 1 FROM question_content_parts part
				                         WHERE part.question_id = question.id)
				              OR EXISTS (SELECT 1 FROM question_output_exclusions exclusion
				                         WHERE exclusion.question_id = question.id)
				              OR EXISTS (
				                  SELECT 1 FROM answers answer
				                  JOIN answer_regions region ON region.answer_id = answer.id
				                  WHERE answer.question_id = question.id
				              )
				          )
				    ) AS developed,
				    EXISTS (
				        SELECT 1 FROM shared_question_contexts context
				        JOIN shared_question_context_regions region
				          ON region.shared_context_id = context.id
				        JOIN exam_booklets booklet ON booklet.id = context.booklet_id
				        WHERE booklet.exam_id = ?
				    ) AS shared_capture
				""")) {
			statement.setLong(1, examId);
			statement.setLong(2, examId);
			statement.setLong(3, examId);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new SQLException("Legacy import eligibility returned no result");
				}
				if (result.getBoolean("reviewed")) {
					return "This Exam has recorded booklet applicability reviews. "
							+ "Legacy metadata must not revisit already reviewed Questions.";
				}
				if (result.getBoolean("developed") || result.getBoolean("shared_capture")) {
					return "This Exam has captured Question content, descriptor-level "
							+ "classification or revision applicability work. "
							+ "Legacy metadata import is no longer appropriate.";
				}
			}
		}
		return null;
	}

	/**
	 * Rejects a developed target inside the caller's write transaction.
	 *
	 * @param connection caller-owned transaction
	 * @param examId     persisted Exam ID
	 * @throws SQLException          if inspection fails
	 * @throws IllegalStateException if the Exam cannot receive legacy metadata
	 */
	public static void requireEligible(Connection connection, long examId) throws SQLException {
		String reason = rejectionReason(connection, examId);
		if (reason != null) {
			throw new IllegalStateException("Legacy Question Import blocked for Exam ID " + examId + ": " + reason);
		}
	}

	/**
	 * Finds an import rejection reason for a persisted Exam.
	 *
	 * @param exam persisted Exam
	 * @return explanatory reason, or null when eligible
	 * @throws SQLException if the lookup fails
	 */
	public String rejectionReason(Exam exam) throws SQLException {
		Objects.requireNonNull(exam, "exam");
		try (Connection connection = database.openConnection()) {
			return rejectionReason(connection, exam.getId());
		}
	}
}
