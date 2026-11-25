package au.edu.eq.questionbank.service.workpackage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.commons.compress.archivers.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.repository.sqlite.SqliteImportedSubjectAuthorityRepository;
import au.edu.eq.questionbank.repository.sqlite.SqliteSubjectImportLedger;
import au.edu.eq.questionbank.service.workpackage.SubjectImportSnapshotFingerprint.Fingerprint;
import au.edu.eq.questionbank.service.workpackage.SubjectReconciliationReview.Decision;

/**
 * Disposable two-source Biology consolidation and idempotence regression tests.
 *
 * <p>
 * Both source and master contain an independent Biology installation. The
 * master additionally contains Chemistry, Physics and Engineering Subjects,
 * which must remain unchanged by importing a complete mixed-content Biology
 * examination, booklet, Question, Answer, image and Shared Context graph.
 */
class SubjectImportCoordinatorIntegrationTest {

	private static final byte[] PNG = Base64.getDecoder().decode(
			"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwC" + "AAAAC0lEQVR42mP8/x8AAwMCAO+/WZcAAAAASUVORK5CYII=");
	@TempDir
	Path directory;

	/**
	 * Exercises full graph insertion, PDF and embedded PNG persistence, independent
	 * Subject preservation, origin aliases and a duplicate replay.
	 */
	@Test
	void consolidatesTwoBiologySourcesWithoutChangingOtherSubjects() throws Exception {
		Fixture fixture = fixture();
		SubjectImportCoordinator service = new SubjectImportCoordinator(fixture.master());
		var result = service.execute(1, fixture.plan(), fixture.incoming(), directory.resolve("backups"),
				"Coordinator approved independent Biology consolidation");
		assertTrue(result.applied());
		assertFalse(result.replay());
		assertTrue(Files.isRegularFile(Path.of(result.backupArchivePath())));
		Path questions = fixture.master().dataRoot().resolve("subjects/Biology/exams/questions.pdf");
		assertArrayEquals("Biology question document".getBytes(), Files.readAllBytes(questions));
		SqliteDatabase master = new SqliteDatabase(fixture.master().databasePath());
		master.verifyIntegrity();
		try (Connection connection = master.openConnection()) {
			assertEquals(4, count(connection, "SELECT COUNT(*) FROM subjects"));
			assertEquals(1, count(connection, "SELECT COUNT(*) FROM exams WHERE subject_id = 1"));
			assertEquals(1, count(connection, "SELECT COUNT(*) FROM questions"));
			assertEquals(1, count(connection, "SELECT COUNT(*) FROM answers"));
			assertEquals(1, count(connection, "SELECT COUNT(*) FROM question_images"));
			assertEquals(2, count(connection, "SELECT COUNT(*) FROM question_content_parts"));
			assertEquals(2, count(connection, "SELECT COUNT(*) FROM shared_question_context_content_parts"));
			assertEquals(1, count(connection, "SELECT COUNT(*) FROM answer_regions"));
			assertEquals(1, count(connection, "SELECT COUNT(*) FROM booklet_applicability_reviews"));
			assertEquals(1, count(connection, "SELECT COUNT(*) FROM subject_import_packages"));
			assertTrue(count(connection, "SELECT COUNT(*) FROM portable_identity_aliases") >= 11);
			assertEquals(count(connection, "SELECT COUNT(*) FROM portable_identity_aliases"), count(connection, """
					SELECT COUNT(*)
					FROM portable_identity_aliases
					WHERE approval_note =
					    'Coordinator approved independent Biology consolidation'
					"""),
					"Aliases without individual explanations must retain " + "the coordinator's overall approval note");
			try (Statement statement = connection.createStatement();
					ResultSet resultSet = statement.executeQuery("SELECT image_png FROM question_images")) {
				assertTrue(resultSet.next());
				assertArrayEquals(PNG, resultSet.getBytes(1));
			}
			try (Statement statement = connection.createStatement();
					ResultSet resultSet = statement
							.executeQuery("SELECT image_png FROM shared_question_context_images")) {
				assertTrue(resultSet.next());
				assertArrayEquals(PNG, resultSet.getBytes(1));
			}
		}
		assertTrue(
				new SqliteSubjectImportLedger(master).find(fixture.plan().reviewedIncoming().packageId()).isPresent());

		// A package replay must be a no-op, including no duplicate Question or files.
		var repeated = service.execute(1, fixture.plan(), fixture.incoming(), directory.resolve("backups"),
				"Repeated import must not produce duplicate data");
		assertTrue(repeated.applied());
		assertTrue(repeated.replay());
		try (Connection connection = master.openConnection()) {
			assertEquals(1, count(connection, "SELECT COUNT(*) FROM questions"));
			assertEquals(1, count(connection, "SELECT COUNT(*) FROM answers"));
			assertEquals(1, count(connection, "SELECT COUNT(*) FROM subject_import_packages"));
		}
	}

