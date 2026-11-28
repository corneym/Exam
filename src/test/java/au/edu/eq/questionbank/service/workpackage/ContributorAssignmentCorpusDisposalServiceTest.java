package au.edu.eq.questionbank.service.workpackage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.ManagedAssetPublicationLock;
import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.repository.sqlite.SqliteImportedSubjectAuthorityRepository;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.repository.sqlite.SqliteSubjectImportLedger;
import au.edu.eq.questionbank.repository.sqlite.SqliteSubjectImportLedger.PackageEntry;
import au.edu.eq.questionbank.repository.sqlite.SqliteSubjectImportLedger.PackageOutcome;
import au.edu.eq.questionbank.service.backup.BackupRequest;
import au.edu.eq.questionbank.service.backup.DefaultBackupService;
import au.edu.eq.questionbank.service.backup.DefaultRestoreExecutor;
import au.edu.eq.questionbank.service.backup.DefaultRestoreService;
import au.edu.eq.questionbank.service.workpackage.ContributorAssignmentCorpusDisposalService.FaultPoint;
import au.edu.eq.questionbank.service.workpackage.ContributorAssignmentCorpusDisposalService.State;

class ContributorAssignmentCorpusDisposalServiceTest {

	@TempDir
	Path directory;

	@Test
	void submittedExclusiveExamReleasesDatabaseAndManagedFiles() throws Exception {
		Fixture fixture = fixture("exclusive");
		Path question = fixture.config().dataRoot().resolve(WorkAssignmentArchiveFixture.QUESTION_PATH);
		Path answer = fixture.config().dataRoot().resolve(WorkAssignmentArchiveFixture.ANSWER_PATH);
		var result = fixture.service().releaseSubmitted(fixture.assignmentId());
		assertEquals(State.VERIFIED, result.state());
		assertTrue(result.entityCount() > 0);
		assertEquals(2, result.assetCount());
		assertEquals(0, count(fixture.database(), "SELECT COUNT(*) FROM contributor_assignment_sessions"));
		assertEquals(0, count(fixture.database(), "SELECT COUNT(*) FROM exams"));
		assertEquals(0, count(fixture.database(), "SELECT COUNT(*) FROM source_documents"));
		assertEquals(1, count(fixture.database(), "SELECT COUNT(*) FROM subjects"));
		assertEquals(0, count(fixture.database(), "SELECT COUNT(*) FROM syllabus_versions"));
		assertEquals(0, count(fixture.database(), "SELECT COUNT(*) FROM curriculum_nodes"));
		assertEquals(1, count(fixture.database(), "SELECT COUNT(*) FROM exam_providers"));
		assertEquals(result.entityCount(),
				count(fixture.database(), "SELECT COUNT(*) FROM portable_entity_cache_evictions"));
		assertEquals(0, count(fixture.database(), "SELECT COUNT(*) FROM portable_entity_retirements"));
		assertFalse(Files.exists(question));
		assertFalse(Files.exists(answer));
		fixture.database().verifySchema();
		fixture.database().verifyIntegrity();
		var replay = fixture.service().releaseSubmitted(fixture.assignmentId());
		assertEquals(result, replay);
	}

	@Test
	void finalisedMockExamReservationSurvivesQuestionCacheEviction() throws Exception {
		Fixture fixture = fixture("mock-reservation-history");
		UUID mockExamId = UUID.randomUUID();
		String subjectPortableId = scalar(fixture.database(), """
				SELECT portable_id
				FROM portable_entity_identities
				WHERE entity_type = 'SUBJECT'
				ORDER BY local_id
				LIMIT 1
				""");
		String questionPortableId = scalar(fixture.database(), """
				SELECT portable_id
				FROM portable_entity_identities
				WHERE entity_type = 'QUESTION'
				ORDER BY local_id
				LIMIT 1
				""");
		execute(fixture.database(), """
				INSERT INTO mock_examinations (
				    exam_id, subject_portable_id, subject_name, cohort_year,
				    paper_one_perusal_minutes, paper_one_working_minutes,
				    paper_two_perusal_minutes, paper_two_working_minutes,
				    include_paper_two
				)
				VALUES ('%s', '%s', 'Chemistry', 2027, 5, 90, 5, 90, 0)
				""".formatted(mockExamId, subjectPortableId));
		execute(fixture.database(), """
				INSERT INTO mock_exam_selections (
				    exam_id, section, position, question_portable_id,
				    source_question_portable_id, question_code, marks
				)
				VALUES ('%s', 'PAPER_ONE_WRITTEN', 0, '%s', NULL, 'Q1', 1)
				""".formatted(mockExamId, questionPortableId));
		execute(fixture.database(), """
				UPDATE mock_examinations
				SET finalised_at = '%s'
				WHERE exam_id = '%s'
				""".formatted(Instant.now(), mockExamId));
		assertEquals(State.VERIFIED, fixture.service().releaseSubmitted(fixture.assignmentId()).state());
		assertEquals(0, count(fixture.database(), "SELECT COUNT(*) FROM questions"));
		assertEquals(1, count(fixture.database(), """
				SELECT COUNT(*)
				FROM mock_exam_reservations
				WHERE exam_id = '%s'
				  AND entity_type = 'QUESTION'
				  AND entity_portable_id = '%s'
				  AND released_at IS NULL
				""".formatted(mockExamId, questionPortableId)));
		assertEquals(1, count(fixture.database(), """
				SELECT COUNT(*)
				FROM portable_entity_cache_evictions
				WHERE portable_id = '%s'
				  AND entity_type = 'QUESTION'
				  AND reattachable = 1
				""".formatted(questionPortableId)));
		fixture.database().verifySchema();
		fixture.database().verifyIntegrity();
	}

