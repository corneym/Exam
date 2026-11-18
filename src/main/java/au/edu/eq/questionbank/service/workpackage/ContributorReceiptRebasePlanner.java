package au.edu.eq.questionbank.service.workpackage;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.workpackage.MasterContributionApplicationCoordinator.Outcome;

/**
 * Produces a read-only contributor rebase assessment from a validated master
 * import receipt.
 *
 * <p>
 * The assessment compares the revision submitted to the master with the
 * contributor's current local revision. A later local revision must be
 * preserved; an unchanged local revision is not sufficient authority to replace
 * local state.
 *
 * <p>
 * Receipt records do not contain full accepted master values or cryptographic
 * sender authentication. Therefore this planner never modifies portable
 * identities, domain records, assignment baselines, return history or managed
 * assets.
 */
public final class ContributorReceiptRebasePlanner {

	private final SqliteDatabase database;

	/**
	 * Creates a rebase assessment planner for a contributor database.
	 *
	 * @param database initialised contributor database
	 * @throws NullPointerException if database is null
	 */
	public ContributorReceiptRebasePlanner(SqliteDatabase database) {
		this.database = Objects.requireNonNull(database, "database");
	}

	/**
	 * Inspects a receipt and assesses every outcome against the contributor's
	 * current portable identity revision.
	 *
	 * <p>
	 * All dispositions are non-mutating. An accepted outcome with an unchanged
	 * local revision awaits trusted master content; one with a newer local revision
	 * must retain the newer local work.
	 *
	 * @param archive original IMPORT_RECEIPT archive
	 * @return immutable, read-only rebase assessment
	 * @throws IOException          if the receipt is malformed or inconsistent
	 * @throws SQLException         if local assignment or identity evidence cannot
	 *                              be read
	 * @throws NullPointerException if archive is null
	 */
	public Plan plan(Path archive) throws IOException, SQLException {
		Objects.requireNonNull(archive, "archive");
		ContributorReceiptInspector.Inspection receipt = new ContributorReceiptInspector(database).inspect(archive);
		SqlitePortableIdentityRepository identities = new SqlitePortableIdentityRepository(database);
		List<Assessment> assessments = new ArrayList<>();
		for (ExchangeRecord record : receipt.outcomes()) {
			JsonNode values = record.values();
			String typeName = values.path("record_type").asText();
			Outcome outcome = Outcome.valueOf(values.path("outcome").asText());
			UUID proposedRevision = requiredUuid(values, "proposed_revision_id");
			UUID canonicalMasterId = optionalUuid(values, "canonical_record_id");
			UUID resultingMasterRevision = optionalUuid(values, "resulting_revision_id");
			UUID localRevision = null;
			Disposition disposition;
			if (outcome == Outcome.REJECTED) {
				disposition = Disposition.PRESERVE_REJECTED;
			} else if (!typeName.equals("QUESTION") && !typeName.equals("ANSWER")) {
				disposition = Disposition.HOLD_UNSUPPORTED_TYPE;
			} else {
				EntityType type = EntityType.valueOf(typeName);
				var identity = identities.findByPortableId(type, record.recordId());
				if (identity.isEmpty()) {
					disposition = Disposition.HOLD_MISSING_LOCAL_RECORD;
				} else {
					localRevision = identity.orElseThrow().revisionId();
					disposition = localRevision.equals(proposedRevision) ? Disposition.AWAIT_TRUSTED_MASTER_STATE
							: Disposition.PRESERVE_LATER_LOCAL_REVISION;
				}
			}
			assessments.add(new Assessment(typeName, record.recordId(), proposedRevision, canonicalMasterId,
					resultingMasterRevision, localRevision, outcome, disposition));
		}
		return new Plan(receipt.reviewId(), receipt.returnPackageId(), assessments);
	}

	private UUID optionalUuid(JsonNode values, String field) throws PackageFormatException {
		JsonNode value = values.get(field);
		if (value == null) {
			throw new PackageFormatException("Missing receipt field: " + field);
		}
		return value.isNull() ? null : requiredUuid(values, field);
	}

