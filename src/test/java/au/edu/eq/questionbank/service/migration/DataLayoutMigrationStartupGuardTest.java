package au.edu.eq.questionbank.service.migration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

class DataLayoutMigrationStartupGuardTest {

	@TempDir
	Path tempDirectory;

	@Test
	void freshEmptyLegacyRootsDoNotBlockStartup() throws Exception {
		Path dataRoot = Files.createDirectories(tempDirectory.resolve("fresh"));
		ApplicationConfig config = ApplicationConfig.fromDataRoot(dataRoot);
		Files.createDirectories(config.pdfDataRoot());
		Files.createDirectories(config.curriculumDataRoot());
		SqliteDatabase database = new SqliteDatabase(config.databasePath());
		database.initialiseSchema();
		assertDoesNotThrow(() -> new DataLayoutMigrationStartupGuard(config, database).requireCurrentLayout());
	}

	@Test
	void legacyPersistedExamPathBlocksNormalStartup() throws Exception {
		Path dataRoot = Files.createDirectories(tempDirectory.resolve("legacy"));
		ApplicationConfig config = ApplicationConfig.fromDataRoot(dataRoot);
		Files.createDirectories(config.pdfDataRoot());
		Files.createDirectories(config.curriculumDataRoot());
		SqliteDatabase database = new SqliteDatabase(config.databasePath());
		database.initialiseSchema();
		Subject chemistry = new SqliteCurriculumWriter(database).insertSubject("Chemistry");
		Path source = config.pdfDataRoot().resolve("Chemistry/QCAA/2024/paper.pdf");
		Files.createDirectories(source.getParent());
		Files.writeString(source, "legacy bytes");
		new SqliteExamImporter(database, new SqliteExamWriter(database)).importExam(chemistry, "QCAA", 2024,
				"External Assessment", "Paper 1", "Chemistry/QCAA/2024/paper.pdf",
				ExamBookletQuestionFormat.WRITTEN_RESPONSE);
		assertThrows(DataLayoutMigrationRequiredException.class,
				() -> new DataLayoutMigrationStartupGuard(config, database).requireCurrentLayout());
	}

	@Test
	void unassignedLegacyCurriculumWorkbookBlocksStartup() throws Exception {
		Path dataRoot = Files.createDirectories(tempDirectory.resolve("legacy-workbook"));
		ApplicationConfig config = ApplicationConfig.fromDataRoot(dataRoot);
		Files.createDirectories(config.pdfDataRoot());
		Files.createDirectories(config.curriculumDataRoot());
		SqliteDatabase database = new SqliteDatabase(config.databasePath());
		database.initialiseSchema();
		Files.writeString(config.curriculumDataRoot().resolve("chemistry.xlsx"), "legacy workbook");
		assertThrows(DataLayoutMigrationRequiredException.class,
				() -> new DataLayoutMigrationStartupGuard(config, database).requireCurrentLayout());
	}
}
