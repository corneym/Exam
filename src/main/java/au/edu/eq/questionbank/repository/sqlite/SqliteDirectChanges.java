package au.edu.eq.questionbank.repository.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/**
 * Reports rows directly modified by a SQLite statement.
 *
 * <p>
 * The SQLite JDBC driver's executeUpdate return value can include rows modified
 * by triggers. That value must not be used to validate the number of domain
 * rows affected by an operation.
 *
 * <p>
 * SQLite's changes() function excludes auxiliary changes made by triggers and
 * foreign-key actions.
 */
public final class SqliteDirectChanges {

	private SqliteDirectChanges() {
	}

	/**
	 * Executes a prepared statement and returns its direct row count.
	 *
	 * @param statement prepared INSERT, UPDATE or DELETE statement
	 * @return number of directly modified rows
	 * @throws SQLException if execution or counting fails
	 */
	public static int executeUpdate(PreparedStatement statement) throws SQLException {
		Objects.requireNonNull(statement, "statement");
		statement.executeUpdate();
		return readChanges(statement.getConnection());
	}

	/**
	 * Executes an SQL statement and returns its direct row count.
	 *
	 * @param statement SQL statement
	 * @param sql       INSERT, UPDATE or DELETE statement text
	 * @return number of directly modified rows
	 * @throws SQLException if execution or counting fails
	 */
	public static int executeUpdate(Statement statement, String sql) throws SQLException {
		Objects.requireNonNull(statement, "statement");
		Objects.requireNonNull(sql, "sql");
		statement.executeUpdate(sql);
		return readChanges(statement.getConnection());
	}

	private static int readChanges(Connection connection) throws SQLException {

		// Read immediately on the same connection. changes() concerns
		// only the most recently completed top-level write statement.
		try (Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("SELECT changes()")) {
			if (!result.next()) {
				throw new SQLException("SQLite did not return a direct change count");
			}
			int count = result.getInt(1);
			if (count < 0 || result.next()) {
				throw new SQLException("Invalid SQLite direct change count");
			}
			return count;
		}
	}
}
