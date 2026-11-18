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
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import au.edu.eq.questionbank.repository.identity.SqlitePortableContentRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.repository.sqlite.SqliteDirectChanges;
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
	 * Executes a complete recorded review or returns an idempotent replay.
	 *
	 * <p>
	 * No partial terminal application is permitted. A HOLD choice or a queued
	 * unsupported entity/change rejects the entire operation. Existing master
	 * revisions must exactly match the recorded review's revision and content
	 * digest. Every successful Question update produces a fresh master revision,
	 * which is committed with the result evidence.
	 *
	 * @param reviewId previously recorded coordinator review identity
	 * @return terminal outcomes and whether this was a repeated execution
	 * @throws SQLException         if review evidence, current master revisions or
	 *                              database constraints prevent atomic application
	 * @throws IOException          if stored portable content cannot be processed
	 * @throws NullPointerException if reviewId is null
	 */
	public ApplicationResult execute(UUID reviewId) throws SQLException, IOException {
		Objects.requireNonNull(reviewId, "reviewId");
		RecordedReview review = new MasterContributionDecisionLedger(database).find(reviewId)
				.orElseThrow(() -> new SQLException("Unknown contribution review: " + reviewId));
		List<ExchangeRecord> incoming = new MasterContributionIntake(database).proposedChanges(review.packageId());
		Map<RecordKey, ExchangeRecord> proposedByKey = new HashMap<>();
		for (ExchangeRecord record : incoming) {
			RecordKey key = new RecordKey(record.recordType(), record.recordId());
			if (proposedByKey.putIfAbsent(key, record) != null) {
				throw new SQLException("Duplicate received proposal identity");
			}
		}
		if (proposedByKey.size() != review.decisions().size()) {
			throw new SQLException("Review no longer matches its complete received proposal set");
		}

		// Reject unsupported work before taking the SQLite write reservation.
		for (RecordDecision decision : review.decisions()) {
			ExchangeRecord proposed = proposedByKey.get(decision.key());
			if (proposed == null || !proposed.revisionId().equals(decision.proposedRevisionId())
					|| !Objects.equals(proposed.baselineRevisionId(), decision.baselineRevisionId())) {
				throw new SQLException("Review and immutable proposal evidence disagree");
			}
			if (decision.choice() == Choice.HOLD) {
				throw new SQLException("Held contribution decisions cannot be applied");
			}
			if (decision.choice() == Choice.QUEUE_APPLY && (!"QUESTION".equals(decision.key().recordType())
					|| decision.preliminaryStatus() != ReviewStatus.READY_UPDATE)) {
				throw new SQLException("Only existing Question updates are supported");
			}
		}
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				Optional<ApplicationResult> existing = find(connection, reviewId);
				if (existing.isPresent()) {
					ApplicationResult previous = existing.orElseThrow();
					connection.commit();
					return new ApplicationResult(previous.reviewId(), previous.packageId(), previous.completedAt(),
							previous.outcomes(), true);
				}
				Instant completedAt = Instant.now();

				// This INSERT acquires the SQLite writer reservation.
				// No concurrent writer can alter master records between
				// the subsequent revision checks and the final COMMIT.
				insertBatch(connection, review, completedAt);
				List<RecordOutcome> outcomes = new ArrayList<>();
				for (RecordDecision decision : review.decisions()) {
					RecordOutcome outcome = applyDecision(connection, decision, proposedByKey.get(decision.key()));
					insertOutcome(connection, reviewId, outcome);
					outcomes.add(outcome);
				}
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

	private RecordOutcome applyDecision(Connection connection, RecordDecision decision, ExchangeRecord proposed)
			throws SQLException, IOException {
		if (decision.choice() == Choice.REJECT) {
			return new RecordOutcome(decision.key(), Outcome.REJECTED, null);
		}
		ExchangeRecord current = requireCurrent(connection, decision);
		requireFreshEvidence(decision, current);
		if (decision.choice() == Choice.SKIP_IDENTICAL) {
			if (!current.values().equals(proposed.values())) {
				throw new SQLException("Master is no longer identical to skipped proposal");
			}
			return new RecordOutcome(decision.key(), Outcome.SKIPPED, current.revisionId());
		}

		// Only the Question text is currently supported. Classification,
		// content parts, Question images and all other fields must match.
		requireTextOnlyChange(current, proposed);
		long localId = requireLocalId(connection, decision.key());
		try (PreparedStatement update = connection.prepareStatement("""
				UPDATE questions
				SET question_text = ?
				WHERE id = ?
				""")) {
			update.setString(1, proposed.values().path("question_text").asText());
			update.setLong(2, localId);
			if (SqliteDirectChanges.executeUpdate(update) != 1) {
				throw new SQLException("Question update did not affect exactly one row");
			}
		}
		ExchangeRecord applied = new SqlitePortableContentRepository(database)
				.snapshot(connection, EntityType.QUESTION, localId).record();
		if (!applied.values().equals(proposed.values())) {
			throw new SQLException("Applied Question content differs from approved proposal");
		}
		if (applied.revisionId().equals(current.revisionId())) {
			throw new SQLException("Question mutation did not advance master revision");
		}
		return new RecordOutcome(decision.key(), Outcome.APPLIED, applied.revisionId());
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

	private String recordDigest(ExchangeRecord record) throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		new ExamWorkJsonlCodec().writeRecords(List.of(record), output);
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(output.toByteArray()));
		} catch (NoSuchAlgorithmException failure) {
			throw new IllegalStateException("SHA-256 is unavailable", failure);
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

	private void requireTextOnlyChange(ExchangeRecord current, ExchangeRecord proposed) throws SQLException {
		if (!(current.values() instanceof ObjectNode currentValues)
				|| !(proposed.values() instanceof ObjectNode proposedValues)) {
			throw new SQLException("Question values are not portable JSON objects");
		}
		JsonNode proposedText = proposedValues.get("question_text");
		JsonNode currentText = currentValues.get("question_text");
		if (proposedText == null || !proposedText.isTextual() || currentText == null || !currentText.isTextual()
				|| proposedText.equals(currentText)) {
			throw new SQLException("Queued Question does not contain a text-only change");
		}
		ObjectNode originalOtherFields = currentValues.deepCopy();
		ObjectNode proposedOtherFields = proposedValues.deepCopy();
		originalOtherFields.remove("question_text");
		proposedOtherFields.remove("question_text");
		if (!originalOtherFields.equals(proposedOtherFields)) {
			throw new SQLException("Queued Question includes unsupported content changes");
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
