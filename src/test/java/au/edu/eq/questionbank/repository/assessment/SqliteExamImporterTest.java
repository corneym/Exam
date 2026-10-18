package au.edu.eq.questionbank.repository.assessment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.AnswerFile;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamAssetExpectations;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamBookletQuestionFormat;
import au.edu.eq.questionbank.model.ExamCaptureState;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class SqliteExamImporterTest {

	@TempDir
	Path tempDirectory;

	@Test
	void atomicallyCreatesBookletWithLegacyPaperIdentity() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("atomic-legacy-booklet.db"));
		database.initialiseSchema();
		Subject chemistry = new SqliteCurriculumWriter(database).insertSubject("Chemistry");
		SqliteExamWriter writer = new SqliteExamWriter(database);
		SqliteExamImporter importer = new SqliteExamImporter(database, writer);
		String hash = "0123456789abcdef".repeat(4);
		ExamBooklet mcq = importer.importExam(chemistry, "QCAA", 2020, "Sample Examination", "MCQ booklet",
				"Chemistry/QCAA/2020/sample-mcq.pdf", ExamBookletQuestionFormat.MULTIPLE_CHOICE, 20, hash, "MCQ");
		assertEquals("MCQ", writer.findLegacyPaperCodes(mcq.getExam()).get(mcq.getId()));

		// Renaming the saved booklet cannot change its Paper identity.
		writer.updateExamBookletMetadata(mcq, "Renamed Multiple Choice", ExamBookletQuestionFormat.MULTIPLE_CHOICE, 20);
		assertEquals("MCQ", writer.findLegacyPaperCodes(mcq.getExam()).get(mcq.getId()));
		String duplicatePath = "Chemistry/QCAA/2020/sample-other-mcq.pdf";

		// The second booklet conflicts with the persisted MCQ link.
		// Neither its new SourceDocument nor its booklet may commit.
		assertThrows(IllegalArgumentException.class,
				() -> importer.importExam(chemistry, "QCAA", 2020, "Sample Examination", "Other MCQ booklet",
						duplicatePath, ExamBookletQuestionFormat.MULTIPLE_CHOICE, 20, "b".repeat(64), "MCQ"));
		assertEquals(1, writer.findExamBooklets(mcq.getExam()).size());
		assertNull(writer.findExamBookletBySourceDocumentPath(duplicatePath));

		// A distinct Exam may have its own MCQ Paper.
		ExamBooklet mock = importer.importExam(chemistry, "QCAA", 2020, "Mock Examination", "MCQ booklet",
				"Chemistry/QCAA/2020/mock-mcq.pdf", ExamBookletQuestionFormat.MULTIPLE_CHOICE, 18, "c".repeat(64),
				"MCQ");
		assertEquals("MCQ", writer.findLegacyPaperCodes(mock.getExam()).get(mock.getId()));

		// Ordinary NEAP creation has no legacy Paper identity.
		ExamBooklet ordinary = importer.importExam(chemistry, "NEAP", 2025, "Topic Test 1", "Custom booklet",
				"Chemistry/NEAP/2025/topic-test.pdf", ExamBookletQuestionFormat.MIXED, 12, "d".repeat(64), null);
		assertEquals(0, writer.findLegacyPaperCodes(ordinary.getExam()).size());
	}

	@Test
	void completeExamRejectsImportOfAnotherBooklet() throws Exception {
		Path databasePath = tempDirectory.resolve("complete-exam-booklet-import.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		Subject chemistry = new SqliteCurriculumWriter(database).insertSubject("Chemistry");
		SqliteExamWriter writer = new SqliteExamWriter(database);
		SqliteExamImporter importer = new SqliteExamImporter(database, writer);
		ExamBooklet firstBooklet = importer.importExam(chemistry, "QCAA", 2025, "External Assessment", "Paper 1",
				"Chemistry/QCAA/2025/paper1.pdf", ExamBookletQuestionFormat.MIXED);

		// Completion records the user's decision that the Exam's structure is now
		// authoritative.
		// Recreate a historical COMPLETE Exam so this test can verify
		// that importing another booklet remains structurally prohibited.
		HistoricalCompleteExamFixture.markComplete(database, writer, firstBooklet.getExam());

		// A second booklet would change Exam structure and must therefore require
		// explicit reactivation first.
		assertThrows(IllegalStateException.class,
				() -> importer.importExam(chemistry, "QCAA", 2025, "External Assessment", "Paper 2",
						"Chemistry/QCAA/2025/paper2.pdf", ExamBookletQuestionFormat.WRITTEN_RESPONSE));

		// The failed transactional import must not leave either a booklet or its
		// proposed SourceDocument behind.
		assertEquals(1, writer.findAllExamBooklets().size());
		assertNull(writer.findExamBookletBySourceDocumentPath("Chemistry/QCAA/2025/paper2.pdf"));
	}

	@Test
	void createsNewBookletWithExistingAnswerAndLegacyPaperInOneTransaction() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("booklet-answer-and-paper.db"));
		database.initialiseSchema();
		Subject chemistry = new SqliteCurriculumWriter(database).insertSubject("Chemistry");
		SqliteExamWriter writer = new SqliteExamWriter(database);
		SqliteExamImporter importer = new SqliteExamImporter(database, writer);
		Exam exam = writer.createExam(chemistry, "QCAA", 2020, "External assessment");
		SqliteAnswerWriter answers = new SqliteAnswerWriter(database, writer);
		AnswerFile markingGuide = answers.findOrCreateAnswerFile(exam, "Marking Guide",
				"Chemistry/QCAA/2020/marking-guide.pdf");
		ExamBooklet created = importer.importExam(chemistry, "QCAA", 2020, "External assessment", "Paper 1",
				"Chemistry/QCAA/2020/paper-1.pdf", ExamBookletQuestionFormat.WRITTEN_RESPONSE, 7, "a".repeat(64), "1",
				markingGuide);
		ExamBooklet reloaded = writer.findExamBooklets(exam).getFirst();
		assertEquals(created.getId(), reloaded.getId());
		assertEquals(Integer.valueOf(7), reloaded.getExpectedQuestionCount());
		assertEquals("1", writer.findLegacyPaperCodes(exam).get(created.getId()));
		assertEquals(markingGuide.getId(), answers.findAnswerFile(reloaded).getId());
		assertEquals("a".repeat(64), reloaded.getSourceDocument().getContentSha256());
		assertEquals(1, writer.findExamBooklets(exam).size());
	}

	@Test
	void freshQuestionBookletImportPersistsPlanningMetadata() throws Exception {
		Path databasePath = tempDirectory.resolve("planned-question-booklet.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		Subject chemistry = new SqliteCurriculumWriter(database).insertSubject("Chemistry");
		SqliteExamWriter writer = new SqliteExamWriter(database);
		SqliteExamImporter importer = new SqliteExamImporter(database, writer);
		String hash = "0123456789abcdef".repeat(4);

		// Fresh Exam/Assets intake supplies the known structural planning metadata
		// together with the managed source identity.
		ExamBooklet imported = importer.importExam(chemistry, "QCAA", 2025, "External Assessment", "Paper 2",
				"Chemistry/QCAA/2025/paper2.pdf", ExamBookletQuestionFormat.WRITTEN_RESPONSE, Integer.valueOf(12),
				hash);
		assertEquals(ExamBookletQuestionFormat.WRITTEN_RESPONSE, imported.getQuestionFormat());
		assertEquals(Integer.valueOf(12), imported.getExpectedQuestionCount());
		assertEquals(hash, imported.getSourceDocument().getContentSha256());
		ExamBooklet reloaded = writer.findExamBookletBySourceDocumentPath("Chemistry/QCAA/2025/paper2.pdf");

		// Independent reconstruction proves the values were committed to SQLite rather
		// than existing only on the object returned by the importer.
		assertEquals(ExamBookletQuestionFormat.WRITTEN_RESPONSE, reloaded.getQuestionFormat());
		assertEquals(Integer.valueOf(12), reloaded.getExpectedQuestionCount());
		assertEquals(hash, reloaded.getSourceDocument().getContentSha256());
	}

	@Test
	void freshQuestionBookletImportRejectsThreeDigitExpectedCount() throws Exception {
		Path databasePath = tempDirectory.resolve("invalid-planned-question-booklet.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		Subject chemistry = new SqliteCurriculumWriter(database).insertSubject("Chemistry");
		SqliteExamWriter writer = new SqliteExamWriter(database);
		SqliteExamImporter importer = new SqliteExamImporter(database, writer);

		// Expected Question counts are deliberately constrained to the agreed
		// one- or two-digit range before any persistence transaction begins.
		assertThrows(IllegalArgumentException.class,
				() -> importer.importExam(chemistry, "QCAA", 2025, "External Assessment", "Paper 2",
						"Chemistry/QCAA/2025/paper2.pdf", ExamBookletQuestionFormat.WRITTEN_RESPONSE,
						Integer.valueOf(100), null));
		assertEquals(0, writer.findAllExamBooklets().size());
	}

	@Test
	void importsAndReusesExplicitBookletQuestionFormat() throws Exception {
		Path databasePath = tempDirectory.resolve("booklet-format.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		Subject chemistry = new SqliteCurriculumWriter(database).insertSubject("Chemistry");
		SqliteExamWriter writer = new SqliteExamWriter(database);
		SqliteExamImporter importer = new SqliteExamImporter(database, writer);

		// New imports must persist the format explicitly selected for this booklet.
		ExamBooklet first = importer.importExam(chemistry, "QCAA", 2025, "External Assessment", "Paper 1",
				"Chemistry/QCAA/2025/paper1.pdf", ExamBookletQuestionFormat.MIXED);
		assertEquals(ExamBookletQuestionFormat.MIXED, first.getQuestionFormat());

		// Reopening the same persisted booklet must recover its stored format rather
		// than replacing it with UNSPECIFIED or the caller's current selection.
		ExamBooklet second = importer.importExam(chemistry, "QCAA", 2025, "External Assessment", "Paper 1",
				"Chemistry/QCAA/2025/paper1.pdf", ExamBookletQuestionFormat.WRITTEN_RESPONSE);
		assertEquals(first.getId(), second.getId());
		assertEquals(ExamBookletQuestionFormat.MIXED, second.getQuestionFormat());
	}

	@Test
	void legacyStyleImportUsesOrdinaryActiveExamAssetModel() throws Exception {
		Path databasePath = tempDirectory.resolve("legacy-hashed-source.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		Subject chemistry = new SqliteCurriculumWriter(database).insertSubject("Chemistry");
		SqliteExamWriter writer = new SqliteExamWriter(database);
		SqliteExamImporter importer = new SqliteExamImporter(database, writer);
		String questionHash = "abcdef0123456789".repeat(4);

		// This is the import overload used when legacy intake knows the managed
		// Question PDF hash but has not established authoritative booklet planning.
		ExamBooklet booklet = importer.importExam(chemistry, "QCAA", 2020, "External Assessment", "Paper 1",
				"Chemistry/QCAA/2020/paper1.pdf", questionHash);

		// Legacy intake creates the same ordinary ExamBooklet model as fresh intake.
		// Unknown planning metadata remains unknown rather than being inferred.
		assertEquals(questionHash, booklet.getSourceDocument().getContentSha256());
		assertEquals(ExamBookletQuestionFormat.UNSPECIFIED, booklet.getQuestionFormat());
		assertNull(booklet.getExpectedQuestionCount());
		assertEquals(ExamCaptureState.ACTIVE, booklet.getExam().getCaptureState());
		SqliteAnswerWriter answerWriter = new SqliteAnswerWriter(database, writer);
		String answerHash = "0123456789abcdef".repeat(4);

		// A legacy marking guide enters the same AnswerFile model used by normal
		// Answer capture and later Exam asset review.
		AnswerFile answerFile = answerWriter.findOrCreateAnswerFile(booklet.getExam(), "Marking guide",
				"Chemistry/QCAA/2020/marking-guide.pdf", answerHash);
		assertEquals(answerHash, answerFile.getSourceDocument().getContentSha256());

		// Ordinary Exam queries must see both legacy-created assets. There is no
		// separate legacy-only asset hierarchy.
		List<ExamBooklet> booklets = writer.findAllExamBooklets();
		List<AnswerFile> answerFiles = answerWriter.findAnswerFiles(booklet.getExam());
		assertEquals(1, booklets.size());
		assertEquals(booklet.getId(), booklets.getFirst().getId());
		assertEquals(1, answerFiles.size());
		assertEquals(answerFile.getId(), answerFiles.getFirst().getId());
		ExamAssetExpectations expectations = writer.findExamAssetExpectations(booklet.getExam());

		// Available counts come from the same persisted assets, while legacy intake
		// deliberately leaves authoritative expected counts unset for later review.
		assertNull(expectations.expectedQuestionBookletCount());
		assertEquals(1, expectations.availableQuestionBookletCount());
		assertNull(expectations.expectedAnswerFileCount());
		assertEquals(1, expectations.availableAnswerFileCount());
	}

	@Test
	void persistsHashForNewSourceDocument() throws Exception {
		Path databasePath = tempDirectory.resolve("hashed-source-import.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		Subject chemistry = new SqliteCurriculumWriter(database).insertSubject("Chemistry");
		SqliteExamWriter writer = new SqliteExamWriter(database);
		SqliteExamImporter importer = new SqliteExamImporter(database, writer);
		String hash = "0123456789abcdef".repeat(4);
		ExamBooklet imported = importer.importExam(chemistry, "QCAA", 2025, "External Assessment", "Paper 1",
				"Chemistry/QCAA/2025/paper1.pdf", ExamBookletQuestionFormat.MIXED, hash);

		// The returned graph must carry the content identity that was persisted with
		// the new SourceDocument.
		assertEquals(hash, imported.getSourceDocument().getContentSha256());
		ExamBooklet reloaded = writer.findExamBookletBySourceDocumentPath("Chemistry/QCAA/2025/paper1.pdf");

		// Reloading independently proves the hash was stored rather than existing only
		// on the object returned by the importer.
		assertEquals(hash, reloaded.getSourceDocument().getContentSha256());
	}

	@Test
	void persistsHashWhenImportingNewSourceDocument() throws Exception {
		Path databasePath = tempDirectory.resolve("hashed-source-import.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		Subject chemistry = new SqliteCurriculumWriter(database).insertSubject("Chemistry");
		SqliteExamWriter writer = new SqliteExamWriter(database);
		SqliteExamImporter importer = new SqliteExamImporter(database, writer);
		String hash = "0123456789abcdef".repeat(4);
		ExamBooklet imported = importer.importExam(chemistry, "QCAA", 2025, "External Assessment", "Paper 1",
				"Chemistry/QCAA/2025/paper1.pdf", ExamBookletQuestionFormat.MIXED, hash);

		// The returned graph must already contain the persisted byte identity.
		assertEquals(hash, imported.getSourceDocument().getContentSha256());
		ExamBooklet reloaded = writer.findExamBookletBySourceDocumentPath("Chemistry/QCAA/2025/paper1.pdf");

		// A fresh reconstruction from SQLite proves the hash was actually persisted.
		assertEquals(hash, reloaded.getSourceDocument().getContentSha256());
	}

	@Test
	void persistsPlanningMetadataDuringFreshBookletImport() throws Exception {
		Path databasePath = tempDirectory.resolve("planned-booklet-import.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		Subject chemistry = new SqliteCurriculumWriter(database).insertSubject("Chemistry");
		SqliteExamWriter writer = new SqliteExamWriter(database);
		SqliteExamImporter importer = new SqliteExamImporter(database, writer);
		String hash = "0123456789abcdef".repeat(4);

		// Fresh Exam/Assets intake must persist all known booklet planning metadata in
		// the same import operation.
		ExamBooklet imported = importer.importExam(chemistry, "QCAA", 2025, "External Assessment", "Paper 2",
				"Chemistry/QCAA/2025/paper2.pdf", ExamBookletQuestionFormat.WRITTEN_RESPONSE, 12, hash);
		assertEquals(ExamBookletQuestionFormat.WRITTEN_RESPONSE, imported.getQuestionFormat());
		assertEquals(Integer.valueOf(12), imported.getExpectedQuestionCount());
		assertEquals(hash, imported.getSourceDocument().getContentSha256());
		ExamBooklet reloaded = writer.findExamBookletBySourceDocumentPath("Chemistry/QCAA/2025/paper2.pdf");

		// A fresh reconstruction proves the planning values were persisted rather than
		// existing only on the returned domain object.
		assertEquals(ExamBookletQuestionFormat.WRITTEN_RESPONSE, reloaded.getQuestionFormat());
		assertEquals(Integer.valueOf(12), reloaded.getExpectedQuestionCount());
		assertEquals(hash, reloaded.getSourceDocument().getContentSha256());
	}

	@Test
	void rejectsAnswerFromAnotherExamWithoutCreatingBooklet() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("foreign-answer-assignment.db"));
		database.initialiseSchema();
		Subject chemistry = new SqliteCurriculumWriter(database).insertSubject("Chemistry");
		SqliteExamWriter writer = new SqliteExamWriter(database);
		SqliteExamImporter importer = new SqliteExamImporter(database, writer);
		Exam external = writer.createExam(chemistry, "QCAA", 2020, "External assessment");
		Exam mock = writer.createExam(chemistry, "QCAA", 2020, "Mock Examination");
		AnswerFile mockAnswers = new SqliteAnswerWriter(database, writer).findOrCreateAnswerFile(mock,
				"Mock Marking Guide", "Chemistry/QCAA/2020/mock-answers.pdf");
		String proposedPath = "Chemistry/QCAA/2020/external-paper.pdf";
		assertThrows(IllegalArgumentException.class,
				() -> importer.importExam(chemistry, "QCAA", 2020, "External assessment", "Paper 1", proposedPath,
						ExamBookletQuestionFormat.WRITTEN_RESPONSE, 7, "c".repeat(64), "1", mockAnswers));
		assertEquals(0, writer.findExamBooklets(external).size());
		assertEquals(0, writer.findLegacyPaperCodes(external).size());
		assertNull(writer.findExamBookletBySourceDocumentPath(proposedPath));
	}

	@Test
	void rejectsUnpersistedAnswerFileAndRollsBackBookletAndPaper() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("stale-answer-assignment.db"));
		database.initialiseSchema();
		Subject chemistry = new SqliteCurriculumWriter(database).insertSubject("Chemistry");
		SqliteExamWriter writer = new SqliteExamWriter(database);
		SqliteExamImporter importer = new SqliteExamImporter(database, writer);
		Exam exam = writer.createExam(chemistry, "QCAA", 2020, "External assessment");
		AnswerFile stale = new AnswerFile(999999L, exam, "Missing answer booklet",
				new SourceDocument(999999L, "Chemistry/QCAA/2020/missing-answer.pdf"));
		String proposedPath = "Chemistry/QCAA/2020/uncommitted-paper.pdf";
		assertThrows(IllegalArgumentException.class,
				() -> importer.importExam(chemistry, "QCAA", 2020, "External assessment", "Paper 1", proposedPath,
						ExamBookletQuestionFormat.WRITTEN_RESPONSE, 7, "b".repeat(64), "1", stale));
		assertEquals(0, writer.findExamBooklets(exam).size());
		assertEquals(0, writer.findLegacyPaperCodes(exam).size());
		assertNull(writer.findExamBookletBySourceDocumentPath(proposedPath));
	}

	@Test
	void reusesExistingExamMetadata() throws Exception {
		Path databasePath = tempDirectory.resolve("questionbank.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SqliteExamWriter writer = new SqliteExamWriter(database);
		SqliteExamImporter importer = new SqliteExamImporter(database, writer);
		ExamBooklet first = importer.importExam(chemistry, "QCAA", 2019, "External Assessment", "Paper 1",
				"Chemistry/2019/paper1.pdf");
		ExamBooklet second = importer.importExam(chemistry, "QCAA", 2019, "External Assessment", "Paper 1",
				"Chemistry/2019/paper1.pdf");
		assertEquals(first.getId(), second.getId());
		assertEquals(first.getExam().getId(), second.getExam().getId());
		assertEquals(first.getExam().getProvider().getId(), second.getExam().getProvider().getId());
		assertEquals(first.getSourceDocument().getId(), second.getSourceDocument().getId());
	}
}
