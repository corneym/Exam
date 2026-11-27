package au.edu.eq.questionbank.repository.sqlite;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Verifies contributor return-ledger protection against the authoritative
 * version 34 SQL migration.
 *
 * <p>
 * Installation is owned entirely by the SQL migration. Published return
 * evidence must remain append-only.
 */
final class SqliteContributorReturnLedgerTriggers {

	private SqliteContributorReturnLedgerTriggers() {
	}

	static void verify(Connection connection) throws SQLException {
		SqliteMigrationTriggerVerifier.verify(connection, "trg_contributor_returns_", "contributor return",
				"/db/migration-v33-to-v34.sql", "/db/migration-v49-to-v50.sql");
	}

	static void verifyLegacy(Connection connection) throws SQLException {
		SqliteMigrationTriggerVerifier.verify(connection, "trg_contributor_returns_", "contributor return",
				"/db/migration-v33-to-v34.sql");
	}
}
