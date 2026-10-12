package au.edu.eq.questionbank.admin;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.repository.assessment.SqliteQuestionSelectSql;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

/**
 * Reports SQLite index definitions and query plans for production
 * Question-retrieval SQL.
 *
 * The database must be an existing, standalone, immutable snapshot. This tool
 * never initialises, migrates or modifies that database.
 *
 * Query-plan rows describe SQLite's selected access paths. They are not
 * measured execution times and do not represent the Java-side Question
 * reconstruction work.
 */
public final class SqliteQuestionQueryPlanTool {

	private SqliteQuestionQueryPlanTool() {
	}

	/**
	 * Executes the read-only query-plan utility.
	 *
	 * @param args snapshot and Subject arguments
	 */
	public static void main(String[] args) {
		int status = run(args, System.out, System.err);
		if (status != 0) {
			System.exit(status);
		}
	}

	/**
	 * Produces index and query-plan evidence.
	 *
	 * @param args   command-line arguments
	 * @param output report destination
	 * @param error  error destination
	 * @return zero for success, one for execution failure, two for invalid
	 *         arguments
	 */
	public static int run(String[] args, PrintStream output, PrintStream error) {
		Objects.requireNonNull(args);
		Objects.requireNonNull(output);
		Objects.requireNonNull(error);
		if (args.length == 1 && "--help".equals(args[0])) {
			printUsage(output);
			return 0;
		}
		if (args.length != 4 || !"--snapshot".equals(args[0]) || !"--subject".equals(args[2])) {
			printUsage(error);
			return 2;
		}
		try {
			Path snapshot = Path.of(args[1]);
			if (!snapshot.isAbsolute()) {
				throw new IllegalArgumentException("Snapshot path must be absolute");
			}
			snapshot = snapshot.normalize();
			long subjectId = Long.parseLong(args[3]);
			if (subjectId <= 0) {
				throw new IllegalArgumentException("Subject ID must be positive");
			}
			if (!Files.isRegularFile(snapshot)) {
				throw new IllegalStateException("Snapshot does not exist: " + snapshot);
			}
			requireStandaloneSnapshot(snapshot);
			long originalBytes = Files.size(snapshot);
			String fingerprint = PerformanceBenchmarkCsvReport.sha256(snapshot);
			SqliteDatabase database = SqliteDatabase.readOnly(snapshot);
			if (database.schemaVersion() != SqliteDatabase.latestSchemaVersion()) {
				throw new IllegalStateException("Snapshot schema is not current");
			}
			database.verifySchema();
			PerformanceBenchmarkCurriculumScopes scopes = new PerformanceBenchmarkCurriculumScopes(snapshot);

			// Use actual current-curriculum node IDs from the snapshot.
			List<CurriculumNode> nodes = scopes.subject(subjectId);
			try (Connection connection = database.openConnection()) {
				output.println("SQLite query-plan investigation");
				output.println("Snapshot: " + snapshot);
				output.println("Snapshot SHA-256: " + fingerprint);
				output.println("Subject ID: " + subjectId);
				output.println("Current retrieval nodes: " + nodes.size());
				output.println("SQLite version: " + database.sqliteVersion());
				printIndexInventory(connection, output);
				explain(connection, output, "question.findAll", SqliteQuestionSelectSql.allIds(), List.of());
				long questionId = firstQuestionId(connection);
				explain(connection, output, "question.findById", SqliteQuestionSelectSql.byId(), List.of(questionId));
				explain(connection, output, "search.current.single-node", SqliteQuestionSelectSql.applicability(1),
						List.of(nodes.getFirst().getId()));
				explain(connection, output, "search.current.subject",
						SqliteQuestionSelectSql.applicability(nodes.size()),
						nodes.stream().map(CurriculumNode::getId).toList());

				// Compare broad and selected-Question plans against the same
				// Subject nodes and database snapshot.
				List<Long> selectedParameters = new ArrayList<>(nodes.stream().map(CurriculumNode::getId).toList());
				selectedParameters.add(questionId);
				explain(connection, output, "search.current.subject.selected-question",
						SqliteQuestionSelectSql.applicabilityForQuestion(nodes.size()), selectedParameters);
			}
			if (Files.size(snapshot) != originalBytes
					|| !fingerprint.equals(PerformanceBenchmarkCsvReport.sha256(snapshot))) {
				throw new IllegalStateException("Snapshot changed during investigation");
			}
			requireStandaloneSnapshot(snapshot);
			output.println();
			output.println("Read-only investigation completed.");
			return 0;
		} catch (IllegalArgumentException failure) {
			error.println("Invalid arguments: " + failure.getMessage());
			return 2;
		} catch (Exception failure) {
			error.println("Query-plan investigation failed: " + failure.getMessage());
			return 1;
		}
	}

