package au.edu.eq.questionbank.repository.assessment;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
import au.edu.eq.questionbank.service.workpackage.ExamWorkJsonlCodec;
import au.edu.eq.questionbank.service.workpackage.ExchangeRecord;

/**
 * Protects coordinator-owned records during offline contributor capture.
 *
 * <p>
 * Ownership is determined from the immutable baseline of the currently
 * selected, unsubmitted assignment, rather than merely from its Exam scope. New
 * contributor records in that Exam remain editable.
 *
 * <p>
 * Imported Subjects and Subjects belonging to installed contributor assignments
 * remain read-only when no eligible assignment is selected. The checks use the
 * caller's database connection and do not change data.
 */
public final class SqliteContributorBaselineWriteGuard {

	/**
	 * Creates a stateless baseline write guard.
	 */
	public SqliteContributorBaselineWriteGuard() {
	}

	/**
	 * Verifies that a new Answer may be created for a persisted Question.
	 *
	 * <p>
	 * An issued Question may receive a new Answer without granting permission to
	 * modify the Question itself. Imported Subjects, inactive assignments and
	 * records outside the selected Exam remain protected.
	 *
	 * @param connection caller-owned database connection
	 * @param questionId positive local Question identifier
	 * @throws SQLException             if ownership validation fails
	 * @throws IllegalStateException    if Answer creation is prohibited
	 * @throws IllegalArgumentException if the Question ID is invalid
	 * @throws NullPointerException     if the connection is null
	 */
	public void requireAnswerCreation(Connection connection, long questionId) throws SQLException {
		Objects.requireNonNull(connection, "connection");
		if (questionId < 1) {
			throw new IllegalArgumentException("Question ID must be positive");
		}
		Scope scope = readScope(connection, EntityType.QUESTION, questionId);
		requireWritableScope(connection, scope);

		// Creation never grants permission to replace an
		// existing Answer, regardless of its ownership.
		if (exists(connection, """
				SELECT 1
				FROM answers
				WHERE question_id = ?
				""", questionId)) {
			throw new IllegalStateException("This Question already has an Answer.");
		}
	}

	/**
	 * Verifies that new contributor-capturable records may be created in a booklet.
	 *
	 * <p>
	 * New Questions, Source Questions and Shared Contexts are allowed within the
	 * selected active assignment, but not in imported Subjects, inactive contributor
	 * corpora or another Exam.
	 *
	 * @param connection caller-owned database connection
	 * @param bookletId  positive local ExamBooklet identifier
	 * @throws SQLException             if ownership validation fails
	 * @throws IllegalStateException    if creation is prohibited or the booklet does
	 *                                  not exist
	 * @throws IllegalArgumentException if the booklet ID is invalid
	 * @throws NullPointerException     if the connection is null
	 */
	public void requireCaptureCreation(Connection connection, long bookletId) throws SQLException {
		Objects.requireNonNull(connection, "connection");
		if (bookletId < 1) {
			throw new IllegalArgumentException("ExamBooklet ID must be positive");
		}
		Scope scope = readScope(connection, EntityType.EXAM_BOOKLET, bookletId);
		requireWritableScope(connection, scope);
	}

	/**
	 * Rejects mutation of an externally authoritative or issued baseline record.
	 *
	 * <p>
	 * During an active assignment, only contributor-created Questions, Answers,
	 * Source Questions and Shared Contexts in its Exam are editable. Structural
	 * Exam, Booklet and AnswerFile records remain protected.
	 *
	 * @param connection caller-owned database connection
	 * @param type       portable entity type
	 * @param localId    positive SQLite-local identifier
	 * @throws SQLException             if ownership or baseline validation fails
	 *
	 * @throws IllegalStateException    if the record is not editable or does not
	 *                                  exist
	 * @throws IllegalArgumentException if the ID or entity type is unsupported
	 * @throws NullPointerException     if a required argument is null
	 */
	public void requireMutable(Connection connection, EntityType type, long localId) throws SQLException {
		Objects.requireNonNull(connection, "connection");
		Objects.requireNonNull(type, "type");
		if (localId < 1) {
			throw new IllegalArgumentException("Record ID must be positive");
		}
		Scope scope = readScope(connection, type, localId);
		ActiveAssignment active = requireWritableScope(connection, scope);
		if (active == null) {
			return;
		}
		if (type == EntityType.EXAM || type == EntityType.EXAM_BOOKLET || type == EntityType.ANSWER_FILE) {
			throw new IllegalStateException("Exam and Booklet structure is coordinator-owned.");
		}
		UUID portableId = readPortableId(connection, type, localId);
		if (baselineContains(active, type, portableId)) {
			throw new IllegalStateException("This " + type.name() + " was supplied by the coordinator "
					+ "and is read-only. " + "Only newly captured contributor " + "work may be edited.");
		}
	}

