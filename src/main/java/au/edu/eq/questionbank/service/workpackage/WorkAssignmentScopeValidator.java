package au.edu.eq.questionbank.service.workpackage;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
import au.edu.eq.questionbank.service.workpackage.ExamWorkAssetIndex.Reference;
import au.edu.eq.questionbank.service.workpackage.ExamWorkManifest.ScopeLevel;

/**
 * Enforces the domain scope of a validated whole-Exam assignment.
 *
 * <p>
 * ZIP integrity is verified separately. This class checks that records and
 * logical assets cannot escape the assigned Exam.
 */
public final class WorkAssignmentScopeValidator {

	private static final Map<String, String> REFERENCE_TYPES = Map.ofEntries(Map.entry("subject_id", "SUBJECT"),
			Map.entry("syllabus_version_id", "SYLLABUS_VERSION"), Map.entry("parent_id", "CURRICULUM_NODE"),
			Map.entry("provider_id", "EXAM_PROVIDER"), Map.entry("source_document_id", "SOURCE_DOCUMENT"),
			Map.entry("exam_id", "EXAM"), Map.entry("booklet_id", "EXAM_BOOKLET"),
			Map.entry("classification_node_id", "CURRICULUM_NODE"), Map.entry("source_question_id", "SOURCE_QUESTION"),
			Map.entry("shared_context_id", "SHARED_QUESTION_CONTEXT"),
			Map.entry("pending_mcq_shared_context_id", "SHARED_QUESTION_CONTEXT"),
			Map.entry("answer_file_id", "ANSWER_FILE"), Map.entry("question_id", "QUESTION"),
			Map.entry("answer_id", "ANSWER"), Map.entry("source_node_id", "CURRICULUM_NODE"),
			Map.entry("target_node_id", "CURRICULUM_NODE"), Map.entry("target_syllabus_version_id", "SYLLABUS_VERSION"),
			Map.entry("current_curriculum_node_id", "CURRICULUM_NODE"), Map.entry("image_id", "QUESTION_IMAGE"));

	/**
	 * Creates a validator for whole-Exam assignment scope and portable dependency
	 * integrity.
	 */
	public WorkAssignmentScopeValidator() {
	}

	/**
	 * Validates assignment domain ownership and reference closure.
	 *
	 * <p>
	 * Call only after normal archive integrity validation. The supplied record
	 * collection must contain the initial portable Subject/Exam graph, not the
	 * assignment-context record.
	 *
	 * @param manifest validated assignment manifest
	 * @param records  initial portable domain records
	 * @param context  contributor assignment-context record
	 * @param assets   verified logical asset-reference index
	 * @throws PackageFormatException if any record, relationship or asset escapes
	 *                                the assignment scope
	 */
	public void validate(ExamWorkManifest manifest, List<ExchangeRecord> records, ExchangeRecord context,
			ExamWorkAssetIndex assets) throws PackageFormatException {
		Objects.requireNonNull(manifest, "manifest");
		Objects.requireNonNull(records, "records");
		Objects.requireNonNull(context, "context");
		Objects.requireNonNull(assets, "assets");
		if (manifest.packageType() != ExamWorkPackageType.WORK_ASSIGNMENT
				|| manifest.scope().level() != ScopeLevel.EXAMS || manifest.scope().examIds().size() != 1
				|| !manifest.scope().bookletIds().isEmpty()) {
			throw new PackageFormatException("Assignment must have exactly one whole-Exam scope");
		}
		Map<String, Map<UUID, ExchangeRecord>> index = index(records);
		UUID subjectId = manifest.subjectId();
		UUID examId = manifest.scope().examIds().getFirst();
		ExchangeRecord subject = requireOnly(index, "SUBJECT", subjectId);
		ExchangeRecord exam = requireOnly(index, "EXAM", examId);
		requireLink(exam, "subject_id", subject.recordId());

		// All assessment entities must belong to the one Exam,
		// either directly or through a selected parent record.
		for (ExchangeRecord booklet : recordsOf(index, "EXAM_BOOKLET")) {
			requireLink(booklet, "exam_id", examId);
		}
		for (ExchangeRecord file : recordsOf(index, "ANSWER_FILE")) {
			requireLink(file, "exam_id", examId);
		}
		for (ExchangeRecord question : recordsOf(index, "QUESTION")) {
			requireParent(index, question, "booklet_id", "EXAM_BOOKLET");
		}
		for (ExchangeRecord source : recordsOf(index, "SOURCE_QUESTION")) {
			requireParent(index, source, "booklet_id", "EXAM_BOOKLET");
		}
		for (ExchangeRecord shared : recordsOf(index, "SHARED_QUESTION_CONTEXT")) {
			requireParent(index, shared, "booklet_id", "EXAM_BOOKLET");
		}
		for (ExchangeRecord answer : recordsOf(index, "ANSWER")) {
			requireParent(index, answer, "question_id", "QUESTION");
		}
		for (ExchangeRecord image : recordsOf(index, "QUESTION_IMAGE")) {
			requireParent(index, image, "question_id", "QUESTION");
		}
		for (ExchangeRecord version : recordsOf(index, "SYLLABUS_VERSION")) {
			requireLink(version, "subject_id", subjectId);
		}

		// This also catches relationships nested inside Question
		// regions, ordered content parts and Shared Context records.
		for (ExchangeRecord record : records) {
			requireReferenceClosure(record.values(), index);
		}
		requireSources(index);
		requireSyllabus(manifest, context, index);
		requireAssets(index, assets);
	}

