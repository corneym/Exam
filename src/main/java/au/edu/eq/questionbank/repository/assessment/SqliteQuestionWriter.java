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
import au.edu.eq.questionbank.model.QuestionResponseType;
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
	 * Atomically links the context to all stored parts of a source question.
	 * Conflicting existing contexts are rejected before any rows are changed.
	 *
	 * @param sourceQuestion the persisted source identity
	 * @param sharedContext  the persisted context in the same booklet
	 * @return the number of rows updated
	 * @throws SQLException             if persistence fails
	 * @throws IllegalArgumentException if relationships are invalid or conflicting
	 */
	public int applySharedContextToSourceQuestion(SourceQuestion sourceQuestion, SharedQuestionContext sharedContext)
			throws SQLException {
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				int updated = applySharedContextToSourceQuestion(connection, sourceQuestion, sharedContext);
				connection.commit();
				return updated;
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

	/**
	 * Atomically captures an empty question and replaces its classification and
	 * links. The classification must stay in the original syllabus.
	 *
	 * @param questionId     the persisted question identifier
	 * @param regions        nonempty regions in assembly order
	 * @param classification replacement classification, or null to retain it
	 * @param sourceQuestion source identity, or null to clear it
	 * @param sharedContext  context, or null to clear it
	 * @throws SQLException if persistence fails
	 */
	public void attachRegions(long questionId, List<QuestionRegion> regions, CurriculumNode classification,
			SourceQuestion sourceQuestion, SharedQuestionContext sharedContext) throws SQLException {
		attachRegionsInternal(questionId, regions, classification, sourceQuestion, sharedContext, true);
	}

	/**
	 * Atomically attaches regions and replaces links while retaining
	 * classification.
	 *
	 * @param questionId     the persisted question identifier
	 * @param regions        nonempty regions in assembly order
	 * @param sourceQuestion source identity, or null to clear it
	 * @param sharedContext  context, or null to clear it
	 * @throws SQLException if persistence fails
	 */
	public void attachRegions(long questionId, List<QuestionRegion> regions, SourceQuestion sourceQuestion,
			SharedQuestionContext sharedContext) throws SQLException {
		attachRegionsInternal(questionId, regions, null, sourceQuestion, sharedContext, true);
	}

	/**
	 * Stores a classified question and all of its regions atomically.
	 *
	 * @param booklet                      the booklet containing the question
	 * @param questionCode                 the non-blank question label
	 * @param questionText                 supplementary text, which may be blank
	 * @param marks                        the positive mark value
	 * @param regions                      zero or more source regions in extraction
	 *                                     order
	 * @param classification               the question's syllabus subtopic or
	 *                                     descriptor
	 * @param sharedContextCaptureRequired whether shared or introductory material
	 *                                     must be included during later capture
	 * @return the stored question with its generated identifier
	 * @throws SQLException             if the transaction cannot be completed
	 * @throws NullPointerException     if a required object is {@code null}
	 * @throws IllegalArgumentException if the question metadata or relationships
	 *                                  are invalid
	 */
	public Question insertQuestion(ExamBooklet booklet, String questionCode, String questionText, int marks,
			List<QuestionRegion> regions, CurriculumNode classification, boolean sharedContextCaptureRequired)
			throws SQLException {
		return insertQuestion(booklet, questionCode, questionText, marks, regions, classification,
				sharedContextCaptureRequired, null, null);
	}

	/**
	 * Stores a classified question and all of its regions atomically.
	 *
	 * @param booklet                      the booklet containing the question
	 * @param questionCode                 the non-blank question label
	 * @param questionText                 supplementary text, which may be blank
	 * @param marks                        the positive mark value
	 * @param regions                      zero or more source regions in extraction
	 *                                     order
	 * @param classification               the question's syllabus subtopic or
	 *                                     descriptor
	 * @param sharedContextCaptureRequired whether shared or introductory material
	 *                                     must be included during later capture
	 * @param sourceQuestion               optional persisted source identity for
	 *                                     related parts
	 * @param sharedContext                optional reusable shared context
	 *                                     belonging to the same booklet
	 * @return the stored question with its generated identifier
	 * @throws SQLException             if the transaction cannot be completed
	 * @throws NullPointerException     if a required object is {@code null}
	 * @throws IllegalArgumentException if the question metadata or relationships
	 *                                  are invalid
	 */
	public Question insertQuestion(ExamBooklet booklet, String questionCode, String questionText, int marks,
			List<QuestionRegion> regions, CurriculumNode classification, boolean sharedContextCaptureRequired,
			SourceQuestion sourceQuestion, SharedQuestionContext sharedContext) throws SQLException {
		return insertQuestion(booklet, questionCode, questionText, marks, regions, classification,
				sharedContextCaptureRequired, sourceQuestion, sharedContext, QuestionResponseType.UNKNOWN);
	}

	/**
	 * Stores a classified question with its authoritative response type and all
	 * source regions atomically.
	 *
	 * @param booklet                      booklet containing the question
	 * @param questionCode                 question identifier
	 * @param questionText                 supplementary text
	 * @param marks                        positive mark value
	 * @param regions                      ordered source regions
	 * @param classification               original curriculum classification
	 * @param sharedContextCaptureRequired historical shared context-capture
	 *                                     evidence
	 * @param sourceQuestion               source-question identity, or null
	 * @param sharedContext                shared context, or null
	 * @param responseType                 authoritative response type
	 * @return stored question
	 * @throws SQLException if persistence fails
	 */
	public Question insertQuestion(ExamBooklet booklet, String questionCode, String questionText, int marks,
			List<QuestionRegion> regions, CurriculumNode classification, boolean sharedContextCaptureRequired,
			SourceQuestion sourceQuestion, SharedQuestionContext sharedContext, QuestionResponseType responseType)
			throws SQLException {
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				Question question = insertQuestion(connection, booklet, questionCode, questionText, marks, regions,
						classification, sharedContextCaptureRequired, sourceQuestion, sharedContext, responseType);
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

	/**
	 * Atomically replaces capture links and classification, preserving stored
	 * regions. The question must belong to the booklet and retain its existing
	 * syllabus.
	 *
	 * @param questionId     the persisted question identifier
	 * @param booklet        the existing source booklet
	 * @param classification replacement subtopic or descriptor
	 * @param sourceQuestion source identity, or null to clear it
	 * @param sharedContext  context, or null to clear it
	 * @throws SQLException if persistence fails
	 */
	public void updateCaptureRelationships(long questionId, ExamBooklet booklet, CurriculumNode classification,
			SourceQuestion sourceQuestion, SharedQuestionContext sharedContext) throws SQLException {
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				updateCaptureRelationships(connection, questionId, booklet, classification, sourceQuestion,
						sharedContext);
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

	/**
	 * Updates only a Question's historical classification in one transaction.
	 *
	 * @param questionId     persistent Question identifier
	 * @param booklet        Question's owning booklet
	 * @param classification replacement classification
	 * @throws SQLException if the update cannot be committed
	 */
	public void updateClassification(long questionId, ExamBooklet booklet, CurriculumNode classification)
			throws SQLException {
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				updateClassification(connection, questionId, booklet, classification);
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

	/**
	 * Atomically replaces editable metadata and ordered regions, retaining the
	 * question identity, booklet, text, legacy evidence and answer. A failed region
	 * insert rolls back both the metadata update and deletion of old regions.
	 *
	 * @param questionId     the persisted question identifier
	 * @param booklet        the existing source booklet
	 * @param questionCode   replacement non-blank code
	 * @param marks          replacement positive marks
	 * @param regions        nonempty replacement regions in assembly order
	 * @param classification replacement classification in the existing syllabus
	 * @param sourceQuestion source identity, or null to clear it
	 * @param sharedContext  context, or null to clear it
	 * @throws SQLException if persistence fails
	 */
	public void updateQuestion(long questionId, ExamBooklet booklet, String questionCode, int marks,
			List<QuestionRegion> regions, CurriculumNode classification, SourceQuestion sourceQuestion,
			SharedQuestionContext sharedContext) throws SQLException {
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {

				// This public update preserves the stored response type. Reject a marks
				// change that would therefore turn an existing MCQ into invalid data.
				verifyUpdatedMarksCompatibleWithStoredResponseType(connection, questionId, marks);
				updateQuestion(connection, questionId, booklet, questionCode, marks, regions, classification,
						sourceQuestion, sharedContext);
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

	int applySharedContextToSourceQuestion(Connection connection, SourceQuestion sourceQuestion,
			SharedQuestionContext sharedContext) throws SQLException {
		if (connection == null) {
			throw new NullPointerException("connection");
		}
		if (sourceQuestion == null) {
			throw new NullPointerException("sourceQuestion");
		}
		if (sharedContext == null) {
			throw new NullPointerException("sharedContext");
		}
		ExamBooklet booklet = sourceQuestion.getBooklet();
		if (sharedContext.getBooklet().getId() != booklet.getId()) {
			throw new IllegalArgumentException("Shared question context must belong to the source question's booklet");
		}
		verifySourceQuestionRelationship(connection, booklet, sourceQuestion);
		verifySharedContextRelationship(connection, booklet, sharedContext);

		// Reject conflicting sibling links before filling any missing links.
		verifySourceQuestionSharedContextConsistency(connection, sourceQuestion, sharedContext);
		return updateSourceQuestionSharedContexts(connection, sourceQuestion, sharedContext);
	}

	void attachRegions(Connection connection, long questionId, List<QuestionRegion> regions,
			CurriculumNode classification, SourceQuestion sourceQuestion, SharedQuestionContext sharedContext)
			throws SQLException {
		attachRegionsInternal(connection, questionId, regions, classification, sourceQuestion, sharedContext, true);
	}

	Question insertQuestion(Connection connection, ExamBooklet booklet, String questionCode, String questionText,
			int marks, List<QuestionRegion> regions, CurriculumNode classification,
			boolean sharedContextCaptureRequired, SourceQuestion sourceQuestion, SharedQuestionContext sharedContext)
			throws SQLException {
		return insertQuestion(connection, booklet, questionCode, questionText, marks, regions, classification,
				sharedContextCaptureRequired, sourceQuestion, sharedContext, QuestionResponseType.UNKNOWN);
	}

	Question insertQuestion(Connection connection, ExamBooklet booklet, String questionCode, String questionText,
			int marks, List<QuestionRegion> regions, CurriculumNode classification,
			boolean sharedContextCaptureRequired, SourceQuestion sourceQuestion, SharedQuestionContext sharedContext,
			QuestionResponseType responseType) throws SQLException {
		if (connection == null) {
			throw new NullPointerException("connection");
		}
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
		if (responseType == null) {
			throw new NullPointerException("responseType");
		}
		if (responseType == QuestionResponseType.MULTIPLE_CHOICE && marks != 1) {

			// Reject inconsistent capture metadata before any persistence transaction can
			// begin, including edit and imported-question workflows.
			throw new IllegalArgumentException("Multiple-choice questions must be worth exactly 1 mark");
		}
		if (sourceQuestion != null && sourceQuestion.getBooklet().getId() != booklet.getId()) {
			throw new IllegalArgumentException("Source question must belong to the question's booklet");
		}
		if (sharedContext != null && sharedContext.getBooklet().getId() != booklet.getId()) {
			throw new IllegalArgumentException("Shared question context must belong to the question's booklet");
		}
		verifySourceQuestionRelationship(connection, booklet, sourceQuestion);
		verifySharedContextRelationship(connection, booklet, sharedContext);

		// Use the caller transaction for the row, regions and domain validation below.
		long questionId = insertQuestionRow(connection, booklet, questionCode, questionText, marks, classification,
				sharedContextCaptureRequired, sourceQuestion, sharedContext, responseType);
		insertRegions(connection, questionId, regions);
		return new Question(questionId, booklet, questionCode, questionText, marks, regions, classification,
				sharedContextCaptureRequired, sourceQuestion, sharedContext, responseType);
	}

	void updateCaptureRelationships(Connection connection, long questionId, ExamBooklet booklet,
			CurriculumNode classification, SourceQuestion sourceQuestion, SharedQuestionContext sharedContext)
			throws SQLException {
		if (connection == null) {
			throw new NullPointerException("connection");
		}
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
		verifyQuestionBooklet(connection, questionId, booklet);
		verifyClassificationSyllabusUnchanged(connection, questionId, classification);
		verifySourceQuestionRelationship(connection, booklet, sourceQuestion);
		verifySharedContextRelationship(connection, booklet, sharedContext);
		updateQuestionCaptureDetails(connection, questionId, classification, sourceQuestion, sharedContext);
	}

	void updateClassification(Connection connection, long questionId, ExamBooklet booklet,
			CurriculumNode classification) throws SQLException {
		if (connection == null) {
			throw new NullPointerException("connection");
		}
		if (questionId < 1) {
			throw new IllegalArgumentException("questionId must be positive");
		}
		if (booklet == null) {
			throw new NullPointerException("booklet");
		}
		validateClassificationForBooklet(booklet, classification);
		verifyQuestionBooklet(connection, questionId, booklet);
		verifyClassificationSyllabusUnchanged(connection, questionId, classification);
		try (PreparedStatement statement = connection.prepareStatement("""
				UPDATE questions
				SET classification_node_id = ?
				WHERE id = ?
				""")) {
			statement.setLong(1, classification.getId());
			statement.setLong(2, questionId);
			if (statement.executeUpdate() != 1) {
				throw new SQLException("Question classification update affected an unexpected number of rows");
			}
		}
	}

	void updateQuestion(Connection connection, long questionId, ExamBooklet booklet, String questionCode, int marks,
			List<QuestionRegion> regions, CurriculumNode classification, SourceQuestion sourceQuestion,
			SharedQuestionContext sharedContext) throws SQLException {
		if (connection == null) {
			throw new NullPointerException("connection");
		}
		if (questionId < 1) {
			throw new IllegalArgumentException("questionId must be positive");
		}
		if (booklet == null) {
			throw new NullPointerException("booklet");
		}
		if (questionCode == null || questionCode.isBlank()) {
			throw new IllegalArgumentException("questionCode must not be blank");
		}
		if (marks < 1) {
			throw new IllegalArgumentException("marks must be positive");
		}
		if (regions == null) {
			throw new NullPointerException("regions");
		}
		if (regions.isEmpty()) {
			throw new IllegalArgumentException("regions must not be empty");
		}
		for (QuestionRegion region : regions) {
			if (region == null) {
				throw new NullPointerException("regions contains null");
			}
			if (region.booklet().getId() != booklet.getId()) {
				throw new IllegalArgumentException("All question regions must belong to the question's booklet");
			}
		}
		validateClassificationForBooklet(booklet, classification);
		if (sourceQuestion != null && sourceQuestion.getBooklet().getId() != booklet.getId()) {
			throw new IllegalArgumentException("Source question must belong to the question's booklet");
		}
		if (sharedContext != null && sharedContext.getBooklet().getId() != booklet.getId()) {
			throw new IllegalArgumentException("Shared question context must belong to the question's booklet");
		}
		verifyQuestionBooklet(connection, questionId, booklet);
		verifyClassificationSyllabusUnchanged(connection, questionId, classification);
		verifySourceQuestionRelationship(connection, booklet, sourceQuestion);
		verifySharedContextRelationship(connection, booklet, sharedContext);
		updateQuestionEditableDetails(connection, questionId, questionCode, marks, classification, sourceQuestion,
				sharedContext);

		// Replace region order within the same transaction as the metadata update.
		deleteQuestionRegions(connection, questionId);
		insertRegions(connection, questionId, regions);
	}

	void updateResponseType(Connection connection, long questionId, ExamBooklet booklet,
			QuestionResponseType responseType) throws SQLException {
		if (connection == null) {
			throw new NullPointerException("connection");
		}
		if (questionId < 1) {
			throw new IllegalArgumentException("questionId must be positive");
		}
		if (booklet == null) {
			throw new NullPointerException("booklet");
		}
		if (responseType == null) {
			throw new NullPointerException("responseType");
		}
		verifyQuestionBooklet(connection, questionId, booklet);
		if (responseType == QuestionResponseType.MULTIPLE_CHOICE) {

			// Response-type changes occur after any marks edit in the same transaction.
			// Refuse to commit MCQ metadata unless the stored final mark value is one.
			verifyStoredQuestionHasOneMark(connection, questionId);
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				UPDATE questions
				SET response_type = ?
				WHERE id = ?
				""")) {
			statement.setString(1, responseType.name());
			statement.setLong(2, questionId);
			if (statement.executeUpdate() != 1) {
				throw new SQLException("Question response-type update affected an unexpected number of rows");
			}
		}
	}

	private void attachRegionsInternal(Connection connection, long questionId, List<QuestionRegion> regions,
			CurriculumNode classification, SourceQuestion sourceQuestion, SharedQuestionContext sharedContext,
			boolean updateRelationships) throws SQLException {
		if (connection == null) {
			throw new NullPointerException("connection");
		}
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

		// Attaching is allowed only for an empty capture; it must never replace
		// existing regions.
		verifyQuestionCanAcceptRegions(connection, questionId, booklet);
		if (updateRelationships) {
			verifySourceQuestionRelationship(connection, booklet, sourceQuestion);
			verifySharedContextRelationship(connection, booklet, sharedContext);
			if (classification == null) {
				updateQuestionRelationships(connection, questionId, sourceQuestion, sharedContext);
			} else {
				verifyClassificationSyllabusUnchanged(connection, questionId, classification);
				updateQuestionCaptureDetails(connection, questionId, classification, sourceQuestion, sharedContext);
			}
		}
		insertRegions(connection, questionId, regions);
	}

	private void attachRegionsInternal(long questionId, List<QuestionRegion> regions, CurriculumNode classification,
			SourceQuestion sourceQuestion, SharedQuestionContext sharedContext, boolean updateRelationships)
			throws SQLException {
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				attachRegionsInternal(connection, questionId, regions, classification, sourceQuestion, sharedContext,
						updateRelationships);
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

	private void deleteQuestionRegions(Connection connection, long questionId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				DELETE FROM question_regions
				WHERE question_id = ?
				""")) {
			statement.setLong(1, questionId);
			statement.executeUpdate();
		}
	}

	private long insertQuestionRow(Connection connection, ExamBooklet booklet, String questionCode, String questionText,
			int marks, CurriculumNode classification, boolean sharedContextCaptureRequired,
			SourceQuestion sourceQuestion, SharedQuestionContext sharedContext, QuestionResponseType responseType)
			throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO questions
				    (booklet_id,
				     classification_node_id,
				     question_code,
				     question_text,
				     marks,
				     shared_context_capture_required,
				     source_question_id,
				     shared_context_id,
				     response_type)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
				RETURNING id
				""")) {
			statement.setLong(1, booklet.getId());
			statement.setLong(2, classification.getId());
			statement.setString(3, questionCode);
			statement.setString(4, questionText);
			statement.setInt(5, marks);
			statement.setInt(6, sharedContextCaptureRequired ? 1 : 0);
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
			statement.setString(9, responseType.name());
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

			// Store assembly order separately from the unchanged one-based PDF page number.
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

	private void updateQuestionEditableDetails(Connection connection, long questionId, String questionCode, int marks,
			CurriculumNode classification, SourceQuestion sourceQuestion, SharedQuestionContext sharedContext)
			throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				UPDATE questions
				SET question_code = ?,
				    marks = ?,
				    classification_node_id = ?,
				    source_question_id = ?,
				    shared_context_id = ?
				WHERE id = ?
				""")) {
			statement.setString(1, questionCode);
			statement.setInt(2, marks);
			statement.setLong(3, classification.getId());
			if (sourceQuestion == null) {
				statement.setNull(4, Types.BIGINT);
			} else {
				statement.setLong(4, sourceQuestion.getId());
			}
			if (sharedContext == null) {
				statement.setNull(5, Types.BIGINT);
			} else {
				statement.setLong(5, sharedContext.getId());
			}
			statement.setLong(6, questionId);
			if (statement.executeUpdate() != 1) {
				throw new SQLException("Question update affected an unexpected number of rows");
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

	private int updateSourceQuestionSharedContexts(Connection connection, SourceQuestion sourceQuestion,
			SharedQuestionContext sharedContext) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				UPDATE questions
				SET shared_context_id = ?
				WHERE source_question_id = ?
				  AND shared_context_id IS NULL
				""")) {
			statement.setLong(1, sharedContext.getId());
			statement.setLong(2, sourceQuestion.getId());
			return statement.executeUpdate();
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

	private void verifySourceQuestionSharedContextConsistency(Connection connection, SourceQuestion sourceQuestion,
			SharedQuestionContext sharedContext) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT id, booklet_id, shared_context_id
				FROM questions
				WHERE source_question_id = ?
				""")) {
			statement.setLong(1, sourceQuestion.getId());
			try (ResultSet result = statement.executeQuery()) {
				while (result.next()) {
					long questionId = result.getLong("id");
					if (result.getLong("booklet_id") != sourceQuestion.getBooklet().getId()) {
						throw new IllegalStateException(
								"Question " + questionId + " is linked to a source question from another booklet");
					}
					long existingContextId = result.getLong("shared_context_id");
					if (!result.wasNull() && existingContextId != sharedContext.getId()) {
						throw new IllegalStateException("Source question " + sourceQuestion.getSourceQuestionCode()
								+ " has inconsistent shared context links");
					}
				}
			}
		}
	}

	private void verifyStoredQuestionHasOneMark(Connection connection, long questionId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT marks
				FROM questions
				WHERE id = ?
				""")) {
			statement.setLong(1, questionId);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new IllegalArgumentException("Question does not exist: " + questionId);
				}

				// A Question may become MULTIPLE_CHOICE only when its final persisted mark
				// value already satisfies the MCQ invariant.
				if (result.getInt("marks") != 1) {
					throw new IllegalArgumentException("Multiple-choice questions must be worth exactly 1 mark");
				}
			}
		}
	}

	private void verifyUpdatedMarksCompatibleWithStoredResponseType(Connection connection, long questionId, int marks)
			throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT response_type
				FROM questions
				WHERE id = ?
				""")) {
			statement.setLong(1, questionId);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new IllegalArgumentException("Question does not exist: " + questionId);
				}
				QuestionResponseType storedResponseType = QuestionResponseType
						.valueOf(result.getString("response_type"));

				// A marks-only update preserves response_type, so an existing MCQ may
				// never be changed away from its mandatory one-mark value.
				if (storedResponseType == QuestionResponseType.MULTIPLE_CHOICE && marks != 1) {
					throw new IllegalArgumentException("Multiple-choice questions must be worth exactly 1 mark");
				}
			}
		}
	}
}
