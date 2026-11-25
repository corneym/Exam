package au.edu.eq.questionbank.service.workpackage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.workpackage.ExamWorkManifest.Scope;
import au.edu.eq.questionbank.service.workpackage.ExamWorkManifest.ScopeLevel;

class SqliteContributionReturnLedgerTest {

	@TempDir
	Path directory;

	@Test
	void recordsOneReturnAndRejectsFurtherSubmissions() throws Exception {
		Fixture fixture = fixture();
		SqliteContributionReturnLedger ledger = new SqliteContributionReturnLedger(fixture.database());
		UUID firstId = UUID.randomUUID();
		var first = ledger.recordPublished(manifest(fixture, firstId), null, hash("return-1"));
		assertEquals(1, first.returnNumber());
		assertEquals(null, first.previousPackageId());
		assertEquals(first, ledger.find(firstId).orElseThrow());

		// The identical published package can still be recognised idempotently.
		var replay = ledger.recordPublished(manifest(fixture, firstId), null, hash("return-1"));
		assertEquals(first, replay);
		UUID secondId = UUID.randomUUID();
		assertThrows(SQLException.class,
				() -> ledger.recordPublished(manifest(fixture, secondId), null, hash("return-2")));
		assertThrows(SQLException.class,
				() -> ledger.recordPublished(manifest(fixture, secondId), firstId, hash("return-2")));
		SqliteDatabase restarted = new SqliteDatabase(directory.resolve("returns.db"));
		var restartedLedger = new SqliteContributionReturnLedger(restarted);
		assertEquals(first, restartedLedger.find(firstId).orElseThrow());
		assertEquals(1, restartedLedger.findLatest(fixture.session().assignmentId()).orElseThrow().returnNumber());
		restarted.verifySchema();
		restarted.verifyIntegrity();
	}

	@Test
	void rejectsForeignInstallationAndProtectsStoredEvidence() throws Exception {
		Fixture fixture = fixture();
		var ledger = new SqliteContributionReturnLedger(fixture.database());
		ExamWorkManifest valid = manifest(fixture, UUID.randomUUID());
		ExamWorkManifest wrongOrigin = new ExamWorkManifest(valid.formatVersion(), valid.packageId(),
				valid.packageType(), valid.createdAt(), UUID.randomUUID(), valid.intendedMasterInstallationId(),
				valid.subjectId(), valid.subjectName(), valid.assignmentId(), valid.baselineSnapshotId(),
				valid.producerApplicationVersion(), valid.minimumReaderApplicationVersion(),
				valid.producerSchemaVersion(), valid.minimumReaderSchemaVersion(), valid.scope(), valid.entries());
		assertThrows(SQLException.class, () -> ledger.recordPublished(wrongOrigin, null, hash("return")));
		var recorded = ledger.recordPublished(valid, null, hash("return"));

		// Database triggers must reject direct modification or
		// deletion, not merely changes made through Java services.
		assertThrows(SQLException.class, () -> execute(fixture.database(), """
				UPDATE contributor_return_packages
				SET archive_sha256 = 'bad'
				WHERE package_id = '%s'
				""".formatted(recorded.packageId())));
		assertThrows(SQLException.class, () -> execute(fixture.database(), """
				DELETE FROM contributor_return_packages
				WHERE package_id = '%s'
				""".formatted(recorded.packageId())));
		assertEquals(recorded, ledger.find(recorded.packageId()).orElseThrow());

		// Returning work does not delete, complete or deselect
		// the installed contributor assignment.
		var sessions = new SqliteContributorAssignmentSessionRepository(fixture.database());
		assertTrue(sessions.find(fixture.session().assignmentId()).isPresent());
		assertTrue(sessions.active().isEmpty(), "Published work must not remain an active contributor assignment");
		sessions.clearActive();
		assertTrue(sessions.active().isEmpty());
		assertFalse(ledger.findLatest(fixture.session().assignmentId()).isEmpty());
		fixture.database().verifySchema();
		fixture.database().verifyIntegrity();
	}

	@Test
	void rejectsSecondReturnAndChangedReplayEvidence() throws Exception {
		Fixture fixture = fixture();
		var ledger = new SqliteContributionReturnLedger(fixture.database());
		UUID firstId = UUID.randomUUID();
		UUID secondId = UUID.randomUUID();
		assertThrows(SQLException.class,
				() -> ledger.recordPublished(manifest(fixture, firstId), UUID.randomUUID(), hash("first")));
		var first = ledger.recordPublished(manifest(fixture, firstId), null, hash("first"));
		assertEquals(1, first.returnNumber());
		assertThrows(SQLException.class,
				() -> ledger.recordPublished(manifest(fixture, secondId), null, hash("second")));
		assertThrows(SQLException.class,
				() -> ledger.recordPublished(manifest(fixture, secondId), firstId, hash("second")));
		assertThrows(SQLException.class,
				() -> ledger.recordPublished(manifest(fixture, firstId), null, hash("tampered-first")));
		assertEquals(first, ledger.findLatest(fixture.session().assignmentId()).orElseThrow());
	}

	private void execute(SqliteDatabase database, String sql) throws SQLException {
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}

