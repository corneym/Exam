package au.edu.eq.questionbank.repository.assessment;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.SharedQuestionContext;
import au.edu.eq.questionbank.model.SourceQuestion;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

/**
 * Persists a question and its ordered source regions in one SQLite transaction.
 */
public final class SqliteQuestionWriter {

	private final SqliteDatabase database;

	/**
	 * Creates a question writer.
	 *
	 * @param database the question-bank database
	 * @throws NullPointerException if {@code database} is {@code null}
	 */
	public SqliteQuestionWriter(SqliteDatabase database) {
		if (database == null) {
			throw new NullPointerException("database");
		}
		this.database = database;
	}

	/**
	 * Atomically attaches ordered regions to a persisted question that currently
	 * has none.
	 *
	 * @param questionId the positive persistent question identifier
	 * @param regions    one or more regions from the question's booklet
	 * @throws SQLException             if the transaction fails
	 * @throws NullPointerException     if {@code regions} or an element is
	 *                                  {@code null}
	 * @throws IllegalArgumentException if the question is absent, already has
	 *                                  regions, or the regions use another booklet
	 */
	public void attachRegions(long questionId, List<QuestionRegion> regions) throws SQLException {
		attachRegionsInternal(questionId, regions, null, null, null, false);
	}

	public void attachRegions(long questionId, List<QuestionRegion> regions, CurriculumNode classification,
			SourceQuestion sourceQuestion, SharedQuestionContext sharedContext) throws SQLException {
		attachRegionsInternal(questionId, regions, classification, sourceQuestion, sharedContext, true);
	}

	public void attachRegions(long questionId, List<QuestionRegion> regions, SourceQuestion sourceQuestion,
			SharedQuestionContext sharedContext) throws SQLException {
		attachRegionsInternal(questionId, regions, null, sourceQuestion, sharedContext, true);
	}

	/**
	 * Stores a classified question and all of its regions atomically.
	 *
	 * @param booklet                 the booklet containing the question
	 * @param questionCode            the non-blank question label
	 * @param questionText            supplementary text, which may be blank
	 * @param marks                   the positive mark value
	 * @param regions                 zero or more source regions in extraction
	 *                                order
	 * @param classification          the question's syllabus subtopic or descriptor
	 * @param preambleCaptureRequired whether shared or introductory material must
	 *                                be included during later capture
	 * @return the stored question with its generated identifier
	 * @throws SQLException             if the transaction cannot be completed
	 * @throws NullPointerException     if a required object is {@code null}
	 * @throws IllegalArgumentException if the question metadata or relationships
	 *                                  are invalid
	 */
	public Question insertQuestion(ExamBooklet booklet, String questionCode, String questionText, int marks,
			List<QuestionRegion> regions, CurriculumNode classification, boolean preambleCaptureRequired)
			throws SQLException {
		return insertQuestion(booklet, questionCode, questionText, marks, regions, classification,
				preambleCaptureRequired, null, null);
	}

