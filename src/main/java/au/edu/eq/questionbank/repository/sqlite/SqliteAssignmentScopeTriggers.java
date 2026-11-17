package au.edu.eq.questionbank.repository.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Installs and verifies database-level contributor capture restrictions.
 *
 * <p>
 * While a contributor assignment is selected, each captured row must belong to
 * its assigned Exam. Classification nodes must belong to the expressly
 * permitted current or original 2019 syllabus.
 *
 * <p>
 * INSERT checks the incoming row, DELETE checks the original row, and UPDATE
 * checks both. This prevents moving records across assignment boundaries or
 * changing unassigned records.
 *
 * <p>
 * The triggers are independent of JavaFX and protect direct repository writes
 * through every SQLite connection.
 */
final class SqliteAssignmentScopeTriggers {

	private static final List<String> TABLES = List.of("questions", "question_regions", "question_images",
			"question_content_parts", "question_output_exclusions", "source_questions", "shared_question_contexts",
			"shared_question_context_regions", "shared_question_context_images",
			"shared_question_context_content_parts", "answers", "answer_regions", "booklet_applicability_reviews");
	private static final List<String> OPERATIONS = List.of("insert", "update", "delete");
	private static final String EXAM_QUERY = """
			SELECT session.exam_local_id
			FROM contributor_assignment_sessions session
			JOIN contributor_assignment_selection selected
			    ON selected.assignment_id = session.assignment_id
			WHERE selected.singleton_id = 1
			""";
	private static final String SYLLABUS_QUERY = """
			SELECT session.current_syllabus_local_id
			FROM contributor_assignment_sessions session
			JOIN contributor_assignment_selection selected
			    ON selected.assignment_id = session.assignment_id
			WHERE selected.singleton_id = 1

			UNION

			SELECT session.legacy_syllabus_local_id
			FROM contributor_assignment_sessions session
			JOIN contributor_assignment_selection selected
			    ON selected.assignment_id = session.assignment_id
			WHERE selected.singleton_id = 1
			  AND session.legacy_syllabus_local_id IS NOT NULL
			""";

	private SqliteAssignmentScopeTriggers() {
	}

	static void install(Connection connection) throws SQLException {
		Objects.requireNonNull(connection, "connection");

		// Schema version 31 installs the original scope definitions.
		// Classification-level enforcement is introduced by version 33.
		try (Statement statement = connection.createStatement()) {
			for (String table : TABLES) {
				for (String operation : OPERATIONS) {
					statement.execute(definition(table, operation, false));
				}
			}
		}
		verifyLegacy(connection);
	}

	static void verify(Connection connection) throws SQLException {
		verify(connection, true);
	}

	static void verifyLegacy(Connection connection) throws SQLException {
		verify(connection, false);
	}

	private static String answer(String answerId) {
		return """
				EXISTS (
				    SELECT 1
				    FROM answers answer
				    JOIN questions question
				        ON question.id = answer.question_id
				    JOIN exam_booklets booklet
				        ON booklet.id = question.booklet_id
				    WHERE answer.id = %s
				      AND booklet.exam_id IN (%s)
				)
				""".formatted(answerId, EXAM_QUERY);
	}

	private static String booklet(String bookletId) {
		return """
				EXISTS (
				    SELECT 1
				    FROM exam_booklets booklet
				    WHERE booklet.id = %s
				      AND booklet.exam_id IN (%s)
				)
				""".formatted(bookletId, EXAM_QUERY);
	}

	private static String classifiable(String nodeId) {
		return """
				EXISTS (
				    SELECT 1
				    FROM curriculum_nodes node
				    WHERE node.id = %s
				      AND node.curriculum_level IN (
				          'SUBTOPIC', 'DESCRIPTOR'
				      )
				)
				""".formatted(nodeId);
	}

