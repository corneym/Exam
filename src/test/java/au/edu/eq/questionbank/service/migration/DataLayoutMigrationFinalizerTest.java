package au.edu.eq.questionbank.service.migration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.model.ExamBookletQuestionFormat;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.repository.assessment.SqliteExamImporter;
import au.edu.eq.questionbank.repository.assessment.SqliteExamWriter;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class DataLayoutMigrationFinalizerTest {

	@TempDir
	Path tempDirectory;

	@Test
	void archivesLegacyRootsOnlyAfterReferencesAreSubjectFirst() throws Exception {
		Fixture fixture = createCurrentFixture("finalize");
		Path oldPdf = Files.writeString(fixture.config().pdfDataRoot().resolve("orphan.pdf"), "old recovery pdf");
		Path oldWorkbook = Files.writeString(fixture.config().curriculumDataRoot().resolve("old-notes.txt"),
				"old recovery curriculum data");
		DataLayoutMigrationFinalizationResult result = new DataLayoutMigrationFinalizer(fixture.config(),
				fixture.database()).finalizeMigration();
		assertTrue(result.archivedFiles() >= 2);
		assertFalse(Files.exists(fixture.config().pdfDataRoot()));
		assertFalse(Files.exists(fixture.config().curriculumDataRoot()));
		Path archiveRoot = fixture.config().dataRoot().resolve("migration-archive").resolve("pre-subject-first");
		assertTrue(Files.isRegularFile(archiveRoot.resolve("pdf").resolve(oldPdf.getFileName())));
		assertTrue(Files.isRegularFile(archiveRoot.resolve("curriculum").resolve(oldWorkbook.getFileName())));
	}

	@Test
	void archiveVerificationFailureLeavesLegacyDataRecoverableAndMigrationRequired() throws Exception {
		Fixture fixture = createCurrentFixture("archive-verification-failure");
		Path legacyFile = Files.writeString(fixture.config().pdfDataRoot().resolve("old.pdf"), "legacy bytes");
		Path archiveFile = fixture.config().dataRoot().resolve("migration-archive").resolve("pre-subject-first")
				.resolve("pdf").resolve("old.pdf");
		Files.createDirectories(archiveFile.getParent());
		Files.writeString(archiveFile, "different archive bytes");
		assertThrows(IOException.class,
				() -> new DataLayoutMigrationFinalizer(fixture.config(), fixture.database()).finalizeMigration());

		// Failed archive verification must leave the former active data intact.
		assertTrue(Files.isRegularFile(legacyFile));
		assertTrue("legacy bytes".equals(Files.readString(legacyFile)));
		assertTrue(Files.isDirectory(fixture.config().pdfDataRoot()));

		// The failed finalisation must not be mistaken for a completed migration.
		assertThrows(DataLayoutMigrationRequiredException.class,
				() -> new DataLayoutMigrationStartupGuard(fixture.config(), fixture.database()).requireCurrentLayout());
	}

	@Test
	void finalizationRefusesToArchiveWhileLegacyReferenceRemains() throws Exception {
		Path dataRoot = Files.createDirectories(tempDirectory.resolve("legacy-reference"));
		ApplicationConfig config = ApplicationConfig.fromDataRoot(dataRoot);
		Files.createDirectories(config.pdfDataRoot());
		Files.createDirectories(config.curriculumDataRoot());
		SqliteDatabase database = new SqliteDatabase(config.databasePath());
		database.initialiseSchema();
		Subject chemistry = new SqliteCurriculumWriter(database).insertSubject("Chemistry");
		Path legacyPdf = config.pdfDataRoot().resolve("Chemistry/QCAA/2024/paper.pdf");
		Files.createDirectories(legacyPdf.getParent());
		Files.writeString(legacyPdf, "legacy bytes");
		new SqliteExamImporter(database, new SqliteExamWriter(database)).importExam(chemistry, "QCAA", 2024,
				"External Assessment", "Paper 1", "Chemistry/QCAA/2024/paper.pdf",
				ExamBookletQuestionFormat.WRITTEN_RESPONSE);
		assertThrows(IllegalStateException.class,
				() -> new DataLayoutMigrationFinalizer(config, database).finalizeMigration());
		assertTrue(Files.isRegularFile(legacyPdf));
	}

	@Test
	void partialRecoveryArchiveIsReusedSafelyOnRerun() throws Exception {
		Fixture fixture = createCurrentFixture("archive-rerun");
		Path legacy = Files.writeString(fixture.config().pdfDataRoot().resolve("old.pdf"), "same archive bytes");
		Path archive = fixture.config().dataRoot().resolve("migration-archive").resolve("pre-subject-first")
				.resolve("pdf").resolve("old.pdf");
		Files.createDirectories(archive.getParent());
		Files.copy(legacy, archive);
		DataLayoutMigrationFinalizationResult result = new DataLayoutMigrationFinalizer(fixture.config(),
				fixture.database()).finalizeMigration();
		assertTrue(result.reusedArchiveFiles() >= 1);
		assertFalse(Files.exists(fixture.config().pdfDataRoot()));
		assertTrue(Files.isRegularFile(archive));
	}

	private Fixture createCurrentFixture(String name) throws Exception {
		Path dataRoot = Files.createDirectories(tempDirectory.resolve(name));
		ApplicationConfig config = ApplicationConfig.fromDataRoot(dataRoot);
		Files.createDirectories(config.pdfDataRoot());
		Files.createDirectories(config.curriculumDataRoot());
		SqliteDatabase database = new SqliteDatabase(config.databasePath());
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		ManagedDataLayout layout = new ManagedDataLayout(dataRoot);
		Path currentPdf = layout.examDirectory("Chemistry", "QCAA", 2024, "External Assessment").resolve("paper.pdf");
		Files.createDirectories(currentPdf.getParent());
		Files.writeString(currentPdf, "current pdf");
		new SqliteExamImporter(database, new SqliteExamWriter(database)).importExam(chemistry, "QCAA", 2024,
				"External Assessment", "Paper 1", layout.relativePath(currentPdf),
				ExamBookletQuestionFormat.WRITTEN_RESPONSE);
		return new Fixture(config, database);
	}

	private record Fixture(ApplicationConfig config, SqliteDatabase database) {
	}
}