	/**
	 * Verifies that the contributor database and exported package retain Question,
	 * Shared Context, Answer and Booklet review child rows.
	 */
	@Test
	void contributorSnapshotPreservesOrderedContentBeforeImport() throws Exception {
		Fixture fixture = fixture();
		SqliteDatabase source = new SqliteDatabase(directory.resolve("source/questionbank.db"));
		try (Connection connection = source.openConnection()) {
			assertEquals(2, count(connection, "SELECT COUNT(*) FROM question_content_parts"),
					"Contributor database is missing Question content parts");
			assertEquals(2, count(connection, "SELECT COUNT(*) FROM shared_question_context_content_parts"),
					"Contributor database is missing Shared Context content parts");
			assertEquals(1, count(connection, "SELECT COUNT(*) FROM answer_regions"),
					"Contributor database is missing Answer PDF regions");
			assertEquals(1, count(connection, "SELECT COUNT(*) FROM booklet_applicability_reviews"),
					"Contributor database is missing Booklet reviews");
		}
		try (ZipFile archive = ZipFile.builder().setPath(fixture.incoming()).get()) {
			var entry = archive.getEntry("records/subject-graph.jsonl");
			assertNotNull(entry, "Contributor archive has no Subject records");
			List<ExchangeRecord> records;
			try (InputStream input = archive.getInputStream(entry)) {
				records = new ExamWorkJsonlCodec().readRecords(input);
			}
			ExchangeRecord question = records.stream().filter(record -> record.recordType().equals("QUESTION"))
					.findFirst().orElseThrow();
			ExchangeRecord context = records.stream()
					.filter(record -> record.recordType().equals("SHARED_QUESTION_CONTEXT")).findFirst().orElseThrow();
			ExchangeRecord answer = records.stream().filter(record -> record.recordType().equals("ANSWER")).findFirst()
					.orElseThrow();
			ExchangeRecord booklet = records.stream().filter(record -> record.recordType().equals("EXAM_BOOKLET"))
					.findFirst().orElseThrow();
			assertEquals(2, question.values().path("question_content_parts").size(),
					"Portable export lost Question content parts");
			assertEquals(2, context.values().path("shared_question_context_content_parts").size(),
					"Portable export lost Shared Context content parts");
			assertEquals(1, answer.values().path("answer_regions").size(), "Portable export lost Answer PDF regions");
			assertEquals(1, booklet.values().path("booklet_applicability_reviews").size(),
					"Portable export lost Booklet applicability reviews");
		}
	}

	@Test
	void installsThreeIndependentSubjectsAndPreservesTheirAuthority() throws Exception {
		ApplicationConfig destination = ApplicationConfig.fromDataRoot(directory.resolve("department"));
		Files.createDirectories(destination.dataRoot());
		SqliteDatabase database = new SqliteDatabase(destination.databasePath());
		database.initialiseSchema();
		var preview = new SubjectSnapshotIntakePreview(database);
		var service = new SubjectImportCoordinator(destination);
		var authorities = new SqliteImportedSubjectAuthorityRepository(database);
		Map<String, UUID> installationIds = new HashMap<>();
		Map<String, Long> importedSubjectIds = new HashMap<>();
		Map<String, Path> archives = new HashMap<>();
		Map<String, String> backupPaths = new HashMap<>();
		List<String> subjects = List.of("Biology", "Chemistry", "Physics");
		for (String subject : subjects) {
			Path archive = createIndependentSnapshot(subject, subject + "-source");
			archives.put(subject, archive);
			var inspection = preview.inspect(archive);
			assertEquals(SubjectSnapshotIntakePreview.State.NEW_SUBJECT, inspection.state());
			assertEquals(1L, inspection.count("SUBJECT"));
			assertEquals(1L, inspection.count("EXAM"));
			assertEquals(1L, inspection.count("QUESTION"));
			assertEquals(1L, inspection.count("ANSWER"));
			assertEquals(3L, inspection.count("CURRICULUM_NODE"));
			UUID origin = inspection.fingerprint().originInstallationId();
			assertFalse(installationIds.containsValue(origin));
			installationIds.put(subject, origin);
			var result = service.executeNewSubject(inspection, archive);
			assertTrue(result.applied());
			assertFalse(result.replay());
			Path backup = Path.of(result.backupArchivePath());
			assertTrue(Files.isRegularFile(backup));
			assertTrue(backup.startsWith(destination.dataRoot().resolve("backups/pre-import")));
			backupPaths.put(subject, result.backupArchivePath());
			long localSubjectId = findSubjectId(database, subject);
			importedSubjectIds.put(subject, localSubjectId);
			var authority = authorities.find(localSubjectId).orElseThrow();
			assertEquals(origin, authority.originInstallationId());
			assertEquals(inspection.fingerprint().subjectId(), authority.originSubjectPortableId());
			assertEquals(inspection.fingerprint().packageId(), authority.latestPackageId());
			assertEquals(inspection.fingerprint().archiveSha256(), authority.archiveSha256());
			assertTrue(authorities.isImported(localSubjectId));
			assertThrows(IllegalStateException.class, () -> authorities.requireLocallyAuthoritative(localSubjectId));

			// Recheck previously installed Subjects after every
			// independent import, not merely at the end.
			for (String installed : importedSubjectIds.keySet()) {
				assertEquals(importedSubjectIds.get(installed).longValue(), findSubjectId(database, installed));
				assertEquals(installationIds.get(installed),
						authorities.find(importedSubjectIds.get(installed)).orElseThrow().originInstallationId());
				assertImportedSubjectGraph(database, destination, installed);
			}
			try (Connection connection = database.openConnection()) {
				int expected = importedSubjectIds.size();
				assertEquals(expected, count(connection, "SELECT COUNT(*) FROM subjects"));
				assertEquals(expected, count(connection, "SELECT COUNT(*) FROM exams"));
				assertEquals(expected, count(connection, "SELECT COUNT(*) FROM questions"));
				assertEquals(expected, count(connection, "SELECT COUNT(*) FROM answers"));
				assertEquals(expected, count(connection, "SELECT COUNT(*) FROM subject_import_packages"));
				assertEquals(expected, count(connection, "SELECT COUNT(*) FROM imported_subject_authorities"));

				// All three independent coordinators use QCAA.
				// Importing another Subject must reuse that provider.
				assertEquals(1, count(connection, "SELECT COUNT(*) FROM exam_providers"));
				assertEquals(expected, count(connection, """
						SELECT COUNT(*)
						FROM exams e
						JOIN exam_providers p
						    ON p.id = e.provider_id
						WHERE p.provider_name = 'QCAA'
						"""));
			}
			database.verifyIntegrity();
		}

		// Replaying all three packages must preserve their
		// original backup evidence and produce no new records.
		for (String subject : subjects) {
			Path archive = archives.get(subject);
			var inspection = preview.inspect(archive);
			assertEquals(SubjectSnapshotIntakePreview.State.ALREADY_IMPORTED, inspection.state());
			var result = service.executeNewSubject(inspection, archive);
			assertTrue(result.applied());
			assertTrue(result.replay());
			assertEquals(backupPaths.get(subject), result.backupArchivePath());
			assertImportedSubjectGraph(database, destination, subject);
		}
		try (Connection connection = database.openConnection()) {
			assertEquals(3, count(connection, "SELECT COUNT(*) FROM subjects"));
			assertEquals(3, count(connection, "SELECT COUNT(*) FROM exams"));
			assertEquals(3, count(connection, "SELECT COUNT(*) FROM questions"));
			assertEquals(3, count(connection, "SELECT COUNT(*) FROM answers"));
			assertEquals(3, count(connection, "SELECT COUNT(*) FROM subject_import_packages"));
			assertEquals(1, count(connection, "SELECT COUNT(*) FROM exam_providers"));
		}
		database.verifyIntegrity();
	}

