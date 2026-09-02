package au.edu.eq.questionbank.admin;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

/**
 * Development command-line utility that deletes only the configured SQLite
 * database and recreates it at the current schema version.
 * <p>
 * The exact confirmation flag is required. PDFs, curriculum workbooks,
 * configuration files, and other application data are never deleted.
 */
public final class DatabaseResetTool {

	static final String CONFIRMATION_FLAG = "--confirm-delete-all-database-data";
	private static final Path PROPERTIES_FILE = Path.of("questionbank.properties");

	/**
	 * Loads {@code questionbank.properties} from the working directory and resets
	 * its configured database only when the destructive confirmation flag is
	 * supplied.
	 *
	 * @param args exactly {@value #CONFIRMATION_FLAG} to perform the reset
	 */
	public static void main(String[] args) {
		int result = run(args, PROPERTIES_FILE, System.out, System.err);
		if (result != 0) {
			System.exit(result);
		}
	}

	static void resetDatabase(Path databasePath) throws IOException, SQLException {
		if (databasePath == null) {
			throw new NullPointerException("databasePath");
		}
		Path normalizedPath = databasePath.toAbsolutePath().normalize();
		if (Files.isDirectory(normalizedPath)) {
			throw new IOException("Configured database path is a directory: " + normalizedPath);
		}

		Files.deleteIfExists(normalizedPath);
		Files.deleteIfExists(sidecarPath(normalizedPath, "-wal"));
		Files.deleteIfExists(sidecarPath(normalizedPath, "-shm"));

		Path parent = normalizedPath.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		new SqliteDatabase(normalizedPath).initialiseSchema();
	}

	static int run(String[] args, Path propertiesFile, PrintStream output, PrintStream error) {
		if (args == null) {
			throw new NullPointerException("args");
		}
		if (propertiesFile == null) {
			throw new NullPointerException("propertiesFile");
		}
		if (output == null) {
			throw new NullPointerException("output");
		}
		if (error == null) {
			throw new NullPointerException("error");
		}

		ApplicationConfig config;
		try {
			config = ApplicationConfig.load(propertiesFile);
		} catch (IOException | RuntimeException e) {
			error.println("Database reset NOT performed.");
			error.println();
			error.println("Could not load configuration from:");
			error.println(propertiesFile.toAbsolutePath().normalize());
			error.println();
			error.println(e.getMessage());
			return 1;
		}

		Path databasePath = config.databasePath();
		if (args.length != 1 || !CONFIRMATION_FLAG.equals(args[0])) {
			printUsage(output, databasePath);
			return 2;
		}

		output.println("Resetting configured database:");
		output.println(databasePath);
		try {
			resetDatabase(databasePath);
		} catch (IOException | SQLException e) {
			error.println("Database reset failed:");
			error.println(databasePath);
			error.println();
			error.println(e.getMessage());
			return 1;
		}
		output.println();
		output.println("Database reset complete. Schema version: " + SqliteDatabase.latestSchemaVersion());
		return 0;
	}

	private static void printUsage(PrintStream output, Path databasePath) {
		output.println("Database reset NOT performed.");
		output.println();
		output.println("Configured database:");
		output.println(databasePath);
		output.println();
		output.println("This operation permanently deletes all database records.");
		output.println("PDF and curriculum source files are not deleted.");
		output.println();
		output.println("Run with:");
		output.println(CONFIRMATION_FLAG);
	}

	private static Path sidecarPath(Path databasePath, String suffix) {
		return databasePath.resolveSibling(databasePath.getFileName().toString() + suffix);
	}

	private DatabaseResetTool() {
	}
}
