package au.edu.eq.questionbank.service.workpackage;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.workpackage.MasterContributionReviewPlanner.RecordAssessment;
import au.edu.eq.questionbank.service.workpackage.MasterContributionReviewPlanner.ReviewPlan;
import au.edu.eq.questionbank.service.workpackage.MasterContributionReviewPlanner.ReviewStatus;

/**
 * Records immutable coordinator choices for previously received contributions.
 *
 * <p>
 * Each review stores the coordinator's choice and the exact master revision
 * evidence observed by a fresh preflight assessment. QUEUE_APPLY records an
 * intention to apply, not a completed application. No authoritative Question,
 * Answer, asset or other domain data is changed here.
 */
public final class MasterContributionDecisionLedger {

	private final SqliteDatabase database;

	/**
	 * Creates a coordinator review-decision ledger.
	 *
	 * @param database initialised master database
	 * @throws NullPointerException if database is null
	 */
	public MasterContributionDecisionLedger(SqliteDatabase database) {
		this.database = Objects.requireNonNull(database, "database");
	}

	/**
	 * Determines the complete set of internal application decisions for a
	 * contribution containing only valid new records.
	 *
	 * <p>
	 * No individual coordinator decisions are required. Unsupported, conflicting,
	 * existing or modified baseline records prevent the entire contribution from
	 * being merged.
	 *
	 * @param plan preliminary contribution assessment
	 * @return immutable application decisions covering every proposal
	 * @throws IllegalArgumentException if any proposal is ineligible
	 * @throws NullPointerException     if the plan is null
	 */
	public static Map<RecordKey, Choice> mergeSelections(ReviewPlan plan) {
		Objects.requireNonNull(plan, "plan");
		if (plan.intakeStatus() != MasterContributionIntake.IntakeStatus.READY_FOR_REVIEW) {
			throw new IllegalArgumentException("This contribution is not eligible for merging.");
		}
		if (plan.assessments().isEmpty()) {
			throw new IllegalArgumentException("The contribution contains no new work.");
		}
		Map<RecordKey, Choice> selections = new HashMap<>();
		for (RecordAssessment assessment : plan.assessments()) {
			boolean supported = switch (assessment.recordType()) {
			case "SOURCE_QUESTION", "SHARED_QUESTION_CONTEXT", "QUESTION", "ANSWER", "QUESTION_IMAGE" -> true;
			default -> false;
			};
			if (!supported) {
				throw new IllegalArgumentException("The contribution contains an unsupported " + "type of work.");
			}
			if (assessment.baselineRevisionId() != null) {
				throw new IllegalArgumentException(
						"The contribution attempts to modify " + "existing coordinator-owned content.");
			}
			if (assessment.status() != ReviewStatus.READY_NEW) {
				String explanation = switch (assessment.status()) {
				case SKIP_IDENTICAL -> "Some returned work already exists in the Exam.";
				case READY_UPDATE -> "The contribution attempts to update existing work.";
				case HELD_STALE_MASTER -> "The coordinator's content has changed.";
				case HELD_MISSING_MASTER -> "Required coordinator content is missing.";
				case HELD_ID_COLLISION -> "Some returned work conflicts with existing records.";
				case HELD_OUT_OF_SCOPE -> "Some returned work belongs outside the assigned Exam.";
				case HELD_LINEAGE -> "The contribution does not match an eligible assignment.";
				default -> "Some returned work cannot be safely merged.";
				};
				throw new IllegalArgumentException(explanation);
			}
			RecordKey key = new RecordKey(assessment.recordType(), assessment.recordId());
			if (selections.putIfAbsent(key, Choice.QUEUE_APPLY) != null) {
				throw new IllegalArgumentException("The contribution contains duplicate records.");
			}
		}
		return Map.copyOf(selections);
	}