	@Test
	void newSubjectImportRejectsArchiveSubstitution() throws Exception {
		ApplicationConfig destination = ApplicationConfig.fromDataRoot(directory.resolve("substitution-target"));
		Files.createDirectories(destination.dataRoot());
		SqliteDatabase database = new SqliteDatabase(destination.databasePath());
		database.initialiseSchema();
		Path original = createIndependentSnapshot("Biology", "original-source");
		Path replacement = createIndependentSnapshot("Biology", "replacement-source");
		var inspection = new SubjectSnapshotIntakePreview(database).inspect(original);
		var service = new SubjectImportCoordinator(destination);

		// Substitute another valid archive of the same Subject.
		// Matching Subject names must not bypass approval binding.
		assertThrows(IllegalStateException.class, () -> service.executeNewSubject(inspection, replacement));
		assertEmptySubjectDestination(database, destination);

		// The originally approved archive remains installable.
		var result = service.executeNewSubject(inspection, original);
		assertTrue(result.applied());
		assertImportedSubjectGraph(database, destination, "Biology");
		database.verifyIntegrity();
	}

	@Test
	void newSubjectImportRejectsDestinationCreatedAfterPreview() throws Exception {
		ApplicationConfig destination = ApplicationConfig.fromDataRoot(directory.resolve("stale-destination"));
		Files.createDirectories(destination.dataRoot());
		SqliteDatabase database = new SqliteDatabase(destination.databasePath());
		database.initialiseSchema();
		Path archive = createIndependentSnapshot("Biology", "stale-source");
		var preview = new SubjectSnapshotIntakePreview(database);
		var inspection = preview.inspect(archive);
		assertEquals(SubjectSnapshotIntakePreview.State.NEW_SUBJECT, inspection.state());

		// Simulate independent local creation after the
		// original new-Subject preview has been approved.
		execute(database, """
				INSERT INTO subjects (id, subject_name)
				VALUES (99, 'Biology')
				""");
		var service = new SubjectImportCoordinator(destination);
		assertThrows(SQLException.class, () -> service.executeNewSubject(inspection, archive));
		assertEquals(99L, findSubjectId(database, "Biology"));
		var authorities = new SqliteImportedSubjectAuthorityRepository(database);
		assertFalse(authorities.isImported(99L));
		authorities.requireLocallyAuthoritative(99L);
		try (Connection connection = database.openConnection()) {
			assertEquals(1, count(connection, "SELECT COUNT(*) FROM subjects"));
			assertEquals(0, count(connection, "SELECT COUNT(*) FROM exams"));
			assertEquals(0, count(connection, "SELECT COUNT(*) FROM questions"));
			assertEquals(0, count(connection, "SELECT COUNT(*) FROM subject_import_packages"));
			assertEquals(0, count(connection, "SELECT COUNT(*) FROM imported_subject_authorities"));
		}
		assertFalse(Files.exists(destination.dataRoot().resolve("subjects/Biology/exams/questions.pdf")));
		database.verifyIntegrity();
	}

	@Test
	void newSubjectImportRollsBackGraphAuthorityAndPublishedAssets() throws Exception {
		ApplicationConfig destination = ApplicationConfig.fromDataRoot(directory.resolve("rollback-target"));
		Files.createDirectories(destination.dataRoot());
		SqliteDatabase database = new SqliteDatabase(destination.databasePath());
		database.initialiseSchema();
		Path archive = createIndependentSnapshot("Biology", "rollback-source");
		var inspection = new SubjectSnapshotIntakePreview(database).inspect(archive);
		var service = new SubjectImportCoordinator(destination);

		// Fail at authority registration, after the graph,
		// managed-file publication and import ledger have been
		// processed, but before the SQLite transaction commits.
		execute(database, """
				CREATE TRIGGER test_reject_imported_authority
				BEFORE INSERT ON imported_subject_authorities
				BEGIN
				    SELECT RAISE(
				        ABORT,
				        'Injected imported authority failure');
				END
				""");
		SQLException failure = assertThrows(SQLException.class, () -> service.executeNewSubject(inspection, archive));
		assertTrue(failure.getMessage().contains("Injected imported authority failure"));
		assertEmptySubjectDestination(database, destination);
		Path staging = destination.dataRoot().resolve(".subject-import-staging")
				.resolve(inspection.fingerprint().packageId().toString());
		assertFalse(Files.exists(staging));
		database.verifyIntegrity();
		execute(database, "DROP TRIGGER test_reject_imported_authority");

		// The exact previously approved package must remain
		// installable after successful rollback and recovery.
		var result = service.executeNewSubject(inspection, archive);
		assertTrue(result.applied());
		assertFalse(result.replay());
		assertImportedSubjectGraph(database, destination, "Biology");
		long subjectId = findSubjectId(database, "Biology");
		assertTrue(new SqliteImportedSubjectAuthorityRepository(database).isImported(subjectId));
		assertFalse(Files.exists(staging));
		database.verifyIntegrity();
	}

