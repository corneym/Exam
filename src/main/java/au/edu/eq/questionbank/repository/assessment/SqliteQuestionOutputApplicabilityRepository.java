package au.edu.eq.questionbank.repository.assessment;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;
import java.util.TreeSet;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

/**
 * SQLite persistence for explicit Question-specific output exclusions.
 */
public final class SqliteQuestionOutputApplicabilityRepository implements QuestionOutputApplicabilityRepository {

	private final SqliteDatabase database;

	/**
	 * Creates an output-applicability repository backed by the supplied database.
	 *
	 * @param database initialised Question Bank database
	 * @throws NullPointerException if {@code database} is {@code null}
	 */
	public SqliteQuestionOutputApplicabilityRepository(SqliteDatabase database) {
		if (database == null) {
			throw new NullPointerException("database");
		}
		this.database = database;
	}

	@Override
	public Set<Long> findExcludedCurrentNodeIds(Question question) {
		validateQuestion(question);
		Set<Long> excludedNodeIds = new TreeSet<>();
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT current_curriculum_node_id
						FROM question_output_exclusions
						WHERE question_id = ?
						ORDER BY current_curriculum_node_id
						""")) {
			statement.setLong(1, question.getId());
			try (ResultSet result = statement.executeQuery()) {
				while (result.next()) {

					// Persist only node identifiers here. Curriculum reconstruction
					// remains the responsibility of the curriculum repository.
					excludedNodeIds.add(result.getLong("current_curriculum_node_id"));
				}
			}
			return Set.copyOf(excludedNodeIds);
		} catch (SQLException exception) {
			throw new IllegalStateException("Could not read Question output exclusions", exception);
		}
	}

	@Override
	public void setExcluded(Question question, CurriculumNode currentNode, boolean excluded) {
		validateQuestion(question);
		validateCurrentNode(question, currentNode);
		try (Connection connection = database.openConnection()) {
			if (excluded) {
				insertExclusion(connection, question, currentNode);
			} else {
				deleteExclusion(connection, question, currentNode);
			}
		} catch (SQLException exception) {
			throw new IllegalStateException("Could not update Question output exclusion", exception);
		}
	}

	private void deleteExclusion(Connection connection, Question question, CurriculumNode currentNode)
			throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				DELETE FROM question_output_exclusions
				WHERE question_id = ?
				  AND current_curriculum_node_id = ?
				""")) {
			statement.setLong(1, question.getId());
			statement.setLong(2, currentNode.getId());

			// Deleting an exclusion that is already absent is deliberately harmless.
			statement.executeUpdate();
		}
	}

	private void insertExclusion(Connection connection, Question question, CurriculumNode currentNode)
			throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT OR IGNORE INTO question_output_exclusions
				    (question_id, current_curriculum_node_id)
				VALUES (?, ?)
				""")) {
			statement.setLong(1, question.getId());
			statement.setLong(2, currentNode.getId());

			// The composite primary key makes repeated exclusion requests idempotent.
			statement.executeUpdate();
		}
	}

	private void validateCurrentNode(Question question, CurriculumNode currentNode) {
		if (currentNode == null) {
			throw new NullPointerException("currentNode");
		}
		if (currentNode.getId() < 1) {
			throw new IllegalArgumentException("currentNode must have a persistent identifier");
		}
		if (!currentNode.getSyllabusVersion().isCurrent()) {
			throw new IllegalArgumentException("Output exclusion requires a current curriculum node");
		}
		CurriculumLevel level = currentNode.getLevel();
		if (level != CurriculumLevel.SUBTOPIC && level != CurriculumLevel.DESCRIPTOR) {
			throw new IllegalArgumentException("Output exclusion requires a Subtopic or Descriptor");
		}
		if (!question.getExam().getSubject().equals(currentNode.getSyllabusVersion().getSubject())) {
			throw new IllegalArgumentException("Question and current curriculum node must belong to the same Subject");
		}
	}

	private void validateQuestion(Question question) {
		if (question == null) {
			throw new NullPointerException("question");
		}
		if (question.getId() < 1) {
			throw new IllegalArgumentException("question must have a persistent identifier");
		}
	}
}