	/**
	 * Retrieves a previously recorded review and its complete decisions.
	 *
	 * @param reviewId durable review UUID
	 * @return review evidence, or empty when not recorded
	 * @throws SQLException         if database retrieval fails
	 * @throws NullPointerException if reviewId is null
	 */
	public Optional<RecordedReview> find(UUID reviewId) throws SQLException {
		Objects.requireNonNull(reviewId, "reviewId");
		try (Connection connection = database.openConnection();
				PreparedStatement batch = connection.prepareStatement("""
						SELECT package_id, observed_at, recorded_at, review_note
						FROM contribution_review_batches
						WHERE review_id = ?
						""");
				PreparedStatement details = connection.prepareStatement("""
						SELECT record_type, record_id,
						       baseline_revision_id, proposed_revision_id,
						       master_revision_id, master_sha256,
						       preliminary_status, choice, result_revision_id
						FROM contribution_review_decisions
						WHERE review_id = ?
						ORDER BY record_type, record_id
						""")) {
			batch.setString(1, reviewId.toString());
			try (ResultSet result = batch.executeQuery()) {
				if (!result.next()) {
					return Optional.empty();
				}
				UUID packageId = UUID.fromString(result.getString("package_id"));
				Instant observedAt = Instant.parse(result.getString("observed_at"));
				Instant recordedAt = Instant.parse(result.getString("recorded_at"));
				String note = result.getString("review_note");
				if (result.next()) {
					throw new SQLException("Duplicate review batch");
				}
				List<RecordDecision> decisions = new ArrayList<>();
				details.setString(1, reviewId.toString());
				try (ResultSet rows = details.executeQuery()) {
					while (rows.next()) {
						decisions.add(new RecordDecision(
								new RecordKey(rows.getString("record_type"),
										UUID.fromString(rows.getString("record_id"))),
								optionalUuid(rows.getString("baseline_revision_id")),
								UUID.fromString(rows.getString("proposed_revision_id")),
								optionalUuid(rows.getString("master_revision_id")), rows.getString("master_sha256"),
								ReviewStatus.valueOf(rows.getString("preliminary_status")),
								Choice.valueOf(rows.getString("choice")),
								optionalUuid(rows.getString("result_revision_id"))));
					}
				}
				if (decisions.isEmpty()) {
					throw new SQLException("Recorded review has no decisions");
				}
				return Optional.of(new RecordedReview(reviewId, packageId, observedAt, recordedAt, note, decisions));
			}
		}
	}

	/**
	 * Records one complete, explicit set of coordinator review choices.
	 *
	 * <p>
	 * A new consistent master snapshot is assessed before recording. Exactly one
	 * choice must be supplied per proposed record. READY_NEW and READY_UPDATE may
	 * be queued; an identical record may be skipped. Held records cannot be queued
	 * for application. REJECT and HOLD do not change domain data.
	 *
	 * <p>
	 * These decisions are historical observations, not authorisation for later
	 * unvalidated writes. Application must repeat revision checks.
	 *
	 * @param packageId  received contribution package UUID
	 * @param selections one explicit choice per proposed portable record
	 * @param note       non-blank coordinator review rationale, maximum 4000
	 *                   characters
	 * @return newly persisted immutable review evidence
	 * @throws SQLException             if the atomic database write fails
	 * @throws IOException              if the master review cannot be prepared
	 * @throws IllegalArgumentException if the choices or note are invalid
	 * @throws NullPointerException     if required arguments are null
	 */
	public RecordedReview record(UUID packageId, Map<RecordKey, Choice> selections, String note)
			throws SQLException, IOException {
		Objects.requireNonNull(packageId, "packageId");
		Objects.requireNonNull(selections, "selections");
		Objects.requireNonNull(note, "note");
		String rationale = note.strip();
		if (rationale.isEmpty() || rationale.length() > 4000) {
			throw new IllegalArgumentException("Review rationale must contain 1–4000 characters");
		}
		ReviewPlan plan = new MasterContributionReviewPlanner(database).plan(packageId);
		return persistReview(plan, selections, rationale);
	}

	/**
	 * Records the coordinator's approval to merge an entire contribution.
	 *
	 * <p>
	 * Eligibility and all proposed records are reassessed against a fresh
	 * coordinator snapshot. Every proposal must satisfy the whole-return merge
	 * policy before any review decisions are recorded.
	 *
	 * <p>
	 * Recording approval does not itself modify authoritative corpus content.
	 * Application must still validate and commit the contribution.
	 *
	 * @param packageId received contribution package identity
	 * @return immutable recorded merge approval
	 * @throws SQLException             if database persistence fails
	 * @throws IOException              if the contribution cannot be reviewed
	 * @throws IllegalArgumentException if any proposal is ineligible
	 * @throws NullPointerException     if the package identity is null
	 */
	public RecordedReview recordMerge(UUID packageId) throws SQLException, IOException {
		Objects.requireNonNull(packageId, "packageId");
		ReviewPlan plan = new MasterContributionReviewPlanner(database).planPending(packageId).orElseThrow(
				() -> new PackageFormatException("This contribution is no longer eligible " + "for merging."));
		Map<RecordKey, Choice> selections = mergeSelections(plan);

		// Preserve durable approval evidence without requiring
		// the coordinator to enter an artificial review rationale.
		return persistReview(plan, selections, "Coordinator approved the complete contribution merge.");
	}