	/**
	 * Checks a proposed contribution graph against the issued whole-Exam scope.
	 *
	 * <p>
	 * The caller must first verify the exact original baseline against master
	 * evidence and reconstruct the complete graph from that baseline and the
	 * independently validated contribution candidates. This method reuses the same
	 * portable reference and logical asset checks as assignment issuance.
	 *
	 * @param manifest validated contribution manifest
	 * @param proposed complete original graph with proposed changes overlaid
	 * @param assets   contribution archive's verified asset index
	 * @throws PackageFormatException if an entity, reference or asset escapes the
	 *                                assigned Exam or a dependency is missing
	 */
	void validateContribution(ExamWorkManifest manifest, List<ExchangeRecord> proposed, ExamWorkAssetIndex assets)
			throws PackageFormatException {
		Objects.requireNonNull(manifest, "manifest");
		Objects.requireNonNull(proposed, "proposed");
		Objects.requireNonNull(assets, "assets");
		if (manifest.packageType() != ExamWorkPackageType.CONTRIBUTION || manifest.scope().level() != ScopeLevel.EXAMS
				|| manifest.scope().examIds().size() != 1 || !manifest.scope().bookletIds().isEmpty()) {
			throw new PackageFormatException("Contribution requires one whole-Exam scope");
		}
		Map<String, Map<UUID, ExchangeRecord>> index = index(proposed);
		UUID subjectId = manifest.subjectId();
		UUID examId = manifest.scope().examIds().getFirst();
		ExchangeRecord subject = requireOnly(index, "SUBJECT", subjectId);
		ExchangeRecord exam = requireOnly(index, "EXAM", examId);
		requireLink(exam, "subject_id", subject.recordId());
		for (ExchangeRecord booklet : recordsOf(index, "EXAM_BOOKLET")) {
			requireLink(booklet, "exam_id", examId);
		}
		for (ExchangeRecord file : recordsOf(index, "ANSWER_FILE")) {
			requireLink(file, "exam_id", examId);
		}
		for (ExchangeRecord question : recordsOf(index, "QUESTION")) {
			requireParent(index, question, "booklet_id", "EXAM_BOOKLET");
		}
		for (ExchangeRecord source : recordsOf(index, "SOURCE_QUESTION")) {
			requireParent(index, source, "booklet_id", "EXAM_BOOKLET");
		}
		for (ExchangeRecord shared : recordsOf(index, "SHARED_QUESTION_CONTEXT")) {
			requireParent(index, shared, "booklet_id", "EXAM_BOOKLET");
		}
		for (ExchangeRecord answer : recordsOf(index, "ANSWER")) {
			requireParent(index, answer, "question_id", "QUESTION");
		}
		for (ExchangeRecord image : recordsOf(index, "QUESTION_IMAGE")) {
			requireParent(index, image, "question_id", "QUESTION");
		}
		for (ExchangeRecord syllabus : recordsOf(index, "SYLLABUS_VERSION")) {
			requireLink(syllabus, "subject_id", subjectId);
		}

		// Nested relationships are checked too, including Question source and
		// Shared Context links, content parts, classifications and image IDs.
		for (ExchangeRecord record : proposed) {
			requireReferenceClosure(record.values(), index);
		}
		requireSources(index);
		requireAssets(index, assets);
	}

