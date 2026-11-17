package au.edu.eq.questionbank.repository.sqlite;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Verifies booklet applicability-review invalidation triggers against their
 * versioned SQL migration definitions.
 *
 * <p>
 * Schema version 21 introduced the original invalidation rules. Version 23
 * replaced the broad booklet UPDATE rule with one that invalidates reviews only
 * after material booklet changes.
 *
 * <p>
 * Both installations are performed by SQL migrations in the caller-owned schema
 * migration transaction.
 */
final class BookletApplicabilityReviewInvalidationTriggers {

	private BookletApplicabilityReviewInvalidationTriggers() {
	}

	static void verify(Connection connection) throws SQLException {
		SqliteMigrationTriggerVerifier.verify(connection, "app_review_", "applicability-review invalidation",
				"/db/migration-v20-to-v21.sql", "/db/migration-v22-to-v23.sql");
	}

	static void verifyLegacy(Connection connection) throws SQLException {
		SqliteMigrationTriggerVerifier.verify(connection, "app_review_", "applicability-review invalidation",
				"/db/migration-v20-to-v21.sql");
	}
}
