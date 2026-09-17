package au.edu.eq.questionbank.repository.curriculum;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumStatus;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.curriculum.CurriculumAuthoringSession;
import au.edu.eq.questionbank.service.curriculum.CurriculumDraftLoader;
import au.edu.eq.questionbank.service.curriculum.CurriculumDraftNode;
import au.edu.eq.questionbank.service.curriculum.CurriculumDraftNumberingService;
import au.edu.eq.questionbank.service.curriculum.CurriculumLifecycleService;

class CurriculumAuthoringPersistenceRegressionTest {

	@TempDir
	Path tempDir;
	private SqliteDatabase database;
	private SyllabusVersion syllabus;
	private CurriculumDraftLoader loader;
	private SqliteCurriculumAuthoringWriter writer;
	private SqliteCurriculumAuthoringRepository repository;
	private final CurriculumDraftNumberingService numbering = new CurriculumDraftNumberingService();

	@BeforeEach
	void createCurriculum() throws Exception {
		database = new SqliteDatabase(tempDir.resolve("authoring.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter seed = new SqliteCurriculumWriter(database);
		var subject = seed.insertSubject("Engineering");
		syllabus = seed.insertSyllabusVersion(subject, "2025", true);
		var unit = seed.insertUnit(syllabus, "1", "Fundamentals", 0);
		var topic = seed.insertTopic(unit, "1.1", "Forces", 0);
		seed.insertDescriptor(topic, "1.1.1", "First descriptor", 0);
		seed.insertDescriptor(topic, "1.1.2", "Second descriptor", 1);
		repository = new SqliteCurriculumAuthoringRepository(database);
		loader = new CurriculumDraftLoader(repository);
		writer = new SqliteCurriculumAuthoringWriter(database);
	}

	@Test
	void deletingPersistedSiblingPreservesSurvivingCodeAndIdentityAfterReload() {
		CurriculumAuthoringSession session = loader.load(syllabus);
		CurriculumDraftNode removed = node(session, "1.1.1");
		CurriculumDraftNode surviving = node(session, "1.1.2");
		long survivingPersistentId = session.persistentIdForDraftId(surviving.draftId()).orElseThrow();
		numbering.removeSubtree(session.draft(), removed.draftId());
		writer.save(session);
		CurriculumAuthoringSession reloaded = loader.load(syllabus);
		CurriculumDraftNode reloadedSurvivor = node(reloaded, "1.1.2");
		assertAll(() -> assertEquals("1.1.2", reloadedSurvivor.code()),
				() -> assertEquals(0, reloadedSurvivor.displayOrder()), () -> assertEquals(survivingPersistentId,
						reloaded.persistentIdForDraftId(reloadedSurvivor.draftId()).orElseThrow()));
	}

	@Test
	void rejectsFinalisingAStaleDraftWithoutOverwritingTheSavedCurriculum() {
		CurriculumAuthoringSession first = loader.load(syllabus);
		CurriculumAuthoringSession stale = loader.load(syllabus);
		numbering.updateText(first.draft(), node(first, "1.1.1").draftId(), "Reviewed wording");
		writer.save(first);
		List<PersistedCurriculumNode> committed = repository.findNodesForVersion(syllabus);
		CurriculumLifecycleService lifecycle = new CurriculumLifecycleService(writer,
				new SqliteCurriculumLifecycleRepository(database), Clock.systemUTC());
		assertAll(() -> assertThrows(IllegalStateException.class, () -> lifecycle.finalise(stale)),
				() -> assertEquals(committed, repository.findNodesForVersion(syllabus)),
				() -> assertEquals(CurriculumStatus.IN_PROGRESS, new SqliteCurriculumRepository(database)
						.findVersionById(syllabus.getId()).orElseThrow().getCurriculumStatus()));
	}

	@Test
	void rejectsStaleSaveAfterAnotherSessionChangesWording() {
		CurriculumAuthoringSession first = loader.load(syllabus);
		CurriculumAuthoringSession stale = loader.load(syllabus);
		numbering.updateText(first.draft(), node(first, "1.1.1").draftId(), "Reviewed wording");
		writer.save(first);
		List<PersistedCurriculumNode> committed = repository.findNodesForVersion(syllabus);
		assertAll(() -> assertThrows(IllegalStateException.class, () -> writer.save(stale)),
				() -> assertEquals(committed, repository.findNodesForVersion(syllabus),
						"A stale save must not replace committed wording"));
	}

	@Test
	void rejectsStaleSaveAfterAnotherSessionReordersNodes() {
		CurriculumAuthoringSession first = loader.load(syllabus);
		CurriculumAuthoringSession stale = loader.load(syllabus);
		assertTrue(numbering.moveDown(first.draft(), node(first, "1.1.1").draftId()));
		writer.save(first);
		List<PersistedCurriculumNode> committed = repository.findNodesForVersion(syllabus);
		assertAll(() -> assertThrows(IllegalStateException.class, () -> writer.save(stale)),
				() -> assertEquals(committed, repository.findNodesForVersion(syllabus),
						"A stale save must not reverse committed ordering or codes"));
	}

	@Test
	void reorderingPersistedSiblingsPreservesCodesAndIdentitiesAfterReload() {
		CurriculumAuthoringSession session = loader.load(syllabus);
		CurriculumDraftNode first = node(session, "1.1.1");
		CurriculumDraftNode second = node(session, "1.1.2");
		long firstPersistentId = session.persistentIdForDraftId(first.draftId()).orElseThrow();
		long secondPersistentId = session.persistentIdForDraftId(second.draftId()).orElseThrow();
		assertTrue(numbering.moveDown(session.draft(), first.draftId()));
		writer.save(session);
		CurriculumAuthoringSession reloaded = loader.load(syllabus);
		CurriculumDraftNode reloadedFirst = node(reloaded, "1.1.1");
		CurriculumDraftNode reloadedSecond = node(reloaded, "1.1.2");
		CurriculumDraftNode topic = node(reloaded, "1.1");
		List<CurriculumDraftNode> descriptors = reloaded.draft().childrenOf(topic.draftId());
		assertAll(() -> assertEquals("1.1.2", descriptors.getFirst().code()),
				() -> assertEquals("1.1.1", descriptors.get(1).code()),
				() -> assertEquals(firstPersistentId,
						reloaded.persistentIdForDraftId(reloadedFirst.draftId()).orElseThrow()),
				() -> assertEquals(secondPersistentId,
						reloaded.persistentIdForDraftId(reloadedSecond.draftId()).orElseThrow()));
	}

	@Test
	void rollsBackPartiallyWrittenDraftAndRetainsBindingsForRetry() throws Exception {
		CurriculumAuthoringSession session = loader.load(syllabus);
		List<PersistedCurriculumNode> before = repository.findNodesForVersion(syllabus);
		CurriculumDraftNode removed = node(session, "1.1.2");
		numbering.removeSubtree(session.draft(), removed.draftId());
		numbering.updateText(session.draft(), node(session, "1.1.1").draftId(), "Changed before failure");
		long topicId = node(session, "1.1").draftId();
		CurriculumDraftNode inserted = numbering.addNode(session.draft(), CurriculumLevel.DESCRIPTOR,
				"Inserted before failure", topicId, 7);
		numbering.addNode(session.draft(), CurriculumLevel.DESCRIPTOR, "Reject this insertion", topicId, 8);
		var snapshotBefore = session.persistedSnapshot();
		var bindingsBefore = session.persistentBindings();
		var deletionsBefore = session.deletedPersistentIds();
		var draftBefore = session.draft().nodes();

		// The trigger only fires after an earlier insertion and text update are visible
		// inside this transaction. This exercises rollback, not early validation.
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					CREATE TRIGGER reject_late_curriculum_insert BEFORE INSERT ON curriculum_nodes
					WHEN NEW.curriculum_name = 'Reject this insertion'
					 AND EXISTS (SELECT 1 FROM curriculum_nodes WHERE curriculum_name = 'Inserted before failure')
					 AND EXISTS (SELECT 1 FROM curriculum_nodes WHERE curriculum_name = 'Changed before failure')
					BEGIN SELECT RAISE(ABORT, 'late authoring failure'); END
					""");
		}
		IllegalStateException failure = assertThrows(IllegalStateException.class, () -> writer.save(session));
		assertTrue(failure.getCause().getMessage().contains("late authoring failure"));
		assertAll(() -> assertEquals(before, repository.findNodesForVersion(syllabus)),
				() -> assertEquals(snapshotBefore, session.persistedSnapshot()),
				() -> assertEquals(bindingsBefore, session.persistentBindings()),
				() -> assertEquals(deletionsBefore, session.deletedPersistentIds()),
				() -> assertEquals(draftBefore, session.draft().nodes()),
				() -> assertTrue(session.persistentIdForDraftId(inserted.draftId()).isEmpty()));
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("DROP TRIGGER reject_late_curriculum_insert");
		}
		writer.save(session);
		assertTrue(session.deletedPersistentIds().isEmpty());
		assertTrue(session.persistentIdForDraftId(inserted.draftId()).isPresent());
		assertEquals("Changed before failure", node(loader.load(syllabus), "1.1.1").name());
	}

	@Test
	void successfulSaveAdvancesSnapshotForFurtherEditsInTheSameSession() {
		CurriculumAuthoringSession session = loader.load(syllabus);
		numbering.updateText(session.draft(), node(session, "1.1.1").draftId(), "First save");
		writer.save(session);
		numbering.updateText(session.draft(), node(session, "1.1.1").draftId(), "Second save");
		writer.save(session);
		assertEquals("Second save", node(loader.load(syllabus), "1.1.1").name());
	}

	private CurriculumDraftNode node(CurriculumAuthoringSession session, String code) {
		return session.draft().nodes().stream().filter(node -> node.code().equals(code)).findFirst().orElseThrow();
	}
}