	private Map<String, Map<UUID, ExchangeRecord>> index(List<ExchangeRecord> records) throws PackageFormatException {
		Map<String, Map<UUID, ExchangeRecord>> indexed = new HashMap<>();
		for (ExchangeRecord record : records) {
			try {
				EntityType.valueOf(record.recordType());
			} catch (IllegalArgumentException exception) {
				throw new PackageFormatException("Unsupported assignment entity type: " + record.recordType(),
						exception);
			}
			Map<UUID, ExchangeRecord> group = indexed.computeIfAbsent(record.recordType(), _ -> new HashMap<>());
			if (group.putIfAbsent(record.recordId(), record) != null) {
				throw new PackageFormatException(
						"Duplicate assignment record: " + record.recordType() + ":" + record.recordId());
			}
		}
		return indexed;
	}

	private UUID parseId(JsonNode value, String description) throws PackageFormatException {
		if (!value.isTextual()) {
			throw new PackageFormatException("Missing portable UUID: " + description);
		}
		try {
			UUID id = UUID.fromString(value.asText());
			if (!id.toString().equals(value.asText())) {
				throw new IllegalArgumentException("Noncanonical UUID");
			}
			return id;
		} catch (IllegalArgumentException exception) {
			throw new PackageFormatException("Invalid portable UUID: " + description, exception);
		}
	}

	private Collection<ExchangeRecord> recordsOf(Map<String, Map<UUID, ExchangeRecord>> index, String type) {
		return index.getOrDefault(type, Map.of()).values();
	}

