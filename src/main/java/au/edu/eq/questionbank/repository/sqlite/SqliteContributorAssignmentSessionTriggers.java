package au.edu.eq.questionbank.repository.sqlite;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Verifies contributor assignment-session protection against the original
 * version 29 SQL migration.
 *
 * <p>
 * Installed baselines and assignment identity are immutable. Only the separate
 * active-selection row may change.
 */
final class SqliteContributorAssignmentSessionTriggers {

	private SqliteContributorAssignmentSessionTriggers() {
	}

	static void verify(Connection connection) throws SQLException {
		if (hasCorpusDisposalSchema(connection)) {
			SqliteMigrationTriggerVerifier.verify(connection, "trg_contributor_assignment_", "contributor assignment",
					"/db/migration-v28-to-v29.sql", "/db/migration-v51-to-v52.sql");
		} else {
			SqliteMigrationTriggerVerifier.verify(connection, "trg_contributor_assignment_", "contributor assignment",
					"/db/migration-v28-to-v29.sql");
		}
	}

	private static boolean hasCorpusDisposalSchema(Connection connection) throws SQLException {
		try (var statement = connection.prepareStatement("""
				SELECT 1
				FROM sqlite_master
				WHERE type = 'table'
				  AND name = 'corpus_disposal_operations'
				"""); var result = statement.executeQuery()) {
			return result.next();
		}
	}
}
