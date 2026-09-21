package au.edu.eq.questionbank.repository.assessment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.AnswerFile;
import au.edu.eq.questionbank.model.AnswerRegion;
import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.Descriptor;
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

class SqliteQuestionRepositoryTest {

	@TempDir
	Path tempDirectory;

	@Test
	void attachesRegionsToImportedQuestion() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("attach-imported-regions.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SyllabusVersion syllabus = curriculumWriter.insertSyllabusVersion(chemistry, "2019", false);
		Unit unit = curriculumWriter.insertUnit(syllabus, "1", "Unit 1", 1);
		Topic topic = curriculumWriter.insertTopic(unit, "1.1", "Topic 1", 1);
		Subtopic subtopic = curriculumWriter.insertSubtopic(topic, "1.1.1", "Subtopic 1", 1);
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		ExamBooklet booklet = new SqliteExamImporter(database, examWriter).importExam(chemistry, "QCAA", 2019,
				"External Assessment", "Paper 1", "Chemistry/2019/paper1.pdf");
		SqliteQuestionRepository repository = new SqliteQuestionRepository(database);
		Question imported = repository.save(booklet, "21a", "", 3, List.of(), subtopic, true);
		List<QuestionRegion> regions = List.of(new QuestionRegion(booklet, 4, 0.10, 0.20, 0.60, 0.15),
				new QuestionRegion(booklet, 4, 0.10, 0.40, 0.60, 0.20));
		Question updated = repository.attachRegions(imported.getId(), regions);
		assertEquals(imported.getId(), updated.getId());
		assertEquals("21a", updated.getQuestionCode());
		assertEquals(3, updated.getMarks());
		assertTrue(updated.isPreambleCaptureRequired());
		assertEquals(2, updated.getRegions().size());
		assertEquals(4, updated.getRegions().get(0).pageNumber());
		Question reloaded = new SqliteQuestionRepository(database).findById(imported.getId()).orElseThrow();
		assertEquals(2, reloaded.getRegions().size());
		assertTrue(reloaded.isPreambleCaptureRequired());
		assertThrows(IllegalArgumentException.class, () -> repository.attachRegions(imported.getId(),
				List.of(new QuestionRegion(booklet, 5, 0.10, 0.10, 0.50, 0.20))));
		Question afterRejectedSecondAttachment = new SqliteQuestionRepository(database).findById(imported.getId())
				.orElseThrow();
		assertEquals(2, afterRejectedSecondAttachment.getRegions().size());
	}

	@Test
	void failedEditRestoresMetadataAndOldRegionsAfterALaterInsertFails() throws Exception {
		ReconstructionFixture fixture = createReconstructionFixture("edit-rollback.db");
		Question original = fixture.question();
		try (Connection connection = fixture.database().openConnection();
				Statement statement = connection.createStatement()) {
			statement.execute("""
					CREATE TRIGGER reject_later_edit_region
					BEFORE INSERT ON question_regions
					WHEN NEW.page_number = 9
					BEGIN
					    SELECT RAISE(ABORT, 'reject later edit region');
					END
					""");
		}
		SqliteQuestionRepository repository = new SqliteQuestionRepository(fixture.database());
		List<QuestionRegion> replacements = List.of(new QuestionRegion(original.getBooklet(), 8, 0.2, 0.2, 0.4, 0.3),
				new QuestionRegion(original.getBooklet(), 9, 0.2, 0.2, 0.4, 0.3));
		assertThrows(IllegalStateException.class, () -> repository.updateQuestion(original.getId(), "Changed", 9,
				replacements, original.getClassification(), null, null));
		Question reloaded = new SqliteQuestionRepository(fixture.database()).findById(original.getId()).orElseThrow();
		assertEquals(original.getQuestionCode(), reloaded.getQuestionCode());
		assertEquals(original.getMarks(), reloaded.getMarks());
		assertEquals(original.getClassification().getId(), reloaded.getClassification().getId());
		assertEquals(original.getRegions().size(), reloaded.getRegions().size());
		for (int i = 0; i < original.getRegions().size(); i++) {
			QuestionRegion expected = original.getRegions().get(i);
			QuestionRegion actual = reloaded.getRegions().get(i);
			assertEquals(expected.pageNumber(), actual.pageNumber());
			assertEquals(expected.x(), actual.x());
			assertEquals(expected.y(), actual.y());
			assertEquals(expected.width(), actual.width());
			assertEquals(expected.height(), actual.height());
		}
	}

