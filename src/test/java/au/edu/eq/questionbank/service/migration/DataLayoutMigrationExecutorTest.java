package au.edu.eq.questionbank.service.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.model.ExamBookletQuestionFormat;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.repository.assessment.SqliteExamImporter;
import au.edu.eq.questionbank.repository.assessment.SqliteExamWriter;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumSourcePdfRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class DataLayoutMigrationExecutorTest {

	@TempDir
	Path tempDirectory;

	@Test
	void appliesVerifiedCopiesAndPublishesAllPathsTransactionally() throws Exception {
		Fixture fixture = createFixture("successful");
		DataLayoutMigrationPlan plan = new DataLayoutMigrationPlanner(fixture.config(), fixture.database()).plan();
		assertTrue(plan.canApply());
		DataLayoutMigrationResult result = new DataLayoutMigrationExecutor(fixture.config(), fixture.database())
				.apply(plan);
		assertEquals(2, result.copiedFiles());
		assertEquals(0, result.reusedFiles());
		assertEquals(1, result.updatedExamPaths());
		assertEquals(1, result.updatedCurriculumSourcePaths());
		String examPath = new SqliteExamWriter(fixture.database()).findAllExamBooklets().getFirst().getSourceDocument()
				.getRelativePath();
		assertEquals("subjects/Chemistry/exams/QCAA/2024/External Assessment/paper-1.pdf", examPath);
		SyllabusVersion syllabus = new SqliteCurriculumRepository(fixture.database())
				.findVersionById(fixture.syllabus().getId()).orElseThrow();
		assertEquals("subjects/Chemistry/curriculum/2025/sources/syllabus.pdf", syllabus.getSourcePdfPath());
		Path migratedExam = fixture.layout().resolve(examPath);
		Path migratedCurriculum = fixture.layout().resolve(syllabus.getSourcePdfPath());
		assertTrue(Files.isRegularFile(migratedExam));
		assertTrue(Files.isRegularFile(migratedCurriculum));
		assertEquals(-1L, Files.mismatch(fixture.examSource(), migratedExam));
		assertEquals(-1L, Files.mismatch(fixture.curriculumSource(), migratedCurriculum));

		// Publication does not destroy recovery material. Legacy-root cleanup is a
		// later phase after new persistence is proven authoritative.
		assertTrue(Files.isRegularFile(fixture.examSource()));
		assertTrue(Files.isRegularFile(fixture.curriculumSource()));
	}

	@Test
	void refusesBlockedPlanWithoutChangingPersistence() throws Exception {
		Fixture fixture = createFixture("blocked");
		Path collision = fixture.layout().examDirectory("Chemistry", "QCAA", 2024, "External Assessment")
				.resolve("paper-1.pdf");
		Files.createDirectories(collision.getParent());
		Files.writeString(collision, "different bytes");
		DataLayoutMigrationPlan plan = new DataLayoutMigrationPlanner(fixture.config(), fixture.database()).plan();
		assertFalse(plan.canApply());
		assertThrows(IllegalStateException.class,
				() -> new DataLayoutMigrationExecutor(fixture.config(), fixture.database()).apply(plan));
		assertEquals(fixture.examRelativePath(), persistedExamPath(fixture));
		assertEquals(fixture.curriculumRelativePath(), persistedCurriculumPath(fixture));
		assertEquals("different bytes", Files.readString(collision));
	}

	@Test
	void rerunReusesVerifiedInterruptedCopiesAndCompletes() throws Exception {
		Fixture fixture = createFixture("rerun");
		DataLayoutMigrationPlan originalPlan = new DataLayoutMigrationPlanner(fixture.config(), fixture.database())
				.plan();
		updateCurriculumPath(fixture, "stale.pdf");
		assertThrows(IllegalStateException.class,
				() -> new DataLayoutMigrationExecutor(fixture.config(), fixture.database()).apply(originalPlan));

		// Restore the still-authoritative legacy reference as an administrator would
		// after resolving the stale concurrent edit.
		updateCurriculumPath(fixture, fixture.curriculumRelativePath());
		DataLayoutMigrationPlan rerunPlan = new DataLayoutMigrationPlanner(fixture.config(), fixture.database()).plan();
		assertTrue(rerunPlan.canApply());
		assertTrue(rerunPlan.moves().stream().allMatch(DataLayoutMigrationPlan.Move::destinationAlreadyVerified));
		DataLayoutMigrationResult result = new DataLayoutMigrationExecutor(fixture.config(), fixture.database())
				.apply(rerunPlan);
		assertEquals(0, result.copiedFiles());
		assertEquals(2, result.reusedFiles());
		assertEquals(1, result.updatedExamPaths());
		assertEquals(1, result.updatedCurriculumSourcePaths());
		assertTrue(persistedExamPath(fixture).startsWith("subjects/"));
		assertTrue(persistedCurriculumPath(fixture).startsWith("subjects/"));
	}

	@Test
	void staleLateReferenceRollsBackEarlierDatabaseRewrite() throws Exception {
		Fixture fixture = createFixture("rollback");
		DataLayoutMigrationPlan plan = new DataLayoutMigrationPlanner(fixture.config(), fixture.database()).plan();

		// Simulate another process changing the curriculum reference after dry-run.
		// File copies still succeed, then the guarded second database rewrite fails.
		updateCurriculumPath(fixture, "changed-after-plan.pdf");
		assertThrows(IllegalStateException.class,
				() -> new DataLayoutMigrationExecutor(fixture.config(), fixture.database()).apply(plan));

		// The Exam update occurs first in the plan but belongs to the same SQLite
		// transaction, so the later stale curriculum failure rolls it back.
		assertEquals(fixture.examRelativePath(), persistedExamPath(fixture));
		assertEquals("changed-after-plan.pdf", persistedCurriculumPath(fixture));

		// Verified destination copies may remain after rollback. They are harmless
		// because no persisted path points at them and they support safe re-run.
		assertTrue(Files.isRegularFile(fixture.layout().examDirectory("Chemistry", "QCAA", 2024, "External Assessment")
				.resolve("paper-1.pdf")));
		assertTrue(Files.isRegularFile(
				fixture.layout().curriculumSourceDirectory("Chemistry", "2025").resolve("syllabus.pdf")));
	}

	private Fixture createFixture(String name) throws Exception {
		Path dataRoot = Files.createDirectories(tempDirectory.resolve(name));
		ApplicationConfig config = ApplicationConfig.fromDataRoot(dataRoot);
		Files.createDirectories(config.pdfDataRoot());
		Files.createDirectories(config.curriculumDataRoot());
		SqliteDatabase database = new SqliteDatabase(config.databasePath());
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SyllabusVersion syllabus = curriculumWriter.insertSyllabusVersion(chemistry, "2025", true);
		String curriculumRelativePath = "Chemistry--subject-" + chemistry.getId() + "/2025--syllabus-"
				+ syllabus.getId() + "/sources/syllabus.pdf";
		Path curriculumSource = config.curriculumDataRoot().resolve(curriculumRelativePath).normalize();
		Files.createDirectories(curriculumSource.getParent());
		Files.writeString(curriculumSource, "legacy curriculum source");
		syllabus = new SqliteCurriculumSourcePdfRepository(database).updateSourcePdfPath(syllabus,
				curriculumRelativePath);
		String examRelativePath = "Chemistry/QCAA/2024/paper-1.pdf";
		Path examSource = config.pdfDataRoot().resolve(examRelativePath).normalize();
		Files.createDirectories(examSource.getParent());
		Files.writeString(examSource, "legacy exam source");
		new SqliteExamImporter(database, new SqliteExamWriter(database)).importExam(chemistry, "QCAA", 2024,
				"External Assessment", "Paper 1", examRelativePath, ExamBookletQuestionFormat.WRITTEN_RESPONSE);
		return new Fixture(config, database, new ManagedDataLayout(dataRoot), syllabus, examSource, curriculumSource,
				examRelativePath, curriculumRelativePath);
	}

	private String persistedCurriculumPath(Fixture fixture) {
		return new SqliteCurriculumRepository(fixture.database()).findVersionById(fixture.syllabus().getId())
				.orElseThrow().getSourcePdfPath();
	}

	private String persistedExamPath(Fixture fixture) throws Exception {
		return new SqliteExamWriter(fixture.database()).findAllExamBooklets().getFirst().getSourceDocument()
				.getRelativePath();
	}

	private void updateCurriculumPath(Fixture fixture, String relativePath) throws Exception {
		try (Connection connection = fixture.database().openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						UPDATE syllabus_versions
						SET source_pdf_path = ?
						WHERE id = ?
						""")) {
			statement.setString(1, relativePath);
			statement.setLong(2, fixture.syllabus().getId());
			assertEquals(1, statement.executeUpdate());
		}
	}

	private record Fixture(ApplicationConfig config, SqliteDatabase database, ManagedDataLayout layout,
			SyllabusVersion syllabus, Path examSource, Path curriculumSource, String examRelativePath,
			String curriculumRelativePath) {
	}
}
