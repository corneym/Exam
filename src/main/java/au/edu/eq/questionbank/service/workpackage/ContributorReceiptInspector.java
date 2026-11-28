package au.edu.eq.questionbank.service.workpackage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import com.fasterxml.jackson.databind.JsonNode;

import au.edu.eq.questionbank.ApplicationVersion;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.workpackage.ExamWorkManifest.EntryRole;
import au.edu.eq.questionbank.service.workpackage.ExamWorkManifest.ScopeLevel;
import au.edu.eq.questionbank.service.workpackage.MasterContributionApplicationCoordinator.Outcome;
import au.edu.eq.questionbank.service.workpackage.MasterContributionDecisionLedger.Choice;
import au.edu.eq.questionbank.service.workpackage.MasterContributionReviewPlanner.ReviewStatus;

/**
 * Validates a coordinator import receipt against the contributor's installed
 * assignment and published return history.
 *
 * <p>
 * Inspection is read-only. It does not acknowledge the receipt, advance a
 * baseline, replace contributor records or discard edits.
 *
 * <p>
 * Archive checksums establish content integrity, not cryptographic proof of the
 * sender. Receipt authority and non-destructive rebase must be enforced before
 * receipt data is used to modify local state.
 */
public final class ContributorReceiptInspector {

	private static final String RECEIPT_PATH = "receipts/import-results.jsonl";
	private static final String MASTER_STATE_PATH = "records/accepted-master-state.jsonl";
	private final SqliteDatabase database;

	/**
	 * Creates a receipt inspector for a contributor installation.
	 *
	 * @param database initialised contributor database
	 * @throws NullPointerException if database is null
	 */
	public ContributorReceiptInspector(SqliteDatabase database) {
		this.database = Objects.requireNonNull(database, "database");
	}

