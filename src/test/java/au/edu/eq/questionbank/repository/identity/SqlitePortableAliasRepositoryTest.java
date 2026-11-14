package au.edu.eq.questionbank.repository.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class SqlitePortableAliasRepositoryTest {

	@TempDir
	Path tempDirectory;

	/**
	 * Verifies aliases persist when the caller commits the transaction and remain
	 * idempotent on repeated approval.
	 */
	@Test
	void callerOwnedApprovalCommitsWithParentTransaction() throws Exception {
		SqliteDatabase master = createDatabase("alias-commit.db");
		Subject biology = new SqliteCurriculumWriter(master).insertSubject("Biology");
		UUID canonical = new SqlitePortableIdentityRepository(master)
				.requireByLocalId(EntityType.SUBJECT, biology.getId()).portableId();
		UUID originInstallation = UUID.randomUUID();
		UUID originRecord = UUID.randomUUID();
		SqlitePortableAliasRepository aliases = new SqlitePortableAliasRepository(master);
		try (Connection connection = master.openConnection()) {
			connection.setAutoCommit(false);
			aliases.approveAlias(connection, originInstallation, EntityType.SUBJECT, originRecord, canonical,
					"Approved Biology consolidation");
			assertEquals(canonical, aliases.approveAlias(connection, originInstallation, EntityType.SUBJECT,
					originRecord, canonical, "Repeated approval"));
			connection.commit();
		}
		assertEquals(canonical, aliases.findAlias(originInstallation, EntityType.SUBJECT, originRecord).orElseThrow());
		assertEquals(1, countAliases(master));
		master.verifyIntegrity();
	}

	/**
	 * Verifies that import-time alias approval cannot accidentally commit through
	 * an auto-commit connection.
	 */
	@Test
	void callerOwnedApprovalRequiresTransaction() throws Exception {
		SqliteDatabase master = createDatabase("caller-transaction.db");
		Subject biology = new SqliteCurriculumWriter(master).insertSubject("Biology");
		UUID canonical = new SqlitePortableIdentityRepository(master)
				.requireByLocalId(EntityType.SUBJECT, biology.getId()).portableId();
		SqlitePortableAliasRepository aliases = new SqlitePortableAliasRepository(master);
		try (Connection connection = master.openConnection()) {
			assertThrows(IllegalStateException.class, () -> aliases.approveAlias(connection, UUID.randomUUID(),
					EntityType.SUBJECT, UUID.randomUUID(), canonical, "Approved Biology correspondence"));
		}
		assertEquals(0, countAliases(master));
	}

	/**
	 * Verifies rollback removes a newly approved alias along with other uncommitted
	 * import operations.
	 */
	@Test
	void callerOwnedApprovalRollsBackWithParentTransaction() throws Exception {
		SqliteDatabase master = createDatabase("alias-rollback.db");
		Subject biology = new SqliteCurriculumWriter(master).insertSubject("Biology");
		UUID canonical = new SqlitePortableIdentityRepository(master)
				.requireByLocalId(EntityType.SUBJECT, biology.getId()).portableId();
		UUID originInstallation = UUID.randomUUID();
		UUID originRecord = UUID.randomUUID();
		SqlitePortableAliasRepository aliases = new SqlitePortableAliasRepository(master);
		try (Connection connection = master.openConnection()) {
			connection.setAutoCommit(false);
			UUID approved = aliases.approveAlias(connection, originInstallation, EntityType.SUBJECT, originRecord,
					canonical, "Approved independently created Biology Subject");
			assertEquals(canonical, approved);
			assertEquals(canonical,
					aliases.findAlias(connection, originInstallation, EntityType.SUBJECT, originRecord).orElseThrow());
			connection.rollback();
		}
		assertTrue(aliases.findAlias(originInstallation, EntityType.SUBJECT, originRecord).isEmpty());
		assertEquals(0, countAliases(master));
		master.verifyIntegrity();
	}

	/**
	 * Verifies transaction-aware alias approval retains the local-origin identity
	 * protection introduced before Subject import development.
	 */
	@Test
	void callerOwnedTransactionCannotRedirectLocalOriginIdentity() throws Exception {
		SqliteDatabase database = createDatabase("local-transaction.db");
		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(database);
		Subject biology = writer.insertSubject("Biology");
		Subject chemistry = writer.insertSubject("Chemistry");
		SqlitePortableIdentityRepository identities = new SqlitePortableIdentityRepository(database);
		UUID installationId = identities.installationId();
		UUID biologyId = identities.requireByLocalId(EntityType.SUBJECT, biology.getId()).portableId();
		UUID chemistryId = identities.requireByLocalId(EntityType.SUBJECT, chemistry.getId()).portableId();
		SqlitePortableAliasRepository aliases = new SqlitePortableAliasRepository(database);
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			assertThrows(IllegalStateException.class, () -> aliases.approveAlias(connection, installationId,
					EntityType.SUBJECT, biologyId, chemistryId, "Incorrect local-origin identity mapping"));
			connection.rollback();
		}
		assertTrue(aliases.findAlias(installationId, EntityType.SUBJECT, biologyId).isEmpty());
		assertEquals(0, countAliases(database));
		database.verifyIntegrity();
	}

	@Test
	void conflictingCanonicalClaimIsRejectedWithoutChangingApproval() throws Exception {
		SqliteDatabase master = createDatabase("conflicts.db");
		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(master);
		Subject biology = writer.insertSubject("Biology");
		Subject chemistry = writer.insertSubject("Chemistry");
		SqlitePortableIdentityRepository identities = new SqlitePortableIdentityRepository(master);
		UUID biologyId = identities.requireByLocalId(EntityType.SUBJECT, biology.getId()).portableId();
		UUID chemistryId = identities.requireByLocalId(EntityType.SUBJECT, chemistry.getId()).portableId();
		UUID originInstallation = UUID.randomUUID();
		UUID originRecord = UUID.randomUUID();
		SqlitePortableAliasRepository aliases = new SqlitePortableAliasRepository(master);
		aliases.approveAlias(originInstallation, EntityType.SUBJECT, originRecord, biologyId,
				"Reviewed Biology correspondence");
		assertThrows(IllegalStateException.class, () -> aliases.approveAlias(originInstallation, EntityType.SUBJECT,
				originRecord, chemistryId, "Attempted conflicting reassignment"));
		assertEquals(biologyId, aliases.findAlias(originInstallation, EntityType.SUBJECT, originRecord).orElseThrow());
		assertEquals(1, countAliases(master));
	}

	@Test
	void distinctOriginsMayMapToOneCanonicalEntity() throws Exception {
		SqliteDatabase master = createDatabase("origins.db");
		Subject biology = new SqliteCurriculumWriter(master).insertSubject("Biology");
		UUID canonical = new SqlitePortableIdentityRepository(master)
				.requireByLocalId(EntityType.SUBJECT, biology.getId()).portableId();
		UUID firstOrigin = UUID.randomUUID();
		UUID secondOrigin = UUID.randomUUID();
		UUID sourceRecord = UUID.randomUUID();
		SqlitePortableAliasRepository aliases = new SqlitePortableAliasRepository(master);
		aliases.approveAlias(firstOrigin, EntityType.SUBJECT, sourceRecord, canonical, "First origin reviewed");
		aliases.approveAlias(secondOrigin, EntityType.SUBJECT, sourceRecord, canonical, "Second origin reviewed");
		assertEquals(canonical, aliases.findAlias(firstOrigin, EntityType.SUBJECT, sourceRecord).orElseThrow());
		assertEquals(canonical, aliases.findAlias(secondOrigin, EntityType.SUBJECT, sourceRecord).orElseThrow());
		assertEquals(2, countAliases(master));
	}

	@Test
	void independentBiologyDatabasesRequireExplicitApproval() throws Exception {
		SqliteDatabase master = createDatabase("master.db");
		SqliteDatabase contributor = createDatabase("contributor.db");
		Subject masterBiology = new SqliteCurriculumWriter(master).insertSubject("Biology");
		Subject contributorBiology = new SqliteCurriculumWriter(contributor).insertSubject("Biology");
		assertEquals(masterBiology.getId(), contributorBiology.getId());
		SqlitePortableIdentityRepository masterIdentities = new SqlitePortableIdentityRepository(master);
		SqlitePortableIdentityRepository contributorIdentities = new SqlitePortableIdentityRepository(contributor);
		UUID masterSubjectId = masterIdentities.requireByLocalId(EntityType.SUBJECT, masterBiology.getId())
				.portableId();
		UUID contributorSubjectId = contributorIdentities
				.requireByLocalId(EntityType.SUBJECT, contributorBiology.getId()).portableId();
		UUID contributorInstallation = contributorIdentities.installationId();
		assertNotEquals(masterIdentities.installationId(), contributorInstallation);
		assertNotEquals(masterSubjectId, contributorSubjectId);
		SqlitePortableAliasRepository aliases = new SqlitePortableAliasRepository(master);

		// Identical Subject names and integer IDs must not
		// manufacture an alias without approval.
		assertTrue(aliases.findAlias(contributorInstallation, EntityType.SUBJECT, contributorSubjectId).isEmpty());
		UUID approved = aliases.approveAlias(contributorInstallation, EntityType.SUBJECT, contributorSubjectId,
				masterSubjectId, "Reviewed Biology 2019 curriculum correspondence");
		assertEquals(masterSubjectId, approved);

		// Repeating approval with the same mapping must not create
		// a second alias or rewrite the original approval.
		assertEquals(approved, aliases.approveAlias(contributorInstallation, EntityType.SUBJECT, contributorSubjectId,
				masterSubjectId, "Repeated approval"));
		assertEquals(1, countAliases(master));

		// Persisted alias must survive reopening the master database.
		SqlitePortableAliasRepository reopened = new SqlitePortableAliasRepository(
				new SqliteDatabase(tempDirectory.resolve("master.db")));
		assertEquals(masterSubjectId,
				reopened.findAlias(contributorInstallation, EntityType.SUBJECT, contributorSubjectId).orElseThrow());
	}

	@Test
	void localOriginCannotBeAliasedToAnotherLocalEntity() throws Exception {
		SqliteDatabase database = createDatabase("local-origin.db");
		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(database);
		Subject biology = writer.insertSubject("Biology");
		Subject chemistry = writer.insertSubject("Chemistry");
		SqlitePortableIdentityRepository identities = new SqlitePortableIdentityRepository(database);
		UUID localInstallationId = identities.installationId();
		UUID biologyId = identities.requireByLocalId(EntityType.SUBJECT, biology.getId()).portableId();
		UUID chemistryId = identities.requireByLocalId(EntityType.SUBJECT, chemistry.getId()).portableId();
		SqlitePortableAliasRepository aliases = new SqlitePortableAliasRepository(database);
		assertThrows(IllegalStateException.class, () -> aliases.approveAlias(localInstallationId, EntityType.SUBJECT,
				biologyId, chemistryId, "Incorrect local-origin identity mapping"));
		assertTrue(aliases.findAlias(localInstallationId, EntityType.SUBJECT, biologyId).isEmpty());
		assertEquals(0, countAliases(database));
		database.verifyIntegrity();
	}

	@Test
	void localOriginCanOnlyReferToItsOwnActiveIdentity() throws Exception {
		SqliteDatabase database = createDatabase("local-origin-self.db");
		Subject biology = new SqliteCurriculumWriter(database).insertSubject("Biology");
		SqlitePortableIdentityRepository identities = new SqlitePortableIdentityRepository(database);
		UUID installationId = identities.installationId();
		UUID biologyId = identities.requireByLocalId(EntityType.SUBJECT, biology.getId()).portableId();
		SqlitePortableAliasRepository aliases = new SqlitePortableAliasRepository(database);
		assertEquals(biologyId, aliases.approveAlias(installationId, EntityType.SUBJECT, biologyId, biologyId,
				"Explicit confirmation of local identity"));
		assertThrows(IllegalStateException.class, () -> aliases.approveAlias(installationId, EntityType.SUBJECT,
				UUID.randomUUID(), biologyId, "Unknown local-origin identity"));
		assertEquals(1, countAliases(database));
		database.verifyIntegrity();
	}

	@Test
	void rejectedAndRetiredCanonicalIdentitiesAreNotSilentlyReassigned() throws Exception {
		SqliteDatabase master = createDatabase("retirement.db");
		Subject subject = new SqliteCurriculumWriter(master).insertSubject("Biology");
		UUID canonical = new SqlitePortableIdentityRepository(master)
				.requireByLocalId(EntityType.SUBJECT, subject.getId()).portableId();
		UUID origin = UUID.randomUUID();
		UUID external = UUID.randomUUID();
		SqlitePortableAliasRepository aliases = new SqlitePortableAliasRepository(master);
		assertThrows(IllegalArgumentException.class,
				() -> aliases.approveAlias(origin, EntityType.EXAM, external, canonical, "Wrong entity type"));
		assertThrows(IllegalArgumentException.class, () -> aliases.approveAlias(origin, EntityType.SUBJECT, external,
				UUID.randomUUID(), "Unknown canonical record"));
		assertEquals(0, countAliases(master));
		aliases.approveAlias(origin, EntityType.SUBJECT, external, canonical, "Confirmed Biology identity");
		try (Connection connection = master.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					DELETE FROM subjects
					WHERE id = 1
					""");
		}

		// An approved alias retains its historical target.
		assertEquals(canonical, aliases.findAlias(origin, EntityType.SUBJECT, external).orElseThrow());
		assertEquals(canonical,
				aliases.approveAlias(origin, EntityType.SUBJECT, external, canonical, "Repeat after retirement"));
		assertEquals(1, countAliases(master));
	}

	@Test
	void sameOriginUuidCannotBeApprovedUnderDifferentEntityTypes() throws Exception {
		SqliteDatabase master = createDatabase("type-conflict.db");
		Subject subject = new SqliteCurriculumWriter(master).insertSubject("Biology");
		try (Connection connection = master.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					INSERT INTO exam_providers (id, provider_name)
					VALUES (1, 'QCAA')
					""");
		}
		SqlitePortableIdentityRepository identities = new SqlitePortableIdentityRepository(master);
		UUID subjectId = identities.requireByLocalId(EntityType.SUBJECT, subject.getId()).portableId();
		UUID providerId = identities.requireByLocalId(EntityType.EXAM_PROVIDER, 1).portableId();
		UUID originInstallation = UUID.randomUUID();
		UUID originRecord = UUID.randomUUID();
		SqlitePortableAliasRepository aliases = new SqlitePortableAliasRepository(master);
		aliases.approveAlias(originInstallation, EntityType.SUBJECT, originRecord, subjectId,
				"Reviewed Biology identity");

		// Same installation and origin record UUID, but a different
		// type, must be rejected before writing another alias.
		assertThrows(IllegalStateException.class, () -> aliases.approveAlias(originInstallation,
				EntityType.EXAM_PROVIDER, originRecord, providerId, "Conflicting provider claim"));
		assertEquals(subjectId, aliases.findAlias(originInstallation, EntityType.SUBJECT, originRecord).orElseThrow());
		assertTrue(aliases.findAlias(originInstallation, EntityType.EXAM_PROVIDER, originRecord).isEmpty());
		assertEquals(1, countAliases(master));

		// Approval rejection must leave a structurally valid database.
		master.verifySchema();
		master.verifyIntegrity();
	}

	private int countAliases(SqliteDatabase database) throws SQLException {
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT COUNT(*)
						FROM portable_identity_aliases
						""")) {
			assertTrue(result.next());
			return result.getInt(1);
		}
	}

	private SqliteDatabase createDatabase(String filename) throws SQLException {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve(filename));
		database.initialiseSchema();
		return database;
	}
}
