package au.edu.eq.questionbank.service.workpackage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import com.fasterxml.jackson.databind.JsonNode;

import au.edu.eq.questionbank.ApplicationVersion;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.workpackage.ExamWorkManifest.Entry;
import au.edu.eq.questionbank.service.workpackage.ExamWorkManifest.EntryRole;
import au.edu.eq.questionbank.service.workpackage.ExamWorkManifest.ScopeLevel;

/**
 * Inspects an offline contribution against the master-issued assignment.
 *
 * <p>
 * Inspection verifies archive integrity, immutable baseline evidence, return
 * metadata, proposed deltas, whole-Exam ownership and asset dependencies. It
 * does not persist a return, resolve conflicts or apply any contribution.
 */
public final class ContributionArchiveInspector {

	private static final String ASSETS_PATH = ExamWorkAssetIndex.INDEX_PATH;
	private static final String BASELINE_PATH = "baseline/initial-records.jsonl";
	private static final String CHANGES_PATH = "records/contribution-changes.jsonl";
	private static final String CONTEXT_PATH = "records/contribution-context.jsonl";
	private final SqliteDatabase database;

	/**
	 * Creates a read-only contribution inspector for the coordinator.
	 *
	 * @param database initialised coordinator database containing issued
	 *                 assignments
	 * @throws NullPointerException if database is null
	 */
	public ContributionArchiveInspector(SqliteDatabase database) {
		this.database = Objects.requireNonNull(database, "database");
	}

	/**
	 * Examines a contribution without modifying the coordinator database or assets.
	 *
	 * <p>
	 * Lineage is checked for internal consistency only. Whether an earlier return
	 * was received, accepted or superseded is decided by the master-side intake
	 * ledger in the subsequent implementation batch.
	 *
	 * @param archive path to a complete CONTRIBUTION .examwork archive
	 * @return verified contribution evidence and reconstructed proposed records
	 * @throws IOException          if the archive is unreadable or its evidence is
	 *                              invalid
	 * @throws SQLException         if coordinator assignment evidence cannot be
	 *                              retrieved
	 * @throws NullPointerException if archive is null
	 */
	public InspectedContribution inspect(Path archive) throws IOException, SQLException {
		Objects.requireNonNull(archive, "archive");
		ExamWorkManifest manifest = new ExamWorkArchiveValidator().validate(archive, ApplicationVersion.current(),
				database.schemaVersion());
		requireManifest(manifest);
		WorkAssignmentLedger ledger = new WorkAssignmentLedger(database);
		WorkAssignmentLedger.Assignment assignment = ledger.find(manifest.assignmentId())
				.orElseThrow(() -> new PackageFormatException("Unknown issued assignment: " + manifest.assignmentId()));
		requireAssignment(manifest, assignment);
		requireEntries(manifest);
		String initialDigest = sha256(archive);
		List<ExchangeRecord> baseline;
		List<ExchangeRecord> changes;
		ExamWorkAssetIndex assets;
		ExchangeRecord context;
		try (ZipFile zip = ZipFile.builder().setPath(archive).get()) {
			byte[] includedBaseline = readEntry(zip, BASELINE_PATH);
			byte[] storedBaseline = ledger.readBaseline(manifest.assignmentId()).records();
			if (!Arrays.equals(storedBaseline, includedBaseline)) {
				throw new PackageFormatException("Contribution baseline differs from issued master baseline");
			}
			ExamWorkJsonlCodec codec = new ExamWorkJsonlCodec();
			baseline = codec.readRecords(new ByteArrayInputStream(storedBaseline));
			changes = codec.readRecords(new ByteArrayInputStream(readEntry(zip, CHANGES_PATH)));
			List<ExchangeRecord> contexts = codec.readRecords(new ByteArrayInputStream(readEntry(zip, CONTEXT_PATH)));
			if (contexts.size() != 1) {
				throw new PackageFormatException("Contribution requires exactly one context record");
			}
			context = contexts.getFirst();
			assets = codec.readAssetIndex(new ByteArrayInputStream(readEntry(zip, ASSETS_PATH)));
		}
		if (!initialDigest.equals(sha256(archive))) {
			throw new PackageFormatException("Contribution archive changed during inspection");
		}
		int returnNumber = requirePositiveInteger(context, "return_number");
		UUID previousPackageId = requireContext(manifest, assignment, context, changes.size(), returnNumber);
		List<ExchangeRecord> proposed = reconstruct(baseline, changes);

		// A valid delta is not enough: every relationship and packaged asset
		// must resolve to a record within the assigned whole-Exam graph.
		new WorkAssignmentScopeValidator().validateContribution(manifest, proposed, assets);

		// The merged portable-domain validator checks semantic restrictions
		// that structural reference and asset checks alone cannot establish.
		PortableDomainRecordValidator.validate(manifest.subjectId(), proposed);
		return new InspectedContribution(manifest, assignment, returnNumber, previousPackageId, changes, proposed,
				assets, initialDigest);
	}

