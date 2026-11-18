package au.edu.eq.questionbank.service.workpackage;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumStatus;
import au.edu.eq.questionbank.model.MappingStatus;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.SharedQuestionContextRegion;

/**
 * Checks domain relationships in a complete portable graph before publication.
 * Package checksums establish integrity, not the validity of the represented
 * domain objects. Both import protocols use this read-only boundary.
 */
final class PortableDomainRecordValidator {

	private final Map<String, Map<UUID, ExchangeRecord>> records = new HashMap<>();

	private PortableDomainRecordValidator(List<ExchangeRecord> graph) throws PackageFormatException {
		for (ExchangeRecord record : graph) {
			if (records.computeIfAbsent(record.recordType(), _ -> new HashMap<>()).putIfAbsent(record.recordId(),
					record) != null) {
				throw invalid(record, "duplicate portable identity");
			}
		}
	}

	/**
	 * Validates a complete Subject graph, without requiring the current syllabus
	 * for historical Questions.
	 */
	static void validate(UUID subjectId, List<ExchangeRecord> graph) throws PackageFormatException {
		var validator = new PortableDomainRecordValidator(graph);
		for (ExchangeRecord record : graph) {
			try {
				validator.validateRecord(subjectId, record);
			} catch (IllegalArgumentException | DateTimeException exception) {
				throw new PackageFormatException(
						"Invalid " + record.recordType() + " " + record.recordId() + ": " + exception.getMessage(),
						exception);
			}
		}
	}

	private void validateRecord(UUID subjectId, ExchangeRecord record) throws PackageFormatException {
		JsonNode values = record.values();
		validateMetadata(record);
		switch (record.recordType()) {
		case "SUBJECT" -> {
			require(record, record.recordId().equals(subjectId), "Subject differs from package Subject");
			text(values, "subject_name");
		}
		case "SYLLABUS_VERSION", "EXAM" -> {
			ExchangeRecord subject = reference(values, "subject_id", "SUBJECT");
			require(record, subject.recordId().equals(subjectId), "subject_id differs from package Subject");
		}
		case "CURRICULUM_NODE" -> validateCurriculumNode(record);
		case "CURRICULUM_MAPPING" -> validateMapping(record);
		case "EXAM_BOOKLET" -> validateBooklet(record);
		case "SOURCE_QUESTION" -> {
			reference(values, "booklet_id", "EXAM_BOOKLET");
			text(values, "source_question_code");
		}
		case "SHARED_QUESTION_CONTEXT" -> validateContext(record);
		case "QUESTION" -> validateQuestion(record);
		case "ANSWER" -> validateAnswer(record);
		default -> {
		}
		}
	}

	private void validateMetadata(ExchangeRecord record) throws PackageFormatException {
		JsonNode values = record.values();
		String nameField = switch (record.recordType()) {
		case "SYLLABUS_VERSION" -> "syllabus_name";
		case "EXAM_PROVIDER" -> "provider_name";
		case "EXAM" -> "exam_name";
		case "EXAM_BOOKLET" -> "booklet_name";
		case "ANSWER_FILE" -> "answer_file_name";
		default -> null;
		};
		if (nameField != null) {
			text(values, nameField);
		}
		if (record.recordType().equals("SYLLABUS_VERSION")) {
			CurriculumStatus status = CurriculumStatus.valueOf(text(values, "curriculum_status"));
			boolean finalised = values.hasNonNull("curriculum_finalised_at");
			if (finalised) {
				Instant.parse(text(values, "curriculum_finalised_at"));
			}
			require(record, (status == CurriculumStatus.FINAL) == finalised,
					"curriculum_status and curriculum_finalised_at must agree");
		}
		if (record.recordType().equals("EXAM")) {
			require(record, integer(values, "exam_year") > 0, "exam_year must be positive");
			reference(values, "provider_id", "EXAM_PROVIDER");
		}
		if (record.recordType().equals("EXAM_BOOKLET") || record.recordType().equals("ANSWER_FILE")) {
			reference(values, "source_document_id", "SOURCE_DOCUMENT");
			reference(values, "exam_id", "EXAM");
		}
		if (record.recordType().equals("SOURCE_DOCUMENT") && values.hasNonNull("content_sha256")) {
			require(record, text(values, "content_sha256").matches("[0-9a-f]{64}"), "invalid content_sha256");
		}
	}

