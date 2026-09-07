package au.edu.eq.questionbank.repository.assessment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.SharedQuestionContext;
import au.edu.eq.questionbank.model.SharedQuestionContextRegion;
import au.edu.eq.questionbank.model.SourceQuestion;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class SqliteQuestionRelationshipPersistenceTest {

	@TempDir
	Path tempDirectory;

	@Test
	void existingSavePathLeavesRelationshipsEmpty() throws Exception {
		Fixture fixture = createFixture("unlinked-question.db");
		SqliteQuestionRepository repository = new SqliteQuestionRepository(fixture.database());
		Question saved = repository.save(fixture.firstBooklet(), "Q1", "", 1,
				List.of(new QuestionRegion(fixture.firstBooklet(), 1, 0.10, 0.10, 0.50, 0.20)),
				fixture.classification(), false);
		Question loaded = repository.findById(saved.getId()).orElseThrow();
		assertFalse(loaded.hasSourceQuestion());
		assertFalse(loaded.hasSharedContext());
		assertFalse(loaded.isSharedContextUnresolved());
	}

	@Test
	void rejectsRelationshipsFromAnotherBooklet() throws Exception {
		Fixture fixture = createFixture("wrong-booklet.db");
		SqliteSourceQuestionRepository sourceRepository = new SqliteSourceQuestionRepository(fixture.database());
		SourceQuestion otherSource = sourceRepository.save(fixture.secondBooklet(), "21");
		SqliteSharedQuestionContextRepository contextRepository = new SqliteSharedQuestionContextRepository(
				fixture.database());
		SharedQuestionContext otherContext = contextRepository.save(fixture.secondBooklet(), "Other booklet context",
				List.of(new SharedQuestionContextRegion(1, 0.10, 0.10, 0.50, 0.20)));
		SqliteQuestionRepository repository = new SqliteQuestionRepository(fixture.database());
		assertThrows(IllegalArgumentException.class,
				() -> repository.save(fixture.firstBooklet(), "Q1", "", 1,
						List.of(new QuestionRegion(fixture.firstBooklet(), 1, 0.10, 0.10, 0.50, 0.20)),
						fixture.classification(), false, otherSource, null));
		assertThrows(IllegalArgumentException.class,
				() -> repository.save(fixture.firstBooklet(), "Q2", "", 1,
						List.of(new QuestionRegion(fixture.firstBooklet(), 1, 0.10, 0.10, 0.50, 0.20)),
						fixture.classification(), false, null, otherContext));
		assertTrue(repository.findAll().isEmpty());
	}

	@Test
	void savesAndReloadsSourceQuestionAndSharedContext() throws Exception {
		Fixture fixture = createFixture("linked-question.db");
		SqliteSourceQuestionRepository sourceRepository = new SqliteSourceQuestionRepository(fixture.database());
		SourceQuestion sourceQuestion = sourceRepository.save(fixture.firstBooklet(), "21");
		SqliteSharedQuestionContextRepository contextRepository = new SqliteSharedQuestionContextRepository(
				fixture.database());
		SharedQuestionContext sharedContext = contextRepository.save(fixture.firstBooklet(), "Question 21 preamble",
				List.of(new SharedQuestionContextRegion(3, 0.10, 0.10, 0.70, 0.20),
						new SharedQuestionContextRegion(4, 0.12, 0.15, 0.65, 0.25)));
		SqliteQuestionRepository repository = new SqliteQuestionRepository(fixture.database());
		Question saved = repository.save(fixture.firstBooklet(), "21a", "", 2,
				List.of(new QuestionRegion(fixture.firstBooklet(), 4, 0.10, 0.45, 0.60, 0.15)),
				fixture.classification(), true, sourceQuestion, sharedContext);
		assertTrue(saved.hasSourceQuestion());
		assertTrue(saved.hasSharedContext());
		Question loaded = new SqliteQuestionRepository(fixture.database()).findById(saved.getId()).orElseThrow();
		assertTrue(loaded.hasSourceQuestion());
		assertTrue(loaded.hasSharedContext());
		assertEquals(sourceQuestion.getId(), loaded.getSourceQuestion().getId());
		assertEquals("21", loaded.getSourceQuestion().getSourceQuestionCode());
		assertEquals(sharedContext.getId(), loaded.getSharedContext().getId());
		assertEquals("Question 21 preamble", loaded.getSharedContext().getLabel());
		assertEquals(2, loaded.getSharedContext().getRegions().size());
		assertEquals(3, loaded.getSharedContext().getRegions().get(0).pageNumber());
		assertEquals(4, loaded.getSharedContext().getRegions().get(1).pageNumber());
		assertTrue(loaded.isPreambleCaptureRequired());
		assertFalse(loaded.isSharedContextUnresolved());
	}

	private Fixture createFixture(String databaseName) throws Exception {
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
		ExamBooklet firstBooklet = examImporter.importExam(chemistry, "QCAA", 2025, "External Assessment", "Paper 1",
				"Chemistry/2025/paper1.pdf");
		ExamBooklet secondBooklet = examImporter.importExam(chemistry, "QCAA", 2025, "External Assessment", "Paper 2",
				"Chemistry/2025/paper2.pdf");
		return new Fixture(database, firstBooklet, secondBooklet, subtopic);
	}

	private record Fixture(SqliteDatabase database, ExamBooklet firstBooklet, ExamBooklet secondBooklet,
			Subtopic classification) {
	}
}
