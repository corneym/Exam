package au.edu.eq.questionbank.repository.assessment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class ExamMetadataCorrectionServiceTest {

	@TempDir
	Path tempDirectory;

	@Test
	void databaseFailureMovesRelocatedFileBackToOriginalLocation() throws Exception {
		Fixture fixture = createFixture("rollback.db");
		Path oldPdf = fixture.pdfRoot().resolve("Chemistry").resolve("2022 QCAA").resolve("2022").resolve("paper1.pdf");
		Files.createDirectories(oldPdf.getParent());
		Files.writeString(oldPdf, "original bytes");
		SourceDocument sourceDocument = fixture.examWriter()
				.insertSourceDocument(fixture.pdfRoot().relativize(oldPdf).toString());
		fixture.examWriter().insertExamBooklet(fixture.exam(), sourceDocument, "Paper 1");
		Path correctedPdf = fixture.pdfRoot().resolve("Chemistry").resolve("QCAA").resolve("2022")
				.resolve("paper1.pdf");
		String correctedRelativePath = fixture.pdfRoot().relativize(correctedPdf).toString();

		// Occupy only the SQLite unique path, not the filesystem destination. This
		// forces failure after the service has already moved the physical PDF.
		fixture.examWriter().insertSourceDocument(correctedRelativePath);
		assertThrows(SQLException.class,
				() -> fixture.service().correct(fixture.exam(), "QCAA", 2022, "External Assessment"));
		assertTrue(Files.exists(oldPdf));
		assertFalse(Files.exists(correctedPdf));
		assertEquals("original bytes", Files.readString(oldPdf));
		Exam unchanged = fixture.examWriter().findExamByProviderAndYear(fixture.subject(), "2022 QCAA", 2022);
		assertEquals(fixture.exam().getId(), unchanged.getId());
		assertEquals("2022", unchanged.getName());
		ExamBooklet unchangedBooklet = fixture.examWriter().findAllExamBooklets().stream()
				.filter(candidate -> candidate.getExam().getId() == fixture.exam().getId()).findFirst().orElseThrow();
		assertEquals(fixture.pdfRoot().relativize(oldPdf).toString(),
				unchangedBooklet.getSourceDocument().getRelativePath());
	}

	@Test
	void rejectsSourceDocumentSharedWithAnotherExamBeforeMovingFile() throws Exception {
		Fixture fixture = createFixture("shared-source.db");
		Path oldPdf = fixture.pdfRoot().resolve("Chemistry").resolve("2022 QCAA").resolve("2022").resolve("paper1.pdf");
		Files.createDirectories(oldPdf.getParent());
		Files.writeString(oldPdf, "shared bytes");
		SourceDocument sharedSource = fixture.examWriter()
				.insertSourceDocument(fixture.pdfRoot().relativize(oldPdf).toString());
		fixture.examWriter().insertExamBooklet(fixture.exam(), sharedSource, "Paper 1");
		Exam otherExam = fixture.examWriter().insertExam(fixture.subject(), fixture.exam().getProvider(), 2023,
				"Other Assessment");
		fixture.examWriter().insertExamBooklet(otherExam, sharedSource, "Paper 1");
		assertThrows(IllegalStateException.class,
				() -> fixture.service().correct(fixture.exam(), "QCAA", 2022, "External Assessment"));
		assertTrue(Files.exists(oldPdf));
		assertFalse(Files
				.exists(fixture.pdfRoot().resolve("Chemistry").resolve("QCAA").resolve("2022").resolve("paper1.pdf")));
	}

	@Test
	void relocatesBookletAndAnswerFilesAndUpdatesPersistedPaths() throws Exception {
		Fixture fixture = createFixture("relocate.db");
		Path oldDirectory = fixture.pdfRoot().resolve("Chemistry").resolve("2022 QCAA").resolve("2022");
		Path examPdf = oldDirectory.resolve("paper1.pdf");
		Path answerPdf = oldDirectory.resolve("answers.pdf");
		Files.createDirectories(oldDirectory);
		Files.writeString(examPdf, "exam bytes");
		Files.writeString(answerPdf, "answer bytes");
		SourceDocument examSource = fixture.examWriter()
				.insertSourceDocument(fixture.pdfRoot().relativize(examPdf).toString());
		ExamBooklet booklet = fixture.examWriter().insertExamBooklet(fixture.exam(), examSource, "Paper 1");
		var answerFile = fixture.answerWriter().findOrCreateAnswerFile(fixture.exam(), "Answers",
				fixture.pdfRoot().relativize(answerPdf).toString());
		ExamMetadataCorrectionService.Result result = fixture.service().correct(fixture.exam(), "QCAA", 2022,
				"External Assessment");
		Path correctedDirectory = fixture.pdfRoot().resolve("Chemistry").resolve("QCAA").resolve("2022");
		Path correctedExamPdf = correctedDirectory.resolve("paper1.pdf");
		Path correctedAnswerPdf = correctedDirectory.resolve("answers.pdf");
		assertFalse(Files.exists(examPdf));
		assertFalse(Files.exists(answerPdf));
		assertEquals("exam bytes", Files.readString(correctedExamPdf));
		assertEquals("answer bytes", Files.readString(correctedAnswerPdf));
		assertEquals(fixture.exam().getId(), result.exam().getId());
		assertEquals("QCAA", result.exam().getProvider().getName());
		assertEquals("External Assessment", result.exam().getName());
		ExamBooklet correctedBooklet = fixture.examWriter().findAllExamBooklets().stream()
				.filter(candidate -> candidate.getId() == booklet.getId()).findFirst().orElseThrow();
		assertEquals(examSource.getId(), correctedBooklet.getSourceDocument().getId());
		assertEquals(fixture.pdfRoot().relativize(correctedExamPdf).toString(),
				correctedBooklet.getSourceDocument().getRelativePath());
		var correctedAnswerFile = fixture.answerWriter().findAnswerFiles(result.exam()).getFirst();
		assertEquals(answerFile.getSourceDocument().getId(), correctedAnswerFile.getSourceDocument().getId());
		assertEquals(fixture.pdfRoot().relativize(correctedAnswerPdf).toString(),
				correctedAnswerFile.getSourceDocument().getRelativePath());
	}

	private Fixture createFixture(String databaseName) throws Exception {
		Path databasePath = tempDirectory.resolve(databaseName);
		Path pdfRoot = Files.createDirectories(tempDirectory.resolve(databaseName + "-pdf"));
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		Subject subject = new SqliteCurriculumWriter(database).insertSubject("Chemistry");
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		SqliteAnswerWriter answerWriter = new SqliteAnswerWriter(database, examWriter);
		ExamProvider malformedProvider = examWriter.insertExamProvider("2022 QCAA");
		Exam exam = examWriter.insertExam(subject, malformedProvider, 2022, "2022");
		ExamMetadataCorrectionService service = new ExamMetadataCorrectionService(pdfRoot, examWriter, answerWriter);
		return new Fixture(subject, exam, examWriter, answerWriter, pdfRoot, service);
	}

	private record Fixture(Subject subject, Exam exam, SqliteExamWriter examWriter, SqliteAnswerWriter answerWriter,
			Path pdfRoot, ExamMetadataCorrectionService service) {
	}
}
