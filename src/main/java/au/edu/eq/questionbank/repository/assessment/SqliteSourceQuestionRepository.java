package au.edu.eq.questionbank.repository.assessment;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.SourceQuestion;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

/**
 * SQLite persistence for source-question identities.
 */
public final class SqliteSourceQuestionRepository implements SourceQuestionRepository {

	private final SqliteDatabase database;

	public SqliteSourceQuestionRepository(SqliteDatabase database) {
		if (database == null) {
			throw new NullPointerException("database");
		}
		this.database = database;
	}

	@Override
	public List<SourceQuestion> findByBooklet(ExamBooklet booklet) {
		if (booklet == null) {
			throw new NullPointerException("booklet");
		}
		List<SourceQuestion> sourceQuestions = new ArrayList<>();
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT id, source_question_code
						FROM source_questions
						WHERE booklet_id = ?
						ORDER BY id
						""")) {
			statement.setLong(1, booklet.getId());
			try (ResultSet result = statement.executeQuery()) {
				while (result.next()) {
					sourceQuestions.add(new SourceQuestion(result.getLong("id"), booklet,
							result.getString("source_question_code")));
				}
			}
		} catch (SQLException e) {
			throw new IllegalStateException("Could not read source questions", e);
		}
		return List.copyOf(sourceQuestions);
	}

	@Override
	public Optional<SourceQuestion> findByBookletAndCode(ExamBooklet booklet, String sourceQuestionCode) {
		if (booklet == null) {
			throw new NullPointerException("booklet");
		}
		if (sourceQuestionCode == null || sourceQuestionCode.isBlank()) {
			throw new IllegalArgumentException("sourceQuestionCode must not be blank");
		}
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT id, source_question_code
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
				return Optional.of(
						new SourceQuestion(result.getLong("id"), booklet, result.getString("source_question_code")));
			}
		} catch (SQLException e) {
			throw new IllegalStateException("Could not read source question", e);
		}
	}

	@Override
	public SourceQuestion save(ExamBooklet booklet, String sourceQuestionCode) {
		if (booklet == null) {
			throw new NullPointerException("booklet");
		}
		if (sourceQuestionCode == null || sourceQuestionCode.isBlank()) {
			throw new IllegalArgumentException("sourceQuestionCode must not be blank");
		}
		if (findByBookletAndCode(booklet, sourceQuestionCode).isPresent()) {
			throw new IllegalArgumentException("Source question already exists in this booklet: " + sourceQuestionCode);
		}
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						INSERT INTO source_questions
						    (booklet_id, source_question_code)
						VALUES (?, ?)
						RETURNING id
						""")) {
			statement.setLong(1, booklet.getId());
			statement.setString(2, sourceQuestionCode);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new SQLException("Source question insert did not return an id");
				}
				return new SourceQuestion(result.getLong("id"), booklet, sourceQuestionCode);
			}
		} catch (SQLException e) {
			throw new IllegalStateException("Could not save source question", e);
		}
	}
}
