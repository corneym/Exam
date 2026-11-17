package au.edu.eq.questionbank.repository.sqlite;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Verifies contributor structural write barriers against their versioned SQL
 * migration definitions.
 *
 * <p>
 * Schema version 30 introduced the original structural guards. Version 32
 * replaced the booklet-update protection to permit assignment-owned pending MCQ
 * context changes.
 *
 * <p>
 * Installation is owned entirely by the SQL migrations.
 */
final class SqliteAssignmentStructureTriggers {

	private SqliteAssignmentStructureTriggers() {
	}

	static void verify(Connection connection) throws SQLException {
		SqliteMigrationTriggerVerifier.verify(connection, "trg_contributor_structure_", "contributor structure",
				"/db/migration-v29-to-v30.sql", "/db/migration-v31-to-v32.sql");
	}

	static void verifyLegacy(Connection connection) throws SQLException {
		SqliteMigrationTriggerVerifier.verify(connection, "trg_contributor_structure_", "contributor structure",
				"/db/migration-v29-to-v30.sql");
	}
}
