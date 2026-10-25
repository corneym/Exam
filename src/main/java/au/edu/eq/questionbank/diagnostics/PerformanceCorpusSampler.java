package au.edu.eq.questionbank.diagnostics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

/**
 * Collects inexpensive aggregate corpus characteristics.
 *
 * This sampler must be invoked from a background worker, never from the JavaFX
 * application thread. It does not initialise or migrate the schema.
 */
public final class PerformanceCorpusSampler {

	private PerformanceCorpusSampler() {
	}

	/**
	 * Collects aggregate counts from a live, initialised Question database.
	 *
	 * A normal SQLite connection is used so concurrent WAL changes remain visible.
	 * Query-only mode prevents this connection from modifying data.
	 *
	 * @param databasePath existing Question database
	 * @return observed corpus characteristics
	 * @throws IOException  if the database file is unavailable
	 * @throws SQLException if the database cannot be queried
	 */
	public static Sample capture(Path databasePath) throws IOException, SQLException {
		Objects.requireNonNull(databasePath, "databasePath");
		Path file = databasePath.toAbsolutePath().normalize();

		// Do not let SQLite silently create an absent database.
		if (!Files.isRegularFile(file)) {
			throw new IOException("Question database does not exist: " + file);
		}
		SqliteDatabase database = new SqliteDatabase(file);
		try (Connection connection = database.openConnection()) {
			try (Statement statement = connection.createStatement()) {
				statement.execute("PRAGMA query_only = ON");
			}
			connection.setAutoCommit(false);
			try {
				long recordedVersion = scalar(connection, "SELECT version FROM schema_version");
				if (recordedVersion < 1 || recordedVersion > Integer.MAX_VALUE) {
					throw new SQLException("Invalid Question database schema version");
				}
				long questions = scalar(connection, "SELECT COUNT(*) FROM questions");
				long answers = scalar(connection, "SELECT COUNT(*) FROM answers");
				long questionParts = scalar(connection, "SELECT COUNT(*) FROM question_content_parts");
				long sharedContextParts = scalar(connection,
						"SELECT COUNT(*) " + "FROM shared_question_context_content_parts");

				// This is the main database file size, not the
				// potentially variable SQLite WAL or external assets.
				long databaseBytes = Files.size(file);
				return new Sample((int) recordedVersion, questions, answers, questionParts, sharedContextParts,
						databaseBytes);
			} finally {

				// End the read transaction without changing the corpus.
				connection.rollback();
			}
		}
	}

	private static long scalar(Connection connection, String sql) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(sql);
				ResultSet result = statement.executeQuery()) {
			if (!result.next()) {
				throw new SQLException("Corpus aggregate query returned no value");
			}
			long value = result.getLong(1);
			if (result.wasNull() || value < 0 || result.next()) {
				throw new SQLException("Corpus aggregate query returned invalid data");
			}
			return value;
		}
	}

	/**
	 * Observed corpus characteristics from one read transaction.
	 *
	 * Database size is an approximate filesystem observation, not an atomic
	 * component of the SQLite read transaction.
	 *
	 * @param schemaVersion                 recorded Question database schema
	 *                                      version
	 * @param questionCount                 number of Question rows
	 * @param answerCount                   number of Answer rows
	 * @param questionContentPartCount      ordered Question content parts
	 * @param sharedContextContentPartCount shared-context content parts
	 * @param databaseBytes                 main SQLite database file size
	 */
	public record Sample(int schemaVersion, long questionCount, long answerCount, long questionContentPartCount,
			long sharedContextContentPartCount, long databaseBytes) {
	}
}
