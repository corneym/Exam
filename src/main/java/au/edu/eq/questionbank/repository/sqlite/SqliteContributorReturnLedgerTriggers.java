package au.edu.eq.questionbank.repository.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;

/**
 * Protects recorded contributor return evidence against modification and
 * deletion.
 *
 * <p>
 * Return lineage is append-only. A later return may refer to an earlier package
 * but must never rewrite that package's evidence.
 */
final class SqliteContributorReturnLedgerTriggers {

	private static final String IMMUTABLE_DELETE = """
			CREATE TRIGGER trg_contributor_returns_no_delete
			BEFORE DELETE ON contributor_return_packages
			BEGIN
			    SELECT RAISE(
			        ABORT,
			        'Contributor return evidence cannot be deleted'
			    );
			END
			""";
	private static final String IMMUTABLE_UPDATE = """
			CREATE TRIGGER trg_contributor_returns_no_update
			BEFORE UPDATE ON contributor_return_packages
			BEGIN
			    SELECT RAISE(
			        ABORT,
			        'Contributor return evidence cannot be modified'
			    );
			END
			""";

	private SqliteContributorReturnLedgerTriggers() {
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
						throw new SQLException("Missing return protection trigger: " + triggerName);
					}
					String actual = result.getString("sql");
					if (actual == null || !normalise(actual).equals(normalise(expected))) {
						throw new SQLException("Altered return protection trigger: " + triggerName);
					}
					if (result.next()) {
						throw new SQLException("Duplicate return protection trigger: " + triggerName);
					}
				}
			}
		}
	}

	private static List<String> definitions() {
		return List.of(IMMUTABLE_DELETE, IMMUTABLE_UPDATE);
	}

	private static String normalise(String sql) {
		return sql.replaceAll("\\s+", " ").strip();
	}
}