	@Test
	void faultDuringDatabaseReleaseRollsBackToExactPlanAndRetryCompletes() throws Exception {
		Fixture fixture = fixture("release-rollback");
		RetiredIdentity retired = retireAssignedBooklet(fixture.database());
		var failing = new ContributorAssignmentCorpusDisposalService(fixture.database(),
				new ManagedDataLayout(fixture.config().dataRoot()), point -> {
					if (point == FaultPoint.AFTER_GRAPH_DELETE) {
						throw new IOException("simulated release interruption");
					}
				});
		assertThrows(IOException.class, () -> failing.releaseSubmitted(fixture.assignmentId()));
		assertEquals("PLANNED", scalar(fixture.database(), "SELECT state FROM corpus_disposal_operations"));
		assertEquals(1, count(fixture.database(), "SELECT COUNT(*) FROM contributor_assignment_sessions"));
		assertEquals(1, count(fixture.database(), "SELECT COUNT(*) FROM exams"));
		assertEquals(1, count(fixture.database(), """
				SELECT COUNT(*)
				FROM portable_entity_retirements
				WHERE portable_id = '%s'
				""".formatted(retired.portableId())));
		assertEquals(0, count(fixture.database(), "SELECT COUNT(*) FROM portable_entity_cache_evictions"));
		var recovered = fixture.service().releaseSubmitted(fixture.assignmentId());
		assertEquals(State.VERIFIED, recovered.state());
		assertEquals(0, count(fixture.database(), "SELECT COUNT(*) FROM exams"));
		assertEquals(0, count(fixture.database(), "SELECT COUNT(*) FROM portable_entity_retirements"));
		fixture.database().verifySchema();
		fixture.database().verifyIntegrity();
	}

	@Test
	void assignmentOwnedRetirementBecomesReattachableEvictionEvidence() throws Exception {
		Fixture fixture = fixture("retired-assignment-entity");
		RetiredIdentity retired = retireAssignedBooklet(fixture.database());
		assertEquals(1, count(fixture.database(), "SELECT COUNT(*) FROM portable_entity_retirements"));
		var result = fixture.service().releaseSubmitted(fixture.assignmentId());
		assertEquals(State.VERIFIED, result.state());
		assertEquals(0, count(fixture.database(), "SELECT COUNT(*) FROM portable_entity_retirements"));
		assertEquals(1, count(fixture.database(), "SELECT COUNT(*) FROM corpus_disposal_retired_entities"));
		assertEquals(1, count(fixture.database(), """
				SELECT COUNT(*)
				FROM portable_entity_cache_evictions
				WHERE portable_id = '%s'
				  AND entity_type = 'EXAM_BOOKLET'
				  AND former_local_id = %d
				  AND latest_revision_id = '%s'
				  AND authority_installation_id = '%s'
				  AND reattachable = 1
				""".formatted(retired.portableId(), retired.localId(), retired.latestRevisionId(),
				retired.authorityInstallationId())));
		assertEquals(1, count(fixture.database(), """
				SELECT COUNT(*)
				FROM portable_revision_history
				WHERE revision_id = '%s'
				  AND portable_id = '%s'
				  AND entity_type = 'EXAM_BOOKLET'
				""".formatted(retired.baselineRevisionId(), retired.portableId())));
		assertEquals(1, count(fixture.database(), """
				SELECT COUNT(*)
				FROM portable_revision_history
				WHERE revision_id = '%s'
				  AND portable_id = '%s'
				  AND entity_type = 'EXAM_BOOKLET'
				""".formatted(retired.latestRevisionId(), retired.portableId())));
		assertEquals(result.entityCount(),
				count(fixture.database(), "SELECT COUNT(*) FROM portable_entity_cache_evictions"));
		fixture.database().verifySchema();
		fixture.database().verifyIntegrity();
	}

	@Test
	void retiredSourceDocumentRetainsBaselineAssetForRecoverableFileRelease() throws Exception {
		Fixture fixture = fixture("retired-source-document");
		String expectedHash = scalar(fixture.database(), """
				SELECT content_sha256
				FROM source_documents
				WHERE relative_path = '%s'
				""".formatted(WorkAssignmentArchiveFixture.QUESTION_PATH));
		execute(fixture.database(), "DELETE FROM questions");
		execute(fixture.database(), "DELETE FROM exam_booklets");
		execute(fixture.database(), """
				DELETE FROM source_documents
				WHERE relative_path = '%s'
				""".formatted(WorkAssignmentArchiveFixture.QUESTION_PATH));
		var result = fixture.service().releaseSubmitted(fixture.assignmentId());
		assertEquals(State.VERIFIED, result.state());
		assertEquals(2, result.assetCount());
		assertEquals(1, count(fixture.database(), """
				SELECT COUNT(*)
				FROM corpus_disposal_assets
				WHERE relative_path = '%s'
				  AND asset_kind = 'SOURCE_DOCUMENT'
				  AND content_sha256 = '%s'
				""".formatted(WorkAssignmentArchiveFixture.QUESTION_PATH, expectedHash)));
		assertEquals(1, count(fixture.database(), """
				SELECT COUNT(*)
				FROM corpus_disposal_retired_entities
				WHERE entity_type = 'SOURCE_DOCUMENT'
				"""));
		assertFalse(Files.exists(fixture.config().dataRoot().resolve(WorkAssignmentArchiveFixture.QUESTION_PATH)));
		fixture.database().verifySchema();
		fixture.database().verifyIntegrity();
	}

