package au.edu.eq.questionbank.service.workpackage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import au.edu.eq.questionbank.repository.identity.SqliteExamAssignmentGraphReader;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
import au.edu.eq.questionbank.repository.identity.SqliteSubjectPortableRecordReader;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.workpackage.MasterContributionIntake.IntakeStatus;

/**
 * Assesses received contribution proposals against current master revisions.
 *
 * <p>
 * Every review observes a consistent, read-only snapshot of the coordinator
 * database. It reports potential additions, updates, identical records and
 * conflicts without altering the authoritative corpus or deciding acceptance.
 *
 * <p>
 * Review results must not be used as write authorisation. An application
 * service must revalidate revision evidence within its write transaction.
 */
public final class MasterContributionReviewPlanner {

	private final SqliteDatabase database;

	/**
	 * Creates a read-only contribution review planner.
	 *
	 * @param database initialised coordinator database
	 * @throws NullPointerException if database is null
	 */
	public MasterContributionReviewPlanner(SqliteDatabase database) {
		this.database = Objects.requireNonNull(database, "database");
	}

	/**
	 * Reviews a previously received package against one master snapshot.
	 *
	 * <p>
	 * Intake evidence and master content are read from the same snapshot. A missing
	 * assigned Subject or Exam holds all proposals rather than interpreting them as
	 * safe additions.
	 *
	 * @param packageId received contribution package identity
	 * @return immutable preliminary review assessments
	 * @throws SQLException         if master persistence or graph reading fails
	 * @throws IOException          if snapshot creation or portable content reading
	 *                              fails
	 * @throws NullPointerException if packageId is null
	 */
	public ReviewPlan plan(UUID packageId) throws SQLException, IOException {
		return plan(packageId, false).orElseThrow();
	}

	/**
	 * Prepares a review only when a received contribution remains eligible for
	 * reconciliation against its active assignment.
	 *
	 * <p>
	 * Previously received but unapplied contributions remain eligible, including
	 * contributions whose earlier review was cancelled. Applied, closed, held and
	 * competing returns are not eligible.
	 *
	 * <p>
	 * Eligibility and record assessments are read from the same consistent master
	 * snapshot. Application must still repeat its authoritative validation before
	 * writing.
	 *
	 * @param packageId received contribution package identity
	 * @return preliminary review plan, or empty when the return is no longer
	 *         eligible
	 * @throws SQLException         if master persistence or graph reading fails
	 * @throws IOException          if snapshot creation or portable content reading
	 *                              fails
	 * @throws NullPointerException if packageId is null
	 */
	public Optional<ReviewPlan> planPending(UUID packageId) throws SQLException, IOException {
		return plan(packageId, true);
	}

	private ReviewStatus classify(IntakeStatus intakeStatus, ExchangeRecord proposed, ExchangeRecord current,
			IdentityState outside, boolean missingRoot) {

		// An unreviewed lineage cannot be made eligible merely because
		// its record revisions happen to agree with the current master.
		if (intakeStatus == IntakeStatus.HELD_LINEAGE) {
			return ReviewStatus.HELD_LINEAGE;
		}
		if (missingRoot) {
			return ReviewStatus.HELD_MISSING_MASTER;
		}
		if (outside == IdentityState.ACTIVE) {
			return ReviewStatus.HELD_OUT_OF_SCOPE;
		}
		if (current == null) {
			if (proposed.baselineRevisionId() != null) {
				return ReviewStatus.HELD_MISSING_MASTER;
			}
			return outside == IdentityState.RETIRED ? ReviewStatus.HELD_ID_COLLISION : ReviewStatus.READY_NEW;
		}

		// Exact semantic equality requires no write, even if another
		// revision has already produced the same master content.
		if (current.values().equals(proposed.values())) {
			return ReviewStatus.SKIP_IDENTICAL;
		}
		if (proposed.baselineRevisionId() == null) {
			return ReviewStatus.HELD_ID_COLLISION;
		}
		if (proposed.baselineRevisionId().equals(current.revisionId())) {
			return ReviewStatus.READY_UPDATE;
		}
		return ReviewStatus.HELD_STALE_MASTER;
	}

