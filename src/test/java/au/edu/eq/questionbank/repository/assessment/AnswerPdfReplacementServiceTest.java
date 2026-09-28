package au.edu.eq.questionbank.repository.assessment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.AnswerFile;
import au.edu.eq.questionbank.model.AnswerRegion;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.document.SourceDocumentHashService;

class AnswerPdfReplacementServiceTest {

	@TempDir
	Path tempDirectory;

	@Test
	void identicalReplacementBackfillsMissingHashWithoutInvalidatingAnswerRegions() throws Exception {
		Path pdfRoot = tempDirectory.resolve("backfill-pdf");
		Path managedAnswer = pdfRoot.resolve("Chemistry/QCAA/2025/answers.pdf");
		Files.createDirectories(managedAnswer.getParent());
		Files.writeString(managedAnswer, "unchanged answer document");
		Path externalCopy = tempDirectory.resolve("same-answer-document.pdf");
		Files.copy(managedAnswer, externalCopy);
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("backfill-answer-hash.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SyllabusVersion syllabus = curriculumWriter.insertSyllabusVersion(chemistry, "2025", true);
		Unit unit = curriculumWriter.insertUnit(syllabus, "1", "Unit 1", 1);
		Topic topic = curriculumWriter.insertTopic(unit, "1.1", "Topic 1", 1);
		Subtopic subtopic = curriculumWriter.insertSubtopic(topic, "1.1.1", "Subtopic 1", 1);
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		ExamBooklet booklet = new SqliteExamImporter(database, examWriter).importExam(chemistry, "QCAA", 2025,
				"External Assessment", "Paper 1", "Chemistry/QCAA/2025/questions.pdf");
		SqliteQuestionRepository questionRepository = new SqliteQuestionRepository(database);
		Question question = questionRepository.save(booklet, "Q1", "", 2,
				List.of(new QuestionRegion(booklet, 1, 0.10, 0.10, 0.60, 0.20)), subtopic, false, null, null,
				QuestionResponseType.WRITTEN_RESPONSE);
		SqliteAnswerWriter answerWriter = new SqliteAnswerWriter(database, examWriter);

		// Register through the legacy-compatible overload so content_sha256 remains
		// NULL
		// until replacement inspects the actual managed bytes.
		AnswerFile legacyAnswerFile = answerWriter.findOrCreateAnswerFile(booklet, "answers.pdf",
				"Chemistry/QCAA/2025/answers.pdf");
		assertNull(legacyAnswerFile.getSourceDocument().getContentSha256());
		answerWriter.insertAnswer(question, null,
				List.of(new AnswerRegion(legacyAnswerFile, 2, 0.10, 0.20, 0.50, 0.12)));
		AnswerPdfReplacementService service = new AnswerPdfReplacementService(database, pdfRoot);
		AnswerPdfReplacementService.Result result = service.replace(booklet, externalCopy);

		// Byte-identical selection is recognition only. No dependent coordinates are
		// invalidated merely to populate previously unknown content identity.
		assertFalse(result.changed());
		assertEquals(legacyAnswerFile.getId(), result.answerFile().getId());
		Question reloaded = questionRepository.findById(question.getId()).orElseThrow();
		assertTrue(reloaded.hasAnswer());
		assertEquals(1, reloaded.getAnswer().getRegions().size());
		assertEquals(2, reloaded.getAnswer().getRegions().getFirst().pageNumber());
		String expectedHash = new SourceDocumentHashService().sha256(managedAnswer);
		AnswerFile persisted = answerWriter.findAnswerFile(booklet);
		assertEquals(expectedHash, persisted.getSourceDocument().getContentSha256());
	}

	@Test
	void replacementReusesUnambiguousMatchingAnswerFileAlreadyManagedForExam() throws Exception {
		Path pdfRoot = tempDirectory.resolve("reuse-pdf");
		Path managedDirectory = pdfRoot.resolve("Chemistry/QCAA/2025");
		Files.createDirectories(managedDirectory);
		Path oldManaged = managedDirectory.resolve("old-answers.pdf");
		Path knownCorrectManaged = managedDirectory.resolve("known-correct-answers.pdf");
		Files.writeString(oldManaged, "wrong answer document");
		Files.writeString(knownCorrectManaged, "already managed correct document");
		Path externalDirectory = Files.createDirectories(tempDirectory.resolve("reuse-external"));
		Path externalCopy = externalDirectory.resolve("renamed-copy.pdf");
		Files.copy(knownCorrectManaged, externalCopy);
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("reuse-existing.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		ExamBooklet booklet = new SqliteExamImporter(database, examWriter).importExam(chemistry, "QCAA", 2025,
				"External Assessment", "Paper 1", "Chemistry/QCAA/2025/questions.pdf");
		SourceDocumentHashService hashService = new SourceDocumentHashService();
		SqliteAnswerWriter answerWriter = new SqliteAnswerWriter(database, examWriter);
		AnswerFile oldFile = answerWriter.findOrCreateAnswerFile(booklet, "Old answers",
				"Chemistry/QCAA/2025/old-answers.pdf", hashService.sha256(oldManaged));
		AnswerFile knownCorrectFile = answerWriter.findOrCreateAnswerFile(booklet.getExam(), "Known correct answers",
				"Chemistry/QCAA/2025/known-correct-answers.pdf", hashService.sha256(knownCorrectManaged));
		assertEquals(2, answerWriter.findAnswerFiles(booklet.getExam()).size());
		AnswerPdfReplacementService service = new AnswerPdfReplacementService(database, pdfRoot);
		AnswerPdfReplacementService.Result result = service.replace(booklet, externalCopy);
		assertTrue(result.changed());

		// Content identity resolves the external copy to the already-managed AnswerFile
		// rather than manufacturing another SourceDocument or AnswerFile.
		assertEquals(knownCorrectFile.getId(), result.answerFile().getId());
		assertEquals(knownCorrectManaged.toAbsolutePath().normalize(),
				result.managedPath().toAbsolutePath().normalize());
		AnswerFile assigned = answerWriter.findAnswerFile(booklet);
		assertEquals(knownCorrectFile.getId(), assigned.getId());

		// The old AnswerFile remains a valid managed Exam asset and the matching
		// replacement does not create a third AnswerFile.
		assertEquals(2, answerWriter.findAnswerFiles(booklet.getExam()).size());
		assertTrue(Files.isRegularFile(oldManaged));
		assertEquals(oldFile.getId(), answerWriter.findAnswerFiles(booklet.getExam()).stream()
				.filter(file -> "Old answers".equals(file.getName())).findFirst().orElseThrow().getId());
	}

	@Test
	void sameFilenameReplacementCreatesSeparateManagedAssetAndReturnsWrittenQuestionToCapture() throws Exception {
		Path pdfRoot = tempDirectory.resolve("pdf");
		Path oldManaged = pdfRoot.resolve("Chemistry/QCAA/2025/answers.pdf");
		Files.createDirectories(oldManaged.getParent());
		Files.writeString(oldManaged, "wrong marking guide");
		Path externalDirectory = Files.createDirectories(tempDirectory.resolve("external"));
		Path replacement = externalDirectory.resolve("answers.pdf");
		Files.writeString(replacement, "correct marking guide");
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("replacement.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SyllabusVersion syllabus = curriculumWriter.insertSyllabusVersion(chemistry, "2025", true);
		Unit unit = curriculumWriter.insertUnit(syllabus, "1", "Unit 1", 1);
		Topic topic = curriculumWriter.insertTopic(unit, "1.1", "Topic 1", 1);
		Subtopic subtopic = curriculumWriter.insertSubtopic(topic, "1.1.1", "Subtopic 1", 1);
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		ExamBooklet booklet = new SqliteExamImporter(database, examWriter).importExam(chemistry, "QCAA", 2025,
				"External Assessment", "Paper 1", "Chemistry/QCAA/2025/questions.pdf");
		SqliteQuestionRepository questionRepository = new SqliteQuestionRepository(database);
		Question question = questionRepository.save(booklet, "Q1", "", 3,
				List.of(new QuestionRegion(booklet, 1, 0.10, 0.10, 0.60, 0.20)), subtopic, false, null, null,
				QuestionResponseType.WRITTEN_RESPONSE);
		SourceDocumentHashService hashService = new SourceDocumentHashService();
		SqliteAnswerWriter answerWriter = new SqliteAnswerWriter(database, examWriter);
		AnswerFile oldFile = answerWriter.findOrCreateAnswerFile(booklet, "answers.pdf",
				"Chemistry/QCAA/2025/answers.pdf", hashService.sha256(oldManaged));
		answerWriter.insertAnswer(question, null, List.of(new AnswerRegion(oldFile, 2, 0.10, 0.20, 0.50, 0.12)));
		AnswerPdfReplacementService service = new AnswerPdfReplacementService(database, pdfRoot);
		AnswerPdfReplacementService.Result result = service.replace(booklet, replacement);
		assertTrue(result.changed());
		assertNotEquals(oldFile.getId(), result.answerFile().getId());

		// The incorrect managed asset is retained because another booklet could still
		// legitimately reference it.
		assertEquals("wrong marking guide", Files.readString(oldManaged));
		assertTrue(Files.isRegularFile(result.managedPath()));
		assertNotEquals(oldManaged, result.managedPath());
		assertEquals("correct marking guide", Files.readString(result.managedPath()));
		String expectedHash = hashService.sha256(replacement);
		assertEquals(expectedHash, result.answerFile().getSourceDocument().getContentSha256());
		Question reloaded = questionRepository.findById(question.getId()).orElseThrow();

		// Removing the region-only Answer returns the written Question to normal Answer
		// capture rather than leaving an invalid empty Answer row.
		assertFalse(reloaded.hasAnswer());
	}
}
