package au.edu.eq.questionbank.repository.sqlite;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Verifies contributor capture and classification scope guards against their
 * versioned SQL migration definitions.
 *
 * <p>
 * Schema version 31 introduced the original assignment scope restrictions.
 * Version 33 added classification-level enforcement while retaining historical
 * compatibility.
 *
 * <p>
 * Installation is owned entirely by the SQL migrations.
 */
final class SqliteAssignmentScopeTriggers {

	private SqliteAssignmentScopeTriggers() {
	}

	static void verify(Connection connection) throws SQLException {
		SqliteMigrationTriggerVerifier.verify(connection, "trg_contributor_scope_", "contributor scope",
				"/db/migration-v30-to-v31.sql", "/db/migration-v32-to-v33.sql");
	}

	static void verifyLegacy(Connection connection) throws SQLException {
		SqliteMigrationTriggerVerifier.verify(connection, "trg_contributor_scope_", "contributor scope",
				"/db/migration-v30-to-v31.sql");
	}
}
