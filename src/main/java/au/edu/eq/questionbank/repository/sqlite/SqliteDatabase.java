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
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Owns the SQLite database location, initialises the question-bank schema, and
 * opens connections with foreign-key enforcement enabled.
 */
public final class SqliteDatabase {

	private static final int LATEST_SCHEMA_VERSION = 52;
	private static final List<String> VERSION_ONE_TABLES = List.of("schema_version", "subjects", "syllabus_versions",
			"curriculum_nodes", "exam_providers", "source_documents", "exams", "exam_booklets", "questions",
			"question_regions", "answer_files", "answers", "answer_regions");
	private final Path databasePath;
	private final boolean readOnly;

	/**
	 * Creates a database boundary with ordinary read/write access.
	 *
	 * @param databasePath database file location
	 * @throws NullPointerException if databasePath is null
	 */
	public SqliteDatabase(Path databasePath) {
		this(databasePath, false);
	}

	private SqliteDatabase(Path databasePath, boolean readOnly) {
		if (databasePath == null) {
			throw new NullPointerException("databasePath");
		}
		this.databasePath = databasePath.toAbsolutePath().normalize();
		this.readOnly = readOnly;
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
	 * Creates a database boundary for a stable, read-only SQLite snapshot.
	 *
	 * The snapshot must already exist and must not change while being read.
	 * Connections cannot create or modify the supplied database.
	 *
	 * Do not use this mode on a live database with concurrent writers.
	 *
	 * @param databasePath existing SQLite snapshot path
	 * @return a read-only database boundary
	 * @throws NullPointerException if databasePath is null
	 */
	public static SqliteDatabase readOnly(Path databasePath) {
		return new SqliteDatabase(databasePath, true);
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

			// Let SQLite create a consistent snapshot even when the live database has
			// journaled writes.
			try (Connection connection = openConnection();
					PreparedStatement statement = connection.prepareStatement("VACUUM INTO ?")) {
				statement.setString(1, normalisedSnapshotPath.toString());
				statement.execute();
			}

			// Validate the copied database independently before accepting it as a backup.
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

				// Only a database without user schema objects may be bootstrapped without
				// version metadata.
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

				// Validate the starting structure, then apply and verify each migration within
				// this transaction.
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
	 * Opens a SQLite connection with the configured access mode.
	 *
	 * <p>
	 * Foreign-key enforcement and recursive triggers are enabled on every
	 * connection. Recursive triggers ensure that implicit DELETE operations
	 * performed by SQLite REPLACE statements execute the existing assignment scope
	 * and structural DELETE guards.
	 *
	 * <p>
	 * When a diagnostic probe is active, the returned connection also records JDBC
	 * operations. Ordinary application connections remain unchanged.
	 *
	 * @return an open database connection
	 * @throws SQLException if the database cannot be opened or configured, or
	 *                      recursive trigger enforcement is unavailable
	 */
	public Connection openConnection() throws SQLException {
		SqliteReadProbe probe = SqliteReadProbe.active();
		long startedNanos = probe == null ? 0L : System.nanoTime();

		// A benchmark must never create a missing database accidentally.
		if (readOnly && !Files.isRegularFile(databasePath)) {
			throw new SQLException("Read-only database file does not exist: " + databasePath);
		}
		String jdbcUrl;
		if (readOnly) {

			// SQLite URI mode=ro prevents changes to the database.
			// immutable=1 avoids creating journal or shared-memory sidecars
			// when reading a consistent, unchanging benchmark snapshot.
			jdbcUrl = "jdbc:sqlite:" + databasePath.toUri().toASCIIString() + "?mode=ro&immutable=1";
		} else {
			jdbcUrl = "jdbc:sqlite:" + databasePath;
		}
		Connection connection = DriverManager.getConnection(jdbcUrl);
		try {
			try (Statement statement = connection.createStatement()) {
				if (readOnly) {

					// Defence in depth: prevent application SQL from modifying
					// database state even if it reaches this connection.
					statement.execute("PRAGMA query_only = ON");
				}
				statement.execute("PRAGMA busy_timeout = 5000");
				statement.execute("PRAGMA foreign_keys = ON");

				// REPLACE may implicitly delete a conflicting row.
				// Its DELETE triggers must execute so contributor scope
				// enforcement cannot be bypassed by replacement.
				statement.execute("PRAGMA recursive_triggers = ON");

				// Fail closed if the SQLite connection did not accept the
				// required trigger configuration.
				try (ResultSet result = statement.executeQuery("PRAGMA recursive_triggers")) {
					if (!result.next() || result.getInt(1) != 1) {
						throw new SQLException(
								"SQLite recursive triggers are required " + "for contributor assignment protection");
					}
				}
			}
			if (probe == null) {
				return connection;
			}
			long openingNanos = System.nanoTime() - startedNanos;
			return probe.wrap(connection, openingNanos);
		} catch (SQLException | RuntimeException failure) {
			try {
				connection.close();
			} catch (SQLException closeFailure) {
				failure.addSuppressed(closeFailure);
			}
			throw failure;
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
	 * Verifies that this database can be migrated to the latest supported schema
	 * using the application's existing migration path, without modifying this
	 * database.
	 * <p>
	 * Databases already at the latest schema are structurally and physically
	 * validated directly. Older supported databases are snapshotted to a temporary
	 * location and the normal schema initialisation and migration process is run
	 * against that temporary copy.
	 *
	 * @throws IOException  if the temporary migration check cannot be created or
	 *                      cleaned up
	 * @throws SQLException if the database is invalid, unsupported, or cannot be
	 *                      migrated safely
	 */
	public void verifyMigrationCompatibility() throws IOException, SQLException {
		int version = schemaVersion();
		if (version == 0) {
			throw new SQLException("Database does not contain a question-bank schema");
		}
		if (version > LATEST_SCHEMA_VERSION) {
			throw new SQLException("Unsupported database schema version " + version + "; latest supported version is "
					+ LATEST_SCHEMA_VERSION);
		}
		verifySchema();
		verifyIntegrity();
		if (version == LATEST_SCHEMA_VERSION) {
			return;
		}

		// Run the real migration path on a disposable snapshot, leaving the original
		// unchanged.
		Path probeDirectory = Files.createTempDirectory("question-bank-migration-check-");
		Path probeDatabasePath = probeDirectory.resolve("questionbank.db");
		try {
			createConsistentSnapshot(probeDatabasePath);
			SqliteDatabase probeDatabase = new SqliteDatabase(probeDatabasePath);
			probeDatabase.initialiseSchema();
			int migratedVersion = probeDatabase.schemaVersion();
			if (migratedVersion != LATEST_SCHEMA_VERSION) {
				throw new SQLException("Database migration check produced schema version " + migratedVersion
						+ "; expected " + LATEST_SCHEMA_VERSION);
			}
			probeDatabase.verifySchema();
			probeDatabase.verifyIntegrity();
		} catch (IOException | SQLException | RuntimeException e) {
			try {
				deleteTemporaryTree(probeDirectory);
			} catch (IOException cleanupFailure) {
				e.addSuppressed(cleanupFailure);
			}
			throw e;
		}
		deleteTemporaryTree(probeDirectory);
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

	private ColumnRequirement column(String name, boolean notNull, int primaryKeyPosition) {
		return new ColumnRequirement(name, notNull, primaryKeyPosition);
	}

	private void createVersionOneSchema(Connection connection) throws SQLException {
		String sql;
		try {
			sql = SqlResourceLoader.load("/db/schema-v01.sql");
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

	private void deleteTemporaryTree(Path root) throws IOException {
		if (!Files.exists(root)) {
			return;
		}
		List<Path> paths;
		try (Stream<Path> stream = Files.walk(root)) {

			// Close the walk before deleting, with children ordered before their parent
			// directories.
			paths = stream.sorted(Comparator.reverseOrder()).toList();
		}
		for (Path path : paths) {
			Files.deleteIfExists(path);
		}
	}

	private void executeMigration(Connection connection, String resourcePath, int expectedVersion) throws SQLException {
		String sql;
		try {
			sql = SqlResourceLoader.load(resourcePath);
		} catch (IOException exception) {
			throw new SQLException("Unable to load migration resource: " + resourcePath, exception);
		}

		// Installation and schema verification share the caller's
		// transaction so migration failures roll back atomically.
		SqlScriptExecutor.execute(connection, sql);
		int actualVersion = readSchemaVersion(connection);
		if (actualVersion != expectedVersion) {
			throw new SQLException(
					"Migration did not produce schema version " + expectedVersion + "; found " + actualVersion);
		}
		verifySchema(connection, expectedVersion);
	}

	private String expectedPrimaryKeyDescription(List<ColumnRequirement> requirements) {
		List<ColumnRequirement> primaryKeyColumns = requirements.stream()
				.filter(requirement -> requirement.primaryKeyPosition() > 0)
				.sorted(Comparator.comparingInt(ColumnRequirement::primaryKeyPosition)).toList();
		if (primaryKeyColumns.isEmpty()) {
			return "no primary key";
		}
		List<String> names = new ArrayList<>();
		for (ColumnRequirement requirement : primaryKeyColumns) {
			names.add(requirement.name());
		}
		return "(" + String.join(", ", names) + ")";
	}

	private ColumnRequirement findColumnRequirement(List<ColumnRequirement> requirements, String columnName) {
		for (ColumnRequirement requirement : requirements) {
			if (requirement.name().equals(columnName)) {
				return requirement;
			}
		}
		return null;
	}

	private ForeignKeyRequirement foreignKey(String fromColumn, String targetTable, String targetColumn) {
		return new ForeignKeyRequirement(fromColumn, targetTable, targetColumn);
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

	private boolean hasExactCompositeForeignKey(Connection connection, String tableName, List<String> fromColumns,
			String targetTable, List<String> targetColumns) throws SQLException {
		if (fromColumns.size() != targetColumns.size()) {
			throw new IllegalArgumentException("Composite foreign-key column counts must match");
		}

		// PRAGMA foreign_key_list assigns one id to every column participating in one
		// composite relationship. Collect candidates by that id before comparing their
		// complete ordered column lists.
		Map<Integer, List<ForeignKeyColumn>> columnsByForeignKey = new java.util.LinkedHashMap<>();
		try (Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("PRAGMA foreign_key_list(" + tableName + ")")) {
			while (result.next()) {
				if (!targetTable.equals(result.getString("table"))) {
					continue;
				}
				int foreignKeyId = result.getInt("id");
				columnsByForeignKey.computeIfAbsent(foreignKeyId, _ -> new ArrayList<>()).add(
						new ForeignKeyColumn(result.getInt("seq"), result.getString("from"), result.getString("to")));
			}
		}
		for (List<ForeignKeyColumn> candidate : columnsByForeignKey.values()) {
			candidate.sort(Comparator.comparingInt(ForeignKeyColumn::sequence));

			// Reject both incomplete prefix matches and larger relationships containing
			// the requested columns.
			if (candidate.size() != fromColumns.size()) {
				continue;
			}
			boolean matches = true;
			for (int index = 0; index < candidate.size(); index++) {
				ForeignKeyColumn column = candidate.get(index);
				if (!fromColumns.get(index).equals(column.fromColumn())
						|| !targetColumns.get(index).equals(column.targetColumn())) {
					matches = false;
					break;
				}
			}
			if (matches) {
				return true;
			}
		}
		return false;
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

				// A matching first column of a composite key is not the required single-column
				// relationship.
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

		// Query SQLite's table-valued PRAGMA functions so schema object names remain
		// bound values rather than being concatenated into SQL text.
		try (PreparedStatement indexListStatement = connection.prepareStatement("""
				SELECT name, "unique" AS is_unique
				FROM pragma_index_list(?)
				ORDER BY seq
				""")) {
			indexListStatement.setString(1, tableName);
			try (ResultSet indexes = indexListStatement.executeQuery()) {
				while (indexes.next()) {
					if (indexes.getInt("is_unique") == 0) {
						continue;
					}

					// Inspect the complete ordered column list for each unique index without
					// treating the SQLite-supplied index name as executable SQL.
					String indexName = indexes.getString("name");
					List<String> actualColumns = new ArrayList<>();
					try (PreparedStatement indexInfoStatement = connection.prepareStatement("""
							SELECT name
							FROM pragma_index_info(?)
							ORDER BY seqno
							""")) {
						indexInfoStatement.setString(1, indexName);
						try (ResultSet columns = indexInfoStatement.executeQuery()) {
							while (columns.next()) {
								actualColumns.add(columns.getString("name"));
							}
						}
					}

					// Match the complete ordered column list, not a prefix of a larger unique
					// key.
					if (actualColumns.equals(expectedColumns)) {
						return true;
					}
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
		if (version < 1 || version >= LATEST_SCHEMA_VERSION) {
			throw new SQLException("No migration available from schema version " + version);
		}

		// The v4 redesign cannot safely migrate populated
		// version-three development Question records.
		if (version == 3) {
			verifyVersion03CanBeMigrated(connection);
		}

		// Portable identity creation remains the existing
		// Java-based migration rather than a SQL resource.
		if (version == 24) {
			SqlitePortableIdentitySchema.migrate(connection);
			verifySchema(connection, 25);
			return 25;
		}
		if (version == 51) {

			// Version 52 installs corpus-disposal state and then performs the
			// assignment-baseline backfill and portable delete-trigger replacement
			// that cannot be expressed by the frozen SQL migration alone.
			SqliteCorpusDisposalSchema.migrate(connection);
			verifySchema(connection, 52);
			return 52;
		}
		int nextVersion = version + 1;
		String resource = String.format(Locale.ROOT, "/db/migration-v%02d-to-v%02d.sql", version, nextVersion);
		executeMigration(connection, resource, nextVersion);
		return nextVersion;
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

			// Require one positive integer version; SQLite coercion must not hide malformed
			// metadata.
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

	private void verifyCurriculumMappingSchema(Connection connection) throws SQLException {
		verifyTableSchema(connection, "curriculum_mappings",
				List.of(column("id", false, 1), column("source_node_id", true, 0), column("target_node_id", true, 0),
						column("mapping_status", true, 0)),
				List.of(foreignKey("source_node_id", "curriculum_nodes", "id"),
						foreignKey("target_node_id", "curriculum_nodes", "id")),
				List.of(List.of("source_node_id", "target_node_id")));
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

	private void verifyLegacyQuestionSchema(Connection connection) throws SQLException {
		verifyTableSchema(connection, "questions",
				List.of(column("id", false, 1), column("exam_id", true, 0), column("classification_node_id", true, 0),
						column("question_code", true, 0), column("question_text", true, 0)),
				List.of(foreignKey("exam_id", "exams", "id"),
						foreignKey("classification_node_id", "curriculum_nodes", "id")),
				List.of(List.of("exam_id", "question_code")));
	}

	private void verifySchema(Connection connection, int version) throws SQLException {
		for (String tableName : VERSION_ONE_TABLES) {
			if (!tableExists(connection, tableName)) {
				throw new SQLException(
						"Database schema version " + version + " is missing required table " + tableName);
			}
		}
		verifyVersion01RelationalSchema(connection, version);
		if (version >= 2) {
			if (!tableExists(connection, "curriculum_mappings")) {
				throw new SQLException(
						"Database schema version " + version + " is missing required table curriculum_mappings");
			}
			verifyCurriculumMappingSchema(connection);
		}
		if (version >= 3) {
			if (!tableExists(connection, "curriculum_mapping_reviews")) {
				throw new SQLException(
						"Database schema version " + version + " is missing required table curriculum_mapping_reviews");
			}
			verifyCurriculumMappingReviewSchema(connection);
		}
		if (version >= 4) {
			String sharedContextCaptureColumnName = version >= 13 ? "shared_context_capture_required"
					: "preamble_capture_required";
			verifyVersion04QuestionSchema(connection, sharedContextCaptureColumnName);
		}
		if (version >= 5) {
			verifyVersion05SharedQuestionSchema(connection);
		}
		if (version >= 6) {
			String sharedContextStatusColumnName = version >= 13 ? "shared_context_status" : "preamble_status";
			verifyVersion06SourceQuestionSchema(connection, sharedContextStatusColumnName);
		}
		if (version >= 7) {
			verifyVersion07CurriculumAuthoringSchema(connection);
		}
		if (version >= 8) {
			verifyVersion08QuestionResponseTypeSchema(connection);
		}
		if (version >= 9) {
			verifyVersion09BookletQuestionFormatSchema(connection);
		}
		if (version >= 10) {
			verifyVersion10BookletAnswerFileSchema(connection);
		}
		if (version >= 11) {
			verifyVersion11PendingMcqSharedContextSchema(connection);
		}
		if (version >= 12) {
			if (!tableExists(connection, "question_output_exclusions")) {
				throw new SQLException(
						"Database schema version " + version + " is missing required table question_output_exclusions");
			}
			verifyVersion12QuestionOutputExclusionSchema(connection);
		}
		if (version >= 14) {
			if (!tableExists(connection, "question_images")) {
				throw new SQLException(
						"Database schema version " + version + " is missing required table question_images");
			}
			if (!tableExists(connection, "question_content_parts")) {
				throw new SQLException(
						"Database schema version " + version + " is missing required table question_content_parts");
			}
			verifyVersion14QuestionContentSchema(connection);
		}
		if (version >= 15) {
			verifyVersion15ExamCaptureSchema(connection);
		}
		if (version >= 16) {
			verifyVersion16SourceDocumentHashSchema(connection);
		}
		if (version >= 17) {
			verifyVersion17QuestionSourceRecaptureSchema(connection);
		}
		if (version >= 18) {
			verifyVersion18ExamAssetExpectationSchema(connection);
		}
		if (version >= 19) {
			verifyVersion19AnswerFileExplanationSchema(connection);
		}
		if (version >= 20) {
			verifyVersion20BookletApplicabilityReviewSchema(connection);
		}
		if (version >= 21) {

			// Preserve the historically correct trigger definitions.
			if (version >= 23) {
				BookletApplicabilityReviewInvalidationTriggers.verify(connection);
			} else {
				BookletApplicabilityReviewInvalidationTriggers.verifyLegacy(connection);
			}
		}
		if (version >= 22) {
			verifyVersion22LegacyPaperSchema(connection);
		}
		if (version >= 24) {
			verifyVersion24SharedContextContentSchema(connection);
		}
		if (version >= 25) {
			verifyVersion25PortableIdentitySchema(connection);
		}
		if (version >= 26) {
			SqlitePortableRevisionTriggers.verify(connection);
		}
		if (version >= 27) {
			verifyVersion27SubjectImportSchema(connection);
		}
		if (version >= 28) {
			verifyVersion28ExamAssignmentSchema(connection);
		}
		if (version >= 29) {
			verifyVersion29ContributorAssignmentSchema(connection);
		}
		if (version >= 30) {
			if (version >= 32) {
				SqliteAssignmentStructureTriggers.verify(connection);
			} else {
				SqliteAssignmentStructureTriggers.verifyLegacy(connection);
			}
		}
		if (version >= 31) {
			if (version >= 33) {
				SqliteAssignmentScopeTriggers.verify(connection);
			} else {
				SqliteAssignmentScopeTriggers.verifyLegacy(connection);
			}
		}
		if (version >= 34) {
			verifyVersion34ContributorReturnSchema(connection);
		}
		if (version >= 35) {
			verifyVersion35ContributionIntakeSchema(connection);
		}
		if (version >= 36) {
			verifyVersion36ContributionReviewSchema(connection);
		}
		if (version >= 37) {
			verifyVersion37ContributionApplicationSchema(connection);
		}
		if (version >= 38) {
			verifyVersion38AssignmentLifecycleSchema(connection);
		}
		if (version >= 39) {
			verifyVersion39MasterExamStructureSchema(connection, version);
		}
		if (version == 40) {
			verifyVersion40MasterCaptureSchema(connection);
		}
		if (version >= 41) {
			verifyVersion41MasterContributionScalarSchema(connection, version);
		}
		if (version >= 42) {
			verifyVersion42MasterRegionSchema(connection);
		}
		if (version >= 43) {
			verifyVersion43ContributorReceiptSchema(connection);
		}

		// Reconstruct the master capture trigger definitions
		// appropriate to each historical schema version.
		if (version >= 46) {
			verifyVersion46MasterGraphSchema(connection);
			SqliteMigrationTriggerVerifier.verify(connection, "trg_master_capture_", "master capture lock",
					"/db/migration-v39-to-v40.sql", "/db/migration-v40-to-v41.sql", "/db/migration-v41-to-v42.sql",
					"/db/migration-v43-to-v44.sql", "/db/migration-v44-to-v45.sql", "/db/migration-v45-to-v46.sql");
		} else if (version >= 45) {
			SqliteMigrationTriggerVerifier.verify(connection, "trg_master_capture_", "master capture lock",
					"/db/migration-v39-to-v40.sql", "/db/migration-v40-to-v41.sql", "/db/migration-v41-to-v42.sql",
					"/db/migration-v43-to-v44.sql", "/db/migration-v44-to-v45.sql");
		} else if (version >= 44) {
			SqliteMigrationTriggerVerifier.verify(connection, "trg_master_capture_", "master capture lock",
					"/db/migration-v39-to-v40.sql", "/db/migration-v40-to-v41.sql", "/db/migration-v41-to-v42.sql",
					"/db/migration-v43-to-v44.sql");
		} else if (version >= 42) {
			SqliteMigrationTriggerVerifier.verify(connection, "trg_master_capture_", "master capture lock",
					"/db/migration-v39-to-v40.sql", "/db/migration-v40-to-v41.sql", "/db/migration-v41-to-v42.sql");
		} else if (version >= 41) {
			SqliteMigrationTriggerVerifier.verify(connection, "trg_master_capture_", "master capture lock",
					"/db/migration-v39-to-v40.sql", "/db/migration-v40-to-v41.sql");
		}
		if (version >= 48) {
			verifyVersion48OperatingContextSchema(connection);
		}
		if (version >= 49) {
			verifyVersion49SubjectSnapshotFreshness(connection);
		}

		// Preserve the merged Issue #301 contributor protections.
		if (version >= 50) {
			verifyVersion50ContributorTerminalSchema(connection);
		}
		if (version >= 51) {
			verifyVersion51MockExaminationSchema(connection);
		}
		if (version >= 52) {
			verifyVersion52CorpusDisposalSchema(connection);
		}
	}

	private void verifyTableColumns(Connection connection, String tableName, List<ColumnRequirement> columnRequirements)
			throws SQLException {
		Set<String> missingColumns = new HashSet<>();
		for (ColumnRequirement requirement : columnRequirements) {
			missingColumns.add(requirement.name());
		}

		// Count all primary-key columns, including unexpected ones, to reject larger
		// composite keys.
		int actualPrimaryKeyColumnCount = 0;
		try (Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("PRAGMA table_info(" + tableName + ")")) {
			while (result.next()) {
				String columnName = result.getString("name");
				int primaryKeyPosition = result.getInt("pk");
				if (primaryKeyPosition > 0) {
					actualPrimaryKeyColumnCount++;
				}
				ColumnRequirement requirement = findColumnRequirement(columnRequirements, columnName);
				if (requirement == null) {
					continue;
				}
				missingColumns.remove(columnName);
				if (requirement.notNull() && result.getInt("notnull") == 0) {
					throw new SQLException(tableName + " column must be NOT NULL: " + columnName);
				}
				if (primaryKeyPosition != requirement.primaryKeyPosition()) {
					throw new SQLException(tableName + " has an invalid primary key; expected exactly "
							+ expectedPrimaryKeyDescription(columnRequirements));
				}
			}
		}
		if (!missingColumns.isEmpty()) {
			throw new SQLException(tableName + " is missing required column " + missingColumns.iterator().next());
		}
		int expectedPrimaryKeyColumnCount = 0;
		for (ColumnRequirement requirement : columnRequirements) {
			if (requirement.primaryKeyPosition() > 0) {
				expectedPrimaryKeyColumnCount++;
			}
		}
		if (actualPrimaryKeyColumnCount != expectedPrimaryKeyColumnCount) {
			throw new SQLException(tableName + " has an invalid primary key; expected exactly "
					+ expectedPrimaryKeyDescription(columnRequirements));
		}
	}

	private void verifyTableSchema(Connection connection, String tableName, List<ColumnRequirement> columnRequirements,
			List<ForeignKeyRequirement> foreignKeyRequirements, List<List<String>> uniqueKeys) throws SQLException {
		verifyTableColumns(connection, tableName, columnRequirements);
		for (ForeignKeyRequirement requirement : foreignKeyRequirements) {
			if (!hasExactSingleColumnForeignKey(connection, tableName, requirement.fromColumn(),
					requirement.targetTable(), requirement.targetColumn())) {
				throw new SQLException(tableName + " is missing exact foreign key " + requirement.fromColumn() + " -> "
						+ requirement.targetTable() + "(" + requirement.targetColumn() + ")");
			}
		}
		for (List<String> uniqueKey : uniqueKeys) {
			if (!hasExactUniqueIndex(connection, tableName, uniqueKey)) {
				throw new SQLException(
						tableName + " is missing exact unique key (" + String.join(", ", uniqueKey) + ")");
			}
		}
	}

	private void verifyVersion01RelationalSchema(Connection connection, int version) throws SQLException {
		verifyTableSchema(connection, "schema_version", List.of(column("version", true, 0)), List.of(), List.of());
		verifyTableSchema(connection, "subjects", List.of(column("id", false, 1), column("subject_name", true, 0)),
				List.of(), List.of(List.of("subject_name")));
		verifyTableSchema(connection, "syllabus_versions",
				List.of(column("id", false, 1), column("subject_id", true, 0), column("syllabus_name", true, 0),
						column("is_current", true, 0)),
				List.of(foreignKey("subject_id", "subjects", "id")), List.of(List.of("subject_id", "syllabus_name")));
		verifyTableSchema(connection, "curriculum_nodes",
				List.of(column("id", false, 1), column("syllabus_version_id", true, 0), column("parent_id", false, 0),
						column("curriculum_code", true, 0), column("curriculum_name", true, 0),
						column("curriculum_level", true, 0), column("display_order", true, 0)),
				List.of(foreignKey("syllabus_version_id", "syllabus_versions", "id"),
						foreignKey("parent_id", "curriculum_nodes", "id")),
				List.of(List.of("syllabus_version_id", "curriculum_code")));
		verifyTableSchema(connection, "exam_providers",
				List.of(column("id", false, 1), column("provider_name", true, 0)), List.of(),
				List.of(List.of("provider_name")));
		verifyTableSchema(connection, "source_documents",
				List.of(column("id", false, 1), column("relative_path", true, 0)), List.of(),
				List.of(List.of("relative_path")));
		verifyTableSchema(connection, "exams",
				List.of(column("id", false, 1), column("subject_id", true, 0), column("provider_id", true, 0),
						column("exam_year", true, 0), column("exam_name", true, 0)),
				List.of(foreignKey("subject_id", "subjects", "id"), foreignKey("provider_id", "exam_providers", "id")),
				List.of(List.of("subject_id", "provider_id", "exam_year", "exam_name")));
		verifyTableSchema(connection, "exam_booklets",
				List.of(column("id", false, 1), column("exam_id", true, 0), column("source_document_id", true, 0),
						column("booklet_name", true, 0)),
				List.of(foreignKey("exam_id", "exams", "id"),
						foreignKey("source_document_id", "source_documents", "id")),
				List.of(List.of("exam_id", "booklet_name")));
		if (version < 4) {
			verifyLegacyQuestionSchema(connection);
		}

		// Region order is part of the composite identity; page numbers and coordinates
		// remain separate.
		verifyTableSchema(connection, "question_regions",
				List.of(column("question_id", true, 1), column("region_order", true, 2), column("booklet_id", true, 0),
						column("page_number", true, 0), column("x", true, 0), column("y", true, 0),
						column("width", true, 0), column("height", true, 0)),
				List.of(foreignKey("question_id", "questions", "id"), foreignKey("booklet_id", "exam_booklets", "id")),
				List.of());
		verifyTableSchema(connection, "answer_files",
				List.of(column("id", false, 1), column("exam_id", true, 0), column("source_document_id", true, 0),
						column("answer_file_name", true, 0)),
				List.of(foreignKey("exam_id", "exams", "id"),
						foreignKey("source_document_id", "source_documents", "id")),
				List.of(List.of("exam_id", "answer_file_name")));
		verifyTableSchema(connection, "answers",
				List.of(column("id", false, 1), column("question_id", true, 0), column("answer_text", false, 0)),
				List.of(foreignKey("question_id", "questions", "id")), List.of(List.of("question_id")));
		verifyTableSchema(connection, "answer_regions",
				List.of(column("answer_id", true, 1), column("region_order", true, 2),
						column("answer_file_id", true, 0), column("page_number", true, 0), column("x", true, 0),
						column("y", true, 0), column("width", true, 0), column("height", true, 0)),
				List.of(foreignKey("answer_id", "answers", "id"), foreignKey("answer_file_id", "answer_files", "id")),
				List.of());
	}

	private void verifyVersion03CanBeMigrated(Connection connection) throws SQLException {
		try (Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM questions")) {
			result.next();
			if (result.getInt(1) > 0) {
				throw new IncompatibleDatabaseException(
						"The existing database contains question data that cannot be migrated safely.");
			}
		}
	}

	private void verifyVersion04QuestionSchema(Connection connection, String sharedContextCaptureColumnName)
			throws SQLException {
		Set<String> requiredColumns = new HashSet<>(List.of("id", "booklet_id", "classification_node_id",
				"question_code", "question_text", "marks", sharedContextCaptureColumnName));
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
						|| "marks".equals(columnName) || sharedContextCaptureColumnName.equals(columnName))
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

	private void verifyVersion05SharedQuestionSchema(Connection connection) throws SQLException {
		verifyTableSchema(connection, "source_questions",
				List.of(column("id", false, 1), column("booklet_id", true, 0), column("source_question_code", true, 0)),
				List.of(foreignKey("booklet_id", "exam_booklets", "id")),
				List.of(List.of("booklet_id", "source_question_code")));
		verifyTableSchema(connection, "shared_question_contexts",
				List.of(column("id", false, 1), column("booklet_id", true, 0), column("context_label", true, 0)),
				List.of(foreignKey("booklet_id", "exam_booklets", "id")), List.of());
		verifyTableSchema(connection, "shared_question_context_regions",
				List.of(column("shared_context_id", true, 1), column("region_order", true, 2),
						column("page_number", true, 0), column("x", true, 0), column("y", true, 0),
						column("width", true, 0), column("height", true, 0)),
				List.of(foreignKey("shared_context_id", "shared_question_contexts", "id")), List.of());
		boolean hasSourceQuestionId = false;
		boolean hasSharedContextId = false;
		try (Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("PRAGMA table_info(questions)")) {
			while (result.next()) {
				String columnName = result.getString("name");
				if ("source_question_id".equals(columnName)) {
					hasSourceQuestionId = true;
					if (result.getInt("notnull") != 0) {
						throw new SQLException("questions column must be nullable: source_question_id");
					}
				} else if ("shared_context_id".equals(columnName)) {
					hasSharedContextId = true;
					if (result.getInt("notnull") != 0) {
						throw new SQLException("questions column must be nullable: shared_context_id");
					}
				}
			}
		}
		if (!hasSourceQuestionId) {
			throw new SQLException("questions is missing required column source_question_id");
		}
		if (!hasSharedContextId) {
			throw new SQLException("questions is missing required column shared_context_id");
		}
		if (!hasExactSingleColumnForeignKey(connection, "questions", "source_question_id", "source_questions", "id")) {
			throw new SQLException("questions is missing exact foreign key source_question_id -> source_questions(id)");
		}
		if (!hasExactSingleColumnForeignKey(connection, "questions", "shared_context_id", "shared_question_contexts",
				"id")) {
			throw new SQLException(
					"questions is missing exact foreign key shared_context_id -> shared_question_contexts(id)");
		}
	}

	private void verifyVersion06SourceQuestionSchema(Connection connection, String sharedContextStatusColumnName)
			throws SQLException {
		boolean hasSharedContextStatus = false;
		try (Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("PRAGMA table_info(source_questions)")) {
			while (result.next()) {
				if (!sharedContextStatusColumnName.equals(result.getString("name"))) {
					continue;
				}
				hasSharedContextStatus = true;
				if (result.getInt("notnull") == 0) {
					throw new SQLException(
							"source_questions column must be NOT NULL: " + sharedContextStatusColumnName);
				}
			}
		}
		if (!hasSharedContextStatus) {
			throw new SQLException("source_questions is missing required column " + sharedContextStatusColumnName);
		}
	}

	private void verifyVersion07CurriculumAuthoringSchema(Connection connection) throws SQLException {
		verifyTableSchema(connection, "syllabus_versions",
				List.of(column("id", false, 1), column("subject_id", true, 0), column("syllabus_name", true, 0),
						column("is_current", true, 0), column("curriculum_status", true, 0),
						column("curriculum_finalised_at", false, 0), column("source_pdf_path", false, 0)),
				List.of(foreignKey("subject_id", "subjects", "id")), List.of(List.of("subject_id", "syllabus_name")));
		verifyTableSchema(connection, "curriculum_nodes",
				List.of(column("id", false, 1), column("syllabus_version_id", true, 0), column("parent_id", false, 0),
						column("curriculum_code", true, 0), column("curriculum_name", true, 0),
						column("curriculum_level", true, 0), column("display_order", true, 0),
						column("source_page_number", false, 0)),
				List.of(foreignKey("syllabus_version_id", "syllabus_versions", "id"),
						foreignKey("parent_id", "curriculum_nodes", "id")),
				List.of(List.of("syllabus_version_id", "curriculum_code")));
	}

	private void verifyVersion08QuestionResponseTypeSchema(Connection connection) throws SQLException {
		boolean hasResponseType = false;
		try (Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("PRAGMA table_info(questions)")) {
			while (result.next()) {
				if (!"response_type".equals(result.getString("name"))) {
					continue;
				}
				hasResponseType = true;

				// Version 8 requires every persisted Question to carry a response-type
				// value, using UNKNOWN for unresolved legacy Questions.
				if (result.getInt("notnull") == 0) {
					throw new SQLException("questions column must be NOT NULL: response_type");
				}
			}
		}
		if (!hasResponseType) {
			throw new SQLException("questions is missing required column response_type");
		}
	}

	private void verifyVersion09BookletQuestionFormatSchema(Connection connection) throws SQLException {
		boolean hasQuestionFormat = false;
		try (Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("PRAGMA table_info(exam_booklets)")) {
			while (result.next()) {
				if (!"question_format".equals(result.getString("name"))) {
					continue;
				}
				hasQuestionFormat = true;

				// Every persisted booklet must carry an explicit stored value. Legacy
				// booklets use UNSPECIFIED rather than SQL NULL.
				if (result.getInt("notnull") == 0) {
					throw new SQLException("exam_booklets column must be NOT NULL: question_format");
				}
			}
		}
		if (!hasQuestionFormat) {
			throw new SQLException("exam_booklets is missing required column question_format");
		}
	}

	private void verifyVersion10BookletAnswerFileSchema(Connection connection) throws SQLException {
		boolean hasAnswerFileId = false;
		try (Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("PRAGMA table_info(exam_booklets)")) {
			while (result.next()) {
				if (!"answer_file_id".equals(result.getString("name"))) {
					continue;
				}
				hasAnswerFileId = true;

				// Existing and unresolved booklets must be allowed to remain unassigned
				// until the correct AnswerFile is established explicitly.
				if (result.getInt("notnull") != 0) {
					throw new SQLException("exam_booklets column must be nullable: answer_file_id");
				}
			}
		}
		if (!hasAnswerFileId) {
			throw new SQLException("exam_booklets is missing required column answer_file_id");
		}

		// The stored mapping must always reference a real AnswerFile. Same-Exam
		// ownership is enforced by the persistence writer when the mapping is changed.
		if (!hasExactSingleColumnForeignKey(connection, "exam_booklets", "answer_file_id", "answer_files", "id")) {
			throw new SQLException("exam_booklets is missing exact foreign key answer_file_id -> answer_files(id)");
		}
	}

	private void verifyVersion11PendingMcqSharedContextSchema(Connection connection) throws SQLException {
		boolean hasPendingSharedContextId = false;
		try (Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("PRAGMA table_info(exam_booklets)")) {
			while (result.next()) {
				if (!"pending_mcq_shared_context_id".equals(result.getString("name"))) {
					continue;
				}
				hasPendingSharedContextId = true;

				// Most booklets have no pending MCQ continuation. NULL is therefore the
				// normal persisted state rather than an exceptional placeholder value.
				if (result.getInt("notnull") != 0) {
					throw new SQLException("exam_booklets column must be nullable: pending_mcq_shared_context_id");
				}
			}
		}
		if (!hasPendingSharedContextId) {
			throw new SQLException("exam_booklets is missing required column pending_mcq_shared_context_id");
		}

		// A pending continuation must always identify a real persisted context. The
		// capture service additionally verifies that the context belongs to this
		// particular booklet before it is used.
		if (!hasExactSingleColumnForeignKey(connection, "exam_booklets", "pending_mcq_shared_context_id",
				"shared_question_contexts", "id")) {
			throw new SQLException(
					"exam_booklets is missing exact foreign key pending_mcq_shared_context_id -> shared_question_contexts(id)");
		}
	}

	private void verifyVersion12QuestionOutputExclusionSchema(Connection connection) throws SQLException {

		// The composite primary key guarantees at most one exclusion decision for a
		// Question/current-node pair. Both references are mandatory.
		verifyTableSchema(connection, "question_output_exclusions",
				List.of(column("question_id", true, 1), column("current_curriculum_node_id", true, 2)),
				List.of(foreignKey("question_id", "questions", "id"),
						foreignKey("current_curriculum_node_id", "curriculum_nodes", "id")),
				List.of());
	}

	private void verifyVersion14QuestionContentSchema(Connection connection) throws SQLException {

		// Stored images have their own identity because one content-order row refers to
		// one particular persisted image.
		verifyTableColumns(connection, "question_images",
				List.of(column("id", false, 1), column("question_id", true, 0), column("image_png", true, 0)));
		if (!hasExactSingleColumnForeignKey(connection, "question_images", "question_id", "questions", "id")) {
			throw new SQLException("question_images is missing exact foreign key question_id -> questions(id)");
		}

		// The composition table owns ordering across all supported content types.
		verifyTableColumns(connection, "question_content_parts",
				List.of(column("question_id", true, 1), column("content_order", true, 2),
						column("content_type", true, 0), column("region_order", false, 0),
						column("image_id", false, 0)));
		if (!hasExactSingleColumnForeignKey(connection, "question_content_parts", "question_id", "questions", "id")) {
			throw new SQLException("question_content_parts is missing exact foreign key question_id -> questions(id)");
		}
		if (!hasExactCompositeForeignKey(connection, "question_content_parts", List.of("question_id", "region_order"),
				"question_regions", List.of("question_id", "region_order"))) {
			throw new SQLException("question_content_parts is missing PDF-region composite foreign key");
		}
		if (!hasExactCompositeForeignKey(connection, "question_content_parts", List.of("question_id", "image_id"),
				"question_images", List.of("question_id", "id"))) {
			throw new SQLException("question_content_parts is missing image composite foreign key");
		}
	}

	private void verifyVersion15ExamCaptureSchema(Connection connection) throws SQLException {
		boolean hasCaptureState = false;
		try (Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("PRAGMA table_info(exams)")) {
			while (result.next()) {
				if (!"capture_state".equals(result.getString("name"))) {
					continue;
				}
				hasCaptureState = true;

				// Every Exam must have an explicit persisted lifecycle state. Existing
				// Exams are migrated to ACTIVE rather than represented by SQL NULL.
				if (result.getInt("notnull") == 0) {
					throw new SQLException("exams column must be NOT NULL: capture_state");
				}
			}
		}
		if (!hasCaptureState) {
			throw new SQLException("exams is missing required column capture_state");
		}
		boolean hasExpectedQuestionCount = false;
		try (Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("PRAGMA table_info(exam_booklets)")) {
			while (result.next()) {
				if (!"expected_question_count".equals(result.getString("name"))) {
					continue;
				}
				hasExpectedQuestionCount = true;

				// Legacy and not-yet-reviewed booklets have no authoritative expected
				// Question count, so NULL is a meaningful persisted state.
				if (result.getInt("notnull") != 0) {
					throw new SQLException("exam_booklets column must be nullable: expected_question_count");
				}
			}
		}
		if (!hasExpectedQuestionCount) {
			throw new SQLException("exam_booklets is missing required column expected_question_count");
		}
	}

	private void verifyVersion16SourceDocumentHashSchema(Connection connection) throws SQLException {
		boolean hasContentSha256 = false;
		try (Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("PRAGMA table_info(source_documents)")) {
			while (result.next()) {
				if (!"content_sha256".equals(result.getString("name"))) {
					continue;
				}
				hasContentSha256 = true;

				// Existing documents cannot be assigned hashes safely during a schema-only
				// migration, so NULL remains a valid persisted value.
				if (result.getInt("notnull") != 0) {
					throw new SQLException("source_documents column must be nullable: content_sha256");
				}
			}
		}
		if (!hasContentSha256) {
			throw new SQLException("source_documents is missing required column content_sha256");
		}
	}

	private void verifyVersion17QuestionSourceRecaptureSchema(Connection connection) throws SQLException {
		boolean hasSourceCaptureRequired = false;
		try (Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("PRAGMA table_info(questions)")) {
			while (result.next()) {
				if (!"source_capture_required".equals(result.getString("name"))) {
					continue;
				}
				hasSourceCaptureRequired = true;

				// Every Question has an explicit yes/no recapture state. Historical rows
				// migrate to zero rather than SQL NULL.
				if (result.getInt("notnull") == 0) {
					throw new SQLException("questions column must be NOT NULL: source_capture_required");
				}
			}
		}
		if (!hasSourceCaptureRequired) {
			throw new SQLException("questions is missing required column source_capture_required");
		}
	}

	private void verifyVersion18ExamAssetExpectationSchema(Connection connection) throws SQLException {
		boolean hasExpectedQuestionBookletCount = false;
		boolean hasExpectedAnswerFileCount = false;
		try (Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("PRAGMA table_info(exams)")) {
			while (result.next()) {
				String columnName = result.getString("name");
				if ("expected_question_booklet_count".equals(columnName)) {
					hasExpectedQuestionBookletCount = true;

					// NULL means that the user has not yet established an authoritative
					// expectation.
					if (result.getInt("notnull") != 0) {
						throw new SQLException("exams column must be nullable: expected_question_booklet_count");
					}
				}
				if ("expected_answer_file_count".equals(columnName)) {
					hasExpectedAnswerFileCount = true;

					// An unknown expected Answer-file count is distinct from explicitly
					// expecting zero Answer files.
					if (result.getInt("notnull") != 0) {
						throw new SQLException("exams column must be nullable: expected_answer_file_count");
					}
				}
			}
		}
		if (!hasExpectedQuestionBookletCount) {
			throw new SQLException("exams is missing required column expected_question_booklet_count");
		}
		if (!hasExpectedAnswerFileCount) {
			throw new SQLException("exams is missing required column expected_answer_file_count");
		}
	}

	private void verifyVersion19AnswerFileExplanationSchema(Connection connection) throws SQLException {
		boolean hasContainsAnswerExplanations = false;
		try (Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("PRAGMA table_info(answer_files)")) {
			while (result.next()) {
				if (!"contains_answer_explanations".equals(result.getString("name"))) {
					continue;
				}
				hasContainsAnswerExplanations = true;

				// Every AnswerFile must have an explicit persisted yes/no value. Existing
				// rows migrate to zero rather than remaining unknown.
				if (result.getInt("notnull") == 0) {
					throw new SQLException("answer_files column must be NOT NULL: contains_answer_explanations");
				}
			}
		}
		if (!hasContainsAnswerExplanations) {
			throw new SQLException("answer_files is missing required column contains_answer_explanations");
		}
	}

	private void verifyVersion20BookletApplicabilityReviewSchema(Connection connection) throws SQLException {
		if (!tableExists(connection, "booklet_applicability_reviews")) {
			throw new SQLException("Database schema is missing required table booklet_applicability_reviews");
		}
		verifyTableSchema(connection, "booklet_applicability_reviews", List.of(column("booklet_id", false, 1)),
				List.of(foreignKey("booklet_id", "exam_booklets", "id")), List.of());
	}

	private void verifyVersion22LegacyPaperSchema(Connection connection) throws SQLException {
		boolean found = false;
		try (Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("PRAGMA table_info(exam_booklets)")) {
			while (result.next()) {
				if (!"legacy_paper_code".equals(result.getString("name"))) {
					continue;
				}
				found = true;

				// Ordinary and not-yet-linked historical booklets must
				// remain valid with no legacy Paper identity.
				if (result.getInt("notnull") != 0) {
					throw new SQLException("exam_booklets.legacy_paper_code must be nullable");
				}
			}
		}
		if (!found) {
			throw new SQLException("exam_booklets is missing required column legacy_paper_code");
		}
		if (!hasExactUniqueIndex(connection, "exam_booklets", List.of("exam_id", "legacy_paper_code"))) {
			throw new SQLException("exam_booklets is missing exact unique key " + "(exam_id, legacy_paper_code)");
		}
	}

	private void verifyVersion24SharedContextContentSchema(Connection connection) throws SQLException {
		verifyTableSchema(connection, "shared_question_context_images",
				List.of(column("shared_context_id", true, 1), column("image_order", true, 2),
						column("image_png", true, 0)),
				List.of(foreignKey("shared_context_id", "shared_question_contexts", "id")), List.of());
		verifyTableSchema(connection, "shared_question_context_content_parts",
				List.of(column("shared_context_id", true, 1), column("content_order", true, 2),
						column("content_type", true, 0), column("region_order", false, 0),
						column("image_order", false, 0)),
				List.of(foreignKey("shared_context_id", "shared_question_contexts", "id")), List.of());

		// verifyTableSchema validates individual column requirements.
		// Explicitly verify the composite references used to retain the
		// ownership and identity of each content part.
		verifyForeignKeyIntegrity(connection);
	}

	private void verifyVersion25PortableIdentitySchema(Connection connection) throws SQLException {
		verifyTableSchema(connection, "installation_identity",
				List.of(column("singleton_id", false, 1), column("installation_id", true, 0)), List.of(),
				List.of(List.of("installation_id")));
		verifyTableSchema(connection, "portable_entity_identities",
				List.of(column("entity_type", true, 1), column("local_id", true, 2), column("portable_id", true, 0),
						column("revision_id", true, 0), column("retired", true, 0)),
				List.of(), List.of(List.of("portable_id"), List.of("revision_id")));
		verifyTableSchema(connection, "portable_entity_retirements",
				List.of(column("portable_id", true, 1), column("entity_type", true, 0), column("local_id", true, 0),
						column("revision_id", true, 0), column("retired_at", true, 0)),
				List.of(), List.of(List.of("revision_id")));
		verifyTableSchema(connection, "portable_identity_aliases",
				List.of(column("origin_installation_id", true, 1), column("entity_type", true, 2),
						column("origin_portable_id", true, 3), column("canonical_portable_id", true, 0),
						column("approval_note", true, 0), column("approved_at", true, 0)),
				List.of(), List.of(List.of("origin_installation_id", "entity_type", "origin_portable_id")));
		SqlitePortableIdentitySchema.verifyInstallationIdentity(connection);
		SqlitePortableIdentitySchema.verifyTriggers(connection);

		// Shape checks alone cannot detect detached live records or
		// identities reused from the permanent retirement history.
		SqlitePortableIdentityAudit.verify(connection);
	}

	/**
	 * Verifies the durable Subject import ledger introduced in schema v27.
	 *
	 * @param connection connection containing the current schema
	 * @throws SQLException if required ledger columns or keys are invalid
	 */
	private void verifyVersion27SubjectImportSchema(Connection connection) throws SQLException {
		verifyTableSchema(connection, "subject_import_packages",
				List.of(column("package_id", true, 1), column("archive_sha256", true, 0),
						column("origin_installation_id", true, 0), column("subject_portable_id", true, 0),
						column("master_state_sha256", true, 0), column("approval_sha256", true, 0),
						column("outcome", true, 0), column("backup_archive_path", false, 0),
						column("approval_note", true, 0), column("completed_at", true, 0)),
				List.of(), List.of());
		verifyTableSchema(connection, "subject_import_decisions",
				List.of(column("package_id", true, 1), column("entry_kind", true, 2), column("entry_key", true, 3),
						column("classification", true, 0), column("coordinator_decision", true, 0),
						column("result", true, 0), column("canonical_portable_id", false, 0),
						column("master_revision_id", false, 0), column("explanation", true, 0)),
				List.of(foreignKey("package_id", "subject_import_packages", "package_id")), List.of());
	}

	/**
	 * Verifies the coordinator's immutable assignment ledger.
	 *
	 * @param connection database connection being validated
	 * @throws SQLException if tables or protection triggers are invalid
	 */
	private void verifyVersion28ExamAssignmentSchema(Connection connection) throws SQLException {
		verifyTableSchema(connection, "exam_work_assignments",
				List.of(column("assignment_id", true, 1), column("package_id", true, 0),
						column("baseline_snapshot_id", true, 0), column("master_installation_id", true, 0),
						column("subject_portable_id", true, 0), column("exam_portable_id", true, 0),
						column("contributor_id", true, 0), column("contributor_name", true, 0),
						column("issued_at", true, 0), column("archive_sha256", true, 0),
						column("records_sha256", true, 0), column("assets_sha256", true, 0),
						column("baseline_records", true, 0), column("baseline_assets", true, 0)),
				List.of(), List.of(List.of("package_id"), List.of("baseline_snapshot_id")));
		verifyTableSchema(connection, "exam_work_assignment_states",
				List.of(column("assignment_id", true, 1), column("state", true, 0), column("updated_at", true, 0)),
				List.of(foreignKey("assignment_id", "exam_work_assignments", "assignment_id")), List.of());
		SqliteExamWorkAssignmentLedgerTriggers.verify(connection);
	}

	private void verifyVersion29ContributorAssignmentSchema(Connection connection) throws SQLException {
		verifyTableSchema(connection, "contributor_assignment_sessions",
				List.of(column("assignment_id", true, 1), column("package_id", true, 0),
						column("baseline_snapshot_id", true, 0), column("master_installation_id", true, 0),
						column("subject_portable_id", true, 0), column("exam_portable_id", true, 0),
						column("current_syllabus_portable_id", true, 0),
						column("legacy_syllabus_portable_id", false, 0), column("contributor_id", true, 0),
						column("contributor_name", true, 0), column("subject_local_id", true, 0),
						column("exam_local_id", true, 0), column("current_syllabus_local_id", true, 0),
						column("legacy_syllabus_local_id", false, 0), column("issued_at", true, 0),
						column("installed_at", true, 0), column("archive_sha256", true, 0),
						column("records_sha256", true, 0), column("assets_sha256", true, 0),
						column("baseline_records", true, 0), column("baseline_assets", true, 0)),
				List.of(foreignKey("subject_local_id", "subjects", "id"), foreignKey("exam_local_id", "exams", "id"),
						foreignKey("current_syllabus_local_id", "syllabus_versions", "id"),
						foreignKey("legacy_syllabus_local_id", "syllabus_versions", "id")),
				List.of(List.of("package_id"), List.of("baseline_snapshot_id")));
		verifyTableSchema(connection, "contributor_assignment_selection",
				List.of(column("singleton_id", true, 1), column("assignment_id", false, 0),
						column("updated_at", true, 0)),
				List.of(foreignKey("assignment_id", "contributor_assignment_sessions", "assignment_id")), List.of());
		SqliteContributorAssignmentSessionTriggers.verify(connection);
		try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("""
				SELECT singleton_id
				FROM contributor_assignment_selection
				""")) {
			if (!result.next() || result.getInt(1) != 1) {
				throw new SQLException("Missing contributor assignment selection singleton");
			}
			if (result.next()) {
				throw new SQLException("Multiple contributor assignment selection rows");
			}
		}
	}

	private void verifyVersion34ContributorReturnSchema(Connection connection) throws SQLException {
		if (readSchemaVersion(connection) >= 50) {
			verifyTableSchema(connection, "contributor_return_packages",
					List.of(column("package_id", true, 1), column("assignment_id", true, 0),
							column("return_number", true, 0), column("previous_package_id", false, 0),
							column("baseline_snapshot_id", true, 0), column("master_installation_id", true, 0),
							column("contributor_id", true, 0), column("origin_installation_id", true, 0),
							column("archive_sha256", true, 0), column("recorded_at", true, 0),
							column("assignment_package_id", true, 0), column("assignment_archive_sha256", true, 0),
							column("subject_portable_id", true, 0), column("exam_portable_id", true, 0),
							column("issued_at", true, 0), column("installed_at", true, 0),
							column("contribution_created_at", false, 0)),
					List.of(foreignKey("previous_package_id", "contributor_return_packages", "package_id")),
					List.of(List.of("assignment_id", "return_number")));
			SqliteContributorReturnLedgerTriggers.verify(connection);
			return;
		}
		verifyTableSchema(connection, "contributor_return_packages",
				List.of(column("package_id", true, 1), column("assignment_id", true, 0),
						column("return_number", true, 0), column("previous_package_id", false, 0),
						column("baseline_snapshot_id", true, 0), column("master_installation_id", true, 0),
						column("contributor_id", true, 0), column("origin_installation_id", true, 0),
						column("archive_sha256", true, 0), column("recorded_at", true, 0)),
				List.of(foreignKey("assignment_id", "contributor_assignment_sessions", "assignment_id"),
						foreignKey("previous_package_id", "contributor_return_packages", "package_id")),
				List.of(List.of("assignment_id", "return_number")));
		SqliteContributorReturnLedgerTriggers.verifyLegacy(connection);
	}

	private void verifyVersion35ContributionIntakeSchema(Connection connection) throws SQLException {
		verifyTableSchema(connection, "contribution_intake_packages",
				List.of(column("package_id", true, 1), column("assignment_id", true, 0),
						column("return_number", true, 0), column("previous_package_id", false, 0),
						column("baseline_snapshot_id", true, 0), column("master_installation_id", true, 0),
						column("contributor_id", true, 0), column("origin_installation_id", true, 0),
						column("archive_sha256", true, 0), column("intake_status", true, 0),
						column("received_at", true, 0)),
				List.of(foreignKey("assignment_id", "exam_work_assignments", "assignment_id")), List.of());
		verifyTableSchema(connection, "contribution_intake_proposals",
				List.of(column("package_id", true, 1), column("record_type", true, 2), column("record_id", true, 3),
						column("proposed_revision_id", true, 0), column("baseline_revision_id", false, 0),
						column("record_jsonl", true, 0)),
				List.of(foreignKey("package_id", "contribution_intake_packages", "package_id")), List.of());
		SqliteMigrationTriggerVerifier.verify(connection, "trg_contribution_intake_", "contribution intake",
				"/db/migration-v34-to-v35.sql");
	}

	private void verifyVersion36ContributionReviewSchema(Connection connection) throws SQLException {
		verifyTableSchema(connection, "contribution_review_batches",
				List.of(column("review_id", true, 1), column("package_id", true, 0), column("observed_at", true, 0),
						column("recorded_at", true, 0), column("review_note", true, 0)),
				List.of(foreignKey("package_id", "contribution_intake_packages", "package_id")),
				List.of(List.of("review_id", "package_id")));
		verifyTableSchema(connection, "contribution_review_decisions",
				List.of(column("review_id", true, 1), column("package_id", true, 0), column("record_type", true, 2),
						column("record_id", true, 3), column("baseline_revision_id", false, 0),
						column("proposed_revision_id", true, 0), column("master_revision_id", false, 0),
						column("master_sha256", false, 0), column("preliminary_status", true, 0),
						column("choice", true, 0), column("result_revision_id", false, 0)),
				List.of(), List.of());
		if (!hasExactCompositeForeignKey(connection, "contribution_review_decisions",
				List.of("review_id", "package_id"), "contribution_review_batches",
				List.of("review_id", "package_id"))) {
			throw new SQLException("Missing review batch foreign key");
		}
		if (!hasExactCompositeForeignKey(connection, "contribution_review_decisions",
				List.of("package_id", "record_type", "record_id"), "contribution_intake_proposals",
				List.of("package_id", "record_type", "record_id"))) {
			throw new SQLException("Missing received proposal foreign key");
		}
		SqliteMigrationTriggerVerifier.verify(connection, "trg_contribution_review_", "contribution review",
				"/db/migration-v35-to-v36.sql");
	}

	private void verifyVersion37ContributionApplicationSchema(Connection connection) throws SQLException {
		verifyTableSchema(connection, "contribution_application_batches",
				List.of(column("review_id", true, 1), column("package_id", true, 0), column("completed_at", true, 0)),
				List.of(), List.of(List.of("package_id")));
		verifyTableSchema(connection, "contribution_application_results",
				List.of(column("review_id", true, 1), column("record_type", true, 2), column("record_id", true, 3),
						column("outcome", true, 0), column("resulting_revision_id", false, 0)),
				List.of(foreignKey("review_id", "contribution_application_batches", "review_id")), List.of());
		if (!hasExactCompositeForeignKey(connection, "contribution_application_batches",
				List.of("review_id", "package_id"), "contribution_review_batches",
				List.of("review_id", "package_id"))) {
			throw new SQLException("Missing application-to-review foreign key");
		}
		if (!hasExactCompositeForeignKey(connection, "contribution_application_results",
				List.of("review_id", "record_type", "record_id"), "contribution_review_decisions",
				List.of("review_id", "record_type", "record_id"))) {
			throw new SQLException("Missing application-to-decision foreign key");
		}
		SqliteMigrationTriggerVerifier.verify(connection, "trg_contribution_application_", "contribution application",
				"/db/migration-v36-to-v37.sql");
	}

	private void verifyVersion38AssignmentLifecycleSchema(Connection connection) throws SQLException {
		verifyTableSchema(connection, "exam_work_assignment_exam_locks",
				List.of(column("exam_portable_id", true, 1), column("assignment_id", true, 0)),
				List.of(foreignKey("assignment_id", "exam_work_assignment_states", "assignment_id")),
				List.of(List.of("assignment_id")));
		verifyTableSchema(connection, "exam_work_assignment_closures",
				List.of(column("assignment_id", true, 1), column("closed_state", true, 0),
						column("closure_kind", true, 0), column("reason", true, 0), column("closed_at", true, 0),
						column("return_package_id", false, 0)),
				List.of(foreignKey("assignment_id", "exam_work_assignments", "assignment_id"),
						foreignKey("return_package_id", "contribution_intake_packages", "package_id")),
				List.of());
		SqliteMigrationTriggerVerifier.verify(connection, "trg_master_assignment_", "master assignment lifecycle",
				"/db/migration-v37-to-v38.sql");

		// An active state must have exactly its own Exam lock.
		// Terminal states must not retain an Exam lock.
		try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("""
				SELECT 1
				FROM exam_work_assignment_states states
				JOIN exam_work_assignments assignments
				    ON assignments.assignment_id =
				       states.assignment_id
				LEFT JOIN exam_work_assignment_exam_locks locks
				    ON locks.assignment_id =
				       states.assignment_id
				WHERE (
				    states.state = 'ACTIVE'
				    AND (
				        locks.assignment_id IS NULL
				        OR locks.exam_portable_id <>
				           assignments.exam_portable_id
				    )
				)
				OR (
				    states.state <> 'ACTIVE'
				    AND locks.assignment_id IS NOT NULL
				)
				LIMIT 1
				""")) {
			if (result.next()) {
				throw new SQLException("Assignment lifecycle has inconsistent Exam locks");
			}
		}

		// Each terminal transition must have matching closure evidence.
		// No active assignment may already have a closure record.
		try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("""
				SELECT 1
				FROM exam_work_assignment_states states
				LEFT JOIN exam_work_assignment_closures closures
				    ON closures.assignment_id =
				       states.assignment_id
				WHERE (
				    states.state = 'ACTIVE'
				    AND closures.assignment_id IS NOT NULL
				)
				OR (
				    states.state <> 'ACTIVE'
				    AND (
				        closures.assignment_id IS NULL
				        OR closures.closed_state <> states.state
				    )
				)
				LIMIT 1
				""")) {
			if (result.next()) {
				throw new SQLException("Assignment lifecycle has inconsistent closure evidence");
			}
		}
	}

	private void verifyVersion39MasterExamStructureSchema(Connection connection, int version) throws SQLException {
		if (version >= 47) {
			SqliteMigrationTriggerVerifier.verify(connection, "trg_master_exam_structure_", "master Exam structure",
					"/db/migration-v38-to-v39.sql", "/db/migration-v46-to-v47.sql");
		} else {
			SqliteMigrationTriggerVerifier.verify(connection, "trg_master_exam_structure_", "master Exam structure",
					"/db/migration-v38-to-v39.sql");
		}
	}

	private void verifyVersion40MasterCaptureSchema(Connection connection) throws SQLException {
		SqliteMigrationTriggerVerifier.verify(connection, "trg_master_capture_", "master capture lock",
				"/db/migration-v39-to-v40.sql");
		SqliteMigrationTriggerVerifier.verify(connection, "trg_master_return_", "master return reconciliation",
				"/db/migration-v39-to-v40.sql");
	}

	private void verifyVersion41MasterContributionScalarSchema(Connection connection, int version) throws SQLException {
		verifyTableSchema(connection, "master_contribution_write_permits",
				List.of(column("singleton_id", false, 1), column("review_id", true, 0), column("record_type", true, 0),
						column("record_id", true, 0), column("operation", true, 0), column("exam_local_id", true, 0)),
				List.of(foreignKey("review_id", "contribution_application_batches", "review_id"),
						foreignKey("exam_local_id", "exams", "id")),
				List.of());
		verifyTableSchema(connection, "contribution_application_entity_mappings",
				List.of(column("review_id", true, 1), column("record_type", true, 2), column("record_id", true, 3),
						column("canonical_record_id", true, 0), column("local_id", true, 0)),
				List.of(), List.of());
		if (!hasExactCompositeForeignKey(connection, "master_contribution_write_permits",
				List.of("review_id", "record_type", "record_id"), "contribution_review_decisions",
				List.of("review_id", "record_type", "record_id"))) {
			throw new SQLException("Master write permit review foreign key is missing");
		}
		if (!hasExactCompositeForeignKey(connection, "contribution_application_entity_mappings",
				List.of("review_id", "record_type", "record_id"), "contribution_application_results",
				List.of("review_id", "record_type", "record_id"))) {
			throw new SQLException("Contribution canonical mapping foreign key is missing");
		}

		// Application permits and canonical mappings remain protected
		// by the original v41 triggers in every subsequent schema version.
		SqliteMigrationTriggerVerifier.verify(connection, "trg_master_application_", "master contribution application",
				"/db/migration-v40-to-v41.sql");

		// Later migrations replace selected capture guards. Reconstruct
		// the exact trigger definitions for the schema version being verified.
		if (version >= 46) {
			SqliteMigrationTriggerVerifier.verify(connection, "trg_master_capture_", "master capture lock",
					"/db/migration-v39-to-v40.sql", "/db/migration-v40-to-v41.sql", "/db/migration-v41-to-v42.sql",
					"/db/migration-v43-to-v44.sql", "/db/migration-v44-to-v45.sql", "/db/migration-v45-to-v46.sql");
		} else if (version >= 45) {
			SqliteMigrationTriggerVerifier.verify(connection, "trg_master_capture_", "master capture lock",
					"/db/migration-v39-to-v40.sql", "/db/migration-v40-to-v41.sql", "/db/migration-v41-to-v42.sql",
					"/db/migration-v43-to-v44.sql", "/db/migration-v44-to-v45.sql");
		} else if (version >= 44) {
			SqliteMigrationTriggerVerifier.verify(connection, "trg_master_capture_", "master capture lock",
					"/db/migration-v39-to-v40.sql", "/db/migration-v40-to-v41.sql", "/db/migration-v41-to-v42.sql",
					"/db/migration-v43-to-v44.sql");
		} else if (version >= 42) {
			SqliteMigrationTriggerVerifier.verify(connection, "trg_master_capture_", "master capture lock",
					"/db/migration-v39-to-v40.sql", "/db/migration-v40-to-v41.sql", "/db/migration-v41-to-v42.sql");
		} else {
			SqliteMigrationTriggerVerifier.verify(connection, "trg_master_capture_", "master capture lock",
					"/db/migration-v39-to-v40.sql", "/db/migration-v40-to-v41.sql");
		}
	}

	private void verifyVersion42MasterRegionSchema(Connection connection) throws SQLException {
		verifyTableSchema(connection, "master_contribution_region_permits",
				List.of(column("singleton_id", false, 1), column("review_id", true, 0), column("record_type", true, 0),
						column("record_id", true, 0), column("exam_local_id", true, 0),
						column("owner_local_id", true, 0)),
				List.of(foreignKey("exam_local_id", "exams", "id")), List.of());
		if (!hasExactCompositeForeignKey(connection, "master_contribution_region_permits",
				List.of("review_id", "record_type", "record_id"), "contribution_review_decisions",
				List.of("review_id", "record_type", "record_id"))) {
			throw new SQLException("Master PDF-region permit review foreign key is missing");
		}
		SqliteMigrationTriggerVerifier.verify(connection, "trg_master_region_", "master PDF-region authorisation",
				"/db/migration-v41-to-v42.sql");
		try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("""
				SELECT COUNT(*)
				FROM master_contribution_region_permits
				""")) {
			if (!result.next() || result.getLong(1) != 0) {
				throw new SQLException("Unreleased master PDF-region permit");
			}
		}
	}

	private void verifyVersion43ContributorReceiptSchema(Connection connection) throws SQLException {
		boolean detached = readSchemaVersion(connection) >= 50;
		verifyTableSchema(connection, "contributor_receipt_acknowledgements",
				List.of(column("receipt_package_id", true, 1), column("return_package_id", true, 0),
						column("assignment_id", true, 0), column("review_id", true, 0),
						column("master_installation_id", true, 0), column("archive_sha256", true, 0),
						column("outcomes_jsonl", true, 0), column("master_records_jsonl", true, 0),
						column("acknowledged_at", true, 0)),
				detached ? List.of(foreignKey("return_package_id", "contributor_return_packages", "package_id"))
						: List.of(foreignKey("return_package_id", "contributor_return_packages", "package_id"),
								foreignKey("assignment_id", "contributor_assignment_sessions", "assignment_id")),
				List.of(List.of("return_package_id")));
		SqliteMigrationTriggerVerifier.verify(connection, "trg_contributor_receipt_ack_",
				"contributor receipt acknowledgement",
				detached ? new String[] { "/db/migration-v42-to-v43.sql", "/db/migration-v49-to-v50.sql" }
						: new String[] { "/db/migration-v42-to-v43.sql" });
	}

	private void verifyVersion52CorpusDisposalSchema(Connection connection) throws SQLException {
		verifyTableSchema(connection, "corpus_disposal_durable_states", List.of(column("state", true, 1)), List.of(),
				List.of());
		verifyTableSchema(connection, "corpus_disposal_operations",
				List.of(column("operation_id", true, 1), column("policy", true, 0), column("policy_version", true, 0),
						column("scope_id", true, 0), column("reason", true, 0),
						column("terminal_return_package_id", false, 0), column("assignment_package_id", true, 0),
						column("assignment_archive_sha256", true, 0), column("plan_sha256", true, 0),
						column("state", true, 0), column("planned_at", true, 0), column("updated_at", true, 0),
						column("database_released_at", false, 0), column("verified_at", false, 0)),
				List.of(foreignKey("terminal_return_package_id", "contributor_return_packages", "package_id"),
						foreignKey("state", "corpus_disposal_durable_states", "state")),
				List.of(List.of("terminal_return_package_id"), List.of("policy", "scope_id")));
		verifyTableSchema(connection, "portable_revision_history", List.of(column("revision_id", true, 1),
				column("portable_id", true, 0), column("entity_type", true, 0), column("first_observed_at", true, 0)),
				List.of(), List.of());
		verifyTableSchema(connection, "corpus_disposal_entities",
				List.of(column("operation_id", true, 1), column("entity_type", true, 2), column("local_id", true, 3),
						column("portable_id", true, 0), column("revision_id", true, 0),
						column("authority_installation_id", true, 0), column("reattachable", true, 0)),
				List.of(foreignKey("operation_id", "corpus_disposal_operations", "operation_id"),
						foreignKey("revision_id", "portable_revision_history", "revision_id")),
				List.of(List.of("operation_id", "portable_id")));
		verifyTableSchema(connection, "corpus_disposal_retired_entities",
				List.of(column("operation_id", true, 1), column("portable_id", true, 2), column("entity_type", true, 0),
						column("former_local_id", true, 0), column("latest_revision_id", true, 0),
						column("authority_installation_id", true, 0)),
				List.of(foreignKey("operation_id", "corpus_disposal_operations", "operation_id"),
						foreignKey("latest_revision_id", "portable_revision_history", "revision_id")),
				List.of());
		verifyTableSchema(connection, "corpus_disposal_assets",
				List.of(column("operation_id", true, 1), column("relative_path", true, 2),
						column("content_sha256", true, 0), column("asset_kind", true, 0)),
				List.of(foreignKey("operation_id", "corpus_disposal_operations", "operation_id")), List.of());
		verifyTableSchema(connection, "portable_entity_cache_evictions",
				List.of(column("operation_id", true, 1), column("portable_id", true, 2), column("entity_type", true, 0),
						column("former_local_id", true, 0), column("latest_revision_id", true, 0),
						column("authority_installation_id", true, 0), column("reattachable", true, 0),
						column("evicted_at", true, 0)),
				List.of(foreignKey("operation_id", "corpus_disposal_operations", "operation_id"),
						foreignKey("latest_revision_id", "portable_revision_history", "revision_id")),
				List.of());
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT sql
				FROM sqlite_master
				WHERE type = 'table' AND name = 'corpus_disposal_operations'
				"""); ResultSet row = statement.executeQuery()) {
			if (!row.next()) {
				throw new SQLException("Missing corpus disposal operation table definition");
			}
			String sql = row.getString(1).replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
			if (!sql.contains("FOREIGN KEY (STATE) REFERENCES CORPUS_DISPOSAL_DURABLE_STATES(STATE) "
					+ "DEFERRABLE INITIALLY DEFERRED")) {
				throw new SQLException("Corpus disposal release authority is not transaction-scoped");
			}
			if (row.next()) {
				throw new SQLException("Duplicate corpus disposal operation table definition");
			}
		}
		SqliteCorpusDisposalSchema.verifyTriggers(connection);
		try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("""
				SELECT state
				FROM corpus_disposal_durable_states
				ORDER BY state
				""")) {
			List<String> states = new ArrayList<>();
			while (rows.next()) {
				states.add(rows.getString(1));
			}
			if (!states.equals(List.of("DATABASE_RELEASED", "PLANNED", "VERIFIED"))) {
				throw new SQLException("Invalid corpus disposal durable-state registry");
			}
		}
		try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("""
				SELECT 1
				FROM corpus_disposal_operations
				WHERE state = 'DATABASE_RELEASING'
				LIMIT 1
				""")) {
			if (rows.next()) {
				throw new SQLException("Corpus disposal deletion authority escaped its transaction");
			}
		}
	}

	private void verifyVersion46MasterGraphSchema(Connection connection) throws SQLException {
		verifyTableSchema(connection, "master_contribution_graph_permits",
				List.of(column("singleton_id", false, 1), column("review_id", true, 0), column("record_type", true, 0),
						column("record_id", true, 0), column("operation", true, 0), column("exam_local_id", true, 0),
						column("owner_local_id", false, 0)),
				List.of(foreignKey("review_id", "contribution_application_batches", "review_id"),
						foreignKey("exam_local_id", "exams", "id")),
				List.of());
		if (!hasExactCompositeForeignKey(connection, "master_contribution_graph_permits",
				List.of("review_id", "record_type", "record_id"), "contribution_review_decisions",
				List.of("review_id", "record_type", "record_id"))) {
			throw new SQLException("Master graph permit review foreign key is missing");
		}
		SqliteMigrationTriggerVerifier.verify(connection, "trg_master_graph_", "master graph authorisation",
				"/db/migration-v45-to-v46.sql");
		try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("""
				SELECT COUNT(*)
				FROM master_contribution_graph_permits
				""")) {
			if (!rows.next() || rows.getLong(1) != 0) {
				throw new SQLException("Unreleased master graph write permit");
			}
		}
	}

	private void verifyVersion48OperatingContextSchema(Connection connection) throws SQLException {
		verifyTableSchema(connection, "imported_subject_authorities",
				List.of(column("subject_local_id", false, 1), column("origin_installation_id", true, 0),
						column("origin_subject_portable_id", true, 0), column("latest_package_id", true, 0),
						column("archive_sha256", true, 0), column("imported_at", true, 0)),
				List.of(foreignKey("subject_local_id", "subjects", "id"),
						foreignKey("latest_package_id", "subject_import_packages", "package_id")),
				List.of(List.of("origin_installation_id", "origin_subject_portable_id")));
		verifyTableSchema(connection, "contributor_assignment_hidden",
				List.of(column("assignment_id", true, 1), column("removed_at", true, 0)),
				List.of(foreignKey("assignment_id", "contributor_assignment_sessions", "assignment_id")), List.of());
		if (readSchemaVersion(connection) >= 50) {
			SqliteMigrationTriggerVerifier.verify(connection, "trg_operating_context_", "operating-context authority",
					"/db/migration-v47-to-v48.sql", "/db/migration-v49-to-v50.sql");
		} else {
			SqliteMigrationTriggerVerifier.verify(connection, "trg_operating_context_", "operating-context authority",
					"/db/migration-v47-to-v48.sql");
		}
	}

	private void verifyVersion49SubjectSnapshotFreshness(Connection connection) throws SQLException {
		verifyTableColumns(connection, "subject_import_packages",
				List.of(column("package_id", true, 1), column("originating_snapshot_created_at", false, 0),
						column("originating_snapshot_content_sha256", false, 0)));
		SqliteMigrationTriggerVerifier.verify(connection, "trg_subject_import_freshness_", "Subject snapshot freshness",
				"/db/migration-v48-to-v49.sql");
		try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("""
				SELECT originating_snapshot_created_at,
				       originating_snapshot_content_sha256
				FROM subject_import_packages
				WHERE originating_snapshot_created_at IS NOT NULL
				   OR originating_snapshot_content_sha256 IS NOT NULL
				""")) {
			while (rows.next()) {
				String timestamp = rows.getString(1);
				String digest = rows.getString(2);
				if (timestamp == null || digest == null || !digest.matches("[0-9a-f]{64}")) {
					throw new SQLException("Incomplete or invalid originating snapshot freshness evidence");
				}
				try {
					Instant.parse(timestamp);
				} catch (DateTimeParseException failure) {
					throw new SQLException("Invalid originating snapshot creation timestamp", failure);
				}
			}
		}
	}

	private void verifyVersion50ContributorTerminalSchema(Connection connection) throws SQLException {
		verifyTableSchema(connection, "contribution_publication_attempts",
				List.of(column("assignment_id", true, 1), column("package_id", true, 0),
						column("archive_sha256", true, 0), column("contribution_created_at", true, 0),
						column("destination_path", true, 0), column("staging_path", true, 0), column("state", true, 0),
						column("selection_was_active", true, 0), column("prepared_at", true, 0),
						column("updated_at", true, 0)),
				List.of(foreignKey("assignment_id", "contributor_assignment_sessions", "assignment_id")),
				List.of(List.of("package_id")));
		SqliteMigrationTriggerVerifier.verify(connection, "trg_contribution_publication_",
				"Contribution publication attempt", "/db/migration-v49-to-v50.sql");
	}

	private void verifyVersion51MockExaminationSchema(Connection connection) throws SQLException {
		verifyTableSchema(connection, "mock_examinations",
				List.of(column("exam_id", true, 1), column("subject_portable_id", true, 0),
						column("subject_name", true, 0), column("cohort_year", true, 0),
						column("paper_one_perusal_minutes", true, 0), column("paper_one_working_minutes", true, 0),
						column("paper_two_perusal_minutes", true, 0), column("paper_two_working_minutes", true, 0),
						column("include_paper_two", true, 0), column("finalised_at", false, 0)),
				List.of(), List.of(List.of("exam_id", "subject_portable_id", "cohort_year")));
		verifyTableSchema(connection, "mock_exam_selections",
				List.of(column("exam_id", true, 1), column("section", true, 2), column("position", true, 3),
						column("question_portable_id", true, 0), column("source_question_portable_id", false, 0),
						column("question_code", true, 0), column("marks", true, 0)),
				List.of(foreignKey("exam_id", "mock_examinations", "exam_id")),
				List.of(List.of("exam_id", "question_portable_id")));
		verifyTableSchema(connection, "mock_exam_reservations",
				List.of(column("exam_id", true, 1), column("subject_portable_id", true, 0),
						column("cohort_year", true, 0), column("entity_type", true, 2),
						column("entity_portable_id", true, 3), column("released_at", false, 0)),
				List.of(), List.of(List.of("subject_portable_id", "cohort_year", "entity_type", "entity_portable_id")));
		List<String> reservationKey = List.of("exam_id", "subject_portable_id", "cohort_year");
		if (!hasExactCompositeForeignKey(connection, "mock_exam_reservations", reservationKey, "mock_examinations",
				reservationKey)) {
			throw new SQLException("Mock examination reservation foreign key is missing");
		}
		String resource = "/db/migration-v50-to-v51.sql";
		List<String> statements;
		try {
			statements = SqlScriptExecutor.splitStatements(SqlResourceLoader.load(resource));
		} catch (IOException failure) {
			throw new SQLException("Unable to load mock examination migration", failure);
		}

		// Verify the CHECK constraints, cascade behaviour and
		// partial index predicate against the frozen migration.
		for (String name : List.of("mock_examinations", "mock_exam_selections", "mock_exam_reservations",
				"ux_mock_exam_active_reservation")) {
			boolean index = name.equals("ux_mock_exam_active_reservation");
			String prefix = (index ? "CREATE UNIQUE INDEX " : "CREATE TABLE ") + name;
			String expected = null;
			for (String sql : statements) {
				int start = sql.indexOf(prefix);
				if (start >= 0) {
					expected = sql.substring(start).replaceFirst(";\\s*$", "").replaceAll("\\s+", " ").strip();
					break;
				}
			}
			if (expected == null) {
				throw new SQLException("Missing schema definition in migration: " + name);
			}
			try (PreparedStatement statement = connection.prepareStatement("""
					SELECT sql
					FROM sqlite_master
					WHERE type = ?
					  AND name = ?
					""")) {
				statement.setString(1, index ? "index" : "table");
				statement.setString(2, name);
				try (ResultSet rows = statement.executeQuery()) {
					if (!rows.next()) {
						throw new SQLException("Missing mock examination schema object: " + name);
					}
					String actual = rows.getString(1);
					if (actual == null
							|| !expected.equals(actual.replaceFirst(";\\s*$", "").replaceAll("\\s+", " ").strip())) {
						throw new SQLException("Altered mock examination schema object: " + name);
					}
				}
			}
		}
		SqliteMigrationTriggerVerifier.verify(connection, "trg_mock_exam_", "mock examination lifecycle", resource);
	}

	private record ForeignKeyColumn(int sequence, String fromColumn, String targetColumn) {
	}

	private record ColumnRequirement(String name, boolean notNull, int primaryKeyPosition) {
	}

	private record ForeignKeyRequirement(String fromColumn, String targetTable, String targetColumn) {
	}
}