	private static String classification(String nodeId) {
		return """
				EXISTS (
				    SELECT 1
				    FROM curriculum_nodes node
				    WHERE node.id = %s
				      AND node.syllabus_version_id IN (%s)
				)
				""".formatted(nodeId, SYLLABUS_QUERY);
	}

	private static String context(String contextId) {
		return """
				EXISTS (
				    SELECT 1
				    FROM shared_question_contexts context
				    JOIN exam_booklets booklet
				        ON booklet.id = context.booklet_id
				    WHERE context.id = %s
				      AND booklet.exam_id IN (%s)
				)
				""".formatted(contextId, EXAM_QUERY);
	}

	private static String definition(String table, String operation, boolean enforceClassificationLevels) {
		String permitted = switch (operation) {
		case "insert" -> predicate(table, "NEW");
		case "delete" -> deletionPredicate(table, "OLD");
		case "update" -> "(" + originalPredicate(table, "OLD") + ") AND (" + predicate(table, "NEW") + ")";
		default -> throw new IllegalArgumentException("Unsupported contributor scope operation");
		};
		if (enforceClassificationLevels && table.equals("questions")) {
			if (operation.equals("insert")) {

				// Every newly captured Question needs a classifiable node.
				permitted = "(" + permitted + ") AND (" + classifiable("NEW.classification_node_id") + ")";
			} else if (operation.equals("update")) {

				// Preserve editing of historical Questions whose existing
				// classifications predate enforcement. Any deliberate
				// reclassification must use a Subtopic or Descriptor.
				String validClassification = """
						NEW.classification_node_id = OLD.classification_node_id
						OR (%s)
						""".formatted(classifiable("NEW.classification_node_id"));
				permitted = "(" + permitted + ") AND (" + validClassification + ")";
			}
		}
		return """
				CREATE TRIGGER %s
				BEFORE %s ON %s
				WHEN (
				    SELECT assignment_id
				    FROM contributor_assignment_selection
				    WHERE singleton_id = 1
				) IS NOT NULL
				AND NOT (%s)
				BEGIN
				    SELECT RAISE(
				        ABORT,
				        'Capture or classification is outside selected assignment'
				    );
				END
				""".formatted(name(table, operation), operation.toUpperCase(Locale.ROOT), table, permitted).strip();
	}

	private static String deletionPredicate(String table, String row) {
		String parentTable;
		String parentColumn;
		switch (table) {
		case "questions", "source_questions", "shared_question_contexts", "booklet_applicability_reviews" -> {
			parentTable = "exam_booklets";
			parentColumn = "booklet_id";
		}
		case "question_regions", "question_images", "question_content_parts", "question_output_exclusions",
				"answers" -> {
			parentTable = "questions";
			parentColumn = "question_id";
		}
		case "answer_regions" -> {
			parentTable = "answers";
			parentColumn = "answer_id";
		}
		case "shared_question_context_regions", "shared_question_context_images",
				"shared_question_context_content_parts" -> {
			parentTable = "shared_question_contexts";
			parentColumn = "shared_context_id";
		}
		default -> throw new IllegalArgumentException("Unsupported scoped deletion table: " + table);
		}

		// When SQLite cascades a parent DELETE, its child DELETE
		// trigger may execute after the parent row has disappeared.
		// Foreign keys prevent independently stored orphan rows,
		// while parent DELETE triggers check the original scope.
		String missingParent = """
				NOT EXISTS (
				    SELECT 1
				    FROM %s parent
				    WHERE parent.id = %s.%s
				)
				""".formatted(parentTable, row, parentColumn);
		return "(" + missingParent + ") OR (" + predicate(table, row) + ")";
	}

	private static String name(String table, String operation) {
		return "trg_contributor_scope_" + table + "_" + operation;
	}

	private static String normalise(String definition) {
		return Objects.requireNonNull(definition, "definition").replaceAll("\\s+", " ").strip();
	}