	private void validateCurriculumNode(ExchangeRecord record) throws PackageFormatException {
		JsonNode values = record.values();
		ExchangeRecord syllabus = reference(values, "syllabus_version_id", "SYLLABUS_VERSION");
		CurriculumLevel level = level(record);
		text(values, "curriculum_code");
		text(values, "curriculum_name");
		require(record, integer(values, "display_order") >= 0, "display_order must not be negative");
		if (level == CurriculumLevel.UNIT) {
			require(record, !values.hasNonNull("parent_id"), "UNIT must not have a parent");
			return;
		}
		ExchangeRecord parent = reference(values, "parent_id", "CURRICULUM_NODE");
		require(record, reference(parent.values(), "syllabus_version_id", "SYLLABUS_VERSION").equals(syllabus),
				"parent must belong to the same syllabus version");
		CurriculumLevel parentLevel = level(parent);
		boolean valid = switch (level) {
		case TOPIC -> parentLevel == CurriculumLevel.UNIT;
		case SUBTOPIC -> parentLevel == CurriculumLevel.TOPIC;
		case DESCRIPTOR -> parentLevel == CurriculumLevel.TOPIC || parentLevel == CurriculumLevel.SUBTOPIC;
		case UNIT -> false;
		};
		require(record, valid, level + " has invalid parent level " + parentLevel);
	}

	private void validateMapping(ExchangeRecord record) throws PackageFormatException {
		ExchangeRecord source = reference(record.values(), "source_node_id", "CURRICULUM_NODE");
		ExchangeRecord target = reference(record.values(), "target_node_id", "CURRICULUM_NODE");
		ExchangeRecord sourceVersion = reference(source.values(), "syllabus_version_id", "SYLLABUS_VERSION");
		ExchangeRecord targetVersion = reference(target.values(), "syllabus_version_id", "SYLLABUS_VERSION");
		require(record, !sourceVersion.equals(targetVersion), "mapping endpoints must use different syllabuses");
		require(record, sourceVersion.values().path("subject_id").equals(targetVersion.values().path("subject_id")),
				"mapping endpoints must belong to the same Subject");
		require(record, level(source) == level(target), "mapping endpoints must have the same curriculum level");
		MappingStatus.valueOf(text(record.values(), "mapping_status"));
	}

	private void validateBooklet(ExchangeRecord record) throws PackageFormatException {
		ExchangeRecord exam = reference(record.values(), "exam_id", "EXAM");
		if (record.values().hasNonNull("answer_file_id")) {
			ExchangeRecord file = reference(record.values(), "answer_file_id", "ANSWER_FILE");
			require(record, reference(file.values(), "exam_id", "EXAM").equals(exam),
					"answer_file_id must belong to the booklet's Exam");
		}
		if (record.values().hasNonNull("pending_mcq_shared_context_id")) {
			ExchangeRecord context = reference(record.values(), "pending_mcq_shared_context_id",
					"SHARED_QUESTION_CONTEXT");
			require(record, reference(context.values(), "booklet_id", "EXAM_BOOKLET").equals(record),
					"pending_mcq_shared_context_id must belong to the same Booklet");
		}
	}

	private void validateQuestion(ExchangeRecord record) throws PackageFormatException {
		JsonNode values = record.values();
		ExchangeRecord booklet = reference(values, "booklet_id", "EXAM_BOOKLET");
		ExchangeRecord exam = reference(booklet.values(), "exam_id", "EXAM");
		ExchangeRecord classification = reference(values, "classification_node_id", "CURRICULUM_NODE");
		CurriculumLevel level = level(classification);
		require(record, level == CurriculumLevel.SUBTOPIC || level == CurriculumLevel.DESCRIPTOR,
				"classification_node_id must reference a SUBTOPIC or DESCRIPTOR, found " + level);
		ExchangeRecord syllabus = reference(classification.values(), "syllabus_version_id", "SYLLABUS_VERSION");
		require(record, syllabus.values().path("subject_id").equals(exam.values().path("subject_id")),
				"classification syllabus must belong to the Question's Subject");
		text(values, "question_code");
		require(record, values.path("question_text").isTextual(), "question_text must be text");
		int marks = integer(values, "marks");
		require(record, marks > 0, "marks must be positive");
		QuestionResponseType response = QuestionResponseType.valueOf(text(values, "response_type"));
		require(record, response != QuestionResponseType.MULTIPLE_CHOICE || marks == 1,
				"Multiple-choice questions must be worth exactly 1 mark");
		for (String field : List.of("source_question_id", "shared_context_id")) {
			if (values.hasNonNull(field)) {
				String type = field.equals("source_question_id") ? "SOURCE_QUESTION" : "SHARED_QUESTION_CONTEXT";
				ExchangeRecord related = reference(values, field, type);
				require(record, reference(related.values(), "booklet_id", "EXAM_BOOKLET").equals(booklet),
						field + " must belong to the Question's Booklet");
			}
		}
		for (JsonNode region : array(values, "question_regions")) {
			require(record, reference(region, "booklet_id", "EXAM_BOOKLET").equals(booklet),
					"question_regions must belong to the Question's Booklet");
			validateRegion(region);
		}
		validateParts(record, "question", false);
	}