	private UUID parseUuid(JsonNode value, String field) throws PackageFormatException {
		if (value == null || !value.isTextual()) {
			throw new PackageFormatException("Missing contribution UUID: " + field);
		}
		try {
			UUID parsed = UUID.fromString(value.asText());
			if (!parsed.toString().equals(value.asText())) {
				throw new IllegalArgumentException("Noncanonical UUID");
			}
			return parsed;
		} catch (IllegalArgumentException exception) {
			throw new PackageFormatException("Invalid contribution UUID: " + field, exception);
		}
	}

	private byte[] readEntry(ZipFile zip, String path) throws IOException {
		ZipArchiveEntry entry = zip.getEntry(path);
		if (entry == null) {
			throw new PackageFormatException("Missing contribution entry: " + path);
		}
		long size = entry.getSize();
		if (size < 0 || size > 64L * 1024 * 1024) {
			throw new PackageFormatException("Contribution structured entry exceeds size limit: " + path);
		}
		try (InputStream input = zip.getInputStream(entry)) {
			byte[] bytes = input.readNBytes((int) size + 1);
			if (bytes.length != size) {
				throw new PackageFormatException("Contribution entry size changed: " + path);
			}
			return bytes;
		}
	}

	private List<ExchangeRecord> reconstruct(List<ExchangeRecord> baseline, List<ExchangeRecord> changes)
			throws PackageFormatException {
		Map<String, ExchangeRecord> proposed = new TreeMap<>();
		for (ExchangeRecord record : baseline) {
			String key = record.recordType() + "\0" + record.recordId();
			if (proposed.putIfAbsent(key, record) != null) {
				throw new PackageFormatException("Duplicate issued baseline record: " + key);
			}
		}
		for (ExchangeRecord change : changes) {
			String key = change.recordType() + "\0" + change.recordId();

			// Planner input must represent current state, without return-specific
			// baselineRevisionId metadata on each contribution candidate.
			proposed.put(key, new ExchangeRecord(change.recordVersion(), change.recordType(), change.recordId(),
					change.revisionId(), null, change.values()));
		}
		List<ExchangeRecord> current = List.copyOf(proposed.values());
		if (!new ContributionDeltaPlanner().plan(baseline, current).candidates().equals(changes)) {
			throw new PackageFormatException("Contribution changes do not match the issued baseline");
		}
		return current;
	}

	private void requireAssignment(ExamWorkManifest manifest, WorkAssignmentLedger.Assignment assignment)
			throws SQLException, PackageFormatException {
		UUID masterId = new SqlitePortableIdentityRepository(database).installationId();
		if (!masterId.equals(assignment.masterInstallationId())
				|| !masterId.equals(manifest.intendedMasterInstallationId())
				|| !assignment.baselineSnapshotId().equals(manifest.baselineSnapshotId())
				|| !assignment.subjectPortableId().equals(manifest.subjectId())
				|| !manifest.scope().examIds().equals(List.of(assignment.examPortableId()))
				|| manifest.packageId().equals(assignment.packageId())) {
			throw new PackageFormatException("Contribution does not match the issued master assignment");
		}
	}

	private UUID requireContext(ExamWorkManifest manifest, WorkAssignmentLedger.Assignment assignment,
			ExchangeRecord context, int candidateCount, int returnNumber) throws PackageFormatException {
		if (!"CONTRIBUTION_CONTEXT".equals(context.recordType()) || !manifest.packageId().equals(context.recordId())
				|| !manifest.packageId().equals(context.revisionId()) || context.baselineRevisionId() != null) {
			throw new PackageFormatException("Invalid contribution context identity");
		}
		requireId(context, "assignment_id", assignment.assignmentId());
		requireId(context, "baseline_snapshot_id", assignment.baselineSnapshotId());
		requireId(context, "contributor_id", assignment.contributorId());
		requireId(context, "master_installation_id", assignment.masterInstallationId());
		requireId(context, "origin_installation_id", manifest.originInstallationId());
		requireId(context, "issued_package_id", assignment.packageId());
		requireId(context, "package_id", manifest.packageId());
		requireId(context, "subject_id", assignment.subjectPortableId());
		requireId(context, "exam_id", assignment.examPortableId());
		requireText(context, "contributor_name", assignment.contributorName());
		requireText(context, "created_at", manifest.createdAt().toString());
		if (requirePositiveInteger(context, "candidate_count") != candidateCount || candidateCount == 0) {
			throw new PackageFormatException("Contribution candidate count is inconsistent");
		}
		JsonNode previous = context.values().get("previous_return_package_id");
		if (previous == null) {
			throw new PackageFormatException("Missing previous return package field");
		}
		UUID previousId = previous.isNull() ? null : parseUuid(previous, "previous_return_package_id");
		if ((returnNumber == 1) != (previousId == null) || manifest.packageId().equals(previousId)) {
			throw new PackageFormatException("Invalid contribution return lineage");
		}
		return previousId;
	}

