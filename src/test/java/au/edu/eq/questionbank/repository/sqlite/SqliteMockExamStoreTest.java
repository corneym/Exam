package au.edu.eq.questionbank.repository.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.AnswerFile;
import au.edu.eq.questionbank.model.AnswerRegion;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.SharedContextStatus;
import au.edu.eq.questionbank.model.SourceQuestion;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.pdf.PrintableExamSettings;
import au.edu.eq.questionbank.repository.assessment.SqliteAnswerWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteExamImporter;
import au.edu.eq.questionbank.repository.assessment.SqliteExamWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteQuestionRepository;
import au.edu.eq.questionbank.repository.assessment.SqliteSourceQuestionRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
import au.edu.eq.questionbank.repository.sqlite.SqliteSubjectImportLedger.PackageEntry;
import au.edu.eq.questionbank.repository.sqlite.SqliteSubjectImportLedger.PackageOutcome;
import au.edu.eq.questionbank.ui.model.ExamBuilderDraft;
import au.edu.eq.questionbank.ui.model.ExamBuilderDraftFile;
import au.edu.eq.questionbank.ui.model.SqliteMockExamStore;

class SqliteMockExamStoreTest {

	private static final Clock CLOCK_2026 = Clock.fixed(Instant.parse("2026-12-31T01:00:00Z"),
			ZoneId.of("Australia/Brisbane"));
	private static final Clock CLOCK_2027 = Clock.fixed(Instant.parse("2027-01-01T01:00:00Z"),
			ZoneId.of("Australia/Brisbane"));
	@TempDir
	Path tempDirectory;

	@Test
	void draftDeletionDoesNotCreateReservations() throws Exception {
		Fixture fixture = createFixture();
		SqliteMockExamStore store = new SqliteMockExamStore(fixture.database(), CLOCK_2026);
		ExamBuilderDraft draft = new ExamBuilderDraft(fixture.subject());
		var section = ExamBuilderDraft.Section.PAPER_ONE_WRITTEN;
		assertTrue(draft.addWholeQuestion(section, fixture.partB(), fixture.corpus()));
		store.saveDraft(draft, settings(2026), true);
		assertEquals(List.of(draft.getExaminationId()), store.findExaminationIds(fixture.subject()));
		assertTrue(store.lockedQuestionIds(fixture.subject(), 2026).isEmpty());
		assertTrue(store.deleteDraft(draft.getExaminationId(), fixture.subject()));
		assertTrue(store.find(draft.getExaminationId(), fixture.subject()).isEmpty());
		assertTrue(store.findExaminationIds(fixture.subject()).isEmpty());
		assertEquals(0, countReservations(fixture.database(), false));
	}

	@Test
	void examinationSummariesCountMultipartQuestionsOnce() throws Exception {
		Fixture fixture = createFixture();
		SqliteMockExamStore store = new SqliteMockExamStore(fixture.database(), CLOCK_2026);
		var section = ExamBuilderDraft.Section.PAPER_ONE_WRITTEN;
		ExamBuilderDraft draft = new ExamBuilderDraft(fixture.subject());
		assertTrue(draft.addWholeQuestion(section, fixture.partB(), fixture.corpus()));
		assertTrue(draft.addWholeQuestion(section, fixture.standalone(), fixture.corpus()));
		store.saveDraft(draft, settings(2026), true);
		var summary = store.findExaminationSummaries(fixture.subject()).getFirst();
		assertEquals(draft.getExaminationId(), summary.examinationId());
		assertEquals(2026, summary.cohortYear());
		assertEquals(2, summary.questionCount());
		assertEquals(9, summary.totalMarks());
		assertFalse(summary.isFinalised());
		assertNull(summary.finalisedAt());
		store.finalise(draft, settings(2026), true);
		summary = store.findExaminationSummaries(fixture.subject()).getFirst();
		assertTrue(summary.isFinalised());
		assertEquals(CLOCK_2026.instant(), summary.finalisedAt());
		assertEquals(2, summary.questionCount());
		assertEquals(9, summary.totalMarks());
		ExamBuilderDraft empty = new ExamBuilderDraft(fixture.subject());
		store.saveDraft(empty, settings(2027), true);
		var summaries = store.findExaminationSummaries(fixture.subject());
		assertEquals(2, summaries.size());
		assertEquals(empty.getExaminationId(), summaries.getFirst().examinationId());
		assertEquals(0, summaries.getFirst().questionCount());
		assertEquals(0, summaries.getFirst().totalMarks());
		assertEquals(draft.getExaminationId(), summaries.getLast().examinationId());
		assertTrue(summaries.getLast().isFinalised());
	}

