package au.edu.eq.questionbank.service.workpackage;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.workpackage.ContributorReceiptAcknowledgementLedger.Acknowledgement;
import au.edu.eq.questionbank.service.workpackage.MasterContributionApplicationCoordinator.Outcome;

/**
 * Presents durable, authenticated known-master state beside current contributor
 * working revisions.
 *
 * <p>
 * The service never changes contributor Questions, Answers, portable
 * identities, issued baselines or managed assets.
 *
 * <p>
 * Accepted records remain authoritative historical observations of their
 * particular reconciled returns. A later installed assignment is reported
 * explicitly so an older receipt cannot be mistaken for the latest assignment
 * baseline.
 *
 * <p>
 * Portable UUIDs are resolved locally. SQLite integer primary keys are never
 * interpreted as cross-installation identities.
 */
public final class ContributorKnownMasterRebaseService {

	private final SqliteDatabase database;

	/**
	 * Creates the known-master rebase service.
	 *
	 * @param database initialised contributor database
	 * @throws NullPointerException if database is null
	 */
	public ContributorKnownMasterRebaseService(SqliteDatabase database) {
		this.database = Objects.requireNonNull(database, "database");
	}

	/**
	 * Authenticates and acknowledges a receipt, then reports its durable
	 * known-master state against current contributor work.
	 *
	 * <p>
	 * This method records acknowledgement evidence but does not modify working
	 * domain records or the immutable assignment baseline.
	 *
	 * @param archive        original IMPORT_RECEIPT archive
	 * @param expectedSha256 independently confirmed receipt digest
	 * @return acknowledgement and non-destructive local rebase view
	 * @throws IOException          if receipt authentication or decoding fails
	 * @throws SQLException         if acknowledgement or local reading fails
	 * @throws NullPointerException if an argument is null
	 */
	public View acknowledgeAndView(Path archive, String expectedSha256) throws IOException, SQLException {
		Acknowledgement acknowledged = new ContributorReceiptAcknowledgementLedger(database).acknowledge(archive,
				expectedSha256);
		return view(acknowledged.receiptPackageId());
	}

