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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import au.edu.eq.questionbank.repository.identity.SqlitePortableContentRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.workpackage.MasterContributionDecisionLedger.Choice;
import au.edu.eq.questionbank.service.workpackage.MasterContributionDecisionLedger.RecordDecision;
import au.edu.eq.questionbank.service.workpackage.MasterContributionDecisionLedger.RecordKey;
import au.edu.eq.questionbank.service.workpackage.MasterContributionDecisionLedger.RecordedReview;
import au.edu.eq.questionbank.service.workpackage.MasterContributionReviewPlanner.ReviewStatus;

/**
 * Atomically applies the supported subset of coordinator-reviewed
 * contributions.
 *
 * <p>
 * The initial implementation supports only existing QUESTION records whose sole
 * content change is question_text. Other queued record changes are rejected
 * before publication. Rejected and identical-skip decisions can be recorded
 * alongside supported Question updates.
 *
 * <p>
 * Domain mutations and terminal result evidence use one SQLite transaction.
 * Recorded revision and content evidence are rechecked after obtaining the
 * SQLite writer reservation. No managed assets are published by this class.
 */
public final class MasterContributionApplicationCoordinator {

	private final SqliteDatabase database;

	/**
	 * Creates a contribution application coordinator.
	 *
	 * @param database initialised authoritative master database
	 * @throws NullPointerException if database is null
	 */
	public MasterContributionApplicationCoordinator(SqliteDatabase database) {
		this.database = Objects.requireNonNull(database, "database");
	}

