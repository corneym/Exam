package au.edu.eq.questionbank.repository.assessment;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamBookletQuestionFormat;
import au.edu.eq.questionbank.model.ExamCaptureState;
import au.edu.eq.questionbank.model.ImageQuestionContentPart;
import au.edu.eq.questionbank.model.PdfQuestionContentPart;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionContentPart;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.SharedQuestionContext;
import au.edu.eq.questionbank.model.SharedQuestionContextRegion;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.document.SourceDocumentHashService;

class QuestionBookletPdfReplacementServiceTest {

	@TempDir
	Path tempDirectory;

	@Test
	void identicalReplacementBackfillsHashWithoutInvalidatingCapture() throws Exception {
		Path pdfRoot = tempDirectory.resolve("same-pdf-root");
		Path managedPdf = pdfRoot.resolve("Chemistry/QCAA/2024/paper1.pdf");
		Files.createDirectories(managedPdf.getParent());
		Files.writeString(managedPdf, "same booklet bytes");
		Path externalCopy = tempDirectory.resolve("same-booklet-copy.pdf");
		Files.copy(managedPdf, externalCopy);
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("same-content.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SyllabusVersion syllabus = curriculumWriter.insertSyllabusVersion(chemistry, "2025", true);
		Unit unit = curriculumWriter.insertUnit(syllabus, "1", "Unit 1", 1);
		Topic topic = curriculumWriter.insertTopic(unit, "1.1", "Topic 1", 1);
		Subtopic subtopic = curriculumWriter.insertSubtopic(topic, "1.1.1", "Subtopic 1", 1);
		SqliteExamWriter examWriter = new SqliteExamWriter(database);

		// Deliberately create a migrated-style SourceDocument with no persisted hash.
		ExamBooklet booklet = new SqliteExamImporter(database, examWriter).importExam(chemistry, "QCAA", 2024,
				"External Assessment", "Paper 1", "Chemistry/QCAA/2024/paper1.pdf",
				ExamBookletQuestionFormat.WRITTEN_RESPONSE);
		QuestionRegion region = new QuestionRegion(booklet, 1, 0.10, 0.10, 0.50, 0.20);
		Question question = new SqliteQuestionWriter(database).insertQuestionWithContent(booklet, "Q1", "", 2,
				List.of(new PdfQuestionContentPart(region)), subtopic, false, null, null,
				QuestionResponseType.WRITTEN_RESPONSE);
		QuestionBookletPdfReplacementService service = new QuestionBookletPdfReplacementService(database, pdfRoot);
		QuestionBookletPdfReplacementService.Result result = service.replace(booklet, externalCopy);

		// Byte-identical selection is recognition/back-fill, not destructive
		// replacement.
		assertFalse(result.contentChanged());
		Question reloaded = new SqliteQuestionRepository(database).findById(question.getId()).orElseThrow();
		assertEquals(1, reloaded.getRegions().size());
		assertFalse(reloaded.getContentParts().isEmpty());
		String expectedHash = new SourceDocumentHashService().sha256(managedPdf);
		ExamBooklet persistedBooklet = examWriter.findExamBookletBySourceDocumentPath("Chemistry/QCAA/2024/paper1.pdf");
		assertEquals(expectedHash, persistedBooklet.getSourceDocument().getContentSha256());
	}

	@Test
	void rejectsReplacementWhenExamIsCompleteWithoutChangingManagedBytes() throws Exception {
		Path pdfRoot = tempDirectory.resolve("complete-pdf-root");
		Path managedPdf = pdfRoot.resolve("Chemistry/QCAA/2025/paper1.pdf");
		Files.createDirectories(managedPdf.getParent());
		Files.writeString(managedPdf, "original complete-exam booklet");
		Path replacementPdf = tempDirectory.resolve("replacement-complete.pdf");
		Files.writeString(replacementPdf, "different replacement booklet");
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("complete-replacement.db"));
		database.initialiseSchema();
		Subject chemistry = new SqliteCurriculumWriter(database).insertSubject("Chemistry");
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		SqliteExamImporter importer = new SqliteExamImporter(database, examWriter);
		SourceDocumentHashService hashService = new SourceDocumentHashService();
		String originalHash = hashService.sha256(managedPdf);
		ExamBooklet booklet = importer.importExam(chemistry, "QCAA", 2025, "External Assessment", "Paper 1",
				"Chemistry/QCAA/2025/paper1.pdf", ExamBookletQuestionFormat.WRITTEN_RESPONSE, originalHash);

		// COMPLETE is persisted independently of the caller's older in-memory booklet
		// object. The replacement service must honour the authoritative database state.
		examWriter.setExamCaptureState(booklet.getExam(), ExamCaptureState.COMPLETE);
		QuestionBookletPdfReplacementService service = new QuestionBookletPdfReplacementService(database, pdfRoot);
		assertThrows(IllegalStateException.class, () -> service.replace(booklet, replacementPdf));

		// Rejection occurs before the managed asset is touched.
		assertEquals("original complete-exam booklet", Files.readString(managedPdf));
		ExamBooklet reloaded = examWriter.findExamBookletBySourceDocumentPath("Chemistry/QCAA/2025/paper1.pdf");
		assertEquals(originalHash, reloaded.getSourceDocument().getContentSha256());
	}