	private UUID requiredUuid(JsonNode values, String field) throws PackageFormatException {
		JsonNode value = values.get(field);
		if (value == null || !value.isTextual()) {
			throw new PackageFormatException("Missing receipt UUID: " + field);
		}
		try {
			UUID parsed = UUID.fromString(value.asText());
			if (!parsed.toString().equals(value.asText())) {
				throw new IllegalArgumentException("Noncanonical UUID");
			}
			return parsed;
		} catch (IllegalArgumentException failure) {
			throw new PackageFormatException("Invalid receipt UUID: " + field, failure);
		}
	}

	/**
	 * Non-mutating action required for a receipt result.
	 */
	public enum Disposition {
		/** The master rejected the proposal; local work is retained. */
		PRESERVE_REJECTED,
		/** The receipt names a record type not supported by this planner. */
		HOLD_UNSUPPORTED_TYPE,
		/** No corresponding active local portable record exists. */
		HOLD_MISSING_LOCAL_RECORD,
		/** The local record changed since the acknowledged submission. */
		PRESERVE_LATER_LOCAL_REVISION,
		/** Local revision matches the submission; trusted master state is needed. */
		AWAIT_TRUSTED_MASTER_STATE
	}

	/**
	 * Assessment of one contributor record acknowledged by the master.
	 *
	 * @param recordType                portable entity type
	 * @param contributorRecordId       original contributor portable UUID
	 * @param proposedRevisionId        revision included in the return
	 * @param canonicalMasterId         accepted master UUID, or null for rejection
	 * @param resultingMasterRevisionId accepted master revision, or null for
	 *                                  rejection
	 * @param localRevisionId           currently observed local revision, or null
	 *                                  if not inspected or not present
	 * @param outcome                   committed master application outcome
	 * @param disposition               safe non-mutating response to that outcome
	 */
	public record Assessment(String recordType, UUID contributorRecordId, UUID proposedRevisionId,
			UUID canonicalMasterId, UUID resultingMasterRevisionId, UUID localRevisionId, Outcome outcome,
			Disposition disposition) {

		/**
		 * Validates the assessment's identity and outcome evidence.
		 *
		 * @throws NullPointerException     if required values are null
		 * @throws IllegalArgumentException if master mapping evidence contradicts the
		 *                                  outcome
		 */
		public Assessment {
			Objects.requireNonNull(recordType, "recordType");
			Objects.requireNonNull(contributorRecordId, "contributorRecordId");
			Objects.requireNonNull(proposedRevisionId, "proposedRevisionId");
			Objects.requireNonNull(outcome, "outcome");
			Objects.requireNonNull(disposition, "disposition");
			boolean hasCanonical = canonicalMasterId != null;
			boolean hasRevision = resultingMasterRevisionId != null;
			if (hasCanonical != hasRevision || hasCanonical == (outcome == Outcome.REJECTED)) {
				throw new IllegalArgumentException("Inconsistent receipt master mapping");
			}
		}
	}

	/**
	 * Immutable rebase assessment for one acknowledged contributor return.
	 *
	 * @param reviewId        completed coordinator review identity
	 * @param returnPackageId original contributor return package identity
	 * @param assessments     immutable per-record assessments
	 */
	public record Plan(UUID reviewId, UUID returnPackageId, List<Assessment> assessments) {

		/**
		 * Copies and validates assessment records.
		 *
		 * @throws NullPointerException     if required evidence is null
		 * @throws IllegalArgumentException if there are no outcomes
		 */
		public Plan {
			Objects.requireNonNull(reviewId, "reviewId");
			Objects.requireNonNull(returnPackageId, "returnPackageId");
			assessments = List.copyOf(Objects.requireNonNull(assessments, "assessments"));
			if (assessments.isEmpty()) {
				throw new IllegalArgumentException("A rebase assessment needs receipt outcomes");
			}
		}
	}
}