	/**
	 * Applies a complete coordinator-approved return, records canonical entity
	 * mappings and closes its assignment atomically.
	 *
	 * <p>
	 * Supported queued operations are new Questions and Answers, existing Question
	 * text/classification updates, and existing Answer text updates. Region, image
	 * and structural changes remain unsupported. All approved Question operations
	 * run before Answer operations so new Answers can reference newly imported
	 * Questions.
	 *
	 * <p>
	 * Domain changes, application results, canonical mappings, assignment closure
	 * and Exam unlock commit together. Any failure rolls the entire transaction
	 * back.
	 *
	 * @param reviewId immutable coordinator review identity
	 * @return terminal application outcomes and replay indicator
	 * @throws SQLException         if validation, dependencies, review evidence or
	 *                              persistence prevent atomic application
	 * @throws IOException          if portable content cannot be processed
	 * @throws NullPointerException if reviewId is null
	 */
	public ApplicationResult execute(UUID reviewId) throws SQLException, IOException {
		Objects.requireNonNull(reviewId, "reviewId");
		RecordedReview review = new MasterContributionDecisionLedger(database).find(reviewId)
				.orElseThrow(() -> new SQLException("Unknown contribution review: " + reviewId));
		List<ExchangeRecord> incoming = new MasterContributionIntake(database).proposedChanges(review.packageId());
		Map<RecordKey, ExchangeRecord> proposedByKey = new HashMap<>();
		for (ExchangeRecord proposed : incoming) {
			RecordKey key = new RecordKey(proposed.recordType(), proposed.recordId());
			if (proposedByKey.putIfAbsent(key, proposed) != null) {
				throw new SQLException("Duplicate received proposal identity");
			}
		}
		if (proposedByKey.size() != review.decisions().size()) {
			throw new SQLException("Review does not match its complete proposal set");
		}
		for (RecordDecision decision : review.decisions()) {
			ExchangeRecord proposed = proposedByKey.get(decision.key());
			if (proposed == null || !proposed.revisionId().equals(decision.proposedRevisionId())
					|| !Objects.equals(proposed.baselineRevisionId(), decision.baselineRevisionId())) {
				throw new SQLException("Review and immutable proposal evidence disagree");
			}
			if (decision.choice() == Choice.HOLD) {
				throw new SQLException("Held decisions cannot be applied");
			}
			if (decision.choice() == Choice.QUEUE_APPLY) {
				boolean supportedType = "QUESTION".equals(decision.key().recordType())
						|| "ANSWER".equals(decision.key().recordType());
				boolean supportedStatus = decision.preliminaryStatus() == ReviewStatus.READY_UPDATE
						|| decision.preliminaryStatus() == ReviewStatus.READY_NEW;
				if (!supportedType || !supportedStatus) {
					throw new SQLException("Only reviewed Question and Answer " + "scalar operations are supported");
				}
			}
		}

		// Apply parents before dependent Answers. This ordering is
		// independent of the alphabetical order of stored review rows.
		List<RecordDecision> ordered = new ArrayList<>(review.decisions());
		ordered.sort(Comparator.comparingInt(decision -> switch (decision.key().recordType()) {
		case "QUESTION" -> 0;
		case "ANSWER" -> 1;
		default -> 2;
		}));
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				Optional<ApplicationResult> previous = find(connection, reviewId);
				if (previous.isPresent()) {
					requireReconciledClosure(connection, review.packageId());
					ApplicationResult original = previous.orElseThrow();
					connection.commit();
					return new ApplicationResult(original.reviewId(), original.packageId(), original.completedAt(),
							original.outcomes(), true);
				}
				Instant completedAt = Instant.now();

				// The first write reserves SQLite's writer transaction.
				insertBatch(connection, review, completedAt);
				UUID assignmentId = requireOneActiveReturn(connection, review.packageId());
				long examId = requireAssignedExamLocalId(connection, review.packageId());
				Map<RecordKey, Long> localIds = new HashMap<>();
				Map<RecordKey, UUID> canonicalIds = new HashMap<>();
				List<RecordOutcome> outcomes = new ArrayList<>();
				for (RecordDecision decision : ordered) {
					RecordOutcome outcome = applyDecision(connection, reviewId, examId, decision,
							proposedByKey.get(decision.key()), localIds, canonicalIds);
					insertOutcome(connection, reviewId, outcome);
					if (outcome.outcome() != Outcome.REJECTED) {
						insertCanonicalMapping(connection, reviewId, decision.key(), canonicalIds.get(decision.key()),
								localIds.get(decision.key()));
					}
					outcomes.add(outcome);
				}

				// Return results in the same canonical order used by
				// find(...) and idempotent replay.
				outcomes.sort(Comparator.comparing((RecordOutcome outcome) -> outcome.key().recordType())
						.thenComparing(outcome -> outcome.key().recordId().toString()));

				// No permit survives into the assignment closure.
				try (PreparedStatement statement = connection.prepareStatement("""
						SELECT 1
						FROM master_contribution_write_permits
						LIMIT 1
						"""); ResultSet result = statement.executeQuery()) {
					if (result.next()) {
						throw new SQLException("Contribution write permit remains active");
					}
				}
				insertReconciledClosure(connection, assignmentId, review.packageId(), reviewId, completedAt);
				connection.commit();
				return new ApplicationResult(reviewId, review.packageId(), completedAt, outcomes, false);
			} catch (SQLException | IOException | RuntimeException failure) {
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
	 * Finds durable terminal application evidence for a review.
	 *
	 * @param reviewId review UUID
	 * @return completed application, or empty if not applied
	 * @throws SQLException         if retrieval fails
	 * @throws NullPointerException if reviewId is null
	 */
	public Optional<ApplicationResult> find(UUID reviewId) throws SQLException {
		Objects.requireNonNull(reviewId, "reviewId");
		try (Connection connection = database.openConnection()) {
			return find(connection, reviewId);
		}
	}

	private RecordOutcome applyDecision(Connection connection, UUID reviewId, long examId, RecordDecision decision,
			ExchangeRecord proposed, Map<RecordKey, Long> localIds, Map<RecordKey, UUID> canonicalIds)
			throws SQLException, IOException {
		RecordKey key = decision.key();
		if (decision.choice() == Choice.REJECT) {
			return new RecordOutcome(key, Outcome.REJECTED, null);
		}
		if (decision.choice() == Choice.SKIP_IDENTICAL) {
			ExchangeRecord current = requireCurrent(connection, decision);
			requireFreshEvidence(decision, current);
			if (!current.values().equals(proposed.values())) {
				throw new SQLException("Master is no longer identical to skipped proposal");
			}
			localIds.put(key, requireLocalId(connection, key));
			canonicalIds.put(key, current.recordId());
			return new RecordOutcome(key, Outcome.SKIPPED, current.revisionId());
		}
		if (decision.preliminaryStatus() == ReviewStatus.READY_UPDATE) {
			ExchangeRecord current = requireCurrent(connection, decision);
			requireFreshEvidence(decision, current);
		}
		MasterContributionScalarWriter.Applied applied = new MasterContributionScalarWriter(database).apply(connection,
				reviewId, examId, decision, proposed, localIds, canonicalIds);
		localIds.put(key, applied.localId());
		canonicalIds.put(key, applied.canonicalId());
		return new RecordOutcome(key, Outcome.APPLIED, applied.revisionId());
	}

	private Optional<ApplicationResult> find(Connection connection, UUID reviewId) throws SQLException {
		try (PreparedStatement batch = connection.prepareStatement("""
				SELECT package_id, completed_at
				FROM contribution_application_batches
				WHERE review_id = ?
				"""); PreparedStatement entries = connection.prepareStatement("""
				SELECT record_type, record_id, outcome,
				       resulting_revision_id
				FROM contribution_application_results
				WHERE review_id = ?
				ORDER BY record_type, record_id
				""")) {
			batch.setString(1, reviewId.toString());
			try (ResultSet result = batch.executeQuery()) {
				if (!result.next()) {
					return Optional.empty();
				}
				UUID packageId = UUID.fromString(result.getString("package_id"));
				Instant completedAt = Instant.parse(result.getString("completed_at"));
				if (result.next()) {
					throw new SQLException("Duplicate terminal application identity");
				}
				List<RecordOutcome> outcomes = new ArrayList<>();
				entries.setString(1, reviewId.toString());
				try (ResultSet rows = entries.executeQuery()) {
					while (rows.next()) {
						String revision = rows.getString("resulting_revision_id");
						outcomes.add(new RecordOutcome(
								new RecordKey(rows.getString("record_type"),
										UUID.fromString(rows.getString("record_id"))),
								Outcome.valueOf(rows.getString("outcome")),
								revision == null ? null : UUID.fromString(revision)));
					}
				}
				if (outcomes.isEmpty()) {
					throw new SQLException("Terminal application is missing record outcomes");
				}
				return Optional.of(new ApplicationResult(reviewId, packageId, completedAt, outcomes, false));
			}
		}
	}

	private void insertBatch(Connection connection, RecordedReview review, Instant completedAt) throws SQLException {
		try (PreparedStatement insert = connection.prepareStatement("""
				INSERT INTO contribution_application_batches (
				    review_id, package_id, completed_at
				) VALUES (?, ?, ?)
				""")) {
			insert.setString(1, review.reviewId().toString());
			insert.setString(2, review.packageId().toString());
			insert.setString(3, completedAt.toString());
			insert.executeUpdate();
		}
	}

	private void insertCanonicalMapping(Connection connection, UUID reviewId, RecordKey key, UUID canonicalId,
			Long localId) throws SQLException {
		if (canonicalId == null || localId == null || localId < 1) {
			throw new SQLException("Accepted contribution has no canonical master mapping");
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO contribution_application_entity_mappings (
				    review_id,
				    record_type,
				    record_id,
				    canonical_record_id,
				    local_id
				)
				VALUES (?, ?, ?, ?, ?)
				""")) {
			statement.setString(1, reviewId.toString());
			statement.setString(2, key.recordType());
			statement.setString(3, key.recordId().toString());
			statement.setString(4, canonicalId.toString());
			statement.setLong(5, localId);
			if (statement.executeUpdate() != 1) {
				throw new SQLException("Canonical contribution mapping was not recorded");
			}
		}
	}

	private void insertOutcome(Connection connection, UUID reviewId, RecordOutcome outcome) throws SQLException {
		try (PreparedStatement insert = connection.prepareStatement("""
				INSERT INTO contribution_application_results (
				    review_id, record_type, record_id,
				    outcome, resulting_revision_id
				) VALUES (?, ?, ?, ?, ?)
				""")) {
			insert.setString(1, reviewId.toString());
			insert.setString(2, outcome.key().recordType());
			insert.setString(3, outcome.key().recordId().toString());
			insert.setString(4, outcome.outcome().name());
			insert.setString(5,
					outcome.resultingRevisionId() == null ? null : outcome.resultingRevisionId().toString());
			insert.executeUpdate();
		}
	}

	private void insertReconciledClosure(Connection connection, UUID assignmentId, UUID packageId, UUID reviewId,
			Instant completedAt) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO exam_work_assignment_closures (
				    assignment_id,
				    closed_state,
				    closure_kind,
				    reason,
				    closed_at,
				    return_package_id
				)
				VALUES (?, 'COMPLETE', 'RECONCILED_RETURN', ?, ?, ?)
				""")) {
			statement.setString(1, assignmentId.toString());
			statement.setString(2, "Reconciled reviewed return " + reviewId);
			statement.setString(3, completedAt.toString());
			statement.setString(4, packageId.toString());
			if (statement.executeUpdate() != 1) {
				throw new SQLException("Reconciled assignment closure was not recorded");
			}
		}
	}