	/**
	 * Validates a receipt against detached assignment and return history.
	 *
	 * <p>
	 * This operation does not authenticate the sender. It preserves the historical
	 * read-only inspection path while also validating accepted master records when
	 * the receipt contains them.
	 *
	 * @param archive original receipt archive
	 * @return validated, non-mutating receipt inspection
	 * @throws IOException          if the archive or receipt evidence is invalid
	 * @throws SQLException         if local assignment evidence cannot be read
	 * @throws NullPointerException if archive is null
	 */
	public Inspection inspect(Path archive) throws IOException, SQLException {
		Objects.requireNonNull(archive, "archive");
		String originalDigest = sha256(archive);
		ExamWorkManifest manifest = new ExamWorkArchiveValidator().validate(archive, ApplicationVersion.current(),
				database.schemaVersion());
		if (manifest.packageType() != ExamWorkPackageType.IMPORT_RECEIPT) {
			throw new PackageFormatException("Contributor receipt requires IMPORT_RECEIPT");
		}
		boolean containsMasterState = requireStructure(manifest);
		var returns = new SqliteContributionReturnLedger(database);
		var assignment = returns.findAssignmentEvidence(manifest.assignmentId())
				.orElseThrow(() -> new SQLException("Receipt assignment has no detached return evidence"));
		UUID installationId = new SqlitePortableIdentityRepository(database).installationId();
		if (!manifest.originInstallationId().equals(assignment.masterInstallationId())
				|| !manifest.intendedMasterInstallationId().equals(assignment.masterInstallationId())
				|| !manifest.baselineSnapshotId().equals(assignment.baselineSnapshotId())
				|| !manifest.subjectId().equals(assignment.subjectPortableId())
				|| manifest.scope().level() != ScopeLevel.EXAMS
				|| !manifest.scope().examIds().equals(List.of(assignment.examPortableId()))
				|| !manifest.scope().bookletIds().isEmpty()) {
			throw new PackageFormatException("Receipt does not match detached assignment evidence");
		}
		List<ExchangeRecord> records;
		List<ExchangeRecord> acceptedMasterRecords = List.of();
		try (ZipFile zip = ZipFile.builder().setPath(archive).get()) {
			ZipArchiveEntry entry = zip.getEntry(RECEIPT_PATH);
			if (entry == null) {
				throw new PackageFormatException("Missing receipt records");
			}
			ExamWorkJsonlCodec codec = new ExamWorkJsonlCodec();
			try (InputStream input = zip.getInputStream(entry)) {
				records = codec.readRecords(input);
			}
			if (containsMasterState) {
				ZipArchiveEntry masterEntry = zip.getEntry(MASTER_STATE_PATH);
				if (masterEntry == null) {
					throw new PackageFormatException("Missing accepted master records");
				}
				try (InputStream input = zip.getInputStream(masterEntry)) {
					acceptedMasterRecords = codec.readRecords(input);
				}
			}
		}
		if (!originalDigest.equals(sha256(archive))) {
			throw new PackageFormatException("Receipt archive changed during inspection");
		}
		ExchangeRecord context = records.stream().filter(record -> "IMPORT_RECEIPT_CONTEXT".equals(record.recordType()))
				.findFirst().orElseThrow(() -> new PackageFormatException("Missing receipt context"));
		if (!context.recordId().equals(manifest.packageId())
				|| !Objects.equals(context.revisionId(), manifest.packageId())
				|| context.baselineRevisionId() != null) {
			throw new PackageFormatException("Invalid receipt context identity");
		}
		JsonNode values = context.values();
		UUID reviewId = requiredUuid(values, "review_id");
		UUID returnId = requiredUuid(values, "contribution_package_id");
		UUID expectedReceiptId = UUID
				.nameUUIDFromBytes(("examwork-import-receipt-v1:" + reviewId).getBytes(StandardCharsets.UTF_8));
		if (!expectedReceiptId.equals(manifest.packageId())) {
			throw new PackageFormatException("Receipt identity does not match review");
		}
		requireUuid(values, "receipt_package_id", manifest.packageId());
		requireUuid(values, "assignment_id", assignment.assignmentId());
		requireUuid(values, "baseline_snapshot_id", assignment.baselineSnapshotId());
		requireUuid(values, "master_installation_id", assignment.masterInstallationId());
		requireUuid(values, "contributor_id", assignment.contributorId());
		requireUuid(values, "contributor_installation_id", installationId);
		String completedAt = requiredText(values, "completed_at");
		try {
			if (!Instant.parse(completedAt).equals(manifest.createdAt())) {
				throw new PackageFormatException("Receipt completion time differs from manifest");
			}
		} catch (java.time.format.DateTimeParseException failure) {
			throw new PackageFormatException("Invalid receipt completion time", failure);
		}
		var published = returns.find(returnId)
				.orElseThrow(() -> new SQLException("Receipt refers to an unpublished local return"));
		if (!published.assignmentId().equals(assignment.assignmentId())
				|| !published.baselineSnapshotId().equals(assignment.baselineSnapshotId())
				|| !published.masterInstallationId().equals(assignment.masterInstallationId())
				|| !published.contributorId().equals(assignment.contributorId())
				|| !published.originInstallationId().equals(installationId)) {
			throw new PackageFormatException("Receipt references foreign return history");
		}
		List<ExchangeRecord> outcomes = new ArrayList<>();
		for (ExchangeRecord record : records) {
			if (record == context) {
				continue;
			}
			validateOutcome(record, reviewId);
			outcomes.add(record);
		}
		JsonNode countNode = values.get("record_count");
		if (countNode == null || !countNode.isIntegralNumber() || !countNode.canConvertToInt()
				|| countNode.intValue() != outcomes.size() || outcomes.isEmpty()) {
			throw new PackageFormatException("Receipt outcome count is inconsistent");
		}
		if (containsMasterState) {
			validateMasterRecords(outcomes, acceptedMasterRecords);
		}
		return new Inspection(manifest, reviewId, returnId, outcomes, acceptedMasterRecords, originalDigest);
	}

	/**
	 * Inspects a receipt whose exact archive digest has been independently
	 * confirmed through a trusted coordinator channel.
	 *
	 * <p>
	 * The caller must obtain the expected digest independently of the archive being
	 * examined. Reading a digest supplied inside or alongside an untrusted archive
	 * does not establish coordinator authority.
	 *
	 * <p>
	 * Accepted outcomes must contain complete canonical master records. Historical
	 * receipts without these records remain inspectable but cannot enter the
	 * trusted rebase path.
	 *
	 * @param archive        received receipt archive
	 * @param expectedSha256 independently confirmed SHA-256 archive digest
	 * @return validated, digest-matched receipt and accepted master state
	 * @throws IOException          if the digest, archive or master-state evidence
	 *                              is invalid or inconsistent
	 * @throws SQLException         if contributor assignment evidence is invalid
	 * @throws NullPointerException if an argument is null
	 */
	public Inspection inspectTrusted(Path archive, String expectedSha256) throws IOException, SQLException {
		Objects.requireNonNull(archive, "archive");
		Objects.requireNonNull(expectedSha256, "expectedSha256");
		if (!expectedSha256.matches("[0-9a-f]{64}")) {
			throw new PackageFormatException("Invalid independently confirmed receipt SHA-256");
		}
		Inspection inspection = inspect(archive);
		byte[] expected = HexFormat.of().parseHex(expectedSha256);
		byte[] actual = HexFormat.of().parseHex(inspection.archiveSha256());
		if (!MessageDigest.isEqual(expected, actual)) {
			throw new PackageFormatException("Receipt does not match independently confirmed SHA-256");
		}
		long accepted = inspection.outcomes().stream()
				.filter(record -> !record.values().path("outcome").asText().equals("REJECTED")).count();
		if (accepted != inspection.acceptedMasterRecords().size()) {
			throw new PackageFormatException("Trusted receipt lacks complete accepted master state");
		}
		return inspection;
	}

