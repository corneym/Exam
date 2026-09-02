package au.edu.eq.questionbank.repository.assessment;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
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

	public void attachRegions(long questionId, List<QuestionRegion> regions) throws SQLException {
		if (questionId < 1) {
			throw new IllegalArgumentException("questionId must be positive");
		}
		if (regions == null) {
			throw new NullPointerException("regions");
		}
		if (regions.isEmpty()) {
			throw new IllegalArgumentException("regions must not be empty");
		}
		ExamBooklet booklet = regions.getFirst().booklet();
		for (QuestionRegion region : regions) {
			if (region == null) {
				throw new NullPointerException("regions contains null");
			}
			if (region.booklet().getId() != booklet.getId()) {
				throw new IllegalArgumentException("All question regions must belong to the same booklet");
			}
		}
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				verifyQuestionCanAcceptRegions(connection, questionId, booklet);
				insertRegions(connection, questionId, regions);
				connection.commit();
			} catch (SQLException | RuntimeException e) {
				connection.rollback();
				throw e;
			}
		}
	}

	/**
	 * Stores a classified question and all of its regions atomically.
	 *
	 * @param exam           the exam containing the question
	 * @param questionCode   the non-blank question label
	 * @param questionText   supplementary text, which may be blank
	 * @param regions        source regions in extraction order
	 * @param classification the question's syllabus subtopic or descriptor
	 * @return the stored question with its generated identifier
	 * @throws SQLException             if the transaction cannot be completed
	 * @throws NullPointerException     if a required object is {@code null}
	 * @throws IllegalArgumentException if the question metadata or relationships
	 *                                  are invalid
	 */
	public Question insertQuestion(ExamBooklet booklet, String questionCode, String questionText, int marks,
			List<QuestionRegion> regions, CurriculumNode classification, boolean preambleCaptureRequired)
			throws SQLException {

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
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				long questionId = insertQuestionRow(connection, booklet, questionCode, questionText, marks,
						classification, preambleCaptureRequired);
				insertRegions(connection, questionId, regions);
				Question question = new Question(questionId, booklet, questionCode, questionText, marks, regions,
						classification, preambleCaptureRequired);
				connection.commit();
				return question;
			} catch (SQLException | RuntimeException e) {
				connection.rollback();
				throw e;
			}
		}
	}

	private long insertQuestionRow(Connection connection, ExamBooklet booklet, String questionCode, String questionText,
			int marks, CurriculumNode classification, boolean preambleCaptureRequired) throws SQLException {

		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO questions
				    (booklet_id,
				     classification_node_id,
				     question_code,
				     question_text,
				     marks,
				     preamble_capture_required)
				VALUES (?, ?, ?, ?, ?, ?)
				RETURNING id
				""")) {
			statement.setLong(1, booklet.getId());
			statement.setLong(2, classification.getId());
			statement.setString(3, questionCode);
			statement.setString(4, questionText);
			statement.setInt(5, marks);
			statement.setInt(6, preambleCaptureRequired ? 1 : 0);
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
}
