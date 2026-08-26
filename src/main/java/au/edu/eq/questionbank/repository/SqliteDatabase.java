package au.edu.eq.questionbank.repository;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Opens connections to the question-bank SQLite database.
 */
public final class SqliteDatabase {

	private final Path databasePath;

	public SqliteDatabase(Path databasePath) {
		this.databasePath = Objects.requireNonNull(databasePath, "databasePath").toAbsolutePath().normalize();
	}

	public Connection openConnection() throws SQLException {
		return DriverManager.getConnection("jdbc:sqlite:" + databasePath);
	}
}