	/**
	 * Reassesses an already acknowledged receipt against current local revisions
	 * without requiring the original archive.
	 *
	 * <p>
	 * The accepted master record is retained separately even when the contributor
	 * has changed the corresponding local record. Rejected proposals never acquire
	 * a canonical master record.
	 *
	 * @param receiptPackageId previously acknowledged receipt UUID
	 * @return historical known-master records and current local dispositions
	 * @throws IOException          if retained canonical records cannot be decoded
	 * @throws SQLException         if acknowledgement, lineage or identity data
	 *                              fails
	 * @throws NullPointerException if receiptPackageId is null
	 */
	public View view(UUID receiptPackageId) throws IOException, SQLException {
		Objects.requireNonNull(receiptPackageId, "receiptPackageId");
		Acknowledgement acknowledgement = new ContributorReceiptAcknowledgementLedger(database).find(receiptPackageId)
				.orElseThrow(() -> new SQLException("Receipt has not been acknowledged"));
		var session = new SqliteContributorAssignmentSessionRepository(database).find(acknowledgement.assignmentId())
				.orElseThrow(() -> new SQLException("Acknowledged assignment is missing"));
		if (!session.masterInstallationId().equals(acknowledgement.masterInstallationId())) {
			throw new SQLException("Acknowledged master does not match assignment");
		}
		Map<String, ExchangeRecord> remainingMaster = new HashMap<>();
		for (ExchangeRecord record : acknowledgement.acceptedMasterRecords()) {
			String key = key(record.recordType(), record.recordId());
			if (remainingMaster.putIfAbsent(key, record) != null) {
				throw new SQLException("Duplicate acknowledged canonical identity");
			}
		}
		List<Entry> entries = new ArrayList<>();
		Set<String> contributorKeys = new HashSet<>();
		SqlitePortableIdentityRepository identities = new SqlitePortableIdentityRepository(database);
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				boolean superseded = newerAssignmentInstalled(connection, session.examPortableId(),
						session.assignmentId(), session.issuedAt());
				for (ExchangeRecord outcome : acknowledgement.outcomes()) {
					String typeName = outcome.values().path("record_type").asText();
					String contributorKey = key(typeName, outcome.recordId());
					if (!contributorKeys.add(contributorKey)) {
						throw new SQLException("Duplicate contributor receipt outcome");
					}
					if (!outcome.recordType().equals("IMPORT_RECEIPT_" + typeName)) {
						throw new SQLException("Acknowledged outcome type mismatch");
					}
					entries.add(assess(connection, identities, outcome, remainingMaster));
				}
				if (!remainingMaster.isEmpty()) {
					throw new SQLException("Unmatched accepted master state " + "in acknowledgement");
				}
				View result = new View(acknowledgement, superseded, entries);
				connection.commit();
				return result;
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

	private Entry assess(Connection connection, SqlitePortableIdentityRepository identities, ExchangeRecord outcome,
			Map<String, ExchangeRecord> remainingMaster) throws SQLException {
		JsonNode values = outcome.values();
		String typeName = values.path("record_type").asText();
		Outcome terminal;
		try {
			terminal = Outcome.valueOf(values.path("outcome").asText());
		} catch (IllegalArgumentException failure) {
			throw new SQLException("Invalid acknowledged terminal outcome", failure);
		}
		if (terminal == Outcome.REJECTED) {
			return new Entry(outcome, null, null, Status.PRESERVE_REJECTED);
		}
		UUID canonicalId = requiredUuid(values, "canonical_record_id");
		UUID acceptedRevision = requiredUuid(values, "resulting_revision_id");
		UUID proposedRevision = requiredUuid(values, "proposed_revision_id");
		ExchangeRecord accepted = remainingMaster.remove(key(typeName, canonicalId));
		if (accepted == null || !accepted.recordId().equals(canonicalId)
				|| !accepted.revisionId().equals(acceptedRevision)) {
			throw new SQLException("Acknowledged master state does not match " + "terminal outcome");
		}
		if (!"QUESTION".equals(typeName) && !"ANSWER".equals(typeName)) {
			return new Entry(outcome, accepted, null, Status.HOLD_UNSUPPORTED_TYPE);
		}
		EntityType type = EntityType.valueOf(typeName);

		// The contributor UUID identifies the local working record.
		// The accepted canonical master UUID is retained separately.
		var local = identities.findByPortableId(connection, type, outcome.recordId());
		if (local.isEmpty()) {
			return new Entry(outcome, accepted, null, Status.HOLD_MISSING_LOCAL_RECORD);
		}
		UUID localRevision = local.orElseThrow().revisionId();
		Status status = localRevision.equals(proposedRevision) ? Status.KNOWN_MASTER_RECORDED
				: Status.PRESERVE_LATER_LOCAL_REVISION;
		return new Entry(outcome, accepted, localRevision, status);
	}

	private String key(String type, UUID portableId) {
		return type + "\0" + portableId;
	}

	private boolean newerAssignmentInstalled(Connection connection, UUID examPortableId, UUID assignmentId,
			Instant originalIssueTime) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT issued_at
				FROM contributor_assignment_sessions
				WHERE exam_portable_id = ?
				  AND assignment_id <> ?
				""")) {
			statement.setString(1, examPortableId.toString());
			statement.setString(2, assignmentId.toString());
			try (ResultSet result = statement.executeQuery()) {
				while (result.next()) {
					Instant otherIssueTime;
					try {
						otherIssueTime = Instant.parse(result.getString("issued_at"));
					} catch (DateTimeParseException failure) {
						throw new SQLException("Invalid installed assignment issue time", failure);
					}

					// Equal issuance timestamps are ambiguous:
					// fail closed instead of declaring older state current.
					if (!otherIssueTime.isBefore(originalIssueTime)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private UUID requiredUuid(JsonNode values, String field) throws SQLException {
		JsonNode node = values.get(field);
		if (node == null || !node.isTextual()) {
			throw new SQLException("Missing acknowledged UUID: " + field);
		}
		try {
			UUID parsed = UUID.fromString(node.asText());
			if (!parsed.toString().equals(node.asText())) {
				throw new IllegalArgumentException("Noncanonical UUID");
			}
			return parsed;
		} catch (IllegalArgumentException failure) {
			throw new SQLException("Invalid acknowledged UUID: " + field, failure);
		}
	}

	/**
	 * Interpretation of a retained master outcome against current contributor
	 * working state.
	 */
	public enum Status {
		/** The master rejected the proposal; local work remains intact. */
		PRESERVE_REJECTED,
		/** Accepted record type is not supported for local comparison. */
		HOLD_UNSUPPORTED_TYPE,
		/** The contributor UUID is no longer present locally. */
		HOLD_MISSING_LOCAL_RECORD,
		/** Local revision differs from the submitted revision. */
		PRESERVE_LATER_LOCAL_REVISION,
		/** Accepted master state is retained; local revision is unchanged. */
		KNOWN_MASTER_RECORDED
	}

	/**
	 * One acknowledged outcome with its accepted master record and current local
	 * revision assessment.
	 *
	 * @param outcome         retained terminal receipt outcome
	 * @param masterRecord    canonical accepted master record, or null for a
	 *                        rejected outcome
	 * @param localRevisionId current contributor revision, or null if not
	 *                        applicable or not present
	 * @param status          safe non-mutating disposition
	 */
	public record Entry(ExchangeRecord outcome, ExchangeRecord masterRecord, UUID localRevisionId, Status status) {

		/**
		 * Validates a rebase entry.
		 *
		 * @throws NullPointerException if outcome or status is null
		 */
		public Entry {
			Objects.requireNonNull(outcome, "outcome");
			Objects.requireNonNull(status, "status");
		}
	}

	/**
	 * Rebase view of a durable receipt against current contributor state.
	 *
	 * <p>
	 * If a newer assignment is installed, this receipt remains historical evidence
	 * and must not be treated as the new assignment baseline.
	 *
	 * @param acknowledgement             durable authenticated receipt evidence
	 * @param supersededByNewerAssignment whether another later-issued assignment
	 *                                    for this Exam has been installed locally
	 * @param entries                     immutable per-record known-master
	 *                                    assessments
	 */
	public record View(Acknowledgement acknowledgement, boolean supersededByNewerAssignment, List<Entry> entries) {

		/**
		 * Validates and defensively retains the view entries.
		 *
		 * @throws NullPointerException if required evidence is null
		 */
		public View {
			Objects.requireNonNull(acknowledgement, "acknowledgement");
			entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
		}
	}
}
