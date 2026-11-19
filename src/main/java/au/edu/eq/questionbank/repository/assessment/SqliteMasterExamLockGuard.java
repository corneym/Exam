package au.edu.eq.questionbank.repository.assessment;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

/**
 * Rejects ordinary coordinator mutations to Exams with active offline
 * assignments.
 *
 * <p>
 * The authoritative assignment claim is stored in SQLite. Callers that modify
 * managed files must check this guard before changing bytes, preferably while
 * holding the managed-asset publication lock.
 *
 * <p>
 * This guard does not authorise contribution application or replace the
 * database-level mutation triggers.
 */
public final class SqliteMasterExamLockGuard {

	private final SqliteDatabase database;

	/**
	 * Creates a master Exam lock guard.
	 *
	 * @param database authoritative coordinator database
	 * @throws NullPointerException if database is null
	 */
	public SqliteMasterExamLockGuard(SqliteDatabase database) {
		this.database = Objects.requireNonNull(database, "database");
	}

	/**
	 * Checks the assignment lock using a caller-owned connection.
	 *
	 * <p>
	 * This overload permits structural writers to check the assignment while
	 * participating in their existing transaction.
	 *
	 * @param connection caller-owned database connection
	 * @param examId     local Exam identifier
	 * @throws SQLException             if the ledger cannot be read
	 * @throws IllegalArgumentException if examId is not positive
	 * @throws IllegalStateException    if an active assignment owns the Exam
	 * @throws NullPointerException     if connection is null
	 */
	public void requireUnlocked(Connection connection, long examId) throws SQLException {
		Objects.requireNonNull(connection, "connection");
		if (examId < 1) {
			throw new IllegalArgumentException("Exam ID must be positive");
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT locks.assignment_id
				FROM exam_work_assignment_exam_locks locks
				JOIN portable_entity_identities identities
				  ON identities.portable_id = locks.exam_portable_id
				 AND identities.entity_type = 'EXAM'
				WHERE identities.local_id = ?
				""")) {
			statement.setLong(1, examId);
			try (ResultSet result = statement.executeQuery()) {
				if (result.next()) {
					throw new IllegalStateException("Exam is locked by active work assignment "
							+ result.getString("assignment_id") + ". Reconcile or close that assignment first.");
				}
			}
		}
	}

	/**
	 * Requires an Exam to have no active coordinator assignment.
	 *
	 * @param examId local Exam identifier
	 * @throws SQLException             if the assignment ledger cannot be read
	 * @throws IllegalArgumentException if examId is not positive
	 * @throws IllegalStateException    if the Exam is assigned
	 */
	public void requireUnlocked(long examId) throws SQLException {
		try (Connection connection = database.openConnection()) {
			requireUnlocked(connection, examId);
		}
	}
}
