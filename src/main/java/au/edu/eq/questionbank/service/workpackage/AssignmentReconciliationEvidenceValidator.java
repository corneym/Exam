package au.edu.eq.questionbank.service.workpackage;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Validates historical reconciliation evidence carried within a whole-Exam
 * assignment context.
 *
 * <p>
 * Structural validation does not authorise modification of contributor records.
 * Local assignment and publication evidence must also be verified before
 * applying any mapping.
 */
final class AssignmentReconciliationEvidenceValidator {

	private static final Set<String> SUPPORTED_TYPES = Set.of("SOURCE_QUESTION", "SHARED_QUESTION_CONTEXT", "QUESTION",
			"ANSWER", "QUESTION_IMAGE");
	private static final List<String> UUID_FIELDS = List.of("assignment_id", "return_package_id",
			"baseline_snapshot_id", "master_installation_id", "contributor_id", "origin_installation_id", "review_id",
			"contributor_record_id", "canonical_record_id", "proposed_revision_id", "resulting_revision_id");

	void validate(ExamWorkManifest manifest, ExchangeRecord context) throws PackageFormatException {

		JsonNode values = context.values();
		JsonNode evidence = values.get("reconciliation_evidence");

		if (evidence == null) {
			return;
		}

		if (!evidence.isArray()) {
			throw new PackageFormatException("Invalid assignment reconciliation evidence collection");
		}

		UUID contributorId = requiredUuid(values, "contributor_id");

		Set<UUID> evidenceIds = new HashSet<>();
		Set<String> mappingKeys = new HashSet<>();
		Set<String> canonicalKeys = new HashSet<>();

		for (JsonNode entry : evidence) {

			if (!entry.isObject()) {
				throw new PackageFormatException("Invalid assignment reconciliation evidence entry");
			}

			UUID evidenceId = requiredUuid(entry, "evidence_id");

			if (!evidenceIds.add(evidenceId)) {
				throw new PackageFormatException("Duplicate assignment reconciliation evidence");
			}

			JsonNode mapping = entry.path("mapping");

			validateMapping(manifest, contributorId, evidenceId, mapping, mappingKeys, canonicalKeys);
		}
	}

	private UUID requiredUuid(JsonNode node, String field) throws PackageFormatException {
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

	private void validateMapping(ExamWorkManifest manifest, UUID contributorId, UUID evidenceId, JsonNode mapping,
			Set<String> mappingKeys, Set<String> canonicalKeys) throws PackageFormatException {
		if (!mapping.isObject()) {
			throw new PackageFormatException("Missing assignment reconciliation mapping");
		}
		for (String field : UUID_FIELDS) {
			requiredUuid(mapping, field);
		}
		UUID previousAssignmentId = requiredUuid(mapping, "assignment_id");
		if (previousAssignmentId.equals(manifest.assignmentId())) {
			throw new PackageFormatException("Reconciliation evidence refers to the new assignment");
		}
		if (requiredUuid(mapping, "baseline_snapshot_id").equals(manifest.baselineSnapshotId())) {
			throw new PackageFormatException("Reconciliation evidence refers to the new baseline");
		}
		if (!manifest.originInstallationId().equals(requiredUuid(mapping, "master_installation_id"))) {
			throw new PackageFormatException("Reconciliation evidence belongs to another coordinator");
		}
		if (!contributorId.equals(requiredUuid(mapping, "contributor_id"))) {
			throw new PackageFormatException("Reconciliation evidence belongs to another contributor");
		}
		String type = mapping.path("record_type").asText();
		if (!SUPPORTED_TYPES.contains(type)) {
			throw new PackageFormatException("Unsupported reconciliation record type: " + type);
		}
		String digest = mapping.path("archive_sha256").asText();
		if (!digest.matches("[0-9a-f]{64}")) {
			throw new PackageFormatException("Invalid reconciliation contribution digest");
		}
		String reviewId = mapping.path("review_id").asText();
		String contributorRecordId = mapping.path("contributor_record_id").asText();
		String canonicalRecordId = mapping.path("canonical_record_id").asText();

		// Reconstruct the deterministic identity used by the
		// coordinator evidence reader.
		String identity = "assignment-reconciliation-v1:" + reviewId + ":" + type + ":" + contributorRecordId;
		UUID expectedEvidenceId = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
		if (!expectedEvidenceId.equals(evidenceId)) {
			throw new PackageFormatException("Reconciliation evidence identity mismatch");
		}
		String mappingKey = previousAssignmentId + ":" + type + ":" + contributorRecordId;
		if (!mappingKeys.add(mappingKey)) {
			throw new PackageFormatException("Duplicate historical contributor record mapping");
		}
		String canonicalKey = previousAssignmentId + ":" + type + ":" + canonicalRecordId;
		if (!canonicalKeys.add(canonicalKey)) {
			throw new PackageFormatException("Duplicate historical canonical record mapping");
		}
	}
}