	@Test
	void changedManagedAssetMakesDurablePlanStaleUntilOriginalBytesAreRestored() throws Exception {
		Fixture fixture = fixture("stale-asset-plan");
		var planOnly = new ContributorAssignmentCorpusDisposalService(fixture.database(),
				new ManagedDataLayout(fixture.config().dataRoot()), point -> {
					if (point == FaultPoint.AFTER_PLAN_COMMIT) {
						throw new IOException("stop after durable plan");
					}
				});
		assertThrows(IOException.class, () -> planOnly.releaseSubmitted(fixture.assignmentId()));
		Path question = fixture.config().dataRoot().resolve(WorkAssignmentArchiveFixture.QUESTION_PATH);
		Files.writeString(question, "changed after planning");
		assertThrows(IOException.class, () -> fixture.service().releaseSubmitted(fixture.assignmentId()));
		assertEquals("PLANNED", scalar(fixture.database(), "SELECT state FROM corpus_disposal_operations"));
		assertEquals(1, count(fixture.database(), "SELECT COUNT(*) FROM exams"));
		assertEquals(0, count(fixture.database(), "SELECT COUNT(*) FROM portable_entity_cache_evictions"));
		Files.write(question, WorkAssignmentArchiveFixture.QUESTION_BYTES);
		assertEquals(State.VERIFIED, fixture.service().releaseSubmitted(fixture.assignmentId()).state());
	}

	@Test
	void plannedDisposalBlocksOrdinaryRecoveryButExactServiceRetryCompletes() throws Exception {
		Fixture fixture = fixture("planned-startup-blocker");
		var planOnly = new ContributorAssignmentCorpusDisposalService(fixture.database(),
				new ManagedDataLayout(fixture.config().dataRoot()), point -> {
					if (point == FaultPoint.AFTER_PLAN_COMMIT) {
						throw new IOException("stop after durable plan");
					}
				});
		assertThrows(IOException.class, () -> planOnly.releaseSubmitted(fixture.assignmentId()));
		assertThrows(IOException.class, () -> ManagedAssetPublicationRecovery.recoverPending(fixture.config()));
		assertEquals("PLANNED", scalar(fixture.database(), "SELECT state FROM corpus_disposal_operations"));
		assertEquals(1, count(fixture.database(), "SELECT COUNT(*) FROM contributor_assignment_sessions"));
		assertEquals(1, count(fixture.database(), "SELECT COUNT(*) FROM exams"));
		assertEquals(State.VERIFIED, fixture.service().releaseSubmitted(fixture.assignmentId()).state());
		assertEquals(0, count(fixture.database(), "SELECT COUNT(*) FROM contributor_assignment_sessions"));
		assertEquals(0, count(fixture.database(), "SELECT COUNT(*) FROM exams"));
	}

	@Test
	void interruptedManagedFileReleaseResumesFromDurableDatabaseEvidence() throws Exception {
		Fixture fixture = fixture("file-release-interruption");
		Path question = fixture.config().dataRoot().resolve(WorkAssignmentArchiveFixture.QUESTION_PATH);
		Path answer = fixture.config().dataRoot().resolve(WorkAssignmentArchiveFixture.ANSWER_PATH);
		stopAfterDatabaseRelease(fixture);
		var interruptedRecovery = new ContributorAssignmentCorpusDisposalRecovery(fixture.config(), point -> {
			if (point == ContributorAssignmentCorpusDisposalRecovery.FaultPoint.AFTER_ASSET_DELETE) {
				throw new IOException("simulated interruption after exact file deletion");
			}
		});
		try (ManagedAssetPublicationLock lock = ManagedAssetPublicationLock.acquire(fixture.config().dataRoot())) {
			assertThrows(IOException.class, () -> interruptedRecovery.recoverPending(lock));
		}
		assertEquals("DATABASE_RELEASED", scalar(fixture.database(), "SELECT state FROM corpus_disposal_operations"));
		assertEquals(1, (Files.exists(question) ? 1 : 0) + (Files.exists(answer) ? 1 : 0));
		ManagedAssetPublicationRecovery.recoverPending(fixture.config());
		assertEquals("VERIFIED", scalar(fixture.database(), "SELECT state FROM corpus_disposal_operations"));
		assertFalse(Files.exists(question));
		assertFalse(Files.exists(answer));
		fixture.database().verifySchema();
		fixture.database().verifyIntegrity();
	}

	@Test
	void fullBackupRestoresDatabaseReleasedOperationAndResumesWithoutExternalPaths() throws Exception {
		Fixture source = fixture("backup-release-source");
		stopAfterDatabaseRelease(source);
		var backup = new DefaultBackupService(source.config(), "Test")
				.createBackup(BackupRequest.full(directory.resolve("backup-release-archives")));
		Fixture target = fixture("backup-release-target", false);
		try (var preparation = new DefaultRestoreService(target.config()).prepareRestore(backup.backupPath())) {
			new DefaultRestoreExecutor(target.config(), "Test").applyRestore(preparation, () -> {
			});
		}
		SqliteDatabase restored = new SqliteDatabase(target.config().databasePath());
		assertEquals("DATABASE_RELEASED", scalar(restored, "SELECT state FROM corpus_disposal_operations"));
		assertTrue(Files.isRegularFile(target.config().dataRoot().resolve(WorkAssignmentArchiveFixture.QUESTION_PATH)));
		ManagedAssetPublicationRecovery.recoverPending(target.config());
		assertEquals("VERIFIED", scalar(restored, "SELECT state FROM corpus_disposal_operations"));
		assertFalse(Files.exists(target.config().dataRoot().resolve(WorkAssignmentArchiveFixture.QUESTION_PATH)));
		assertFalse(Files.exists(target.config().dataRoot().resolve(WorkAssignmentArchiveFixture.ANSWER_PATH)));
		restored.verifySchema();
		restored.verifyIntegrity();
	}

