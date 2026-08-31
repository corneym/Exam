package au.edu.eq.questionbank.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;

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
	public Question insertQuestion(Exam exam, String questionCode, String questionText, List<QuestionRegion> regions,
			CurriculumNode classification) throws SQLException {

		if (exam == null) {
			throw new NullPointerException("exam");
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
				long questionId = insertQuestionRow(connection, exam, questionCode, questionText, classification);

				insertRegions(connection, questionId, regions);

				Question question = new Question(questionId, exam, questionCode, questionText, regions, classification);

				connection.commit();

				return question;

			} catch (SQLException | RuntimeException e) {

				connection.rollback();
				throw e;
			}
		}
	}

	private long insertQuestionRow(Connection connection, Exam exam, String questionCode, String questionText,
			CurriculumNode classification) throws SQLException {

		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO questions
				    (exam_id,
				     classification_node_id,
				     question_code,
				     question_text)
				VALUES (?, ?, ?, ?)
				RETURNING id
				""")) {

			statement.setLong(1, exam.getId());

			statement.setLong(2, classification.getId());

			statement.setString(3, questionCode);

			statement.setString(4, questionText);

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
}
