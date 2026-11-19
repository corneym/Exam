package au.edu.eq.questionbank.service.workpackage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
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
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.node.ObjectNode;

import au.edu.eq.questionbank.ManagedAssetPublicationLock;
import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.repository.identity.SqlitePortableContentRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.workpackage.MasterContributionApprovedImagePreparer.PreparedImage;
import au.edu.eq.questionbank.service.workpackage.MasterContributionDecisionLedger.Choice;
import au.edu.eq.questionbank.service.workpackage.MasterContributionDecisionLedger.RecordDecision;
import au.edu.eq.questionbank.service.workpackage.MasterContributionDecisionLedger.RecordKey;
import au.edu.eq.questionbank.service.workpackage.MasterContributionDecisionLedger.RecordedReview;
import au.edu.eq.questionbank.service.workpackage.MasterContributionReviewPlanner.ReviewStatus;

/**
 * Applies coordinator-reviewed contributions to a locked master Exam.
 *
 * <p>
 * The application follows the single-active-assignment workflow. Current master
 * identities, revisions and content are checked against immutable review
 * evidence inside the authoritative transaction. Unsupported or divergent
 * changes fail closed; no three-way merge is attempted.
 *
 * <p>
 * The archive-aware path supports reviewed Question/Answer content, Source
 * Questions, Shared Context graphs, ordered regions and embedded PNGs. Reviewed
 * changes, canonical UUID mappings, terminal outcomes and assignment closure
 * commit atomically.
 *
 * <p>
 * External managed-asset publication is not performed here. The caller holds
 * the shared managed-data publication lock for binary dependencies.
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
	 * Applies a reviewed contribution that needs no external binary content, or
	 * replays an already committed review.
	 *
	 * @param reviewId recorded coordinator review UUID
	 * @return committed application result
	 * @throws SQLException         if review or master evidence is invalid
	 * @throws IOException          if portable content processing fails
	 * @throws NullPointerException if reviewId is null
	 */
	public ApplicationResult execute(UUID reviewId) throws SQLException, IOException {
		return executeReviewed(reviewId, false, List.of(), List.of());
	}

	/**
	 * Applies a reviewed contribution with its original verified binary archive.
	 *
	 * <p>
	 * The managed-asset publication lock covers archive verification, the
	 * preparation of approved Question and Shared Context PNGs, and the complete
	 * authoritative application transaction.
	 *
	 * <p>
	 * New Source Questions, Shared Contexts, Question and Answer content, approved
	 * Question PNG creation/replacement and supported PDF regions commit together
	 * with terminal outcomes and assignment closure.
	 *
	 * @param reviewId immutable coordinator review UUID
	 * @param archive  original received contribution archive
	 * @param layout   authoritative master managed-data layout
	 * @return committed terminal result, including replay status
	 * @throws SQLException         if review, dependency or master state is invalid
	 * @throws IOException          if archive or binary evidence is invalid
	 * @throws NullPointerException if any argument is null
	 */
	public ApplicationResult execute(UUID reviewId, Path archive, ManagedDataLayout layout)
			throws SQLException, IOException {
		Objects.requireNonNull(reviewId, "reviewId");
		Objects.requireNonNull(archive, "archive");
		Objects.requireNonNull(layout, "layout");
		RecordedReview review = new MasterContributionDecisionLedger(database).find(reviewId)
				.orElseThrow(() -> new SQLException("Unknown contribution review: " + reviewId));
		if (find(reviewId).isPresent()) {
			return execute(reviewId);
		}
		try (var publicationLock = ManagedAssetPublicationLock.acquire(layout.dataRoot())) {
			publicationLock.requireNoPendingPublications();
			new MasterContributionAssetPreflight(database, layout).verify(review.packageId(), archive);
			List<PreparedImage> images = new MasterContributionApprovedImagePreparer(database).prepare(review, archive);
			List<MasterContributionApprovedContextPreparer.PreparedContext> contexts = new MasterContributionApprovedContextPreparer(
					database).prepare(review, archive);
			return executeReviewed(reviewId, true, images, contexts);
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
			ExchangeRecord proposed, Map<RecordKey, Long> localIds, Map<RecordKey, UUID> canonicalIds,
			boolean allowPdfRegions, Map<RecordKey, RecordDecision> decisions, Map<RecordKey, ExchangeRecord> proposals,
			Map<RecordKey, PreparedImage> images,
			Map<RecordKey, MasterContributionApprovedContextPreparer.PreparedContext> contexts)
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
		String type = key.recordType();
		if ("QUESTION_IMAGE".equals(type)) {

			// The parent Question already performed the approved image
			// insertion or replacement under its scoped region permit.
			Long localId = localIds.get(key);
			if (localId == null || (decision.preliminaryStatus() != ReviewStatus.READY_NEW
					&& decision.preliminaryStatus() != ReviewStatus.READY_UPDATE)) {
				throw new SQLException("Question image was not applied by its approved parent");
			}
			ExchangeRecord actual = new SqlitePortableContentRepository(database)
					.snapshot(connection, EntityType.QUESTION_IMAGE, localId).record();
			ObjectNode expected = ((ObjectNode) proposed.values()).deepCopy();
			UUID originalParent = UUID.fromString(expected.path("question_id").asText());
			UUID canonicalParent = canonicalIds.get(new RecordKey("QUESTION", originalParent));
			if (canonicalParent != null) {
				expected.put("question_id", canonicalParent.toString());
			}
			if (!actual.values().equals(expected)) {
				throw new SQLException("Applied Question image differs from approved content");
			}
			canonicalIds.put(key, actual.recordId());
			return new RecordOutcome(key, Outcome.APPLIED, actual.revisionId());
		}
		if (decision.preliminaryStatus() == ReviewStatus.READY_UPDATE) {
			ExchangeRecord current = requireCurrent(connection, decision);
			requireFreshEvidence(decision, current);
		}
		MasterContributionScalarWriter.Applied applied;
		if ("SOURCE_QUESTION".equals(type) || "SHARED_QUESTION_CONTEXT".equals(type)) {
			applied = new MasterContributionGraphWriter(database).apply(connection, reviewId, examId, decision,
					proposed, contexts);
		} else {
			applied = new MasterContributionScalarWriter(database).apply(connection, reviewId, examId, decision,
					proposed, localIds, canonicalIds, allowPdfRegions, decisions, proposals, images);
		}
		localIds.put(key, applied.localId());
		canonicalIds.put(key, applied.canonicalId());
		return new RecordOutcome(key, Outcome.APPLIED, applied.revisionId());
	}

	private ApplicationResult executeReviewed(UUID reviewId, boolean allowPdfRegions,
			List<PreparedImage> preparedImages,
			List<MasterContributionApprovedContextPreparer.PreparedContext> preparedContexts)
			throws SQLException, IOException {
		Objects.requireNonNull(reviewId, "reviewId");
		Objects.requireNonNull(preparedImages, "preparedImages");
		Objects.requireNonNull(preparedContexts, "preparedContexts");
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
			throw new SQLException("Review does not match complete proposal set");
		}
		Map<RecordKey, RecordDecision> decisionsByKey = new HashMap<>();
		for (RecordDecision decision : review.decisions()) {
			if (decisionsByKey.putIfAbsent(decision.key(), decision) != null) {
				throw new SQLException("Duplicate coordinator decision");
			}
			ExchangeRecord proposed = proposedByKey.get(decision.key());
			if (proposed == null || !proposed.revisionId().equals(decision.proposedRevisionId())
					|| !Objects.equals(proposed.baselineRevisionId(), decision.baselineRevisionId())) {
				throw new SQLException("Review and immutable proposal evidence disagree");
			}
			if (decision.choice() == Choice.HOLD) {
				throw new SQLException("Held decisions cannot be applied");
			}
			if (decision.choice() == Choice.QUEUE_APPLY) {
				String type = decision.key().recordType();
				boolean supported = switch (type) {
				case "SOURCE_QUESTION", "SHARED_QUESTION_CONTEXT", "QUESTION", "ANSWER", "QUESTION_IMAGE" -> true;
				default -> false;
				};
				boolean supportedStatus = decision.preliminaryStatus() == ReviewStatus.READY_NEW
						|| decision.preliminaryStatus() == ReviewStatus.READY_UPDATE;
				if (!supported || !supportedStatus) {
					throw new SQLException("Unsupported queued contribution record: " + type);
				}
			}
		}
		Map<RecordKey, PreparedImage> imagesByKey = new HashMap<>();
		for (PreparedImage image : preparedImages) {
			RecordKey key = new RecordKey("QUESTION_IMAGE", image.imageId());
			if (imagesByKey.putIfAbsent(key, image) != null) {
				throw new SQLException("Duplicate prepared Question image");
			}
		}
		Map<RecordKey, MasterContributionApprovedContextPreparer.PreparedContext> contextsByKey = new HashMap<>();
		for (var context : preparedContexts) {
			RecordKey key = new RecordKey("SHARED_QUESTION_CONTEXT", context.proposal().recordId());
			if (contextsByKey.putIfAbsent(key, context) != null) {
				throw new SQLException("Duplicate prepared Shared Context");
			}
		}
		List<RecordDecision> ordered = new ArrayList<>(review.decisions());

		// Apply dependencies before Questions, and Answers after Questions.
		ordered.sort(Comparator.comparingInt(decision -> switch (decision.key().recordType()) {
		case "SOURCE_QUESTION" -> 0;
		case "SHARED_QUESTION_CONTEXT" -> 1;
		case "QUESTION" -> 2;
		case "QUESTION_IMAGE" -> 3;
		case "ANSWER" -> 4;
		default -> 5;
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
				Set<RecordKey> expectedImages = new HashSet<>();
				Set<RecordKey> expectedContexts = new HashSet<>();
				for (RecordDecision decision : review.decisions()) {
					if (decision.choice() != Choice.QUEUE_APPLY) {
						continue;
					}
					if ("QUESTION_IMAGE".equals(decision.key().recordType())) {
						expectedImages.add(decision.key());
					}
					if ("SHARED_QUESTION_CONTEXT".equals(decision.key().recordType())) {
						expectedContexts.add(decision.key());
					}
				}
				if (!expectedImages.equals(imagesByKey.keySet())) {
					throw new SQLException("Prepared Question image set differs from review");
				}
				if (!expectedContexts.equals(contextsByKey.keySet())) {
					throw new SQLException("Prepared Shared Context set differs from review");
				}
				if (!allowPdfRegions && (!expectedImages.isEmpty() || !expectedContexts.isEmpty())) {
					throw new SQLException("Reviewed binary graph requires verified archive");
				}
				Instant completedAt = Instant.now();

				// Begin the authoritative application transaction. All
				// outcomes, mappings and closure must commit together.
				insertBatch(connection, review, completedAt);
				UUID assignmentId = requireOneActiveReturn(connection, review.packageId());
				long examId = requireAssignedExamLocalId(connection, review.packageId());
				Map<RecordKey, Long> localIds = new HashMap<>();
				Map<RecordKey, UUID> canonicalIds = new HashMap<>();
				List<RecordOutcome> outcomes = new ArrayList<>();
				for (RecordDecision decision : ordered) {
					RecordOutcome outcome = applyDecision(connection, reviewId, examId, decision,
							proposedByKey.get(decision.key()), localIds, canonicalIds, allowPdfRegions, decisionsByKey,
							proposedByKey, imagesByKey, contextsByKey);
					insertOutcome(connection, reviewId, outcome);
					if (outcome.outcome() != Outcome.REJECTED) {
						insertCanonicalMapping(connection, reviewId, decision.key(), canonicalIds.get(decision.key()),
								localIds.get(decision.key()));
					}
					outcomes.add(outcome);
				}
				outcomes.sort(Comparator.comparing((RecordOutcome outcome) -> outcome.key().recordType())
						.thenComparing(outcome -> outcome.key().recordId().toString()));

				// No temporary write authorisation may survive closure.
				try (PreparedStatement statement = connection.prepareStatement("""
						SELECT
						    (SELECT COUNT(*)
						     FROM master_contribution_write_permits)
						  + (SELECT COUNT(*)
						     FROM master_contribution_region_permits)
						  + (SELECT COUNT(*)
						     FROM master_contribution_graph_permits)
						"""); ResultSet result = statement.executeQuery()) {
					if (!result.next() || result.getLong(1) != 0) {
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