	private String recordDigest(ExchangeRecord record) throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		new ExamWorkJsonlCodec().writeRecords(List.of(record), output);
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(output.toByteArray()));
		} catch (NoSuchAlgorithmException failure) {
			throw new IllegalStateException("SHA-256 is unavailable", failure);
		}
	}

	private long requireAssignedExamLocalId(Connection connection, UUID packageId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT identity.local_id
				FROM contribution_intake_packages packet
				JOIN exam_work_assignments assignment
				  ON assignment.assignment_id =
				     packet.assignment_id
				JOIN exam_work_assignment_exam_locks locked
				  ON locked.assignment_id =
				     assignment.assignment_id
				 AND locked.exam_portable_id =
				     assignment.exam_portable_id
				JOIN portable_entity_identities identity
				  ON identity.portable_id =
				     assignment.exam_portable_id
				 AND identity.entity_type = 'EXAM'
				 AND identity.retired = 0
				WHERE packet.package_id = ?
				""")) {
			statement.setString(1, packageId.toString());
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new SQLException("Received return has no locked master Exam");
				}
				long examId = result.getLong(1);
				if (examId < 1 || result.next()) {
					throw new SQLException("Invalid master Exam assignment identity");
				}
				return examId;
			}
		}
	}

	private ExchangeRecord requireCurrent(Connection connection, RecordDecision decision) throws SQLException {
		EntityType type;
		try {
			type = EntityType.valueOf(decision.key().recordType());
		} catch (IllegalArgumentException invalid) {
			throw new SQLException("Unsupported portable entity type", invalid);
		}
		long localId = requireLocalId(connection, decision.key());
		return new SqlitePortableContentRepository(database).snapshot(connection, type, localId).record();
	}

	private void requireFreshEvidence(RecordDecision decision, ExchangeRecord current)
			throws SQLException, IOException {
		if (!Objects.equals(current.revisionId(), decision.masterRevisionId())
				|| !Objects.equals(recordDigest(current), decision.masterSha256())) {
			throw new SQLException("Master record changed after contribution review: " + decision.key());
		}
		if (decision.choice() == Choice.QUEUE_APPLY
				&& !Objects.equals(decision.baselineRevisionId(), current.revisionId())) {
			throw new SQLException("Master revision no longer matches issued baseline");
		}
	}

	private long requireLocalId(Connection connection, RecordKey key) throws SQLException {
		EntityType entityType;
		try {
			entityType = EntityType.valueOf(key.recordType());
		} catch (IllegalArgumentException invalid) {
			throw new SQLException("Unsupported portable entity type", invalid);
		}
		return new SqlitePortableIdentityRepository(database).findByPortableId(connection, entityType, key.recordId())
				.orElseThrow(() -> new SQLException("Master portable entity is missing: " + key)).localId();
	}

	private UUID requireOneActiveReturn(Connection connection, UUID packageId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT packet.assignment_id
				FROM contribution_intake_packages packet
				JOIN exam_work_assignment_states states
				  ON states.assignment_id = packet.assignment_id
				JOIN exam_work_assignments assignment
				  ON assignment.assignment_id = packet.assignment_id
				JOIN exam_work_assignment_exam_locks locks
				  ON locks.assignment_id = packet.assignment_id
				 AND locks.exam_portable_id =
				     assignment.exam_portable_id
				WHERE packet.package_id = ?
				  AND packet.intake_status = 'READY_FOR_REVIEW'
				  AND packet.return_number = 1
				  AND packet.previous_package_id IS NULL
				  AND states.state = 'ACTIVE'
				  AND NOT EXISTS (
				      SELECT 1
				      FROM contribution_intake_packages competing
				      WHERE competing.assignment_id = packet.assignment_id
				        AND competing.package_id <> packet.package_id
				  )
				""")) {
			statement.setString(1, packageId.toString());
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new SQLException("Return cannot be applied: the assignment is "
							+ "closed, the return is held, or " + "another return remains unresolved");
				}
				UUID assignmentId = UUID.fromString(result.getString("assignment_id"));
				if (result.next()) {
					throw new SQLException("Duplicate active assignment reconciliation");
				}
				return assignmentId;
			}
		}
	}

	private void requireReconciledClosure(Connection connection, UUID packageId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT 1
				FROM contribution_intake_packages packet
				JOIN exam_work_assignment_closures closure
				  ON closure.assignment_id = packet.assignment_id
				 AND closure.return_package_id = packet.package_id
				JOIN exam_work_assignment_states states
				  ON states.assignment_id = closure.assignment_id
				WHERE packet.package_id = ?
				  AND closure.closure_kind = 'RECONCILED_RETURN'
				  AND closure.closed_state = 'COMPLETE'
				  AND states.state = 'COMPLETE'
				""")) {
			statement.setString(1, packageId.toString());
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new SQLException(
							"Previously applied review has no matching " + "reconciled assignment closure");
				}
			}
		}
	}

	/**
	 * Terminal outcome of a single reviewed proposal.
	 */
	public enum Outcome {
		/** Authoritative master content changed in the committed transaction. */
		APPLIED,
		/** Identical current master content was preserved without mutation. */
		SKIPPED,
		/** Explicitly rejected proposal was not applied. */
		REJECTED
	}

	/**
	 * Immutable terminal result for a single proposal.
	 *
	 * @param key                 portable record identity
	 * @param outcome             committed terminal outcome
	 * @param resultingRevisionId authoritative revision, or null for rejection
	 */
	public record RecordOutcome(RecordKey key, Outcome outcome, UUID resultingRevisionId) {

		/**
		 * Validates the relationship between the outcome and its revision.
		 *
		 * @throws NullPointerException     if key or outcome is null
		 * @throws IllegalArgumentException if the revision is inconsistent
		 */
		public RecordOutcome {
			Objects.requireNonNull(key, "key");
			Objects.requireNonNull(outcome, "outcome");
			if ((outcome == Outcome.REJECTED) != (resultingRevisionId == null)) {
				throw new IllegalArgumentException("Invalid terminal outcome revision");
			}
		}
	}

	/**
	 * Complete terminal contribution application evidence.
	 *
	 * @param reviewId    reviewed decision batch UUID
	 * @param packageId   original return package UUID
	 * @param completedAt transaction completion timestamp
	 * @param outcomes    immutable collection of per-record results
	 * @param duplicate   true for an idempotently repeated execution
	 */
	public record ApplicationResult(UUID reviewId, UUID packageId, Instant completedAt, List<RecordOutcome> outcomes,
			boolean duplicate) {

		/**
		 * Validates the stored result and defensively copies its outcomes.
		 *
		 * @throws NullPointerException     if a required component is null
		 * @throws IllegalArgumentException if the result has no outcomes
		 */
		public ApplicationResult {
			Objects.requireNonNull(reviewId, "reviewId");
			Objects.requireNonNull(packageId, "packageId");
			Objects.requireNonNull(completedAt, "completedAt");
			outcomes = List.copyOf(Objects.requireNonNull(outcomes, "outcomes"));
			if (outcomes.isEmpty()) {
				throw new IllegalArgumentException("Terminal application must have record outcomes");
			}
		}
	}
}
