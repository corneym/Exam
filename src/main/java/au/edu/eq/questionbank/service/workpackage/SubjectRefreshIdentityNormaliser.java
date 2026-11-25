package au.edu.eq.questionbank.service.workpackage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import au.edu.eq.questionbank.service.workpackage.SubjectRefreshChangeClassifier.RecordKey;

/**
 * Normalises originating Subject records into a common portable identity
 * namespace for refresh comparison.
 *
 * <p>
 * Only explicitly recognised entity identities and relationship fields are
 * translated. Revision identities, ordinary text, ordered child collections and
 * other domain values are preserved.
 *
 * <p>
 * This class does not approve aliases, create database identities or perform
 * persistence operations.
 */
public final class SubjectRefreshIdentityNormaliser {

	private static final Map<String, String> REFERENCE_TYPES = Map.ofEntries(Map.entry("subject_id", "SUBJECT"),
			Map.entry("provider_id", "EXAM_PROVIDER"), Map.entry("syllabus_version_id", "SYLLABUS_VERSION"),
			Map.entry("parent_id", "CURRICULUM_NODE"), Map.entry("classification_node_id", "CURRICULUM_NODE"),
			Map.entry("source_node_id", "CURRICULUM_NODE"), Map.entry("target_node_id", "CURRICULUM_NODE"),
			Map.entry("current_curriculum_node_id", "CURRICULUM_NODE"),
			Map.entry("target_syllabus_version_id", "SYLLABUS_VERSION"),
			Map.entry("source_document_id", "SOURCE_DOCUMENT"), Map.entry("exam_id", "EXAM"),
			Map.entry("answer_file_id", "ANSWER_FILE"), Map.entry("booklet_id", "EXAM_BOOKLET"),
			Map.entry("source_question_id", "SOURCE_QUESTION"),
			Map.entry("shared_context_id", "SHARED_QUESTION_CONTEXT"),
			Map.entry("pending_mcq_shared_context_id", "SHARED_QUESTION_CONTEXT"), Map.entry("question_id", "QUESTION"),
			Map.entry("answer_id", "ANSWER"), Map.entry("image_id", "QUESTION_IMAGE"));

	/**
	 * Creates a stateless portable identity normaliser.
	 */
	public SubjectRefreshIdentityNormaliser() {
	}

	/**
	 * Normalises record identities and recognised portable relationships.
	 *
	 * <p>
	 * Every record requires an explicit target identity. Every non-null recognised
	 * relationship must resolve through the same mapping. Newly introduced records
	 * may use explicitly supplied provisional identities until the persistence
	 * layer allocates canonical identities.
	 *
	 * <p>
	 * The original records and their JSON values remain unchanged.
	 *
	 * @param records    originating Subject records
	 * @param identities originating-to-target portable identity mapping
	 * @return immutable, deterministically ordered normalised records
	 * @throws PackageFormatException if identities are missing, ambiguous or
	 *                                malformed
	 * @throws NullPointerException   if either argument is null
	 */
	public List<ExchangeRecord> normalise(List<ExchangeRecord> records, Map<RecordKey, UUID> identities)
			throws PackageFormatException {
		Objects.requireNonNull(records, "records");
		Objects.requireNonNull(identities, "identities");
		Map<RecordKey, UUID> mappings = Map.copyOf(identities);
		Set<RecordKey> sourceKeys = new HashSet<>();
		Set<RecordKey> targetKeys = new HashSet<>();
		Set<UUID> targetIds = new HashSet<>();
		List<ExchangeRecord> normalised = new ArrayList<>();
		for (ExchangeRecord record : records) {
			RecordKey sourceKey = new RecordKey(record.recordType(), record.recordId());
			if (!sourceKeys.add(sourceKey)) {
				throw new PackageFormatException("Duplicate originating record: " + sourceKey);
			}
			UUID targetId = requireIdentity(mappings, sourceKey);
			RecordKey targetKey = new RecordKey(record.recordType(), targetId);

			// Two originating records must not collapse into
			// one destination identity during normalisation.
			if (!targetKeys.add(targetKey) || !targetIds.add(targetId)) {
				throw new PackageFormatException(
						"Multiple originating records resolve " + "to the same target identity: " + targetId);
			}
			JsonNode values = normaliseValues(record.values(), mappings);
			normalised.add(new ExchangeRecord(record.recordVersion(), record.recordType(), targetId,
					record.revisionId(), record.baselineRevisionId(), values));
		}

		// Validate closure after all identities have been translated.
		for (ExchangeRecord record : normalised) {
			requireReferenceClosure(record.values(), targetKeys);
		}
		normalised.sort(Comparator.comparing(ExchangeRecord::recordType).thenComparing(ExchangeRecord::recordId));
		return List.copyOf(normalised);
	}

