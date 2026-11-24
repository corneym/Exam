package au.edu.eq.questionbank.service.workpackage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import au.edu.eq.questionbank.ApplicationVersion;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.workpackage.ExamWorkManifest.ScopeLevel;

/**
 * Persists coordinator-issued offline Exam assignments.
 *
 * The original record baseline and asset reference index are retained
 * independently from the contributor's package.
 */
public final class WorkAssignmentLedger {

	private static final String ASSETS_PATH = ExamWorkAssetIndex.INDEX_PATH;
	private static final String BASELINE_PATH = "baseline/initial-records.jsonl";
	private static final String CONTEXT_PATH = "records/assignment-context.jsonl";
	private static final String RECORDS_PATH = "records/subject-graph.jsonl";
	private final SqliteDatabase database;

	/**
	 * Creates a ledger for the coordinator's master database.
	 *
	 * @param database initialised authoritative SQLite database
	 */
	public WorkAssignmentLedger(SqliteDatabase database) {
		this.database = Objects.requireNonNull(database, "database");
	}

	/**
	 * Closes an active coordinator assignment without importing a return package.
	 *
	 * <p>
	 * The operation is permitted only while the assignment is ACTIVE and no
	 * contribution package has been received for it. The reason is retained as
	 * immutable closure evidence. Inserting the closure atomically changes the
	 * assignment to CANCELLED and releases the Exam's assignment claim.
	 *
	 * <p>
	 * Accepted work from any previous assignment is unaffected.
	 *
	 * @param assignmentId assignment to close
	 * @param reason       coordinator's explanation, between 1 and 1024
	 *                     non-whitespace-trimmed characters after stripping
	 * @return the persisted terminal assignment
	 * @throws SQLException             if persistence fails
	 * @throws IllegalArgumentException if the reason is blank or too long
	 * @throws IllegalStateException    if the assignment is not active or already
	 *                                  has received contribution evidence
	 * @throws NullPointerException     if either argument is null
	 */
	public Assignment closeWithoutReturn(UUID assignmentId, String reason) throws SQLException {
		Objects.requireNonNull(assignmentId, "assignmentId");
		Objects.requireNonNull(reason, "reason");
		String explanation = reason.strip();
		if (explanation.isEmpty() || explanation.length() > 1024) {
			throw new IllegalArgumentException("Assignment closure reason must contain 1 to 1024 characters");
		}
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				try (PreparedStatement statement = connection.prepareStatement("""
						SELECT state
						FROM exam_work_assignment_states
						WHERE assignment_id = ?
						""")) {
					statement.setString(1, assignmentId.toString());
					try (ResultSet result = statement.executeQuery()) {
						if (!result.next() || !"ACTIVE".equals(result.getString("state"))) {
							throw new IllegalStateException(
									"Assignment is unknown or no longer active: " + assignmentId);
						}
					}
				}
				try (PreparedStatement statement = connection.prepareStatement("""
						SELECT 1
						FROM contribution_intake_packages
						WHERE assignment_id = ?
						LIMIT 1
						""")) {
					statement.setString(1, assignmentId.toString());
					try (ResultSet result = statement.executeQuery()) {
						if (result.next()) {
							throw new IllegalStateException("Assignment has received contributions "
									+ "which must be reconciled first: " + assignmentId);
						}
					}
				}

				// The database trigger performs the lifecycle transition
				// and releases the Exam lock atomically with this insert.
				try (PreparedStatement statement = connection.prepareStatement("""
						INSERT INTO exam_work_assignment_closures (
						    assignment_id,
						    closed_state,
						    closure_kind,
						    reason,
						    closed_at
						)
						VALUES (?, 'CANCELLED', 'WITHOUT_RETURN', ?, ?)
						""")) {
					statement.setString(1, assignmentId.toString());
					statement.setString(2, explanation);
					statement.setString(3, Instant.now().toString());
					statement.executeUpdate();
				}
				connection.commit();
			} catch (SQLException | RuntimeException failure) {
				try {
					connection.rollback();
				} catch (SQLException rollbackFailure) {
					failure.addSuppressed(rollbackFailure);
				}
				throw failure;
			}
		}
		return find(assignmentId)
				.orElseThrow(() -> new SQLException("Closed assignment could not be read: " + assignmentId));
	}

	/**
	 * Finds an issued assignment and its current lifecycle state.
	 *
	 * @param assignmentId durable assignment identity
	 * @return assignment, or empty when unknown
	 * @throws SQLException if the ledger cannot be read
	 */
	public Optional<Assignment> find(UUID assignmentId) throws SQLException {
		Objects.requireNonNull(assignmentId, "assignmentId");
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT
						    assignment.assignment_id,
						    assignment.package_id,
						    assignment.baseline_snapshot_id,
						    assignment.master_installation_id,
						    assignment.subject_portable_id,
						    assignment.exam_portable_id,
						    assignment.contributor_id,
						    assignment.contributor_name,
						    assignment.issued_at,
						    assignment.archive_sha256,
						    states.state
						FROM exam_work_assignments assignment
						JOIN exam_work_assignment_states states
						    ON states.assignment_id =
						       assignment.assignment_id
						WHERE assignment.assignment_id = ?
						""")) {
			statement.setString(1, assignmentId.toString());
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					return Optional.empty();
				}
				Assignment assignment = readAssignment(result);
				if (result.next()) {
					throw new SQLException("Duplicate persisted assignment identity");
				}
				return Optional.of(assignment);
			}
		}
	}

	/**
	 * Finds the one active coordinator assignment for an Exam.
	 *
	 * <p>
	 * Terminal assignments remain in the ledger but do not count as active. A
	 * duplicate active assignment is treated as corrupt lifecycle evidence rather
	 * than resolved arbitrarily.
	 *
	 * @param examPortableId portable Exam identity
	 * @return the active assignment, or empty when the Exam is available
	 * @throws SQLException         if the ledger is invalid or cannot be read
	 * @throws NullPointerException if examPortableId is null
	 */
	public Optional<Assignment> findActiveForExam(UUID examPortableId) throws SQLException {
		Objects.requireNonNull(examPortableId, "examPortableId");
		Assignment active = null;
		for (Assignment assignment : findForExam(examPortableId)) {
			if (assignment.state() != State.ACTIVE) {
				continue;
			}
			if (active != null) {
				throw new SQLException("More than one active assignment exists for Exam: " + examPortableId);
			}
			active = assignment;
		}
		return Optional.ofNullable(active);
	}

	/**
	 * Finds all issued assignments for one portable Exam identity.
	 *
	 * @param examPortableId assigned Exam identity
	 * @return assignments in issuance order
	 * @throws SQLException if retrieval fails
	 */
	public List<Assignment> findForExam(UUID examPortableId) throws SQLException {
		Objects.requireNonNull(examPortableId, "examPortableId");
		List<Assignment> assignments = new ArrayList<>();
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT
						    assignment.assignment_id,
						    assignment.package_id,
						    assignment.baseline_snapshot_id,
						    assignment.master_installation_id,
						    assignment.subject_portable_id,
						    assignment.exam_portable_id,
						    assignment.contributor_id,
						    assignment.contributor_name,
						    assignment.issued_at,
						    assignment.archive_sha256,
						    states.state
						FROM exam_work_assignments assignment
						JOIN exam_work_assignment_states states
						    ON states.assignment_id =
						       assignment.assignment_id
						WHERE assignment.exam_portable_id = ?
						ORDER BY assignment.issued_at,
						         assignment.assignment_id
						""")) {
			statement.setString(1, examPortableId.toString());
			try (ResultSet result = statement.executeQuery()) {
				while (result.next()) {
					assignments.add(readAssignment(result));
				}
			}
		}
		return List.copyOf(assignments);
	}

	/**
	 * Retrieves immutable original record and asset-index bytes.
	 *
	 * <p>
	 * Both stored digests are verified before the baseline is returned. A lifecycle
	 * change does not affect either value.
	 *
	 * @param assignmentId issued assignment identity
	 * @return original baseline evidence
	 * @throws SQLException if the assignment does not exist or its baseline
	 *                      integrity check fails
	 */
	public Baseline readBaseline(UUID assignmentId) throws SQLException {
		Objects.requireNonNull(assignmentId, "assignmentId");
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT
						    baseline_records,
						    baseline_assets,
						    records_sha256,
						    assets_sha256
						FROM exam_work_assignments
						WHERE assignment_id = ?
						""")) {
			statement.setString(1, assignmentId.toString());
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new SQLException("Unknown assignment: " + assignmentId);
				}
				byte[] records = result.getBytes("baseline_records");
				byte[] assets = result.getBytes("baseline_assets");
				if (records == null || assets == null || !sha256(records).equals(result.getString("records_sha256"))
						|| !sha256(assets).equals(result.getString("assets_sha256"))) {
					throw new SQLException("Stored assignment baseline failed integrity check");
				}
				return new Baseline(records, assets);
			}
		}
	}

	/**
	 * Registers one validated coordinator-issued WORK_ASSIGNMENT archive.
	 *
	 * <p>
	 * Registration is rejected if the package belongs to another master, does not
	 * contain exactly one assigned Exam, or its immutable baseline disagrees with
	 * the initial records.
	 *
	 * @param archive existing validated .examwork archive
	 * @return newly registered ACTIVE assignment
	 * @throws IOException  if the archive is missing or invalid
	 * @throws SQLException if the coordinator ledger cannot persist the assignment
	 *                      or identity is duplicated
	 */
	public Assignment register(Path archive) throws IOException, SQLException {
		Objects.requireNonNull(archive, "archive");
		ExamWorkManifest manifest = new ExamWorkArchiveValidator().validate(archive, ApplicationVersion.current(),
				database.schemaVersion());
		if (manifest.packageType() != ExamWorkPackageType.WORK_ASSIGNMENT) {
			throw new PackageFormatException("Only WORK_ASSIGNMENT packages can be registered");
		}
		if (manifest.scope().level() != ScopeLevel.EXAMS || manifest.scope().examIds().size() != 1
				|| !manifest.scope().bookletIds().isEmpty()) {
			throw new PackageFormatException("Assignments must cover exactly one complete Exam");
		}
		UUID masterId = new SqlitePortableIdentityRepository(database).installationId();
		if (!masterId.equals(manifest.originInstallationId())
				|| !masterId.equals(manifest.intendedMasterInstallationId())) {
			throw new PackageFormatException("Assignment does not originate from this master");
		}
		String originalArchiveHash = sha256(archive);
		byte[] baseline;
		byte[] assets;
		UUID contributorId;
		String contributorName;
		try (ZipFile zip = new ZipFile(archive.toFile())) {
			byte[] records = readArchiveEntry(zip, RECORDS_PATH);
			baseline = readArchiveEntry(zip, BASELINE_PATH);
			assets = readArchiveEntry(zip, ASSETS_PATH);
			if (!Arrays.equals(records, baseline)) {
				throw new PackageFormatException("Assignment baseline differs from initial records");
			}
			ExamWorkJsonlCodec codec = new ExamWorkJsonlCodec();
			List<ExchangeRecord> initial = codec.readRecords(new ByteArrayInputStream(records));
			List<ExchangeRecord> contexts = codec
					.readRecords(new ByteArrayInputStream(readArchiveEntry(zip, CONTEXT_PATH)));
			if (contexts.size() != 1) {
				throw new PackageFormatException("Assignment must have exactly one context record");
			}
			ExchangeRecord context = contexts.getFirst();
			if (!context.recordType().equals("WORK_ASSIGNMENT_CONTEXT")
					|| !context.recordId().equals(manifest.assignmentId())
					|| !Objects.equals(context.revisionId(), manifest.baselineSnapshotId())) {
				throw new PackageFormatException("Assignment context identity is inconsistent");
			}
			UUID examId = manifest.scope().examIds().getFirst();
			long examCount = initial.stream().filter(record -> record.recordType().equals("EXAM")).count();
			if (examCount != 1 || initial.stream()
					.noneMatch(record -> record.recordType().equals("EXAM") && record.recordId().equals(examId))) {
				throw new PackageFormatException("Baseline does not contain the assigned Exam");
			}
			if (initial.stream().noneMatch(record -> record.recordType().equals("SUBJECT")
					&& record.recordId().equals(manifest.subjectId()))) {
				throw new PackageFormatException("Baseline does not contain its Subject");
			}
			requireContextValue(context, "assignment_id", manifest.assignmentId().toString());
			requireContextValue(context, "baseline_snapshot_id", manifest.baselineSnapshotId().toString());
			requireContextValue(context, "master_installation_id", masterId.toString());
			requireContextValue(context, "subject_id", manifest.subjectId().toString());
			requireContextValue(context, "exam_id", examId.toString());
			requireContextValue(context, "issued_at", manifest.createdAt().toString());
			contributorId = parseUuid(context.values().path("contributor_id").asText(), "contributor_id");
			contributorName = context.values().path("contributor_name").asText().strip();
			if (contributorName.isEmpty() || contributorName.length() > 256) {
				throw new PackageFormatException("Assignment contributor name is invalid");
			}

			// Verify that the explicitly current syllabus is included.
			UUID currentSyllabusId = parseUuid(context.values().path("current_syllabus_id").asText(),
					"current_syllabus_id");
			long currentCount = initial.stream().filter(record -> record.recordType().equals("SYLLABUS_VERSION"))
					.filter(record -> record.values().path("is_current").asInt() == 1).count();
			if (currentCount != 1 || initial.stream()
					.noneMatch(record -> record.recordType().equals("SYLLABUS_VERSION")
							&& record.recordId().equals(currentSyllabusId)
							&& record.values().path("is_current").asInt() == 1)) {
				throw new PackageFormatException("Assignment has no unique current syllabus");
			}
			if (context.values().path("legacy_capture_enabled").asBoolean()) {
				UUID legacySyllabusId = parseUuid(context.values().path("legacy_syllabus_id").asText(),
						"legacy_syllabus_id");
				if (initial.stream()
						.noneMatch(record -> record.recordType().equals("SYLLABUS_VERSION")
								&& record.recordId().equals(legacySyllabusId)
								&& isOriginal2019(record.values().path("syllabus_name").asText()))) {
					throw new PackageFormatException("Legacy assignment lacks original 2019 syllabus");
				}
			}
			ExamWorkAssetIndex index = codec.readAssetIndex(new ByteArrayInputStream(assets));

			// ZIP verification is insufficient on its own. Reject otherwise
			// valid packages containing records from another Exam or Subject.
			new WorkAssignmentScopeValidator().validate(manifest, initial, context, index);
		}
		if (!originalArchiveHash.equals(sha256(archive))) {
			throw new PackageFormatException("Assignment archive changed during registration");
		}
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {

				// Recheck the persisted lifecycle at registration time.
				// The Exam may have changed since the archive was created.
				requireActiveExam(connection, manifest.scope().examIds().getFirst());
				try (PreparedStatement statement = connection.prepareStatement("""
						INSERT INTO exam_work_assignments (
						    assignment_id,
						    package_id,
						    baseline_snapshot_id,
						    master_installation_id,
						    subject_portable_id,
						    exam_portable_id,
						    contributor_id,
						    contributor_name,
						    issued_at,
						    archive_sha256,
						    records_sha256,
						    assets_sha256,
						    baseline_records,
						    baseline_assets
						)
						VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?,
						        ?, ?, ?, ?, ?)
						""")) {
					statement.setString(1, manifest.assignmentId().toString());
					statement.setString(2, manifest.packageId().toString());
					statement.setString(3, manifest.baselineSnapshotId().toString());
					statement.setString(4, masterId.toString());
					statement.setString(5, manifest.subjectId().toString());
					statement.setString(6, manifest.scope().examIds().getFirst().toString());
					statement.setString(7, contributorId.toString());
					statement.setString(8, contributorName);
					statement.setString(9, manifest.createdAt().toString());
					statement.setString(10, originalArchiveHash);
					statement.setString(11, sha256(baseline));
					statement.setString(12, sha256(assets));
					statement.setBytes(13, baseline);
					statement.setBytes(14, assets);
					statement.executeUpdate();
				}
				try (PreparedStatement statement = connection.prepareStatement("""
						INSERT INTO exam_work_assignment_states (
						    assignment_id,
						    state,
						    updated_at
						)
						VALUES (?, 'ACTIVE', ?)
						""")) {
					statement.setString(1, manifest.assignmentId().toString());
					statement.setString(2, Instant.now().toString());
					statement.executeUpdate();
				}
				connection.commit();
			} catch (SQLException | RuntimeException failure) {
				try {
					connection.rollback();
				} catch (SQLException rollbackFailure) {
					failure.addSuppressed(rollbackFailure);
				}
				throw failure;
			}
		}
		return find(manifest.assignmentId()).orElseThrow(() -> new SQLException("Issued assignment was not retained"));
	}

	private boolean isOriginal2019(String name) {
		return name.equals("2019") || name.endsWith(" 2019");
	}

	private MessageDigest newDigest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private UUID parseUuid(String value, String field) throws PackageFormatException {
		try {
			UUID parsed = UUID.fromString(value);
			if (!parsed.toString().equals(value)) {
				throw new IllegalArgumentException("UUID is not canonical");
			}
			return parsed;
		} catch (RuntimeException exception) {
			throw new PackageFormatException("Invalid assignment context UUID: " + field, exception);
		}
	}

	private byte[] readArchiveEntry(ZipFile archive, String path) throws IOException {
		ZipEntry entry = archive.getEntry(path);
		if (entry == null) {
			throw new PackageFormatException("Missing required assignment entry: " + path);
		}
		try (InputStream input = archive.getInputStream(entry)) {
			return input.readAllBytes();
		}
	}

	private Assignment readAssignment(ResultSet result) throws SQLException {
		try {
			return new Assignment(UUID.fromString(result.getString("assignment_id")),
					UUID.fromString(result.getString("package_id")),
					UUID.fromString(result.getString("baseline_snapshot_id")),
					UUID.fromString(result.getString("master_installation_id")),
					UUID.fromString(result.getString("subject_portable_id")),
					UUID.fromString(result.getString("exam_portable_id")),
					UUID.fromString(result.getString("contributor_id")), result.getString("contributor_name"),
					Instant.parse(result.getString("issued_at")), result.getString("archive_sha256"),
					State.valueOf(result.getString("state")));
		} catch (IllegalArgumentException exception) {
			throw new SQLException("Invalid persisted assignment identity or state", exception);
		}
	}

	private void requireActiveExam(Connection connection, UUID examPortableId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT exam.capture_state
				FROM portable_entity_identities identity
				JOIN exams exam ON exam.id = identity.local_id
				WHERE identity.entity_type = 'EXAM'
				  AND identity.portable_id = ?
				  AND identity.retired = 0
				""")) {
			statement.setString(1, examPortableId.toString());
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new SQLException("Assigned Exam is missing from the master database.");
				}
				if (!"ACTIVE".equals(result.getString("capture_state"))) {
					throw new SQLException("Assignment blocked: the Exam is complete. "
							+ "Reactivate it before issuing an assignment.");
				}
			}
		}
	}

	private void requireContextValue(ExchangeRecord context, String name, String expected)
			throws PackageFormatException {
		if (!expected.equals(context.values().path(name).asText())) {
			throw new PackageFormatException("Assignment context mismatch: " + name);
		}
	}

	private String sha256(byte[] bytes) {
		return HexFormat.of().formatHex(newDigest().digest(bytes));
	}

	private String sha256(Path file) throws IOException {
		MessageDigest digest = newDigest();
		try (InputStream input = Files.newInputStream(file)) {
			byte[] buffer = new byte[8192];
			int count;
			while ((count = input.read(buffer)) != -1) {
				digest.update(buffer, 0, count);
			}
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	/**
	 * One persisted coordinator-issued assignment.
	 *
	 * @param assignmentId         durable assignment identity
	 * @param packageId            issued archive identity
	 * @param baselineSnapshotId   immutable baseline identity
	 * @param masterInstallationId coordinator installation
	 * @param subjectPortableId    assigned Subject
	 * @param examPortableId       assigned Exam
	 * @param contributorId        assigned contributor
	 * @param contributorName      contributor label
	 * @param issuedAt             original issuance time
	 * @param archiveSha256        original archive digest
	 * @param state                current lifecycle state
	 */
	public record Assignment(UUID assignmentId, UUID packageId, UUID baselineSnapshotId, UUID masterInstallationId,
			UUID subjectPortableId, UUID examPortableId, UUID contributorId, String contributorName, Instant issuedAt,
			String archiveSha256, State state) {

		/**
		 * Validates an assignment ledger result.
		 */
		public Assignment {
			Objects.requireNonNull(assignmentId, "assignmentId");
			Objects.requireNonNull(packageId, "packageId");
			Objects.requireNonNull(baselineSnapshotId, "baselineSnapshotId");
			Objects.requireNonNull(masterInstallationId, "masterInstallationId");
			Objects.requireNonNull(subjectPortableId, "subjectPortableId");
			Objects.requireNonNull(examPortableId, "examPortableId");
			Objects.requireNonNull(contributorId, "contributorId");
			Objects.requireNonNull(contributorName, "contributorName");
			Objects.requireNonNull(issuedAt, "issuedAt");
			Objects.requireNonNull(archiveSha256, "archiveSha256");
			Objects.requireNonNull(state, "state");
		}
	}

	/**
	 * Immutable original package evidence retained by the master.
	 *
	 * @param records canonical baseline record JSONL bytes
	 * @param assets  canonical asset-reference JSONL bytes
	 */
	public record Baseline(byte[] records, byte[] assets) {

		/**
		 * Defensively copies the stored evidence.
		 */
		public Baseline {
			records = Objects.requireNonNull(records, "records").clone();
			assets = Objects.requireNonNull(assets, "assets").clone();
		}

		/**
		 * Returns a defensive copy of the original baseline records.
		 *
		 * @return independent copy of the canonical baseline JSONL bytes
		 */
		@Override
		public byte[] records() {
			return records.clone();
		}

		/**
		 * Returns a defensive copy of the original asset-reference index.
		 *
		 * @return independent copy of the canonical asset-index JSONL bytes
		 */
		@Override
		public byte[] assets() {
			return assets.clone();
		}
	}

	/**
	 * Lifecycle of the contributor assignment, independently from the Exam's corpus
	 * completion status.
	 */
	public enum State {
		/** Assignment is available for contributor capture work. */
		ACTIVE,
		/** Coordinator has closed the completed assignment. */
		COMPLETE,
		/** Coordinator has cancelled the assignment. */
		CANCELLED
	}
}