	private void insertBatch(Connection connection, RecordedReview review) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO contribution_review_batches (
				    review_id, package_id, observed_at,
				    recorded_at, review_note
				) VALUES (?, ?, ?, ?, ?)
				""")) {
			statement.setString(1, review.reviewId().toString());
			statement.setString(2, review.packageId().toString());
			statement.setString(3, review.observedAt().toString());
			statement.setString(4, review.recordedAt().toString());
			statement.setString(5, review.note());
			statement.executeUpdate();
		}
	}

	private void insertDecisions(Connection connection, RecordedReview review) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO contribution_review_decisions (
				    review_id, package_id,
				    record_type, record_id,
				    baseline_revision_id, proposed_revision_id,
				    master_revision_id, master_sha256,
				    preliminary_status, choice, result_revision_id
				) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""")) {
			for (RecordDecision decision : review.decisions()) {
				statement.setString(1, review.reviewId().toString());
				statement.setString(2, review.packageId().toString());
				statement.setString(3, decision.key().recordType());
				statement.setString(4, decision.key().recordId().toString());
				statement.setString(5, uuidText(decision.baselineRevisionId()));
				statement.setString(6, decision.proposedRevisionId().toString());
				statement.setString(7, uuidText(decision.masterRevisionId()));
				statement.setString(8, decision.masterSha256());
				statement.setString(9, decision.preliminaryStatus().name());
				statement.setString(10, decision.choice().name());
				statement.setString(11, uuidText(decision.resultRevisionId()));
				statement.executeUpdate();
			}
		}
	}

	private UUID optionalUuid(String value) {
		return value == null ? null : UUID.fromString(value);
	}

	private RecordedReview persistReview(ReviewPlan plan, Map<RecordKey, Choice> selections, String rationale)
			throws SQLException {
		UUID packageId = plan.packageId();
		Map<RecordKey, RecordAssessment> assessed = new HashMap<>();
		for (RecordAssessment assessment : plan.assessments()) {
			RecordKey key = new RecordKey(assessment.recordType(), assessment.recordId());
			if (assessed.putIfAbsent(key, assessment) != null) {
				throw new IllegalArgumentException("Duplicate proposed record identity");
			}
		}
		if (!assessed.keySet().equals(selections.keySet())) {
			throw new IllegalArgumentException("One coordinator choice is required for every proposal");
		}
		List<RecordDecision> decisions = new ArrayList<>();
		for (RecordAssessment assessment : plan.assessments()) {
			RecordKey key = new RecordKey(assessment.recordType(), assessment.recordId());
			Choice choice = Objects.requireNonNull(selections.get(key), "choice for " + key);
			requirePermitted(assessment.status(), choice);
			UUID resultRevision = choice == Choice.SKIP_IDENTICAL ? assessment.masterRevisionId() : null;
			decisions.add(new RecordDecision(key, assessment.baselineRevisionId(), assessment.proposedRevisionId(),
					assessment.masterRevisionId(), assessment.masterRecordSha256(), assessment.status(), choice,
					resultRevision));
		}
		UUID reviewId = UUID.randomUUID();
		Instant recordedAt = Instant.now();
		RecordedReview review = new RecordedReview(reviewId, packageId, plan.observedAt(), recordedAt, rationale,
				decisions);
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				insertBatch(connection, review);
				insertDecisions(connection, review);
				connection.commit();
				return review;
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

	private void requirePermitted(ReviewStatus status, Choice choice) {
		boolean permitted = switch (choice) {
		case QUEUE_APPLY -> status == ReviewStatus.READY_NEW || status == ReviewStatus.READY_UPDATE;
		case SKIP_IDENTICAL -> status == ReviewStatus.SKIP_IDENTICAL;
		case REJECT, HOLD -> true;
		};
		if (!permitted) {
			throw new IllegalArgumentException("Decision " + choice + " is not permitted for " + status);
		}
	}

	private String uuidText(UUID value) {
		return value == null ? null : value.toString();
	}

	/**
	 * Explicit coordinator choice for one received proposal.
	 */
	public enum Choice {
		/** Queue a safe candidate for a later validated write transaction. */
		QUEUE_APPLY,
		/** Preserve matching master content and record its observed revision. */
		SKIP_IDENTICAL,
		/** Reject the proposal without changing master content. */
		REJECT,
		/** Leave the proposal unresolved without changing master content. */
		HOLD
	}

	/**
	 * Portable identity of one proposed record.
	 *
	 * @param recordType portable entity type
	 * @param recordId   portable UUID
	 */
	public record RecordKey(String recordType, UUID recordId) {

		/**
		 * Requires a non-blank record type and portable identity.
		 *
		 * @throws NullPointerException     if an argument is null
		 * @throws IllegalArgumentException if recordType is blank
		 */
		public RecordKey {
			Objects.requireNonNull(recordType, "recordType");
			Objects.requireNonNull(recordId, "recordId");
			if (recordType.isBlank()) {
				throw new IllegalArgumentException("Record type is blank");
			}
		}
	}

	/**
	 * Immutable coordinator decision and observed revision evidence.
	 *
	 * @param key                proposed record's portable identity
	 * @param baselineRevisionId original issued revision, or null for new
	 * @param proposedRevisionId contributor's proposed revision
	 * @param masterRevisionId   observed master revision, or null when absent
	 * @param masterSha256       observed canonical master record digest, or null
	 * @param preliminaryStatus  preflight assessment
	 * @param choice             explicit coordinator decision
	 * @param resultRevisionId   observed retained revision for an identical skip,
	 *                           otherwise null; never signifies application
	 */
	public record RecordDecision(RecordKey key, UUID baselineRevisionId, UUID proposedRevisionId, UUID masterRevisionId,
			String masterSha256, ReviewStatus preliminaryStatus, Choice choice, UUID resultRevisionId) {

		/**
		 * Validates decision evidence.
		 *
		 * @throws NullPointerException     if required fields are null
		 * @throws IllegalArgumentException if revision evidence is inconsistent
		 */
		public RecordDecision {
			Objects.requireNonNull(key, "key");
			Objects.requireNonNull(proposedRevisionId, "proposedRevisionId");
			Objects.requireNonNull(preliminaryStatus, "preliminaryStatus");
			Objects.requireNonNull(choice, "choice");
			if ((masterRevisionId == null) != (masterSha256 == null)) {
				throw new IllegalArgumentException("Master revision and digest must occur together");
			}
			if (masterSha256 != null && !masterSha256.matches("[0-9a-f]{64}")) {
				throw new IllegalArgumentException("Invalid master digest");
			}
			if (choice == Choice.SKIP_IDENTICAL) {
				if (preliminaryStatus != ReviewStatus.SKIP_IDENTICAL || masterRevisionId == null
						|| !masterRevisionId.equals(resultRevisionId)) {
					throw new IllegalArgumentException("Identical skip requires observed master revision");
				}
			} else if (resultRevisionId != null) {
				throw new IllegalArgumentException("Unapplied decision cannot have a resulting revision");
			}
			if (choice == Choice.QUEUE_APPLY && preliminaryStatus != ReviewStatus.READY_NEW
					&& preliminaryStatus != ReviewStatus.READY_UPDATE) {
				throw new IllegalArgumentException("Held proposal cannot be queued for application");
			}
		}
	}

	/**
	 * Complete, immutable review evidence for a received return.
	 *
	 * @param reviewId   unique review event UUID
	 * @param packageId  received return package UUID
	 * @param observedAt time of the reviewed master snapshot
	 * @param recordedAt time of durable decision recording
	 * @param note       coordinator's rationale
	 * @param decisions  defensive copy of all per-record decisions
	 */
	public record RecordedReview(UUID reviewId, UUID packageId, Instant observedAt, Instant recordedAt, String note,
			List<RecordDecision> decisions) {

		/**
		 * Validates and defensively copies the review evidence.
		 *
		 * @throws NullPointerException     if required values are null
		 * @throws IllegalArgumentException if note or decisions are empty
		 */
		public RecordedReview {
			Objects.requireNonNull(reviewId, "reviewId");
			Objects.requireNonNull(packageId, "packageId");
			Objects.requireNonNull(observedAt, "observedAt");
			Objects.requireNonNull(recordedAt, "recordedAt");
			Objects.requireNonNull(note, "note");
			decisions = List.copyOf(Objects.requireNonNull(decisions, "decisions"));
			if (note.isBlank() || note.length() > 4000 || decisions.isEmpty()) {
				throw new IllegalArgumentException("Review requires a rationale and decisions");
			}
		}
	}
}
