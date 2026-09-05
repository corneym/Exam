package au.edu.eq.questionbank.repository.sqlite;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Owns the SQLite database location, initialises the question-bank schema, and
 * opens connections with foreign-key enforcement enabled.
 */
public final class SqliteDatabase {

	private static final int LATEST_SCHEMA_VERSION = 4;
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
	 * Returns the latest database schema version supported by this application.
	 *
	 * @return the latest supported schema version
	 */
	public static int latestSchemaVersion() {
		return LATEST_SCHEMA_VERSION;
	}

	/**
	 * Creates a transactionally consistent SQLite snapshot using
	 * {@code VACUUM INTO}, then reopens and validates the resulting database.
	 * <p>
	 * The destination must not already exist. If snapshot creation or validation
	 * fails, any partial destination file is removed.
	 *
	 * @param snapshotPath destination SQLite file
	 * @throws IOException              if filesystem preparation or cleanup fails
	 * @throws SQLException             if snapshot creation or validation fails
	 * @throws NullPointerException     if {@code snapshotPath} is {@code null}
	 * @throws IllegalArgumentException if the destination is the live database
	 */
	public void createConsistentSnapshot(Path snapshotPath) throws IOException, SQLException {
		if (snapshotPath == null) {
			throw new NullPointerException("snapshotPath");
		}
		Path normalisedSnapshotPath = snapshotPath.toAbsolutePath().normalize();
		if (normalisedSnapshotPath.equals(databasePath)) {
			throw new IllegalArgumentException("Snapshot destination must not be the live database");
		}
		if (!Files.isRegularFile(databasePath)) {
			throw new IOException("Database file does not exist: " + databasePath);
		}
		if (Files.exists(normalisedSnapshotPath)) {
			throw new IOException("Snapshot destination already exists: " + normalisedSnapshotPath);
		}
		Path parentDirectory = normalisedSnapshotPath.getParent();
		if (parentDirectory != null) {
			Files.createDirectories(parentDirectory);
		}
		int sourceSchemaVersion = schemaVersion();
		if (sourceSchemaVersion == 0) {
			throw new SQLException("Database does not contain a question-bank schema");
		}
		if (sourceSchemaVersion > LATEST_SCHEMA_VERSION) {
			throw new SQLException("Unsupported database schema version " + sourceSchemaVersion
					+ "; latest supported version is " + LATEST_SCHEMA_VERSION);
		}
		try {
			try (Connection connection = openConnection();
					PreparedStatement statement = connection.prepareStatement("VACUUM INTO ?")) {
				statement.setString(1, normalisedSnapshotPath.toString());
				statement.execute();
			}
			SqliteDatabase snapshotDatabase = new SqliteDatabase(normalisedSnapshotPath);
			int snapshotSchemaVersion = snapshotDatabase.schemaVersion();
			if (snapshotSchemaVersion != sourceSchemaVersion) {
				throw new SQLException("Snapshot schema version " + snapshotSchemaVersion
						+ " does not match source schema version " + sourceSchemaVersion);
			}
			snapshotDatabase.verifySchema();
			snapshotDatabase.verifyIntegrity();
		} catch (SQLException | RuntimeException e) {
			try {
				Files.deleteIfExists(normalisedSnapshotPath);
			} catch (IOException cleanupFailure) {
				e.addSuppressed(cleanupFailure);
			}
			throw e;
		}
	}

