package au.edu.eq.questionbank.repository.assessment;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.SourceQuestionCodeParser;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

/**
 * Corrects editable legacy question metadata without requiring source-region
 * replacement.
 * <p>
 * This operation is deliberately separate from ordinary question editing.
 * Legacy-imported questions may exist before question regions have been
 * captured, so metadata correction must work with zero, one or many existing
 * regions.
 * <p>
 * The operation may change: question code, marks, classification and the
 * historical preamble-capture-required flag. It preserves booklet identity,
 * source-document identity, question text, question regions, answer state and
 * any real shared question context.
 */
public final class LegacyQuestionMetadataService {

	private final SqliteDatabase database;
	private final SqliteQuestionRepository questionRepository;

	/**
	 * Creates a metadata correction service.
	 *
	 * @param database question-bank database
	 */
	public LegacyQuestionMetadataService(SqliteDatabase database) {
		if (database == null) {
			throw new NullPointerException("database");
		}
		this.database = database;
		this.questionRepository = new SqliteQuestionRepository(database);
	}

	/**
	 * Atomically corrects editable legacy metadata.
	 *
	 * @param question                persisted question being corrected
	 * @param questionCode            replacement non-blank question code
	 * @param marks                   replacement positive mark value
	 * @param classification          replacement Subtopic or Descriptor from the
	 *                                question's existing syllabus version
	 * @param preambleCaptureRequired corrected historical preamble-capture hint
	 * @return reloaded question after successful persistence
	 * @throws NullPointerException     if question or classification is null
	 * @throws IllegalArgumentException if metadata or classification is invalid
	 * @throws IllegalStateException    if persistence fails
	 */
	public Question updateMetadata(Question question, String questionCode, int marks, CurriculumNode classification,
			boolean preambleCaptureRequired) {
		validateRequest(question, questionCode, marks, classification);
		long questionId = question.getId();
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				StoredQuestionState stored = findStoredQuestionState(connection, questionId);
				if (stored.bookletId() != question.getBooklet().getId()) {
					throw new IllegalArgumentException("Stored question does not belong to the supplied booklet");
				}
				long existingSyllabusVersionId = findSyllabusVersionId(connection, stored.classificationNodeId());
				verifyReplacementClassification(connection, classification, existingSyllabusVersionId);
				String sourceQuestionCode = SourceQuestionCodeParser.derive(questionCode);
				Long replacementSourceQuestionId = findOrCreateSourceQuestionId(connection, stored.bookletId(),
						sourceQuestionCode);
				updateQuestionMetadata(connection, questionId, stored.bookletId(), questionCode, marks,
						classification.getId(), preambleCaptureRequired, replacementSourceQuestionId);
				if (stored.sourceQuestionId() != null
						&& !stored.sourceQuestionId().equals(replacementSourceQuestionId)) {
					deleteSourceQuestionIfUnreferenced(connection, stored.sourceQuestionId());
				}
				connection.commit();
			} catch (SQLException | RuntimeException failure) {
				try {
					connection.rollback();
				} catch (SQLException rollbackFailure) {
					failure.addSuppressed(rollbackFailure);
				}
				throw failure;
			}
		} catch (SQLException failure) {
			throw new IllegalStateException("Could not update legacy question metadata atomically", failure);
		}
		return questionRepository.findById(questionId).orElseThrow(
				() -> new IllegalStateException("Question disappeared after metadata update: " + questionId));
	}

	private void deleteSourceQuestionIfUnreferenced(Connection connection, long sourceQuestionId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				DELETE FROM source_questions
				WHERE id = ?
				  AND NOT EXISTS (
				      SELECT 1
				      FROM questions
				      WHERE source_question_id =
				            source_questions.id
				  )
				""")) {
			statement.setLong(1, sourceQuestionId);
			statement.executeUpdate();
		}
	}

	private Long findOrCreateSourceQuestionId(Connection connection, long bookletId, String sourceQuestionCode)
			throws SQLException {
		if (sourceQuestionCode == null) {
			return null;
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT id
				FROM source_questions
				WHERE booklet_id = ?
				  AND source_question_code = ?
				""")) {
			statement.setLong(1, bookletId);
			statement.setString(2, sourceQuestionCode);
			try (ResultSet result = statement.executeQuery()) {
				if (result.next()) {
					return Long.valueOf(result.getLong("id"));
				}
			}
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO source_questions
				    (booklet_id,
				     source_question_code)
				VALUES (?, ?)
				RETURNING id
				""")) {
			statement.setLong(1, bookletId);
			statement.setString(2, sourceQuestionCode);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new SQLException("Source-question insert did not return an id");
				}
				return Long.valueOf(result.getLong("id"));
			}
		}
	}

	private StoredQuestionState findStoredQuestionState(Connection connection, long questionId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT
				    booklet_id,
				    classification_node_id,
				    source_question_id
				FROM questions
				WHERE id = ?
				""")) {
			statement.setLong(1, questionId);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new IllegalArgumentException("Question does not exist: " + questionId);
				}
				Long sourceQuestionId = null;
				long storedSourceQuestionId = result.getLong("source_question_id");
				if (!result.wasNull()) {
					sourceQuestionId = Long.valueOf(storedSourceQuestionId);
				}
				return new StoredQuestionState(result.getLong("booklet_id"), result.getLong("classification_node_id"),
						sourceQuestionId);
			}
		}
	}

	private long findSyllabusVersionId(Connection connection, long curriculumNodeId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT syllabus_version_id
				FROM curriculum_nodes
				WHERE id = ?
				""")) {
			statement.setLong(1, curriculumNodeId);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new IllegalStateException(
							"Stored question classification does not exist: " + curriculumNodeId);
				}
				return result.getLong("syllabus_version_id");
			}
		}
	}

	private void updateQuestionMetadata(Connection connection, long questionId, long bookletId, String questionCode,
			int marks, long classificationNodeId, boolean preambleCaptureRequired, Long sourceQuestionId)
			throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				UPDATE questions
				SET question_code = ?,
				    marks = ?,
				    classification_node_id = ?,
				    preamble_capture_required = ?,
				    source_question_id = ?
				WHERE id = ?
				  AND booklet_id = ?
				""")) {
			statement.setString(1, questionCode);
			statement.setInt(2, marks);
			statement.setLong(3, classificationNodeId);
			statement.setInt(4, preambleCaptureRequired ? 1 : 0);
			if (sourceQuestionId == null) {
				statement.setNull(5, Types.BIGINT);
			} else {
				statement.setLong(5, sourceQuestionId.longValue());
			}
			statement.setLong(6, questionId);
			statement.setLong(7, bookletId);
			if (statement.executeUpdate() != 1) {
				throw new SQLException("Question metadata update affected an unexpected number of rows");
			}
		}
	}

	private void validateRequest(Question question, String questionCode, int marks, CurriculumNode classification) {
		if (question == null) {
			throw new NullPointerException("question");
		}
		if (questionCode == null || questionCode.isBlank()) {
			throw new IllegalArgumentException("questionCode must not be blank");
		}
		if (marks < 1) {
			throw new IllegalArgumentException("marks must be positive");
		}
		if (classification == null) {
			throw new NullPointerException("classification");
		}
		if (classification.getLevel() != CurriculumLevel.SUBTOPIC
				&& classification.getLevel() != CurriculumLevel.DESCRIPTOR) {
			throw new IllegalArgumentException("Question classification must be a SUBTOPIC or DESCRIPTOR");
		}
		if (!classification.getSyllabusVersion().getSubject().equals(question.getExam().getSubject())) {
			throw new IllegalArgumentException("Question classification must belong to the exam's subject");
		}
		if (!classification.getSyllabusVersion().equals(question.getClassification().getSyllabusVersion())) {
			throw new IllegalArgumentException(
					"Legacy metadata correction must remain within the question's existing syllabus version");
		}
	}

	private void verifyReplacementClassification(Connection connection, CurriculumNode classification,
			long expectedSyllabusVersionId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT
				    syllabus_version_id,
				    curriculum_level
				FROM curriculum_nodes
				WHERE id = ?
				""")) {
			statement.setLong(1, classification.getId());
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new IllegalArgumentException(
							"Replacement classification does not exist: " + classification.getId());
				}
				long syllabusVersionId = result.getLong("syllabus_version_id");
				String level = result.getString("curriculum_level");
				if (syllabusVersionId != expectedSyllabusVersionId) {
					throw new IllegalArgumentException(
							"Replacement classification must remain within the question's existing syllabus version");
				}
				if (!CurriculumLevel.SUBTOPIC.name().equals(level)
						&& !CurriculumLevel.DESCRIPTOR.name().equals(level)) {
					throw new IllegalArgumentException("Replacement classification must be a SUBTOPIC or DESCRIPTOR");
				}
				if (!classification.getLevel().name().equals(level)) {
					throw new IllegalArgumentException(
							"Replacement classification does not match persisted curriculum data");
				}
			}
		}
	}

	private record StoredQuestionState(long bookletId, long classificationNodeId, Long sourceQuestionId) {
	}
}
