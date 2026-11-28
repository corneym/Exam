package au.edu.eq.questionbank.repository.sqlite;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/** Owns installation and trigger verification for schema-v52 disposal state. */
final class SqliteCorpusDisposalSchema {

	private SqliteCorpusDisposalSchema() {
	}

	static void migrate(Connection connection) throws SQLException {
		Objects.requireNonNull(connection, "connection");
		String sql;
		try {
			sql = SqlResourceLoader.load("/db/migration-v51-to-v52.sql");
		} catch (IOException failure) {
			throw new SQLException("Unable to load contributor corpus-disposal migration", failure);
		}
		SqlScriptExecutor.execute(connection, sql);
		SqlitePortableRevisionHistoryBackfill.backfillAssignmentBaselines(connection);
		SqlitePortableIdentitySchema.installCacheEvictionDeleteTriggers(connection);
	}

	static void verifyTriggers(Connection connection) throws SQLException {
		Objects.requireNonNull(connection, "connection");
		SqliteMigrationTriggerVerifier.verify(connection, "trg_corpus_disposal_", "corpus disposal",
				"/db/migration-v51-to-v52.sql");
		SqliteMigrationTriggerVerifier.verify(connection, "trg_portable_cache_eviction_", "portable cache eviction",
				"/db/migration-v51-to-v52.sql");
		SqliteMigrationTriggerVerifier.verify(connection, "app_portable_revision_history_", "portable revision history",
				"/db/migration-v51-to-v52.sql");
	}
}