	@Test
	void modifiedManagedFileAfterDatabaseReleaseFailsClosed() throws Exception {
		Fixture fixture = fixture("modified-file-after-release");
		Path question = fixture.config().dataRoot().resolve(WorkAssignmentArchiveFixture.QUESTION_PATH);
		stopAfterDatabaseRelease(fixture);
		Files.writeString(question, "changed after database release");
		assertThrows(IOException.class, () -> ManagedAssetPublicationRecovery.recoverPending(fixture.config()));
		assertEquals("DATABASE_RELEASED", scalar(fixture.database(), "SELECT state FROM corpus_disposal_operations"));
		assertTrue(Files.isRegularFile(question));
		assertEquals("changed after database release", Files.readString(question));
		assertEquals(0, count(fixture.database(), "SELECT COUNT(*) FROM contributor_assignment_sessions"));
		fixture.database().verifyIntegrity();
	}

	@Test
	void newAuthoritativeReferenceAfterDatabaseReleasePreventsFileDeletion() throws Exception {
		Fixture fixture = fixture("reference-after-release");
		Path question = fixture.config().dataRoot().resolve(WorkAssignmentArchiveFixture.QUESTION_PATH);
		stopAfterDatabaseRelease(fixture);
		try (Connection connection = fixture.database().openConnection();
				PreparedStatement statement = connection
						.prepareStatement("INSERT INTO source_documents (relative_path) VALUES (?)")) {
			statement.setString(1, WorkAssignmentArchiveFixture.QUESTION_PATH);
			assertEquals(1, statement.executeUpdate());
		}
		assertThrows(IOException.class, () -> ManagedAssetPublicationRecovery.recoverPending(fixture.config()));
		assertEquals("DATABASE_RELEASED", scalar(fixture.database(), "SELECT state FROM corpus_disposal_operations"));
		assertTrue(Files.isRegularFile(question));
		assertEquals(1, count(fixture.database(), """
				SELECT COUNT(*) FROM source_documents
				WHERE relative_path = '%s'
				""".formatted(WorkAssignmentArchiveFixture.QUESTION_PATH)));
		fixture.database().verifyIntegrity();
	}

	@Test
	void sourceDocumentSharedWithLocalExamIsPreservedAndNotOfferedForFileRelease() throws Exception {
		Fixture fixture = fixture("shared-source");
		long sourceId = Long.parseLong(scalar(fixture.database(), """
				SELECT id FROM source_documents
				WHERE relative_path = '%s'
				""".formatted(WorkAssignmentArchiveFixture.QUESTION_PATH)));
		long curriculumNodeId = Long
				.parseLong(scalar(fixture.database(), "SELECT id FROM curriculum_nodes ORDER BY id LIMIT 1"));
		withSelectionCleared(fixture.database(), () -> {
			execute(fixture.database(), """
					INSERT INTO exams (id, subject_id, provider_id, exam_year, exam_name)
					SELECT 900, subject_id, provider_id, 2024, 'Local retained Exam'
					FROM exams
					LIMIT 1
					""");
			execute(fixture.database(), """
					INSERT INTO exam_booklets (id, exam_id, source_document_id, booklet_name)
					VALUES (901, 900, %d, 'Retained booklet')
					""".formatted(sourceId));
			execute(fixture.database(), """
					INSERT INTO questions (
					    id, booklet_id, classification_node_id,
					    question_code, question_text, marks,
					    shared_context_capture_required
					)
					VALUES (902, 901, %d, 'LOCAL-1', 'Locally authoritative question', 1, 0)
					""".formatted(curriculumNodeId));
		});
		var result = fixture.service().releaseSubmitted(fixture.assignmentId());
		assertEquals(State.VERIFIED, result.state());
		assertEquals(1, count(fixture.database(), "SELECT COUNT(*) FROM exams WHERE id = 900"));
		assertEquals(1, count(fixture.database(), "SELECT COUNT(*) FROM questions WHERE id = 902"));
		assertEquals(1, count(fixture.database(), "SELECT COUNT(*) FROM source_documents WHERE id = " + sourceId));
		assertEquals(1, count(fixture.database(), "SELECT COUNT(*) FROM syllabus_versions"));
		assertEquals(3, count(fixture.database(), "SELECT COUNT(*) FROM curriculum_nodes"));
		assertEquals(1, result.assetCount());
		assertTrue(
				Files.isRegularFile(fixture.config().dataRoot().resolve(WorkAssignmentArchiveFixture.QUESTION_PATH)));
	}