	@Test
	void rejectsReplacementWhoseBytesAlreadyBelongToAnotherManagedDocument() throws Exception {
		Path pdfRoot = tempDirectory.resolve("duplicate-pdf-root");
		Path firstManagedPdf = pdfRoot.resolve("Chemistry/QCAA/2025/paper1.pdf");
		Path secondManagedPdf = pdfRoot.resolve("Chemistry/QCAA/2025/paper2.pdf");
		Files.createDirectories(firstManagedPdf.getParent());
		Files.writeString(firstManagedPdf, "paper one bytes");
		Files.writeString(secondManagedPdf, "paper two bytes");
		Path externalReplacement = tempDirectory.resolve("renamed-paper-two.pdf");
		Files.copy(secondManagedPdf, externalReplacement);
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("duplicate-replacement.db"));
		database.initialiseSchema();
		Subject chemistry = new SqliteCurriculumWriter(database).insertSubject("Chemistry");
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		SqliteExamImporter importer = new SqliteExamImporter(database, examWriter);
		SourceDocumentHashService hashService = new SourceDocumentHashService();
		ExamBooklet paper1 = importer.importExam(chemistry, "QCAA", 2025, "External Assessment", "Paper 1",
				"Chemistry/QCAA/2025/paper1.pdf", ExamBookletQuestionFormat.WRITTEN_RESPONSE,
				hashService.sha256(firstManagedPdf));

		// Register Paper 2 independently so its SHA-256 identity is already a managed
		// SourceDocument before Paper 1 replacement is attempted.
		importer.importExam(chemistry, "QCAA", 2025, "External Assessment", "Paper 2", "Chemistry/QCAA/2025/paper2.pdf",
				ExamBookletQuestionFormat.WRITTEN_RESPONSE, hashService.sha256(secondManagedPdf));
		QuestionBookletPdfReplacementService service = new QuestionBookletPdfReplacementService(database, pdfRoot);
		assertThrows(IllegalArgumentException.class, () -> service.replace(paper1, externalReplacement));

