package au.edu.eq.questionbank.service.workpackage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.commons.compress.archivers.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.ApplicationVersion;
import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableAliasRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository;
import au.edu.eq.questionbank.repository.sqlite.HistoricalSchemaFixture;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.repository.sqlite.SqliteImportedSubjectAuthorityRepository;
import au.edu.eq.questionbank.repository.sqlite.SqliteSubjectImportLedger;
import au.edu.eq.questionbank.service.workpackage.ExamWorkArchiveWriter.EntrySource;
import au.edu.eq.questionbank.service.workpackage.SubjectImportSnapshotFingerprint.Fingerprint;
import au.edu.eq.questionbank.service.workpackage.SubjectImportSnapshotFingerprint.SnapshotEvidence;
import au.edu.eq.questionbank.service.workpackage.SubjectSnapshotFreshness.Outcome;

/**
 * Verifies one-file Subject inspection against independent installations.
 */
class SubjectSnapshotIntakePreviewTest {

	@TempDir
	Path directory;

	@Test
	void acceptsNewSubjectDespiteCoincidentLocalIds() throws Exception {
		SqliteDatabase biology = createDatabase("biology-source");
		insertSubject(biology, 1, "Biology");
		SqliteDatabase destination = createDatabase("department");
		insertSubject(destination, 1, "Chemistry");
		Path archive = export(biology, "biology-source", 1, "biology.examwork");
		var inspection = new SubjectSnapshotIntakePreview(destination).inspect(archive);
		assertEquals(SubjectSnapshotIntakePreview.State.NEW_SUBJECT, inspection.state());
		assertNull(inspection.existingSubjectId());
		assertEquals("Biology", inspection.fingerprint().subjectName());
		assertEquals(1, inspection.count("SUBJECT"));
		assertTrue(inspection.recordCounts().containsKey("SUBJECT"));

		// The source and destination both use local ID 1.
		// Preview neither conflates those IDs nor changes the destination.
		assertEquals(1, countSubjects(destination));
		assertEquals("Chemistry", subjectName(destination, 1));
	}

	@Test
	void migratedLegacyAuthorityCannotRefreshWithoutOriginatingFreshnessEvidence() throws Exception {
		SqliteDatabase source = createDatabase("legacy-freshness-source");
		insertSubject(source, 1, "Biology");
		SqliteDatabase destination = createDatabase("legacy-freshness-destination");
		ApplicationConfig config = ApplicationConfig.fromDataRoot(directory.resolve("legacy-freshness-destination"));
		Path original = export(source, "legacy-freshness-source", 1, "legacy-freshness-original.examwork");
		var preview = new SubjectSnapshotIntakePreview(destination);
		var initial = preview.inspect(original);
		assertTrue(new SubjectImportCoordinator(config).executeNewSubject(initial, original).applied());

		// Reconstruct a genuine v48 destination with its imported graph and ledger
		// intact, but without the subsequently introduced originating evidence.
		try (Connection connection = destination.openConnection(); Statement statement = connection.createStatement()) {
			HistoricalSchemaFixture.removeVersion49Objects(statement);
			assertEquals(1, statement.executeUpdate("UPDATE schema_version SET version = 48 WHERE version = 50"));
		}
		destination.verifySchema();
		destination.initialiseSchema();
		var ledger = new SqliteSubjectImportLedger(destination);
		assertTrue(ledger.find(initial.fingerprint().packageId()).isPresent());
		assertTrue(ledger.findOriginatingSnapshotEvidence(initial.fingerprint().packageId()).isEmpty());
		Path next = export(source, "legacy-freshness-source", 1, "legacy-freshness-next.examwork");
		var inspection = preview.inspect(next);
		var authority = preview.requireRefreshAuthority(inspection);
		PackageFormatException failure = assertThrows(PackageFormatException.class,
				() -> preview.requireRefreshEvidence(inspection, next));
		assertTrue(failure.getMessage().contains("no recorded originating freshness evidence"));
		assertEquals(initial.fingerprint().packageId(), authority.latestPackageId());
		assertEquals(authority, new SqliteImportedSubjectAuthorityRepository(destination)
				.find(authority.subjectLocalId()).orElseThrow());
		assertEquals(1, countSubjects(destination));
		assertTrue(ledger.find(inspection.fingerprint().packageId()).isEmpty());
		destination.verifyIntegrity();
	}

