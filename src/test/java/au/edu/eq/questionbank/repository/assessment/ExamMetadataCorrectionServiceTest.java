package au.edu.eq.questionbank.repository.assessment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.pdf.PdfStore;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class ExamMetadataCorrectionServiceTest {

	@TempDir
	Path tempDirectory;

	@Test
	void databaseFailureMovesRelocatedFileBackToOriginalLocation() throws Exception {
		Fixture fixture = createFixture("rollback.db");
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("rollback.db"));
		SqliteBookletApplicabilityReviewRepository reviews = new SqliteBookletApplicabilityReviewRepository(database);
		Path oldPdf = fixture.pdfRoot().resolve("Chemistry").resolve("2022 QCAA").resolve("2022").resolve("paper1.pdf");
		Files.createDirectories(oldPdf.getParent());
		Files.writeString(oldPdf, "original bytes");
		SourceDocument sourceDocument = fixture.examWriter()
				.insertSourceDocument(fixture.pdfRoot().relativize(oldPdf).toString());
		ExamBooklet booklet = fixture.examWriter().insertExamBooklet(fixture.exam(), sourceDocument, "Paper 1");
		Path correctedPdf = fixture.pdfRoot().resolve("Chemistry").resolve("QCAA").resolve("2022")
				.resolve("paper1.pdf");
		String correctedRelativePath = fixture.pdfRoot().relativize(correctedPdf).toString();

		// Occupy only the SQLite unique path, not the filesystem destination.
		// This forces failure after the service has already moved the PDF.
		fixture.examWriter().insertSourceDocument(correctedRelativePath);
		reviews.setReviewed(booklet.getId(), true);
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

		// A failed correction must leave the original review intact.
		assertEquals(Set.of(booklet.getId()), reviews.findReviewedBookletIds(fixture.exam().getId()));
		database.verifySchema();
	}

	@Test
	void metadataRelocationPreservesOnlyExistingApplicabilityReviews() throws Exception {
		Path dataRoot = Files.createDirectories(tempDirectory.resolve("review-preserving-correction"));
		Path legacyPdfRoot = dataRoot.resolve("pdf");
		ManagedDataLayout layout = new ManagedDataLayout(dataRoot);
		PdfStore pdfStore = new PdfStore(layout, legacyPdfRoot);
		SqliteDatabase database = new SqliteDatabase(dataRoot.resolve("questionbank.db"));
		database.initialiseSchema();
		Subject subject = new SqliteCurriculumWriter(database).insertSubject("Chemistry");
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		SqliteAnswerWriter answerWriter = new SqliteAnswerWriter(database, examWriter);
		SqliteBookletApplicabilityReviewRepository reviews = new SqliteBookletApplicabilityReviewRepository(database);
		ExamProvider provider = examWriter.insertExamProvider("Old QCAA");
		Exam exam = examWriter.insertExam(subject, provider, 2022, "Old Assessment");
		Exam otherExam = examWriter.insertExam(subject, provider, 2023, "Other Assessment");
		Path firstSource = tempDirectory.resolve("paper1.pdf");
		Path secondSource = tempDirectory.resolve("paper2.pdf");
		Path answerSource = tempDirectory.resolve("answers.pdf");
		Path otherSource = tempDirectory.resolve("other-paper.pdf");
		Files.writeString(firstSource, "first question paper");
		Files.writeString(secondSource, "second question paper");
		Files.writeString(answerSource, "answer paper");
		Files.writeString(otherSource, "unrelated question paper");
		Path firstPdf = pdfStore.importExamPdf(firstSource, "Chemistry", "Old QCAA", 2022, "Old Assessment");
		Path secondPdf = pdfStore.importExamPdf(secondSource, "Chemistry", "Old QCAA", 2022, "Old Assessment");
		Path answerPdf = pdfStore.importExamPdf(answerSource, "Chemistry", "Old QCAA", 2022, "Old Assessment");
		Path otherPdf = pdfStore.importExamPdf(otherSource, "Chemistry", "Old QCAA", 2023, "Other Assessment");
		SourceDocument firstDocument = examWriter.insertSourceDocument(pdfStore.relativePath(firstPdf));
		SourceDocument secondDocument = examWriter.insertSourceDocument(pdfStore.relativePath(secondPdf));
		SourceDocument otherDocument = examWriter.insertSourceDocument(pdfStore.relativePath(otherPdf));
		ExamBooklet firstBooklet = examWriter.insertExamBooklet(exam, firstDocument, "Paper 1");
		ExamBooklet secondBooklet = examWriter.insertExamBooklet(exam, secondDocument, "Paper 2");
		ExamBooklet otherBooklet = examWriter.insertExamBooklet(otherExam, otherDocument, "Other Paper");
		var answerFile = answerWriter.findOrCreateAnswerFile(exam, "Answers", pdfStore.relativePath(answerPdf));

		// Review only one of the corrected Exam's two booklets.
		// The unrelated Exam also has an existing confirmation.
		reviews.setReviewed(firstBooklet.getId(), true);
		reviews.setReviewed(otherBooklet.getId(), true);
		assertEquals(Set.of(firstBooklet.getId()), reviews.findReviewedBookletIds(exam.getId()));
		ExamMetadataCorrectionService service = new ExamMetadataCorrectionService(layout, legacyPdfRoot, examWriter,
				answerWriter);
		ExamMetadataCorrectionService.Result result = service.correct(exam, "QCAA", 2025, "External Assessment");
		assertEquals(exam.getId(), result.exam().getId());
		assertEquals("QCAA", result.exam().getProvider().getName());
		assertEquals(2025, result.exam().getYear());
		assertEquals("External Assessment", result.exam().getName());
		Path correctedDirectory = layout.examDirectory("Chemistry", "QCAA", 2025, "External Assessment");
		Path correctedFirstPdf = correctedDirectory.resolve(firstSource.getFileName());
		Path correctedSecondPdf = correctedDirectory.resolve(secondSource.getFileName());
		Path correctedAnswerPdf = correctedDirectory.resolve(answerSource.getFileName());

		// The source bytes are unchanged despite relocation.
		assertEquals("first question paper", Files.readString(correctedFirstPdf));
		assertEquals("second question paper", Files.readString(correctedSecondPdf));
		assertEquals("answer paper", Files.readString(correctedAnswerPdf));
		assertFalse(Files.exists(firstPdf));
		assertFalse(Files.exists(secondPdf));
		assertFalse(Files.exists(answerPdf));
		assertEquals("unrelated question paper", Files.readString(otherPdf));
		assertEquals(Map.of(firstDocument.getId(), layout.relativePath(correctedFirstPdf), secondDocument.getId(),
				layout.relativePath(correctedSecondPdf), answerFile.getSourceDocument().getId(),
				layout.relativePath(correctedAnswerPdf)), result.sourceDocumentPaths());

		// Reopen the repository to verify persisted confirmations,
		// independently of any previously constructed repository.
		SqliteBookletApplicabilityReviewRepository reopened = new SqliteBookletApplicabilityReviewRepository(
				new SqliteDatabase(dataRoot.resolve("questionbank.db")));
		assertTrue(reopened.isReviewed(firstBooklet.getId()));
		assertFalse(reopened.isReviewed(secondBooklet.getId()));
		assertTrue(reopened.isReviewed(otherBooklet.getId()));
		assertEquals(Set.of(firstBooklet.getId()), reopened.findReviewedBookletIds(exam.getId()));
		assertEquals(Set.of(otherBooklet.getId()), reopened.findReviewedBookletIds(otherExam.getId()));
		database.verifySchema();
	}

	@Test
	void ordinarySourcePathReplacementStillInvalidatesApplicabilityReview() throws Exception {
		Fixture fixture = createFixture("ordinary-replacement.db");
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("ordinary-replacement.db"));
		SqliteBookletApplicabilityReviewRepository reviews = new SqliteBookletApplicabilityReviewRepository(database);
		SourceDocument firstSource = fixture.examWriter().insertSourceDocument("paper1.pdf");
		SourceDocument secondSource = fixture.examWriter().insertSourceDocument("paper2.pdf");
		ExamBooklet firstBooklet = fixture.examWriter().insertExamBooklet(fixture.exam(), firstSource, "Paper 1");
		ExamBooklet secondBooklet = fixture.examWriter().insertExamBooklet(fixture.exam(), secondSource, "Paper 2");
		reviews.setReviewed(firstBooklet.getId(), true);
		reviews.setReviewed(secondBooklet.getId(), true);

		// The ordinary public API cannot establish that a different
		// source path refers to the same unchanged PDF.
		fixture.examWriter().correctExamMetadataAndSourceDocumentPaths(fixture.exam(),
				fixture.exam().getProvider().getName(), fixture.exam().getYear(), fixture.exam().getName(),
				Map.of(firstSource.getId(), "replacement-paper1.pdf"));
		assertFalse(reviews.isReviewed(firstBooklet.getId()));
		assertTrue(reviews.isReviewed(secondBooklet.getId()));
		assertEquals(Set.of(secondBooklet.getId()), reviews.findReviewedBookletIds(fixture.exam().getId()));
		ExamBooklet updatedBooklet = fixture.examWriter().findAllExamBooklets().stream()
				.filter(candidate -> candidate.getId() == firstBooklet.getId()).findFirst().orElseThrow();
		assertEquals(firstSource.getId(), updatedBooklet.getSourceDocument().getId());
		assertEquals("replacement-paper1.pdf", updatedBooklet.getSourceDocument().getRelativePath());
		database.verifySchema();
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
		Path oldProviderDirectory = oldDirectory.getParent();
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

		// Relocation leaves no obsolete provider/year directory tree behind. Pruning
		// stops at Chemistry because the corrected QCAA hierarchy now lives there.
		assertFalse(Files.exists(oldDirectory));
		assertFalse(Files.exists(oldProviderDirectory));
		assertTrue(Files.isDirectory(fixture.pdfRoot().resolve("Chemistry")));
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

	@Test
	void relocationPruningStopsAtFirstNonEmptyDirectory() throws Exception {
		Fixture fixture = createFixture("non-empty-source-directory.db");
		Path oldDirectory = fixture.pdfRoot().resolve("Chemistry").resolve("2022 QCAA").resolve("2022");
		Path oldPdf = oldDirectory.resolve("paper1.pdf");
		Path retainedFile = oldDirectory.resolve("retain.txt");
		Files.createDirectories(oldDirectory);
		Files.writeString(oldPdf, "exam bytes");
		Files.writeString(retainedFile, "unrelated managed-directory content");
		SourceDocument sourceDocument = fixture.examWriter()
				.insertSourceDocument(fixture.pdfRoot().relativize(oldPdf).toString());
		fixture.examWriter().insertExamBooklet(fixture.exam(), sourceDocument, "Paper 1");
		fixture.service().correct(fixture.exam(), "QCAA", 2022, "External Assessment");
		Path correctedPdf = fixture.pdfRoot().resolve("Chemistry").resolve("QCAA").resolve("2022")
				.resolve("paper1.pdf");
		assertTrue(Files.exists(correctedPdf));
		assertFalse(Files.exists(oldPdf));

		// An unrelated entry makes the old year directory the pruning boundary.
		// Neither that directory nor its ancestors may be removed.
		assertTrue(Files.exists(retainedFile));
		assertTrue(Files.isDirectory(oldDirectory));
		assertTrue(Files.isDirectory(oldDirectory.getParent()));
		assertTrue(Files.isDirectory(fixture.pdfRoot()));
	}

	@Test
	void subjectFirstCorrectionRelocatesAllSourcesUsingCorrectedExamIdentity() throws Exception {
		Path dataRoot = Files.createDirectories(tempDirectory.resolve("subject-first-correction"));
		Path legacyPdfRoot = dataRoot.resolve("pdf");
		ManagedDataLayout layout = new ManagedDataLayout(dataRoot);
		PdfStore pdfStore = new PdfStore(layout, legacyPdfRoot);
		SqliteDatabase database = new SqliteDatabase(dataRoot.resolve("questionbank.db"));
		database.initialiseSchema();
		Subject subject = new SqliteCurriculumWriter(database).insertSubject("Chemistry");
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		SqliteAnswerWriter answerWriter = new SqliteAnswerWriter(database, examWriter);
		ExamProvider provider = examWriter.insertExamProvider("2022 QCAA");
		Exam exam = examWriter.insertExam(subject, provider, 2022, "Old Assessment");
		Path questionSource = tempDirectory.resolve("correction-question.pdf");
		Path answerSource = tempDirectory.resolve("correction-answer.pdf");
		Files.writeString(questionSource, "question bytes");
		Files.writeString(answerSource, "answer bytes");
		Path questionPdf = pdfStore.importExamPdf(questionSource, "Chemistry", "2022 QCAA", 2022, "Old Assessment");
		Path answerPdf = pdfStore.importExamPdf(answerSource, "Chemistry", "2022 QCAA", 2022, "Old Assessment");
		SourceDocument questionDocument = examWriter.insertSourceDocument(pdfStore.relativePath(questionPdf));
		ExamBooklet booklet = examWriter.insertExamBooklet(exam, questionDocument, "Paper 1");
		var answerFile = answerWriter.findOrCreateAnswerFile(exam, "Answers", pdfStore.relativePath(answerPdf));
		ExamMetadataCorrectionService service = new ExamMetadataCorrectionService(layout, legacyPdfRoot, examWriter,
				answerWriter);
		ExamMetadataCorrectionService.Result result = service.correct(exam, "QCAA", 2025, "External Assessment");
		Path correctedDirectory = layout.examDirectory("Chemistry", "QCAA", 2025, "External Assessment");
		Path correctedQuestionPdf = correctedDirectory.resolve(questionSource.getFileName());
		Path correctedAnswerPdf = correctedDirectory.resolve(answerSource.getFileName());
		assertFalse(Files.exists(questionPdf));
		assertFalse(Files.exists(answerPdf));
		assertEquals("question bytes", Files.readString(correctedQuestionPdf));
		assertEquals("answer bytes", Files.readString(correctedAnswerPdf));
		assertEquals("QCAA", result.exam().getProvider().getName());
		assertEquals(2025, result.exam().getYear());
		assertEquals("External Assessment", result.exam().getName());
		ExamBooklet correctedBooklet = examWriter.findAllExamBooklets().stream()
				.filter(candidate -> candidate.getId() == booklet.getId()).findFirst().orElseThrow();
		assertEquals(questionDocument.getId(), correctedBooklet.getSourceDocument().getId());
		assertEquals(layout.relativePath(correctedQuestionPdf), correctedBooklet.getSourceDocument().getRelativePath());
		var correctedAnswerFile = answerWriter.findAnswerFiles(result.exam()).getFirst();
		assertEquals(answerFile.getSourceDocument().getId(), correctedAnswerFile.getSourceDocument().getId());
		assertEquals(layout.relativePath(correctedAnswerPdf),
				correctedAnswerFile.getSourceDocument().getRelativePath());

		// The old Assessment hierarchy must be removed once all of its managed sources
		// have moved successfully.
		assertFalse(Files.exists(layout.examDirectory("Chemistry", "2022 QCAA", 2022, "Old Assessment")));
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
