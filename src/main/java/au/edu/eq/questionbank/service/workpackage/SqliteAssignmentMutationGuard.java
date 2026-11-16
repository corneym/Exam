package au.edu.eq.questionbank.service.workpackage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Set;

/**
 * Checks contributor mutations against an assigned Exam and its permitted
 * syllabus versions.
 *
 * <p>
 * The Subject, Exam and syllabus identifiers must originate from a validated,
 * persisted assignment session. Callers must perform the check using the same
 * transaction as the intended mutation.
 *
 * <p>
 * This guard does not grant permission to change Exam structure, source assets
 * or authoritative curriculum definitions.
 */
public final class SqliteAssignmentMutationGuard {

	private final long examId;
	private final Set<Long> permittedSyllabusVersionIds;
	private final long subjectId;

	/**
	 * Creates a mutation guard from validated local assignment identities.
	 *
	 * @param subjectId                   assigned local Subject ID
	 * @param examId                      assigned local Exam ID
	 * @param permittedSyllabusVersionIds local IDs of the explicitly current
	 *                                    syllabus and, when enabled, the original
	 *                                    2019 syllabus
	 * @throws IllegalArgumentException if any identity is invalid
	 * @throws NullPointerException     if the syllabus set is null
	 */
	public SqliteAssignmentMutationGuard(long subjectId, long examId, Set<Long> permittedSyllabusVersionIds) {
		if (subjectId < 1 || examId < 1) {
			throw new IllegalArgumentException("Assignment Subject and Exam IDs must be positive");
		}
		Objects.requireNonNull(permittedSyllabusVersionIds, "permittedSyllabusVersionIds");
		if (permittedSyllabusVersionIds.isEmpty()
				|| permittedSyllabusVersionIds.stream().anyMatch(id -> id == null || id < 1)) {
			throw new IllegalArgumentException("Assignment requires valid permitted syllabus IDs");
		}
		this.subjectId = subjectId;
		this.examId = examId;
		this.permittedSyllabusVersionIds = Set.copyOf(permittedSyllabusVersionIds);
	}

	/**
	 * Rejects coordinator-owned structural mutations in assignment mode.
	 *
	 * <p>
	 * Call before attempting to add, edit, delete, replace or reassign an Exam,
	 * Booklet, AnswerFile, source asset or syllabus definition.
	 *
	 * @throws IllegalStateException always, because these mutations are prohibited
	 *                               in assignment mode
	 */
	public void denyAuthoritativeStructureChange() {
		throw new IllegalStateException(
				"Authoritative Exam, asset and syllabus changes " + "are prohibited in contributor assignment mode");
	}

	/**
	 * Requires an existing Answer to belong to the assigned Exam.
	 *
	 * @param connection caller-owned database transaction
	 * @param answerId   local Answer ID
	 * @throws SQLException          if the ownership lookup fails
	 * @throws IllegalStateException if the Answer is absent or out of scope
	 */
	public void requireAnswer(Connection connection, long answerId) throws SQLException {
		requireExamOwner(connection, """
				SELECT booklet.exam_id
				FROM answers answer
				JOIN questions question
				    ON question.id = answer.question_id
				JOIN exam_booklets booklet
				    ON booklet.id = question.booklet_id
				WHERE answer.id = ?
				""", answerId, "Answer");
	}

	/**
	 * Requires an AnswerFile referenced by capture to belong to the assigned Exam.
	 * This does not authorise changing that AnswerFile.
	 *
	 * @param connection   caller-owned database transaction
	 * @param answerFileId local AnswerFile ID
	 * @throws SQLException          if the ownership lookup fails
	 * @throws IllegalStateException if the AnswerFile is absent or out of scope
	 */
	public void requireAnswerFile(Connection connection, long answerFileId) throws SQLException {
		requireExamOwner(connection, """
				SELECT exam_id
				FROM answer_files
				WHERE id = ?
				""", answerFileId, "AnswerFile");
	}

	/**
	 * Requires a Booklet to belong to the assigned Exam.
	 *
	 * <p>
	 * Use before new Question, SourceQuestion or Shared Context capture, within the
	 * capture transaction.
	 *
	 * @param connection caller-owned database transaction
	 * @param bookletId  local Booklet ID
	 * @throws SQLException          if the ownership lookup fails
	 * @throws IllegalStateException if the Booklet is absent or out of scope
	 */
	public void requireBooklet(Connection connection, long bookletId) throws SQLException {
		requireExamOwner(connection, """
				SELECT exam_id
				FROM exam_booklets
				WHERE id = ?
				""", bookletId, "Booklet");
	}

