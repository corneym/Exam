package au.edu.eq.questionbank.service.workpackage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.workpackage.WorkAssignmentPackageInspector.Inspection;

/**
 * Establishes whether historical assignment reconciliation evidence corresponds
 * to locally submitted contributor work.
 *
 * <p>
 * Assessment is read-only. It does not authorise arbitrary identity replacement
 * or modify the contributor corpus.
 */
final class AssignmentReconciliationAuthority {

	private final SqliteDatabase database;

	AssignmentReconciliationAuthority(SqliteDatabase database) {
		this.database = Objects.requireNonNull(database, "database");
	}

	List<ExchangeRecord> assess(Inspection incoming) throws SQLException, IOException {
		Objects.requireNonNull(incoming, "incoming");
		new AssignmentReconciliationEvidenceValidator().validate(incoming.manifest(), incoming.context());
		var sessions = new SqliteContributorAssignmentSessionRepository(database);
		var returns = new SqliteContributionReturnLedger(database);
		var identities = new SqlitePortableIdentityRepository(database);
		UUID installationId = identities.installationId();
		UUID examId = incoming.manifest().scope().examIds().getFirst();
		UUID contributorId = uuid(incoming.context().values(), "contributor_id");
		List<ExchangeRecord> accepted = new ArrayList<>();
		JsonNode evidence = incoming.context().values().path("reconciliation_evidence");
		for (JsonNode entry : evidence) {
			JsonNode mapping = entry.path("mapping");
			UUID originId = uuid(mapping, "origin_installation_id");

			// The same contributor may previously have worked
			// on another installation. Its historical mappings
			// must not authorise changes to this installation.
			if (!installationId.equals(originId)) {
				continue;
			}
			UUID assignmentId = uuid(mapping, "assignment_id");
			var session = sessions.find(assignmentId)
					.orElseThrow(() -> new PackageFormatException("Reconciliation assignment is not installed"));
			if (!session.examPortableId().equals(examId)
					|| !session.subjectPortableId().equals(incoming.manifest().subjectId())
					|| !session.contributorId().equals(contributorId)
					|| !session.masterInstallationId().equals(incoming.manifest().originInstallationId())
					|| !session.baselineSnapshotId().equals(uuid(mapping, "baseline_snapshot_id"))) {
				throw new PackageFormatException("Reconciliation evidence differs from local assignment");
			}
			if (!incoming.manifest().createdAt().isAfter(session.issuedAt())) {
				throw new PackageFormatException("Reconciliation assignment is not newer");
			}
			var published = returns.findLatest(assignmentId)
					.orElseThrow(() -> new PackageFormatException("Reconciliation assignment was not submitted"));
			if (!published.packageId().equals(uuid(mapping, "return_package_id"))
					|| !published.assignmentId().equals(assignmentId)
					|| !published.baselineSnapshotId().equals(session.baselineSnapshotId())
					|| !published.contributorId().equals(contributorId)
					|| !published.masterInstallationId().equals(session.masterInstallationId())
					|| !published.originInstallationId().equals(installationId)
					|| !published.archiveSha256().equals(mapping.path("archive_sha256").asText())) {
				throw new PackageFormatException("Reconciliation differs from published return evidence");
			}
			EntityType type = EntityType.valueOf(mapping.path("record_type").asText());
			UUID originalId = uuid(mapping, "contributor_record_id");
			UUID canonicalId = uuid(mapping, "canonical_record_id");
			UUID proposedRevision = uuid(mapping, "proposed_revision_id");

			// A coordinator-owned baseline record must never
			// be treated as a newly submitted contributor record.
			Set<UUID> baselineIds = baselineIds(sessions, assignmentId, type);
			if (baselineIds.contains(originalId)) {
				throw new PackageFormatException("Reconciliation attempts to replace a baseline identity");
			}
			var original = identities.findByPortableId(type, originalId);
			if (original.isEmpty()) {

				// A historical mapping may refer to work that
				// has already been reconciled in an earlier cycle.
				// This mapping grants no further mutation authority.
				continue;
			}
			if (!original.orElseThrow().revisionId().equals(proposedRevision)) {
				throw new PackageFormatException("Submitted contributor revision has changed");
			}
			if (!originalId.equals(canonicalId) && identities.findByPortableId(type, canonicalId).isPresent()) {
				throw new PackageFormatException("Canonical reconciliation identity already exists");
			}
			boolean presentInIncoming = incoming.records().stream().anyMatch(
					record -> record.recordType().equals(type.name()) && record.recordId().equals(canonicalId));
			if (!presentInIncoming) {
				throw new PackageFormatException("Accepted record is absent from the new baseline");
			}
			accepted.add(new ExchangeRecord(ExchangeRecord.CURRENT_RECORD_VERSION, "ASSIGNMENT_RECONCILIATION_EVIDENCE",
					uuid(entry, "evidence_id"), null, null, mapping));
		}
		return List.copyOf(accepted);
	}

	private Set<UUID> baselineIds(SqliteContributorAssignmentSessionRepository sessions, UUID assignmentId,
			EntityType type) throws SQLException, IOException {
		byte[] baseline = sessions.readBaseline(assignmentId).records();
		List<ExchangeRecord> records = new ExamWorkJsonlCodec().readRecords(new ByteArrayInputStream(baseline));
		Set<UUID> ids = new HashSet<>();
		for (ExchangeRecord record : records) {
			if (record.recordType().equals(type.name())) {
				ids.add(record.recordId());
			}
		}
		return ids;
	}

	private UUID uuid(JsonNode node, String field) throws PackageFormatException {
		JsonNode value = node.path(field);
		if (!value.isTextual()) {
			throw new PackageFormatException("Missing reconciliation UUID: " + field);
		}
		try {
			UUID id = UUID.fromString(value.asText());
			if (!id.toString().equals(value.asText())) {
				throw new IllegalArgumentException("Noncanonical UUID");
			}
			return id;
		} catch (IllegalArgumentException failure) {
			throw new PackageFormatException("Invalid reconciliation UUID: " + field, failure);
		}
	}
}
