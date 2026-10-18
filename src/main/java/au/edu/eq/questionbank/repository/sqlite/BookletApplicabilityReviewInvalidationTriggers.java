package au.edu.eq.questionbank.repository.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Installs and verifies database triggers that invalidate explicit booklet
 * applicability-review confirmations after material edits.
 *
 * The triggers live in the database and therefore cover every persistence entry
 * point, not just the Dashboard.
 */
final class BookletApplicabilityReviewInvalidationTriggers {

	private static final String MATERIAL_BOOKLET_UPDATE_WHEN = """
			OLD.exam_id IS NOT NEW.exam_id
			OR OLD.source_document_id IS NOT NEW.source_document_id
			OR OLD.question_format IS NOT NEW.question_format
			OR OLD.answer_file_id IS NOT NEW.answer_file_id
			OR OLD.pending_mcq_shared_context_id IS NOT NEW.pending_mcq_shared_context_id
			OR OLD.expected_question_count IS NOT NEW.expected_question_count
			""";
	private static final List<Rule> RULES = List.of(new Rule("questions", "SELECT ${row}.booklet_id"),
			new Rule("question_regions", """
					SELECT q.booklet_id
					FROM questions q
					WHERE q.id = ${row}.question_id
					"""), new Rule("question_images", """
					SELECT q.booklet_id
					FROM questions q
					WHERE q.id = ${row}.question_id
					"""), new Rule("question_content_parts", """
					SELECT q.booklet_id
					FROM questions q
					WHERE q.id = ${row}.question_id
					"""), new Rule("question_output_exclusions", """
					SELECT q.booklet_id
					FROM questions q
					WHERE q.id = ${row}.question_id
					"""), new Rule("source_questions", "SELECT ${row}.booklet_id"),
			new Rule("shared_question_contexts", "SELECT ${row}.booklet_id"),
			new Rule("shared_question_context_regions", """
					SELECT c.booklet_id
					FROM shared_question_contexts c
					WHERE c.id = ${row}.shared_context_id
					"""), new Rule("answers", """
					SELECT q.booklet_id
					FROM questions q
					WHERE q.id = ${row}.question_id
					"""), new Rule("answer_regions", """
					SELECT q.booklet_id
					FROM answers a
					JOIN questions q ON q.id = a.question_id
					WHERE a.id = ${row}.answer_id
					"""), new Rule("exam_booklets", "SELECT ${row}.id"), new Rule("answer_files", """
					SELECT b.id
					FROM exam_booklets b
					WHERE b.exam_id = ${row}.exam_id
					"""), new Rule("source_documents", """
					SELECT b.id
					FROM exam_booklets b
					WHERE b.source_document_id = ${row}.id

					UNION

					SELECT b.id
					FROM exam_booklets b
					JOIN answer_files a ON a.exam_id = b.exam_id
					WHERE a.source_document_id = ${row}.id
					"""), new Rule("curriculum_nodes", """
					SELECT b.id
					FROM exam_booklets b
					JOIN exams e ON e.id = b.exam_id
					JOIN syllabus_versions s
					  ON s.subject_id = e.subject_id
					WHERE s.id = ${row}.syllabus_version_id
					"""), new Rule("syllabus_versions", """
					SELECT b.id
					FROM exam_booklets b
					JOIN exams e ON e.id = b.exam_id
					WHERE e.subject_id = ${row}.subject_id
					"""), new Rule("curriculum_mappings", """
					SELECT b.id
					FROM exam_booklets b
					JOIN exams e ON e.id = b.exam_id
					WHERE e.subject_id IN (
					    SELECT s.subject_id
					    FROM curriculum_nodes n
					    JOIN syllabus_versions s
					      ON s.id = n.syllabus_version_id
					    WHERE n.id IN (
					        ${row}.source_node_id,
					        ${row}.target_node_id
					    )
					)
					"""), new Rule("curriculum_mapping_reviews", """
					SELECT b.id
					FROM exam_booklets b
					JOIN exams e ON e.id = b.exam_id
					WHERE e.subject_id IN (
					    SELECT s.subject_id
					    FROM curriculum_nodes n
					    JOIN syllabus_versions s
					      ON s.id = n.syllabus_version_id
					    WHERE n.id = ${row}.source_node_id

					    UNION

					    SELECT s.subject_id
					    FROM syllabus_versions s
					    WHERE s.id =
					        ${row}.target_syllabus_version_id
					)
					"""));

	private BookletApplicabilityReviewInvalidationTriggers() {
	}