	private IdentityState findExternalIdentity(PreparedStatement statement, ExchangeRecord proposed)
			throws SQLException {
		statement.setString(1, proposed.recordType());
		statement.setString(2, proposed.recordId().toString());
		try (ResultSet result = statement.executeQuery()) {
			if (!result.next()) {
				return IdentityState.ABSENT;
			}
			boolean retired = result.getInt("retired") != 0;
			if (result.next()) {
				throw new SQLException("Duplicate master portable identity");
			}
			return retired ? IdentityState.RETIRED : IdentityState.ACTIVE;
		}
	}

	private String key(ExchangeRecord record) {
		return record.recordType() + "\0" + record.recordId();
	}

	private Optional<ReviewPlan> plan(UUID packageId, boolean pendingOnly) throws SQLException, IOException {
		Objects.requireNonNull(packageId, "packageId");
		Path directory = Files.createTempDirectory("examwork-master-review-");
		Path snapshotPath = directory.resolve("master-review.db");
		try {
			database.createConsistentSnapshot(snapshotPath);
			SqliteDatabase snapshot = SqliteDatabase.readOnly(snapshotPath);

			// Evaluate eligibility against the same immutable snapshot
			// used to assess the proposed records.
			if (pendingOnly) {
				try (Connection connection = snapshot.openConnection()) {
					if (MasterContributionIntake.findReviewableAssignment(connection, packageId).isEmpty()) {
						return Optional.empty();
					}
				}
			}

			// Read the receipt, proposals, assignment and current Exam
			// from one snapshot to prevent mixed-state comparisons.
			MasterContributionIntake intake = new MasterContributionIntake(snapshot);
			MasterContributionIntake.ReceivedPackage received = intake.find(packageId)
					.orElseThrow(() -> new PackageFormatException("Contribution has not been received: " + packageId));
			List<ExchangeRecord> changes = intake.proposedChanges(packageId);
			if (changes.isEmpty()) {
				throw new PackageFormatException("Received contribution has no proposed records");
			}
			WorkAssignmentLedger.Assignment assignment = new WorkAssignmentLedger(snapshot)
					.find(received.assignmentId())
					.orElseThrow(() -> new SQLException("Issued assignment is missing " + "from master snapshot"));
			if (!received.baselineSnapshotId().equals(assignment.baselineSnapshotId())
					|| !received.masterInstallationId().equals(assignment.masterInstallationId())
					|| !received.contributorId().equals(assignment.contributorId())) {
				throw new PackageFormatException("Stored intake evidence disagrees " + "with issued assignment");
			}
			SqlitePortableIdentityRepository identities = new SqlitePortableIdentityRepository(snapshot);
			var subject = identities.findByPortableId(EntityType.SUBJECT, assignment.subjectPortableId());
			var exam = identities.findByPortableId(EntityType.EXAM, assignment.examPortableId());
			boolean missingRoot = subject.isEmpty() || exam.isEmpty();
			List<ExchangeRecord> masterRecords;
			if (missingRoot) {
				masterRecords = List.of();
			} else {
				var graph = new SqliteExamAssignmentGraphReader(snapshotPath).read(subject.orElseThrow().localId(),
						exam.orElseThrow().localId());
				masterRecords = new SqliteSubjectPortableRecordReader(snapshotPath).read(graph);
			}
			Map<String, ExchangeRecord> currentByIdentity = new HashMap<>();
			for (ExchangeRecord current : masterRecords) {
				if (currentByIdentity.putIfAbsent(key(current), current) != null) {
					throw new SQLException("Duplicate portable identity " + "in master Exam graph");
				}
			}
			List<RecordAssessment> assessments = new ArrayList<>();
			try (Connection connection = snapshot.openConnection();
					PreparedStatement identityLookup = connection.prepareStatement("""
							SELECT retired
							FROM portable_entity_identities
							WHERE entity_type = ?
							  AND portable_id = ?
							""")) {
				for (ExchangeRecord proposed : changes) {
					ExchangeRecord current = currentByIdentity.get(key(proposed));
					IdentityState outside = current == null ? findExternalIdentity(identityLookup, proposed)
							: IdentityState.ABSENT;
					ReviewStatus status = classify(received.status(), proposed, current, outside, missingRoot);
					assessments.add(new RecordAssessment(proposed.recordType(), proposed.recordId(),
							proposed.baselineRevisionId(), proposed.revisionId(),
							current == null ? null : current.revisionId(),
							current == null ? null : recordDigest(current), status));
				}
			}
			return Optional.of(new ReviewPlan(received.packageId(), received.assignmentId(), received.status(),
					Instant.now(), assessments));
		} finally {

			// Remove the temporary snapshot even when eligibility
			// prevents review or assessment fails.
			try {
				Files.deleteIfExists(snapshotPath);
			} finally {
				Files.deleteIfExists(directory);
			}
		}
	}

