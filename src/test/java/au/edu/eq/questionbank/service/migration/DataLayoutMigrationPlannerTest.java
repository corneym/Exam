package au.edu.eq.questionbank.service.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
import au.edu.eq.questionbank.service.migration.DataLayoutMigrationPlan.AssetKind;
import au.edu.eq.questionbank.service.migration.DataLayoutMigrationPlan.Move;

class DataLayoutMigrationPlannerTest {

	@TempDir
	Path tempDirectory;

	@Test
	void dryRunPlansLegacyExamAndCurriculumSourcesWithoutMutation() throws Exception {
		Fixture fixture = createFixture("normal");
		DataLayoutMigrationPlan plan = new DataLayoutMigrationPlanner(fixture.config(), fixture.database()).plan();
		assertTrue(plan.canApply());
		assertTrue(plan.migrationRequired());
		assertEquals(2, plan.moves().size());
		assertTrue(plan.blockers().isEmpty());
		Move examMove = move(plan, AssetKind.EXAM_PDF);
		assertEquals(
				fixture.layout().examDirectory("Chemistry", "QCAA", 2024, "External Assessment").resolve("paper-1.pdf"),
				examMove.destination());
		assertEquals("subjects/Chemistry/exams/QCAA/2024/External Assessment/paper-1.pdf", examMove.newRelativePath());
		assertFalse(examMove.destinationAlreadyVerified());
		Move curriculumMove = move(plan, AssetKind.CURRICULUM_SOURCE_PDF);
		assertEquals(fixture.layout().curriculumSourceDirectory("Chemistry", "2025").resolve("syllabus.pdf"),
				curriculumMove.destination());
		assertEquals("subjects/Chemistry/curriculum/2025/sources/syllabus.pdf", curriculumMove.newRelativePath());
		assertFalse(curriculumMove.destinationAlreadyVerified());

		// Planning is a true dry-run. Old files and database references remain
		// untouched and no destination file is created.
		assertTrue(Files.isRegularFile(fixture.examSource()));
		assertTrue(Files.isRegularFile(fixture.curriculumSource()));
		assertFalse(Files.exists(examMove.destination()));
		assertFalse(Files.exists(curriculumMove.destination()));
		String persistedExamPath = new SqliteExamWriter(fixture.database()).findAllExamBooklets().getFirst()
				.getSourceDocument().getRelativePath();
		assertEquals(fixture.examRelativePath(), persistedExamPath);
		SyllabusVersion persistedSyllabus = new SqliteCurriculumRepository(fixture.database())
				.findVersionById(fixture.syllabus().getId()).orElseThrow();
		assertEquals(fixture.curriculumRelativePath(), persistedSyllabus.getSourcePdfPath());
	}

	@Test
	void existingDifferentDestinationBytesBlockMigration() throws Exception {
		Fixture fixture = createFixture("collision");
		Path destination = fixture.layout().examDirectory("Chemistry", "QCAA", 2024, "External Assessment")
				.resolve("paper-1.pdf");
		Files.createDirectories(destination.getParent());
		Files.writeString(destination, "different-destination-bytes");
		DataLayoutMigrationPlan plan = new DataLayoutMigrationPlanner(fixture.config(), fixture.database()).plan();
		assertFalse(plan.canApply());
		assertTrue(plan.blockers().stream().anyMatch(
				blocker -> "DESTINATION_COLLISION".equals(blocker.code()) && destination.equals(blocker.path())));
	}

	@Test
	void explicitLegacyWorkbookAssignmentPlansManagedWorkbook() throws Exception {
		Fixture fixture = createFixture("assigned-workbook");
		Path workbook = Files.writeString(fixture.config().curriculumDataRoot().resolve("chemistry.xlsx"),
				"legacy workbook bytes");
		DataLayoutMigrationPlan plan = new DataLayoutMigrationPlanner(fixture.config(), fixture.database(),
				List.of(new CurriculumWorkbookMigrationAssignment("chemistry.xlsx", "Chemistry", "2025"))).plan();
		assertTrue(plan.canApply());
		DataLayoutMigrationPlan.Move workbookMove = plan.moves().stream()
				.filter(move -> move.assetKind() == AssetKind.CURRICULUM_WORKBOOK).findFirst().orElseThrow();
		assertEquals(0, workbookMove.persistentId());
		assertEquals(workbook, workbookMove.source());
		assertEquals(fixture.layout().curriculumWorkbookDirectory("Chemistry", "2025").resolve("chemistry.xlsx"),
				workbookMove.destination());
		assertFalse(Files.exists(workbookMove.destination()));
		assertTrue(
				plan.blockers().stream().noneMatch(blocker -> "UNASSIGNED_CURRICULUM_WORKBOOK".equals(blocker.code())));
	}