	/**
	 * Creates a new database and applies all migrations, or upgrades an existing
	 * supported database sequentially to the latest schema version. Creation and
	 * migration run in one transaction. Existing databases with missing or invalid
	 * version metadata, or missing required schema structures for their recorded
	 * version, are rejected rather than repaired implicitly.
	 *
	 * @throws SQLException                  if the schema cannot be created, the
	 *                                       version information is invalid, or the
	 *                                       database is newer than the application
	 * @throws IncompatibleDatabaseException if a version-three database contains
	 *                                       disposable development question data
	 *                                       for which no lossless v4 migration is
	 *                                       defined
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
	 * Returns the schema version recorded by this database.
	 *
	 * @return the recorded schema version, or {@code 0} if no schema-version table
	 *         exists
	 * @throws SQLException if the schema version cannot be read or is invalid
	 */
	public int schemaVersion() throws SQLException {
		try (Connection connection = openConnection()) {
			return readSchemaVersion(connection);
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

	/**
	 * Verifies the physical integrity and foreign-key consistency of this database.
	 *
	 * @throws SQLException if an integrity problem or foreign-key violation is
	 *                      found, or if the checks cannot be completed
	 */
	public void verifyIntegrity() throws SQLException {
		try (Connection connection = openConnection()) {
			verifyDatabaseIntegrity(connection);
			verifyForeignKeyIntegrity(connection);
		}
	}

	/**
	 * Verifies that this database contains a supported and structurally valid
	 * question-bank schema without modifying or migrating it.
	 *
	 * @throws SQLException if the schema is missing, unsupported, or structurally
	 *                      invalid
	 */
	public void verifySchema() throws SQLException {
		try (Connection connection = openConnection()) {
			int version = readSchemaVersion(connection);
			if (version == 0) {
				throw new SQLException("Database does not contain a question-bank schema");
			}
			if (version > LATEST_SCHEMA_VERSION) {
				throw new SQLException("Unsupported database schema version " + version
						+ "; latest supported version is " + LATEST_SCHEMA_VERSION);
			}
			verifySchema(connection, version);
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

	private int foreignKeyColumnCount(Connection connection, String tableName, int foreignKeyId) throws SQLException {
		int count = 0;
		try (Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("PRAGMA foreign_key_list(" + tableName + ")")) {
			while (result.next()) {
				if (result.getInt("id") == foreignKeyId) {
					count++;
				}
			}
		}
		return count;
	}

	private boolean hasExactSingleColumnForeignKey(Connection connection, String tableName, String fromColumn,
			String targetTable, String targetColumn) throws SQLException {
		try (Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("PRAGMA foreign_key_list(" + tableName + ")")) {
			while (result.next()) {
				if (result.getInt("seq") != 0 || !fromColumn.equals(result.getString("from"))
						|| !targetTable.equals(result.getString("table"))
						|| !targetColumn.equals(result.getString("to"))) {
					continue;
				}
				int foreignKeyId = result.getInt("id");
				if (foreignKeyColumnCount(connection, tableName, foreignKeyId) == 1) {
					return true;
				}
			}
		}
		return false;
	}

	private boolean hasExactUniqueIndex(Connection connection, String tableName, List<String> expectedColumns)
			throws SQLException {
		try (Statement statement = connection.createStatement();
				ResultSet indexes = statement.executeQuery("PRAGMA index_list(" + tableName + ")")) {
			while (indexes.next()) {
				if (indexes.getInt("unique") == 0) {
					continue;
				}
				String indexName = indexes.getString("name").replace("'", "''");
				List<String> actualColumns = new ArrayList<>();
				try (Statement indexStatement = connection.createStatement();
						ResultSet columns = indexStatement.executeQuery("PRAGMA index_info('" + indexName + "')")) {
					while (columns.next()) {
						actualColumns.add(columns.getString("name"));
					}
				}
				if (actualColumns.equals(expectedColumns)) {
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
		if (version == 3) {
			verifyVersionThreeCanBeMigrated(connection);
			executeMigration(connection, "/db/migration-v3-to-v4.sql", 4);
			return 4;
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
		if (!hasExactSingleColumnForeignKey(connection, "curriculum_mapping_reviews", "source_node_id",
				"curriculum_nodes", "id")) {
			throw new SQLException(
					"curriculum_mapping_reviews is missing exact foreign key source_node_id -> curriculum_nodes(id)");
		}
		if (!hasExactSingleColumnForeignKey(connection, "curriculum_mapping_reviews", "target_syllabus_version_id",
				"syllabus_versions", "id")) {
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

	private void verifyDatabaseIntegrity(Connection connection) throws SQLException {
		List<String> problems = new ArrayList<>();
		try (Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("PRAGMA integrity_check")) {
			while (result.next()) {
				String message = result.getString(1);
				if (!"ok".equalsIgnoreCase(message)) {
					problems.add(message);
				}
			}
		}
		if (!problems.isEmpty()) {
			throw new SQLException("SQLite integrity check failed: " + String.join("; ", problems));
		}
	}

	private void verifyForeignKeyIntegrity(Connection connection) throws SQLException {
		try (Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("PRAGMA foreign_key_check")) {
			if (!result.next()) {
				return;
			}
			String tableName = result.getString("table");
			String rowId = result.getString("rowid");
			String parentTable = result.getString("parent");
			String foreignKeyId = result.getString("fkid");
			throw new SQLException("SQLite foreign-key check failed" + " in table " + tableName + ", row " + rowId
					+ ", parent table " + parentTable + ", foreign key " + foreignKeyId);
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
		if (version >= 3) {
			if (!tableExists(connection, "curriculum_mapping_reviews")) {
				throw new SQLException(
						"Database schema version " + version + " is missing required table curriculum_mapping_reviews");
			}
			verifyCurriculumMappingReviewSchema(connection);
		}
		if (version >= 4) {
			verifyVersionFourQuestionSchema(connection);
		}
	}

	private void verifyVersionFourQuestionSchema(Connection connection) throws SQLException {
		Set<String> requiredColumns = new HashSet<>(List.of("id", "booklet_id", "classification_node_id",
				"question_code", "question_text", "marks", "preamble_capture_required"));
		int primaryKeyColumnCount = 0;
		int idPrimaryKeyPosition = 0;
		try (Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("PRAGMA table_info(questions)")) {
			while (result.next()) {
				String columnName = result.getString("name");
				requiredColumns.remove(columnName);
				int primaryKeyPosition = result.getInt("pk");
				if (primaryKeyPosition > 0) {
					primaryKeyColumnCount++;
				}
				if ("id".equals(columnName)) {
					idPrimaryKeyPosition = primaryKeyPosition;
				} else if (("booklet_id".equals(columnName) || "classification_node_id".equals(columnName)
						|| "question_code".equals(columnName) || "question_text".equals(columnName)
						|| "marks".equals(columnName) || "preamble_capture_required".equals(columnName))
						&& result.getInt("notnull") == 0) {
					throw new SQLException("questions column must be NOT NULL: " + columnName);
				}
			}
		}
		if (!requiredColumns.isEmpty()) {
			throw new SQLException("questions is missing required column " + requiredColumns.iterator().next());
		}
		if (primaryKeyColumnCount != 1 || idPrimaryKeyPosition != 1) {
			throw new SQLException("questions has an invalid primary key; expected exactly (id)");
		}
		if (!hasExactSingleColumnForeignKey(connection, "questions", "booklet_id", "exam_booklets", "id")) {
			throw new SQLException("questions is missing exact foreign key booklet_id -> exam_booklets(id)");
		}
		if (!hasExactSingleColumnForeignKey(connection, "questions", "classification_node_id", "curriculum_nodes",
				"id")) {
			throw new SQLException(
					"questions is missing exact foreign key classification_node_id -> curriculum_nodes(id)");
		}
		if (!hasExactUniqueIndex(connection, "questions", List.of("booklet_id", "question_code"))) {
			throw new SQLException("questions is missing exact unique key (booklet_id, question_code)");
		}
	}

	private void verifyVersionThreeCanBeMigrated(Connection connection) throws SQLException {
		try (Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM questions")) {
			result.next();
			if (result.getInt(1) > 0) {
				throw new IncompatibleDatabaseException(
						"The existing database contains question data that cannot be migrated safely.");
			}
		}
	}
}
