package au.edu.eq.questionbank.repository.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;

/**
 * Installs and verifies database-level protection of assignment issuance
 * evidence and lifecycle transitions.
 */
final class SqliteExamWorkAssignmentLedgerTriggers {

	private static final String IMMUTABLE_DELETE = """
			CREATE TRIGGER trg_exam_work_assignments_no_delete
			BEFORE DELETE ON exam_work_assignments
			BEGIN
			    SELECT RAISE(
			        ABORT,
			        'Issued assignment evidence cannot be deleted'
			    );
			END
			""";
	private static final String IMMUTABLE_UPDATE = """
			CREATE TRIGGER trg_exam_work_assignments_no_update
			BEFORE UPDATE ON exam_work_assignments
			BEGIN
			    SELECT RAISE(
			        ABORT,
			        'Issued assignment evidence cannot be modified'
			    );
			END
			""";
	private static final String INITIAL_STATE = """
			CREATE TRIGGER trg_exam_work_assignment_initial_state
			BEFORE INSERT ON exam_work_assignment_states
			WHEN NEW.state <> 'ACTIVE'
			BEGIN
			    SELECT RAISE(
			        ABORT,
			        'New assignments must begin ACTIVE'
			    );
			END
			""";
	private static final String STATE_DELETE = """
			CREATE TRIGGER trg_exam_work_assignment_state_no_delete
			BEFORE DELETE ON exam_work_assignment_states
			BEGIN
			    SELECT RAISE(
			        ABORT,
			        'Assignment lifecycle evidence cannot be deleted'
			    );
			END
			""";
	private static final String STATE_TRANSITION = """
			CREATE TRIGGER trg_exam_work_assignment_state_transition
			BEFORE UPDATE ON exam_work_assignment_states
			WHEN OLD.state <> 'ACTIVE'
			     OR NEW.state NOT IN ('COMPLETE', 'CANCELLED')
			BEGIN
			    SELECT RAISE(
			        ABORT,
			        'Assignment lifecycle transition is not permitted'
			    );
			END
			""";

	private SqliteExamWorkAssignmentLedgerTriggers() {
	}

	static void install(Connection connection) throws SQLException {
		Objects.requireNonNull(connection, "connection");
		try (Statement statement = connection.createStatement()) {
			for (String definition : definitions()) {
				statement.execute(definition);
			}
		}
		verify(connection);
	}

	static void verify(Connection connection) throws SQLException {
		Objects.requireNonNull(connection, "connection");
		for (String expected : definitions()) {
			String triggerName = expected.strip().split("\\s+")[2];
			try (PreparedStatement statement = connection.prepareStatement("""
					SELECT sql
					FROM sqlite_master
					WHERE type = 'trigger'
					  AND name = ?
					""")) {
				statement.setString(1, triggerName);
				try (ResultSet result = statement.executeQuery()) {
					if (!result.next()) {
						throw new SQLException("Missing assignment protection trigger: " + triggerName);
					}
					String actual = result.getString("sql");

					// Verify the full definition rather than the name
					// alone. A same-named permissive replacement must fail.
					if (actual == null || !normalise(actual).equals(normalise(expected))) {
						throw new SQLException("Altered assignment protection trigger: " + triggerName);
					}
				}
			}
		}
	}

	private static List<String> definitions() {
		return List.of(IMMUTABLE_DELETE, IMMUTABLE_UPDATE, INITIAL_STATE, STATE_DELETE, STATE_TRANSITION);
	}

	private static String normalise(String sql) {
		return sql.replaceAll("\\s+", " ").strip();
	}
}
