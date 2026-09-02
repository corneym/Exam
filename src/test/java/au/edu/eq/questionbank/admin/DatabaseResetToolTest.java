package au.edu.eq.questionbank.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class DatabaseResetToolTest {

	@TempDir
	Path tempDirectory;

	@Test
	void confirmationResetsOnlyTheConfiguredDatabaseAndSidecars() throws Exception {
		Path dataRoot = tempDirectory.resolve("data");
		Path databasePath = dataRoot.resolve("questionbank.db");
		Path propertiesFile = tempDirectory.resolve("questionbank.properties");
		Files.createDirectories(dataRoot);
		Files.writeString(propertiesFile, "data.root=" + dataRoot.toString().replace('\\', '/') + "\n");

		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("INSERT INTO subjects (subject_name) VALUES ('Chemistry')");
		}

		Path walPath = Path.of(databasePath.toString() + "-wal");
		Path shmPath = Path.of(databasePath.toString() + "-shm");
		Files.writeString(walPath, "stale wal");
		Files.writeString(shmPath, "stale shm");
		Path pdf = dataRoot.resolve("pdf/Chemistry/source.pdf");
		Path curriculum = dataRoot.resolve("curriculum/chemistry.xlsx");
		Files.createDirectories(pdf.getParent());
		Files.createDirectories(curriculum.getParent());
		Files.writeString(pdf, "PDF");
		Files.writeString(curriculum, "workbook");

		ByteArrayOutputStream outputBytes = new ByteArrayOutputStream();
		ByteArrayOutputStream errorBytes = new ByteArrayOutputStream();
		int result;
		try (PrintStream output = new PrintStream(outputBytes, true, StandardCharsets.UTF_8);
				PrintStream error = new PrintStream(errorBytes, true, StandardCharsets.UTF_8)) {
			result = DatabaseResetTool.run(new String[] { DatabaseResetTool.CONFIRMATION_FLAG }, propertiesFile,
					output, error);
		}

		assertEquals(0, result);
		assertTrue(Files.exists(databasePath));
		assertFalse(Files.exists(walPath));
		assertFalse(Files.exists(shmPath));
		assertTrue(Files.exists(pdf));
		assertTrue(Files.exists(curriculum));
		assertTrue(Files.exists(propertiesFile));
		assertTrue(errorBytes.toString(StandardCharsets.UTF_8).isEmpty());
		assertTrue(outputBytes.toString(StandardCharsets.UTF_8).contains(databasePath.toAbsolutePath().toString()));

		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			try (ResultSet resultSet = statement.executeQuery("SELECT version FROM schema_version")) {
				assertTrue(resultSet.next());
				assertEquals(SqliteDatabase.latestSchemaVersion(), resultSet.getInt(1));
			}
			try (ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM subjects")) {
				assertTrue(resultSet.next());
				assertEquals(0, resultSet.getInt(1));
			}
		}
	}

	@Test
	void missingConfirmationPrintsUsageWithoutDeletingAnything() throws Exception {
		Path dataRoot = tempDirectory.resolve("safe-data");
		Path databasePath = dataRoot.resolve("questionbank.db");
		Path propertiesFile = tempDirectory.resolve("safe-questionbank.properties");
		Files.createDirectories(dataRoot);
		Files.writeString(databasePath, "do not delete");
		Files.writeString(Path.of(databasePath.toString() + "-wal"), "do not delete wal");
		Files.writeString(propertiesFile, "data.root=" + dataRoot.toString().replace('\\', '/') + "\n");

		ByteArrayOutputStream outputBytes = new ByteArrayOutputStream();
		ByteArrayOutputStream errorBytes = new ByteArrayOutputStream();
		int result;
		try (PrintStream output = new PrintStream(outputBytes, true, StandardCharsets.UTF_8);
				PrintStream error = new PrintStream(errorBytes, true, StandardCharsets.UTF_8)) {
			result = DatabaseResetTool.run(new String[0], propertiesFile, output, error);
		}

		String output = outputBytes.toString(StandardCharsets.UTF_8);
		assertEquals(2, result);
		assertTrue(output.contains("Database reset NOT performed."));
		assertTrue(output.contains(databasePath.toAbsolutePath().toString()));
		assertTrue(output.contains(DatabaseResetTool.CONFIRMATION_FLAG));
		assertTrue(Files.readString(databasePath).equals("do not delete"));
		assertTrue(Files.exists(Path.of(databasePath.toString() + "-wal")));
		assertTrue(errorBytes.toString(StandardCharsets.UTF_8).isEmpty());
	}
}
