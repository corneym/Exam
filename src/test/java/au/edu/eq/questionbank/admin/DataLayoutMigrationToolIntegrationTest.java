package au.edu.eq.questionbank.admin;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.model.AnswerFile;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamBookletQuestionFormat;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.repository.assessment.SqliteAnswerWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteExamImporter;
import au.edu.eq.questionbank.repository.assessment.SqliteExamWriter;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumSourcePdfRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.migration.DataLayoutMigrationStartupGuard;

class DataLayoutMigrationToolIntegrationTest {

	@TempDir
	Path tempDirectory;

	@Test
	void migratesRealisticLegacyRootThroughAdminEntryPointAndLeavesStartupCurrent() throws Exception {
		Path dataRoot = Files.createDirectories(tempDirectory.resolve("legacy-installation"));
		ApplicationConfig config = ApplicationConfig.fromDataRoot(dataRoot);
		Files.createDirectories(config.pdfDataRoot());
		Files.createDirectories(config.curriculumDataRoot());
		SqliteDatabase database = new SqliteDatabase(config.databasePath());
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SyllabusVersion syllabus = curriculumWriter.insertSyllabusVersion(chemistry, "2025", true);
		String curriculumSourceRelative = "Chemistry--subject-" + chemistry.getId() + "/2025--syllabus-"
				+ syllabus.getId() + "/sources/syllabus.pdf";
		Path curriculumSource = config.curriculumDataRoot().resolve(curriculumSourceRelative);
		Files.createDirectories(curriculumSource.getParent());
		Files.writeString(curriculumSource, "legacy syllabus pdf bytes");
		new SqliteCurriculumSourcePdfRepository(database).updateSourcePdfPath(syllabus, curriculumSourceRelative);
		String questionRelative = "Chemistry/QCAA/2024/paper-1.pdf";
		String answerRelative = "Chemistry/QCAA/2024/answers.pdf";
		Path questionPdf = config.pdfDataRoot().resolve(questionRelative);
		Path answerPdf = config.pdfDataRoot().resolve(answerRelative);
		Files.createDirectories(questionPdf.getParent());
		Files.writeString(questionPdf, "legacy question pdf bytes");
		Files.writeString(answerPdf, "legacy answer pdf bytes");
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		ExamBooklet booklet = new SqliteExamImporter(database, examWriter).importExam(chemistry, "QCAA", 2024,
				"External Assessment", "Paper 1", questionRelative, ExamBookletQuestionFormat.WRITTEN_RESPONSE);
		SqliteAnswerWriter answerWriter = new SqliteAnswerWriter(database, examWriter);
		answerWriter.findOrCreateAnswerFile(booklet.getExam(), "Marking Guide", answerRelative);
		Path workbook = config.curriculumDataRoot().resolve("chemistry-2025.xlsx");
		createCurriculumWorkbook(workbook);
		Path recoveryOnlyFile = Files.writeString(config.pdfDataRoot().resolve("migration-notes.txt"),
				"retain in migration archive");
		ManagedDataLayout layout = new ManagedDataLayout(dataRoot);
		String[] dryRunArguments = { "--data-root", dataRoot.toString(), "--assign-workbook", "chemistry-2025.xlsx",
				"Chemistry", "2025", "--dry-run" };
		ByteArrayOutputStream dryRunOutput = new ByteArrayOutputStream();
		ByteArrayOutputStream dryRunError = new ByteArrayOutputStream();
		int dryRunResult = DataLayoutMigrationTool.run(dryRunArguments, printStream(dryRunOutput),
				printStream(dryRunError));
		assertEquals(0, dryRunResult);
		assertTrue(dryRunError.toString(StandardCharsets.UTF_8).isEmpty());
		String dryRunText = dryRunOutput.toString(StandardCharsets.UTF_8);
		assertTrue(dryRunText.contains("Migration required: yes"));
		assertTrue(dryRunText.contains("Planned managed moves: 4"));
		assertTrue(dryRunText.contains("Archive-only legacy files: 1"));
		assertTrue(dryRunText.contains("Blockers: 0"));

		// Dry-run must not create the Subject-first tree, update persistence or
		// disturb the legacy installation.
		assertFalse(Files.exists(layout.subjectsRoot()));
		assertTrue(Files.isRegularFile(questionPdf));
		assertTrue(Files.isRegularFile(answerPdf));
		assertTrue(Files.isRegularFile(curriculumSource));
		assertTrue(Files.isRegularFile(workbook));
		assertEquals(questionRelative,
				examWriter.findAllExamBooklets().getFirst().getSourceDocument().getRelativePath());
		String[] applyArguments = { "--data-root", dataRoot.toString(), "--assign-workbook", "chemistry-2025.xlsx",
				"Chemistry", "2025", "--apply" };
		ByteArrayOutputStream applyOutput = new ByteArrayOutputStream();
		ByteArrayOutputStream applyError = new ByteArrayOutputStream();
		int applyResult = DataLayoutMigrationTool.run(applyArguments, printStream(applyOutput),
				printStream(applyError));
		assertEquals(0, applyResult);
		assertTrue(applyError.toString(StandardCharsets.UTF_8).isEmpty());
		assertTrue(applyOutput.toString(StandardCharsets.UTF_8)
				.contains("Subject-first migration completed successfully."));
		Path migratedQuestionPdf = layout.examDirectory("Chemistry", "QCAA", 2024, "External Assessment")
				.resolve("paper-1.pdf");
		Path migratedAnswerPdf = layout.examDirectory("Chemistry", "QCAA", 2024, "External Assessment")
				.resolve("answers.pdf");
		Path migratedCurriculumPdf = layout.curriculumSourceDirectory("Chemistry", "2025").resolve("syllabus.pdf");
		Path migratedWorkbook = layout.curriculumWorkbookDirectory("Chemistry", "2025").resolve("chemistry-2025.xlsx");
		assertTrue(Files.isRegularFile(migratedQuestionPdf));
		assertTrue(Files.isRegularFile(migratedAnswerPdf));
		assertTrue(Files.isRegularFile(migratedCurriculumPdf));
		assertTrue(Files.isRegularFile(migratedWorkbook));
		assertEquals(-1L,
				Files.mismatch(questionPdfFromArchive(config, "Chemistry/QCAA/2024/paper-1.pdf"), migratedQuestionPdf));
		assertEquals(-1L,
				Files.mismatch(answerPdfFromArchive(config, "Chemistry/QCAA/2024/answers.pdf"), migratedAnswerPdf));
		assertFalse(Files.exists(config.pdfDataRoot()));
		assertFalse(Files.exists(config.curriculumDataRoot()));
		Path archiveRoot = dataRoot.resolve("migration-archive/pre-subject-first");
		assertTrue(Files.isRegularFile(archiveRoot.resolve("pdf/migration-notes.txt")));
		assertEquals("retain in migration archive", Files.readString(archiveRoot.resolve("pdf/migration-notes.txt")));
		assertTrue(Files.isRegularFile(archiveRoot.resolve("curriculum/chemistry-2025.xlsx")));
		assertTrue(Files.isRegularFile(
				archiveRoot.resolve("curriculum").resolve(config.curriculumDataRoot().relativize(curriculumSource))));

		// Reopen through ordinary repositories rather than trusting the migration
		// plan or its in-memory objects.
		SqliteExamWriter reopenedExamWriter = new SqliteExamWriter(database);
		ExamBooklet migratedBooklet = reopenedExamWriter.findAllExamBooklets().stream()
				.filter(candidate -> candidate.getId() == booklet.getId()).findFirst().orElseThrow();
		assertEquals(layout.relativePath(migratedQuestionPdf), migratedBooklet.getSourceDocument().getRelativePath());
		List<AnswerFile> migratedAnswerFiles = new SqliteAnswerWriter(database, reopenedExamWriter)
				.findAnswerFiles(migratedBooklet.getExam());
		assertEquals(1, migratedAnswerFiles.size());
		assertEquals(layout.relativePath(migratedAnswerPdf),
				migratedAnswerFiles.getFirst().getSourceDocument().getRelativePath());
		SyllabusVersion migratedSyllabus = new SqliteCurriculumRepository(database).findVersionById(syllabus.getId())
				.orElseThrow();
		assertEquals(layout.relativePath(migratedCurriculumPdf), migratedSyllabus.getSourcePdfPath());
		assertDoesNotThrow(() -> new DataLayoutMigrationStartupGuard(config, database).requireCurrentLayout());

		// Re-running the exact successful command, including its historic workbook
		// assignment, is now a clean no-op.
		ByteArrayOutputStream rerunOutput = new ByteArrayOutputStream();
		ByteArrayOutputStream rerunError = new ByteArrayOutputStream();
		int rerunResult = DataLayoutMigrationTool.run(applyArguments, printStream(rerunOutput),
				printStream(rerunError));
		assertEquals(0, rerunResult);
		assertTrue(rerunError.toString(StandardCharsets.UTF_8).isEmpty());
		assertTrue(rerunOutput.toString(StandardCharsets.UTF_8).contains("Migration required: no"));
		assertFalse(Files.exists(recoveryOnlyFile));
	}

	private Path answerPdfFromArchive(ApplicationConfig config, String relativePath) {
		return config.dataRoot().resolve("migration-archive/pre-subject-first/pdf").resolve(relativePath).normalize();
	}

	private void createCurriculumWorkbook(Path path) throws Exception {
		try (Workbook workbook = new XSSFWorkbook()) {
			Sheet sheet = workbook.createSheet("Curriculum");
			Row header = sheet.createRow(0);
			header.createCell(0).setCellValue("Code");
			header.createCell(1).setCellValue("Content");
			Row descriptor = sheet.createRow(1);
			descriptor.createCell(0).setCellValue("1.1.1");
			descriptor.createCell(1).setCellValue("Describe a migration fixture.");
			try (OutputStream output = Files.newOutputStream(path)) {
				workbook.write(output);
			}
		}
	}

	private PrintStream printStream(ByteArrayOutputStream bytes) {
		return new PrintStream(bytes, true, StandardCharsets.UTF_8);
	}

	private Path questionPdfFromArchive(ApplicationConfig config, String relativePath) {
		return config.dataRoot().resolve("migration-archive/pre-subject-first/pdf").resolve(relativePath).normalize();
	}
}