	@Test
	void rejectsAttachingRegionsToQuestionThatAlreadyHasRegions() throws Exception {
		ReconstructionFixture fixture = createReconstructionFixture("already-captured-question.db");
		Question question = fixture.question();
		assertThrows(IllegalArgumentException.class,
				() -> new SqliteQuestionRepository(fixture.database()).attachRegions(question.getId(),
						List.of(new QuestionRegion(question.getBooklet(), 2, 0.10, 0.10, 0.50, 0.20))));
	}

	@Test
	void rejectsMultiMarkEditWithoutCorruptingPersistedMultipleChoiceQuestion() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("response-type.db"));
		database.initialiseSchema();

		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SyllabusVersion syllabus = curriculumWriter.insertSyllabusVersion(chemistry, "2025", true);
		Unit unit = curriculumWriter.insertUnit(syllabus, "1", "Unit 1", 1);
		Topic topic = curriculumWriter.insertTopic(unit, "1.1", "Topic 1", 1);
		Subtopic subtopic = curriculumWriter.insertSubtopic(topic, "1.1.1", "Subtopic 1", 1);

		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		ExamBooklet booklet = new SqliteExamImporter(database, examWriter).importExam(chemistry, "QCAA", 2025,
				"External Assessment", "Mixed booklet", "Chemistry/2025/questions.pdf");

		SqliteQuestionRepository repository = new SqliteQuestionRepository(database);
		Question saved = repository.save(booklet, "Q1", "", 1,
				List.of(new QuestionRegion(booklet, 1, 0.10, 0.10, 0.50, 0.20)), subtopic, false, null, null,
				QuestionResponseType.MULTIPLE_CHOICE);

		assertEquals(QuestionResponseType.MULTIPLE_CHOICE, saved.getResponseType());

		Question reloaded = new SqliteQuestionRepository(database).findById(saved.getId()).orElseThrow();

		assertEquals(QuestionResponseType.MULTIPLE_CHOICE, reloaded.getResponseType());
		assertEquals(1, reloaded.getMarks());

		// A marks-only repository edit preserves response type, so changing this MCQ
		// to two marks must be rejected before SQLite is modified.
		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
				() -> repository.updateQuestion(saved.getId(), "Q1", 2,
						List.of(new QuestionRegion(booklet, 2, 0.10, 0.20, 0.50, 0.20)), subtopic, null, null));

		assertTrue(failure.getMessage().contains("Multiple-choice questions must be worth exactly 1 mark"));

		Question afterRejectedEdit = new SqliteQuestionRepository(database).findById(saved.getId()).orElseThrow();

		// Reopening proves the rejected edit did not leave an invalid row behind.
		assertEquals(QuestionResponseType.MULTIPLE_CHOICE, afterRejectedEdit.getResponseType());
		assertEquals(1, afterRejectedEdit.getMarks());
		assertEquals(1, afterRejectedEdit.getRegions().getFirst().pageNumber());
	}

	@Test
	void rejectsPersistedAnswerRegionWhoseFileBelongsToAnotherExam() throws Exception {
		ReconstructionFixture fixture = createReconstructionFixture("invalid-answer-reconstruction.db");
		try (Connection connection = fixture.database().openConnection();
				Statement statement = connection.createStatement()) {
			statement.execute("INSERT INTO answers (id, question_id, answer_text) VALUES (1, "
					+ fixture.question().getId() + ", NULL)");
			statement.execute("""
					INSERT INTO answer_regions
					    (answer_id, region_order, answer_file_id, page_number, x, y, width, height)
					VALUES (1, 0, %d, 1, 0.10, 0.10, 0.50, 0.20)
					""".formatted(fixture.otherAnswerFile().getId()));
		}
		assertThrows(IllegalStateException.class,
				() -> new SqliteQuestionRepository(fixture.database()).findById(fixture.question().getId()));
	}

	@Test
	void rejectsPersistedQuestionRegionWhoseBookletBelongsToAnotherExam() throws Exception {
		ReconstructionFixture fixture = createReconstructionFixture("invalid-question-reconstruction.db");
		try (Connection connection = fixture.database().openConnection();
				Statement statement = connection.createStatement()) {
			statement.execute("DELETE FROM question_regions WHERE question_id = " + fixture.question().getId());
			statement.execute("""
					INSERT INTO question_regions
					    (question_id, region_order, booklet_id, page_number, x, y, width, height)
					VALUES (%d, 0, %d, 1, 0.10, 0.10, 0.50, 0.20)
					""".formatted(fixture.question().getId(), fixture.otherBooklet().getId()));
		}
		assertThrows(IllegalStateException.class,
				() -> new SqliteQuestionRepository(fixture.database()).findById(fixture.question().getId()));
	}

	@Test
	void reloadsPersistedAnswer() throws Exception {
		Path databasePath = tempDirectory.resolve("answers.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SyllabusVersion syllabus = curriculumWriter.insertSyllabusVersion(chemistry, "2025", true);
		Unit unit = curriculumWriter.insertUnit(syllabus, "1", "Unit 1", 1);
		Topic topic = curriculumWriter.insertTopic(unit, "1.1", "Topic 1", 1);
		Subtopic subtopic = curriculumWriter.insertSubtopic(topic, "1.1.1", "Subtopic 1", 1);
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		SqliteExamImporter examImporter = new SqliteExamImporter(database, examWriter);
		ExamBooklet booklet = examImporter.importExam(chemistry, "QCAA", 2025, "External Assessment",
				"Question booklet", "Chemistry/2025/questions.pdf");
		SqliteQuestionRepository repository = new SqliteQuestionRepository(database);
		Question question = repository.save(booklet, "Q1", "", 1,
				List.of(new QuestionRegion(booklet, 1, 0.10, 0.10, 0.50, 0.20)), subtopic, false);
		SqliteAnswerWriter answerWriter = new SqliteAnswerWriter(database, examWriter);
		AnswerFile answerFile = answerWriter.findOrCreateAnswerFile(booklet.getExam(), "Answers",
				"Chemistry/2025/answers.pdf");
		answerWriter.insertAnswer(question, "B", List.of(new AnswerRegion(answerFile, 4, 0.10, 0.20, 0.40, 0.10)));
		SqliteQuestionRepository secondRepository = new SqliteQuestionRepository(database);
		Question loaded = secondRepository.findById(question.getId()).orElseThrow();
		assertTrue(loaded.hasAnswer());
		assertEquals("B", loaded.getAnswer().getAnswerText());
		assertEquals(1, loaded.getAnswer().getRegions().size());
		assertEquals(4, loaded.getAnswer().getRegions().getFirst().pageNumber());
	}

	@Test
	void savesAndReloadsDescriptorClassification() throws Exception {
		Path databasePath = tempDirectory.resolve("descriptor-classification.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject psychology = curriculumWriter.insertSubject("Psychology");
		SyllabusVersion syllabus = curriculumWriter.insertSyllabusVersion(psychology, "2025", true);
		Unit unit = curriculumWriter.insertUnit(syllabus, "1", "Unit 1", 1);
		Topic topic = curriculumWriter.insertTopic(unit, "1.1", "Topic 1", 1);
		Descriptor descriptor = curriculumWriter.insertDescriptor(topic, "1.1.1", "Descriptor 1", 1);
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		SqliteExamImporter examImporter = new SqliteExamImporter(database, examWriter);
		ExamBooklet booklet = examImporter.importExam(psychology, "QCAA", 2025, "External Assessment",
				"Question booklet", "Psychology/2025/questions.pdf");
		SqliteQuestionRepository repository = new SqliteQuestionRepository(database);
		Question saved = repository.save(booklet, "Q1", "", 1,
				List.of(new QuestionRegion(booklet, 1, 0.10, 0.10, 0.50, 0.20)), descriptor, false);
		SqliteDatabase reopenedDatabase = new SqliteDatabase(databasePath);
		reopenedDatabase.initialiseSchema();
		SqliteQuestionRepository secondRepository = new SqliteQuestionRepository(reopenedDatabase);
		Question loaded = secondRepository.findById(saved.getId()).orElseThrow();
		assertInstanceOf(Descriptor.class, loaded.getClassification());
		assertEquals(descriptor.getId(), loaded.getClassification().getId());
		assertEquals(CurriculumLevel.DESCRIPTOR, loaded.getClassification().getLevel());
		assertInstanceOf(Topic.class, loaded.getClassification().getParent());
		assertEquals(topic.getId(), loaded.getClassification().getParent().getId());
		assertEquals(psychology, loaded.getClassification().getSyllabusVersion().getSubject());
		assertEquals(CurriculumLevel.DESCRIPTOR, secondRepository.findAll().getFirst().getClassification().getLevel());
	}

	@Test
	void savesAndReloadsImportedQuestionWithoutRegions() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("imported-question.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SyllabusVersion syllabus = curriculumWriter.insertSyllabusVersion(chemistry, "2019", false);
		Unit unit = curriculumWriter.insertUnit(syllabus, "1", "Unit 1", 1);
		Topic topic = curriculumWriter.insertTopic(unit, "1.1", "Topic 1", 1);
		Subtopic subtopic = curriculumWriter.insertSubtopic(topic, "1.1.1", "Subtopic 1", 1);
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		ExamBooklet booklet = new SqliteExamImporter(database, examWriter).importExam(chemistry, "QCAA", 2019,
				"External Assessment", "Paper 1", "Chemistry/2019/paper1.pdf");
		SqliteQuestionRepository repository = new SqliteQuestionRepository(database);
		Question saved = repository.save(booklet, "21a", "", 3, List.of(), subtopic, true);
		Question loaded = repository.findById(saved.getId()).orElseThrow();
		assertEquals(booklet.getId(), loaded.getBooklet().getId());
		assertEquals(3, loaded.getMarks());
		assertTrue(loaded.getRegions().isEmpty());
		assertTrue(loaded.isPreambleCaptureRequired());
	}

	@Test
	void savesAndReloadsQuestion() throws Exception {
		Path databasePath = tempDirectory.resolve("questionbank.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SyllabusVersion syllabus = curriculumWriter.insertSyllabusVersion(chemistry, "2019", false);
		Unit unit = curriculumWriter.insertUnit(syllabus, "1", "Unit 1", 1);
		Topic topic = curriculumWriter.insertTopic(unit, "1.1", "Topic 1", 1);
		Subtopic subtopic = curriculumWriter.insertSubtopic(topic, "1.1.1", "Subtopic 1", 1);
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		SqliteExamImporter examImporter = new SqliteExamImporter(database, examWriter);
		ExamBooklet booklet = examImporter.importExam(chemistry, "QCAA", 2019, "External Assessment", "Paper 1",
				"Chemistry/2019/paper1.pdf");
		List<QuestionRegion> regions = List.of(new QuestionRegion(booklet, 4, 0.10, 0.20, 0.50, 0.15),
				new QuestionRegion(booklet, 5, 0.10, 0.10, 0.50, 0.20));
		SqliteQuestionRepository repository = new SqliteQuestionRepository(database);
		Question saved = repository.save(booklet, "Q6", "", 3, regions, subtopic, false);
		assertTrue(saved.getId() > 0);
		SqliteQuestionRepository secondRepository = new SqliteQuestionRepository(database);
		Question loaded = secondRepository.findById(saved.getId()).orElseThrow();
		assertEquals(saved.getId(), loaded.getId());
		assertEquals("Q6", loaded.getQuestionCode());
		assertEquals(2, loaded.getRegions().size());
		assertEquals(3, loaded.getMarks());
		assertEquals(booklet.getId(), loaded.getBooklet().getId());
		assertEquals(4, loaded.getRegions().get(0).pageNumber());
		assertEquals(5, loaded.getRegions().get(1).pageNumber());
		assertEquals(subtopic.getId(), loaded.getClassification().getId());
		assertEquals(1, secondRepository.findAll().size());
	}

	private ReconstructionFixture createReconstructionFixture(String databaseName) throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve(databaseName));
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SyllabusVersion syllabus = curriculumWriter.insertSyllabusVersion(chemistry, "2025", true);
		Unit unit = curriculumWriter.insertUnit(syllabus, "1", "Unit 1", 1);
		Topic topic = curriculumWriter.insertTopic(unit, "1.1", "Topic 1", 1);
		Subtopic subtopic = curriculumWriter.insertSubtopic(topic, "1.1.1", "Subtopic 1", 1);
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		SqliteExamImporter examImporter = new SqliteExamImporter(database, examWriter);
		ExamBooklet booklet = examImporter.importExam(chemistry, "QCAA", 2025, "External Assessment",
				"Question booklet", "Chemistry/2025/questions.pdf");
		ExamBooklet otherBooklet = examImporter.importExam(chemistry, "QCAA", 2024, "External Assessment",
				"Question booklet", "Chemistry/2024/questions.pdf");
		SqliteQuestionRepository repository = new SqliteQuestionRepository(database);
		Question question = repository.save(booklet, "Q1", "", 1,
				List.of(new QuestionRegion(booklet, 1, 0.10, 0.10, 0.50, 0.20)), subtopic, false);
		SqliteAnswerWriter answerWriter = new SqliteAnswerWriter(database, examWriter);
		AnswerFile otherAnswerFile = answerWriter.findOrCreateAnswerFile(otherBooklet.getExam(), "Answers",
				"Chemistry/2024/answers.pdf");
		return new ReconstructionFixture(database, question, otherBooklet, otherAnswerFile);
	}

	private record ReconstructionFixture(SqliteDatabase database, Question question, ExamBooklet otherBooklet,
			AnswerFile otherAnswerFile) {
	}
}