	@Test
	void importedSubjectAuthorityPreservesCurriculumWhileExamCacheIsReleased() throws Exception {
		Fixture fixture = fixture("imported-authority");
		long subjectId = Long.parseLong(scalar(fixture.database(), "SELECT id FROM subjects"));
		UUID subjectPortableId = UUID.fromString(scalar(fixture.database(), """
				SELECT portable_id FROM portable_entity_identities
				WHERE entity_type = 'SUBJECT' AND local_id = %d
				""".formatted(subjectId)));
		PackageEntry imported = new PackageEntry(UUID.randomUUID(), "a".repeat(64), UUID.randomUUID(),
				subjectPortableId, "b".repeat(64), "c".repeat(64), PackageOutcome.APPLIED,
				directory.resolve("verified-import-backup.zip").toString(), "Approved imported Subject authority");
		try (Connection connection = fixture.database().openConnection()) {
			connection.setAutoCommit(false);
			new SqliteSubjectImportLedger(fixture.database()).recordPackage(connection, imported);
			new SqliteImportedSubjectAuthorityRepository(fixture.database()).recordImported(connection, subjectId,
					imported.packageId());
			connection.commit();
		}
		assertEquals(State.VERIFIED, fixture.service().releaseSubmitted(fixture.assignmentId()).state());
		assertEquals(0, count(fixture.database(), "SELECT COUNT(*) FROM exams"));
		assertEquals(1, count(fixture.database(), "SELECT COUNT(*) FROM syllabus_versions"));
		assertEquals(3, count(fixture.database(), "SELECT COUNT(*) FROM curriculum_nodes"));
		assertEquals(1, count(fixture.database(), "SELECT COUNT(*) FROM imported_subject_authorities"));
	}

	@Test
	void migratedMultipleReturnHistoryUsesLatestTerminalEvidence() throws Exception {
		Fixture fixture = fixture("historical-multiple-returns");
		UUID latestReturn = insertHistoricalSecondReturn(fixture.database(), fixture.assignmentId());
		assertEquals(State.VERIFIED, fixture.service().releaseSubmitted(fixture.assignmentId()).state());
		assertEquals(latestReturn.toString(), scalar(fixture.database(), """
				SELECT terminal_return_package_id
				FROM corpus_disposal_operations
				"""));
		assertEquals(2, count(fixture.database(), "SELECT COUNT(*) FROM contributor_return_packages"));
		assertEquals(0, count(fixture.database(), "SELECT COUNT(*) FROM contributor_assignment_sessions"));
		fixture.database().verifySchema();
		fixture.database().verifyIntegrity();
	}

	@Test
	void submittedAssignmentReleasePreservesAnotherSubjectsInstalledAssignment() throws Exception {
		Fixture fixture = fixture("other-subject-assignment");
		UUID otherAssignment = insertOtherSubjectSession(fixture.database());
		assertEquals(State.VERIFIED, fixture.service().releaseSubmitted(fixture.assignmentId()).state());
		assertEquals(1, count(fixture.database(), """
				SELECT COUNT(*) FROM contributor_assignment_sessions
				WHERE assignment_id = '%s'
				""".formatted(otherAssignment)));
		assertEquals(1, count(fixture.database(), "SELECT COUNT(*) FROM exams WHERE id = 903"));
		assertEquals(1, count(fixture.database(), "SELECT COUNT(*) FROM subjects WHERE id = 900"));
		assertEquals(1, count(fixture.database(), "SELECT COUNT(*) FROM syllabus_versions WHERE id = 901"));
		assertEquals(1, count(fixture.database(), "SELECT COUNT(*) FROM subjects WHERE subject_name = 'Biology'"));
		fixture.database().verifyIntegrity();
	}