	@Test
	void failedFinalisationRollsBackReservationsAndDraftChanges() throws Exception {
		Fixture fixture = createFixture();
		SqliteMockExamStore store = new SqliteMockExamStore(fixture.database(), CLOCK_2026);
		ExamBuilderDraft draft = new ExamBuilderDraft(fixture.subject());
		var section = ExamBuilderDraft.Section.PAPER_ONE_WRITTEN;
		assertTrue(draft.addWholeQuestion(section, fixture.partA(), fixture.corpus()));
		store.saveDraft(draft, settings(2026), true);
		assertTrue(draft.addWholeQuestion(section, fixture.standalone(), fixture.corpus()));
		try (Connection connection = fixture.database().openConnection();
				Statement statement = connection.createStatement()) {
			statement.execute("""
					CREATE TRIGGER test_reject_source_reservation
					BEFORE INSERT ON mock_exam_reservations
					WHEN NEW.entity_type = 'SOURCE_QUESTION'
					BEGIN
					    SELECT RAISE(
					        ABORT,
					        'Injected SourceQuestion reservation failure'
					    );
					END
					""");
		}
		assertThrows(SQLException.class, () -> store.finalise(draft, settings(2026), true));
		assertFalse(draft.isFinalised());
		assertEquals(3, draft.getTotalSelectedCount());
		assertEquals(0, countReservations(fixture.database(), false));
		assertTrue(store.lockedQuestionIds(fixture.subject(), 2026).isEmpty());

		// The failed transaction must preserve the previously
		// saved draft rather than publishing its unsaved additions.
		var saved = store.find(draft.getExaminationId(), fixture.subject()).orElseThrow();
		assertFalse(saved.draft().isFinalised());
		assertEquals(List.of(fixture.partA().getId(), fixture.partB().getId()), selectedIds(saved.draft(), section));
		try (Connection connection = fixture.database().openConnection();
				Statement statement = connection.createStatement()) {
			statement.execute("DROP TRIGGER test_reject_source_reservation");
		}
		store.finalise(draft, settings(2026), true);
		assertTrue(draft.isFinalised());
		assertEquals(4, countReservations(fixture.database(), false));
		assertEquals(Set.of(fixture.partA().getId(), fixture.partB().getId(), fixture.standalone().getId()),
				store.lockedQuestionIds(fixture.subject(), 2026));
		fixture.database().verifySchema();
		fixture.database().verifyIntegrity();
	}

	@Test
	void finalisationRejectsPartialMultipartSelections() throws Exception {
		Fixture fixture = createFixture();
		SqliteMockExamStore store = new SqliteMockExamStore(fixture.database(), CLOCK_2026);
		ExamBuilderDraft draft = new ExamBuilderDraft(fixture.subject());
		var section = ExamBuilderDraft.Section.PAPER_ONE_WRITTEN;

		// Simulate an incomplete corpus supplied by a caller.
		assertTrue(draft.addWholeQuestion(section, fixture.partA(), List.of(fixture.partA())));
		assertThrows(IllegalStateException.class, () -> store.finalise(draft, settings(2026), true));
		assertFalse(draft.isFinalised());
		assertTrue(store.find(draft.getExaminationId(), fixture.subject()).isEmpty());
		assertEquals(0, countReservations(fixture.database(), false));
	}

