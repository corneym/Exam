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
		SqliteMigrationTriggerVerifier.verify(connection, "trg_contributor_structure_", "contributor structure",
				"/db/migration-v29-to-v30.sql", "/db/migration-v31-to-v32.sql");
	}

	static void verifyLegacy(Connection connection) throws SQLException {
		SqliteMigrationTriggerVerifier.verify(connection, "trg_contributor_structure_", "contributor structure",
				"/db/migration-v29-to-v30.sql");
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
}