	private String recordDigest(ExchangeRecord record) throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		new ExamWorkJsonlCodec().writeRecords(List.of(record), output);
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(output.toByteArray()));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	/**
	 * Preliminary record status, not an application decision.
	 */
	public enum ReviewStatus {
		/** Proposed new identity is absent from master. */
		READY_NEW,
		/** Current revision matches the issued baseline revision. */
		READY_UPDATE,
		/** Master content already equals the proposed content. */
		SKIP_IDENTICAL,
		/** Master revision differs from the original baseline. */
		HELD_STALE_MASTER,
		/** Required master record or assignment root is missing. */
		HELD_MISSING_MASTER,
		/** Proposed new identity collides with an existing or retired identity. */
		HELD_ID_COLLISION,
		/** Proposed identity already belongs to another master scope. */
		HELD_OUT_OF_SCOPE,
		/** Package has unresolved or competing return lineage. */
		HELD_LINEAGE
	}

	/**
	 * Preliminary comparison of a proposed record with master evidence.
	 *
	 * @param recordType         portable entity type
	 * @param recordId           portable identity
	 * @param baselineRevisionId issued baseline revision, or null for new records
	 * @param proposedRevisionId contributor's revision
	 * @param masterRevisionId   observed master revision, or null if absent
	 * @param masterRecordSha256 digest of the canonical master record envelope, or
	 *                           null if absent
	 * @param status             preliminary review status
	 */
	public record RecordAssessment(String recordType, UUID recordId, UUID baselineRevisionId, UUID proposedRevisionId,
			UUID masterRevisionId, String masterRecordSha256, ReviewStatus status) {

		/**
		 * Checks required identities and matched master evidence.
		 *
		 * @throws NullPointerException     if required fields are null
		 * @throws IllegalArgumentException if master digest evidence is invalid
		 */
		public RecordAssessment {
			Objects.requireNonNull(recordType, "recordType");
			Objects.requireNonNull(recordId, "recordId");
			Objects.requireNonNull(proposedRevisionId, "proposedRevisionId");
			Objects.requireNonNull(status, "status");
			if (recordType.isBlank()) {
				throw new IllegalArgumentException("Record type is blank");
			}
			if ((masterRevisionId == null) != (masterRecordSha256 == null)) {
				throw new IllegalArgumentException("Master revision and digest must occur together");
			}
			if (masterRecordSha256 != null && !masterRecordSha256.matches("[0-9a-f]{64}")) {
				throw new IllegalArgumentException("Invalid master record digest");
			}
		}
	}

	/**
	 * One package's assessments against a single immutable master snapshot.
	 *
	 * @param packageId    received package identity
	 * @param assignmentId issued assignment identity
	 * @param intakeStatus persisted package lineage status
	 * @param observedAt   time the snapshot review finished
	 * @param assessments  immutable, portable-identity ordered assessments
	 */
	public record ReviewPlan(UUID packageId, UUID assignmentId, IntakeStatus intakeStatus, Instant observedAt,
			List<RecordAssessment> assessments) {

		/**
		 * Defensively retains assessments and requires all review metadata.
		 *
		 * @throws NullPointerException if required values are null
		 */
		public ReviewPlan {
			Objects.requireNonNull(packageId, "packageId");
			Objects.requireNonNull(assignmentId, "assignmentId");
			Objects.requireNonNull(intakeStatus, "intakeStatus");
			Objects.requireNonNull(observedAt, "observedAt");
			assessments = List.copyOf(Objects.requireNonNull(assessments, "assessments"));
		}
	}

	private enum IdentityState {
		ABSENT, ACTIVE, RETIRED
	}
}
