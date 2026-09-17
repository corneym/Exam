package au.edu.eq.questionbank.repository.sqlite;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Executes a simple SQL script containing semicolon-separated statements.
 */
final class SqlScriptExecutor {

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

		// Scripts must use semicolons only as statement separators; this is not a SQL
		// parser.
		for (String statementText : sql.split(";")) {
			String trimmed = statementText.trim();
			if (trimmed.isEmpty()) {
				continue;
			}

			// Execute in order on the caller transaction so migration failures can roll
			// back earlier statements.
			try (Statement statement = connection.createStatement()) {
				statement.execute(trimmed);
			}
		}
	}

	private SqlScriptExecutor() {
	}
}