	@Test
	void finalisedDefinitionsAndSelectionsAreImmutable() throws Exception {
		Fixture fixture = createFixture();
		SqliteMockExamStore store = new SqliteMockExamStore(fixture.database(), CLOCK_2026);
		ExamBuilderDraft draft = new ExamBuilderDraft(fixture.subject());
		var paperOne = ExamBuilderDraft.Section.PAPER_ONE_WRITTEN;
		var paperTwo = ExamBuilderDraft.Section.PAPER_TWO_WRITTEN;
		assertTrue(draft.addWholeQuestion(paperOne, fixture.partA(), fixture.corpus()));
		store.finalise(draft, settings(2026), true);
		assertTrue(draft.isFinalised());
		assertFalse(draft.remove(paperOne, fixture.partA().getId()));
		assertFalse(draft.transfer(paperOne, paperTwo, fixture.partB().getId()));
		assertFalse(draft.move(paperOne, fixture.partA().getId(), 1));
		assertFalse(draft.addWholeQuestion(paperOne, fixture.standalone(), fixture.corpus()));
		assertFalse(draft.add(paperOne, fixture.standalone()));
		assertFalse(draft.addSelections(paperOne, List.of(fixture.standalone()), fixture.corpus()));
		assertThrows(IllegalStateException.class, draft::clear);
		ExamBuilderDraft stale = new ExamBuilderDraft(fixture.subject(), draft.getExaminationId());
		assertTrue(stale.addWholeQuestion(paperOne, fixture.standalone(), fixture.corpus()));
		assertThrows(IllegalStateException.class, () -> draft.replaceWith(stale));
		assertThrows(IllegalStateException.class, () -> store.saveDraft(stale, settings(2026), true));
		assertThrows(IllegalStateException.class, () -> store.finalise(stale, settings(2026), true));
		assertThrows(IllegalStateException.class, () -> store.deleteDraft(draft.getExaminationId(), fixture.subject()));

		// Verify the database protections independently
		// of the Java lifecycle boundary.
		try (Connection connection = fixture.database().openConnection();
				Statement statement = connection.createStatement()) {
			assertThrows(SQLException.class, () -> statement.executeUpdate("""
					UPDATE mock_examinations
					SET cohort_year = 2027
					"""));
			assertThrows(SQLException.class, () -> statement.executeUpdate("""
					DELETE FROM mock_exam_selections
					"""));
			assertThrows(SQLException.class, () -> statement.executeUpdate("""
					DELETE FROM mock_exam_reservations
					"""));
			assertThrows(SQLException.class, () -> statement.executeUpdate("""
					DELETE FROM mock_examinations
					"""));
		}
		assertEquals(3, countReservations(fixture.database(), false));
		var persisted = store.find(draft.getExaminationId(), fixture.subject()).orElseThrow();
		assertTrue(persisted.draft().isFinalised());
		assertEquals(List.of(fixture.partA().getId(), fixture.partB().getId()),
				selectedIds(persisted.draft(), paperOne));
		fixture.database().verifyIntegrity();
	}

	@Test
	void futureConstituentOfReservedSourceQuestionIsAlsoLocked() throws Exception {
		Fixture fixture = createFixture();
		SqliteMockExamStore store = new SqliteMockExamStore(fixture.database(), CLOCK_2026);
		ExamBuilderDraft draft = new ExamBuilderDraft(fixture.subject());
		var section = ExamBuilderDraft.Section.PAPER_ONE_WRITTEN;
		assertTrue(draft.addWholeQuestion(section, fixture.partA(), fixture.corpus()));
		store.finalise(draft, settings(2026), true);
		Question additionalPart = createWrittenQuestion(fixture.database(), fixture.booklet(), fixture.subtopic(),
				fixture.source(), fixture.answerFile(), "21c", 4);
		assertEquals(Set.of(fixture.partA().getId(), fixture.partB().getId(), additionalPart.getId()),
				store.lockedQuestionIds(fixture.subject(), 2026));
		List<Question> expandedCorpus = new ArrayList<>(fixture.corpus());
		expandedCorpus.add(additionalPart);
		ExamBuilderDraft later = new ExamBuilderDraft(fixture.subject());
		assertTrue(later.addWholeQuestion(section, additionalPart, expandedCorpus));
		assertThrows(IllegalStateException.class, () -> store.finalise(later, settings(2026), true));

		// Historical reopening must reproduce the original
		// two constituents without expanding the examination.
		var historical = store.find(draft.getExaminationId(), fixture.subject()).orElseThrow();
		assertTrue(historical.draft().isFinalised());
		assertEquals(List.of(fixture.partA().getId(), fixture.partB().getId()),
				selectedIds(historical.draft(), section));
		assertEquals(5, historical.draft().getTotalMarks(section));
		store.finalise(draft, settings(2026), true);
		assertEquals(3, countReservations(fixture.database(), false));
	}