	private void requireAssets(Map<String, Map<UUID, ExchangeRecord>> index, ExamWorkAssetIndex assets)
			throws PackageFormatException {
		Map<UUID, Integer> sourceCounts = new HashMap<>();
		Map<UUID, Integer> syllabusCounts = new HashMap<>();
		Map<UUID, Integer> questionImageCounts = new HashMap<>();
		Map<UUID, Set<Integer>> contextImageOrders = new HashMap<>();
		for (Reference reference : assets.references()) {
			if (reference.ownerType() == null || reference.ownerId() == null
					|| reference.usage().equals("INVENTORIED")) {
				throw new PackageFormatException("Assignment contains unowned Subject inventory");
			}
			ExchangeRecord owner = index.getOrDefault(reference.ownerType(), Map.of()).get(reference.ownerId());
			if (owner == null) {
				throw new PackageFormatException("Asset belongs to an out-of-scope record: " + reference.ownerType()
						+ ":" + reference.ownerId());
			}
			String expectedType = switch (reference.usage()) {
			case "SOURCE_PDF" -> "SOURCE_DOCUMENT";
			case "SYLLABUS_PDF" -> "SYLLABUS_VERSION";
			case "EMBEDDED_QUESTION_PNG" -> "QUESTION_IMAGE";
			case "EMBEDDED_CONTEXT_PNG" -> "SHARED_QUESTION_CONTEXT";
			default -> throw new PackageFormatException("Unsupported assignment asset usage: " + reference.usage());
			};
			if (!expectedType.equals(reference.ownerType())) {
				throw new PackageFormatException("Asset usage and owner type disagree");
			}
			boolean pdf = reference.usage().endsWith("_PDF");
			String expectedMediaType = pdf ? "application/pdf" : "image/png";
			if (!expectedMediaType.equals(reference.mediaType())) {
				throw new PackageFormatException("Assignment asset has incorrect media type");
			}
			switch (reference.usage()) {
			case "SOURCE_PDF" -> {

				// The managed path is held in the asset index,
				// not the portable SourceDocument record.
				JsonNode storedHash = owner.values().path("content_sha256");
				if (storedHash.isTextual() && !storedHash.asText().equals(reference.sha256())) {
					throw new PackageFormatException("Source document hash disagrees with asset");
				}
				sourceCounts.merge(owner.recordId(), 1, Integer::sum);
			}
			case "SYLLABUS_PDF" -> {
				syllabusCounts.merge(owner.recordId(), 1, Integer::sum);
			}
			case "EMBEDDED_QUESTION_PNG" -> {
				String expectedHash = owner.values().path("image_png").asText();
				if (!reference.sha256().equals(expectedHash)) {
					throw new PackageFormatException("Question image bytes disagree with record");
				}
				questionImageCounts.merge(owner.recordId(), 1, Integer::sum);
			}
			case "EMBEDDED_CONTEXT_PNG" -> {
				JsonNode images = owner.values().path("shared_question_context_images");
				if (!images.isArray()) {
					throw new PackageFormatException("Missing Shared Context image collection");
				}
				boolean matched = false;
				for (JsonNode image : images) {
					int order = image.path("image_order").asInt(-1);
					String hash = image.path("image_png").asText();
					String expectedSuffix = "/" + owner.recordId() + "-" + order + ".png";
					if (order >= 0 && reference.managedPath().endsWith(expectedSuffix)
							&& reference.sha256().equals(hash)) {
						boolean added = contextImageOrders.computeIfAbsent(owner.recordId(), _ -> new HashSet<>())
								.add(order);
						if (!added) {
							throw new PackageFormatException("Duplicate Shared Context image order");
						}
						matched = true;
						break;
					}
				}
				if (!matched) {
					throw new PackageFormatException("Shared Context image asset is not in its record");
				}
			}
			default -> throw new PackageFormatException("Unexpected assignment asset usage");
			}
		}

		// Every selected SourceDocument must have exactly one
		// logical PDF reference, even when Booklets share its bytes.
		for (ExchangeRecord document : recordsOf(index, "SOURCE_DOCUMENT")) {
			if (sourceCounts.getOrDefault(document.recordId(), 0) != 1) {
				throw new PackageFormatException("Missing or duplicate source PDF: " + document.recordId());
			}
		}

		// Syllabus PDFs are optional in the portable record model,
		// but no syllabus may claim multiple source PDFs.
		for (ExchangeRecord syllabus : recordsOf(index, "SYLLABUS_VERSION")) {
			if (syllabusCounts.getOrDefault(syllabus.recordId(), 0) > 1) {
				throw new PackageFormatException("Duplicate syllabus PDF: " + syllabus.recordId());
			}
		}
		for (ExchangeRecord image : recordsOf(index, "QUESTION_IMAGE")) {
			if (questionImageCounts.getOrDefault(image.recordId(), 0) != 1) {
				throw new PackageFormatException("Missing or duplicate Question image asset: " + image.recordId());
			}
		}
		for (ExchangeRecord context : recordsOf(index, "SHARED_QUESTION_CONTEXT")) {
			JsonNode images = context.values().path("shared_question_context_images");
			if (!images.isArray()) {
				throw new PackageFormatException("Invalid Shared Context image collection");
			}
			Set<Integer> included = contextImageOrders.getOrDefault(context.recordId(), Set.of());
			if (included.size() != images.size()) {
				throw new PackageFormatException("Missing Shared Context image assets: " + context.recordId());
			}
		}
	}

	private void requireContextValue(ExchangeRecord context, String field, UUID expected)
			throws PackageFormatException {
		UUID actual = parseId(context.values().path(field), "assignment context " + field);
		if (!expected.equals(actual)) {
			throw new PackageFormatException("Assignment context identity mismatch: " + field);
		}
	}

	private void requireLink(ExchangeRecord record, String field, UUID expected) throws PackageFormatException {
		UUID actual = parseId(record.values().path(field), record.recordType() + "." + field);
		if (!expected.equals(actual)) {
			throw new PackageFormatException(record.recordType() + " escapes assignment scope");
		}
	}

	private ExchangeRecord requireOnly(Map<String, Map<UUID, ExchangeRecord>> index, String type, UUID expectedId)
			throws PackageFormatException {
		Map<UUID, ExchangeRecord> group = index.getOrDefault(type, Map.of());
		if (group.size() != 1 || !group.containsKey(expectedId)) {
			throw new PackageFormatException("Assignment requires exactly one " + type + ": " + expectedId);
		}
		return group.get(expectedId);
	}

	private void requireParent(Map<String, Map<UUID, ExchangeRecord>> index, ExchangeRecord child, String field,
			String parentType) throws PackageFormatException {
		UUID parentId = parseId(child.values().path(field), child.recordType() + "." + field);
		if (!index.getOrDefault(parentType, Map.of()).containsKey(parentId)) {
			throw new PackageFormatException(child.recordType() + " references an out-of-scope " + parentType);
		}
	}

