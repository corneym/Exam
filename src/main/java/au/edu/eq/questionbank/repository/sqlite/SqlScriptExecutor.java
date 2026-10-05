package au.edu.eq.questionbank.repository.sqlite;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Executes a simple SQL script containing semicolon-separated statements.
 */
final class SqlScriptExecutor {

	private SqlScriptExecutor() {
	}

	/**
	 * Executes each non-blank semicolon-delimited statement in script order. The
	 * caller owns the connection and its transaction boundary.
	 *
	 * @param connection the connection on which to execute the script
	 * @param sql        the SQL script
	 * @throws SQLException         if a statement fails
	 * @throws NullPointerException if either argument is {@code null}
	 */
	static void execute(Connection connection, String sql) throws SQLException {
		if (connection == null) {
			throw new NullPointerException("connection");
		}
		if (sql == null) {
			throw new NullPointerException("sql");
		}
		int statementNumber = 0;

		// Scripts must use semicolons only as statement separators; this is not a SQL
		// parser.
		for (String statementText : sql.split(";")) {
			String trimmed = statementText.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			statementNumber++;

			// Migration resources contain schema/data-changing statements rather than
			// result-producing queries. executeUpdate uses SQLite's direct execution
			// path and avoids prepared-statement cleanup masking the real migration
			// failure.
			try (Statement statement = connection.createStatement()) {
				statement.executeUpdate(trimmed);
			} catch (SQLException exception) {

				// Preserve both the failing script position and SQLite's original
				// diagnostic. This is particularly useful when a migration contains
				// several ALTER/CREATE/UPDATE statements.
				String firstLine = trimmed.lines().findFirst().orElse(trimmed);
				throw new SQLException("SQL script statement " + statementNumber + " failed: " + firstLine, exception);
			}
		}
	}
}