	private void validateContext(ExchangeRecord record) throws PackageFormatException {
		reference(record.values(), "booklet_id", "EXAM_BOOKLET");
		text(record.values(), "context_label");
		for (JsonNode region : array(record.values(), "shared_question_context_regions")) {
			validateRegion(region);
		}
		validateParts(record, "shared_question_context", true);
	}

	private void validateParts(ExchangeRecord record, String prefix, boolean context) throws PackageFormatException {
		JsonNode values = record.values();
		JsonNode regions = array(values, prefix + "_regions");
		JsonNode parts = array(values, prefix + "_content_parts");
		JsonNode images = array(values, prefix + "_images");
		require(record, !context || !parts.isEmpty(), "Shared Context must contain content");
		Set<Integer> regionOrders = orders(record, regions, "region_order");
		Set<Integer> imageOrders = context ? orders(record, images, "image_order") : Set.of();
		orders(record, parts, "content_order");
		for (JsonNode part : parts) {
			switch (text(part, "content_type")) {
			case "PDF_REGION" -> {
				int order = integer(part, "region_order");
				require(record, regionOrders.contains(order), "content part has missing region_order " + order);
			}
			case "IMAGE" -> {
				if (context) {
					require(record, imageOrders.contains(integer(part, "image_order")),
							"content part has missing image_order");
				} else {
					ExchangeRecord image = reference(part, "image_id", "QUESTION_IMAGE");
					require(record, reference(image.values(), "question_id", "QUESTION").equals(record),
							"content image must belong to the same Question");
				}
			}
			default -> throw invalid(record, "unsupported content_type");
			}
		}
	}

	private Set<Integer> orders(ExchangeRecord record, JsonNode rows, String field) throws PackageFormatException {
		Set<Integer> orders = new HashSet<>();
		for (JsonNode row : rows) {
			int order = integer(row, field);
			require(record, order >= 0 && orders.add(order), "invalid or duplicate " + field);
		}
		return orders;
	}

	private void validateAnswer(ExchangeRecord record) throws PackageFormatException {
		ExchangeRecord question = reference(record.values(), "question_id", "QUESTION");
		ExchangeRecord booklet = reference(question.values(), "booklet_id", "EXAM_BOOKLET");
		ExchangeRecord exam = reference(booklet.values(), "exam_id", "EXAM");
		JsonNode regions = array(record.values(), "answer_regions");
		JsonNode text = record.values().path("answer_text");
		require(record, (text.isTextual() && !text.asText().isBlank()) || !regions.isEmpty(),
				"Answer must contain text or regions");
		orders(record, regions, "region_order");
		for (JsonNode region : regions) {
			ExchangeRecord file = reference(region, "answer_file_id", "ANSWER_FILE");
			require(record, reference(file.values(), "exam_id", "EXAM").equals(exam),
					"Answer region file must belong to the Question's Exam");
			validateRegion(region);
		}
	}

	private void validateRegion(JsonNode region) {

		// All PDF-region domain types share these proportional bounds and one-based
		// pages.
		new SharedQuestionContextRegion(integer(region, "page_number"), number(region, "x"), number(region, "y"),
				number(region, "width"), number(region, "height"));
	}

	private ExchangeRecord reference(JsonNode values, String field, String type) throws PackageFormatException {
		UUID id = UUID.fromString(text(values, field));
		ExchangeRecord result = records.getOrDefault(type, Map.of()).get(id);
		if (result == null) {
			throw new PackageFormatException("Missing semantic dependency " + type + " " + id + " for " + field);
		}
		return result;
	}

	private CurriculumLevel level(ExchangeRecord record) {
		return CurriculumLevel.valueOf(text(record.values(), "curriculum_level"));
	}

	private String text(JsonNode values, String field) {
		JsonNode value = values.path(field);
		if (!value.isTextual() || value.asText().isBlank()) {
			throw new IllegalArgumentException(field + " must be non-blank text");
		}
		return value.asText();
	}

	private int integer(JsonNode values, String field) {
		JsonNode value = values.path(field);
		if (!value.isIntegralNumber() || !value.canConvertToInt()) {
			throw new IllegalArgumentException(field + " must be an integer");
		}
		return value.intValue();
	}

	private double number(JsonNode values, String field) {
		JsonNode value = values.path(field);
		if (!value.isNumber()) {
			throw new IllegalArgumentException(field + " must be numeric");
		}
		return value.doubleValue();
	}

	private JsonNode array(JsonNode values, String field) {
		JsonNode value = values.path(field);
		if (!value.isArray()) {
			throw new IllegalArgumentException(field + " must be an array");
		}
		return value;
	}

	private void require(ExchangeRecord record, boolean valid, String message) throws PackageFormatException {
		if (!valid) {
			throw invalid(record, message);
		}
	}

	private PackageFormatException invalid(ExchangeRecord record, String message) {
		return new PackageFormatException("Invalid " + record.recordType() + " " + record.recordId() + ": " + message);
	}
}
