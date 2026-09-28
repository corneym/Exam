package au.edu.eq.questionbank.repository.assessment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.AnswerFile;
import au.edu.eq.questionbank.model.ExamAssetExpectations;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamBookletQuestionFormat;
import au.edu.eq.questionbank.model.ExamCaptureState;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class SqliteExamImporterTest {

	@TempDir
	Path tempDirectory;

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
		writer.setExamCaptureState(firstBooklet.getExam(), ExamCaptureState.COMPLETE);

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