	/**
	 * Installs triggers using the caller's migration transaction.
	 */
	static void install(Connection connection) throws SQLException {
		for (Rule rule : RULES) {
			installTrigger(connection, rule, "INSERT");
			installTrigger(connection, rule, "UPDATE");
			installTrigger(connection, rule, "DELETE");
		}
	}

	/**
	 * Replaces only the overly broad booklet UPDATE trigger inside the caller's
	 * schema migration transaction. All other invalidation rules are retained.
	 *
	 * @param connection caller-owned migration connection
	 * @throws SQLException if the trigger cannot be replaced
	 */
	static void installVersion23BookletUpdateTrigger(Connection connection) throws SQLException {
		Rule bookletRule = new Rule("exam_booklets", "SELECT ${row}.id");
		try (Statement statement = connection.createStatement()) {
			statement.executeUpdate("DROP TRIGGER " + triggerName("exam_booklets", "UPDATE"));
		}

		// The old trigger is dropped and the corrected trigger created in one
		// outer migration transaction; failure restores the original definition.
		installTrigger(connection, bookletRule, "UPDATE", MATERIAL_BOOKLET_UPDATE_WHEN);
	}

	/**
	 * Rejects a version-21 database missing a required invalidation trigger.
	 */
	static void verify(Connection connection) throws SQLException {
		for (Rule rule : RULES) {
			for (String event : List.of("INSERT", "UPDATE", "DELETE")) {
				String name = triggerName(rule.table(), event);
				try (PreparedStatement statement = connection.prepareStatement("""
						SELECT 1
						FROM sqlite_master
						WHERE type = 'trigger'
						  AND name = ?
						  AND tbl_name = ?
						""")) {
					statement.setString(1, name);
					statement.setString(2, rule.table());
					try (ResultSet result = statement.executeQuery()) {
						if (!result.next()) {
							throw new SQLException("Missing applicability-review " + "invalidation trigger: " + name);
						}
					}
				}
			}
		}
	}

	/**
	 * Verifies that the booklet UPDATE trigger has v23's selective definition,
	 * rather than merely existing under the expected trigger name.
	 *
	 * @param connection database connection to verify
	 * @throws SQLException if the corrected trigger is missing or has changed
	 */
	static void verifyVersion23BookletUpdateTrigger(Connection connection) throws SQLException {
		Rule bookletRule = new Rule("exam_booklets", "SELECT ${row}.id");
		String expected = triggerSql(bookletRule, "UPDATE", MATERIAL_BOOKLET_UPDATE_WHEN);
		String name = triggerName("exam_booklets", "UPDATE");
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT sql
				FROM sqlite_master
				WHERE type = 'trigger'
				  AND name = ?
				  AND tbl_name = 'exam_booklets'
				""")) {
			statement.setString(1, name);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next() || !normaliseSql(expected).equals(normaliseSql(result.getString("sql")))) {
					throw new SQLException("Incorrect schema-v23 applicability-review trigger: " + name);
				}
			}
		}
	}

	private static void installTrigger(Connection connection, Rule rule, String event) throws SQLException {
		installTrigger(connection, rule, event, null);
	}

	private static void installTrigger(Connection connection, Rule rule, String event, String whenCondition)
			throws SQLException {

		// A complete CREATE TRIGGER cannot pass through the semicolon-splitting
		// SQL script executor; execute it directly using the caller's connection.
		try (Statement statement = connection.createStatement()) {
			statement.executeUpdate(triggerSql(rule, event, whenCondition));
		}
	}

	private static String normaliseSql(String sql) {
		return sql.replaceAll("\\s+", " ").strip();
	}

	private static String triggerName(String table, String event) {
		return "app_review_" + table + "_" + event.toLowerCase();
	}

	private static String triggerSql(Rule rule, String event, String whenCondition) {
		String oldSelect = rule.bookletSelect().replace("${row}", "OLD");
		String newSelect = rule.bookletSelect().replace("${row}", "NEW");
		String affectedBooklets = switch (event) {
		case "INSERT" -> newSelect;
		case "DELETE" -> oldSelect;
		case "UPDATE" -> oldSelect + "\nUNION\n" + newSelect;
		default -> throw new IllegalArgumentException(event);
		};
		String whenClause = whenCondition == null ? "" : "WHEN " + whenCondition.strip() + "\n";
		return """
				CREATE TRIGGER %s
				AFTER %s ON %s
				%sBEGIN
				    DELETE FROM booklet_applicability_reviews
				    WHERE booklet_id IN (
				        %s
				    );
				END
				""".formatted(triggerName(rule.table(), event), event, rule.table(), whenClause, affectedBooklets);
	}

	private record Rule(String table, String bookletSelect) {
	}
}
