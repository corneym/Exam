package au.edu.eq.questionbank.repository.assessment;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

/**
 * Constructs historical COMPLETE-state fixtures for persistence tests.
 *
 * These fixtures represent Exams completed before applicability review became a
 * requirement. This helper must not be used by production code or by tests
 * verifying current ACTIVE-to-COMPLETE eligibility.
 */
final class HistoricalCompleteExamFixture {

	private HistoricalCompleteExamFixture() {
	}

	static Exam markComplete(SqliteDatabase database, SqliteExamWriter writer, Exam exam) throws SQLException {

		// Recreate previously persisted lifecycle state without invoking
		// the modern completion gate. This is test fixture setup only.
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						UPDATE exams
						SET capture_state = 'COMPLETE'
						WHERE id = ?
						  AND subject_id = ?
						""")) {
			statement.setLong(1, exam.getId());
			statement.setLong(2, exam.getSubject().getId());
			if (statement.executeUpdate() != 1) {
				throw new IllegalStateException("Historical Exam fixture was not persisted");
			}
		}

		// Return authoritative persisted state rather than manufacturing
		// a COMPLETE Exam object that might disagree with SQLite.
		return writer.findExamsForSubject(exam.getSubject()).stream()
				.filter(candidate -> candidate.getId() == exam.getId()).findFirst().orElseThrow();
	}
}