	@Test
	void persistedFreshnessRejectsOlderArchivesAndAllowsOnlyNewerSameAuthority() throws Exception {
		SqliteDatabase source = createDatabase("durable-freshness-source");
		insertSubject(source, 1, "Biology");
		SqliteDatabase destination = createDatabase("durable-freshness-destination");
		ApplicationConfig config = ApplicationConfig.fromDataRoot(directory.resolve("durable-freshness-destination"));
		Path exported = export(source, "durable-freshness-source", 1, "durable-freshness-export.examwork");
		Instant acceptedTime = Instant.parse("2026-11-20T01:00:00Z");
		Path accepted = snapshotAt(exported, "durable-freshness-accepted.examwork", acceptedTime);
		Path stale = snapshotAt(exported, "durable-freshness-stale.examwork", acceptedTime.minusSeconds(60));
		Path equalTime = snapshotAt(exported, "durable-freshness-equal.examwork", acceptedTime);
		Path newer = snapshotAt(exported, "durable-freshness-newer.examwork", acceptedTime.plusSeconds(60));
		var preview = new SubjectSnapshotIntakePreview(destination);
		var initial = preview.inspect(accepted);
		var coordinator = new SubjectImportCoordinator(config);
		assertTrue(coordinator.executeNewSubject(initial, accepted).applied());
		var ledger = new SqliteSubjectImportLedger(destination);
		var persisted = ledger.findOriginatingSnapshotEvidence(initial.fingerprint().packageId()).orElseThrow();
		assertEquals(acceptedTime, persisted.createdAt());
		assertEquals(initial.fingerprint().contentSha256(), persisted.contentSha256());
		assertTrue(coordinator.executeNewSubject(initial, accepted).replay());
		assertEquals(persisted,
				ledger.findOriginatingSnapshotEvidence(initial.fingerprint().packageId()).orElseThrow());
		var reopened = new SubjectSnapshotIntakePreview(new SqliteDatabase(config.databasePath()));
		PackageFormatException old = assertThrows(PackageFormatException.class,
				() -> reopened.requireRefreshEvidence(reopened.inspect(stale), stale));
		assertTrue(old.getMessage().contains("older than"));
		PackageFormatException equal = assertThrows(PackageFormatException.class,
				() -> reopened.requireRefreshEvidence(reopened.inspect(equalTime), equalTime));
		assertTrue(equal.getMessage().contains("same creation"));
		var eligible = reopened.requireRefreshEvidence(reopened.inspect(newer), newer);
		assertEquals(Outcome.NEWER_UNCHANGED, eligible.outcome());
		assertEquals(initial.fingerprint(), eligible.accepted().fingerprint());
		assertEquals(acceptedTime, eligible.accepted().createdAt());
		assertEquals(acceptedTime.plusSeconds(60), eligible.incoming().createdAt());
		assertThrows(PackageFormatException.class,
				() -> reopened.requireRefreshEvidence(reopened.inspect(newer), stale));

		// A genuine authoritative curriculum addition changes the semantic digest.
		try (Connection connection = source.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					INSERT INTO syllabus_versions (id, subject_id, syllabus_name, is_current)
					VALUES (11, 1, 'Biology 2025', 1)
					""");
		}
		Path changedExport = export(source, "durable-freshness-source", 1, "durable-freshness-changed-export.examwork");
		Path changed = snapshotAt(changedExport, "durable-freshness-changed.examwork", acceptedTime.plusSeconds(120));
		Path conflict = snapshotAt(changedExport, "durable-freshness-conflict.examwork", acceptedTime);
		var changedEvidence = reopened.requireRefreshEvidence(reopened.inspect(changed), changed);
		assertEquals(Outcome.NEWER_CHANGED, changedEvidence.outcome());
		assertNotEquals(eligible.accepted().fingerprint().contentSha256(),
				changedEvidence.incoming().fingerprint().contentSha256());
		PackageFormatException conflicting = assertThrows(PackageFormatException.class,
				() -> reopened.requireRefreshEvidence(reopened.inspect(conflict), conflict));
		assertTrue(conflicting.getMessage().contains("different authoritative content"));
		assertTrue(ledger.find(changedEvidence.incoming().fingerprint().packageId()).isEmpty());
		assertEquals(1, countSubjects(destination));
		assertEquals(initial.fingerprint().packageId(), eligible.authority().latestPackageId());
		assertTrue(ledger.find(eligible.incoming().fingerprint().packageId()).isEmpty());
		assertEquals(eligible.authority(), new SqliteImportedSubjectAuthorityRepository(destination)
				.find(eligible.authority().subjectLocalId()).orElseThrow());
		destination.verifyIntegrity();
	}

	@Test
	void recognisesAnExistingSubjectWithoutMergingIt() throws Exception {
		SqliteDatabase source = createDatabase("external-biology");
		insertSubject(source, 1, "Biology");
		SqliteDatabase destination = createDatabase("department-existing");
		insertSubject(destination, 51, "Biology");
		Path archive = export(source, "external-biology", 1, "biology-existing.examwork");
		var inspection = new SubjectSnapshotIntakePreview(destination).inspect(archive);
		assertEquals(SubjectSnapshotIntakePreview.State.EXISTING_SUBJECT, inspection.state());
		assertEquals(51L, inspection.existingSubjectId());
		assertEquals(1, countSubjects(destination));
		assertEquals("Biology", subjectName(destination, 51));
	}

	@Test
	void recognisesTwoDifferentSourceSubjectsIndependently() throws Exception {
		SqliteDatabase biology = createDatabase("bio");
		SqliteDatabase physics = createDatabase("physics");
		SqliteDatabase destination = createDatabase("hod");
		insertSubject(biology, 1, "Biology");
		insertSubject(physics, 1, "Physics");
		Path biologyArchive = export(biology, "bio", 1, "bio.examwork");
		Path physicsArchive = export(physics, "physics", 1, "physics2.examwork");
		var preview = new SubjectSnapshotIntakePreview(destination);
		var bioInspection = preview.inspect(biologyArchive);
		var physicsInspection = preview.inspect(physicsArchive);
		assertEquals(SubjectSnapshotIntakePreview.State.NEW_SUBJECT, bioInspection.state());
		assertEquals(SubjectSnapshotIntakePreview.State.NEW_SUBJECT, physicsInspection.state());
		assertEquals("Biology", bioInspection.fingerprint().subjectName());
		assertEquals("Physics", physicsInspection.fingerprint().subjectName());
		assertEquals(0, countSubjects(destination));
	}

	@Test
	void refreshAliasResolverAcceptsNewUnallocatedIdentity() throws Exception {
		SqliteDatabase database = createDatabase("refresh-new-identity");
		UUID origin = UUID.randomUUID();
		UUID incomingId = UUID.randomUUID();
		ExchangeRecord incoming = new ExchangeRecord(ExchangeRecord.CURRENT_RECORD_VERSION, "SUBJECT", incomingId,
				UUID.randomUUID(), null, JsonNodeFactory.instance.objectNode().put("subject_name", "Biology"));
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			var result = new SubjectRefreshAliasResolver(database).resolve(connection, origin, List.of(),
					List.of(incoming));
			var key = new SubjectRefreshChangeClassifier.RecordKey("SUBJECT", incomingId);
			assertEquals(1, result.size());
			assertEquals(incomingId, result.get(key));
			assertThrows(UnsupportedOperationException.class, result::clear);
			connection.rollback();
		}
	}

	@Test
	void refreshAliasResolverRejectsAliasOutsideDestinationGraph() throws Exception {
		SqliteDatabase database = createDatabase("refresh-outside-graph");
		insertSubject(database, 1L, "Biology");
		insertSubject(database, 2L, "Chemistry");
		var identities = new SqlitePortableIdentityRepository(database);
		UUID chemistryId = identities.requireByLocalId(SqlitePortableIdentityRepository.EntityType.SUBJECT, 2L)
				.portableId();
		UUID biologyId = identities.requireByLocalId(SqlitePortableIdentityRepository.EntityType.SUBJECT, 1L)
				.portableId();
		UUID origin = UUID.randomUUID();
		UUID incomingId = UUID.randomUUID();
		var aliases = new SqlitePortableAliasRepository(database);
		aliases.approveAlias(origin, SqlitePortableIdentityRepository.EntityType.SUBJECT, incomingId, chemistryId,
				"Test unrelated Subject alias");
		ExchangeRecord current = new ExchangeRecord(ExchangeRecord.CURRENT_RECORD_VERSION, "SUBJECT", biologyId,
				UUID.randomUUID(), null, JsonNodeFactory.instance.objectNode().put("subject_name", "Biology"));
		ExchangeRecord incoming = new ExchangeRecord(ExchangeRecord.CURRENT_RECORD_VERSION, "SUBJECT", incomingId,
				UUID.randomUUID(), null, JsonNodeFactory.instance.objectNode().put("subject_name", "Biology"));
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			var failure = assertThrows(PackageFormatException.class, () -> new SubjectRefreshAliasResolver(database)
					.resolve(connection, origin, List.of(current), List.of(incoming)));
			assertTrue(failure.getMessage().contains("outside the current"));
			connection.rollback();
		}
	}

	@Test
	void refreshAliasResolverRejectsAutoCommitConnection() throws Exception {
		SqliteDatabase database = createDatabase("refresh-autocommit");
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(true);
			assertThrows(IllegalStateException.class, () -> new SubjectRefreshAliasResolver(database)
					.resolve(connection, UUID.randomUUID(), List.of(), List.of()));
		}
	}

	@Test
	void refreshAliasResolverRejectsDuplicateCanonicalTargets() throws Exception {
		SqliteDatabase database = createDatabase("refresh-duplicate-target");
		insertSubject(database, 1L, "Biology");
		var identities = new SqlitePortableIdentityRepository(database);
		UUID canonicalId = identities.requireByLocalId(SqlitePortableIdentityRepository.EntityType.SUBJECT, 1L)
				.portableId();
		UUID origin = UUID.randomUUID();
		UUID firstId = UUID.randomUUID();
		UUID secondId = UUID.randomUUID();
		var aliases = new SqlitePortableAliasRepository(database);
		aliases.approveAlias(origin, SqlitePortableIdentityRepository.EntityType.SUBJECT, firstId, canonicalId,
				"First test alias");
		aliases.approveAlias(origin, SqlitePortableIdentityRepository.EntityType.SUBJECT, secondId, canonicalId,
				"Second test alias");
		ObjectNode values = JsonNodeFactory.instance.objectNode().put("subject_name", "Biology");
		ExchangeRecord current = new ExchangeRecord(ExchangeRecord.CURRENT_RECORD_VERSION, "SUBJECT", canonicalId,
				UUID.randomUUID(), null, values);
		ExchangeRecord first = new ExchangeRecord(ExchangeRecord.CURRENT_RECORD_VERSION, "SUBJECT", firstId,
				UUID.randomUUID(), null, values);
		ExchangeRecord second = new ExchangeRecord(ExchangeRecord.CURRENT_RECORD_VERSION, "SUBJECT", secondId,
				UUID.randomUUID(), null, values);
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			var failure = assertThrows(PackageFormatException.class, () -> new SubjectRefreshAliasResolver(database)
					.resolve(connection, origin, List.of(current), List.of(first, second)));
			assertTrue(failure.getMessage().contains("Multiple originating records"));
			connection.rollback();
		}
	}

	@Test
	void refreshAliasResolverRejectsUnmappedAllocatedIdentity() throws Exception {
		SqliteDatabase database = createDatabase("refresh-unmapped-collision");
		insertSubject(database, 1L, "Chemistry");
		var identities = new SqlitePortableIdentityRepository(database);
		UUID allocatedId = identities.requireByLocalId(SqlitePortableIdentityRepository.EntityType.SUBJECT, 1L)
				.portableId();
		ExchangeRecord incoming = new ExchangeRecord(ExchangeRecord.CURRENT_RECORD_VERSION, "SUBJECT", allocatedId,
				UUID.randomUUID(), null, JsonNodeFactory.instance.objectNode().put("subject_name", "Biology"));
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			var failure = assertThrows(PackageFormatException.class, () -> new SubjectRefreshAliasResolver(database)
					.resolve(connection, UUID.randomUUID(), List.of(), List.of(incoming)));
			assertTrue(failure.getMessage().contains("collides with an allocated"));
			connection.rollback();
		}
	}

	@Test
	void refreshAliasResolverReservesRetiredCanonicalIdentities() throws Exception {
		SqliteDatabase database = createDatabase("refresh-retired-identity");
		insertSubject(database, 1L, "Biology");
		var identities = new SqlitePortableIdentityRepository(database);
		UUID canonicalId = identities.requireByLocalId(SqlitePortableIdentityRepository.EntityType.SUBJECT, 1L)
				.portableId();
		UUID origin = UUID.randomUUID();
		UUID incomingId = UUID.randomUUID();
		new SqlitePortableAliasRepository(database).approveAlias(origin,
				SqlitePortableIdentityRepository.EntityType.SUBJECT, incomingId, canonicalId,
				"Original imported identity");
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try (PreparedStatement statement = connection.prepareStatement("""
					DELETE FROM subjects
					WHERE id = ?
					""")) {
				statement.setLong(1, 1L);
				statement.executeUpdate();
			}
			assertTrue(identities
					.findByPortableId(connection, SqlitePortableIdentityRepository.EntityType.SUBJECT, canonicalId)
					.isEmpty());
			ObjectNode values = JsonNodeFactory.instance.objectNode().put("subject_name", "Biology");
			ExchangeRecord previouslyAliased = new ExchangeRecord(ExchangeRecord.CURRENT_RECORD_VERSION, "SUBJECT",
					incomingId, UUID.randomUUID(), null, values);
			ExchangeRecord reusingRetiredUuid = new ExchangeRecord(ExchangeRecord.CURRENT_RECORD_VERSION, "SUBJECT",
					canonicalId, UUID.randomUUID(), null, values);
			var resolver = new SubjectRefreshAliasResolver(database);
			var retiredAliasFailure = assertThrows(PackageFormatException.class,
					() -> resolver.resolve(connection, origin, List.of(), List.of(previouslyAliased)));
			assertTrue(retiredAliasFailure.getMessage().contains("missing or retired"));
			var uuidReuseFailure = assertThrows(PackageFormatException.class,
					() -> resolver.resolve(connection, origin, List.of(), List.of(reusingRetiredUuid)));
			assertTrue(uuidReuseFailure.getMessage().contains("collides with an allocated"));
			connection.rollback();
		}
	}

	@Test
	void refreshAliasResolverResolvesApprovedCanonicalIdentity() throws Exception {
		SqliteDatabase database = createDatabase("refresh-approved-alias");
		insertSubject(database, 1L, "Biology");
		var identities = new SqlitePortableIdentityRepository(database);
		UUID canonicalId = identities.requireByLocalId(SqlitePortableIdentityRepository.EntityType.SUBJECT, 1L)
				.portableId();
		UUID origin = UUID.randomUUID();
		UUID incomingId = UUID.randomUUID();
		var aliases = new SqlitePortableAliasRepository(database);
		aliases.approveAlias(origin, SqlitePortableIdentityRepository.EntityType.SUBJECT, incomingId, canonicalId,
				"Approved Subject import");
		ObjectNode values = JsonNodeFactory.instance.objectNode().put("subject_name", "Biology");
		UUID revision = UUID.randomUUID();
		ExchangeRecord current = new ExchangeRecord(ExchangeRecord.CURRENT_RECORD_VERSION, "SUBJECT", canonicalId,
				revision, null, values);
		ExchangeRecord incoming = new ExchangeRecord(ExchangeRecord.CURRENT_RECORD_VERSION, "SUBJECT", incomingId,
				revision, null, values);
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			var mappings = new SubjectRefreshAliasResolver(database).resolve(connection, origin, List.of(current),
					List.of(incoming));
			var key = new SubjectRefreshChangeClassifier.RecordKey("SUBJECT", incomingId);
			assertEquals(canonicalId, mappings.get(key));
			var normalised = new SubjectRefreshIdentityNormaliser().normalise(List.of(incoming), mappings);
			var changes = new SubjectRefreshChangeClassifier().compareRecords(List.of(current), normalised);
			assertEquals(1, changes.size());
			assertEquals(SubjectRefreshChangeClassifier.ChangeKind.UNCHANGED, changes.getFirst().kind());
			assertEquals(canonicalId, changes.getFirst().key().recordId());
			connection.rollback();
		}
	}

	@Test
	void refreshRejectsDifferentSubjectIdentityWithinSameInstallation() throws Exception {
		SqliteDatabase source = createDatabase("refresh-wrong-subject-source");
		insertSubject(source, 1, "Biology");
		SqliteDatabase destination = createDatabase("refresh-wrong-subject-destination");
		ApplicationConfig config = ApplicationConfig
				.fromDataRoot(directory.resolve("refresh-wrong-subject-destination"));
		Path original = export(source, "refresh-wrong-subject-source", 1, "refresh-original-subject.examwork");
		var preview = new SubjectSnapshotIntakePreview(destination);
		var initial = preview.inspect(original);
		assertTrue(new SubjectImportCoordinator(config).executeNewSubject(initial, original).applied());

		// Retain the original authoritative Subject under a
		// different name and create a distinct Subject that
		// reuses its former name on the same installation.
		try (Connection connection = source.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						UPDATE subjects
						SET subject_name = 'Historical Biology'
						WHERE id = 1
						""")) {
			assertEquals(1, statement.executeUpdate());
		}
		insertSubject(source, 2, "Biology");
		Path differentSubject = export(source, "refresh-wrong-subject-source", 2, "refresh-different-subject.examwork");
		var inspection = preview.inspect(differentSubject);
		assertEquals(SubjectSnapshotIntakePreview.State.EXISTING_SUBJECT, inspection.state());
		assertEquals(initial.fingerprint().originInstallationId(), inspection.fingerprint().originInstallationId());
		assertTrue(!initial.fingerprint().subjectId().equals(inspection.fingerprint().subjectId()));
		PackageFormatException failure = assertThrows(PackageFormatException.class,
				() -> preview.requireRefreshAuthority(inspection));
		assertTrue(failure.getMessage().contains("different originating Subject identity"));
		assertEquals(1, countSubjects(destination));
		long localSubjectId = inspection.existingSubjectId().longValue();
		var authority = new SqliteImportedSubjectAuthorityRepository(destination).find(localSubjectId).orElseThrow();
		assertEquals(initial.fingerprint().subjectId(), authority.originSubjectPortableId());
		assertEquals(initial.fingerprint().packageId(), authority.latestPackageId());
		destination.verifyIntegrity();
	}

	@Test
	void refreshRejectsLocallyAuthoritativeSubject() throws Exception {
		SqliteDatabase source = createDatabase("refresh-local-source");
		insertSubject(source, 1, "Biology");
		SqliteDatabase destination = createDatabase("refresh-local-destination");
		insertSubject(destination, 51, "Biology");
		Path archive = export(source, "refresh-local-source", 1, "refresh-local.examwork");
		var preview = new SubjectSnapshotIntakePreview(destination);
		var inspection = preview.inspect(archive);
		assertEquals(SubjectSnapshotIntakePreview.State.EXISTING_SUBJECT, inspection.state());
		assertEquals(51L, inspection.existingSubjectId());
		PackageFormatException failure = assertThrows(PackageFormatException.class,
				() -> preview.requireRefreshAuthority(inspection));
		assertTrue(failure.getMessage().contains("locally authoritative"));
		assertEquals(1, countSubjects(destination));
		assertEquals("Biology", subjectName(destination, 51));
		assertTrue(new SqliteImportedSubjectAuthorityRepository(destination).find(51L).isEmpty());
		destination.verifyIntegrity();
	}

	@Test
	void refreshRejectsNewSubjectAndPreviouslyAppliedPackage() throws Exception {
		SqliteDatabase source = createDatabase("refresh-replay-source");
		insertSubject(source, 1, "Biology");
		SqliteDatabase destination = createDatabase("refresh-replay-destination");
		ApplicationConfig config = ApplicationConfig.fromDataRoot(directory.resolve("refresh-replay-destination"));
		Path archive = export(source, "refresh-replay-source", 1, "refresh-replay.examwork");
		var preview = new SubjectSnapshotIntakePreview(destination);
		var initial = preview.inspect(archive);
		assertEquals(SubjectSnapshotIntakePreview.State.NEW_SUBJECT, initial.state());
		assertThrows(PackageFormatException.class, () -> preview.requireRefreshAuthority(initial));
		assertEquals(0, countSubjects(destination));
		assertTrue(new SubjectImportCoordinator(config).executeNewSubject(initial, archive).applied());
		var replay = preview.inspect(archive);
		assertEquals(SubjectSnapshotIntakePreview.State.ALREADY_IMPORTED, replay.state());

		// The ordinary intake route may report an idempotent replay,
		// but it must not prepare another refresh transaction.
		assertThrows(PackageFormatException.class, () -> preview.requireRefreshAuthority(replay));
		assertEquals(1, countSubjects(destination));
		destination.verifyIntegrity();
	}

	@Test
	void rejectsCaseOnlyChangeToImportedSubjectName() throws Exception {
		SqliteDatabase source = createDatabase("refresh-name-case-source");
		insertSubject(source, 1L, "Biology");
		SqliteDatabase destination = createDatabase("refresh-name-case-destination");
		ApplicationConfig config = ApplicationConfig.fromDataRoot(directory.resolve("refresh-name-case-destination"));
		Path original = export(source, "refresh-name-case-source", 1L, "refresh-name-case-original.examwork");
		var preview = new SubjectSnapshotIntakePreview(destination);
		var initial = preview.inspect(original);
		assertTrue(new SubjectImportCoordinator(config).executeNewSubject(initial, original).applied());
		long localSubjectId = new SqliteCurriculumRepository(destination).findAllSubjects().getFirst().getId();
		var authority = new SqliteImportedSubjectAuthorityRepository(destination).find(localSubjectId).orElseThrow();

		// A case-insensitive collision must not permit the
		// canonical Subject name to change.
		try (Connection connection = source.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						UPDATE subjects
						SET subject_name = 'BIOLOGY'
						WHERE id = 1
						""")) {
			assertEquals(1, statement.executeUpdate());
		}
		Path changed = export(source, "refresh-name-case-source", 1L, "refresh-name-case-changed.examwork");
		SQLException failure = assertThrows(SQLException.class, () -> preview.inspect(changed));
		assertTrue(failure.getMessage().contains("Subject names are immutable"));
		assertEquals(1, countSubjects(destination));
		assertEquals("Biology", subjectName(destination, localSubjectId));
		assertEquals(authority,
				new SqliteImportedSubjectAuthorityRepository(destination).find(localSubjectId).orElseThrow());
		destination.verifyIntegrity();
	}

	@Test
	void rejectsChangedNameDuringTransactionalRefreshRevalidation() throws Exception {
		SqliteDatabase source = createDatabase("refresh-name-revalidation-source");
		insertSubject(source, 1L, "Biology");
		SqliteDatabase destination = createDatabase("refresh-name-revalidation-destination");
		ApplicationConfig config = ApplicationConfig
				.fromDataRoot(directory.resolve("refresh-name-revalidation-destination"));
		Path original = export(source, "refresh-name-revalidation-source", 1L,
				"refresh-name-revalidation-original.examwork");
		var preview = new SubjectSnapshotIntakePreview(destination);
		var initial = preview.inspect(original);
		assertTrue(new SubjectImportCoordinator(config).executeNewSubject(initial, original).applied());
		long localSubjectId = new SqliteCurriculumRepository(destination).findAllSubjects().getFirst().getId();
		var authority = new SqliteImportedSubjectAuthorityRepository(destination).find(localSubjectId).orElseThrow();
		Path subsequent = export(source, "refresh-name-revalidation-source", 1L,
				"refresh-name-revalidation-subsequent.examwork");
		var inspection = preview.inspect(subsequent);
		assertEquals(SubjectSnapshotIntakePreview.State.EXISTING_SUBJECT, inspection.state());
		assertEquals(authority, preview.requireRefreshAuthority(inspection));

		// Construct deliberately inconsistent inspection evidence.
		// This represents a changed incoming name presented to the
		// transactional revalidation boundary after valid inspection.
		var originalFingerprint = inspection.fingerprint();
		var invalidFingerprint = new Fingerprint(originalFingerprint.packageId(),
				originalFingerprint.originInstallationId(), originalFingerprint.subjectId(), "Biological Science",
				originalFingerprint.contentSha256(), originalFingerprint.archiveSha256());
		var invalidInspection = new SubjectSnapshotIntakePreview.Inspection(invalidFingerprint, inspection.state(),
				inspection.existingSubjectId(), inspection.recordCounts(), inspection.records(), inspection.assets());
		try (Connection connection = destination.openConnection()) {
			connection.setAutoCommit(false);
			try {
				SQLException failure = assertThrows(SQLException.class,
						() -> preview.requireRefreshAuthority(connection, invalidInspection));
				assertTrue(failure.getMessage().contains("Subject names are immutable"));
			} finally {
				connection.rollback();
			}
		}
		assertEquals(authority,
				new SqliteImportedSubjectAuthorityRepository(destination).find(localSubjectId).orElseThrow());
		assertEquals("Biology", subjectName(destination, localSubjectId));
		assertEquals(1, countSubjects(destination));
		assertTrue(new SqliteSubjectImportLedger(destination).find(originalFingerprint.packageId()).isEmpty());
		destination.verifyIntegrity();
	}

	@Test
	void rejectsCorruptArchiveWithoutChangingDestination() throws Exception {
		SqliteDatabase destination = createDatabase("corrupt-destination");
		insertSubject(destination, 1, "Chemistry");
		Path archive = directory.resolve("broken.examwork");
		Files.writeString(archive, "not an ExamWork archive");
		assertThrows(IOException.class, () -> new SubjectSnapshotIntakePreview(destination).inspect(archive));
		assertEquals(1, countSubjects(destination));
		assertEquals("Chemistry", subjectName(destination, 1));
	}

	@Test
	void rejectsImportingSubjectBackIntoItsOriginInstallation() throws Exception {
		SqliteDatabase source = createDatabase("source");
		insertSubject(source, 1, "Physics");
		Path archive = export(source, "source", 1, "physics.examwork");
		assertThrows(IOException.class, () -> new SubjectSnapshotIntakePreview(source).inspect(archive));
		assertEquals("Physics", subjectName(source, 1));
	}

	@Test
	void renamedOriginCannotBecomeDuplicateNewSubject() throws Exception {
		SqliteDatabase source = createDatabase("refresh-rename-source");
		insertSubject(source, 1, "Biology");
		SqliteDatabase destination = createDatabase("refresh-rename-destination");
		ApplicationConfig config = ApplicationConfig.fromDataRoot(directory.resolve("refresh-rename-destination"));
		Path original = export(source, "refresh-rename-source", 1, "refresh-rename-original.examwork");
		var preview = new SubjectSnapshotIntakePreview(destination);
		var initial = preview.inspect(original);
		SubjectImportCoordinator coordinator = new SubjectImportCoordinator(config);
		assertTrue(coordinator.executeNewSubject(initial, original).applied());
		long localSubjectId = new SqliteCurriculumRepository(destination).findAllSubjects().getFirst().getId();
		var originalAuthority = new SqliteImportedSubjectAuthorityRepository(destination).find(localSubjectId)
				.orElseThrow();

		// Deliberately create an invalid authoritative snapshot
		// with an altered Subject name but unchanged originating
		// installation and portable Subject identity.
		try (Connection connection = source.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						UPDATE subjects
						SET subject_name = ?
						WHERE id = ?
						""")) {
			statement.setString(1, "Biological Science");
			statement.setLong(2, 1L);
			assertEquals(1, statement.executeUpdate());
		}
		Path renamed = export(source, "refresh-rename-source", 1, "refresh-renamed.examwork");
		var fingerprint = new SubjectImportSnapshotFingerprint().inspect(renamed);
		assertEquals(originalAuthority.originInstallationId(), fingerprint.originInstallationId());
		assertEquals(originalAuthority.originSubjectPortableId(), fingerprint.subjectId());
		assertEquals("Biological Science", fingerprint.subjectName());

		// The invalid package must be rejected during intake,
		// not presented as an eligible refresh or new Subject.
		SQLException failure = assertThrows(SQLException.class, () -> preview.inspect(renamed));
		assertTrue(failure.getMessage().contains("Subject names are immutable"));
		assertEquals(1, countSubjects(destination));
		assertEquals("Biology", subjectName(destination, localSubjectId));
		assertEquals(originalAuthority,
				new SqliteImportedSubjectAuthorityRepository(destination).find(localSubjectId).orElseThrow());
		assertTrue(new SqliteSubjectImportLedger(destination).find(fingerprint.packageId()).isEmpty());

		// A separate local Subject with the incoming name also
		// creates an ambiguous destination that must fail closed.
		insertSubject(destination, 99, "Biological Science");
		SQLException ambiguous = assertThrows(SQLException.class, () -> preview.inspect(renamed));
		assertTrue(ambiguous.getMessage().contains("different destination Subjects"));
		assertEquals(2, countSubjects(destination));
		assertEquals("Biology", subjectName(destination, localSubjectId));
		assertEquals("Biological Science", subjectName(destination, 99));
		assertEquals(originalAuthority,
				new SqliteImportedSubjectAuthorityRepository(destination).find(localSubjectId).orElseThrow());
		destination.verifyIntegrity();
	}

	@Test
	void snapshotEvidenceDistinguishesContentFromPackageIdentity() throws Exception {
		SqliteDatabase source = createDatabase("snapshot-content-source");
		insertSubject(source, 1L, "Biology");
		Path firstArchive = export(source, "snapshot-content-source", 1L, "snapshot-content-first.examwork");
		Path secondArchive = export(source, "snapshot-content-source", 1L, "snapshot-content-second.examwork");
		SubjectImportSnapshotFingerprint fingerprints = new SubjectImportSnapshotFingerprint();
		SnapshotEvidence first = fingerprints.inspectEvidence(firstArchive);
		SnapshotEvidence second = fingerprints.inspectEvidence(secondArchive);

		// Independent exports of unchanged Subject content
		// retain the same semantic content fingerprint.
		assertEquals(first.fingerprint().contentSha256(), second.fingerprint().contentSha256());
		assertNotEquals(first.fingerprint().packageId(), second.fingerprint().packageId());
		assertNotEquals(first.fingerprint().archiveSha256(), second.fingerprint().archiveSha256());
		assertEquals(first.fingerprint().subjectId(), second.fingerprint().subjectId());
		assertEquals(first.fingerprint().originInstallationId(), second.fingerprint().originInstallationId());
		source.verifyIntegrity();
	}

	@Test
	void snapshotEvidenceRetainsManifestCreationTime() throws Exception {
		SqliteDatabase source = createDatabase("snapshot-evidence-source");
		insertSubject(source, 1L, "Biology");
		Path archive = directory.resolve("snapshot-evidence-original.examwork");
		ExamWorkManifest manifest = new SubjectSnapshotExporter(source,
				new ManagedDataLayout(directory.resolve("snapshot-evidence-source"))).export(1L, archive);
		SubjectImportSnapshotFingerprint fingerprints = new SubjectImportSnapshotFingerprint();
		SnapshotEvidence evidence = fingerprints.inspectEvidence(archive);
		assertEquals(manifest.createdAt(), evidence.createdAt());
		assertEquals(manifest.packageId(), evidence.fingerprint().packageId());
		assertEquals(manifest.originInstallationId(), evidence.fingerprint().originInstallationId());
		assertEquals(manifest.subjectId(), evidence.fingerprint().subjectId());
		assertEquals(manifest.subjectName(), evidence.fingerprint().subjectName());
		assertEquals(fingerprints.inspect(archive), evidence.fingerprint());

		// Filesystem modification time must not influence
		// the originating creation timestamp or archive digest.
		Files.setLastModifiedTime(archive, FileTime.from(manifest.createdAt().plusSeconds(86400)));
		assertEquals(evidence, fingerprints.inspectEvidence(archive));
		source.verifyIntegrity();
	}

	private int countSubjects(SqliteDatabase database) throws SQLException {
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM subjects");
				ResultSet result = statement.executeQuery()) {
			return result.next() ? result.getInt(1) : -1;
		}
	}

	private SqliteDatabase createDatabase(String name) throws Exception {
		ApplicationConfig config = ApplicationConfig.fromDataRoot(directory.resolve(name));
		Files.createDirectories(config.dataRoot());
		SqliteDatabase database = new SqliteDatabase(config.databasePath());
		database.initialiseSchema();
		return database;
	}

	private Path export(SqliteDatabase database, String sourceDirectory, long subjectId, String archiveName)
			throws Exception {
		Path destination = directory.resolve(archiveName);
		new SubjectSnapshotExporter(database, new ManagedDataLayout(directory.resolve(sourceDirectory)))
				.export(subjectId, destination);
		assertTrue(Files.isRegularFile(destination));
		return destination;
	}

	private void insertSubject(SqliteDatabase database, long id, String name) throws SQLException {
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						INSERT INTO subjects (id, subject_name)
						VALUES (?, ?)
						""")) {
			statement.setLong(1, id);
			statement.setString(2, name);
			assertEquals(1, statement.executeUpdate());
		}
	}

	private Path snapshotAt(Path archive, String filename, Instant createdAt) throws Exception {
		ExamWorkManifest original = new ExamWorkArchiveValidator().validate(archive, ApplicationVersion.current(),
				SqliteDatabase.latestSchemaVersion());

		// Rewrite only manifest identity and time; retain the validated real graph
		// and asset bytes. Fixed instants avoid timing-dependent tests.
		ExamWorkManifest replacement = new ExamWorkManifest(original.formatVersion(), UUID.randomUUID(),
				original.packageType(), createdAt, original.originInstallationId(),
				original.intendedMasterInstallationId(), original.subjectId(), original.subjectName(),
				original.assignmentId(), original.baselineSnapshotId(), original.producerApplicationVersion(),
				original.minimumReaderApplicationVersion(), original.producerSchemaVersion(),
				original.minimumReaderSchemaVersion(), original.scope(), original.entries());
		Path target = directory.resolve(filename);
		try (ZipFile zip = ZipFile.builder().setPath(archive).get(); var output = Files.newOutputStream(target)) {
			Map<String, EntrySource> sources = new HashMap<>();
			for (var entry : original.entries()) {
				sources.put(entry.path(), () -> zip.getInputStream(zip.getEntry(entry.path())));
			}
			new ExamWorkArchiveWriter().write(replacement, sources, output);
		}
		assertEquals(replacement, new ExamWorkArchiveValidator().validate(target, ApplicationVersion.current(),
				SqliteDatabase.latestSchemaVersion()));
		return target;
	}

	private String subjectName(SqliteDatabase database, long id) throws SQLException {
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT subject_name
						FROM subjects
						WHERE id = ?
						""")) {
			statement.setLong(1, id);
			try (ResultSet result = statement.executeQuery()) {
				assertTrue(result.next());
				return result.getString(1);
			}
		}
	}
}