	@Test
	void refreshEligibilityPreservesImportedAuthorityAndUnrelatedSubjects() throws Exception {
		ApplicationConfig destination = ApplicationConfig.fromDataRoot(directory.resolve("refresh-eligible-target"));
		Files.createDirectories(destination.dataRoot());
		SqliteDatabase database = new SqliteDatabase(destination.databasePath());
		database.initialiseSchema();
		execute(database, """
				INSERT INTO subjects (id, subject_name)
				VALUES (100, 'Chemistry')
				""");
		Path original = createIndependentSnapshot("Biology", "refresh-eligible-source");
		var preview = new SubjectSnapshotIntakePreview(database);
		var initial = preview.inspect(original);
		SubjectImportCoordinator coordinator = new SubjectImportCoordinator(destination);
		var installation = coordinator.executeNewSubject(initial, original);
		assertTrue(installation.applied());
		long subjectId = findSubjectId(database, "Biology");
		SqliteImportedSubjectAuthorityRepository authorities = new SqliteImportedSubjectAuthorityRepository(database);
		var originalAuthority = authorities.find(subjectId).orElseThrow();
		ApplicationConfig source = ApplicationConfig.fromDataRoot(directory.resolve("refresh-eligible-source"));
		SqliteDatabase sourceDatabase = new SqliteDatabase(source.databasePath());

		// Make a genuine authoritative change while preserving
		// the originating installation and Subject identities.
		execute(sourceDatabase, """
				UPDATE questions
				SET marks = 3
				WHERE id = 81
				""");
		Path updated = directory.resolve("refresh-eligible-updated.examwork");
		new SubjectSnapshotExporter(sourceDatabase, new ManagedDataLayout(source.dataRoot())).export(1L, updated);
		var inspection = preview.inspect(updated);
		assertEquals(SubjectSnapshotIntakePreview.State.EXISTING_SUBJECT, inspection.state());
		assertEquals(subjectId, inspection.existingSubjectId().longValue());
		assertFalse(initial.fingerprint().packageId().equals(inspection.fingerprint().packageId()));
		assertFalse(initial.fingerprint().contentSha256().equals(inspection.fingerprint().contentSha256()));
		assertEquals(originalAuthority, preview.requireRefreshAuthority(inspection));

		// Eligibility inspection must not advance the latest
		// accepted package or mutate any authoritative records.
		assertEquals(originalAuthority, authorities.find(subjectId).orElseThrow());
		assertEquals(100L, findSubjectId(database, "Chemistry"));
		assertFalse(authorities.isImported(100L));
		assertTrue(new SqliteSubjectImportLedger(database).find(inspection.fingerprint().packageId()).isEmpty());
		try (Connection connection = database.openConnection()) {
			assertEquals(2, count(connection, "SELECT COUNT(*) FROM subjects"));
			assertEquals(1, count(connection, "SELECT COUNT(*) FROM questions WHERE marks = 2"));
			assertEquals(0, count(connection, "SELECT COUNT(*) FROM questions WHERE marks = 3"));
			assertEquals(1, count(connection, "SELECT COUNT(*) FROM subject_import_packages"));
			assertEquals(1, count(connection, "SELECT COUNT(*) FROM imported_subject_authorities"));
		}

		// The current new-Subject service must remain incapable
		// of publishing an eligible subsequent snapshot.
		assertThrows(IOException.class, () -> coordinator.executeNewSubject(inspection, updated));
		assertImportedSubjectGraph(database, destination, "Biology");
		database.verifyIntegrity();
	}

	@Test
	void refreshPreviewDetectsAddedAndChangedManagedAssets() throws Exception {
		Path original = createIndependentSnapshot("Biology", "refresh-assets-source");
		ApplicationConfig destination = ApplicationConfig.fromDataRoot(directory.resolve("refresh-assets-destination"));
		Files.createDirectories(destination.dataRoot());
		SqliteDatabase database = new SqliteDatabase(destination.databasePath());
		database.initialiseSchema();
		var preview = new SubjectSnapshotIntakePreview(database);
		new SubjectImportCoordinator(destination).executeNewSubject(preview.inspect(original), original);
		ApplicationConfig source = ApplicationConfig.fromDataRoot(directory.resolve("refresh-assets-source"));
		Path retainedFile = source.dataRoot().resolve("subjects/Biology/exams/refresh-notes.txt");
		Files.writeString(retainedFile, "New authoritative notes");
		Path newer = directory.resolve("refresh-assets-newer.examwork");
		new SubjectSnapshotExporter(new SqliteDatabase(source.databasePath()), new ManagedDataLayout(source.dataRoot()))
				.export(1L, newer);
		var plan = preview.previewRefresh(newer, new ManagedDataLayout(destination.dataRoot()));
		var addition = plan.assetChanges().stream()
				.filter(change -> change.managedPath().endsWith("/refresh-notes.txt")).findFirst().orElseThrow();
		assertEquals(SubjectRefreshChangeClassifier.ChangeKind.ADD, addition.kind());
		assertFalse(Files.exists(destination.dataRoot().resolve("subjects/Biology/exams/refresh-notes.txt")));
		var approval = plan.approve("Accept authoritative asset changes");
		approval.requireUnchanged(preview.previewRefresh(newer, new ManagedDataLayout(destination.dataRoot())));

		// Alter destination inventory after the coordinator's review.
		Path unexpected = destination.dataRoot().resolve("subjects/Biology/exams/unexpected.txt");
		Files.writeString(unexpected, "Changed destination inventory");
		var regenerated = preview.previewRefresh(newer, new ManagedDataLayout(destination.dataRoot()));
		assertThrows(PackageFormatException.class, () -> approval.requireUnchanged(regenerated));
		assertTrue(Files.exists(unexpected));
		try (Connection connection = database.openConnection()) {
			assertEquals(1, count(connection, "SELECT COUNT(*) FROM subject_import_packages"));
			assertEquals(1, count(connection, "SELECT COUNT(*) FROM subjects"));
		}
	}

