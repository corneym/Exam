package au.edu.eq.questionbank.repository.sqlite;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.repository.assessment.AnswerPdfReplacementService;
import au.edu.eq.questionbank.repository.assessment.ExamMetadataCorrectionService;
import au.edu.eq.questionbank.repository.assessment.QuestionBookletPdfReplacementService;
import au.edu.eq.questionbank.repository.assessment.SqliteAnswerWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteExamWriter;
import au.edu.eq.questionbank.service.workpackage.SqliteContributorAssignmentSessionRepository;

class SqliteAssignmentAssetServiceGuardTest {

	@TempDir
	Path directory;

	@Test
	void answerPdfReplacementIsBlockedBeforeCreatingManagedFiles() throws Exception {
		Fixture fixture = fixture("answer-replacement");
		AnswerPdfReplacementService service = new AnswerPdfReplacementService(fixture.database(),
				new ManagedDataLayout(fixture.dataRoot()), fixture.dataRoot().resolve("legacy"));
		IllegalStateException failure = assertThrows(IllegalStateException.class,
				() -> service.replace(fixture.booklet(), fixture.replacement()));
		assertTrue(failure.getMessage().contains("contributor assignment"));
		assertEquals("Original managed PDF", Files.readString(fixture.managedPdf()));
		assertFalse(Files.exists(fixture.managedPdf().getParent().resolve("replacement.pdf")));
		fixture.database().verifyIntegrity();
	}

	@Test
	void examMetadataCorrectionCannotMoveAssignedSourceFiles() throws Exception {
		Fixture fixture = fixture("metadata-correction");
		SqliteExamWriter exams = new SqliteExamWriter(fixture.database());
		SqliteAnswerWriter answers = new SqliteAnswerWriter(fixture.database(), exams);
		ExamMetadataCorrectionService service = new ExamMetadataCorrectionService(
				new ManagedDataLayout(fixture.dataRoot()), fixture.dataRoot().resolve("legacy"), exams, answers);
		assertThrows(IllegalStateException.class,
				() -> service.correct(fixture.booklet().getExam(), "NEAP", 2025, "Corrected Assessment"));
		assertEquals("Original managed PDF", Files.readString(fixture.managedPdf()));

		// No managed source path may have been relocated.
		assertTrue(Files.exists(fixture.managedPdf()));
		assertEquals("Assigned Exam", fixture.booklet().getExam().getName());
		fixture.database().verifyIntegrity();
	}

	@Test
	void normalModeAllowsCoordinatorWritePreflight() throws Exception {
		Fixture fixture = fixture("ordinary-mode");
		SqliteExamWriter exams = new SqliteExamWriter(fixture.database());
		assertThrows(IllegalStateException.class, exams::requireCoordinatorStructureEdit);
		new SqliteContributorAssignmentSessionRepository(fixture.database()).clearActive();

		// Exiting assignment mode restores ordinary editing
		// without deleting the stored assignment session.
		assertDoesNotThrow(exams::requireCoordinatorStructureEdit);
		assertEquals("Original managed PDF", Files.readString(fixture.managedPdf()));
		fixture.database().verifyIntegrity();
	}

	@Test
	void questionPdfReplacementIsBlockedBeforeChangingManagedBytes() throws Exception {
		Fixture fixture = fixture("question-replacement");
		QuestionBookletPdfReplacementService service = new QuestionBookletPdfReplacementService(fixture.database(),
				new ManagedDataLayout(fixture.dataRoot()), fixture.dataRoot().resolve("legacy"));
		IllegalStateException failure = assertThrows(IllegalStateException.class,
				() -> service.replace(fixture.booklet(), fixture.replacement()));
		assertTrue(failure.getMessage().contains("contributor assignment"));
		assertEquals("Original managed PDF", Files.readString(fixture.managedPdf()));
		assertEquals("Replacement PDF", Files.readString(fixture.replacement()));
		fixture.database().verifyIntegrity();
	}

	@Test
	void restrictionSurvivesReopeningDatabase() throws Exception {
		Fixture fixture = fixture("restart");
		SqliteDatabase reopened = new SqliteDatabase(fixture.dataRoot().resolve("questionbank.db"));
		reopened.verifySchema();
		SqliteExamWriter exams = new SqliteExamWriter(reopened);
		assertThrows(IllegalStateException.class, exams::requireCoordinatorStructureEdit);
	}

	private Fixture fixture(String name) throws Exception {
		Path dataRoot = Files.createDirectories(directory.resolve(name));
		SqliteDatabase database = SqliteAssignmentGuardFixture.create(dataRoot.resolve("questionbank.db"), false);
		Path managedPdf = dataRoot.resolve("subjects/Biology/exams/test.pdf");
		Files.createDirectories(managedPdf.getParent());
		Files.writeString(managedPdf, "Original managed PDF");
		Path replacement = dataRoot.resolve("replacement.pdf");
		Files.writeString(replacement, "Replacement PDF");
		ExamBooklet booklet = new SqliteExamWriter(database).findAllExamBooklets().stream()
				.filter(value -> value.getId() == 61).findFirst().orElseThrow();
		return new Fixture(dataRoot, database, booklet, managedPdf, replacement);
	}

	private record Fixture(Path dataRoot, SqliteDatabase database, ExamBooklet booklet, Path managedPdf,
			Path replacement) {
	}
}
