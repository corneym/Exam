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

					// Historical fixtures must not retain the schema-v24 tables.
					// Their production migration must recreate them.
					removeVersion24Objects(statement);

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

	/**
	 * Removes schema objects introduced after version 23 from a disposable
	 * historical database fixture.
	 *
	 * @param statement statement operating on the fixture connection
	 * @throws SQLException if schema cleanup fails
	 */
	public static void removeVersion24Objects(Statement statement) throws SQLException {
		if (statement == null) {
			throw new NullPointerException("statement");
		}

		// Newer portable identity tables must be removed before an older
		// schema version can be reconstructed.
		removeVersion25Objects(statement);

		// Content-order rows reference the image and region tables.
		statement.execute("DROP TABLE shared_question_context_content_parts");
		statement.execute("DROP TABLE shared_question_context_images");
	}

	/**
	 * Removes portable-identity structures introduced in versions 25 and 26 from an
	 * isolated, disposable historical database fixture.
	 *
	 * @param statement statement operating on the fixture connection
	 * @throws SQLException if schema cleanup fails
	 */
	public static void removeVersion25Objects(Statement statement) throws SQLException {
		if (statement == null) {
			throw new NullPointerException("statement");
		}
		removeVersion26Objects(statement);
		List<String> triggers = new ArrayList<>();
		try (Statement lookup = statement.getConnection().createStatement(); ResultSet result = lookup.executeQuery("""
				SELECT name
				FROM sqlite_master
				WHERE type = 'trigger'
				  AND name GLOB 'app_portable_*'
				ORDER BY name
				""")) {
			while (result.next()) {
				String name = result.getString("name");
				if (!name.matches(
						"app_portable_[a-z_]+_(insert|delete|id_update)" + "|app_portable_identity_immutable")) {
					throw new SQLException("Unexpected portable identity trigger: " + name);
				}
				triggers.add(name);
			}
		}
		for (String name : triggers) {
			statement.execute("DROP TRIGGER \"" + name + "\"");
		}
		statement.execute("DROP TABLE IF EXISTS portable_identity_aliases");
		statement.execute("DROP TABLE IF EXISTS portable_entity_retirements");
		statement.execute("DROP TABLE IF EXISTS portable_entity_identities");
		statement.execute("DROP TABLE IF EXISTS installation_identity");
	}

	/**
	 * Removes version-26 revision triggers from a disposable test fixture.
	 *
	 * @param statement statement operating on the fixture connection
	 * @throws SQLException if cleanup fails
	 */
	public static void removeVersion26Objects(Statement statement) throws SQLException {
		if (statement == null) {
			throw new NullPointerException("statement");
		}
		List<String> names = new ArrayList<>();
		try (Statement lookup = statement.getConnection().createStatement(); ResultSet result = lookup.executeQuery("""
				SELECT name
				FROM sqlite_master
				WHERE type = 'trigger'
				  AND name GLOB 'app_revision_*'
				ORDER BY name
				""")) {
			while (result.next()) {
				String name = result.getString("name");
				if (!name.matches("app_revision_[a-z_]+")) {
					throw new SQLException("Unexpected revision trigger: " + name);
				}
				names.add(name);
			}
		}
		for (String name : names) {
			statement.execute("DROP TRIGGER \"" + name + "\"");
		}
	}
}
