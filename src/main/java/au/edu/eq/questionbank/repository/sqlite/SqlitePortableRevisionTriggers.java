package au.edu.eq.questionbank.repository.sqlite;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Verifies the portable-record material-change revision triggers.
 *
 * <p>
 * Schema version 26 installs the original definitions directly from its
 * versioned SQL migration. Verification checks trigger names, owning tables and
 * complete SQL definitions.
 *
 * <p>
 * Direct entity changes and changes to owned child records advance revisions.
 * No-op updates preserve existing revisions.
 */
final class SqlitePortableRevisionTriggers {

	private SqlitePortableRevisionTriggers() {
	}

	static void verify(Connection connection) throws SQLException {
		SqliteMigrationTriggerVerifier.verify(connection, "app_revision_", "revision", "/db/migration-v25-to-v26.sql");
	}
}
