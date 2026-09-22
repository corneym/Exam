package au.edu.eq.questionbank.repository.assessment;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.SharedContextStatus;
import au.edu.eq.questionbank.model.SourceQuestion;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

/**
 * SQLite persistence for source-question identities.
 */
public final class SqliteSourceQuestionRepository implements SourceQuestionRepository {

	private final SqliteDatabase database;

	/**
	 * Creates a source-question repository using the supplied SQLite database.
	 *
	 * @param database the database that owns the source-question rows
	 * @throws NullPointerException if {@code database} is {@code null}
	 */
	public SqliteSourceQuestionRepository(SqliteDatabase database) {
		if (database == null) {
			throw new NullPointerException("database");
		}
		this.database = database;
	}

	/**
	 * {@inheritDoc}
	 *
	 * @throws NullPointerException  if {@code booklet} is {@code null}
	 * @throws IllegalStateException if the query fails
	 */
	@Override
	public List<SourceQuestion> findByBooklet(ExamBooklet booklet) {
		if (booklet == null) {
			throw new NullPointerException("booklet");
		}
		List<SourceQuestion> sourceQuestions = new ArrayList<>();
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT id, source_question_code, preamble_status
						FROM source_questions
						WHERE booklet_id = ?
						ORDER BY id
						""")) {
			statement.setLong(1, booklet.getId());
			try (ResultSet result = statement.executeQuery()) {
				while (result.next()) {
					sourceQuestions.add(
							new SourceQuestion(result.getLong("id"), booklet, result.getString("source_question_code"),
									SharedContextStatus.valueOf(result.getString("preamble_status"))));
				}
			}
		} catch (SQLException e) {
			throw new IllegalStateException("Could not read source questions", e);
		}
		return List.copyOf(sourceQuestions);
	}

	/**
	 * {@inheritDoc}
	 *
	 * @throws NullPointerException     if {@code booklet} is {@code null}
	 * @throws IllegalArgumentException if {@code sourceQuestionCode} is blank
	 * @throws IllegalStateException    if the query fails
	 */
	@Override
	public Optional<SourceQuestion> findByBookletAndCode(ExamBooklet booklet, String sourceQuestionCode) {
		try (Connection connection = database.openConnection()) {
			return findByBookletAndCode(connection, booklet, sourceQuestionCode);
		} catch (SQLException e) {
			throw new IllegalStateException("Could not read source question", e);
		}
	}

	/**
	 * {@inheritDoc}
	 *
	 * @throws NullPointerException     if {@code booklet} is {@code null}
	 * @throws IllegalArgumentException if the code is blank or already exists in
	 *                                  the booklet
	 * @throws IllegalStateException    if persistence fails
	 */
	@Override
	public SourceQuestion save(ExamBooklet booklet, String sourceQuestionCode) {
		try (Connection connection = database.openConnection()) {
			return save(connection, booklet, sourceQuestionCode);
		} catch (SQLException e) {
			throw new IllegalStateException("Could not save source question", e);
		}
	}

	/**
	 * {@inheritDoc}
	 *
	 * @throws NullPointerException  if either argument is {@code null}
	 * @throws IllegalStateException if the identified row does not exist or the
	 *                               update fails
	 */
	@Override
	public SourceQuestion updatePreambleStatus(SourceQuestion sourceQuestion, SharedContextStatus preambleStatus) {
		try (Connection connection = database.openConnection()) {
			return updatePreambleStatus(connection, sourceQuestion, preambleStatus);
		} catch (SQLException e) {
			throw new IllegalStateException("Could not update source question preamble status", e);
		}
	}

	// The guarded delete retains source identities still used by another question
	// part.
	boolean deleteIfUnreferenced(Connection connection, SourceQuestion sourceQuestion) throws SQLException {
		if (connection == null) {
			throw new NullPointerException("connection");
		}
		if (sourceQuestion == null) {
			throw new NullPointerException("sourceQuestion");
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				DELETE FROM source_questions
				WHERE id = ?
				  AND booklet_id = ?
				  AND NOT EXISTS (
				      SELECT 1
				      FROM questions
				      WHERE source_question_id = source_questions.id
				  )
				""")) {
			statement.setLong(1, sourceQuestion.getId());
			statement.setLong(2, sourceQuestion.getBooklet().getId());
			return statement.executeUpdate() == 1;
		}
	}

	Optional<SourceQuestion> findByBookletAndCode(Connection connection, ExamBooklet booklet, String sourceQuestionCode)
			throws SQLException {
		if (connection == null) {
			throw new NullPointerException("connection");
		}
		if (booklet == null) {
			throw new NullPointerException("booklet");
		}
		if (sourceQuestionCode == null || sourceQuestionCode.isBlank()) {
			throw new IllegalArgumentException("sourceQuestionCode must not be blank");
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT id, source_question_code, preamble_status
				FROM source_questions
				WHERE booklet_id = ?
				  AND source_question_code = ?
				""")) {
			statement.setLong(1, booklet.getId());
			statement.setString(2, sourceQuestionCode);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					return Optional.empty();
				}
				return Optional
						.of(new SourceQuestion(result.getLong("id"), booklet, result.getString("source_question_code"),
								SharedContextStatus.valueOf(result.getString("preamble_status"))));
			}
		}
	}

	SourceQuestion save(Connection connection, ExamBooklet booklet, String sourceQuestionCode) throws SQLException {
		if (connection == null) {
			throw new NullPointerException("connection");
		}
		if (booklet == null) {
			throw new NullPointerException("booklet");
		}
		if (sourceQuestionCode == null || sourceQuestionCode.isBlank()) {
			throw new IllegalArgumentException("sourceQuestionCode must not be blank");
		}
		if (findByBookletAndCode(connection, booklet, sourceQuestionCode).isPresent()) {
			throw new IllegalArgumentException("Source question already exists in this booklet: " + sourceQuestionCode);
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO source_questions
				    (booklet_id, source_question_code)
				VALUES (?, ?)
				RETURNING id, preamble_status
				""")) {
			statement.setLong(1, booklet.getId());
			statement.setString(2, sourceQuestionCode);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new SQLException("Source question insert did not return an id");
				}
				return new SourceQuestion(result.getLong("id"), booklet, sourceQuestionCode,
						SharedContextStatus.valueOf(result.getString("preamble_status")));
			}
		}
	}

	SourceQuestion updatePreambleStatus(Connection connection, SourceQuestion sourceQuestion,
			SharedContextStatus preambleStatus) throws SQLException {
		if (connection == null) {
			throw new NullPointerException("connection");
		}
		if (sourceQuestion == null) {
			throw new NullPointerException("sourceQuestion");
		}
		if (preambleStatus == null) {
			throw new NullPointerException("preambleStatus");
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				UPDATE source_questions
				SET preamble_status = ?
				WHERE id = ?
				  AND booklet_id = ?
				""")) {
			statement.setString(1, preambleStatus.name());
			statement.setLong(2, sourceQuestion.getId());
			statement.setLong(3, sourceQuestion.getBooklet().getId());
			if (statement.executeUpdate() != 1) {
				throw new IllegalStateException("Source question could not be updated: " + sourceQuestion.getId());
			}
		}
		return new SourceQuestion(sourceQuestion.getId(), sourceQuestion.getBooklet(),
				sourceQuestion.getSourceQuestionCode(), preambleStatus);
	}
}