	@Test
	void refreshPreviewPreservesSharedProviderAndOtherSubjects() throws Exception {
		Path biology = createIndependentSnapshot("Biology", "refresh-provider-biology");
		Path chemistry = createIndependentSnapshot("Chemistry", "refresh-provider-chemistry");
		ApplicationConfig destination = ApplicationConfig
				.fromDataRoot(directory.resolve("refresh-provider-destination"));
		Files.createDirectories(destination.dataRoot());
		SqliteDatabase database = new SqliteDatabase(destination.databasePath());
		database.initialiseSchema();
		var preview = new SubjectSnapshotIntakePreview(database);
		var coordinator = new SubjectImportCoordinator(destination);
		coordinator.executeNewSubject(preview.inspect(biology), biology);
		coordinator.executeNewSubject(preview.inspect(chemistry), chemistry);
		ApplicationConfig source = ApplicationConfig.fromDataRoot(directory.resolve("refresh-provider-biology"));
		Path newer = directory.resolve("refresh-provider-newer.examwork");
		new SubjectSnapshotExporter(new SqliteDatabase(source.databasePath()), new ManagedDataLayout(source.dataRoot()))
				.export(1L, newer);
		var plan = preview.previewRefresh(newer, new ManagedDataLayout(destination.dataRoot()));
		assertEquals(0L, plan.countRecords("EXAM_PROVIDER", SubjectRefreshChangeClassifier.ChangeKind.UPDATE));
		assertEquals(0L, plan.countRecords("EXAM_PROVIDER", SubjectRefreshChangeClassifier.ChangeKind.REMOVE));
		assertEquals(0L, plan.countRecords("EXAM_PROVIDER", SubjectRefreshChangeClassifier.ChangeKind.ADD));
		assertEquals(1L, plan.countRecords("EXAM_PROVIDER", SubjectRefreshChangeClassifier.ChangeKind.UNCHANGED));
		try (Connection connection = database.openConnection()) {
			assertEquals(2, count(connection, "SELECT COUNT(*) FROM subjects"));
			assertEquals(2, count(connection, "SELECT COUNT(*) FROM exams"));
			assertEquals(1, count(connection, "SELECT COUNT(*) FROM exam_providers"));
			assertEquals(2, count(connection, "SELECT COUNT(*) FROM subject_import_packages"));
		}
		assertImportedSubjectGraph(database, destination, "Biology");
		assertImportedSubjectGraph(database, destination, "Chemistry");
	}

	@Test
	void refreshRejectsDifferentOriginInstallation() throws Exception {
		ApplicationConfig destination = ApplicationConfig
				.fromDataRoot(directory.resolve("refresh-wrong-origin-target"));
		Files.createDirectories(destination.dataRoot());
		SqliteDatabase database = new SqliteDatabase(destination.databasePath());
		database.initialiseSchema();
		Path original = createIndependentSnapshot("Biology", "refresh-correct-origin");
		Path unrelated = createIndependentSnapshot("Biology", "refresh-unrelated-origin");
		var preview = new SubjectSnapshotIntakePreview(database);
		var initial = preview.inspect(original);
		assertTrue(new SubjectImportCoordinator(destination).executeNewSubject(initial, original).applied());
		long subjectId = findSubjectId(database, "Biology");
		SqliteImportedSubjectAuthorityRepository authorities = new SqliteImportedSubjectAuthorityRepository(database);
		var originalAuthority = authorities.find(subjectId).orElseThrow();
		var inspection = preview.inspect(unrelated);
		assertEquals(SubjectSnapshotIntakePreview.State.EXISTING_SUBJECT, inspection.state());
		assertEquals(subjectId, inspection.existingSubjectId().longValue());
		assertFalse(originalAuthority.originInstallationId().equals(inspection.fingerprint().originInstallationId()));
		PackageFormatException failure = assertThrows(PackageFormatException.class,
				() -> preview.requireRefreshAuthority(inspection));
		assertTrue(failure.getMessage().contains("different originating installation"));
		assertEquals(originalAuthority, authorities.find(subjectId).orElseThrow());
		assertTrue(new SqliteSubjectImportLedger(database).find(inspection.fingerprint().packageId()).isEmpty());
		try (Connection connection = database.openConnection()) {
			assertEquals(1, count(connection, "SELECT COUNT(*) FROM subjects"));
			assertEquals(1, count(connection, "SELECT COUNT(*) FROM subject_import_packages"));
		}
		assertImportedSubjectGraph(database, destination, "Biology");
		database.verifyIntegrity();
	}