		// Duplicate detection happens before destructive filesystem or database work.
		assertEquals("paper one bytes", Files.readString(firstManagedPdf));
		ExamBooklet reloaded = examWriter.findExamBookletBySourceDocumentPath("Chemistry/QCAA/2025/paper1.pdf");
		assertEquals(hashService.sha256(firstManagedPdf), reloaded.getSourceDocument().getContentSha256());
	}

	@Test
	void replacesQuestionPdfAndInvalidatesOnlyPdfDependentCapture() throws Exception {
		Path pdfRoot = tempDirectory.resolve("pdf");
		Path managedPdf = pdfRoot.resolve("Chemistry/QCAA/2025/paper1.pdf");
		Files.createDirectories(managedPdf.getParent());
		Files.writeString(managedPdf, "old question booklet");
		Path replacementPdf = tempDirectory.resolve("correct-paper1.pdf");
		Files.writeString(replacementPdf, "correct question booklet");
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("questionbank.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SyllabusVersion syllabus = curriculumWriter.insertSyllabusVersion(chemistry, "2025", true);
		Unit unit = curriculumWriter.insertUnit(syllabus, "1", "Unit 1", 1);
		Topic topic = curriculumWriter.insertTopic(unit, "1.1", "Topic 1", 1);
		Subtopic subtopic = curriculumWriter.insertSubtopic(topic, "1.1.1", "Subtopic 1", 1);
		SourceDocumentHashService hashService = new SourceDocumentHashService();
		String originalHash = hashService.sha256(managedPdf);
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		SqliteExamImporter examImporter = new SqliteExamImporter(database, examWriter);
		ExamBooklet booklet = examImporter.importExam(chemistry, "QCAA", 2025, "External Assessment", "Paper 1",
				"Chemistry/QCAA/2025/paper1.pdf", ExamBookletQuestionFormat.MIXED, originalHash);
		SqliteSharedQuestionContextRepository contextRepository = new SqliteSharedQuestionContextRepository(database);
		SharedQuestionContext context = contextRepository.save(booklet, "Questions 1-2 context",
				List.of(new SharedQuestionContextRegion(1, 0.05, 0.05, 0.90, 0.10)));
		QuestionRegion pdfRegion = new QuestionRegion(booklet, 2, 0.10, 0.20, 0.70, 0.20);
		byte[] imageBytes = new byte[] { 1, 2, 3, 4, 5 };
		List<QuestionContentPart> content = List.of(new PdfQuestionContentPart(pdfRegion),
				new ImageQuestionContentPart(imageBytes));
		SqliteQuestionWriter questionWriter = new SqliteQuestionWriter(database);
		Question question = questionWriter.insertQuestionWithContent(booklet, "Q1", "supplementary text", 3, content,
				subtopic, false, null, context, QuestionResponseType.WRITTEN_RESPONSE);
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						UPDATE exam_booklets
						SET pending_mcq_shared_context_id = ?
						WHERE id = ?
						""")) {
			statement.setLong(1, context.getId());
			statement.setLong(2, booklet.getId());
			assertEquals(1, statement.executeUpdate());
		}
		QuestionBookletPdfReplacementService service = new QuestionBookletPdfReplacementService(database, pdfRoot);
		QuestionBookletPdfReplacementService.Impact impact = service.assess(booklet);
		assertEquals(1, impact.affectedQuestionCount());
		assertEquals(1, impact.pdfRegionCount());
		assertEquals(1, impact.sharedContextCount());
		assertEquals(1, impact.preservedImageCount());
		assertTrue(impact.hasSourceDependentCapture());
		QuestionBookletPdfReplacementService.Result result = service.replace(booklet, replacementPdf);
		assertTrue(result.contentChanged());
		assertEquals(impact, result.impact());

		// The managed asset slot remains stable while its bytes and persisted identity
		// are replaced.
		assertEquals("correct question booklet", Files.readString(managedPdf));
		String replacementHash = hashService.sha256(replacementPdf);
		assertEquals(replacementHash, result.booklet().getSourceDocument().getContentSha256());
		assertEquals(booklet.getSourceDocument().getId(), result.booklet().getSourceDocument().getId());
		assertEquals(booklet.getSourceDocument().getRelativePath(),
				result.booklet().getSourceDocument().getRelativePath());
		Question reloaded = new SqliteQuestionRepository(database).findById(question.getId()).orElseThrow();

		// Reloading through the Question repository must retain the v16
		// managed-document identity used by later replacement and duplicate checks.
		assertEquals(replacementHash, reloaded.getBooklet().getSourceDocument().getContentSha256());

		// The surviving image means ordinary content completeness cannot identify this
		// Question as incomplete. Replacement therefore persists an explicit source
		// recapture requirement.
		assertTrue(reloaded.isSourceCaptureRequired());

		// Question identity and independent metadata survive the source correction.
		assertEquals(question.getId(), reloaded.getId());
		assertEquals("Q1", reloaded.getQuestionCode());
		assertEquals("supplementary text", reloaded.getQuestionText());
		assertEquals(3, reloaded.getMarks());
		assertEquals(subtopic.getId(), reloaded.getClassification().getId());
		assertEquals(QuestionResponseType.WRITTEN_RESPONSE, reloaded.getResponseType());

		// Old PDF coordinates are invalid, but the clipboard/image fragment remains
		// authoritative content.
		assertTrue(reloaded.getRegions().isEmpty());
		assertEquals(1, reloaded.getContentParts().size());
		assertTrue(reloaded.getContentParts().getFirst() instanceof ImageQuestionContentPart);
		ImageQuestionContentPart preservedImage = (ImageQuestionContentPart) reloaded.getContentParts().getFirst();
		assertArrayEquals(imageBytes, preservedImage.pngBytes());

		// The old Shared Context object and link are invalidated, while the Question
		// retains an explicit requirement to capture that shared material again.
		assertNull(reloaded.getSharedContext());
		assertTrue(reloaded.isSharedContextCaptureRequired());
		assertTrue(reloaded.isSharedContextUnresolved());
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT pending_mcq_shared_context_id
						FROM exam_booklets
						WHERE id = ?
						""")) {
			statement.setLong(1, booklet.getId());
			try (ResultSet query = statement.executeQuery()) {
				assertTrue(query.next());
				assertNull(query.getObject("pending_mcq_shared_context_id"));
			}
		}
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT COUNT(*)
						FROM shared_question_contexts
						WHERE booklet_id = ?
						""")) {
			statement.setLong(1, booklet.getId());
			try (ResultSet query = statement.executeQuery()) {
				assertTrue(query.next());
				assertEquals(0, query.getInt(1));
			}
		}
		QuestionRegion replacementRegion = new QuestionRegion(result.booklet(), 1, 0.15, 0.15, 0.60, 0.20);
		List<QuestionContentPart> recapturedContent = List.of(reloaded.getContentParts().getFirst(),
				new PdfQuestionContentPart(replacementRegion));
		SqliteQuestionCaptureService.PendingSharedContext recapturedContext = new SqliteQuestionCaptureService.PendingSharedContext(
				"Replacement context", List.of(new SharedQuestionContextRegion(1, 0.05, 0.05, 0.90, 0.10)));
		Question recaptured = new SqliteQuestionCaptureService(database)
				.save(SqliteQuestionCaptureService.Request.withContent(SqliteQuestionCaptureService.Operation.IMPORTED,
						result.booklet(), reloaded, reloaded.getQuestionCode(), reloaded.getMarks(), recapturedContent,
						reloaded.getClassification(), reloaded.getResponseType(), null, recapturedContext, false));

		// Successful replacement-source capture clears only the Question-source
		// marker and retains both preserved image content and newly captured PDF
		// content.
		assertFalse(recaptured.isSourceCaptureRequired());
		assertEquals(2, recaptured.getContentParts().size());
		assertTrue(recaptured.getContentParts().getFirst() instanceof ImageQuestionContentPart);
		assertEquals(1, recaptured.getRegions().size());
		Question finalReload = new SqliteQuestionRepository(database).findById(question.getId()).orElseThrow();
		assertFalse(finalReload.isSourceCaptureRequired());
		assertEquals(2, finalReload.getContentParts().size());
		assertEquals(1, finalReload.getRegions().size());
		assertTrue(finalReload.hasSharedContext());
		ExamBooklet persistedBooklet = examWriter.findExamBookletBySourceDocumentPath("Chemistry/QCAA/2025/paper1.pdf");
		assertEquals(replacementHash, persistedBooklet.getSourceDocument().getContentSha256());
	}
}
