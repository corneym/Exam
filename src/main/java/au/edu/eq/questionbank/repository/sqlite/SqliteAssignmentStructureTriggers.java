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
 * Installs and verifies contributor assignment structural write barriers.
 *
 * <p>
 * While an installed assignment is selected, authoritative Subject, syllabus,
 * Exam and source-asset metadata cannot be inserted, updated or deleted through
 * any SQLite connection.
 *
 * <p>
 * The selected assignment is stored in the database, so enforcement survives
 * application restarts and does not rely on UI state.
 */
final class SqliteAssignmentStructureTriggers {

	private static final List<String> TABLES = List.of("subjects", "syllabus_versions", "curriculum_nodes",
			"curriculum_mappings", "curriculum_mapping_reviews", "exam_providers", "source_documents", "exams",
			"exam_booklets", "answer_files");
	private static final List<String> OPERATIONS = List.of("insert", "update", "delete");

	private SqliteAssignmentStructureTriggers() {
	}

	static void enablePendingMcqContinuation(Connection connection) throws SQLException {
		Objects.requireNonNull(connection, "connection");

		// Called inside the schema migration transaction. Replace only the
		// Booklet UPDATE guard; all other structural guards remain installed.
		try (Statement statement = connection.createStatement()) {
			statement.execute("DROP TRIGGER " + name("exam_booklets", "update"));
			statement.execute(definition("exam_booklets", "update", true));
			statement.execute(pendingMcqDefinition());
		}
		verify(connection);
	}

	static void install(Connection connection) throws SQLException {
		Objects.requireNonNull(connection, "connection");

		// Schema v30 installs the original blanket structural guards.
		// Schema v32 replaces the Booklet UPDATE guard separately.
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

	private static String definition(String table, String operation, boolean pendingMcqEnabled) {
		if (pendingMcqEnabled && table.equals("exam_booklets") && operation.equals("update")) {

			// Naming the protected columns makes even a no-op structural
			// UPDATE illegal. Only the pending context column is exempt.
			return """
					CREATE TRIGGER %s
					BEFORE UPDATE OF
					    id, exam_id, source_document_id, booklet_name,
					    question_format, answer_file_id,
					    expected_question_count, legacy_paper_code
					ON exam_booklets
					WHEN (
					    SELECT assignment_id
					    FROM contributor_assignment_selection
					    WHERE singleton_id = 1
					    ) IS NOT NULL
					BEGIN
					    SELECT RAISE(
					        ABORT,
					        'Coordinator-owned structure is read-only in assignment mode'
					    );
					END
					""".formatted(name(table, operation)).strip();
		}
		return """
				CREATE TRIGGER %s
				BEFORE %s ON %s
				WHEN (
				    SELECT assignment_id
				    FROM contributor_assignment_selection
				    WHERE singleton_id = 1
				    ) IS NOT NULL
				BEGIN
				    SELECT RAISE(
				        ABORT,
				        'Coordinator-owned structure is read-only in assignment mode'
				    );
				END
				""".formatted(name(table, operation), operation.toUpperCase(Locale.ROOT), table).strip();
	}

	private static String name(String table, String operation) {
		return "trg_contributor_structure_" + table + "_" + operation;
	}

	private static String normalise(String definition) {
		return Objects.requireNonNull(definition, "definition").replaceAll("\\s+", " ").strip();
	}

	private static String pendingMcqDefinition() {
		return """
				CREATE TRIGGER trg_contributor_structure_exam_booklets_pending_mcq_update
				BEFORE UPDATE OF pending_mcq_shared_context_id
				ON exam_booklets
				WHEN (
				    SELECT assignment_id
				    FROM contributor_assignment_selection
				    WHERE singleton_id = 1
				    ) IS NOT NULL
				AND NOT (
				    EXISTS (
				        SELECT 1
				        FROM contributor_assignment_sessions session
				        JOIN contributor_assignment_selection selected
				            ON selected.assignment_id = session.assignment_id
				        WHERE selected.singleton_id = 1
				          AND session.exam_local_id = OLD.exam_id
				    )
				    AND (
				        NEW.pending_mcq_shared_context_id IS NULL
				        OR EXISTS (
				            SELECT 1
				            FROM shared_question_contexts context
				            WHERE context.id = NEW.pending_mcq_shared_context_id
				              AND context.booklet_id = OLD.id
				        )
				    )
				)
				BEGIN
				    SELECT RAISE(
				        ABORT,
				        'Coordinator-owned structure is read-only in assignment mode'
				    );
				END
				""".strip();
	}

	private static void verify(Connection connection, boolean pendingMcqEnabled) throws SQLException {
		Objects.requireNonNull(connection, "connection");
		for (String table : TABLES) {
			for (String operation : OPERATIONS) {
				verifyTrigger(connection, table, name(table, operation),
						definition(table, operation, pendingMcqEnabled));
			}
		}
		if (pendingMcqEnabled) {
			verifyTrigger(connection, "exam_booklets", "trg_contributor_structure_exam_booklets_pending_mcq_update",
					pendingMcqDefinition());
		}
	}

	private static void verifyTrigger(Connection connection, String table, String triggerName, String expected)
			throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT tbl_name, sql
				FROM sqlite_master
				WHERE type = 'trigger'
				  AND name = ?
				""")) {
			statement.setString(1, triggerName);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new SQLException("Missing contributor structure trigger: " + triggerName);
				}
				if (!table.equals(result.getString("tbl_name"))
						|| !normalise(expected).equals(normalise(result.getString("sql")))) {
					throw new SQLException("Altered contributor structure trigger: " + triggerName);
				}
				if (result.next()) {
					throw new SQLException("Duplicate contributor structure trigger: " + triggerName);
				}
			}
		}
	}
}