	/**
	 * Rejects a changed master snapshot before publishing incoming files or graph
	 * changes, preserving the independently edited master.
	 */
	@Test
	void staleMasterApprovalCannotPublishImport() throws Exception {
		Fixture fixture = fixture();
		SqliteDatabase database = new SqliteDatabase(fixture.master().databasePath());
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("UPDATE subjects SET subject_name = 'Biology changed' WHERE id = 1");
		}
		SubjectImportCoordinator service = new SubjectImportCoordinator(fixture.master());
		assertThrows(IllegalStateException.class, () -> service.execute(1, fixture.plan(), fixture.incoming(),
				directory.resolve("backups"), "Stale coordinator approval"));
		try (Connection connection = database.openConnection()) {
			assertEquals(0, count(connection, "SELECT COUNT(*) FROM questions"));
			assertEquals(0, count(connection, "SELECT COUNT(*) FROM subject_import_packages"));
			assertEquals(1, count(connection, "SELECT COUNT(*) FROM subjects WHERE subject_name = 'Biology changed'"));
		}
		assertFalse(Files.exists(fixture.master().dataRoot().resolve("subjects/Biology/exams/questions.pdf")));
	}

	private void assertEmptySubjectDestination(SqliteDatabase database, ApplicationConfig destination)
			throws Exception {
		try (Connection connection = database.openConnection()) {
			for (String table : List.of("subjects", "syllabus_versions", "curriculum_nodes", "exam_providers",
					"source_documents", "exams", "exam_booklets", "answer_files", "source_questions",
					"shared_question_contexts", "questions", "question_regions", "question_images",
					"question_content_parts", "answers", "answer_regions", "portable_identity_aliases",
					"subject_import_packages", "imported_subject_authorities")) {
				assertEquals(0, count(connection, "SELECT COUNT(*) FROM " + table),
						"Unexpected persisted rows in " + table);
			}
		}
		Path subjectsRoot = destination.dataRoot().resolve("subjects");
		if (Files.exists(subjectsRoot)) {
			try (var paths = Files.walk(subjectsRoot)) {
				assertFalse(paths.anyMatch(Files::isRegularFile), "Failed import left managed files behind");
			}
		}
		database.verifyIntegrity();
	}

	private void assertImportedSubjectGraph(SqliteDatabase database, ApplicationConfig destination, String subject)
			throws Exception {
		long subjectId = findSubjectId(database, subject);
		Path questionPdf = destination.dataRoot().resolve("subjects").resolve(subject).resolve("exams/questions.pdf");
		Path answerPdf = destination.dataRoot().resolve("subjects").resolve(subject).resolve("exams/answers.pdf");
		Path syllabusPdf = destination.dataRoot().resolve("subjects").resolve(subject).resolve("curriculum")
				.resolve(subject + " 2025").resolve("sources/syllabus.pdf");
		assertEquals(subject + " question document", Files.readString(questionPdf));
		assertEquals(subject + " answer document", Files.readString(answerPdf));
		assertEquals(subject + " syllabus document", Files.readString(syllabusPdf));
		try (Connection connection = database.openConnection()) {
			assertEquals(1, count(connection, """
					SELECT COUNT(*)
					FROM exams
					WHERE subject_id =
					""" + subjectId));
			assertEquals(1, count(connection, """
					SELECT COUNT(*)
					FROM syllabus_versions
					WHERE subject_id =
					""" + subjectId));
			assertEquals(3, count(connection, """
					SELECT COUNT(*)
					FROM curriculum_nodes n
					JOIN syllabus_versions s
					    ON s.id = n.syllabus_version_id
					WHERE s.subject_id =
					""" + subjectId));
			try (PreparedStatement statement = connection.prepareStatement("""
					SELECT
					    q.id AS question_id,
					    q.question_text,
					    q.marks,
					    a.id AS answer_id,
					    a.answer_text,
					    n.curriculum_name,
					    sc.id AS context_id,
					    sc.context_label,
					    b.id AS booklet_id,
					    b.booklet_name,
					    af.answer_file_name,
					    qd.relative_path AS question_pdf,
					    ad.relative_path AS answer_pdf,
					    sv.source_pdf_path AS syllabus_pdf
					FROM questions q
					JOIN exam_booklets b
					    ON b.id = q.booklet_id
					JOIN exams e
					    ON e.id = b.exam_id
					JOIN answers a
					    ON a.question_id = q.id
					JOIN curriculum_nodes n
					    ON n.id = q.classification_node_id
					JOIN syllabus_versions sv
					    ON sv.id = n.syllabus_version_id
					JOIN shared_question_contexts sc
					    ON sc.id = q.shared_context_id
					JOIN answer_files af
					    ON af.id = b.answer_file_id
					JOIN source_documents qd
					    ON qd.id = b.source_document_id
					JOIN source_documents ad
					    ON ad.id = af.source_document_id
					WHERE e.subject_id = ?
					  AND sv.subject_id = e.subject_id
					  AND af.exam_id = e.id
					  AND sc.booklet_id = b.id
					""")) {
				statement.setLong(1, subjectId);
				try (ResultSet result = statement.executeQuery()) {
					assertTrue(result.next());
					assertEquals("Imported " + subject + " Question", result.getString("question_text"));
					assertEquals(2, result.getInt("marks"));
					assertEquals(subject + " response", result.getString("answer_text"));
					assertEquals(subject + " Descriptor", result.getString("curriculum_name"));
					assertEquals(subject + " stimulus", result.getString("context_label"));
					assertEquals("Paper 1", result.getString("booklet_name"));
					assertEquals("Marking Guide", result.getString("answer_file_name"));
					assertEquals("subjects/" + subject + "/exams/questions.pdf", result.getString("question_pdf"));
					assertEquals("subjects/" + subject + "/exams/answers.pdf", result.getString("answer_pdf"));
					assertEquals("subjects/" + subject + "/curriculum/" + subject + " 2025/sources/syllabus.pdf",
							result.getString("syllabus_pdf"));
					long questionId = result.getLong("question_id");
					long answerId = result.getLong("answer_id");
					long contextId = result.getLong("context_id");
					long bookletId = result.getLong("booklet_id");
					assertFalse(result.next());
					assertEquals(1, count(connection, """
							SELECT COUNT(*)
							FROM question_regions
							WHERE question_id =
							""" + questionId));
					assertEquals(1, count(connection, """
							SELECT COUNT(*)
							FROM question_images
							WHERE question_id =
							""" + questionId));
					assertEquals(2, count(connection, """
							SELECT COUNT(*)
							FROM question_content_parts
							WHERE question_id =
							""" + questionId));
					assertEquals(1, count(connection, """
							SELECT COUNT(*)
							FROM answer_regions
							WHERE answer_id =
							""" + answerId));
					assertEquals(1, count(connection, """
							SELECT COUNT(*)
							FROM shared_question_context_regions
							WHERE shared_context_id =
							""" + contextId));
					assertEquals(2, count(connection, """
							SELECT COUNT(*)
							FROM shared_question_context_content_parts
							WHERE shared_context_id =
							""" + contextId));
					assertEquals(1, count(connection, """
							SELECT COUNT(*)
							FROM booklet_applicability_reviews
							WHERE booklet_id =
							""" + bookletId));
					try (PreparedStatement images = connection.prepareStatement("""
							SELECT image_png
							FROM question_images
							WHERE question_id = ?
							""")) {
						images.setLong(1, questionId);
						try (ResultSet image = images.executeQuery()) {
							assertTrue(image.next());
							assertArrayEquals(PNG, image.getBytes(1));
							assertFalse(image.next());
						}
					}
					try (PreparedStatement images = connection.prepareStatement("""
							SELECT image_png
							FROM shared_question_context_images
							WHERE shared_context_id = ?
							""")) {
						images.setLong(1, contextId);
						try (ResultSet image = images.executeQuery()) {
							assertTrue(image.next());
							assertArrayEquals(PNG, image.getBytes(1));
							assertFalse(image.next());
						}
					}
				}
			}
		}
	}

	/**
	 * Counts selected rows in a disposable SQLite connection.
	 */
	private int count(Connection connection, String sql) throws Exception {
		try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
			assertTrue(result.next());
			return result.getInt(1);
		}
	}

	private Path createIndependentSnapshot(String subject, String sourceDirectory) throws Exception {
		ApplicationConfig source = ApplicationConfig.fromDataRoot(directory.resolve(sourceDirectory));
		Files.createDirectories(source.dataRoot());
		SqliteDatabase database = new SqliteDatabase(source.databasePath());
		database.initialiseSchema();
		seedSource(database, source, subject);
		database.verifyIntegrity();
		Path archive = directory.resolve(sourceDirectory + ".examwork");
		new SubjectSnapshotExporter(database, new ManagedDataLayout(source.dataRoot())).export(1L, archive);
		assertTrue(Files.isRegularFile(archive));
		return archive;
	}

	/**
	 * Executes trusted fixture SQL only, never package-controlled SQL.
	 */
	private void execute(SqliteDatabase database, String... sql) throws Exception {
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			for (String script : sql) {
				statement.execute(script);
			}
		}
	}

	private long findSubjectId(SqliteDatabase database, String subject) throws SQLException {
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT id
						FROM subjects
						WHERE subject_name = ?
						""")) {
			statement.setString(1, subject);
			try (ResultSet result = statement.executeQuery()) {
				assertTrue(result.next(), "Missing imported Subject: " + subject);
				long id = result.getLong(1);
				assertFalse(result.next(), "Duplicate Subject: " + subject);
				return id;
			}
		}
	}

	/**
	 * Constructs two distinct installations and a complete approved review.
	 */
	private Fixture fixture() throws Exception {
		ApplicationConfig source = ApplicationConfig.fromDataRoot(directory.resolve("source"));
		ApplicationConfig master = ApplicationConfig.fromDataRoot(directory.resolve("master"));
		Files.createDirectories(source.dataRoot());
		Files.createDirectories(master.dataRoot());
		SqliteDatabase sourceDb = new SqliteDatabase(source.databasePath());
		SqliteDatabase masterDb = new SqliteDatabase(master.databasePath());
		sourceDb.initialiseSchema();
		masterDb.initialiseSchema();
		execute(masterDb, """
				INSERT INTO subjects (id, subject_name) VALUES
				    (1, 'Biology'), (2, 'Chemistry'),
				    (3, 'Physics'), (4, 'Engineering')
				""");
		seedSource(sourceDb, source);
		Path masterArchive = directory.resolve("master-biology.examwork");
		Path incoming = directory.resolve("incoming-biology.examwork");
		SubjectSnapshotExporter masterExporter = new SubjectSnapshotExporter(masterDb,
				new ManagedDataLayout(master.dataRoot()));
		SubjectSnapshotExporter incomingExporter = new SubjectSnapshotExporter(sourceDb,
				new ManagedDataLayout(source.dataRoot()));
		masterExporter.export(1, masterArchive);
		incomingExporter.export(1, incoming);
		var preview = new SubjectReconciliationPreviewService().preview(masterArchive, incoming);
		SubjectReconciliationReview review = new SubjectReconciliationReview(preview);

		// Ordinary UI approvals do not require individual justifications.
		// Their identity aliases must inherit the coordinator's import note.
		for (var key : review.reviewKeys()) {
			review.decide(key, Decision.ACCEPT, "");
		}
		Fingerprint reviewedMaster = new SubjectImportSnapshotFingerprint().inspect(masterArchive);
		Fingerprint reviewedIncoming = new SubjectImportSnapshotFingerprint().inspect(incoming);
		SubjectImportApprovalPlan plan = SubjectImportApprovalPlan.prepare(review, reviewedMaster, reviewedIncoming);
		return new Fixture(master, incoming, plan);
	}

	/**
	 * Creates the original Biology integration fixture.
	 */
	private void seedSource(SqliteDatabase database, ApplicationConfig source) throws Exception {
		seedSource(database, source, "Biology");
	}

	/**
	 * Creates an independent Subject, syllabus, examination, booklet, Question,
	 * Answer and mixed-content fixture.
	 */
	private void seedSource(SqliteDatabase database, ApplicationConfig source, String subject) throws Exception {

		// Each independent installation deliberately uses the same
		// integer identifiers. Import must resolve portable identities
		// instead of treating these local IDs as globally unique.
		Path subjectDirectory = source.dataRoot().resolve("subjects").resolve(subject);
		Path questions = subjectDirectory.resolve("exams/questions.pdf");
		Path answers = subjectDirectory.resolve("exams/answers.pdf");
		Path syllabus = subjectDirectory.resolve("curriculum").resolve(subject + " 2025")
				.resolve("sources/syllabus.pdf");
		for (Path path : List.of(questions, answers, syllabus)) {
			Files.createDirectories(path.getParent());
		}
		Files.writeString(questions, subject + " question document");
		Files.writeString(answers, subject + " answer document");
		Files.writeString(syllabus, subject + " syllabus document");

		// These SQL statements contain trusted test-fixture values
		// only, never data read from an incoming package.
		List<String> seed = List.of("""
				INSERT INTO subjects (id, subject_name)
				VALUES (1, 'Biology')
				""", """
				INSERT INTO syllabus_versions (
				    id, subject_id, syllabus_name,
				    is_current, source_pdf_path)
				VALUES (
				    11, 1, 'Biology 2025', 1,
				    'subjects/Biology/curriculum/Biology 2025/sources/syllabus.pdf')
				""", """
				INSERT INTO curriculum_nodes (
				    id, syllabus_version_id, curriculum_code,
				    curriculum_name, curriculum_level, display_order)
				VALUES (
				    101, 11, 'B25U1', 'Biology Unit', 'UNIT', 0)
				""", """
				INSERT INTO curriculum_nodes (
				    id, syllabus_version_id, parent_id,
				    curriculum_code, curriculum_name,
				    curriculum_level, display_order)
				VALUES
				    (102, 11, 101, 'B25T1',
				        'Biology Topic', 'TOPIC', 0),
				    (103, 11, 102, 'B25D1',
				        'Biology Descriptor', 'DESCRIPTOR', 0)
				""", """
				INSERT INTO exam_providers (id, provider_name)
				VALUES (21, 'QCAA')
				""", """
				INSERT INTO source_documents (id, relative_path)
				VALUES
				    (51, 'subjects/Biology/exams/questions.pdf'),
				    (52, 'subjects/Biology/exams/answers.pdf')
				""", """
				INSERT INTO exams (
				    id, subject_id, provider_id, exam_year, exam_name)
				VALUES (
				    31, 1, 21, 2025, 'External Assessment')
				""", """
				INSERT INTO exam_booklets (
				    id, exam_id, source_document_id, booklet_name)
				VALUES (61, 31, 51, 'Paper 1')
				""", """
				INSERT INTO answer_files (
				    id, exam_id, source_document_id, answer_file_name)
				VALUES (71, 31, 52, 'Marking Guide')
				""", """
				UPDATE exam_booklets
				SET answer_file_id = 71
				WHERE id = 61
				""", """
				INSERT INTO source_questions (
				    id, booklet_id, source_question_code)
				VALUES (91, 61, '1')
				""", """
				INSERT INTO shared_question_contexts (
				    id, booklet_id, context_label)
				VALUES (121, 61, 'Biology stimulus')
				""", """
				INSERT INTO questions (
				    id, booklet_id, classification_node_id,
				    question_code, question_text, marks,
				    shared_context_capture_required,
				    source_question_id, shared_context_id)
				VALUES (
				    81, 61, 103, '1',
				    'Imported Biology Question', 2, 0, 91, 121)
				""", """
				INSERT INTO question_regions (
				    question_id, region_order, booklet_id,
				    page_number, x, y, width, height)
				VALUES (
				    81, 0, 61, 2, 0.1, 0.2, 0.5, 0.3)
				""", """
				INSERT INTO shared_question_context_regions (
				    shared_context_id, region_order,
				    page_number, x, y, width, height)
				VALUES (
				    121, 0, 1, 0.1, 0.1, 0.4, 0.4)
				""", """
				INSERT INTO answers (
				    id, question_id, answer_text)
				VALUES (141, 81, 'Biology response')
				""", """
				INSERT INTO answer_regions (
				    answer_id, region_order, answer_file_id,
				    page_number, x, y, width, height)
				VALUES (
				    141, 0, 71, 3, 0.1, 0.2, 0.4, 0.3)
				""");
		for (String sql : seed) {
			execute(database, sql.replace("Biology", subject).replace("B25", subject.substring(0, 1) + "25"));
		}
		try (Connection connection = database.openConnection()) {
			try (PreparedStatement question = connection.prepareStatement("""
					INSERT INTO question_images (
					    id, question_id, image_png)
					VALUES (131, 81, ?)
					""")) {
				question.setBytes(1, PNG);
				question.executeUpdate();
			}
			try (PreparedStatement context = connection.prepareStatement("""
					INSERT INTO shared_question_context_images (
					    shared_context_id, image_order, image_png)
					VALUES (121, 0, ?)
					""")) {
				context.setBytes(1, PNG);
				context.executeUpdate();
			}
		}
		execute(database, """
				INSERT INTO question_content_parts (
				    question_id, content_order,
				    content_type, region_order)
				VALUES (81, 0, 'PDF_REGION', 0)
				""", """
				INSERT INTO question_content_parts (
				    question_id, content_order,
				    content_type, image_id)
				VALUES (81, 1, 'IMAGE', 131)
				""", """
				INSERT INTO shared_question_context_content_parts (
				    shared_context_id, content_order,
				    content_type, region_order)
				VALUES (121, 0, 'PDF_REGION', 0)
				""", """
				INSERT INTO shared_question_context_content_parts (
				    shared_context_id, content_order,
				    content_type, image_order)
				VALUES (121, 1, 'IMAGE', 0)
				""", """
				INSERT INTO booklet_applicability_reviews (booklet_id)
				VALUES (61)
				""");
	}

	/** Complete independent contributor and master fixture. */
	private record Fixture(ApplicationConfig master, Path incoming, SubjectImportApprovalPlan plan) {
	}
}