	private UUID optionalUuid(JsonNode values, String field) throws PackageFormatException {
		JsonNode node = values.get(field);
		if (node == null) {
			throw new PackageFormatException("Missing receipt field: " + field);
		}
		return node.isNull() ? null : requiredUuid(values, field);
	}

	private String requiredText(JsonNode values, String field) throws PackageFormatException {
		JsonNode value = values.get(field);
		if (value == null || !value.isTextual() || value.asText().isBlank()) {
			throw new PackageFormatException("Missing receipt text: " + field);
		}
		return value.asText();
	}

	private UUID requiredUuid(JsonNode values, String field) throws PackageFormatException {
		String value = requiredText(values, field);
		try {
			UUID result = UUID.fromString(value);
			if (!result.toString().equals(value)) {
				throw new IllegalArgumentException("Noncanonical UUID");
			}
			return result;
		} catch (IllegalArgumentException failure) {
			throw new PackageFormatException("Invalid receipt UUID: " + field, failure);
		}
	}

	private boolean requireStructure(ExamWorkManifest manifest) throws PackageFormatException {
		if (manifest.entries().size() < 2 || manifest.entries().size() > 3) {
			throw new PackageFormatException("Receipt must contain two or three structured entries");
		}
		boolean index = false;
		boolean results = false;
		boolean masterState = false;
		for (var entry : manifest.entries()) {
			if (entry.path().equals(ExamWorkAssetIndex.INDEX_PATH) && entry.role() == EntryRole.INDEX) {
				index = true;
			} else if (entry.path().equals(RECEIPT_PATH) && entry.role() == EntryRole.RECEIPT) {
				results = true;
			} else if (entry.path().equals(MASTER_STATE_PATH) && entry.role() == EntryRole.RECORDS) {
				masterState = true;
			} else {
				throw new PackageFormatException("Unexpected receipt archive entry: " + entry.path());
			}
		}
		if (!index || !results) {
			throw new PackageFormatException("Receipt structure is incomplete");
		}
		return masterState;
	}

	private void requireUuid(JsonNode values, String field, UUID expected) throws PackageFormatException {
		if (!expected.equals(requiredUuid(values, field))) {
			throw new PackageFormatException("Receipt identity mismatch: " + field);
		}
	}

