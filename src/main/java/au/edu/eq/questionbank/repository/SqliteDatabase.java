package au.edu.eq.questionbank.repository;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Owns the SQLite database location, initialises the question-bank schema, and
 * opens connections with foreign-key enforcement enabled.
 */
public final class SqliteDatabase {

	private static final int LATEST_SCHEMA_VERSION = 2;
	private static final List<String> VERSION_ONE_TABLES = List.of("schema_version", "subjects", "syllabus_versions",
			"curriculum_nodes", "exam_providers", "source_documents", "exams", "exam_booklets", "questions",
			"question_regions", "answer_files", "answers", "answer_regions");

	private final Path databasePath;

	/**
	 * Creates a database boundary for the supplied SQLite file.
	 *
	 * @param databasePath the SQLite database file; converted to a normalized
	 *                     absolute path
	 * @throws NullPointerException if {@code databasePath} is {@code null}
	 */
	public SqliteDatabase(Path databasePath) {
		if (databasePath == null) {
			throw new NullPointerException("databasePath");
		}
		this.databasePath = databasePath.toAbsolutePath().normalize();
	}

	/**
	 * Creates a new database and applies all migrations, or upgrades an existing
	 * supported database sequentially to the latest schema version. Creation and
	 * migration run in one transaction. Existing databases with missing or invalid
	 * version metadata, or missing tables required by their recorded version, are
	 * rejected rather than repaired implicitly.
	 *
	 * @throws SQLException if the schema cannot be created, the version information
	 *                      is invalid, or the database is newer than the
	 *                      application
	 */
	public void initialiseSchema() throws SQLException {
		try (Connection connection = openConnection()) {
			connection.setAutoCommit(false);
			try {
				int version = readSchemaVersion(connection);
				if (version == 0) {
					if (hasUserSchemaObjects(connection)) {
						throw new SQLException("Existing database has no schema_version table");
					}
					createVersionOneSchema(connection);
					version = 1;
				}
				if (version > LATEST_SCHEMA_VERSION) {
					throw new SQLException("Unsupported database schema version " + version
							+ "; latest supported version is " + LATEST_SCHEMA_VERSION);
				}
				verifySchema(connection, version);
				while (version < LATEST_SCHEMA_VERSION) {
					version = migrate(connection, version);
				}
				verifySchema(connection, LATEST_SCHEMA_VERSION);
				connection.commit();
			} catch (SQLException | RuntimeException e) {
				try {
					connection.rollback();
				} catch (SQLException rollbackFailure) {
					e.addSuppressed(rollbackFailure);
				}
				throw e;
			}
		}
	}

	/**
	 * Opens a caller-owned connection with SQLite foreign-key enforcement enabled.
	 *
	 * @return an open connection that the caller must close
	 * @throws SQLException if the connection cannot be opened or configured
	 */
	public Connection openConnection() throws SQLException {
		Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);

		try {
			try (Statement statement = connection.createStatement()) {
				statement.execute("PRAGMA foreign_keys = ON");
			}
			return connection;
		} catch (SQLException e) {
			try {
				connection.close();
			} catch (SQLException closeFailure) {
				e.addSuppressed(closeFailure);
			}
			throw e;
		}
	}

	private void createVersionOneSchema(Connection connection) throws SQLException {
		String sql;
		try {
			sql = SqlResourceLoader.load("/db/schema-v1.sql");
		} catch (IOException e) {
			throw new SQLException("Unable to load schema resource", e);
		}
		SqlScriptExecutor.execute(connection, sql);
		int version = readSchemaVersion(connection);
		if (version != 1) {
			throw new SQLException("Schema creation did not produce version 1");
		}
		verifySchema(connection, 1);
	}

	private void executeMigration(Connection connection, String resourcePath, int expectedVersion) throws SQLException {
		String sql;
		try {
			sql = SqlResourceLoader.load(resourcePath);
		} catch (IOException e) {
			throw new SQLException("Unable to load migration resource: " + resourcePath, e);
		}
		SqlScriptExecutor.execute(connection, sql);
		int actualVersion = readSchemaVersion(connection);
		if (actualVersion != expectedVersion) {
			throw new SQLException(
					"Migration did not produce schema version " + expectedVersion + "; found " + actualVersion);
		}
		verifySchema(connection, expectedVersion);
	}

	private int migrate(Connection connection, int version) throws SQLException {
		if (version == 1) {
			executeMigration(connection, "/db/migration-v1-to-v2.sql", 2);
			return 2;
		}
		throw new SQLException("No migration available from schema version " + version);
	}

	private int readSchemaVersion(Connection connection) throws SQLException {
		if (!schemaVersionTableExists(connection)) {
			return 0;
		}
		try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("""
				SELECT version, typeof(version) AS version_type
				FROM schema_version
				""")) {
			if (!result.next()) {
				throw new SQLException("schema_version table is empty");
			}
			String versionType = result.getString("version_type");
			long storedVersion = result.getLong("version");
			if (!"integer".equals(versionType) || storedVersion < 1 || storedVersion > Integer.MAX_VALUE) {
				throw new SQLException("Invalid database schema version: " + result.getString("version"));
			}
			if (result.next()) {
				throw new SQLException("schema_version contains more than one row");
			}
			return (int) storedVersion;
		}
	}

	private boolean schemaVersionTableExists(Connection connection) throws SQLException {
		return tableExists(connection, "schema_version");
	}

	private boolean hasUserSchemaObjects(Connection connection) throws SQLException {
		try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("""
				SELECT 1
				FROM sqlite_master
				WHERE type IN ('table', 'view', 'trigger')
				  AND name NOT LIKE 'sqlite_%'
				LIMIT 1
				""")) {
			return result.next();
		}
	}

	private boolean tableExists(Connection connection, String tableName) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT 1
				FROM sqlite_master
				WHERE type = 'table'
				  AND name = ?
				""")) {
			statement.setString(1, tableName);
			try (ResultSet result = statement.executeQuery()) {
				return result.next();
			}
		}
	}

	private void verifySchema(Connection connection, int version) throws SQLException {
		for (String tableName : VERSION_ONE_TABLES) {
			if (!tableExists(connection, tableName)) {
				throw new SQLException(
						"Database schema version " + version + " is missing required table " + tableName);
			}
		}
		if (version >= 2 && !tableExists(connection, "curriculum_mappings")) {
			throw new SQLException(
					"Database schema version " + version + " is missing required table curriculum_mappings");
		}
	}
}
