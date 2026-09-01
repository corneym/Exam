package au.edu.eq.questionbank.repository.sqlite;

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

	private static final int LATEST_SCHEMA_VERSION = 3;
	private static final List<String> VERSION_ONE_TABLES = List.of("schema_version", "subjects", "syllabus_versions",
			"curriculum_nodes", "exam_providers", "source_documents", "exams", "exam_booklets", "questions",
			"question_regions", "answer_files", "answers", "answer_regions");

	/**
	 * Returns the latest database schema version supported by this application.
	 *
	 * @return the latest supported schema version
	 */
	public static int latestSchemaVersion() {
		return LATEST_SCHEMA_VERSION;
	}

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
	 * version metadata, or missing required schema structures for their recorded
	 * version, are rejected rather than repaired implicitly.
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

	/**
	 * Returns the SQLite runtime version used by the current JDBC driver.
	 *
	 * @return the SQLite runtime version
	 * @throws SQLException if the version cannot be read
	 */
	public String sqliteVersion() throws SQLException {
		try (Connection connection = openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("SELECT sqlite_version()")) {
			if (!result.next()) {
				throw new SQLException("SQLite did not return a version");
			}
			return result.getString(1);
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

	private int foreignKeyColumnCount(Connection connection, int foreignKeyId) throws SQLException {
		int count = 0;
		try (Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("PRAGMA foreign_key_list(curriculum_mapping_reviews)")) {
			while (result.next()) {
				if (result.getInt("id") == foreignKeyId) {
					count++;
				}
			}
		}
		return count;
	}

	private boolean hasExactSingleColumnForeignKey(Connection connection, String fromColumn, String targetTable,
			String targetColumn) throws SQLException {
		try (Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("PRAGMA foreign_key_list(curriculum_mapping_reviews)")) {
			while (result.next()) {
				if (result.getInt("seq") != 0 || !fromColumn.equals(result.getString("from"))
						|| !targetTable.equals(result.getString("table"))
						|| !targetColumn.equals(result.getString("to"))) {
					continue;
				}
				int foreignKeyId = result.getInt("id");
				if (foreignKeyColumnCount(connection, foreignKeyId) == 1) {
					return true;
				}
			}
		}
		return false;
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

	private int migrate(Connection connection, int version) throws SQLException {
		if (version == 1) {
			executeMigration(connection, "/db/migration-v1-to-v2.sql", 2);
			return 2;
		}
		if (version == 2) {
			executeMigration(connection, "/db/migration-v2-to-v3.sql", 3);
			return 3;
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

	private void verifyCurriculumMappingReviewForeignKeys(Connection connection) throws SQLException {
		if (!hasExactSingleColumnForeignKey(connection, "source_node_id", "curriculum_nodes", "id")) {
			throw new SQLException(
					"curriculum_mapping_reviews is missing exact foreign key source_node_id -> curriculum_nodes(id)");
		}
		if (!hasExactSingleColumnForeignKey(connection, "target_syllabus_version_id", "syllabus_versions", "id")) {
			throw new SQLException(
					"curriculum_mapping_reviews is missing exact foreign key target_syllabus_version_id -> syllabus_versions(id)");
		}
	}

	private void verifyCurriculumMappingReviewSchema(Connection connection) throws SQLException {
		boolean hasSourceNodeId = false;
		boolean hasTargetVersionId = false;
		boolean hasReviewOutcome = false;
		int sourcePrimaryKeyPosition = 0;
		int targetPrimaryKeyPosition = 0;
		int outcomePrimaryKeyPosition = 0;
		int primaryKeyColumnCount = 0;
		try (Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("PRAGMA table_info(curriculum_mapping_reviews)")) {
			while (result.next()) {
				String columnName = result.getString("name");
				int primaryKeyPosition = result.getInt("pk");
				if (primaryKeyPosition > 0) {
					primaryKeyColumnCount++;
				}
				if ("source_node_id".equals(columnName)) {
					hasSourceNodeId = true;
					sourcePrimaryKeyPosition = primaryKeyPosition;
				} else if ("target_syllabus_version_id".equals(columnName)) {
					hasTargetVersionId = true;
					targetPrimaryKeyPosition = primaryKeyPosition;
				} else if ("review_outcome".equals(columnName)) {
					hasReviewOutcome = true;
					outcomePrimaryKeyPosition = primaryKeyPosition;
				}
			}
		}
		if (!hasSourceNodeId) {
			throw new SQLException("curriculum_mapping_reviews is missing required column source_node_id");
		}
		if (!hasTargetVersionId) {
			throw new SQLException("curriculum_mapping_reviews is missing required column target_syllabus_version_id");
		}
		if (!hasReviewOutcome) {
			throw new SQLException("curriculum_mapping_reviews is missing required column review_outcome");
		}
		if (primaryKeyColumnCount != 2 || sourcePrimaryKeyPosition != 1 || targetPrimaryKeyPosition != 2
				|| outcomePrimaryKeyPosition != 0) {
			throw new SQLException(
					"curriculum_mapping_reviews has an invalid primary key; expected exactly (source_node_id, target_syllabus_version_id)");
		}
		verifyCurriculumMappingReviewForeignKeys(connection);
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
		if (version >= 3) {
			if (!tableExists(connection, "curriculum_mapping_reviews")) {
				throw new SQLException(
						"Database schema version " + version + " is missing required table curriculum_mapping_reviews");
			}
			verifyCurriculumMappingReviewSchema(connection);
		}
	}
}