	private Fixture fixture() throws Exception {
		SqliteDatabase database = new SqliteDatabase(directory.resolve("returns.db"));
		database.initialiseSchema();
		assertEquals(SqliteDatabase.latestSchemaVersion(), database.schemaVersion());
		execute(database, """
				INSERT INTO subjects (id, subject_name)
				VALUES (1, 'Chemistry')
				""");
		execute(database, """
				INSERT INTO syllabus_versions (
				    id, subject_id, syllabus_name, is_current
				)
				VALUES (11, 1, 'Chemistry 2025', 1)
				""");
		execute(database, """
				INSERT INTO exam_providers (id, provider_name)
				VALUES (21, 'QCAA')
				""");
		execute(database, """
				INSERT INTO exams (
				    id, subject_id, provider_id,
				    exam_year, exam_name
				)
				VALUES (31, 1, 21, 2025, 'External Assessment')
				""");
		SqlitePortableIdentityRepository identities = new SqlitePortableIdentityRepository(database);
		UUID installationId = identities.installationId();
		UUID subjectId = identities.requireByLocalId(EntityType.SUBJECT, 1).portableId();
		UUID examId = identities.requireByLocalId(EntityType.EXAM, 31).portableId();
		UUID syllabusId = identities.requireByLocalId(EntityType.SYLLABUS_VERSION, 11).portableId();
		UUID assignmentId = UUID.randomUUID();
		UUID originalPackageId = UUID.randomUUID();
		UUID baselineId = UUID.randomUUID();
		UUID masterId = UUID.randomUUID();
		UUID contributorId = UUID.randomUUID();

		// Generate structurally valid canonical baseline JSONL
		// for this ledger-only integration fixture.
		ExchangeRecord subject = new ExchangeRecord(ExchangeRecord.CURRENT_RECORD_VERSION, "SUBJECT", subjectId,
				UUID.randomUUID(), null, JsonNodeFactory.instance.objectNode().put("subject_name", "Chemistry"));
		ByteArrayOutputStream recordsOutput = new ByteArrayOutputStream();
		new ExamWorkJsonlCodec().writeRecords(List.of(subject), recordsOutput);
		byte[] baselineRecords = recordsOutput.toByteArray();
		byte[] baselineAssets = new byte[0];
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						INSERT INTO contributor_assignment_sessions (
						    assignment_id,
						    package_id,
						    baseline_snapshot_id,
						    master_installation_id,
						    subject_portable_id,
						    exam_portable_id,
						    current_syllabus_portable_id,
						    contributor_id,
						    contributor_name,
						    subject_local_id,
						    exam_local_id,
						    current_syllabus_local_id,
						    issued_at,
						    installed_at,
						    archive_sha256,
						    records_sha256,
						    assets_sha256,
						    baseline_records,
						    baseline_assets
						)
						VALUES (
						    ?, ?, ?, ?, ?, ?, ?, ?, ?,
						    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
						)
						""")) {
			Instant now = Instant.now();
			statement.setString(1, assignmentId.toString());
			statement.setString(2, originalPackageId.toString());
			statement.setString(3, baselineId.toString());
			statement.setString(4, masterId.toString());
			statement.setString(5, subjectId.toString());
			statement.setString(6, examId.toString());
			statement.setString(7, syllabusId.toString());
			statement.setString(8, contributorId.toString());
			statement.setString(9, "Test Contributor");
			statement.setLong(10, 1);
			statement.setLong(11, 31);
			statement.setLong(12, 11);
			statement.setString(13, now.toString());
			statement.setString(14, now.toString());
			statement.setString(15, hash("issued-assignment"));
			statement.setString(16, hashBytes(baselineRecords));
			statement.setString(17, hashBytes(baselineAssets));
			statement.setBytes(18, baselineRecords);
			statement.setBytes(19, baselineAssets);
			assertEquals(1, statement.executeUpdate());
		}
		var sessions = new SqliteContributorAssignmentSessionRepository(database);
		sessions.select(assignmentId);
		return new Fixture(database, sessions.find(assignmentId).orElseThrow(), installationId);
	}

	private String hash(String value) throws Exception {
		return hashBytes(value.getBytes(StandardCharsets.UTF_8));
	}

	private String hashBytes(byte[] bytes) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
	}

	private ExamWorkManifest manifest(Fixture fixture, UUID packageId) {
		var session = fixture.session();
		return new ExamWorkManifest(ExamWorkManifest.CURRENT_FORMAT_VERSION, packageId,
				ExamWorkPackageType.CONTRIBUTION, Instant.now(), fixture.installationId(),
				session.masterInstallationId(), session.subjectPortableId(), "Chemistry", session.assignmentId(),
				session.baselineSnapshotId(), "1.0.1", "1.0.1", SqliteDatabase.latestSchemaVersion(),
				SqliteDatabase.latestSchemaVersion(),
				new Scope(ScopeLevel.EXAMS, List.of(session.examPortableId()), List.of()), List.of());
	}

	private record Fixture(SqliteDatabase database, SqliteContributorAssignmentSessionRepository.Session session,
			UUID installationId) {
	}
}
