package au.edu.eq.questionbank.repository.sqlite;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;

/**
 * Installs and verifies contributor assignment-session protection.
 *
 * <p>
 * Installed baselines and assignment identity are immutable. Only the separate
 * active-selection row may change.
 */
final class SqliteContributorAssignmentSessionTriggers {

	private static final String SESSION_NO_DELETE = """
			CREATE TRIGGER trg_contributor_assignment_session_no_delete
			BEFORE DELETE ON contributor_assignment_sessions
			BEGIN
			    SELECT RAISE(
			        ABORT,
			        'Installed assignment evidence cannot be deleted'
			    );
			END
			""";
	private static final String SESSION_NO_UPDATE = """
			CREATE TRIGGER trg_contributor_assignment_session_no_update
			BEFORE UPDATE ON contributor_assignment_sessions
			BEGIN
			    SELECT RAISE(
			        ABORT,
			        'Installed assignment evidence cannot be modified'
			    );
			END
			""";
	private static final String SELECTION_NO_DELETE = """
			CREATE TRIGGER trg_contributor_assignment_selection_no_delete
			BEFORE DELETE ON contributor_assignment_selection
			BEGIN
			    SELECT RAISE(
			        ABORT,
			        'Contributor selection singleton cannot be deleted'
			    );
			END
			""";
	private static final String SELECTION_ID_IMMUTABLE = """
			CREATE TRIGGER trg_contributor_assignment_selection_id_immutable
			BEFORE UPDATE OF singleton_id
			ON contributor_assignment_selection
			WHEN NEW.singleton_id IS NOT OLD.singleton_id
			BEGIN
			    SELECT RAISE(
			        ABORT,
			        'Contributor selection identity is immutable'
			    );
			END
			""";

	private SqliteContributorAssignmentSessionTriggers() {
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
		SqliteMigrationTriggerVerifier.verify(connection, "trg_contributor_assignment_", "contributor assignment",
				"/db/migration-v28-to-v29.sql");
	}

	private static List<String> definitions() {
		return List.of(SESSION_NO_DELETE, SESSION_NO_UPDATE, SELECTION_NO_DELETE, SELECTION_ID_IMMUTABLE);
	}
}