	/**
	 * Normalises portable asset owners and reserved embedded image paths.
	 *
	 * <p>
	 * Ordinary managed filesystem paths, content digests, sizes, media types and
	 * usage values remain unchanged. Embedded image paths are translated into the
	 * canonical owner identity namespace.
	 *
	 * @param assets     originating asset inventory
	 * @param identities originating-to-target portable identity mapping
	 * @return normalised immutable asset inventory
	 * @throws PackageFormatException if an owner or embedded path is invalid
	 * @throws NullPointerException   if either argument is null
	 */
	public ExamWorkAssetIndex normaliseAssets(ExamWorkAssetIndex assets,
			Map<SubjectRefreshChangeClassifier.RecordKey, UUID> identities) throws PackageFormatException {
		Objects.requireNonNull(assets, "assets");
		Objects.requireNonNull(identities, "identities");
		Map<SubjectRefreshChangeClassifier.RecordKey, UUID> mappings = Map.copyOf(identities);
		List<ExamWorkAssetIndex.Reference> references = new ArrayList<>();
		for (ExamWorkAssetIndex.Reference reference : assets.references()) {
			UUID targetOwner = null;
			String managedPath = reference.managedPath();
			if (reference.ownerId() != null) {
				targetOwner = requireIdentity(mappings,
						new SubjectRefreshChangeClassifier.RecordKey(reference.ownerType(), reference.ownerId()));
				if (managedPath.contains("/exams/_examwork_embedded/")) {
					String sourceOwner = reference.ownerId().toString();
					if (!managedPath.contains(sourceOwner)) {
						throw new PackageFormatException("Embedded asset path does not contain "
								+ "its originating owner identity: " + managedPath);
					}

					// Reserved embedded paths are identity-derived.
					// Ordinary managed filenames must not be rewritten.
					managedPath = managedPath.replace(sourceOwner, targetOwner.toString());
				}
			}
			references
					.add(new ExamWorkAssetIndex.Reference(reference.archivePath(), reference.sha256(), reference.size(),
							reference.mediaType(), managedPath, reference.ownerType(), targetOwner, reference.usage()));
		}
		try {
			return new ExamWorkAssetIndex(references);
		} catch (IllegalArgumentException failure) {
			throw new PackageFormatException("Normalised asset inventory is inconsistent", failure);
		}
	}

	private JsonNode normaliseValues(JsonNode node, Map<RecordKey, UUID> mappings) throws PackageFormatException {
		if (node.isObject()) {
			ObjectNode result = JsonNodeFactory.instance.objectNode();
			for (var field : node.properties()) {
				String name = field.getKey();
				JsonNode value = field.getValue();
				String referenceType = REFERENCE_TYPES.get(name);
				if (referenceType != null && !value.isNull()) {
					UUID sourceId = requireUuid(name, value);
					UUID targetId = requireIdentity(mappings, new RecordKey(referenceType, sourceId));
					result.put(name, targetId.toString());
				} else {
					result.set(name, normaliseValues(value, mappings));
				}
			}
			return result;
		}
		if (node.isArray()) {
			ArrayNode result = JsonNodeFactory.instance.arrayNode();

			// Child collection ordering is authoritative.
			for (JsonNode child : node) {
				result.add(normaliseValues(child, mappings));
			}
			return result;
		}
		return node.deepCopy();
	}

	private UUID requireIdentity(Map<RecordKey, UUID> mappings, RecordKey sourceKey) throws PackageFormatException {
		UUID target = mappings.get(sourceKey);
		if (target == null) {
			throw new PackageFormatException(
					"No target identity for " + sourceKey.recordType() + " " + sourceKey.recordId());
		}
		return target;
	}

	private void requireReferenceClosure(JsonNode node, Set<RecordKey> targetKeys) throws PackageFormatException {
		if (node.isObject()) {
			for (var field : node.properties()) {
				String referenceType = REFERENCE_TYPES.get(field.getKey());
				JsonNode value = field.getValue();
				if (referenceType != null && !value.isNull()) {
					RecordKey reference = new RecordKey(referenceType, requireUuid(field.getKey(), value));
					if (!targetKeys.contains(reference)) {
						throw new PackageFormatException(
								"Normalised relationship points " + "outside the Subject graph: " + reference);
					}
				} else {
					requireReferenceClosure(value, targetKeys);
				}
			}
		} else if (node.isArray()) {
			for (JsonNode child : node) {
				requireReferenceClosure(child, targetKeys);
			}
		}
	}

	private UUID requireUuid(String field, JsonNode value) throws PackageFormatException {
		if (!value.isTextual()) {
			throw new PackageFormatException("Portable relationship " + field + " must contain a UUID string");
		}
		String text = value.asText();
		try {
			UUID id = UUID.fromString(text);
			if (!id.toString().equals(text)) {
				throw new IllegalArgumentException("Noncanonical UUID");
			}
			return id;
		} catch (IllegalArgumentException failure) {
			throw new PackageFormatException("Invalid portable relationship: " + field, failure);
		}
	}
}