	/**
	 * Requires a classification node to belong to one of the assignment's permitted
	 * syllabus versions.
	 *
	 * @param connection caller-owned database transaction
	 * @param nodeId     local curriculum node ID
	 * @throws SQLException          if the node lookup fails
	 * @throws IllegalStateException if its syllabus is not permitted
	 */
	public void requireClassificationNode(Connection connection, long nodeId) throws SQLException {
		Objects.requireNonNull(connection, "connection");
		if (nodeId < 1) {
			throw new IllegalStateException("Invalid assignment classification node");
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT syllabus_version_id
				FROM curriculum_nodes
				WHERE id = ?
				""")) {
			statement.setLong(1, nodeId);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new IllegalStateException("Classification node does not exist: " + nodeId);
				}
				long syllabusId = result.getLong(1);
				if (!permittedSyllabusVersionIds.contains(syllabusId)) {
					throw new IllegalStateException(
							"Classification belongs to a syllabus " + "outside this assignment");
				}
				if (result.next()) {
					throw new SQLException("Duplicate curriculum node identity: " + nodeId);
				}
			}
		}
	}

	/**
	 * Checks an Exam identity against the active assignment.
	 *
	 * @param candidateExamId Exam being accessed
	 * @throws IllegalStateException if the Exam differs from the assignment
	 */
	public void requireExam(long candidateExamId) {
		if (candidateExamId != examId) {
			throw new IllegalStateException("Operation targets an Exam outside this assignment");
		}
	}

	/**
	 * Requires an existing Question to belong to the assigned Exam.
	 *
	 * @param connection caller-owned database transaction
	 * @param questionId local Question ID
	 * @throws SQLException          if the ownership lookup fails
	 * @throws IllegalStateException if the Question is absent or out of scope
	 */
	public void requireQuestion(Connection connection, long questionId) throws SQLException {
		requireExamOwner(connection, """
				SELECT booklet.exam_id
				FROM questions question
				JOIN exam_booklets booklet
				    ON booklet.id = question.booklet_id
				WHERE question.id = ?
				""", questionId, "Question");
	}

	/**
	 * Requires an existing Shared Context to belong to the assigned Exam.
	 *
	 * @param connection caller-owned database transaction
	 * @param contextId  local Shared Context ID
	 * @throws SQLException          if the ownership lookup fails
	 * @throws IllegalStateException if the context is absent or out of scope
	 */
	public void requireSharedContext(Connection connection, long contextId) throws SQLException {
		requireExamOwner(connection, """
				SELECT booklet.exam_id
				FROM shared_question_contexts context
				JOIN exam_booklets booklet
				    ON booklet.id = context.booklet_id
				WHERE context.id = ?
				""", contextId, "Shared Context");
	}

	/**
	 * Requires an existing SourceQuestion to belong to the assigned Exam.
	 *
	 * @param connection       caller-owned database transaction
	 * @param sourceQuestionId local SourceQuestion ID
	 * @throws SQLException          if the ownership lookup fails
	 * @throws IllegalStateException if the SourceQuestion is absent or out of scope
	 */
	public void requireSourceQuestion(Connection connection, long sourceQuestionId) throws SQLException {
		requireExamOwner(connection, """
				SELECT booklet.exam_id
				FROM source_questions source
				JOIN exam_booklets booklet
				    ON booklet.id = source.booklet_id
				WHERE source.id = ?
				""", sourceQuestionId, "SourceQuestion");
	}

	/**
	 * Checks a Subject identity against the active assignment.
	 *
	 * @param candidateSubjectId Subject being accessed
	 * @throws IllegalStateException if the Subject differs
	 */
	public void requireSubject(long candidateSubjectId) {
		if (candidateSubjectId != subjectId) {
			throw new IllegalStateException("Operation targets a Subject outside this assignment");
		}
	}

	/**
	 * Requires a syllabus version to be explicitly permitted by the assignment.
	 *
	 * @param syllabusVersionId local syllabus version ID
	 * @throws IllegalStateException if the syllabus is not permitted
	 */
	public void requireSyllabusVersion(long syllabusVersionId) {
		if (!permittedSyllabusVersionIds.contains(syllabusVersionId)) {
			throw new IllegalStateException("Syllabus version is not permitted by this assignment");
		}
	}

	private void requireExamOwner(Connection connection, String sql, long localId, String entityName)
			throws SQLException {
		Objects.requireNonNull(connection, "connection");
		if (localId < 1) {
			throw new IllegalStateException("Invalid " + entityName + " identity");
		}

		// All SQL statements are constants defined by this class.
		// No incoming package or UI value supplies SQL identifiers.
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setLong(1, localId);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new IllegalStateException(entityName + " does not exist: " + localId);
				}
				if (result.getLong(1) != examId) {
					throw new IllegalStateException(entityName + " belongs to an unassigned Exam");
				}
				if (result.next()) {
					throw new SQLException("Duplicate " + entityName + " identity: " + localId);
				}
			}
		}
	}
}
