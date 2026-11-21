package au.edu.eq.questionbank.service.workpackage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

/**
 * Provides durable contributor assignment-session selection.
 *
 * <p>
 * Installation evidence is maintained independently from the coordinator's
 * issuance ledger. Selecting or exiting assignment mode never changes an
 * assignment's lifecycle or baseline.
 *
 * <p>
 * This repository does not install Exam graphs or provide an alternative to
 * assignment-aware persistence write guards.
 */
public final class SqliteContributorAssignmentSessionRepository {

	private final SqliteDatabase database;

	/**
	 * Creates a repository for an initialised contributor database.
	 *
	 * @param database contributor's SQLite database
	 * @throws NullPointerException if database is null
	 */
	public SqliteContributorAssignmentSessionRepository(SqliteDatabase database) {
		this.database = Objects.requireNonNull(database, "database");
	}

	/**
	 * Finds the currently selected contributor assignment.
	 *
	 * <p>
	 * Returns empty when ordinary application mode is selected.
	 *
	 * @return active assignment, or empty
	 * @throws SQLException if stored session data cannot be read
	 */

	public Optional<Session> active() throws SQLException {
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT session.*
						FROM contributor_assignment_selection selection
						JOIN contributor_assignment_sessions session
						  ON session.assignment_id = selection.assignment_id
						WHERE selection.singleton_id = 1
						  AND NOT EXISTS (
						      SELECT 1
						      FROM contributor_return_packages packet
						      WHERE packet.assignment_id = session.assignment_id
						  )
						  AND NOT EXISTS (
						      SELECT 1
						      FROM contributor_assignment_hidden hidden
						      WHERE hidden.assignment_id = session.assignment_id
						  )
						""");
				ResultSet result = statement.executeQuery()) {

			if (!result.next()) {
				return Optional.empty();
			}

			Session session = readSession(result);

			if (result.next()) {
				throw new SQLException("Multiple active contributor assignments");
			}

			return Optional.of(session);
		}
	}

	/**
	 * Constructs the scope guard for the selected assignment.
	 *
	 * <p>
	 * A new guard is constructed from persisted local identities. Mutation
	 * operations must still execute their ownership checks within their own
	 * database transactions.
	 *
	 * @return assignment guard, or empty in ordinary mode
	 * @throws SQLException if active session retrieval fails
	 */
	public Optional<SqliteAssignmentMutationGuard> activeGuard() throws SQLException {
		Optional<Session> selected = active();
		if (selected.isEmpty()) {
			return Optional.empty();
		}
		Session session = selected.get();
		Set<Long> syllabuses = new HashSet<>();
		syllabuses.add(session.currentSyllabusLocalId());
		if (session.legacySyllabusLocalId() != null) {
			syllabuses.add(session.legacySyllabusLocalId());
		}
		return Optional
				.of(new SqliteAssignmentMutationGuard(session.subjectLocalId(), session.examLocalId(), syllabuses));
	}

	/**
	 * Exits contributor assignment mode without deleting any work.
	 *
	 * <p>
	 * All installed assignments remain available for later selection.
	 *
	 * @throws SQLException if the active-selection row cannot be updated
	 */
	public void clearActive() throws SQLException {
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						UPDATE contributor_assignment_selection
						SET assignment_id = NULL,
						    updated_at = ?
						WHERE singleton_id = 1
						""")) {
			statement.setString(1, Instant.now().toString());
			if (statement.executeUpdate() != 1) {
				throw new SQLException("Contributor assignment selection is unavailable");
			}
		}
	}

	/**
	 * Finds one installed assignment without changing selection.
	 *
	 * @param assignmentId assignment identity
	 * @return installed session, or empty
	 * @throws SQLException if retrieval fails
	 */
	public Optional<Session> find(UUID assignmentId) throws SQLException {
		Objects.requireNonNull(assignmentId, "assignmentId");
		try (Connection connection = database.openConnection()) {
			return find(connection, assignmentId);
		}
	}

	/**
	 * Returns every installed contributor assignment.
	 *
	 * <p>
	 * Results are ordered by installation time and assignment identity.
	 *
	 * @return immutable list of stored sessions
	 * @throws SQLException if retrieval fails
	 */
	public List<Session> findAll() throws SQLException {
		List<Session> sessions = new ArrayList<>();
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT *
						FROM contributor_assignment_sessions
						ORDER BY installed_at, assignment_id
						""");
				ResultSet result = statement.executeQuery()) {
			while (result.next()) {
				sessions.add(readSession(result));
			}
		}
		return List.copyOf(sessions);
	}

	/**
	 * Retrieves immutable contributor assignment baseline evidence.
	 *
	 * <p>
	 * Both stored byte arrays are checked against their stored SHA-256 digests. The
	 * returned arrays are defensive copies.
	 *
	 * @param assignmentId installed assignment identity
	 * @return original canonical record and asset-index bytes
	 * @throws SQLException if the assignment is unknown, the baseline is damaged or
	 *                      database retrieval fails
	 */
	public Baseline readBaseline(UUID assignmentId) throws SQLException {
		Objects.requireNonNull(assignmentId, "assignmentId");
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT baseline_records,
						       baseline_assets,
						       records_sha256,
						       assets_sha256
						FROM contributor_assignment_sessions
						WHERE assignment_id = ?
						""")) {
			statement.setString(1, assignmentId.toString());
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new SQLException("Unknown contributor assignment: " + assignmentId);
				}
				byte[] records = result.getBytes("baseline_records");
				byte[] assets = result.getBytes("baseline_assets");
				if (records == null || assets == null || !sha256(records).equals(result.getString("records_sha256"))
						|| !sha256(assets).equals(result.getString("assets_sha256"))) {
					throw new SQLException("Contributor assignment baseline failed integrity check");
				}
				if (result.next()) {
					throw new SQLException("Duplicate contributor assignment baseline");
				}
				return new Baseline(records, assets);
			}
		}
	}

	/**
	 * Selects an existing contributor assignment.
	 *
	 * <p>
	 * Selection is independent from installation and does not modify any captured
	 * Question, Answer, Shared Context or baseline record.
	 *
	 * @param assignmentId installed assignment to activate
	 * @return newly selected session
	 * @throws SQLException             if persistence or selection fails
	 * @throws IllegalArgumentException if the assignment is unknown
	 */
	public Session select(UUID assignmentId) throws SQLException {
		Objects.requireNonNull(assignmentId, "assignmentId");
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				Session session = find(connection, assignmentId).orElseThrow(
						() -> new IllegalArgumentException("Unknown contributor assignment: " + assignmentId));

				try (PreparedStatement check = connection.prepareStatement("""
						SELECT CASE
						    WHEN EXISTS (
						        SELECT 1
						        FROM contributor_return_packages packet
						        WHERE packet.assignment_id = ?
						    )
						    OR EXISTS (
						        SELECT 1
						        FROM contributor_assignment_hidden hidden
						        WHERE hidden.assignment_id = ?
						    )
						    THEN 1
						    ELSE 0
						END
						""")) {
					check.setString(1, assignmentId.toString());
					check.setString(2, assignmentId.toString());

					try (ResultSet result = check.executeQuery()) {
						if (!result.next() || result.getInt(1) != 0) {
							throw new IllegalStateException("This contributor assignment is submitted or removed "
									+ "and cannot be activated for capture.");
						}
					}
				}

				try (PreparedStatement statement = connection.prepareStatement("""
						UPDATE contributor_assignment_selection
						SET assignment_id = ?,
						    updated_at = ?
						WHERE singleton_id = 1
						""")) {
					statement.setString(1, assignmentId.toString());
					statement.setString(2, Instant.now().toString());
					if (statement.executeUpdate() != 1) {
						throw new SQLException("Contributor selection singleton is missing");
					}
				}
				connection.commit();
				return session;
			} catch (SQLException | RuntimeException failure) {
				try {
					connection.rollback();
				} catch (SQLException rollbackFailure) {
					failure.addSuppressed(rollbackFailure);
				}
				throw failure;
			}
		}
	}

	/**
	 * Records an installed whole-Exam assignment in a caller-owned transaction.
	 *
	 * <p>
	 * The caller must first validate and stage the original assignment archive
	 * using WorkAssignmentPackageInspector and install its complete domain graph
	 * and managed assets. This operation verifies that all original portable
	 * identities and revisions are present locally.
	 *
	 * <p>
	 * The transaction is neither committed nor rolled back here. Registration does
	 * not activate the assignment.
	 *
	 * @param connection caller-owned installation transaction
	 * @param inspection validated original assignment package inspection
	 * @param localIds   installed Subject, Exam and syllabus identities
	 * @return newly registered or previously installed assignment
	 * @throws SQLException if identities, revisions or persistence disagree
	 * @throws IOException  if the portable baseline is invalid
	 */
	Session registerInstalled(Connection connection, WorkAssignmentPackageInspector.Inspection inspection,
			LocalIds localIds) throws SQLException, IOException {
		Objects.requireNonNull(connection, "connection");
		Objects.requireNonNull(inspection, "inspection");
		Objects.requireNonNull(localIds, "localIds");
		if (connection.getAutoCommit()) {
			throw new IllegalStateException("Assignment installation needs a caller-owned transaction");
		}
		ExamWorkManifest manifest = inspection.manifest();
		ExchangeRecord context = inspection.context();
		if (manifest.packageType() != ExamWorkPackageType.WORK_ASSIGNMENT) {
			throw new PackageFormatException("Only WORK_ASSIGNMENT packages may be installed");
		}
		if (!manifest.originInstallationId().equals(manifest.intendedMasterInstallationId())) {
			throw new PackageFormatException("Assignment master identities are inconsistent");
		}
		if (!inspection.records().equals(inspection.baseline())) {
			throw new PackageFormatException("Installed assignment differs from its issuance baseline");
		}
		new WorkAssignmentScopeValidator().validate(manifest, inspection.records(), context, inspection.assets());
		UUID currentSyllabusId = parseUuid(context.values().path("current_syllabus_id").asText());
		boolean legacyEnabled = context.values().path("legacy_capture_enabled").asBoolean();
		UUID legacySyllabusId = legacyEnabled ? parseUuid(context.values().path("legacy_syllabus_id").asText()) : null;
		UUID contributorId = parseUuid(context.values().path("contributor_id").asText());
		String contributorName = context.values().path("contributor_name").asText().strip();
		if (contributorName.isEmpty() || contributorName.length() > 256) {
			throw new PackageFormatException("Invalid assignment contributor name");
		}
		if (legacyEnabled != (localIds.legacySyllabusId() != null)) {
			throw new SQLException("Installed legacy syllabus does not match assignment");
		}
		UUID examId = manifest.scope().examIds().getFirst();

		// A contributor must retain the original portable identities.
		// A graph imported with newly generated UUIDs is unacceptable.
		requireLocalIdentity(connection, EntityType.SUBJECT, localIds.subjectId(), manifest.subjectId());
		requireLocalIdentity(connection, EntityType.EXAM, localIds.examId(), examId);
		requireLocalIdentity(connection, EntityType.SYLLABUS_VERSION, localIds.currentSyllabusId(), currentSyllabusId);
		if (legacySyllabusId != null) {
			requireLocalIdentity(connection, EntityType.SYLLABUS_VERSION, localIds.legacySyllabusId(),
					legacySyllabusId);
		}
		requireLocalRelationships(connection, localIds);
		ByteArrayOutputStream recordOutput = new ByteArrayOutputStream();
		ByteArrayOutputStream assetOutput = new ByteArrayOutputStream();
		ExamWorkJsonlCodec codec = new ExamWorkJsonlCodec();
		codec.writeRecords(inspection.baseline(), recordOutput);
		codec.writeAssetIndex(inspection.assets(), assetOutput);
		byte[] recordBytes = recordOutput.toByteArray();
		byte[] assetBytes = assetOutput.toByteArray();
		Optional<Session> existing = find(connection, manifest.assignmentId());
		if (existing.isPresent()) {
			Session previous = existing.get();

			// Reopening an installed assignment must not compare against
			// current Question revisions, which may now contain real work.
			if (!previous.packageId().equals(manifest.packageId())
					|| !previous.baselineSnapshotId().equals(manifest.baselineSnapshotId())
					|| !previous.archiveSha256().equals(inspection.archiveSha256())
					|| previous.subjectLocalId() != localIds.subjectId() || previous.examLocalId() != localIds.examId()
					|| previous.currentSyllabusLocalId() != localIds.currentSyllabusId()
					|| !Objects.equals(previous.legacySyllabusLocalId(), localIds.legacySyllabusId())) {
				throw new SQLException("Assignment identity was previously installed " + "with different evidence");
			}
			return previous;
		}
		requireOriginalRecordIdentities(connection, inspection.baseline());
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO contributor_assignment_sessions (
				    assignment_id,
				    package_id,
				    baseline_snapshot_id,
				    master_installation_id,
				    subject_portable_id,
				    exam_portable_id,
				    current_syllabus_portable_id,
				    legacy_syllabus_portable_id,
				    contributor_id,
				    contributor_name,
				    subject_local_id,
				    exam_local_id,
				    current_syllabus_local_id,
				    legacy_syllabus_local_id,
				    issued_at,
				    installed_at,
				    archive_sha256,
				    records_sha256,
				    assets_sha256,
				    baseline_records,
				    baseline_assets
				)
				VALUES (
				    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
				    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
				)
				""")) {
			statement.setString(1, manifest.assignmentId().toString());
			statement.setString(2, manifest.packageId().toString());
			statement.setString(3, manifest.baselineSnapshotId().toString());
			statement.setString(4, manifest.originInstallationId().toString());
			statement.setString(5, manifest.subjectId().toString());
			statement.setString(6, examId.toString());
			statement.setString(7, currentSyllabusId.toString());
			statement.setString(8, legacySyllabusId == null ? null : legacySyllabusId.toString());
			statement.setString(9, contributorId.toString());
			statement.setString(10, contributorName);
			statement.setLong(11, localIds.subjectId());
			statement.setLong(12, localIds.examId());
			statement.setLong(13, localIds.currentSyllabusId());
			if (localIds.legacySyllabusId() == null) {
				statement.setNull(14, java.sql.Types.INTEGER);
			} else {
				statement.setLong(14, localIds.legacySyllabusId());
			}
			statement.setString(15, manifest.createdAt().toString());
			statement.setString(16, Instant.now().toString());
			statement.setString(17, inspection.archiveSha256());
			statement.setString(18, sha256(recordBytes));
			statement.setString(19, sha256(assetBytes));
			statement.setBytes(20, recordBytes);
			statement.setBytes(21, assetBytes);
			if (statement.executeUpdate() != 1) {
				throw new SQLException("Contributor assignment registration did not insert");
			}
		}
		return find(connection, manifest.assignmentId())
				.orElseThrow(() -> new SQLException("Registered assignment was not retained"));
	}

	private Optional<Session> find(Connection connection, UUID assignmentId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT *
				FROM contributor_assignment_sessions
				WHERE assignment_id = ?
				""")) {
			statement.setString(1, assignmentId.toString());
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					return Optional.empty();
				}
				Session session = readSession(result);
				if (result.next()) {
					throw new SQLException("Duplicate contributor assignment identity");
				}
				return Optional.of(session);
			}
		}
	}

	private UUID parseUuid(String value) throws SQLException {
		try {
			UUID uuid = UUID.fromString(value);
			if (!uuid.toString().equals(value)) {
				throw new IllegalArgumentException("Noncanonical stored UUID");
			}
			return uuid;
		} catch (IllegalArgumentException exception) {
			throw new SQLException("Invalid persisted assignment UUID", exception);
		}
	}

	private Session readSession(ResultSet result) throws SQLException {
		String legacyPortable = result.getString("legacy_syllabus_portable_id");
		long legacyLocal = result.getLong("legacy_syllabus_local_id");
		Long legacyLocalId = result.wasNull() ? null : legacyLocal;
		try {
			return new Session(parseUuid(result.getString("assignment_id")), parseUuid(result.getString("package_id")),
					parseUuid(result.getString("baseline_snapshot_id")),
					parseUuid(result.getString("master_installation_id")),
					parseUuid(result.getString("subject_portable_id")), parseUuid(result.getString("exam_portable_id")),
					parseUuid(result.getString("current_syllabus_portable_id")),
					legacyPortable == null ? null : parseUuid(legacyPortable),
					parseUuid(result.getString("contributor_id")), result.getString("contributor_name"),
					result.getLong("subject_local_id"), result.getLong("exam_local_id"),
					result.getLong("current_syllabus_local_id"), legacyLocalId,
					Instant.parse(result.getString("issued_at")), Instant.parse(result.getString("installed_at")),
					result.getString("archive_sha256"));
		} catch (IllegalArgumentException exception) {
			throw new SQLException("Invalid contributor session metadata", exception);
		}
	}

	private void requireLocalIdentity(Connection connection, EntityType type, long localId, UUID expected)
			throws SQLException {
		var identities = new SqlitePortableIdentityRepository(database);
		var actual = identities.findByLocalId(connection, type, localId)
				.orElseThrow(() -> new SQLException("Missing installed identity: " + type + ":" + localId));
		if (!actual.portableId().equals(expected)) {
			throw new SQLException("Installed " + type + " does not preserve its issued portable identity");
		}
	}

	private void requireLocalRelationships(Connection connection, LocalIds localIds) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT subject_id
				FROM exams
				WHERE id = ?
				""")) {
			statement.setLong(1, localIds.examId());
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next() || result.getLong(1) != localIds.subjectId()) {
					throw new SQLException("Installed Exam belongs to the wrong Subject");
				}
			}
		}
		requireSyllabusRelationship(connection, localIds.subjectId(), localIds.currentSyllabusId(), true);
		if (localIds.legacySyllabusId() != null) {
			requireSyllabusRelationship(connection, localIds.subjectId(), localIds.legacySyllabusId(), false);
		}
	}

	private void requireOriginalRecordIdentities(Connection connection, List<ExchangeRecord> baseline)
			throws SQLException {
		var identities = new SqlitePortableIdentityRepository(database);
		for (ExchangeRecord record : baseline) {
			EntityType type;
			try {
				type = EntityType.valueOf(record.recordType());
			} catch (IllegalArgumentException exception) {
				throw new SQLException("Unsupported assignment record type: " + record.recordType(), exception);
			}
			var identity = identities.findByPortableId(connection, type, record.recordId()).orElseThrow(
					() -> new SQLException("Missing imported portable identity: " + type + ":" + record.recordId()));

			// A newly generated revision is not equivalent to the
			// coordinator's issued baseline revision.
			if (record.revisionId() == null || !record.revisionId().equals(identity.revisionId())) {
				throw new SQLException(
						"Installed record revision differs from baseline: " + type + ":" + record.recordId());
			}
		}
	}

	private void requireSyllabusRelationship(Connection connection, long subjectId, long syllabusId, boolean current)
			throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT subject_id, is_current, syllabus_name
				FROM syllabus_versions
				WHERE id = ?
				""")) {
			statement.setLong(1, syllabusId);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next() || result.getLong("subject_id") != subjectId) {
					throw new SQLException("Installed syllabus belongs to the wrong Subject");
				}
				if (current) {
					if (result.getInt("is_current") != 1) {
						throw new SQLException("Assigned current syllabus is not current");
					}
				} else {
					String name = result.getString("syllabus_name");
					if (!("2019".equals(name) || name.endsWith(" 2019"))) {
						throw new SQLException("Legacy capture requires original 2019 syllabus");
					}
				}
			}
		}
	}

	private String sha256(byte[] bytes) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(bytes));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	/**
	 * Local identities produced by the contributor graph installation.
	 *
	 * @param subjectId         local assigned Subject ID
	 * @param examId            local assigned Exam ID
	 * @param currentSyllabusId local current syllabus ID
	 * @param legacySyllabusId  optional local original 2019 syllabus ID
	 */
	public record LocalIds(long subjectId, long examId, long currentSyllabusId, Long legacySyllabusId) {

		/**
		 * Validates the locally resolved assignment identities.
		 *
		 * @throws IllegalArgumentException if any ID is non-positive
		 */
		public LocalIds {
			if (subjectId < 1 || examId < 1 || currentSyllabusId < 1
					|| (legacySyllabusId != null && legacySyllabusId < 1)) {
				throw new IllegalArgumentException("Installed assignment IDs must be positive");
			}
		}
	}

	/**
	 * Original, immutable contributor assignment baseline bytes.
	 *
	 * @param records canonical initial Exam graph JSONL
	 * @param assets  canonical asset-reference index JSONL
	 */
	public record Baseline(byte[] records, byte[] assets) {

		/**
		 * Makes defensive copies of both baseline byte arrays.
		 */
		public Baseline {
			records = Objects.requireNonNull(records, "records").clone();
			assets = Objects.requireNonNull(assets, "assets").clone();
		}

		/**
		 * Returns a defensive copy of the baseline records.
		 *
		 * @return independent canonical JSONL record bytes
		 */
		@Override
		public byte[] records() {
			return records.clone();
		}

		/**
		 * Returns a defensive copy of the baseline asset index.
		 *
		 * @return independent canonical JSONL asset-index bytes
		 */
		@Override
		public byte[] assets() {
			return assets.clone();
		}
	}

	/**
	 * One stored contributor assignment and its local working context.
	 *
	 * @param assignmentId              original assignment identity
	 * @param packageId                 original issuance package identity
	 * @param baselineSnapshotId        immutable baseline identity
	 * @param masterInstallationId      originating master identity
	 * @param subjectPortableId         assigned Subject portable identity
	 * @param examPortableId            assigned Exam portable identity
	 * @param currentSyllabusPortableId required current syllabus identity
	 * @param legacySyllabusPortableId  optional original 2019 syllabus identity
	 * @param contributorId             assigned contributor identity
	 * @param contributorName           assigned contributor label
	 * @param subjectLocalId            locally installed Subject ID
	 * @param examLocalId               locally installed Exam ID
	 * @param currentSyllabusLocalId    locally installed current syllabus ID
	 * @param legacySyllabusLocalId     optional local 2019 syllabus ID
	 * @param issuedAt                  original issuance time
	 * @param installedAt               local installation time
	 * @param archiveSha256             original assignment package digest
	 */
	public record Session(UUID assignmentId, UUID packageId, UUID baselineSnapshotId, UUID masterInstallationId,
			UUID subjectPortableId, UUID examPortableId, UUID currentSyllabusPortableId, UUID legacySyllabusPortableId,
			UUID contributorId, String contributorName, long subjectLocalId, long examLocalId,
			long currentSyllabusLocalId, Long legacySyllabusLocalId, Instant issuedAt, Instant installedAt,
			String archiveSha256) {

		/**
		 * Validates the stored assignment working context.
		 *
		 * @throws IllegalArgumentException if local IDs or hashes are invalid
		 * @throws NullPointerException     if required values are absent
		 */
		public Session {
			Objects.requireNonNull(assignmentId, "assignmentId");
			Objects.requireNonNull(packageId, "packageId");
			Objects.requireNonNull(baselineSnapshotId, "baselineSnapshotId");
			Objects.requireNonNull(masterInstallationId, "masterInstallationId");
			Objects.requireNonNull(subjectPortableId, "subjectPortableId");
			Objects.requireNonNull(examPortableId, "examPortableId");
			Objects.requireNonNull(currentSyllabusPortableId, "currentSyllabusPortableId");
			Objects.requireNonNull(contributorId, "contributorId");
			Objects.requireNonNull(contributorName, "contributorName");
			Objects.requireNonNull(issuedAt, "issuedAt");
			Objects.requireNonNull(installedAt, "installedAt");
			if (subjectLocalId < 1 || examLocalId < 1 || currentSyllabusLocalId < 1
					|| (legacySyllabusLocalId != null && legacySyllabusLocalId < 1)) {
				throw new IllegalArgumentException("Assignment has invalid local identities");
			}
			if ((legacySyllabusLocalId == null) != (legacySyllabusPortableId == null)) {
				throw new IllegalArgumentException("Legacy syllabus identities are inconsistent");
			}
			if (contributorName.isBlank() || contributorName.length() > 256 || archiveSha256 == null
					|| !archiveSha256.matches("[0-9a-f]{64}")) {
				throw new IllegalArgumentException("Invalid contributor session metadata");
			}
		}
	}
}
