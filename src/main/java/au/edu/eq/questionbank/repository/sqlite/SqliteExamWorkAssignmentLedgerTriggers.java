package au.edu.eq.questionbank.repository.sqlite;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Verifies database-level protection of assignment issuance evidence and
 * lifecycle transitions against the version 28 SQL migration.
 */
final class SqliteExamWorkAssignmentLedgerTriggers {

	private SqliteExamWorkAssignmentLedgerTriggers() {
	}

	static void verify(Connection connection) throws SQLException {
		SqliteMigrationTriggerVerifier.verify(connection, "trg_exam_work_assignment", "assignment protection",
				"/db/migration-v27-to-v28.sql");
	}
}