	private static String originalPredicate(String table, String row) {
		if (table.equals("questions")) {

			// A parent SourceQuestion or Shared Context may have
			// just been deleted and its FK set to NULL by SQLite.
			// Verify the original Question's Exam and classification,
			// then validate all relationships on the NEW row.
			return booklet(row + ".booklet_id") + " AND " + classification(row + ".classification_node_id");
		}
		return predicate(table, row);
	}

	private static String predicate(String table, String row) {
		return switch (table) {
		case "questions" -> booklet(row + ".booklet_id") + " AND " + classification(row + ".classification_node_id")
				+ " AND (" + row + ".source_question_id IS NULL OR " + """
						EXISTS (
						    SELECT 1
						    FROM source_questions source
						    WHERE source.id = %s.source_question_id
						      AND source.booklet_id = %s.booklet_id
						)
						""".formatted(row, row) + ") AND (" + row + ".shared_context_id IS NULL OR " + """
						EXISTS (
						    SELECT 1
						    FROM shared_question_contexts context
						    WHERE context.id = %s.shared_context_id
						      AND context.booklet_id = %s.booklet_id
						)
						""".formatted(row, row) + ")";
		case "question_regions" -> question(row + ".question_id") + " AND " + """
				EXISTS (
				    SELECT 1
				    FROM questions question
				    WHERE question.id = %s.question_id
				      AND question.booklet_id = %s.booklet_id
				)
				""".formatted(row, row);
		case "question_images", "question_content_parts" ->

			// Composite foreign keys already require image
			// and region references to belong to this Question.
			question(row + ".question_id");
		case "question_output_exclusions" ->
			question(row + ".question_id") + " AND " + classification(row + ".current_curriculum_node_id");
		case "source_questions", "shared_question_contexts" -> booklet(row + ".booklet_id");
		case "shared_question_context_regions", "shared_question_context_images",
				"shared_question_context_content_parts" ->
			context(row + ".shared_context_id");
		case "answers" -> question(row + ".question_id");
		case "answer_regions" -> answer(row + ".answer_id") + " AND " + """
				EXISTS (
				    SELECT 1
				    FROM answers answer
				    JOIN questions question
				        ON question.id = answer.question_id
				    JOIN exam_booklets booklet
				        ON booklet.id = question.booklet_id
				    WHERE answer.id = %s.answer_id
				      AND booklet.answer_file_id =
				          %s.answer_file_id
				)
				""".formatted(row, row);
		case "booklet_applicability_reviews" -> booklet(row + ".booklet_id");
		default -> throw new IllegalArgumentException("Unsupported contributor capture table: " + table);
		};
	}

	private static String question(String questionId) {
		return """
				EXISTS (
				    SELECT 1
				    FROM questions question
				    JOIN exam_booklets booklet
				        ON booklet.id = question.booklet_id
				    WHERE question.id = %s
				      AND booklet.exam_id IN (%s)
				)
				""".formatted(questionId, EXAM_QUERY);
	}

	private static void verify(Connection connection, boolean enforceClassificationLevels) throws SQLException {
		Objects.requireNonNull(connection, "connection");
		for (String table : TABLES) {
			for (String operation : OPERATIONS) {
				String triggerName = name(table, operation);
				String expected = definition(table, operation, enforceClassificationLevels);
				try (PreparedStatement statement = connection.prepareStatement("""
						SELECT tbl_name, sql
						FROM sqlite_master
						WHERE type = 'trigger'
						  AND name = ?
						""")) {
					statement.setString(1, triggerName);
					try (ResultSet result = statement.executeQuery()) {
						if (!result.next()) {
							throw new SQLException("Missing contributor scope trigger: " + triggerName);
						}
						if (!table.equals(result.getString("tbl_name"))
								|| !normalise(expected).equals(normalise(result.getString("sql")))) {
							throw new SQLException("Altered contributor scope trigger: " + triggerName);
						}
						if (result.next()) {
							throw new SQLException("Duplicate contributor scope trigger: " + triggerName);
						}
					}
				}
			}
		}
	}
}