	@Test
	void importedSubjectCannotCreateOrReleaseReservations() throws Exception {
		Fixture fixture = createFixture();
		SqliteMockExamStore store = new SqliteMockExamStore(fixture.database(), CLOCK_2026);
		var section = ExamBuilderDraft.Section.PAPER_ONE_WRITTEN;
		ExamBuilderDraft draft = new ExamBuilderDraft(fixture.subject());
		assertTrue(draft.addWholeQuestion(section, fixture.partA(), fixture.corpus()));
		var ledger = new SqliteSubjectImportLedger(fixture.database());
		var authority = new SqliteImportedSubjectAuthorityRepository(fixture.database());
		var entry = new PackageEntry(UUID.randomUUID(), "a".repeat(64), UUID.randomUUID(), UUID.randomUUID(),
				"b".repeat(64), "c".repeat(64), PackageOutcome.APPLIED,
				tempDirectory.resolve("pre-import.zip").toString(), "Imported Subject fixture");
		try (Connection connection = fixture.database().openConnection()) {
			connection.setAutoCommit(false);
			assertTrue(ledger.recordPackage(connection, entry));
			authority.recordImported(connection, fixture.subject().getId(), entry.packageId());
			connection.commit();
		}
		assertThrows(IllegalStateException.class, () -> store.saveDraft(draft, settings(2026), true));
		assertThrows(IllegalStateException.class, () -> store.finalise(draft, settings(2026), true));
		assertThrows(IllegalStateException.class, () -> store.resetCurrentYear(fixture.subject()));
		assertThrows(IllegalStateException.class, () -> store.deleteDraft(draft.getExaminationId(), fixture.subject()));
		assertFalse(draft.isFinalised());
		assertEquals(0, countReservations(fixture.database(), false));
	}