	private void requireEntries(ExamWorkManifest manifest) throws PackageFormatException {
		Map<String, EntryRole> expected = new HashMap<>(Map.of(ASSETS_PATH, EntryRole.INDEX, BASELINE_PATH,
				EntryRole.BASELINE, CHANGES_PATH, EntryRole.RECORDS, CONTEXT_PATH, EntryRole.RECORDS));
		for (Entry entry : manifest.entries()) {
			if (entry.role() == EntryRole.ASSET) {
				continue;
			}
			if (expected.remove(entry.path()) != entry.role()) {
				throw new PackageFormatException("Unexpected contribution archive entry: " + entry.path());
			}
		}
		if (!expected.isEmpty()) {
			throw new PackageFormatException("Missing required contribution archive entries: " + expected.keySet());
		}
	}

	private void requireId(ExchangeRecord context, String field, UUID expected) throws PackageFormatException {
		if (!expected.equals(parseUuid(context.values().get(field), field))) {
			throw new PackageFormatException("Contribution context mismatch: " + field);
		}
	}

	private void requireManifest(ExamWorkManifest manifest) throws PackageFormatException {
		if (manifest.packageType() != ExamWorkPackageType.CONTRIBUTION || manifest.scope().level() != ScopeLevel.EXAMS
				|| manifest.scope().examIds().size() != 1 || !manifest.scope().bookletIds().isEmpty()) {
			throw new PackageFormatException("Contribution must cover exactly one assigned Exam");
		}
	}

	private int requirePositiveInteger(ExchangeRecord context, String field) throws PackageFormatException {
		JsonNode node = context.values().get(field);
		if (node == null || !node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() < 1) {
			throw new PackageFormatException("Invalid contribution context integer: " + field);
		}
		return node.intValue();
	}

	private void requireText(ExchangeRecord context, String field, String expected) throws PackageFormatException {
		JsonNode value = context.values().get(field);
		if (value == null || !value.isTextual() || !expected.equals(value.asText())) {
			throw new PackageFormatException("Contribution context mismatch: " + field);
		}
	}

	private String sha256(Path path) throws IOException {
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 unavailable", exception);
		}
		try (InputStream input = Files.newInputStream(path)) {
			byte[] buffer = new byte[8192];
			int length;
			while ((length = input.read(buffer)) != -1) {
				digest.update(buffer, 0, length);
			}
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	/**
	 * Immutable, read-only evidence prepared for subsequent master-side review.
	 *
	 * @param manifest          validated contribution manifest
	 * @param assignment        original master-issued assignment
	 * @param returnNumber      contributor-declared one-based return sequence
	 * @param previousPackageId preceding contributor return, or null for the first
	 * @param changes           validated proposed contributor changes
	 * @param proposedRecords   whole-Exam graph after applying the candidate
	 *                          overlay
	 * @param assets            validated current contribution asset references
	 * @param archiveSha256     SHA-256 of the complete received archive
	 */
	public record InspectedContribution(ExamWorkManifest manifest, WorkAssignmentLedger.Assignment assignment,
			int returnNumber, UUID previousPackageId, List<ExchangeRecord> changes,
			List<ExchangeRecord> proposedRecords, ExamWorkAssetIndex assets, String archiveSha256) {

		/**
		 * Defensively retains the inspected content and validates basic evidence.
		 */
		public InspectedContribution {
			Objects.requireNonNull(manifest, "manifest");
			Objects.requireNonNull(assignment, "assignment");
			changes = List.copyOf(Objects.requireNonNull(changes, "changes"));
			proposedRecords = List.copyOf(Objects.requireNonNull(proposedRecords, "proposedRecords"));
			Objects.requireNonNull(assets, "assets");
			if (returnNumber < 1 || (returnNumber == 1) != (previousPackageId == null)) {
				throw new IllegalArgumentException("Invalid contribution return order");
			}
			if (archiveSha256 == null || !archiveSha256.matches("[0-9a-f]{64}")) {
				throw new IllegalArgumentException("Invalid contribution archive digest");
			}
		}
	}
}