	private static void explain(Connection connection, PrintStream output, String label, String sql,
			List<Long> parameters) throws SQLException {
		output.println();
		output.println("Plan: " + label);
		output.println("Bound parameters: " + parameters.size());
		try (PreparedStatement statement = connection.prepareStatement("EXPLAIN QUERY PLAN " + sql)) {
			for (int index = 0; index < parameters.size(); index++) {
				statement.setLong(index + 1, parameters.get(index));
			}
			try (ResultSet result = statement.executeQuery()) {
				int rows = 0;
				while (result.next()) {
					output.println(
							result.getInt("id") + " | " + result.getInt("parent") + " | " + result.getString("detail"));
					rows++;
				}
				if (rows == 0) {
					throw new SQLException("SQLite returned an empty query plan: " + label);
				}
			}
		}
	}

	private static long firstQuestionId(Connection connection) throws SQLException {
		try (Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("SELECT MIN(id) FROM questions")) {
			if (!result.next()) {
				throw new SQLException("Question ID lookup returned no row");
			}
			long id = result.getLong(1);

			// The plan is generated even for an empty corpus.
			// SQL parameter values do not execute the SELECT here.
			return result.wasNull() ? 1L : id;
		}
	}

	private static List<String> indexColumns(Connection connection, String indexName) throws SQLException {
		List<String> columns = new ArrayList<>();
		String sql = "PRAGMA index_xinfo(" + sqlLiteral(indexName) + ")";
		try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
			while (result.next()) {

				// Only key columns determine the indexed search key.
				// Auxiliary rowid columns are omitted from this list.
				if (result.getInt("key") != 1) {
					continue;
				}
				String name = result.getString("name");
				if (name == null) {
					int cid = result.getInt("cid");
					name = cid == -1 ? "<rowid>" : "<expression>";
				}
				columns.add(name);
			}
		}
		return List.copyOf(columns);
	}

	private static void printIndexesForTable(Connection connection, PrintStream output, String table)
			throws SQLException {
		record Index(String name, int unique, String origin, int partial) {
		}
		List<Index> indexes = new ArrayList<>();
		try (Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("PRAGMA index_list(" + sqlLiteral(table) + ")")) {
			while (result.next()) {
				indexes.add(new Index(result.getString("name"), result.getInt("unique"), result.getString("origin"),
						result.getInt("partial")));
			}
		}
		if (indexes.isEmpty()) {
			output.println("  (no separately listed indexes)");
			return;
		}
		for (Index index : indexes) {
			List<String> columns = indexColumns(connection, index.name());
			StringJoiner columnList = new StringJoiner(", ");
			for (String column : columns) {
				columnList.add(column);
			}
			output.println("  Index: " + index.name());
			output.println("    Unique: " + (index.unique() == 1));
			output.println("    Origin: " + index.origin());
			output.println("    Partial: " + (index.partial() == 1));
			output.println("    Key columns: " + columnList);
		}
	}

	private static void printIndexInventory(Connection connection, PrintStream output) throws SQLException {
		output.println();
		output.println("=== SQLite index inventory ===");
		List<String> tables = new ArrayList<>();
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT name
				FROM sqlite_master
				WHERE type = 'table'
				  AND name NOT LIKE 'sqlite_%'
				ORDER BY name
				"""); ResultSet result = statement.executeQuery()) {
			while (result.next()) {
				tables.add(result.getString("name"));
			}
		}
		for (String table : tables) {
			output.println();
			output.println("Table: " + table);
			printIndexesForTable(connection, output, table);
		}
	}

	private static void printUsage(PrintStream output) {
		output.println("Usage: --snapshot <absolute-path> " + "--subject <current-subject-id>");
	}

	private static void requireStandaloneSnapshot(Path snapshot) {
		Path wal = snapshot.resolveSibling(snapshot.getFileName() + "-wal");
		Path shm = snapshot.resolveSibling(snapshot.getFileName() + "-shm");
		if (Files.exists(wal) || Files.exists(shm)) {
			throw new IllegalStateException("Snapshot contains WAL/SHM sidecars");
		}
	}

	private static String sqlLiteral(String value) {

		// Names originate from SQLite metadata, never user-supplied SQL.
		return "'" + value.replace("'", "''") + "'";
	}
}