	private boolean baselineContains(ActiveAssignment active, EntityType type, UUID portableId) throws SQLException {
		byte[] records = active.baselineRecords();
		String digest;
		try {
			digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(records));
		} catch (NoSuchAlgorithmException failure) {
			throw new SQLException("SHA-256 is unavailable", failure);
		}
		if (!digest.equals(active.recordsSha256())) {
			throw new SQLException("Contributor assignment baseline failed its integrity check");
		}
		List<ExchangeRecord> baseline;
		try {
			baseline = new ExamWorkJsonlCodec().readRecords(new ByteArrayInputStream(records));
		} catch (IOException failure) {
			throw new SQLException("Contributor assignment baseline cannot be decoded", failure);
		}
		return baseline.stream()
				.anyMatch(record -> record.recordType().equals(type.name()) && record.recordId().equals(portableId));
	}

	private boolean exists(Connection connection, String sql, long localId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setLong(1, localId);
			try (ResultSet result = statement.executeQuery()) {
				return result.next();
			}
		}
	}

	private ActiveAssignment readActiveAssignment(Connection connection) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT session.exam_local_id,
				       session.baseline_records,
				       session.records_sha256
				FROM contributor_assignment_selection selection
				JOIN contributor_assignment_sessions session
				  ON session.assignment_id = selection.assignment_id
				WHERE selection.singleton_id = 1
				  AND NOT EXISTS (
				      SELECT 1
				      FROM contributor_return_packages packet
				      WHERE packet.assignment_id = session.assignment_id
				  )
				  AND NOT EXISTS (
				      SELECT 1
				      FROM contributor_assignment_hidden hidden
				      WHERE hidden.assignment_id = session.assignment_id
				  )
				"""); ResultSet result = statement.executeQuery()) {
			if (!result.next()) {
				return null;
			}
			byte[] records = result.getBytes("baseline_records");
			String digest = result.getString("records_sha256");
			if (records == null || digest == null) {
				throw new SQLException("Active contributor baseline is missing");
			}
			ActiveAssignment active = new ActiveAssignment(result.getLong("exam_local_id"), records, digest);
			if (result.next()) {
				throw new SQLException("More than one active contributor assignment is selected");
			}
			return active;
		}
	}

	private UUID readPortableId(Connection connection, EntityType type, long localId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT portable_id
				FROM portable_entity_identities
				WHERE entity_type = ?
				  AND local_id = ?
				  AND retired = 0
				""")) {
			statement.setString(1, type.name());
			statement.setLong(2, localId);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new SQLException("Missing portable identity for " + type + ":" + localId);
				}
				UUID identifier = UUID.fromString(result.getString("portable_id"));
				if (result.next()) {
					throw new SQLException("Duplicate portable record identity");
				}
				return identifier;
			}
		}
	}

	private Scope readScope(Connection connection, EntityType type, long localId) throws SQLException {
		String sql = switch (type) {
		case QUESTION -> """
				SELECT exam.subject_id, exam.id AS exam_id
				FROM questions question
				JOIN exam_booklets booklet ON booklet.id = question.booklet_id
				JOIN exams exam ON exam.id = booklet.exam_id
				WHERE question.id = ?
				""";
		case ANSWER -> """
				SELECT exam.subject_id, exam.id AS exam_id
				FROM answers answer
				JOIN questions question ON question.id = answer.question_id
				JOIN exam_booklets booklet ON booklet.id = question.booklet_id
				JOIN exams exam ON exam.id = booklet.exam_id
				WHERE answer.id = ?
				""";
		case SOURCE_QUESTION -> """
				SELECT exam.subject_id, exam.id AS exam_id
				FROM source_questions source
				JOIN exam_booklets booklet ON booklet.id = source.booklet_id
				JOIN exams exam ON exam.id = booklet.exam_id
				WHERE source.id = ?
				""";
		case SHARED_QUESTION_CONTEXT -> """
				SELECT exam.subject_id, exam.id AS exam_id
				FROM shared_question_contexts context
				JOIN exam_booklets booklet ON booklet.id = context.booklet_id
				JOIN exams exam ON exam.id = booklet.exam_id
				WHERE context.id = ?
				""";
		case EXAM_BOOKLET -> """
				SELECT exam.subject_id, exam.id AS exam_id
				FROM exam_booklets booklet
				JOIN exams exam ON exam.id = booklet.exam_id
				WHERE booklet.id = ?
				""";
		case ANSWER_FILE -> """
				SELECT exam.subject_id, exam.id AS exam_id
				FROM answer_files file
				JOIN exams exam ON exam.id = file.exam_id
				WHERE file.id = ?
				""";
		case EXAM -> """
				SELECT subject_id, id AS exam_id
				FROM exams
				WHERE id = ?
				""";
		default -> throw new IllegalArgumentException("Unsupported contributor write-guard entity: " + type);
		};
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setLong(1, localId);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new IllegalStateException("Record does not exist: " + type + ":" + localId);
				}
				Scope scope = new Scope(result.getLong("subject_id"), result.getLong("exam_id"));
				if (result.next()) {
					throw new SQLException("Ambiguous record ownership");
				}
				return scope;
			}
		}
	}

	private ActiveAssignment requireWritableScope(Connection connection, Scope scope) throws SQLException {
		if (exists(connection, """
				SELECT 1
				FROM imported_subject_authorities
				WHERE subject_local_id = ?
				""", scope.subjectId())) {
			throw new IllegalStateException("This Subject is an imported read-only snapshot.");
		}
		ActiveAssignment active = readActiveAssignment(connection);
		if (active == null) {
			if (exists(connection, """
					SELECT 1
					FROM contributor_assignment_sessions
					WHERE subject_local_id = ?
					""", scope.subjectId())) {
				throw new IllegalStateException("This Subject belongs to a contributor "
						+ "work package. Select an active " + "assignment to capture new work.");
			}
			return null;
		}
		if (active.examId() != scope.examId()) {
			throw new IllegalStateException("This record is outside the " + "selected contributor Exam.");
		}
		return active;
	}

	private record ActiveAssignment(long examId, byte[] baselineRecords, String recordsSha256) {
	}

	private record Scope(long subjectId, long examId) {
	}
}