	@Test
	void overlappingDraftsCanBeSavedButOnlyOneCanFinalise() throws Exception {
		Fixture fixture = createFixture();
		SqliteMockExamStore store = new SqliteMockExamStore(fixture.database(), CLOCK_2026);
		ExamBuilderDraft first = new ExamBuilderDraft(fixture.subject());
		ExamBuilderDraft second = new ExamBuilderDraft(fixture.subject());
		var section = ExamBuilderDraft.Section.PAPER_ONE_WRITTEN;
		assertNotEquals(first.getExaminationId(), second.getExaminationId());
		assertTrue(first.addWholeQuestion(section, fixture.partB(), fixture.corpus()));

		// Select the unrelated Question first to detect
		// partial reservations if a later conflict occurs.
		assertTrue(second.addWholeQuestion(section, fixture.standalone(), fixture.corpus()));
		assertTrue(second.addWholeQuestion(section, fixture.partA(), fixture.corpus()));
		store.saveDraft(first, settings(2026), true);
		store.saveDraft(second, settings(2026), true);
		assertTrue(store.lockedQuestionIds(fixture.subject(), 2026).isEmpty());
		store.finalise(first, settings(2026), true);
		assertTrue(first.isFinalised());
		Set<Long> multipartIds = Set.of(fixture.partA().getId(), fixture.partB().getId());
		assertEquals(multipartIds, store.lockedQuestionIds(fixture.subject(), 2026));
		assertEquals(3, countReservations(fixture.database(), false));

		// Simulate reopening the application.
		SqliteMockExamStore reopened = new SqliteMockExamStore(fixture.database(), CLOCK_2026);
		var persistedFirst = reopened.find(first.getExaminationId(), fixture.subject()).orElseThrow();
		assertTrue(persistedFirst.draft().isFinalised());
		assertEquals(first.getExaminationId(), persistedFirst.draft().getExaminationId());
		assertEquals(settings(2026), persistedFirst.settings());
		assertEquals(CLOCK_2026.instant(), persistedFirst.finalisedAt());
		assertFalse(reopened.find(second.getExaminationId(), fixture.subject()).orElseThrow().draft().isFinalised());
		IllegalStateException conflict = assertThrows(IllegalStateException.class,
				() -> reopened.finalise(second, settings(2026), true));
		assertTrue(conflict.getMessage().contains("already reserved"));
		assertFalse(second.isFinalised());
		assertEquals(3, countReservations(fixture.database(), false));
		assertFalse(reopened.lockedQuestionIds(fixture.subject(), 2026).contains(fixture.standalone().getId()));

		// Bypass Java prevalidation to verify that SQLite
		// independently rejects conflicting finalisation.
		try (Connection connection = fixture.database().openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						UPDATE mock_examinations
						SET finalised_at = ?
						WHERE exam_id = ?
						""")) {
			statement.setString(1, CLOCK_2026.instant().toString());
			statement.setString(2, second.getExaminationId().toString());
			assertThrows(SQLException.class, statement::executeUpdate);
		}
		assertEquals(3, countReservations(fixture.database(), false));
		assertFalse(reopened.find(second.getExaminationId(), fixture.subject()).orElseThrow().draft().isFinalised());
		assertTrue(second.remove(section, fixture.partB().getId()));
		reopened.finalise(second, settings(2026), true);
		assertTrue(second.isFinalised());
		assertEquals(Set.of(fixture.partA().getId(), fixture.partB().getId(), fixture.standalone().getId()),
				reopened.lockedQuestionIds(fixture.subject(), 2026));
		assertEquals(4, countReservations(fixture.database(), false));
		fixture.database().verifySchema();
		fixture.database().verifyIntegrity();
	}

	@Test
	void paperTwoExclusionRejectsExistingPaperTwoSelections() throws Exception {
		Fixture fixture = createFixture();
		SqliteMockExamStore store = new SqliteMockExamStore(fixture.database(), CLOCK_2026);
		ExamBuilderDraft draft = new ExamBuilderDraft(fixture.subject());
		assertTrue(
				draft.addWholeQuestion(ExamBuilderDraft.Section.PAPER_TWO_WRITTEN, fixture.partA(), fixture.corpus()));
		assertThrows(IllegalArgumentException.class, () -> store.saveDraft(draft, settings(2026), false));
		assertThrows(IllegalArgumentException.class, () -> store.finalise(draft, settings(2026), false));
		assertTrue(store.find(draft.getExaminationId(), fixture.subject()).isEmpty());
		assertEquals(0, countReservations(fixture.database(), false));
	}

	@Test
	void reservationsAreIsolatedBySubject() throws Exception {
		Fixture fixture = createFixture();
		SqliteMockExamStore store = new SqliteMockExamStore(fixture.database(), CLOCK_2026);
		Subject physics = new SqliteCurriculumWriter(fixture.database()).insertSubject("Physics");
		ExamBuilderDraft draft = new ExamBuilderDraft(fixture.subject());
		assertTrue(
				draft.addWholeQuestion(ExamBuilderDraft.Section.PAPER_ONE_WRITTEN, fixture.partA(), fixture.corpus()));
		store.finalise(draft, settings(2026), true);
		assertTrue(store.lockedQuestionIds(physics, 2026).isEmpty());
		assertEquals(0, store.resetCurrentYear(physics));
		assertEquals(Set.of(fixture.partA().getId(), fixture.partB().getId()),
				store.lockedQuestionIds(fixture.subject(), 2026));
		assertThrows(IllegalStateException.class, () -> store.find(draft.getExaminationId(), physics));
	}

	@Test
	void resetAndYearRolloverPreserveHistoricalReservations() throws Exception {
		Fixture fixture = createFixture();
		SqliteMockExamStore store2026 = new SqliteMockExamStore(fixture.database(), CLOCK_2026);
		SqliteMockExamStore store2027 = new SqliteMockExamStore(fixture.database(), CLOCK_2027);
		var section = ExamBuilderDraft.Section.PAPER_ONE_WRITTEN;
		ExamBuilderDraft first = new ExamBuilderDraft(fixture.subject());
		assertTrue(first.addWholeQuestion(section, fixture.partA(), fixture.corpus()));
		store2026.finalise(first, settings(2026), true);
		Set<Long> multipartIds = Set.of(fixture.partA().getId(), fixture.partB().getId());
		assertEquals(multipartIds, store2026.lockedQuestionIds(fixture.subject(), 2026));
		assertTrue(store2027.lockedQuestionIds(fixture.subject(), 2027).isEmpty());
		ExamBuilderDraft second = new ExamBuilderDraft(fixture.subject());
		assertTrue(second.addWholeQuestion(section, fixture.partB(), fixture.corpus()));
		store2027.finalise(second, settings(2027), true);
		assertEquals(multipartIds, store2027.lockedQuestionIds(fixture.subject(), 2027));
		assertEquals(6, countReservations(fixture.database(), false));

		// Release only the current Subject and calendar year.
		assertEquals(3, store2027.resetCurrentYear(fixture.subject()));
		assertTrue(store2027.lockedQuestionIds(fixture.subject(), 2027).isEmpty());
		assertEquals(multipartIds, store2027.lockedQuestionIds(fixture.subject(), 2026));
		assertEquals(6, countReservations(fixture.database(), false));
		assertEquals(3, countReservations(fixture.database(), true));

		// Repeating finalisation must not reactivate released usage.
		store2027.finalise(second, settings(2027), true);
		assertTrue(store2027.lockedQuestionIds(fixture.subject(), 2027).isEmpty());
		assertEquals(0, store2027.resetCurrentYear(fixture.subject()));
		ExamBuilderDraft third = new ExamBuilderDraft(fixture.subject());
		assertTrue(third.addWholeQuestion(section, fixture.partA(), fixture.corpus()));
		store2027.finalise(third, settings(2027), true);
		assertEquals(multipartIds, store2027.lockedQuestionIds(fixture.subject(), 2027));
		assertEquals(9, countReservations(fixture.database(), false));
		assertEquals(3, countReservations(fixture.database(), true));
		assertTrue(store2027.find(second.getExaminationId(), fixture.subject()).orElseThrow().draft().isFinalised());
		fixture.database().verifyIntegrity();
	}

	@Test
	void resetRejectsConfirmationForAnEarlierCalendarYear() throws Exception {
		Fixture fixture = createFixture();
		SqliteMockExamStore store = new SqliteMockExamStore(fixture.database(), CLOCK_2027);
		ExamBuilderDraft draft = new ExamBuilderDraft(fixture.subject());
		assertTrue(
				draft.addWholeQuestion(ExamBuilderDraft.Section.PAPER_ONE_WRITTEN, fixture.partA(), fixture.corpus()));
		store.finalise(draft, settings(2027), true);
		assertEquals(2027, store.currentYear());
		IllegalStateException failure = assertThrows(IllegalStateException.class,
				() -> store.resetCurrentYear(fixture.subject(), 2026));
		assertTrue(failure.getMessage().contains("calendar year has changed"));
		assertEquals(Set.of(fixture.partA().getId(), fixture.partB().getId()),
				store.lockedQuestionIds(fixture.subject(), 2027));
		assertEquals(0, countReservations(fixture.database(), true));
		assertEquals(3, store.resetCurrentYear(fixture.subject(), 2027));
		assertTrue(store.lockedQuestionIds(fixture.subject(), 2027).isEmpty());
		assertEquals(3, countReservations(fixture.database(), true));
	}

	@Test
	void staleExternalDraftReopensAsAuthoritativeFinalisedExam() throws Exception {
		Fixture fixture = createFixture();
		SqliteMockExamStore store = new SqliteMockExamStore(fixture.database(), CLOCK_2026);
		SqlitePortableIdentityRepository identities = new SqlitePortableIdentityRepository(fixture.database());
		SqliteQuestionRepository questions = new SqliteQuestionRepository(fixture.database());
		ExamBuilderDraftFile files = new ExamBuilderDraftFile(identities, questions, store);
		ExamBuilderDraft draft = new ExamBuilderDraft(fixture.subject());
		var section = ExamBuilderDraft.Section.PAPER_ONE_WRITTEN;
		assertTrue(draft.addWholeQuestion(section, fixture.partA(), fixture.corpus()));
		Path staleFile = tempDirectory.resolve("before-finalisation.exam.json");
		files.save(staleFile, draft, settings(2025));
		var editable = files.load(staleFile, fixture.subject());
		assertEquals(draft.getExaminationId(), editable.draft().getExaminationId());
		assertFalse(editable.draft().isFinalised());
		store.finalise(draft, settings(2026), true);
		var loaded = files.load(staleFile, fixture.subject());
		assertTrue(loaded.draft().isFinalised());
		assertEquals(draft.getExaminationId(), loaded.draft().getExaminationId());
		assertEquals(2026, loaded.settings().year());
		assertEquals(List.of(fixture.partA().getId(), fixture.partB().getId()), selectedIds(loaded.draft(), section));
		Path committedFile = tempDirectory.resolve("finalised.exam.json");
		files.save(committedFile, draft, settings(2026));
		ExamBuilderDraftFile draftOnlyFiles = new ExamBuilderDraftFile(identities, questions);

		// The JSON marker alone is not authoritative.
		assertThrows(IOException.class, () -> draftOnlyFiles.load(committedFile, fixture.subject()));
		assertTrue(files.load(committedFile, fixture.subject()).draft().isFinalised());
	}

	@Test
	void version50UpgradePreservesCorpusAndPortableIdentities() throws Exception {
		Fixture fixture = createFixture();
		SqliteDatabase database = fixture.database();
		var identities = new SqlitePortableIdentityRepository(database);
		var subjectIdentity = identities.requireByLocalId(EntityType.SUBJECT, fixture.subject().getId());
		var sourceIdentity = identities.requireByLocalId(EntityType.SOURCE_QUESTION, fixture.source().getId());
		var firstIdentity = identities.requireByLocalId(EntityType.QUESTION, fixture.partA().getId());
		var secondIdentity = identities.requireByLocalId(EntityType.QUESTION, fixture.partB().getId());
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			HistoricalSchemaFixture.removeVersion51Objects(statement);
			assertEquals(1, statement.executeUpdate("""
					UPDATE schema_version
					SET version = 50
					WHERE version = 51
					"""));
		}
		database.verifySchema();
		database.verifyMigrationCompatibility();

		// Compatibility checking must not modify the live database.
		assertEquals(50, database.schemaVersion());
		database.initialiseSchema();
		assertEquals(51, database.schemaVersion());
		assertEquals(subjectIdentity, identities.requireByLocalId(EntityType.SUBJECT, fixture.subject().getId()));
		assertEquals(sourceIdentity, identities.requireByLocalId(EntityType.SOURCE_QUESTION, fixture.source().getId()));
		assertEquals(firstIdentity, identities.requireByLocalId(EntityType.QUESTION, fixture.partA().getId()));
		assertEquals(secondIdentity, identities.requireByLocalId(EntityType.QUESTION, fixture.partB().getId()));
		var questions = new SqliteQuestionRepository(database);
		Question restoredA = questions.findById(fixture.partA().getId()).orElseThrow();
		Question restoredB = questions.findById(fixture.partB().getId()).orElseThrow();
		assertEquals(fixture.source().getId(), restoredA.getSourceQuestion().getId());
		assertEquals(fixture.source().getId(), restoredB.getSourceQuestion().getId());
		assertEquals(2, restoredA.getMarks());
		assertEquals(3, restoredB.getMarks());
		assertTrue(restoredA.hasAnswer());
		assertTrue(restoredB.hasAnswer());
		assertTrue(new SqliteMockExamStore(database, CLOCK_2026).lockedQuestionIds(fixture.subject(), 2026).isEmpty());
		database.verifySchema();
		database.verifyIntegrity();
	}

	private int countReservations(SqliteDatabase database, boolean releasedOnly) throws SQLException {
		String sql = releasedOnly ? """
				SELECT COUNT(*)
				FROM mock_exam_reservations
				WHERE released_at IS NOT NULL
				""" : "SELECT COUNT(*) FROM mock_exam_reservations";
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery(sql)) {
			assertTrue(rows.next());
			return rows.getInt(1);
		}
	}

	private Fixture createFixture() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("mock-exams.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter curriculum = new SqliteCurriculumWriter(database);
		Subject subject = curriculum.insertSubject("Chemistry");
		SyllabusVersion syllabus = curriculum.insertSyllabusVersion(subject, "2025", true);
		Unit unit = curriculum.insertUnit(syllabus, "3", "Unit 3", 1);
		Topic topic = curriculum.insertTopic(unit, "3.1", "Equilibrium", 1);
		Subtopic subtopic = curriculum.insertSubtopic(topic, "3.1.1", "Equilibrium", 1);
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		ExamBooklet booklet = new SqliteExamImporter(database, examWriter).importExam(subject, "QCAA", 2025,
				"External Assessment", "Paper 1", "questions.pdf");
		SqliteSourceQuestionRepository sources = new SqliteSourceQuestionRepository(database);
		SourceQuestion source = sources.save(booklet, "21");
		source = sources.updateSharedContextStatus(source, SharedContextStatus.NONE);
		SqliteAnswerWriter answers = new SqliteAnswerWriter(database, examWriter);
		AnswerFile answerFile = answers.findOrCreateAnswerFile(booklet, "Marking guide", "answers.pdf");
		Question partA = createWrittenQuestion(database, booklet, subtopic, source, answerFile, "21a", 2);
		Question partB = createWrittenQuestion(database, booklet, subtopic, source, answerFile, "21b", 3);
		Question standalone = createWrittenQuestion(database, booklet, subtopic, null, answerFile, "22", 4);
		return new Fixture(database, subject, booklet, subtopic, source, answerFile, partA, partB, standalone);
	}

	private Question createWrittenQuestion(SqliteDatabase database, ExamBooklet booklet, Subtopic subtopic,
			SourceQuestion source, AnswerFile answerFile, String code, int marks) throws SQLException {
		SqliteQuestionRepository questions = new SqliteQuestionRepository(database);
		Question question = questions.save(booklet, code, "", marks,
				List.of(new QuestionRegion(booklet, 1, 0.1, 0.1, 0.8, 0.2)), subtopic, false, source, null,
				QuestionResponseType.WRITTEN_RESPONSE);
		SqliteAnswerWriter answers = new SqliteAnswerWriter(database, new SqliteExamWriter(database));
		answers.insertAnswer(question, null, List.of(new AnswerRegion(answerFile, 1, 0.1, 0.1, 0.8, 0.2)));
		return questions.findById(question.getId()).orElseThrow();
	}

	private List<Long> selectedIds(ExamBuilderDraft draft, ExamBuilderDraft.Section section) {
		return draft.getQuestions(section).stream().map(Question::getId).toList();
	}

	private PrintableExamSettings settings(int year) {
		return new PrintableExamSettings("Chemistry", year, 5, 60, 5, 75);
	}

	private record Fixture(SqliteDatabase database, Subject subject, ExamBooklet booklet, Subtopic subtopic,
			SourceQuestion source, AnswerFile answerFile, Question partA, Question partB, Question standalone) {

		List<Question> corpus() {
			return List.of(partA, partB, standalone);
		}
	}
}
