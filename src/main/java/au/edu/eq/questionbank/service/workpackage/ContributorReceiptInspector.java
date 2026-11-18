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
import java.util.HexFormat;
import java.util.List;
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
	 * Validates a received import receipt without changing local state.
	 *
	 * @param archive original IMPORT_RECEIPT .examwork archive
	 * @return validated receipt metadata and immutable record list
	 * @throws IOException          if archive contents or receipt evidence are
	 *                              invalid, unreadable or inconsistent
	 * @throws SQLException         if local assignment or return evidence cannot be
	 *                              read or does not exist
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
		requireStructure(manifest);
		var session = new SqliteContributorAssignmentSessionRepository(database).find(manifest.assignmentId())
				.orElseThrow(() -> new SQLException("Receipt assignment is not installed"));
		UUID installationId = new SqlitePortableIdentityRepository(database).installationId();
		if (!manifest.originInstallationId().equals(session.masterInstallationId())
				|| !manifest.intendedMasterInstallationId().equals(session.masterInstallationId())
				|| !manifest.baselineSnapshotId().equals(session.baselineSnapshotId())
				|| !manifest.subjectId().equals(session.subjectPortableId())
				|| manifest.scope().level() != ScopeLevel.EXAMS
				|| !manifest.scope().examIds().equals(List.of(session.examPortableId()))
				|| !manifest.scope().bookletIds().isEmpty()) {
			throw new PackageFormatException("Receipt does not match installed assignment");
		}
		List<ExchangeRecord> records;
		try (ZipFile zip = ZipFile.builder().setPath(archive).get()) {
			ZipArchiveEntry entry = zip.getEntry(RECEIPT_PATH);
			if (entry == null) {
				throw new PackageFormatException("Missing receipt records");
			}
			try (InputStream input = zip.getInputStream(entry)) {
				records = new ExamWorkJsonlCodec().readRecords(input);
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
		requireUuid(values, "assignment_id", session.assignmentId());
		requireUuid(values, "baseline_snapshot_id", session.baselineSnapshotId());
		requireUuid(values, "master_installation_id", session.masterInstallationId());
		requireUuid(values, "contributor_id", session.contributorId());
		requireUuid(values, "contributor_installation_id", installationId);
		String completedAt = requiredText(values, "completed_at");
		try {
			if (!Instant.parse(completedAt).equals(manifest.createdAt())) {
				throw new PackageFormatException("Receipt completion time differs from manifest");
			}
		} catch (java.time.format.DateTimeParseException failure) {
			throw new PackageFormatException("Invalid receipt completion time", failure);
		}
		var published = new SqliteContributionReturnLedger(database).find(returnId)
				.orElseThrow(() -> new SQLException("Receipt refers to an unpublished local return"));
		if (!published.assignmentId().equals(session.assignmentId())
				|| !published.baselineSnapshotId().equals(session.baselineSnapshotId())
				|| !published.masterInstallationId().equals(session.masterInstallationId())
				|| !published.contributorId().equals(session.contributorId())
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
		return new Inspection(manifest, reviewId, returnId, outcomes, originalDigest);
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

	private void requireStructure(ExamWorkManifest manifest) throws PackageFormatException {
		if (manifest.entries().size() != 2) {
			throw new PackageFormatException("Receipt must contain exactly two structured entries");
		}
		boolean index = false;
		boolean results = false;
		for (var entry : manifest.entries()) {
			if (entry.path().equals(ExamWorkAssetIndex.INDEX_PATH) && entry.role() == EntryRole.INDEX) {
				index = true;
			} else if (entry.path().equals(RECEIPT_PATH) && entry.role() == EntryRole.RECEIPT) {
				results = true;
			} else {
				throw new PackageFormatException("Unexpected receipt archive entry: " + entry.path());
			}
		}
		if (!index || !results) {
			throw new PackageFormatException("Receipt structure is incomplete");
		}
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
	 * Fully inspected receipt evidence.
	 *
	 * @param manifest        validated package manifest
	 * @param reviewId        completed coordinator review identity
	 * @param returnPackageId contributor return being acknowledged
	 * @param outcomes        immutable copy of per-record outcome envelopes
	 * @param archiveSha256   digest of the original archive
	 */
	public record Inspection(ExamWorkManifest manifest, UUID reviewId, UUID returnPackageId,
			List<ExchangeRecord> outcomes, String archiveSha256) {

		/**
		 * Validates and defensively copies the inspection result.
		 *
		 * @throws NullPointerException if required evidence is null
		 */
		public Inspection {
			Objects.requireNonNull(manifest, "manifest");
			Objects.requireNonNull(reviewId, "reviewId");
			Objects.requireNonNull(returnPackageId, "returnPackageId");
			outcomes = List.copyOf(Objects.requireNonNull(outcomes, "outcomes"));
			Objects.requireNonNull(archiveSha256, "archiveSha256");
		}
	}
}
