package au.edu.eq.questionbank.repository.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads active coordinator Exam locks and their contributor details.
 *
 * <p>
 * Only the authoritative assignment lock table determines whether an Exam is
 * assigned. Assignment history and completed or cancelled packages never make
 * an Exam read-only by themselves.
 */
public final class SqliteCoordinatorExamLockRepository {

	private final SqliteDatabase database;

	/**
	 * Creates a coordinator Exam-lock lookup.
	 *
	 * @param database initialised application database
	 */
	public SqliteCoordinatorExamLockRepository(SqliteDatabase database) {
		this.database = Objects.requireNonNull(database, "database");
	}

	/**
	 * Finds the active lock for an Exam.
	 *
	 * @param examLocalId positive local Exam identifier
	 * @return active assignment lock, or empty if the Exam is unlocked
	 * @throws SQLException if lock information cannot be read
	 */
	public Optional<ExamLock> find(long examLocalId) throws SQLException {
		if (examLocalId < 1) {
			throw new IllegalArgumentException("Exam ID must be positive");
		}
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT assignments.assignment_id,
						       assignments.contributor_name
						FROM exam_work_assignment_exam_locks locked
						JOIN portable_entity_identities identity
						  ON identity.portable_id = locked.exam_portable_id
						 AND identity.entity_type = 'EXAM'
						 AND identity.retired = 0
						JOIN exam_work_assignments assignments
						  ON assignments.assignment_id = locked.assignment_id
						WHERE identity.local_id = ?
						""")) {
			statement.setLong(1, examLocalId);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					return Optional.empty();
				}
				ExamLock lock = new ExamLock(examLocalId, UUID.fromString(result.getString("assignment_id")),
						result.getString("contributor_name"));
				if (result.next()) {
					throw new SQLException("More than one active lock for Exam " + examLocalId);
				}
				return Optional.of(lock);
			}
		}
	}

	/**
	 * Finds active locks for every Exam in a Subject.
	 *
	 * @param subjectLocalId positive local Subject identifier
	 * @return immutable map keyed by local Exam identifier
	 * @throws SQLException if lock information cannot be read
	 */
	public Map<Long, ExamLock> findForSubject(long subjectLocalId) throws SQLException {
		if (subjectLocalId < 1) {
			throw new IllegalArgumentException("Subject ID must be positive");
		}
		Map<Long, ExamLock> locks = new LinkedHashMap<>();
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT exam.id AS exam_local_id,
						       assignments.assignment_id,
						       assignments.contributor_name
						FROM exam_work_assignment_exam_locks locked
						JOIN portable_entity_identities identity
						  ON identity.portable_id = locked.exam_portable_id
						 AND identity.entity_type = 'EXAM'
						 AND identity.retired = 0
						JOIN exams exam
						  ON exam.id = identity.local_id
						JOIN exam_work_assignments assignments
						  ON assignments.assignment_id = locked.assignment_id
						WHERE exam.subject_id = ?
						ORDER BY exam.id
						""")) {
			statement.setLong(1, subjectLocalId);
			try (ResultSet result = statement.executeQuery()) {
				while (result.next()) {
					long examId = result.getLong("exam_local_id");
					ExamLock lock = new ExamLock(examId, UUID.fromString(result.getString("assignment_id")),
							result.getString("contributor_name"));
					if (locks.putIfAbsent(examId, lock) != null) {
						throw new SQLException("Multiple active locks for Exam " + examId);
					}
				}
			}
		}
		return Map.copyOf(locks);
	}

	/**
	 * Immutable active coordinator assignment details.
	 *
	 * @param examLocalId     local Exam identifier
	 * @param assignmentId    immutable active assignment UUID
	 * @param contributorName recorded contributor name
	 */
	public record ExamLock(long examLocalId, UUID assignmentId, String contributorName) {

		/**
		 * Validates the coordinator lock record.
		 */
		public ExamLock {
			if (examLocalId < 1) {
				throw new IllegalArgumentException("Exam ID must be positive");
			}
			Objects.requireNonNull(assignmentId, "assignmentId");
			if (contributorName == null || contributorName.isBlank()) {
				throw new IllegalArgumentException("Contributor name must not be blank");
			}
		}
	}
}
