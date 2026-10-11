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

	private static void installTrigger(Connection connection, Rule rule, String event) throws SQLException {
		String oldSelect = rule.bookletSelect().replace("${row}", "OLD");
		String newSelect = rule.bookletSelect().replace("${row}", "NEW");
		String affectedBooklets = switch (event) {
		case "INSERT" -> newSelect;
		case "DELETE" -> oldSelect;
		case "UPDATE" -> oldSelect + "\nUNION\n" + newSelect;
		default -> throw new IllegalArgumentException(event);
		};
		String sql = """
				CREATE TRIGGER %s
				AFTER %s ON %s
				BEGIN
				    DELETE FROM booklet_applicability_reviews
				    WHERE booklet_id IN (
				        %s
				    );
				END
				""".formatted(triggerName(rule.table(), event), event, rule.table(), affectedBooklets);

		// Execute each complete CREATE TRIGGER statement directly.
		// The general migration script executor splits on semicolons
		// and cannot correctly execute trigger bodies.
		try (Statement statement = connection.createStatement()) {
			statement.executeUpdate(sql);
		}
	}

	private static String triggerName(String table, String event) {
		return "app_review_" + table + "_" + event.toLowerCase();
	}

	private record Rule(String table, String bookletSelect) {
	}
}
