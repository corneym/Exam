package au.edu.eq.questionbank.service.curriculum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumStatus;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.repository.curriculum.CurriculumLifecycleRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumAuthoringRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumAuthoringWriter;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumLifecycleRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class CurriculumLifecycleServiceTest {

	@TempDir
	Path tempDir;

	@Test
	void failedFinalisationAfterSaveKeepsDraftInProgressAndAllowsRetry() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("failed-finalisation-after-save.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(database);
		Subject engineering = writer.insertSubject("Engineering");
		SyllabusVersion syllabus = writer.insertSyllabusVersion(engineering, "2025", true);
		Unit unit = writer.insertUnit(syllabus, "1", "Engineering fundamentals", 0);
		Topic topic = writer.insertTopic(unit, "1.1", "Forces", 0);
		writer.insertDescriptor(topic, "1.1.1", "Resolve forces", 0);
		CurriculumDraftLoader loader = new CurriculumDraftLoader(new SqliteCurriculumAuthoringRepository(database));
		CurriculumAuthoringSession session = loader.load(syllabus);
		CurriculumDraftNode draftUnit = session.draft().childrenOf(null).getFirst();
		CurriculumDraftNode draftTopic = session.draft().childrenOf(draftUnit.draftId()).getFirst();
		CurriculumDraftNode addedDescriptor = new CurriculumDraftNumberingService().addNode(session.draft(),
				CurriculumLevel.DESCRIPTOR, "Calculate resultant force", draftTopic.draftId(), null);
		SqliteCurriculumAuthoringWriter authoringWriter = new SqliteCurriculumAuthoringWriter(database);
		CurriculumLifecycleRepository failingLifecycleRepository = new CurriculumLifecycleRepository() {

			@Override
			public SyllabusVersion finalise(SyllabusVersion syllabusVersion, Instant finalisedAt) {
				throw new IllegalStateException("Deliberate finalisation failure");
			}

			@Override
			public SyllabusVersion reopen(SyllabusVersion syllabusVersion) {
				throw new UnsupportedOperationException();
			}
		};
		CurriculumLifecycleService failingService = new CurriculumLifecycleService(authoringWriter,
				failingLifecycleRepository, Clock.fixed(Instant.parse("2026-09-17T03:00:00Z"), ZoneOffset.UTC));
		assertThrows(IllegalStateException.class, () -> failingService.finalise(session));
		SqliteCurriculumRepository repository = new SqliteCurriculumRepository(database);
		SyllabusVersion afterFailure = repository.findVersionById(syllabus.getId()).orElseThrow();
		/*
		 * The draft save completed before the lifecycle transition failed.
		 */
		assertEquals("Calculate resultant force", repository.findByCode(afterFailure, "1.1.2").orElseThrow().getName());
		/*
		 * Finalisation itself failed, so both persisted and session state remain
		 * IN_PROGRESS.
		 */
		assertEquals(CurriculumStatus.IN_PROGRESS, afterFailure.getCurriculumStatus());
		assertNull(afterFailure.getCurriculumFinalisedAt());
		assertEquals(CurriculumStatus.IN_PROGRESS, session.syllabusVersion().getCurriculumStatus());
		/*
		 * The successful save must also leave the newly persisted draft node bound to
		 * its permanent database identity.
		 */
		assertTrue(session.persistentIdForDraftId(addedDescriptor.draftId()).isPresent());
		/*
		 * Retrying with the real lifecycle repository must succeed without reopening or
		 * rebuilding the authoring session.
		 */
		CurriculumLifecycleService retryService = new CurriculumLifecycleService(authoringWriter,
				new SqliteCurriculumLifecycleRepository(database),
				Clock.fixed(Instant.parse("2026-09-17T04:00:00Z"), ZoneOffset.UTC));
		SyllabusVersion finalVersion = retryService.finalise(session);
		assertEquals(CurriculumStatus.FINAL, finalVersion.getCurriculumStatus());
		assertEquals(Instant.parse("2026-09-17T04:00:00Z"), finalVersion.getCurriculumFinalisedAt());
		assertTrue(session.syllabusVersion().isCurriculumFinal());
	}

	@Test
	void finalisesAndExplicitlyReopensCurriculum() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("curriculum-lifecycle.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(database);
		Subject engineering = writer.insertSubject("Engineering");
		SyllabusVersion syllabus = writer.insertSyllabusVersion(engineering, "2025", true);
		Unit unit = writer.insertUnit(syllabus, "1", "Engineering fundamentals", 0);
		Topic topic = writer.insertTopic(unit, "1.1", "Forces", 0);
		writer.insertDescriptor(topic, "1.1.1", "Resolve forces", 0);
		CurriculumDraftLoader loader = new CurriculumDraftLoader(new SqliteCurriculumAuthoringRepository(database));
		CurriculumAuthoringSession session = loader.load(syllabus);
		CurriculumDraftNode draftUnit = session.draft().childrenOf(null).get(0);
		CurriculumDraftNode draftTopic = session.draft().childrenOf(draftUnit.draftId()).get(0);
		CurriculumDraftNode descriptor = session.draft().childrenOf(draftTopic.draftId()).get(0);
		new CurriculumDraftNumberingService().updateText(session.draft(), descriptor.draftId(),
				"Resolve forces into horizontal and vertical components");
		Instant finalisedAt = Instant.parse("2026-09-14T08:00:00Z");
		Clock clock = Clock.fixed(finalisedAt, ZoneOffset.UTC);
		SqliteCurriculumAuthoringWriter authoringWriter = new SqliteCurriculumAuthoringWriter(database);
		CurriculumLifecycleService service = new CurriculumLifecycleService(authoringWriter,
				new SqliteCurriculumLifecycleRepository(database), clock);
		SyllabusVersion finalVersion = service.finalise(session);
		assertEquals(CurriculumStatus.FINAL, finalVersion.getCurriculumStatus());
		assertEquals(finalisedAt, finalVersion.getCurriculumFinalisedAt());
		assertTrue(session.syllabusVersion().isCurriculumFinal());
		SqliteCurriculumRepository repository = new SqliteCurriculumRepository(database);
		SyllabusVersion reloadedFinal = repository.findVersionById(syllabus.getId()).orElseThrow();
		assertEquals(CurriculumStatus.FINAL, reloadedFinal.getCurriculumStatus());
		assertEquals(finalisedAt, reloadedFinal.getCurriculumFinalisedAt());
		assertEquals("Resolve forces into horizontal and vertical components",
				repository.findByCode(reloadedFinal, "1.1.1").orElseThrow().getName());
		assertThrows(IllegalStateException.class, () -> authoringWriter.save(session));
		SyllabusVersion reopened = service.reopen(session);
		assertEquals(CurriculumStatus.IN_PROGRESS, reopened.getCurriculumStatus());
		assertNull(reopened.getCurriculumFinalisedAt());
		assertEquals(CurriculumStatus.IN_PROGRESS, session.syllabusVersion().getCurriculumStatus());
		SyllabusVersion reloadedReopened = repository.findVersionById(syllabus.getId()).orElseThrow();
		assertEquals(CurriculumStatus.IN_PROGRESS, reloadedReopened.getCurriculumStatus());
		assertNull(reloadedReopened.getCurriculumFinalisedAt());
		/*
		 * Editing and saving are available again only after explicit reopening.
		 */
		new CurriculumDraftNumberingService().updateText(session.draft(), descriptor.draftId(),
				"Edited after reopening");
		authoringWriter.save(session);
		assertEquals("Edited after reopening",
				repository.findByCode(reloadedReopened, "1.1.1").orElseThrow().getName());
	}

	@Test
	void refusesToFinaliseInvalidDraft() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDir.resolve("invalid-finalisation.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(database);
		Subject engineering = writer.insertSubject("Engineering");
		SyllabusVersion syllabus = writer.insertSyllabusVersion(engineering, "2025", true);
		writer.insertUnit(syllabus, "1", "Engineering fundamentals", 0);
		CurriculumAuthoringSession session = new CurriculumDraftLoader(
				new SqliteCurriculumAuthoringRepository(database)).load(syllabus);
		CurriculumDraftNode draftUnit = session.draft().childrenOf(null).get(0);
		session.draft().removeSubtree(draftUnit.draftId());
		CurriculumLifecycleService service = new CurriculumLifecycleService(
				new SqliteCurriculumAuthoringWriter(database), new SqliteCurriculumLifecycleRepository(database),
				Clock.systemUTC());
		assertThrows(IllegalArgumentException.class, () -> service.finalise(session));
		SyllabusVersion reloaded = new SqliteCurriculumRepository(database).findVersionById(syllabus.getId())
				.orElseThrow();
		assertEquals(CurriculumStatus.IN_PROGRESS, reloaded.getCurriculumStatus());
		assertNull(reloaded.getCurriculumFinalisedAt());
	}
}