	private String sha256(Path archive) throws IOException {
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException failure) {
			throw new IllegalStateException("SHA-256 is unavailable", failure);
		}
		try (InputStream input = Files.newInputStream(archive)) {
			byte[] buffer = new byte[8192];
			for (int count; (count = input.read(buffer)) != -1;) {
				digest.update(buffer, 0, count);
			}
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	private void validateMasterRecords(List<ExchangeRecord> outcomes, List<ExchangeRecord> masterRecords)
			throws PackageFormatException {
		Map<String, ExchangeRecord> expected = new HashMap<>();
		for (ExchangeRecord outcome : outcomes) {
			JsonNode values = outcome.values();
			if ("REJECTED".equals(values.path("outcome").asText())) {
				continue;
			}
			String type = requiredText(values, "record_type");
			UUID canonical = requiredUuid(values, "canonical_record_id");
			String key = type + "\0" + canonical;
			if (expected.putIfAbsent(key, outcome) != null) {
				throw new PackageFormatException("Duplicate receipt canonical master mapping");
			}
		}
		for (ExchangeRecord master : masterRecords) {
			if (!"QUESTION".equals(master.recordType()) && !"ANSWER".equals(master.recordType())) {
				throw new PackageFormatException("Unsupported accepted master record type");
			}
			String key = master.recordType() + "\0" + master.recordId();
			ExchangeRecord outcome = expected.remove(key);
			if (outcome == null) {
				throw new PackageFormatException("Unexpected or duplicate accepted master record");
			}
			UUID revision = requiredUuid(outcome.values(), "resulting_revision_id");
			if (!revision.equals(master.revisionId()) || master.baselineRevisionId() != null) {
				throw new PackageFormatException("Accepted master record revision disagrees " + "with receipt outcome");
			}
		}
		if (!expected.isEmpty()) {
			throw new PackageFormatException("Receipt is missing accepted master records");
		}
	}

	private void validateOutcome(ExchangeRecord record, UUID reviewId) throws PackageFormatException {
		String prefix = "IMPORT_RECEIPT_";
		if (!record.recordType().startsWith(prefix) || record.recordType().equals("IMPORT_RECEIPT_CONTEXT")) {
			throw new PackageFormatException("Invalid receipt outcome record type");
		}
		JsonNode values = record.values();
		requireUuid(values, "review_id", reviewId);
		requireUuid(values, "record_id", record.recordId());
		String expectedType = record.recordType().substring(prefix.length());
		if (!expectedType.equals(requiredText(values, "record_type"))) {
			throw new PackageFormatException("Receipt outcome record type mismatch");
		}
		Outcome outcome;
		Choice choice;
		ReviewStatus status;
		try {
			outcome = Outcome.valueOf(requiredText(values, "outcome"));
			choice = Choice.valueOf(requiredText(values, "decision"));
			status = ReviewStatus.valueOf(requiredText(values, "review_status"));
		} catch (IllegalArgumentException failure) {
			throw new PackageFormatException("Unknown receipt decision or outcome", failure);
		}
		requiredUuid(values, "proposed_revision_id");
		UUID canonical = optionalUuid(values, "canonical_record_id");
		UUID resulting = optionalUuid(values, "resulting_revision_id");
		boolean accepted = outcome != Outcome.REJECTED;
		if (accepted != (canonical != null) || accepted != (resulting != null)
				|| !Objects.equals(record.revisionId(), resulting) || record.baselineRevisionId() != null) {
			throw new PackageFormatException("Receipt outcome has inconsistent master identity");
		}
		boolean matchingDecision = switch (outcome) {
		case APPLIED ->
			choice == Choice.QUEUE_APPLY && (status == ReviewStatus.READY_NEW || status == ReviewStatus.READY_UPDATE);
		case SKIPPED -> choice == Choice.SKIP_IDENTICAL && status == ReviewStatus.SKIP_IDENTICAL;
		case REJECTED -> choice == Choice.REJECT;
		};
		if (!matchingDecision) {
			throw new PackageFormatException("Receipt decision and terminal outcome disagree");
		}
	}

	/**
	 * Fully inspected receipt evidence and any accepted canonical master records
	 * embedded in the archive.
	 *
	 * <p>
	 * Inspection alone does not establish sender authority. Only a successful
	 * independently confirmed digest check permits this evidence to enter the
	 * trusted rebase assessment path.
	 *
	 * @param manifest              validated receipt package manifest
	 * @param reviewId              completed coordinator review identity
	 * @param returnPackageId       acknowledged contributor return identity
	 * @param outcomes              immutable per-record terminal outcomes
	 * @param acceptedMasterRecords complete accepted canonical records, or empty
	 *                              for a historical receipt without master-state
	 *                              entries
	 * @param archiveSha256         SHA-256 of the inspected original archive
	 */
	public record Inspection(ExamWorkManifest manifest, UUID reviewId, UUID returnPackageId,
			List<ExchangeRecord> outcomes, List<ExchangeRecord> acceptedMasterRecords, String archiveSha256) {

		/**
		 * Validates the inspection and defensively copies its lists.
		 *
		 * @throws NullPointerException if required evidence is null
		 */
		public Inspection {
			Objects.requireNonNull(manifest, "manifest");
			Objects.requireNonNull(reviewId, "reviewId");
			Objects.requireNonNull(returnPackageId, "returnPackageId");
			outcomes = List.copyOf(Objects.requireNonNull(outcomes, "outcomes"));
			acceptedMasterRecords = List.copyOf(Objects.requireNonNull(acceptedMasterRecords, "acceptedMasterRecords"));
			Objects.requireNonNull(archiveSha256, "archiveSha256");
		}
	}
}
