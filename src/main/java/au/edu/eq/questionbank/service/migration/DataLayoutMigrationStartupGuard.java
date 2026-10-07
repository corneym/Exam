package au.edu.eq.questionbank.service.migration;

import java.io.IOException;
import java.sql.SQLException;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

/**
 * Prevents the desktop application from opening a data root whose managed path
 * semantics still require migration.
 */
public final class DataLayoutMigrationStartupGuard {

	private final ApplicationConfig config;
	private final SqliteDatabase database;

	/**
	 * Creates a startup migration guard.
	 *
	 * @param config   application paths
	 * @param database initialised database
	 */
	public DataLayoutMigrationStartupGuard(ApplicationConfig config, SqliteDatabase database) {
		if (config == null) {
			throw new NullPointerException("config");
		}
		if (database == null) {
			throw new NullPointerException("database");
		}
		this.config = config;
		this.database = database;
	}

	/**
	 * Verifies that normal Subject-first runtime semantics are safe.
	 *
	 * @throws DataLayoutMigrationRequiredException if migration remains
	 * @throws SQLException                         if persistence cannot be
	 *                                              inspected
	 * @throws IOException                          if managed roots cannot be
	 *                                              inspected
	 */
	public void requireCurrentLayout() throws SQLException, IOException {
		DataLayoutMigrationPlan plan = new DataLayoutMigrationPlanner(config, database).plan();
		if (plan.migrationRequired()) {
			throw new DataLayoutMigrationRequiredException(plan);
		}
	}
}
