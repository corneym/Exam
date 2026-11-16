package au.edu.eq.questionbank.repository.sqlite;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.pdf.PdfStore;
import au.edu.eq.questionbank.repository.assessment.SqliteExamWriter;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumSourcePdfRepository;
import au.edu.eq.questionbank.service.curriculum.CurriculumAuthoringSession;
import au.edu.eq.questionbank.service.curriculum.CurriculumDraft;
import au.edu.eq.questionbank.service.curriculum.CurriculumSourcePdfService;
import au.edu.eq.questionbank.service.curriculum.CurriculumSourcePdfStore;
import au.edu.eq.questionbank.service.document.CoordinatorExamPdfImportService;
import au.edu.eq.questionbank.service.workpackage.SqliteContributorAssignmentSessionRepository;

class SqliteCoordinatorPdfImportBoundaryTest {

	@TempDir
	Path directory;

	@Test
	void exitingAssignmentAllowsOrdinaryExamPdfImport() throws Exception {
		Fixture fixture = fixture("ordinary-exam-import");
		new SqliteContributorAssignmentSessionRepository(fixture.database()).clearActive();
		Path managed = fixture.examImport().importPdf(fixture.sourcePdf(), "Biology", "QCAA", 2025, "Assigned Exam");
		assertTrue(Files.isRegularFile(managed));
		assertArrayEquals(fixture.sourceBytes(), Files.readAllBytes(managed));
		fixture.database().verifyIntegrity();
	}

	@Test
	void exitingAssignmentAllowsOrdinarySyllabusAttachment() throws Exception {
		Fixture fixture = fixture("ordinary-syllabus-import");
		new SqliteContributorAssignmentSessionRepository(fixture.database()).clearActive();
		CurriculumAuthoringSession session = fixture.curriculumSession();
		Path managed = fixture.curriculumService().attachPdf(session, fixture.sourcePdf());
		assertTrue(Files.isRegularFile(managed));
		assertArrayEquals(fixture.sourceBytes(), Files.readAllBytes(managed));
		var persisted = new SqliteCurriculumRepository(fixture.database()).findVersionById(11).orElseThrow();
		assertEquals(session.syllabusVersion().getSourcePdfPath(), persisted.getSourcePdfPath());
		fixture.database().verifyIntegrity();
	}

	@Test
	void initialExamPdfImportRejectsAssignmentBeforeCreatingFile() throws Exception {
		Fixture fixture = fixture("blocked-exam-import");
		Path destination = fixture.layout().examDirectory("Biology", "QCAA", 2025, "Assigned Exam")
				.resolve("new-questions.pdf");
		IllegalStateException failure = assertThrows(IllegalStateException.class,
				() -> fixture.examImport().importPdf(fixture.sourcePdf(), "Biology", "QCAA", 2025, "Assigned Exam"));
		assertTrue(failure.getMessage().contains("contributor assignment"));
		assertFalse(Files.exists(destination));
		assertFalse(Files.exists(destination.getParent()));

		// The externally selected PDF is never modified.
		assertArrayEquals(fixture.sourceBytes(), Files.readAllBytes(fixture.sourcePdf()));
		fixture.database().verifyIntegrity();
	}

	@Test
	void reopenedDatabaseRetainsFileImportRestriction() throws Exception {
		Fixture fixture = fixture("restart");
		SqliteDatabase reopened = new SqliteDatabase(fixture.dataRoot().resolve("questionbank.db"));
		reopened.verifySchema();
		CoordinatorExamPdfImportService service = new CoordinatorExamPdfImportService(new PdfStore(fixture.layout()),
				new SqliteExamWriter(reopened));
		Path destination = fixture.layout().examDirectory("Biology", "QCAA", 2025, "Assigned Exam")
				.resolve("new-questions.pdf");
		assertThrows(IllegalStateException.class,
				() -> service.importPdf(fixture.sourcePdf(), "Biology", "QCAA", 2025, "Assigned Exam"));
		assertFalse(Files.exists(destination));
	}

	@Test
	void syllabusAttachmentRejectsAssignmentBeforeCreatingCopy() throws Exception {
		Fixture fixture = fixture("blocked-syllabus-import");
		Path managedDirectory = fixture.layout().curriculumSourceDirectory("Biology", "Biology 2025");
		CurriculumAuthoringSession session = fixture.curriculumSession();
		IllegalStateException failure = assertThrows(IllegalStateException.class,
				() -> fixture.curriculumService().attachPdf(session, fixture.sourcePdf()));
		assertTrue(failure.getMessage().contains("syllabus PDF attachment is prohibited"));
		assertFalse(Files.exists(managedDirectory));
		assertNull(session.syllabusVersion().getSourcePdfPath());
		var persisted = new SqliteCurriculumRepository(fixture.database()).findVersionById(11).orElseThrow();
		assertNull(persisted.getSourcePdfPath());
		fixture.database().verifyIntegrity();
	}

	private Fixture fixture(String name) throws Exception {
		Path dataRoot = Files.createDirectories(directory.resolve(name));
		SqliteDatabase database = SqliteAssignmentGuardFixture.create(dataRoot.resolve("questionbank.db"), false);
		ManagedDataLayout layout = new ManagedDataLayout(dataRoot);
		Path downloads = Files.createDirectories(directory.resolve(name + "-downloads"));
		Path source = downloads.resolve("new-questions.pdf");
		byte[] content = """
				%PDF-1.4
				Coordinator source document
				%%EOF
				""".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		Files.write(source, content);
		return new Fixture(dataRoot, database, layout, source, content);
	}

	private record Fixture(Path dataRoot, SqliteDatabase database, ManagedDataLayout layout, Path sourcePdf,
			byte[] sourceBytes) {

		private CoordinatorExamPdfImportService examImport() {
			return new CoordinatorExamPdfImportService(new PdfStore(layout), new SqliteExamWriter(database));
		}

		private CurriculumAuthoringSession curriculumSession() {
			var version = new SqliteCurriculumRepository(database).findVersionById(11).orElseThrow();
			return new CurriculumAuthoringSession(version, new CurriculumDraft());
		}

		private CurriculumSourcePdfService curriculumService() {
			return new CurriculumSourcePdfService(new CurriculumSourcePdfStore(layout, dataRoot.resolve("curriculum")),
					new SqliteCurriculumSourcePdfRepository(database));
		}
	}
}
