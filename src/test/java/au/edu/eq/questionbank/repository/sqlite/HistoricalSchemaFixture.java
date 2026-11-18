package au.edu.eq.questionbank.repository.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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

	public static void removeAssignmentGuardObjects(Statement statement) throws SQLException {
		if (statement == null) {
			throw new NullPointerException("statement");
		}
		removeAssignmentScopeObjects(statement);
		dropAssignmentTriggers(statement, "trg_contributor_structure_*");
	}

	public static void removeAssignmentScopeObjects(Statement statement) throws SQLException {
		if (statement == null) {
			throw new NullPointerException("statement");
		}
		dropAssignmentTriggers(statement, "trg_contributor_scope_*");
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
	 * Removes version-26 revision triggers and newer import-ledger structures from
	 * a disposable historical test fixture.
	 *
	 * @param statement statement operating on the fixture connection
	 * @throws SQLException if cleanup fails
	 */
	public static void removeVersion26Objects(Statement statement) throws SQLException {
		if (statement == null) {
			throw new NullPointerException("statement");
		}
		removeVersion27Objects(statement);
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

	/**
	 * Removes Subject import-ledger structures introduced in v27, after removing
	 * any newer assignment-ledger structures.
	 *
	 * <p>
	 * Used only for disposable historical migration test fixtures.
	 *
	 * @param statement statement operating on the fixture connection
	 * @throws SQLException if cleanup fails
	 */
	public static void removeVersion27Objects(Statement statement) throws SQLException {
		if (statement == null) {
			throw new NullPointerException("statement");
		}
		removeVersion28Objects(statement);
		statement.execute("DROP TABLE IF EXISTS subject_import_decisions");
		statement.execute("DROP TABLE IF EXISTS subject_import_packages");
	}

	/**
	 * Removes assignment-ledger structures introduced in version 28, after removing
	 * newer contributor-session structures.
	 *
	 * <p>
	 * Only for disposable historical migration fixtures.
	 *
	 * @param statement statement operating on the fixture connection
	 * @throws SQLException if cleanup fails
	 */
	public static void removeVersion28Objects(Statement statement) throws SQLException {
		if (statement == null) {
			throw new NullPointerException("statement");
		}
		removeVersion29Objects(statement);
		statement.execute("DROP TABLE IF EXISTS exam_work_assignment_states");
		statement.execute("DROP TABLE IF EXISTS exam_work_assignments");
	}

	/**
	 * Removes contributor assignment-session structures introduced in schema
	 * version 29.
	 *
	 * <p>
	 * Only for disposable historical migration fixtures. This method must never be
	 * used on production data.
	 *
	 * @param statement statement operating on the fixture connection
	 * @throws SQLException if cleanup fails
	 */
	public static void removeVersion29Objects(Statement statement) throws SQLException {
		if (statement == null) {
			throw new NullPointerException("statement");
		}

		// Remove v34 dependencies before removing the
		// contributor assignment-session tables.
		removeVersion34Objects(statement);

		// Remove the newer assignment protection triggers.
		removeAssignmentGuardObjects(statement);
		statement.execute("DROP TABLE IF EXISTS contributor_assignment_selection");
		statement.execute("DROP TABLE IF EXISTS contributor_assignment_sessions");
	}

	/**
	 * Removes version 34 contributor-return structures from a disposable historical
	 * migration test database.
	 *
	 * <p>
	 * Newer schema structures are removed first. This method must never be used
	 * against production data.
	 *
	 * @param statement statement for the disposable test database
	 * @throws SQLException if cleanup fails
	 */
	public static void removeVersion34Objects(Statement statement) throws SQLException {
		if (statement == null) {
			throw new NullPointerException("statement");
		}

		// Remove version 35 before reconstructing an older schema.
		removeVersion35Objects(statement);

		// Remove version 34 immutability guards and their table.
		statement.execute("DROP TRIGGER IF EXISTS trg_contributor_returns_no_delete");
		statement.execute("DROP TRIGGER IF EXISTS trg_contributor_returns_no_update");
		statement.execute("DROP TABLE IF EXISTS contributor_return_packages");
	}

	/**
	 * Removes version 35 coordinator contribution-intake structures from a
	 * disposable historical migration test database.
	 *
	 * <p>
	 * Version 36 review evidence is removed first. This method must never be used
	 * against production data.
	 *
	 * @param statement statement for the disposable test database
	 * @throws SQLException if cleanup fails
	 */
	public static void removeVersion35Objects(Statement statement) throws SQLException {
		if (statement == null) {
			throw new NullPointerException("statement");
		}
		removeVersion36Objects(statement);
		statement.execute("DROP TRIGGER IF EXISTS trg_contribution_intake_proposals_no_delete");
		statement.execute("DROP TRIGGER IF EXISTS trg_contribution_intake_proposals_no_update");
		statement.execute("DROP TABLE IF EXISTS contribution_intake_proposals");
		statement.execute("DROP TRIGGER IF EXISTS trg_contribution_intake_packages_no_delete");
		statement.execute("DROP TRIGGER IF EXISTS trg_contribution_intake_packages_no_update");
		statement.execute("DROP TABLE IF EXISTS contribution_intake_packages");
	}

	/**
	 * Removes schema v36 review evidence and all newer application evidence from a
	 * disposable historical migration test database.
	 *
	 * <p>
	 * This method must never be used against production data.
	 *
	 * @param statement statement for the disposable test database
	 * @throws SQLException if cleanup fails
	 */
	public static void removeVersion36Objects(Statement statement) throws SQLException {
		if (statement == null) {
			throw new NullPointerException("statement");
		}
		removeVersion37Objects(statement);
		statement.execute("DROP TRIGGER IF EXISTS trg_contribution_review_decisions_no_delete");
		statement.execute("DROP TRIGGER IF EXISTS trg_contribution_review_decisions_no_update");
		statement.execute("DROP TABLE IF EXISTS contribution_review_decisions");
		statement.execute("DROP TRIGGER IF EXISTS trg_contribution_review_batches_no_delete");
		statement.execute("DROP TRIGGER IF EXISTS trg_contribution_review_batches_no_update");
		statement.execute("DROP TABLE IF EXISTS contribution_review_batches");
	}

	/**
	 * Removes schema v37 application evidence and all newer structures from a
	 * disposable historical migration test database.
	 *
	 * @param statement statement for the disposable test database
	 * @throws SQLException if cleanup fails
	 */
	public static void removeVersion37Objects(Statement statement) throws SQLException {
		if (statement == null) {
			throw new NullPointerException("statement");
		}
		removeVersion38Objects(statement);
		statement.execute("DROP TRIGGER IF EXISTS trg_contribution_application_results_no_delete");
		statement.execute("DROP TRIGGER IF EXISTS trg_contribution_application_results_no_update");
		statement.execute("DROP TABLE IF EXISTS contribution_application_results");
		statement.execute("DROP TRIGGER IF EXISTS trg_contribution_application_batches_no_delete");
		statement.execute("DROP TRIGGER IF EXISTS trg_contribution_application_batches_no_update");
		statement.execute("DROP TABLE IF EXISTS contribution_application_batches");
	}

	/**
	 * Removes schema v38 assignment lifecycle structures from an isolated,
	 * disposable historical migration fixture.
	 *
	 * <p>
	 * This method must never be applied to production data. It removes the v38
	 * triggers before dropping closure evidence and assignment claims, so older
	 * migrations can be replayed.
	 *
	 * @param statement statement for the disposable test database
	 * @throws SQLException         if lifecycle structures cannot be removed
	 * @throws NullPointerException if statement is null
	 */
	public static void removeVersion38Objects(Statement statement) throws SQLException {
		Objects.requireNonNull(statement, "statement");
		removeVersion39Objects(statement);
		List<String> triggerNames = new ArrayList<>();
		try (PreparedStatement lookup = statement.getConnection().prepareStatement("""
				SELECT name
				FROM sqlite_master
				WHERE type = 'trigger'
				  AND name GLOB 'trg_master_assignment_*'
				ORDER BY name
				"""); ResultSet result = lookup.executeQuery()) {
			while (result.next()) {
				String name = result.getString(1);
				if (!name.matches("trg_master_assignment_[a-z_]+")) {
					throw new SQLException("Unexpected master assignment trigger: " + name);
				}
				triggerNames.add(name);
			}
		}
		for (String name : triggerNames) {
			statement.execute("DROP TRIGGER \"" + name + "\"");
		}
		statement.execute("DROP TABLE IF EXISTS exam_work_assignment_closures");
		statement.execute("DROP TABLE IF EXISTS exam_work_assignment_exam_locks");
	}

	/**
	 * Removes v39 master Exam structural guards from a disposable historical
	 * migration test database.
	 *
	 * <p>
	 * This method must never be applied to production data.
	 *
	 * @param statement statement for the disposable test database
	 * @throws SQLException         if trigger removal fails
	 * @throws NullPointerException if statement is null
	 */
	public static void removeVersion39Objects(Statement statement) throws SQLException {
		Objects.requireNonNull(statement, "statement");
		removeVersion40Objects(statement);
		List<String> triggerNames = new ArrayList<>();
		try (PreparedStatement lookup = statement.getConnection().prepareStatement("""
				SELECT name
				FROM sqlite_master
				WHERE type = 'trigger'
				  AND name GLOB 'trg_master_exam_structure_*'
				ORDER BY name
				"""); ResultSet result = lookup.executeQuery()) {
			while (result.next()) {
				String name = result.getString(1);
				if (!name.matches("trg_master_exam_structure_[a-z_]+")) {
					throw new SQLException("Unexpected master Exam structure trigger: " + name);
				}
				triggerNames.add(name);
			}
		}
		for (String name : triggerNames) {
			statement.execute("DROP TRIGGER \"" + name + "\"");
		}
	}

	/**
	 * Removes version 40 master capture and return-validation triggers from a
	 * disposable historical schema test database.
	 *
	 * <p>
	 * This method must never be used against production data.
	 *
	 * @param statement statement for the disposable database
	 * @throws SQLException         if trigger discovery or removal fails
	 * @throws NullPointerException if statement is null
	 */
	public static void removeVersion40Objects(Statement statement) throws SQLException {
		Objects.requireNonNull(statement, "statement");
		removeVersion41Objects(statement);
		List<String> triggerNames = new ArrayList<>();
		try (PreparedStatement lookup = statement.getConnection().prepareStatement("""
				SELECT name
				FROM sqlite_master
				WHERE type = 'trigger'
				  AND (
				      name GLOB 'trg_master_capture_*'
				      OR name GLOB 'trg_master_return_*'
				  )
				ORDER BY name
				"""); ResultSet result = lookup.executeQuery()) {
			while (result.next()) {
				String name = result.getString(1);
				if (!name.matches("trg_master_(capture|return)_[a-z_]+")) {
					throw new SQLException("Unexpected version 40 trigger: " + name);
				}
				triggerNames.add(name);
			}
		}
		for (String name : triggerNames) {
			statement.execute("DROP TRIGGER \"" + name + "\"");
		}
	}

	/**
	 * Removes v41 scalar-application tables and triggers from a disposable
	 * historical migration fixture.
	 *
	 * <p>
	 * This operation is intended only as preparation for also removing v40 objects
	 * and replaying historical migrations. It does not independently reconstruct
	 * the four original v40 capture trigger definitions.
	 *
	 * @param statement statement for the disposable database
	 * @throws SQLException         if schema cleanup fails
	 * @throws NullPointerException if statement is null
	 */
	public static void removeVersion41Objects(Statement statement) throws SQLException {
		Objects.requireNonNull(statement, "statement");
		removeVersion42Objects(statement);
		List<String> names = new ArrayList<>();
		try (PreparedStatement lookup = statement.getConnection().prepareStatement("""
				SELECT name
				FROM sqlite_master
				WHERE type = 'trigger'
				  AND name GLOB 'trg_master_application_*'
				ORDER BY name
				"""); ResultSet result = lookup.executeQuery()) {
			while (result.next()) {
				String name = result.getString(1);
				if (!name.matches("trg_master_application_[a-z_]+")) {
					throw new SQLException("Unexpected v41 application trigger: " + name);
				}
				names.add(name);
			}
		}
		for (String name : names) {
			statement.execute("DROP TRIGGER \"" + name + "\"");
		}
		statement.execute("""
				DROP TABLE IF EXISTS master_contribution_write_permits
				""");
		statement.execute("""
				DROP TABLE IF EXISTS contribution_application_entity_mappings
				""");
	}

	/**
	 * Removes version 42 region-authorisation objects from a disposable historical
	 * migration fixture.
	 *
	 * <p>
	 * This is used before removing the v41 and older schemas. It does not
	 * reconstruct the six original v40 capture triggers; callers requiring a
	 * complete older schema must continue the historical cleanup chain.
	 *
	 * @param statement statement for the disposable database
	 * @throws SQLException         if schema cleanup fails
	 * @throws NullPointerException if statement is null
	 */
	public static void removeVersion42Objects(Statement statement) throws SQLException {
		Objects.requireNonNull(statement, "statement");
		removeVersion43Objects(statement);
		List<String> triggerNames = new ArrayList<>();
		try (PreparedStatement lookup = statement.getConnection().prepareStatement("""
				SELECT name
				FROM sqlite_master
				WHERE type = 'trigger'
				  AND name GLOB 'trg_master_region_*'
				ORDER BY name
				"""); ResultSet result = lookup.executeQuery()) {
			while (result.next()) {
				String name = result.getString(1);
				if (!name.matches("trg_master_region_[a-z_]+")) {
					throw new SQLException("Unexpected version 42 trigger: " + name);
				}
				triggerNames.add(name);
			}
		}
		for (String name : triggerNames) {
			statement.execute("DROP TRIGGER \"" + name + "\"");
		}
		statement.execute("""
				DROP TABLE IF EXISTS master_contribution_region_permits
				""");
	}

	/**
	 * Removes receipt acknowledgement structures from a disposable historical
	 * migration fixture.
	 *
	 * <p>
	 * Production databases must never use this method because receipt
	 * acknowledgements are permanent audit evidence.
	 *
	 * @param statement statement for a disposable test database
	 * @throws SQLException         if schema cleanup fails
	 * @throws NullPointerException if statement is null
	 */
	public static void removeVersion43Objects(Statement statement) throws SQLException {
		Objects.requireNonNull(statement, "statement");
		statement.execute("""
				DROP TRIGGER IF EXISTS
				    trg_contributor_receipt_ack_validate
				""");
		statement.execute("""
				DROP TRIGGER IF EXISTS
				    trg_contributor_receipt_ack_no_update
				""");
		statement.execute("""
				DROP TRIGGER IF EXISTS
				    trg_contributor_receipt_ack_no_delete
				""");
		statement.execute("""
				DROP TABLE IF EXISTS
				    contributor_receipt_acknowledgements
				""");
	}

	private static void dropAssignmentTriggers(Statement statement, String pattern) throws SQLException {
		List<String> names = new ArrayList<>();
		try (PreparedStatement lookup = statement.getConnection().prepareStatement("""
				SELECT name
				FROM sqlite_master
				WHERE type = 'trigger'
				  AND name GLOB ?
				ORDER BY name
				""")) {
			lookup.setString(1, pattern);
			try (ResultSet result = lookup.executeQuery()) {
				while (result.next()) {
					String name = result.getString(1);
					if (!name.matches("trg_contributor_(structure|scope)_[a-z_]+")) {
						throw new SQLException("Unexpected contributor trigger: " + name);
					}
					names.add(name);
				}
			}
		}
		for (String name : names) {
			statement.execute("DROP TRIGGER \"" + name + "\"");
		}
	}
}