	/**
	 * Stores a classified question and all of its regions atomically.
	 *
	 * @param booklet                 the booklet containing the question
	 * @param questionCode            the non-blank question label
	 * @param questionText            supplementary text, which may be blank
	 * @param marks                   the positive mark value
	 * @param regions                 zero or more source regions in extraction
	 *                                order
	 * @param classification          the question's syllabus subtopic or descriptor
	 * @param preambleCaptureRequired whether shared or introductory material must
	 *                                be included during later capture
	 * @return the stored question with its generated identifier
	 * @throws SQLException             if the transaction cannot be completed
	 * @throws NullPointerException     if a required object is {@code null}
	 * @throws IllegalArgumentException if the question metadata or relationships
	 */
	public Question insertQuestion(ExamBooklet booklet, String questionCode, String questionText, int marks,
			List<QuestionRegion> regions, CurriculumNode classification, boolean preambleCaptureRequired,
			SourceQuestion sourceQuestion, SharedQuestionContext sharedContext) throws SQLException {
		if (booklet == null) {
			throw new NullPointerException("booklet");
		}
		if (questionCode == null || questionCode.isBlank()) {
			throw new IllegalArgumentException("questionCode must not be blank");
		}
		if (questionText == null) {
			throw new NullPointerException("questionText");
		}
		if (regions == null) {
			throw new NullPointerException("regions");
		}
		if (classification == null) {
			throw new NullPointerException("classification");
		}
		if (sourceQuestion != null && sourceQuestion.getBooklet().getId() != booklet.getId()) {
			throw new IllegalArgumentException("Source question must belong to the question's booklet");
		}
		if (sharedContext != null && sharedContext.getBooklet().getId() != booklet.getId()) {
			throw new IllegalArgumentException("Shared question context must belong to the question's booklet");
		}
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				verifySourceQuestionRelationship(connection, booklet, sourceQuestion);
				verifySharedContextRelationship(connection, booklet, sharedContext);
				long questionId = insertQuestionRow(connection, booklet, questionCode, questionText, marks,
						classification, preambleCaptureRequired, sourceQuestion, sharedContext);
				insertRegions(connection, questionId, regions);
				Question question = new Question(questionId, booklet, questionCode, questionText, marks, regions,
						classification, preambleCaptureRequired, sourceQuestion, sharedContext);
				connection.commit();
				return question;
			} catch (SQLException | RuntimeException e) {
				try {
					connection.rollback();
				} catch (SQLException rollbackFailure) {
					e.addSuppressed(rollbackFailure);
				}
				throw e;
			}
		}
	}

	public void updateCaptureRelationships(long questionId, ExamBooklet booklet, CurriculumNode classification,
			SourceQuestion sourceQuestion, SharedQuestionContext sharedContext) throws SQLException {
		if (questionId < 1) {
			throw new IllegalArgumentException("questionId must be positive");
		}
		if (booklet == null) {
			throw new NullPointerException("booklet");
		}
		validateClassificationForBooklet(booklet, classification);
		if (sourceQuestion != null && sourceQuestion.getBooklet().getId() != booklet.getId()) {
			throw new IllegalArgumentException("Source question must belong to the question's booklet");
		}
		if (sharedContext != null && sharedContext.getBooklet().getId() != booklet.getId()) {
			throw new IllegalArgumentException("Shared question context must belong to the question's booklet");
		}
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				verifyQuestionBooklet(connection, questionId, booklet);
				verifyClassificationSyllabusUnchanged(connection, questionId, classification);
				verifySourceQuestionRelationship(connection, booklet, sourceQuestion);
				verifySharedContextRelationship(connection, booklet, sharedContext);
				updateQuestionCaptureDetails(connection, questionId, classification, sourceQuestion, sharedContext);
				connection.commit();
			} catch (SQLException | RuntimeException e) {
				try {
					connection.rollback();
				} catch (SQLException rollbackFailure) {
					e.addSuppressed(rollbackFailure);
				}
				throw e;
			}
		}
	}

	private void attachRegionsInternal(long questionId, List<QuestionRegion> regions, CurriculumNode classification,
			SourceQuestion sourceQuestion, SharedQuestionContext sharedContext, boolean updateRelationships)
			throws SQLException {
		if (questionId < 1) {
			throw new IllegalArgumentException("questionId must be positive");
		}
		if (regions == null) {
			throw new NullPointerException("regions");
		}
		if (regions.isEmpty()) {
			throw new IllegalArgumentException("regions must not be empty");
		}
		QuestionRegion firstRegion = regions.getFirst();
		if (firstRegion == null) {
			throw new NullPointerException("regions contains null");
		}
		ExamBooklet booklet = firstRegion.booklet();
		for (QuestionRegion region : regions) {
			if (region == null) {
				throw new NullPointerException("regions contains null");
			}
			if (region.booklet().getId() != booklet.getId()) {
				throw new IllegalArgumentException("All question regions must belong to the same booklet");
			}
		}
		if (classification != null) {
			validateClassificationForBooklet(booklet, classification);
		}
		if (sourceQuestion != null && sourceQuestion.getBooklet().getId() != booklet.getId()) {
			throw new IllegalArgumentException("Source question must belong to the question's booklet");
		}
		if (sharedContext != null && sharedContext.getBooklet().getId() != booklet.getId()) {
			throw new IllegalArgumentException("Shared question context must belong to the question's booklet");
		}
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				verifyQuestionCanAcceptRegions(connection, questionId, booklet);
				if (updateRelationships) {
					verifySourceQuestionRelationship(connection, booklet, sourceQuestion);
					verifySharedContextRelationship(connection, booklet, sharedContext);
					if (classification == null) {
						updateQuestionRelationships(connection, questionId, sourceQuestion, sharedContext);
					} else {
						verifyClassificationSyllabusUnchanged(connection, questionId, classification);
						updateQuestionCaptureDetails(connection, questionId, classification, sourceQuestion,
								sharedContext);
					}
				}
				insertRegions(connection, questionId, regions);
				connection.commit();
			} catch (SQLException | RuntimeException e) {
				try {
					connection.rollback();
				} catch (SQLException rollbackFailure) {
					e.addSuppressed(rollbackFailure);
				}
				throw e;
			}
		}
	}

	private long insertQuestionRow(Connection connection, ExamBooklet booklet, String questionCode, String questionText,
			int marks, CurriculumNode classification, boolean preambleCaptureRequired, SourceQuestion sourceQuestion,
			SharedQuestionContext sharedContext) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO questions
				    (booklet_id,
				     classification_node_id,
				     question_code,
				     question_text,
				     marks,
				     preamble_capture_required,
				     source_question_id,
				     shared_context_id)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?)
				RETURNING id
				""")) {
			statement.setLong(1, booklet.getId());
			statement.setLong(2, classification.getId());
			statement.setString(3, questionCode);
			statement.setString(4, questionText);
			statement.setInt(5, marks);
			statement.setInt(6, preambleCaptureRequired ? 1 : 0);
			if (sourceQuestion == null) {
				statement.setNull(7, Types.BIGINT);
			} else {
				statement.setLong(7, sourceQuestion.getId());
			}
			if (sharedContext == null) {
				statement.setNull(8, Types.BIGINT);
			} else {
				statement.setLong(8, sharedContext.getId());
			}
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new SQLException("Question insert did not return an id");
				}
				return result.getLong("id");
			}
		}
	}

	private void insertRegions(Connection connection, long questionId, List<QuestionRegion> regions)
			throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO question_regions
				    (question_id,
				     region_order,
				     booklet_id,
				     page_number,
				     x,
				     y,
				     width,
				     height)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?)
				""")) {
			for (int i = 0; i < regions.size(); i++) {
				QuestionRegion region = regions.get(i);
				statement.setLong(1, questionId);
				statement.setInt(2, i);
				statement.setLong(3, region.booklet().getId());
				statement.setInt(4, region.pageNumber());
				statement.setDouble(5, region.x());
				statement.setDouble(6, region.y());
				statement.setDouble(7, region.width());
				statement.setDouble(8, region.height());
				statement.executeUpdate();
			}
		}
	}

	private void updateQuestionCaptureDetails(Connection connection, long questionId, CurriculumNode classification,
			SourceQuestion sourceQuestion, SharedQuestionContext sharedContext) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				UPDATE questions
				SET classification_node_id = ?,
				    source_question_id = ?,
				    shared_context_id = ?
				WHERE id = ?
				""")) {
			statement.setLong(1, classification.getId());
			if (sourceQuestion == null) {
				statement.setNull(2, Types.BIGINT);
			} else {
				statement.setLong(2, sourceQuestion.getId());
			}
			if (sharedContext == null) {
				statement.setNull(3, Types.BIGINT);
			} else {
				statement.setLong(3, sharedContext.getId());
			}
			statement.setLong(4, questionId);
			if (statement.executeUpdate() != 1) {
				throw new SQLException("Question capture update affected an unexpected number of rows");
			}
		}
	}

	private void updateQuestionRelationships(Connection connection, long questionId, SourceQuestion sourceQuestion,
			SharedQuestionContext sharedContext) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				UPDATE questions
				SET source_question_id = ?,
				    shared_context_id = ?
				WHERE id = ?
				""")) {
			if (sourceQuestion == null) {
				statement.setNull(1, Types.BIGINT);
			} else {
				statement.setLong(1, sourceQuestion.getId());
			}
			if (sharedContext == null) {
				statement.setNull(2, Types.BIGINT);
			} else {
				statement.setLong(2, sharedContext.getId());
			}
			statement.setLong(3, questionId);
			if (statement.executeUpdate() != 1) {
				throw new SQLException("Question relationship update affected an unexpected number of rows");
			}
		}
	}

	private void validateClassificationForBooklet(ExamBooklet booklet, CurriculumNode classification) {
		if (classification == null) {
			throw new NullPointerException("classification");
		}
		CurriculumLevel level = classification.getLevel();
		if (level != CurriculumLevel.SUBTOPIC && level != CurriculumLevel.DESCRIPTOR) {
			throw new IllegalArgumentException("Question classification must be a SUBTOPIC or DESCRIPTOR");
		}
		if (!classification.getSyllabusVersion().getSubject().equals(booklet.getExam().getSubject())) {
			throw new IllegalArgumentException("Question classification must belong to the exam's subject");
		}
	}

	private void verifyClassificationSyllabusUnchanged(Connection connection, long questionId,
			CurriculumNode classification) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT cn.syllabus_version_id
				FROM questions q
				JOIN curriculum_nodes cn
				    ON cn.id = q.classification_node_id
				WHERE q.id = ?
				""")) {
			statement.setLong(1, questionId);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new IllegalArgumentException("Question does not exist: " + questionId);
				}
				long existingSyllabusId = result.getLong("syllabus_version_id");
				if (classification.getSyllabusVersion().getId() != existingSyllabusId) {
					throw new IllegalArgumentException(
							"Imported question classification must remain in its existing syllabus");
				}
			}
		}
	}

	private void verifyQuestionBooklet(Connection connection, long questionId, ExamBooklet booklet)
			throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT booklet_id
				FROM questions
				WHERE id = ?
				""")) {
			statement.setLong(1, questionId);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new IllegalArgumentException("Question does not exist: " + questionId);
				}
				if (result.getLong("booklet_id") != booklet.getId()) {
					throw new IllegalArgumentException("Capture relationships must belong to the question's booklet");
				}
			}
		}
	}

	private void verifyQuestionCanAcceptRegions(Connection connection, long questionId, ExamBooklet booklet)
			throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT
				    q.booklet_id,
				    COUNT(qr.question_id) AS region_count
				FROM questions q
				LEFT JOIN question_regions qr
				    ON qr.question_id = q.id
				WHERE q.id = ?
				GROUP BY q.id, q.booklet_id
				""")) {
			statement.setLong(1, questionId);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new IllegalArgumentException("Question does not exist: " + questionId);
				}
				if (result.getLong("booklet_id") != booklet.getId()) {
					throw new IllegalArgumentException("Question regions must belong to the question's booklet");
				}
				if (result.getInt("region_count") != 0) {
					throw new IllegalArgumentException("Question already has captured regions");
				}
			}
		}
	}

	private void verifySharedContextRelationship(Connection connection, ExamBooklet booklet,
			SharedQuestionContext sharedContext) throws SQLException {
		if (sharedContext == null) {
			return;
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT booklet_id
				FROM shared_question_contexts
				WHERE id = ?
				""")) {
			statement.setLong(1, sharedContext.getId());
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new IllegalArgumentException(
							"Shared question context does not exist: " + sharedContext.getId());
				}
				if (result.getLong("booklet_id") != booklet.getId()) {
					throw new IllegalArgumentException("Shared question context must belong to the question's booklet");
				}
			}
		}
	}

	private void verifySourceQuestionRelationship(Connection connection, ExamBooklet booklet,
			SourceQuestion sourceQuestion) throws SQLException {
		if (sourceQuestion == null) {
			return;
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT booklet_id
				FROM source_questions
				WHERE id = ?
				""")) {
			statement.setLong(1, sourceQuestion.getId());
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new IllegalArgumentException("Source question does not exist: " + sourceQuestion.getId());
				}
				if (result.getLong("booklet_id") != booklet.getId()) {
					throw new IllegalArgumentException("Source question must belong to the question's booklet");
				}
			}
		}
	}
}
