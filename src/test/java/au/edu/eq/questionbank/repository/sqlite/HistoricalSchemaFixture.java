package au.edu.eq.questionbank.repository.sqlite;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Removes newer schema objects when constructing historical SQLite databases
 * for migration and backup-restore regression tests.
 *
 * This helper is for test fixtures only. Production migrations must never
 * silently discard existing review records or triggers.
 */
public final class HistoricalSchemaFixture {

	private HistoricalSchemaFixture() {
	}

	/**
	 * Removes the schema additions introduced by versions 20 and 21.
	 *
	 * The supplied database must be a disposable, freshly initialised test fixture.
	 * The caller may then reverse older schema migrations and set the corresponding
	 * historical schema version.
	 *
	 * @param database disposable SQLite test database
	 * @throws SQLException if schema cleanup fails
	 */
	public static void removePostVersion19Objects(SqliteDatabase database) throws SQLException {

		if (database == null) {
			throw new NullPointerException("database");
		}

		try (Connection connection = database.openConnection()) {
			List<String> triggerNames = new ArrayList<>();

			try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("""
					SELECT name
					FROM sqlite_master
					WHERE type = 'trigger'
					  AND name GLOB 'app_review_*'
					ORDER BY name
					""")) {

				while (result.next()) {
					triggerNames.add(result.getString("name"));
				}
			}

			// Remove v21 triggers before removing their v20 target table.
			try (Statement statement = connection.createStatement()) {
				for (String triggerName : triggerNames) {
					if (!triggerName.matches("app_review_[a-z_]+_(insert|update|delete)")) {
						throw new SQLException("Unexpected applicability trigger: " + triggerName);
					}

					statement.execute("DROP TRIGGER \"" + triggerName + "\"");
				}

				statement.execute("DROP TABLE booklet_applicability_reviews");
			}
		}
	}
}
