package au.edu.eq.questionbank.repository.assessment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.Descriptor;
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
	void appliesSharedContextToEveryPartOfSourceQuestion() throws Exception {
		Fixture fixture = createFixture("source-wide-context.db");
		SqliteQuestionRepository repository = new SqliteQuestionRepository(fixture.database());
		SourceQuestion sourceQuestion = new SqliteSourceQuestionRepository(fixture.database())
				.save(fixture.firstBooklet(), "22");
		Question partA = repository.save(fixture.firstBooklet(), "22a", "", 2,
				List.of(new QuestionRegion(fixture.firstBooklet(), 3, 0.10, 0.30, 0.70, 0.20)),
				fixture.classification(), true, sourceQuestion, null);
		Question partB = repository.save(fixture.firstBooklet(), "22b", "", 3,
				List.of(new QuestionRegion(fixture.firstBooklet(), 3, 0.10, 0.55, 0.70, 0.20)),
				fixture.classification(), false, sourceQuestion, null);
		SharedQuestionContext sharedContext = new SqliteSharedQuestionContextRepository(fixture.database()).save(
				fixture.firstBooklet(), "Question 22 preamble",
				List.of(new SharedQuestionContextRegion(3, 0.10, 0.10, 0.70, 0.15)));
		assertEquals(2, repository.applySharedContextToSourceQuestion(sourceQuestion, sharedContext));
		Question reloadedA = repository.findById(partA.getId()).orElseThrow();
		Question reloadedB = repository.findById(partB.getId()).orElseThrow();
		assertEquals(sharedContext.getId(), reloadedA.getSharedContext().getId());
		assertEquals(sharedContext.getId(), reloadedB.getSharedContext().getId());
		assertFalse(reloadedA.isSharedContextUnresolved());
		assertEquals(0, repository.applySharedContextToSourceQuestion(sourceQuestion, sharedContext));
	}

	@Test
	void attachesRelationshipsWhenCapturingImportedQuestion() throws Exception {
		Fixture fixture = createFixture("capture-relationships.db");
		SqliteQuestionRepository repository = new SqliteQuestionRepository(fixture.database());
		Question imported = repository.save(fixture.firstBooklet(), "21a", "", 2, List.of(), fixture.classification(),
				true);
		assertTrue(imported.isSharedContextUnresolved());
		SourceQuestion sourceQuestion = new SqliteSourceQuestionRepository(fixture.database())
				.save(fixture.firstBooklet(), "21");
		SharedQuestionContext sharedContext = new SqliteSharedQuestionContextRepository(fixture.database()).save(
				fixture.firstBooklet(), "Question 21 preamble",
				List.of(new SharedQuestionContextRegion(3, 0.10, 0.10, 0.70, 0.20)));
		Question captured = repository.attachRegions(imported.getId(),
				List.of(new QuestionRegion(fixture.firstBooklet(), 3, 0.10, 0.40, 0.70, 0.20)), fixture.descriptor(),
				sourceQuestion, sharedContext);
		assertEquals(fixture.descriptor().getId(), captured.getClassification().getId());
		assertEquals(sourceQuestion.getId(), captured.getSourceQuestion().getId());
		assertEquals(sharedContext.getId(), captured.getSharedContext().getId());
		assertFalse(captured.isSharedContextUnresolved());
		Question reloaded = new SqliteQuestionRepository(fixture.database()).findById(imported.getId()).orElseThrow();
		assertEquals(1, reloaded.getRegions().size());
		assertEquals(sourceQuestion.getId(), reloaded.getSourceQuestion().getId());
		assertEquals(sharedContext.getId(), reloaded.getSharedContext().getId());
		assertFalse(reloaded.isSharedContextUnresolved());
	}

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
	void rejectsConflictingContextWithoutUpdatingOtherParts() throws Exception {
		Fixture fixture = createFixture("conflicting-source-context.db");
		SqliteQuestionRepository repository = new SqliteQuestionRepository(fixture.database());
		SourceQuestion sourceQuestion = new SqliteSourceQuestionRepository(fixture.database())
				.save(fixture.firstBooklet(), "22");
		SqliteSharedQuestionContextRepository contextRepository = new SqliteSharedQuestionContextRepository(
				fixture.database());
		SharedQuestionContext firstContext = contextRepository.save(fixture.firstBooklet(), "First preamble",
				List.of(new SharedQuestionContextRegion(1, 0.1, 0.1, 0.7, 0.2)));
		SharedQuestionContext conflictingContext = contextRepository.save(fixture.firstBooklet(),
				"Conflicting preamble", List.of(new SharedQuestionContextRegion(1, 0.1, 0.4, 0.7, 0.2)));
		repository.save(fixture.firstBooklet(), "22a", "", 1,
				List.of(new QuestionRegion(fixture.firstBooklet(), 1, 0.1, 0.6, 0.7, 0.1)), fixture.classification(),
				true, sourceQuestion, firstContext);
		Question partB = repository.save(fixture.firstBooklet(), "22b", "", 1,
				List.of(new QuestionRegion(fixture.firstBooklet(), 2, 0.1, 0.2, 0.7, 0.1)), fixture.classification(),
				true, sourceQuestion, null);
		assertThrows(IllegalStateException.class,
				() -> repository.applySharedContextToSourceQuestion(sourceQuestion, conflictingContext));
		Question reloadedB = repository.findById(partB.getId()).orElseThrow();
		assertFalse(reloadedB.hasSharedContext());
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
	void replacingSharedContextPreservesAllQuestionLinks() throws Exception {
		Fixture fixture = createFixture("replace-linked-context.db");
		SqliteQuestionRepository questionRepository = new SqliteQuestionRepository(fixture.database());
		SqliteSourceQuestionRepository sourceRepository = new SqliteSourceQuestionRepository(fixture.database());
		SqliteSharedQuestionContextRepository contextRepository = new SqliteSharedQuestionContextRepository(
				fixture.database());
		SourceQuestion sourceQuestion = sourceRepository.save(fixture.firstBooklet(), "22");
		SharedQuestionContext originalContext = contextRepository.save(fixture.firstBooklet(), "Question 22 preamble",
				List.of(new SharedQuestionContextRegion(2, 0.10, 0.10, 0.70, 0.15)));
		Question partA = questionRepository.save(fixture.firstBooklet(), "22a", "", 2,
				List.of(new QuestionRegion(fixture.firstBooklet(), 2, 0.10, 0.35, 0.70, 0.15)),
				fixture.classification(), true, sourceQuestion, originalContext);
		Question partB = questionRepository.save(fixture.firstBooklet(), "22b", "", 3,
				List.of(new QuestionRegion(fixture.firstBooklet(), 2, 0.10, 0.55, 0.70, 0.15)),
				fixture.classification(), true, sourceQuestion, originalContext);
		List<SharedQuestionContextRegion> correctedRegions = List
				.of(new SharedQuestionContextRegion(3, 0.12, 0.12, 0.65, 0.18));
		SharedQuestionContext correctedContext = contextRepository.replace(originalContext,
				"Corrected Question 22 preamble", correctedRegions);
		Question reloadedA = questionRepository.findById(partA.getId()).orElseThrow();
		Question reloadedB = questionRepository.findById(partB.getId()).orElseThrow();

		// Both parts still point to the same persistent context after its source
		// regions are corrected.
		assertEquals(originalContext.getId(), correctedContext.getId());
		assertEquals(originalContext.getId(), reloadedA.getSharedContext().getId());
		assertEquals(originalContext.getId(), reloadedB.getSharedContext().getId());

		// Reloading each Question must expose the corrected shared-context content.
		assertEquals("Corrected Question 22 preamble", reloadedA.getSharedContext().getLabel());
		assertEquals(correctedRegions, reloadedA.getSharedContext().getRegions());
		assertEquals("Corrected Question 22 preamble", reloadedB.getSharedContext().getLabel());
		assertEquals(correctedRegions, reloadedB.getSharedContext().getRegions());
	}

	@Test
	void resolvesSharedContextWithoutReplacingExistingQuestionRegions() throws Exception {
		Fixture fixture = createFixture("relationship-only-resolution.db");
		SqliteQuestionRepository repository = new SqliteQuestionRepository(fixture.database());
		SqliteSourceQuestionRepository sourceRepository = new SqliteSourceQuestionRepository(fixture.database());
		SourceQuestion sourceQuestion = sourceRepository.save(fixture.firstBooklet(), "21");
		Question imported = repository.save(fixture.firstBooklet(), "21a", "", 2,
				List.of(new QuestionRegion(fixture.firstBooklet(), 4, 0.10, 0.40, 0.70, 0.20)),
				fixture.classification(), true, sourceQuestion, null);
		SharedQuestionContext sharedContext = new SqliteSharedQuestionContextRepository(fixture.database()).save(
				fixture.firstBooklet(), "Question 21 preamble",
				List.of(new SharedQuestionContextRegion(3, 0.10, 0.10, 0.70, 0.20)));
		Question updated = repository.updateCaptureRelationships(imported.getId(), fixture.descriptor(), sourceQuestion,
				sharedContext);
		assertEquals(1, updated.getRegions().size());
		assertEquals(4, updated.getRegions().getFirst().pageNumber());
		assertEquals(fixture.descriptor().getId(), updated.getClassification().getId());
		assertEquals(sourceQuestion.getId(), updated.getSourceQuestion().getId());
		assertEquals(sharedContext.getId(), updated.getSharedContext().getId());
		assertFalse(updated.isSharedContextUnresolved());
		Question reloaded = new SqliteQuestionRepository(fixture.database()).findById(imported.getId()).orElseThrow();
		assertEquals(1, reloaded.getRegions().size());
		assertEquals(4, reloaded.getRegions().getFirst().pageNumber());
		assertEquals(fixture.descriptor().getId(), reloaded.getClassification().getId());
		assertEquals(sharedContext.getId(), reloaded.getSharedContext().getId());
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

	@Test
	void updatesQuestionMetadataAndRegionsWithoutChangingIdentity() throws Exception {
		Fixture fixture = createFixture("question-update.db");
		SqliteQuestionRepository repository = new SqliteQuestionRepository(fixture.database());
		Question original = repository.save(fixture.firstBooklet(), "21a", "preserved text", 2,
				List.of(new QuestionRegion(fixture.firstBooklet(), 2, 0.10, 0.20, 0.60, 0.15)),
				fixture.classification(), true);
		SourceQuestion sourceQuestion = new SqliteSourceQuestionRepository(fixture.database())
				.save(fixture.firstBooklet(), "21");
		SharedQuestionContext sharedContext = new SqliteSharedQuestionContextRepository(fixture.database()).save(
				fixture.firstBooklet(), "Question 21 preamble",
				List.of(new SharedQuestionContextRegion(1, 0.10, 0.10, 0.70, 0.20)));
		Question updated = repository.updateQuestion(original.getId(), "21b", 4,
				List.of(new QuestionRegion(fixture.firstBooklet(), 5, 0.15, 0.30, 0.65, 0.25)), fixture.descriptor(),
				sourceQuestion, sharedContext);
		assertEquals(original.getId(), updated.getId());
		assertEquals(fixture.firstBooklet().getId(), updated.getBooklet().getId());
		assertEquals("21b", updated.getQuestionCode());
		assertEquals("preserved text", updated.getQuestionText());
		assertEquals(4, updated.getMarks());
		assertEquals(fixture.descriptor().getId(), updated.getClassification().getId());
		assertEquals(1, updated.getRegions().size());
		assertEquals(5, updated.getRegions().getFirst().pageNumber());
		assertEquals(sourceQuestion.getId(), updated.getSourceQuestion().getId());
		assertEquals(sharedContext.getId(), updated.getSharedContext().getId());
		assertTrue(updated.isPreambleCaptureRequired());
		Question reloaded = new SqliteQuestionRepository(fixture.database()).findById(original.getId()).orElseThrow();
		assertEquals("21b", reloaded.getQuestionCode());
		assertEquals(4, reloaded.getMarks());
		assertEquals(1, reloaded.getRegions().size());
		assertEquals(5, reloaded.getRegions().getFirst().pageNumber());
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
		Descriptor descriptor = curriculumWriter.insertDescriptor(subtopic, "1.1.1.1", "Descriptor 1", 1);
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		SqliteExamImporter examImporter = new SqliteExamImporter(database, examWriter);
		ExamBooklet firstBooklet = examImporter.importExam(chemistry, "QCAA", 2025, "External Assessment", "Paper 1",
				"Chemistry/2025/paper1.pdf");
		ExamBooklet secondBooklet = examImporter.importExam(chemistry, "QCAA", 2025, "External Assessment", "Paper 2",
				"Chemistry/2025/paper2.pdf");
		return new Fixture(database, firstBooklet, secondBooklet, subtopic, descriptor);
	}

	private record Fixture(SqliteDatabase database, ExamBooklet firstBooklet, ExamBooklet secondBooklet,
			Subtopic classification, Descriptor descriptor) {
	}
}