	@Test
	void flatLegacyCurriculumWorkbookIsExplicitMigrationBlocker() throws Exception {
		Fixture fixture = createFixture("workbook");
		Path workbook = Files.writeString(fixture.config().curriculumDataRoot().resolve("chemistry-2025.xlsx"),
				"legacy curriculum workbook");
		DataLayoutMigrationPlan plan = new DataLayoutMigrationPlanner(fixture.config(), fixture.database()).plan();
		assertFalse(plan.canApply());
		assertTrue(plan.blockers().stream().anyMatch(
				blocker -> "UNASSIGNED_CURRICULUM_WORKBOOK".equals(blocker.code()) && workbook.equals(blocker.path())));
	}

	@Test
	void interruptedByteIdenticalCopyIsReusableButStillRequiresMigration() throws Exception {
		Fixture fixture = createFixture("partial");
		Path destination = fixture.layout().examDirectory("Chemistry", "QCAA", 2024, "External Assessment")
				.resolve("paper-1.pdf");
		Files.createDirectories(destination.getParent());
		Files.copy(fixture.examSource(), destination);
		DataLayoutMigrationPlan plan = new DataLayoutMigrationPlanner(fixture.config(), fixture.database()).plan();
		assertTrue(plan.canApply());
		assertTrue(plan.migrationRequired());
		Move examMove = move(plan, AssetKind.EXAM_PDF);
		assertTrue(examMove.destinationAlreadyVerified());

		// The legacy persisted path is still authoritative, so an interrupted copy
		// cannot be mistaken for completed migration.
		assertEquals(fixture.examRelativePath(), new SqliteExamWriter(fixture.database()).findAllExamBooklets()
				.getFirst().getSourceDocument().getRelativePath());
	}

	@Test
	void missingPersistedLegacySourceBlocksMigration() throws Exception {
		Fixture fixture = createFixture("missing");
		Files.delete(fixture.examSource());
		DataLayoutMigrationPlan plan = new DataLayoutMigrationPlanner(fixture.config(), fixture.database()).plan();
		assertFalse(plan.canApply());
		assertTrue(plan.blockers().stream().anyMatch(
				blocker -> "MISSING_SOURCE".equals(blocker.code()) && fixture.examSource().equals(blocker.path())));
	}

	@Test
	void unrelatedLegacyFileIsPreservedAsArchiveOnlyMaterial() throws Exception {
		Fixture fixture = createFixture("archive-only");
		Path orphan = Files.writeString(fixture.config().pdfDataRoot().resolve("old-notes.txt"),
				"retain this material");
		DataLayoutMigrationPlan plan = new DataLayoutMigrationPlanner(fixture.config(), fixture.database()).plan();
		assertTrue(plan.canApply());
		assertTrue(plan.archiveOnlyFiles().contains(orphan.toAbsolutePath().normalize()));
	}

	@Test
	void workbookAssignmentRequiresPersistedSubjectAndVersion() throws Exception {
		Fixture fixture = createFixture("unknown-workbook-owner");
		Files.writeString(fixture.config().curriculumDataRoot().resolve("chemistry.xlsx"), "legacy workbook bytes");
		DataLayoutMigrationPlan plan = new DataLayoutMigrationPlanner(fixture.config(), fixture.database(),
				List.of(new CurriculumWorkbookMigrationAssignment("chemistry.xlsx", "Chemistry", "2099"))).plan();
		assertFalse(plan.canApply());
		assertTrue(plan.blockers().stream().anyMatch(blocker -> "UNKNOWN_WORKBOOK_OWNER".equals(blocker.code())));
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

	private Move move(DataLayoutMigrationPlan plan, AssetKind assetKind) {
		return plan.moves().stream().filter(move -> move.assetKind() == assetKind).findFirst().orElseThrow();
	}

	private record Fixture(ApplicationConfig config, SqliteDatabase database, ManagedDataLayout layout,
			SyllabusVersion syllabus, Path examSource, Path curriculumSource, String examRelativePath,
			String curriculumRelativePath) {
	}
}
