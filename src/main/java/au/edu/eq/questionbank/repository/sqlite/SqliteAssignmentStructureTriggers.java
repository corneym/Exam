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
		try (Statement statement = connection.createStatement()) {
			for (String table : TABLES) {
				for (String operation : OPERATIONS) {
					statement.execute(definition(table, operation));
				}
			}
		}
		verify(connection);
	}

	static void verify(Connection connection) throws SQLException {
		Objects.requireNonNull(connection, "connection");
		for (String table : TABLES) {
			for (String operation : OPERATIONS) {
				String name = name(table, operation);
				String expected = definition(table, operation);
				try (PreparedStatement statement = connection.prepareStatement("""
						SELECT tbl_name, sql
						FROM sqlite_master
						WHERE type = 'trigger'
						  AND name = ?
						""")) {
					statement.setString(1, name);
					try (ResultSet result = statement.executeQuery()) {
						if (!result.next()) {
							throw new SQLException("Missing contributor structure trigger: " + name);
						}
						if (!table.equals(result.getString("tbl_name"))
								|| !normalise(expected).equals(normalise(result.getString("sql")))) {
							throw new SQLException("Altered contributor structure trigger: " + name);
						}
						if (result.next()) {
							throw new SQLException("Duplicate contributor structure trigger: " + name);
						}
					}
				}
			}
		}
	}

	private static String definition(String table, String operation) {
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
}