	@Test
	void removedUnsubmittedAssignmentPreservesItsSharedSubjectCurriculum() throws Exception {
		Fixture fixture = fixture("shared-subject-assignment");
		UUID otherAssignment = insertSameSubjectSession(fixture.database());
		try (Connection connection = fixture.database().openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						INSERT INTO contributor_assignment_hidden (assignment_id, removed_at)
						VALUES (?, ?)
						""")) {
			statement.setString(1, otherAssignment.toString());
			statement.setString(2, Instant.now().toString());
			assertEquals(1, statement.executeUpdate());
		}
		assertEquals(State.VERIFIED, fixture.service().releaseSubmitted(fixture.assignmentId()).state());
		assertEquals(1, count(fixture.database(), """
				SELECT COUNT(*) FROM contributor_assignment_sessions
				WHERE assignment_id = '%s'
				""".formatted(otherAssignment)));
		assertEquals(1, count(fixture.database(), """
				SELECT COUNT(*) FROM contributor_assignment_hidden
				WHERE assignment_id = '%s'
				""".formatted(otherAssignment)));
		assertEquals(1, count(fixture.database(), "SELECT COUNT(*) FROM exams WHERE id = 910"));
		assertEquals(1, count(fixture.database(), "SELECT COUNT(*) FROM syllabus_versions"));
		assertEquals(3, count(fixture.database(), "SELECT COUNT(*) FROM curriculum_nodes"));
		fixture.database().verifyIntegrity();
	}

	@Test
	void removedUnchangedAssignmentReleasesWithoutTerminalContribution() throws Exception {
		Fixture fixture = fixture("removed-unchanged", false);
		new ContributorAssignmentRemovalService(fixture.database()).removeUnchanged(fixture.assignmentId());
		var released = fixture.service().releaseRemovedUnchanged(fixture.assignmentId());
		assertEquals(State.VERIFIED, released.state());
		assertEquals(0, count(fixture.database(), "SELECT COUNT(*) FROM contributor_assignment_sessions"));
		assertEquals(1, count(fixture.database(), """
				SELECT COUNT(*)
				FROM corpus_disposal_operations
				WHERE scope_id = '%s'
				  AND reason = 'REMOVED_UNCHANGED'
				  AND terminal_return_package_id IS NULL
				  AND state = 'VERIFIED'
				""".formatted(fixture.assignmentId())));
		assertTrue(count(fixture.database(), "SELECT COUNT(*) FROM portable_entity_cache_evictions") > 0);
		fixture.database().verifySchema();
		fixture.database().verifyIntegrity();
	}

	@Test
	void unsubmittedAssignmentAndUnresolvedPublicationAreNeverPlanned() throws Exception {
		Fixture unsubmitted = fixture("unsubmitted", false);
		assertThrows(IllegalStateException.class,
				() -> unsubmitted.service().releaseSubmitted(unsubmitted.assignmentId()));
		assertEquals(0, count(unsubmitted.database(), "SELECT COUNT(*) FROM corpus_disposal_operations"));
		assertEquals(1, count(unsubmitted.database(), "SELECT COUNT(*) FROM exams"));
		Fixture uncertain = fixture("uncertain");
		insertPublicationAttempt(uncertain);
		assertThrows(IOException.class, () -> uncertain.service().releaseSubmitted(uncertain.assignmentId()));
		assertEquals(0, count(uncertain.database(), "SELECT COUNT(*) FROM corpus_disposal_operations"));
		assertEquals(1, count(uncertain.database(), "SELECT COUNT(*) FROM exams"));
	}

	private Fixture fixture(String name) throws Exception {
		return fixture(name, true);
	}

	private Fixture fixture(String name, boolean submitted) throws Exception {
		ApplicationConfig config = ApplicationConfig.fromDataRoot(directory.resolve(name));
		Files.createDirectories(config.dataRoot());
		SqliteDatabase database = new SqliteDatabase(config.databasePath());
		database.initialiseSchema();
		Path archive = WorkAssignmentArchiveFixture.write(directory.resolve(name + ".examwork"));
		var installed = new WorkAssignmentInstallationCoordinator(config).install(archive);
		if (submitted) {
			insertTerminalReturn(database, installed.assignmentId());
		}
		return new Fixture(config, database, installed.assignmentId(),
				new ContributorAssignmentCorpusDisposalService(database, new ManagedDataLayout(config.dataRoot())));
	}

	private void insertTerminalReturn(SqliteDatabase database, UUID assignmentId) throws SQLException {
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						INSERT INTO contributor_return_packages (
						    package_id, assignment_id, return_number, previous_package_id,
						    baseline_snapshot_id, master_installation_id, contributor_id,
						    origin_installation_id, archive_sha256, recorded_at,
						    assignment_package_id, assignment_archive_sha256,
						    subject_portable_id, exam_portable_id,
						    issued_at, installed_at, contribution_created_at
						)
						SELECT ?, session.assignment_id, 1, NULL,
						       session.baseline_snapshot_id, session.master_installation_id,
						       session.contributor_id, installation.installation_id,
						       ?, ?, session.package_id, session.archive_sha256,
						       session.subject_portable_id, session.exam_portable_id,
						       session.issued_at, session.installed_at, ?
						FROM contributor_assignment_sessions session
						CROSS JOIN installation_identity installation
						WHERE session.assignment_id = ? AND installation.singleton_id = 1
						""")) {
			String now = Instant.now().toString();
			statement.setString(1, UUID.randomUUID().toString());
			statement.setString(2, "f".repeat(64));
			statement.setString(3, now);
			statement.setString(4, now);
			statement.setString(5, assignmentId.toString());
			assertEquals(1, statement.executeUpdate());
		}
		execute(database, """
				UPDATE contributor_assignment_selection
				SET assignment_id = NULL,
				    updated_at = '%s'
				WHERE singleton_id = 1
				  AND assignment_id = '%s'
				""".formatted(Instant.now(), assignmentId));
	}

	private RetiredIdentity retireAssignedBooklet(SqliteDatabase database) throws SQLException {
		long localId = Long.parseLong(scalar(database, """
				SELECT booklet.id
				FROM exam_booklets booklet
				WHERE NOT EXISTS (
				    SELECT 1 FROM questions question
				    WHERE question.booklet_id = booklet.id
				)
				ORDER BY booklet.id
				LIMIT 1
				"""));
		UUID portableId = UUID.fromString(scalar(database, """
				SELECT portable_id
				FROM portable_entity_identities
				WHERE entity_type = 'EXAM_BOOKLET' AND local_id = %d
				""".formatted(localId)));
		UUID baselineRevisionId = UUID.fromString(scalar(database, """
				SELECT revision_id
				FROM portable_entity_identities
				WHERE entity_type = 'EXAM_BOOKLET' AND local_id = %d
				""".formatted(localId)));
		UUID authorityInstallationId = UUID
				.fromString(scalar(database, "SELECT master_installation_id FROM contributor_assignment_sessions"));
		execute(database, """
				UPDATE exam_booklets
				SET booklet_name = booklet_name || ' contributor edit'
				WHERE id = %d
				""".formatted(localId));
		UUID latestRevisionId = UUID.fromString(scalar(database, """
				SELECT revision_id
				FROM portable_entity_identities
				WHERE entity_type = 'EXAM_BOOKLET' AND local_id = %d
				""".formatted(localId)));
		execute(database, "DELETE FROM exam_booklets WHERE id = " + localId);
		return new RetiredIdentity(localId, portableId, baselineRevisionId, latestRevisionId, authorityInstallationId);
	}

	private void insertPublicationAttempt(Fixture fixture) throws SQLException {

		// Remove detached terminal evidence only in this disposable test database, then
		// create the uncertain publication state the planner must reject.
		try (Connection connection = fixture.database().openConnection();
				Statement statement = connection.createStatement()) {
			statement.execute("DROP TRIGGER trg_contributor_returns_no_delete");
			statement.execute("DELETE FROM contributor_return_packages");
		}
		try (Connection connection = fixture.database().openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						INSERT INTO contribution_publication_attempts (
						    assignment_id, package_id, archive_sha256, contribution_created_at,
						    destination_path, staging_path, state, selection_was_active,
						    prepared_at, updated_at
						)
						VALUES (?, ?, ?, ?, ?, ?, 'PREPARED', 1, ?, ?)
						""")) {
			String now = Instant.now().toString();
			statement.setString(1, fixture.assignmentId().toString());
			statement.setString(2, UUID.randomUUID().toString());
			statement.setString(3, "e".repeat(64));
			statement.setString(4, now);
			statement.setString(5, directory.resolve("missing.examwork").toAbsolutePath().toString());
			statement.setString(6, directory.resolve("missing.tmp").toAbsolutePath().toString());
			statement.setString(7, now);
			statement.setString(8, now);
			assertEquals(1, statement.executeUpdate());
		}
		try (Connection connection = fixture.database().openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						UPDATE contribution_publication_attempts
						SET state = 'PUBLISHING', updated_at = ?
						WHERE assignment_id = ? AND state = 'PREPARED'
						""")) {
			statement.setString(1, Instant.now().toString());
			statement.setString(2, fixture.assignmentId().toString());
			assertEquals(1, statement.executeUpdate());
		}
	}

	private UUID insertHistoricalSecondReturn(SqliteDatabase database, UUID assignmentId) throws SQLException {
		UUID packageId = UUID.randomUUID();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			connection.setAutoCommit(false);
			try {
				statement.execute("DROP TRIGGER trg_contributor_returns_single_final_insert");
				try (PreparedStatement insert = connection.prepareStatement("""
						INSERT INTO contributor_return_packages (
						    package_id, assignment_id, return_number, previous_package_id,
						    baseline_snapshot_id, master_installation_id, contributor_id,
						    origin_installation_id, archive_sha256, recorded_at,
						    assignment_package_id, assignment_archive_sha256,
						    subject_portable_id, exam_portable_id,
						    issued_at, installed_at, contribution_created_at
						)
						SELECT ?, assignment_id, 2, package_id,
						       baseline_snapshot_id, master_installation_id, contributor_id,
						       origin_installation_id, ?, ?,
						       assignment_package_id, assignment_archive_sha256,
						       subject_portable_id, exam_portable_id,
						       issued_at, installed_at, ?
						FROM contributor_return_packages
						WHERE assignment_id = ? AND return_number = 1
						""")) {
					String now = Instant.now().toString();
					insert.setString(1, packageId.toString());
					insert.setString(2, "8".repeat(64));
					insert.setString(3, now);
					insert.setString(4, now);
					insert.setString(5, assignmentId.toString());
					assertEquals(1, insert.executeUpdate());
				}
				statement.execute("""
						CREATE TRIGGER trg_contributor_returns_single_final_insert
						BEFORE INSERT ON contributor_return_packages
						WHEN EXISTS (
						    SELECT 1
						    FROM contributor_return_packages existing
						    WHERE existing.assignment_id = NEW.assignment_id
						)
						BEGIN
						    SELECT RAISE(
						        ABORT,
						        'A final Contribution already exists for this assignment'
						    );
						END
						""");
				connection.commit();
			} catch (SQLException | RuntimeException failure) {
				connection.rollback();
				throw failure;
			}
		}
		return packageId;
	}

	private UUID insertOtherSubjectSession(SqliteDatabase database) throws Exception {
		UUID assignmentId = UUID.randomUUID();
		withSelectionCleared(database, () -> {
			execute(database, "INSERT INTO subjects (id, subject_name) VALUES (900, 'Chemistry')");
			execute(database, """
					INSERT INTO syllabus_versions (id, subject_id, syllabus_name, is_current)
					VALUES (901, 900, 'Chemistry 2026', 1)
					""");
			execute(database, """
					INSERT INTO curriculum_nodes (
					    id, syllabus_version_id, parent_id, curriculum_code,
					    curriculum_name, curriculum_level, display_order
					)
					VALUES (902, 901, NULL, 'CHEM-U1', 'Chemistry Unit', 'UNIT', 0)
					""");
			execute(database, """
					INSERT INTO exams (id, subject_id, provider_id, exam_year, exam_name)
					SELECT 903, 900, id, 2026, 'Chemistry Assignment'
					FROM exam_providers ORDER BY id LIMIT 1
					""");
			try (Connection connection = database.openConnection();
					PreparedStatement statement = connection.prepareStatement("""
							INSERT INTO contributor_assignment_sessions (
							    assignment_id, package_id, baseline_snapshot_id, master_installation_id,
							    subject_portable_id, exam_portable_id, current_syllabus_portable_id,
							    legacy_syllabus_portable_id, contributor_id, contributor_name,
							    subject_local_id, exam_local_id, current_syllabus_local_id,
							    legacy_syllabus_local_id, issued_at, installed_at,
							    archive_sha256, records_sha256, assets_sha256,
							    baseline_records, baseline_assets
							)
							SELECT ?, ?, ?, ?, subject_identity.portable_id, exam_identity.portable_id,
							       syllabus_identity.portable_id, NULL, ?, 'Other Contributor',
							       900, 903, 901, NULL, ?, ?, ?, original.records_sha256,
							       original.assets_sha256, original.baseline_records, original.baseline_assets
							FROM contributor_assignment_sessions original
							JOIN portable_entity_identities subject_identity
							  ON subject_identity.entity_type = 'SUBJECT' AND subject_identity.local_id = 900
							JOIN portable_entity_identities exam_identity
							  ON exam_identity.entity_type = 'EXAM' AND exam_identity.local_id = 903
							JOIN portable_entity_identities syllabus_identity
							  ON syllabus_identity.entity_type = 'SYLLABUS_VERSION' AND syllabus_identity.local_id = 901
							WHERE original.assignment_id <> ?
							""")) {
				String now = Instant.now().toString();
				statement.setString(1, assignmentId.toString());
				statement.setString(2, UUID.randomUUID().toString());
				statement.setString(3, UUID.randomUUID().toString());
				statement.setString(4, UUID.randomUUID().toString());
				statement.setString(5, UUID.randomUUID().toString());
				statement.setString(6, now);
				statement.setString(7, now);
				statement.setString(8, "d".repeat(64));
				statement.setString(9, assignmentId.toString());
				assertEquals(1, statement.executeUpdate());
			}
		});
		return assignmentId;
	}

	private UUID insertSameSubjectSession(SqliteDatabase database) throws Exception {
		UUID assignmentId = UUID.randomUUID();
		withSelectionCleared(database, () -> {
			execute(database, """
					INSERT INTO exams (id, subject_id, provider_id, exam_year, exam_name)
					SELECT 910, subject_id, provider_id, 2027, 'Second Biology Assignment'
					FROM exams ORDER BY id LIMIT 1
					""");
			try (Connection connection = database.openConnection();
					PreparedStatement statement = connection.prepareStatement("""
							INSERT INTO contributor_assignment_sessions (
							    assignment_id, package_id, baseline_snapshot_id, master_installation_id,
							    subject_portable_id, exam_portable_id, current_syllabus_portable_id,
							    legacy_syllabus_portable_id, contributor_id, contributor_name,
							    subject_local_id, exam_local_id, current_syllabus_local_id,
							    legacy_syllabus_local_id, issued_at, installed_at,
							    archive_sha256, records_sha256, assets_sha256,
							    baseline_records, baseline_assets
							)
							SELECT ?, ?, ?, original.master_installation_id,
							       original.subject_portable_id, exam_identity.portable_id,
							       original.current_syllabus_portable_id, NULL,
							       ?, 'Second Biology Contributor', original.subject_local_id,
							       910, original.current_syllabus_local_id, NULL, ?, ?, ?,
							       original.records_sha256, original.assets_sha256,
							       original.baseline_records, original.baseline_assets
							FROM contributor_assignment_sessions original
							JOIN portable_entity_identities exam_identity
							  ON exam_identity.entity_type = 'EXAM' AND exam_identity.local_id = 910
							WHERE original.assignment_id <> ?
							""")) {
				String now = Instant.now().toString();
				statement.setString(1, assignmentId.toString());
				statement.setString(2, UUID.randomUUID().toString());
				statement.setString(3, UUID.randomUUID().toString());
				statement.setString(4, UUID.randomUUID().toString());
				statement.setString(5, now);
				statement.setString(6, now);
				statement.setString(7, "9".repeat(64));
				statement.setString(8, assignmentId.toString());
				assertEquals(1, statement.executeUpdate());
			}
		});
		return assignmentId;
	}

	private void withSelectionCleared(SqliteDatabase database, SqlRunnable action) throws Exception {
		String assignmentId = scalar(database,
				"SELECT assignment_id FROM contributor_assignment_selection WHERE singleton_id = 1");
		execute(database, "UPDATE contributor_assignment_selection SET assignment_id = NULL, updated_at = 'test'");
		try {
			action.run();
		} finally {
			String restored = assignmentId == null ? "NULL" : "'" + assignmentId + "'";
			execute(database, "UPDATE contributor_assignment_selection SET assignment_id = " + restored
					+ ", updated_at = 'test-restored'");
		}
	}

	private void execute(SqliteDatabase database, String sql) throws SQLException {
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}

	private int count(SqliteDatabase database, String sql) throws SQLException {
		return Integer.parseInt(scalar(database, sql));
	}

	private String scalar(SqliteDatabase database, String sql) throws SQLException {
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet row = statement.executeQuery(sql)) {
			assertTrue(row.next());
			String value = row.getString(1);
			assertFalse(row.next());
			return value;
		}
	}

	private void stopAfterDatabaseRelease(Fixture fixture) throws SQLException {
		var databaseReleaseOnly = new ContributorAssignmentCorpusDisposalService(fixture.database(),
				new ManagedDataLayout(fixture.config().dataRoot()), point -> {
					if (point == FaultPoint.AFTER_DATABASE_RELEASE_COMMIT) {
						throw new IOException("simulated interruption after database release");
					}
				});
		assertThrows(IOException.class, () -> databaseReleaseOnly.releaseSubmitted(fixture.assignmentId()));
		assertEquals("DATABASE_RELEASED", scalar(fixture.database(), "SELECT state FROM corpus_disposal_operations"));
		assertEquals(0, count(fixture.database(), "SELECT COUNT(*) FROM contributor_assignment_sessions"));
		assertEquals(0, count(fixture.database(), "SELECT COUNT(*) FROM exams"));
		assertTrue(
				Files.isRegularFile(fixture.config().dataRoot().resolve(WorkAssignmentArchiveFixture.QUESTION_PATH)));
		assertTrue(Files.isRegularFile(fixture.config().dataRoot().resolve(WorkAssignmentArchiveFixture.ANSWER_PATH)));
	}

	@FunctionalInterface
	private interface SqlRunnable {

		void run() throws Exception;
	}

	private record Fixture(ApplicationConfig config, SqliteDatabase database, UUID assignmentId,
			ContributorAssignmentCorpusDisposalService service) {
	}

	private record RetiredIdentity(long localId, UUID portableId, UUID baselineRevisionId, UUID latestRevisionId,
			UUID authorityInstallationId) {
	}
}
