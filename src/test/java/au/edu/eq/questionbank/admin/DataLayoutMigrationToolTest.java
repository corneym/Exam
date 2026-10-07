package au.edu.eq.questionbank.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.model.ExamBookletQuestionFormat;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.repository.assessment.SqliteExamImporter;
import au.edu.eq.questionbank.repository.assessment.SqliteExamWriter;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class DataLayoutMigrationToolTest {

	@TempDir
	Path tempDirectory;

	@Test
	void applyOnCurrentEmptyDataRootReportsNoMigrationRequired() throws Exception {
		Path dataRoot = Files.createDirectories(tempDirectory.resolve("apply-current"));
		ApplicationConfig config = ApplicationConfig.fromDataRoot(dataRoot);
		Files.createDirectories(config.pdfDataRoot());
		Files.createDirectories(config.curriculumDataRoot());
		new SqliteDatabase(config.databasePath()).initialiseSchema();
		ByteArrayOutputStream outputBytes = new ByteArrayOutputStream();
		ByteArrayOutputStream errorBytes = new ByteArrayOutputStream();
		int result = DataLayoutMigrationTool.run(new String[] { "--data-root", dataRoot.toString(), "--apply" },
				new PrintStream(outputBytes, true, StandardCharsets.UTF_8),
				new PrintStream(errorBytes, true, StandardCharsets.UTF_8));
		assertEquals(0, result);
		assertTrue(errorBytes.toString(StandardCharsets.UTF_8).isEmpty());
		assertTrue(outputBytes.toString(StandardCharsets.UTF_8).contains("Migration required: no"));
	}

	@Test
	void dryRunReportsCurrentEmptyDataRootWithoutMutation() throws Exception {
		Path dataRoot = Files.createDirectories(tempDirectory.resolve("data"));
		ApplicationConfig config = ApplicationConfig.fromDataRoot(dataRoot);
		Files.createDirectories(config.pdfDataRoot());
		Files.createDirectories(config.curriculumDataRoot());
		new SqliteDatabase(config.databasePath()).initialiseSchema();
		ByteArrayOutputStream outputBytes = new ByteArrayOutputStream();
		ByteArrayOutputStream errorBytes = new ByteArrayOutputStream();
		int result = DataLayoutMigrationTool.run(new String[] { "--data-root", dataRoot.toString(), "--dry-run" },
				new PrintStream(outputBytes, true, StandardCharsets.UTF_8),
				new PrintStream(errorBytes, true, StandardCharsets.UTF_8));
		assertEquals(0, result);
		assertTrue(errorBytes.toString(StandardCharsets.UTF_8).isEmpty());
		assertTrue(outputBytes.toString(StandardCharsets.UTF_8).contains("Migration required: no"));
	}

	@Test
	void dryRunUsesExplicitLegacyRootsFromConfigurationFile() throws Exception {
		Path dataRoot = Files.createDirectories(tempDirectory.resolve("configured-data"));
		Path legacyPdfRoot = Files.createDirectories(tempDirectory.resolve("external-pdf"));
		Path legacyCurriculumRoot = Files.createDirectories(tempDirectory.resolve("external-curriculum"));
		Path databasePath = dataRoot.resolve("questionbank.db");
		Path configurationFile = tempDirectory.resolve("questionbank.properties");
		Files.writeString(configurationFile,
				"pdf.dataRoot=" + legacyPdfRoot.toString().replace('\\', '/') + "\n" + "curriculum.dataRoot="
						+ legacyCurriculumRoot.toString().replace('\\', '/') + "\n" + "database.path="
						+ databasePath.toString().replace('\\', '/') + "\n");
		ApplicationConfig config = ApplicationConfig.load(configurationFile);
		SqliteDatabase database = new SqliteDatabase(config.databasePath());
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		Path legacyExam = legacyPdfRoot.resolve("Chemistry/QCAA/2024/paper.pdf");
		Files.createDirectories(legacyExam.getParent());
		Files.writeString(legacyExam, "legacy exam bytes");
		new SqliteExamImporter(database, new SqliteExamWriter(database)).importExam(chemistry, "QCAA", 2024,
				"External Assessment", "Paper 1", "Chemistry/QCAA/2024/paper.pdf",
				ExamBookletQuestionFormat.WRITTEN_RESPONSE);
		ByteArrayOutputStream outputBytes = new ByteArrayOutputStream();
		ByteArrayOutputStream errorBytes = new ByteArrayOutputStream();
		int result = DataLayoutMigrationTool.run(new String[] { "--config", configurationFile.toString(), "--dry-run" },
				new PrintStream(outputBytes, true, StandardCharsets.UTF_8),
				new PrintStream(errorBytes, true, StandardCharsets.UTF_8));
		assertEquals(0, result);
		assertTrue(errorBytes.toString(StandardCharsets.UTF_8).isEmpty());
		String output = outputBytes.toString(StandardCharsets.UTF_8);
		assertTrue(output.contains(legacyPdfRoot.toString()));
		assertTrue(output.contains(
				dataRoot.resolve("subjects/Chemistry/exams/QCAA/2024/External Assessment/paper.pdf").toString()));
		assertTrue(Files.isRegularFile(legacyExam));
		assertFalse(Files.exists(dataRoot.resolve("subjects/Chemistry/exams/QCAA/2024/External Assessment/paper.pdf")));
	}

	@Test
	void invalidArgumentsReturnUsageError() {
		ByteArrayOutputStream outputBytes = new ByteArrayOutputStream();
		ByteArrayOutputStream errorBytes = new ByteArrayOutputStream();
		int result = DataLayoutMigrationTool.run(new String[] { "--dry-run" }, new PrintStream(outputBytes),
				new PrintStream(errorBytes));
		assertEquals(2, result);
		assertTrue(errorBytes.toString(StandardCharsets.UTF_8).contains("Usage:"));
	}

	@Test
	void successfulApplyNormalisesLegacyExplicitConfiguration() throws Exception {
		Path dataRoot = Files.createDirectories(tempDirectory.resolve("normalise-data"));
		Path legacyPdfRoot = tempDirectory.resolve("old-external-pdf");
		Path legacyCurriculumRoot = tempDirectory.resolve("old-external-curriculum");
		Path databasePath = dataRoot.resolve("questionbank.db");
		Path configurationFile = tempDirectory.resolve("normalise-questionbank.properties");
		Files.writeString(configurationFile,
				"pdf.dataRoot=" + legacyPdfRoot.toString().replace('\\', '/') + "\n" + "curriculum.dataRoot="
						+ legacyCurriculumRoot.toString().replace('\\', '/') + "\n" + "database.path="
						+ databasePath.toString().replace('\\', '/') + "\n");
		new SqliteDatabase(databasePath).initialiseSchema();
		ByteArrayOutputStream outputBytes = new ByteArrayOutputStream();
		ByteArrayOutputStream errorBytes = new ByteArrayOutputStream();
		int result = DataLayoutMigrationTool.run(new String[] { "--config", configurationFile.toString(), "--apply" },
				new PrintStream(outputBytes, true, StandardCharsets.UTF_8),
				new PrintStream(errorBytes, true, StandardCharsets.UTF_8));
		assertEquals(0, result);
		assertTrue(errorBytes.toString(StandardCharsets.UTF_8).isEmpty());
		String savedConfiguration = Files.readString(configurationFile);
		assertTrue(savedConfiguration.contains("data.root="));
		assertFalse(savedConfiguration.contains("pdf.dataRoot="));
		assertFalse(savedConfiguration.contains("curriculum.dataRoot="));
		assertFalse(savedConfiguration.contains("database.path="));
		ApplicationConfig reloaded = ApplicationConfig.load(configurationFile);
		assertEquals(dataRoot.toAbsolutePath().normalize(), reloaded.dataRoot());
	}
}
