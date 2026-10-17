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
	 * Removes schema additions introduced by versions 20, 21 and 22.
	 *
	 * <p>
	 * The supplied database must be a disposable, freshly initialised test fixture.
	 * The caller may then remove other later-version structures and set the
	 * required historical schema version.
	 *
	 * <p>
	 * All fixture cleanup occurs in one transaction so failure does not leave a
	 * partially reconstructed historical database.
	 *
	 * @param database disposable SQLite test database
	 * @throws SQLException if schema cleanup fails
	 */
	public static void removePostVersion19Objects(SqliteDatabase database) throws SQLException {
		if (database == null) {
			throw new NullPointerException("database");
		}
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
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
				try (Statement statement = connection.createStatement()) {

					// Remove v21 triggers before modifying their
					// referenced tables.
					for (String triggerName : triggerNames) {
						if (!triggerName.matches("app_review_[a-z_]+_(insert|update|delete)")) {
							throw new SQLException("Unexpected applicability trigger: " + triggerName);
						}
						statement.execute("DROP TRIGGER \"" + triggerName + "\"");
					}

					// Version 22 added both a unique index and a
					// nullable legacy Paper identity column.
					// The index must be removed before the column.
					statement.execute("""
							DROP INDEX ux_exam_booklets_legacy_paper_code
							""");
					statement.execute("""
							ALTER TABLE exam_booklets
							DROP COLUMN legacy_paper_code
							""");

					// Version 20 introduced the applicability table.
					statement.execute("""
							DROP TABLE booklet_applicability_reviews
							""");
				}
				connection.commit();
			} catch (SQLException | RuntimeException exception) {
				try {
					connection.rollback();
				} catch (SQLException rollbackFailure) {
					exception.addSuppressed(rollbackFailure);
				}
				throw exception;
			}
		}
	}
}