	private void requireReferenceClosure(JsonNode node, Map<String, Map<UUID, ExchangeRecord>> index)
			throws PackageFormatException {
		if (node.isArray()) {
			for (JsonNode child : node) {
				requireReferenceClosure(child, index);
			}
			return;
		}
		if (!node.isObject()) {
			return;
		}
		for (Map.Entry<String, JsonNode> field : node.properties()) {
			String type = REFERENCE_TYPES.get(field.getKey());
			JsonNode value = field.getValue();
			if (type != null && !value.isNull()) {
				UUID id = parseId(value, type + " reference " + field.getKey());
				if (!index.getOrDefault(type, Map.of()).containsKey(id)) {
					throw new PackageFormatException("Missing assignment dependency: " + type + ":" + id);
				}
			}
			requireReferenceClosure(value, index);
		}
	}

	private void requireSources(Map<String, Map<UUID, ExchangeRecord>> index) throws PackageFormatException {
		Set<UUID> referenced = new HashSet<>();
		for (ExchangeRecord booklet : recordsOf(index, "EXAM_BOOKLET")) {
			referenced.add(parseId(booklet.values().path("source_document_id"), "Booklet source document"));
		}
		for (ExchangeRecord file : recordsOf(index, "ANSWER_FILE")) {
			referenced.add(parseId(file.values().path("source_document_id"), "AnswerFile source document"));
		}
		Set<UUID> included = index.getOrDefault("SOURCE_DOCUMENT", Map.of()).keySet();
		if (!referenced.equals(included)) {
			throw new PackageFormatException("Assignment contains missing or unrelated source documents");
		}
	}

	private void requireSyllabus(ExamWorkManifest manifest, ExchangeRecord context,
			Map<String, Map<UUID, ExchangeRecord>> index) throws PackageFormatException {
		if (!context.recordType().equals("WORK_ASSIGNMENT_CONTEXT")
				|| !context.recordId().equals(manifest.assignmentId())
				|| !Objects.equals(context.revisionId(), manifest.baselineSnapshotId())) {
			throw new PackageFormatException("Invalid assignment context identity");
		}
		requireContextValue(context, "assignment_id", manifest.assignmentId());
		requireContextValue(context, "baseline_snapshot_id", manifest.baselineSnapshotId());
		requireContextValue(context, "master_installation_id", manifest.originInstallationId());
		requireContextValue(context, "subject_id", manifest.subjectId());
		requireContextValue(context, "exam_id", manifest.scope().examIds().getFirst());
		if (!context.values().path("issued_at").asText().equals(manifest.createdAt().toString())) {
			throw new PackageFormatException("Assignment issuance time mismatch");
		}
		UUID currentId = parseId(context.values().path("current_syllabus_id"), "Current syllabus");
		Map<UUID, ExchangeRecord> versions = index.getOrDefault("SYLLABUS_VERSION", Map.of());
		long currentCount = versions.values().stream()
				.filter(version -> version.values().path("is_current").asInt() == 1).count();
		ExchangeRecord current = versions.get(currentId);
		if (currentCount != 1 || current == null || current.values().path("is_current").asInt() != 1) {
			throw new PackageFormatException("Assignment lacks a unique current syllabus");
		}
		JsonNode legacyFlag = context.values().path("legacy_capture_enabled");
		if (!legacyFlag.isBoolean()) {
			throw new PackageFormatException("Assignment has invalid legacy-capture flag");
		}
		if (legacyFlag.asBoolean()) {
			UUID legacyId = parseId(context.values().path("legacy_syllabus_id"), "Original 2019 syllabus");
			ExchangeRecord legacy = versions.get(legacyId);
			if (legacy == null) {
				throw new PackageFormatException("Original 2019 syllabus is missing");
			}
			String name = legacy.values().path("syllabus_name").asText();
			if (!(name.equals("2019") || name.endsWith(" 2019"))) {
				throw new PackageFormatException("Legacy capture must use original 2019 syllabus");
			}
		} else if (context.values().hasNonNull("legacy_syllabus_id")) {
			throw new PackageFormatException("Legacy syllabus is not permitted by this assignment");
		}
	}
}
