package au.edu.eq.questionbank.repository.sqlite;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verifies installed SQLite triggers against versioned migration resources.
 *
 * <p>
 * Reconstructs the expected trigger definitions by applying CREATE and DROP
 * statements in migration order. It then checks the installed trigger names,
 * owning tables and exact SQL definitions, ignoring only whitespace
 * differences.
 */
final class SqliteMigrationTriggerVerifier {

	private static final Pattern CREATE = Pattern.compile("(?is)^CREATE\\s+TRIGGER\\s+([a-z_][a-z0-9_]*)"
			+ "\\s+(?:BEFORE|AFTER)\\b.*?\\bON\\s+" + "([a-z_][a-z0-9_]*)\\b.*$");
	private static final Pattern DROP = Pattern.compile("(?is)^DROP\\s+TRIGGER\\s+([a-z_][a-z0-9_]*)\\s*;?$");

	private SqliteMigrationTriggerVerifier() {
	}

	static void verify(Connection connection, String prefix, String description, String... migrationResources)
			throws SQLException {
		Objects.requireNonNull(connection, "connection");
		Objects.requireNonNull(prefix, "prefix");
		Objects.requireNonNull(description, "description");
		Objects.requireNonNull(migrationResources, "migrationResources");
		Map<String, ExpectedTrigger> expected = expectedTriggers(prefix, migrationResources);
		if (expected.isEmpty()) {
			throw new SQLException("No " + description + " triggers in migration resources");
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT name, tbl_name, sql
				FROM sqlite_master
				WHERE type = 'trigger'
				  AND name GLOB ?
				""")) {
			statement.setString(1, prefix + "*");
			try (ResultSet result = statement.executeQuery()) {
				while (result.next()) {
					String name = result.getString("name");
					ExpectedTrigger definition = expected.remove(name);
					if (definition == null) {
						throw new SQLException("Unexpected " + description + " trigger: " + name);
					}
					if (!definition.table().equals(result.getString("tbl_name"))
							|| !normalise(definition.sql()).equals(normalise(result.getString("sql")))) {
						throw new SQLException("Altered " + description + " trigger: " + name);
					}
				}
			}
		}
		if (!expected.isEmpty()) {
			throw new SQLException("Missing " + description + " trigger: " + expected.keySet().iterator().next());
		}
	}

	private static Map<String, ExpectedTrigger> expectedTriggers(String prefix, String... migrationResources)
			throws SQLException {
		Map<String, ExpectedTrigger> expected = new LinkedHashMap<>();
		for (String resource : migrationResources) {
			String script;
			try {
				script = SqlResourceLoader.load(resource);
			} catch (IOException failure) {
				throw new SQLException("Unable to load trigger migration: " + resource, failure);
			}
			for (String statement : SqlScriptExecutor.splitStatements(script)) {
				String sql = stripLeadingComments(statement);
				Matcher drop = DROP.matcher(sql);
				if (drop.matches()) {
					String name = drop.group(1);
					if (name.startsWith(prefix) && expected.remove(name) == null) {
						throw new SQLException("Migration drops unknown trigger: " + name);
					}
					continue;
				}
				Matcher create = CREATE.matcher(sql);
				if (!create.matches()) {
					continue;
				}
				String name = create.group(1);
				if (!name.startsWith(prefix)) {
					continue;
				}
				ExpectedTrigger definition = new ExpectedTrigger(create.group(2), sql);

				// A replacement must first explicitly DROP its predecessor.
				if (expected.putIfAbsent(name, definition) != null) {
					throw new SQLException("Duplicate trigger definition in migrations: " + name);
				}
			}
		}
		return expected;
	}

	private static String normalise(String sql) {
		return Objects.requireNonNull(sql, "sql").strip().replaceFirst(";\\s*$", "").replaceAll("\\s+", " ").strip();
	}

	private static String stripLeadingComments(String statement) {
		String remaining = statement.stripLeading();
		while (remaining.startsWith("--")) {
			int newline = remaining.indexOf('\n');
			if (newline < 0) {
				return "";
			}
			remaining = remaining.substring(newline + 1).stripLeading();
		}
		return remaining;
	}

	private record ExpectedTrigger(String table, String sql) {
	}
}
