package au.edu.eq.questionbank.repository.sqlite;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Executes a simple SQL script containing semicolon-separated statements.
 */
final class SqlScriptExecutor {

	private static final Pattern TRIGGER_END = Pattern.compile("(?is)\\bEND\\s*;\\s*$");
	private static final Pattern TRIGGER_START = Pattern.compile("(?i)CREATE\\s+(?:TEMP(?:ORARY)?\\s+)?TRIGGER\\b");

	private SqlScriptExecutor() {
	}

	/**
	 * Executes a SQL migration script in statement order.
	 *
	 * <p>
	 * Semicolons inside quoted strings and comments are ignored. CREATE TRIGGER
	 * statements are retained through their concluding END statement, allowing
	 * trigger bodies to contain multiple SQL statements.
	 *
	 * <p>
	 * The caller owns the connection and its transaction boundary. This executor
	 * supports the migration-script syntax used by the application; it is not a
	 * general-purpose SQLite parser.
	 *
	 * @param connection connection on which to execute the script
	 * @param sql        complete SQL migration script
	 * @throws SQLException         if a statement fails or the script is incomplete
	 * @throws NullPointerException if either argument is null
	 */
	static void execute(Connection connection, String sql) throws SQLException {
		Objects.requireNonNull(connection, "connection");
		Objects.requireNonNull(sql, "sql");
		List<String> statements = splitStatements(sql);
		int statementNumber = 0;
		for (String statementText : statements) {
			statementNumber++;
			try (Statement statement = connection.createStatement()) {
				statement.executeUpdate(statementText);
			} catch (SQLException exception) {
				String firstLine = statementText.lines().findFirst().orElse(statementText);
				throw new SQLException("SQL script statement " + statementNumber + " failed: " + firstLine, exception);
			}
		}
	}

	static List<String> splitStatements(String sql) throws SQLException {
		List<String> statements = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		char closingQuote = 0;
		boolean lineComment = false;
		boolean blockComment = false;
		for (int i = 0; i < sql.length(); i++) {
			char character = sql.charAt(i);
			char next = i + 1 < sql.length() ? sql.charAt(i + 1) : 0;
			if (lineComment) {
				current.append(character);
				if (character == '\n') {
					lineComment = false;
				}
				continue;
			}
			if (blockComment) {
				current.append(character);
				if (character == '*' && next == '/') {
					current.append(next);
					i++;
					blockComment = false;
				}
				continue;
			}
			if (closingQuote != 0) {
				current.append(character);
				if (character == closingQuote) {
					if (closingQuote != ']' && next == closingQuote) {

						// SQLite doubles quote characters inside literals.
						current.append(next);
						i++;
					} else {
						closingQuote = 0;
					}
				}
				continue;
			}
			if (character == '-' && next == '-') {
				current.append(character).append(next);
				i++;
				lineComment = true;
				continue;
			}
			if (character == '/' && next == '*') {
				current.append(character).append(next);
				i++;
				blockComment = true;
				continue;
			}
			if (character == '\'' || character == '"' || character == '`' || character == '[') {
				current.append(character);
				closingQuote = character == '[' ? ']' : character;
				continue;
			}
			if (character == ';') {
				current.append(character);
				String candidate = current.toString();
				boolean trigger = startsWithTrigger(candidate);
				boolean completeTrigger = TRIGGER_END.matcher(candidate).find();

				// Ordinary statements terminate at a semicolon.
				// A trigger terminates at its final END semicolon.
				if (!trigger || completeTrigger) {
					if (!isCommentOnly(candidate)) {
						statements.add(candidate.strip());
					}
					current.setLength(0);
				}
				continue;
			}
			current.append(character);
		}
		if (closingQuote != 0 || blockComment) {
			throw new SQLException("Unterminated SQL quotation or comment");
		}
		String remaining = current.toString().strip();
		if (!isCommentOnly(remaining)) {
			if (startsWithTrigger(remaining)) {
				throw new SQLException("Unterminated CREATE TRIGGER statement");
			}
			statements.add(remaining);
		}
		return List.copyOf(statements);
	}

	private static boolean isCommentOnly(String sql) {
		return skipLeadingTrivia(sql) == sql.length();
	}

	private static int skipLeadingTrivia(String sql) {
		int index = 0;
		int length = sql.length();
		while (index < length) {
			char current = sql.charAt(index);
			if (Character.isWhitespace(current)) {
				index++;
				continue;
			}

			// Skip SQL line comments through their newline or EOF.
			if (current == '-' && index + 1 < length && sql.charAt(index + 1) == '-') {
				index += 2;
				while (index < length && sql.charAt(index) != '\n') {
					index++;
				}
				continue;
			}

			// Skip block comments without regular-expression backtracking.
			if (current == '/' && index + 1 < length && sql.charAt(index + 1) == '*') {
				int end = sql.indexOf("*/", index + 2);
				if (end < 0) {
					return length;
				}
				index = end + 2;
				continue;
			}

			// The first non-trivia character begins the SQL statement.
			return index;
		}
		return index;
	}

	private static boolean startsWithTrigger(String sql) {
		int start = skipLeadingTrivia(sql);
		if (start == sql.length()) {
			return false;
		}

		// Match the first SQL keywords only. No repeated alternatives
		// or backtracking through preceding comments are required.
		return TRIGGER_START.matcher(sql).region(start, sql.length()).lookingAt();
	}
}
