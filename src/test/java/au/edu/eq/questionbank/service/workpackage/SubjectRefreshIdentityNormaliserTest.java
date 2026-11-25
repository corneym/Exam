package au.edu.eq.questionbank.service.workpackage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import au.edu.eq.questionbank.service.workpackage.ExamWorkAssetIndex.Reference;
import au.edu.eq.questionbank.service.workpackage.SubjectRefreshChangeClassifier.ChangeKind;
import au.edu.eq.questionbank.service.workpackage.SubjectRefreshChangeClassifier.RecordKey;

class SubjectRefreshIdentityNormaliserTest {

	private final SubjectRefreshIdentityNormaliser normaliser = new SubjectRefreshIdentityNormaliser();

	@Test
	void normalisationPreventsFalseAdditionsAndRemovals() throws Exception {
		UUID originatingId = UUID.randomUUID();
		UUID canonicalId = UUID.randomUUID();
		UUID revision = UUID.randomUUID();
		ExchangeRecord incoming = new ExchangeRecord(ExchangeRecord.CURRENT_RECORD_VERSION, "QUESTION", originatingId,
				revision, null, object().put("marks", 3));
		ExchangeRecord current = new ExchangeRecord(ExchangeRecord.CURRENT_RECORD_VERSION, "QUESTION", canonicalId,
				revision, null, object().put("marks", 2));
		var normalised = normaliser.normalise(List.of(incoming),
				Map.of(new RecordKey("QUESTION", originatingId), canonicalId));
		var changes = new SubjectRefreshChangeClassifier().compareRecords(List.of(current), normalised);
		assertEquals(1, changes.size());
		assertEquals(ChangeKind.UPDATE, changes.getFirst().kind());
		assertEquals(canonicalId, changes.getFirst().key().recordId());
	}

	@Test
	void normalisesAssetOwnersWithoutChangingContent() throws Exception {
		UUID originalOwner = UUID.randomUUID();
		UUID canonicalOwner = UUID.randomUUID();
		String digest = "a".repeat(64);
		String archivePath = "assets/sha256/aa/" + digest + ".pdf";
		Reference owned = new Reference(archivePath, digest, 120L, "application/pdf",
				"subjects/Biology/exams/paper.pdf", "SOURCE_DOCUMENT", originalOwner, "QUESTION_PDF");
		Reference unowned = new Reference(archivePath, digest, 120L, "application/pdf", "subjects/Biology/retained.pdf",
				null, null, "INVENTORIED_FILE");
		ExamWorkAssetIndex original = new ExamWorkAssetIndex(List.of(owned, unowned));
		var normalised = normaliser.normaliseAssets(original,
				Map.of(new RecordKey("SOURCE_DOCUMENT", originalOwner), canonicalOwner));
		Reference translated = normalised.references().stream().filter(reference -> reference.ownerId() != null)
				.findFirst().orElseThrow();
		assertEquals(canonicalOwner, translated.ownerId());
		assertEquals(owned.managedPath(), translated.managedPath());
		assertEquals(owned.archivePath(), translated.archivePath());
		assertEquals(owned.sha256(), translated.sha256());
		assertEquals(owned.size(), translated.size());
		assertEquals(owned.mediaType(), translated.mediaType());
		assertEquals(owned.usage(), translated.usage());
		assertTrue(normalised.references().contains(unowned));
		assertTrue(original.references().contains(owned));
	}

	@Test
	void normalisesCurriculumHierarchyAndMappingEndpoints() throws Exception {
		UUID subject = UUID.randomUUID();
		UUID version = UUID.randomUUID();
		UUID unit = UUID.randomUUID();
		UUID descriptor = UUID.randomUUID();
		UUID target = UUID.randomUUID();
		UUID mapping = UUID.randomUUID();
		UUID canonicalSubject = UUID.randomUUID();
		UUID canonicalVersion = UUID.randomUUID();
		UUID canonicalUnit = UUID.randomUUID();
		UUID canonicalDescriptor = UUID.randomUUID();
		UUID canonicalTarget = UUID.randomUUID();
		UUID canonicalMapping = UUID.randomUUID();
		ObjectNode subjectValues = object().put("subject_name", "Biology");
		ObjectNode versionValues = object().put("subject_id", subject.toString());
		ObjectNode unitValues = object().put("syllabus_version_id", version.toString()).putNull("parent_id");
		ObjectNode descriptorValues = object().put("syllabus_version_id", version.toString()).put("parent_id",
				unit.toString());
		ObjectNode targetValues = object().put("syllabus_version_id", version.toString()).put("parent_id",
				unit.toString());
		ObjectNode mappingValues = object().put("source_node_id", descriptor.toString()).put("target_node_id",
				target.toString());
		var records = List.of(record("SUBJECT", subject, subjectValues),
				record("SYLLABUS_VERSION", version, versionValues), record("CURRICULUM_NODE", unit, unitValues),
				record("CURRICULUM_NODE", descriptor, descriptorValues),
				record("CURRICULUM_NODE", target, targetValues), record("CURRICULUM_MAPPING", mapping, mappingValues));
		var identities = Map.of(new RecordKey("SUBJECT", subject), canonicalSubject,
				new RecordKey("SYLLABUS_VERSION", version), canonicalVersion, new RecordKey("CURRICULUM_NODE", unit),
				canonicalUnit, new RecordKey("CURRICULUM_NODE", descriptor), canonicalDescriptor,
				new RecordKey("CURRICULUM_NODE", target), canonicalTarget, new RecordKey("CURRICULUM_MAPPING", mapping),
				canonicalMapping);
		var normalised = normaliser.normalise(records, identities);
		Map<UUID, ExchangeRecord> byId = normalised.stream()
				.collect(Collectors.toMap(ExchangeRecord::recordId, record -> record));
		assertEquals(canonicalSubject.toString(), byId.get(canonicalVersion).values().path("subject_id").asText());
		assertEquals(canonicalVersion.toString(),
				byId.get(canonicalDescriptor).values().path("syllabus_version_id").asText());
		assertEquals(canonicalUnit.toString(), byId.get(canonicalDescriptor).values().path("parent_id").asText());
		assertTrue(byId.get(canonicalUnit).values().path("parent_id").isNull());
		assertEquals(canonicalDescriptor.toString(),
				byId.get(canonicalMapping).values().path("source_node_id").asText());
		assertEquals(canonicalTarget.toString(), byId.get(canonicalMapping).values().path("target_node_id").asText());
	}

	@Test
	void normalisesRecordIdentitiesAndNestedRelationships() throws Exception {
		UUID originalBooklet = UUID.randomUUID();
		UUID canonicalBooklet = UUID.randomUUID();
		UUID originalQuestion = UUID.randomUUID();
		UUID canonicalQuestion = UUID.randomUUID();
		UUID originalImage = UUID.randomUUID();
		UUID canonicalImage = UUID.randomUUID();
		UUID revision = UUID.randomUUID();
		UUID baseline = UUID.randomUUID();
		ObjectNode bookletValues = object();
		bookletValues.put("booklet_name", "Paper 1");
		ObjectNode questionValues = object();
		questionValues.put("booklet_id", originalBooklet.toString());
		questionValues.put("question_text", originalBooklet.toString());
		questionValues.putNull("shared_context_id");
		ArrayNode regions = questionValues.putArray("question_regions");
		regions.addObject().put("booklet_id", originalBooklet.toString()).put("region_order", 0);
		regions.addObject().put("booklet_id", originalBooklet.toString()).put("region_order", 1);
		questionValues.putArray("question_content_parts").addObject().put("content_type", "IMAGE").put("image_id",
				originalImage.toString());
		ObjectNode imageValues = object();
		imageValues.put("question_id", originalQuestion.toString());
		ExchangeRecord booklet = record("EXAM_BOOKLET", originalBooklet, bookletValues);
		ExchangeRecord question = new ExchangeRecord(ExchangeRecord.CURRENT_RECORD_VERSION, "QUESTION",
				originalQuestion, revision, baseline, questionValues);
		ExchangeRecord image = record("QUESTION_IMAGE", originalImage, imageValues);
		Map<RecordKey, UUID> mappings = Map.of(new RecordKey("EXAM_BOOKLET", originalBooklet), canonicalBooklet,
				new RecordKey("QUESTION", originalQuestion), canonicalQuestion,
				new RecordKey("QUESTION_IMAGE", originalImage), canonicalImage);
		var result = normaliser.normalise(List.of(question, image, booklet), mappings);
		assertEquals(3, result.size());
		assertEquals(canonicalBooklet, result.get(0).recordId());
		ExchangeRecord translatedQuestion = result.get(1);
		ExchangeRecord translatedImage = result.get(2);
		assertEquals(canonicalQuestion, translatedQuestion.recordId());
		assertEquals(canonicalImage, translatedImage.recordId());
		JsonNode translated = translatedQuestion.values();
		assertEquals(canonicalBooklet.toString(), translated.path("booklet_id").asText());
		assertEquals(canonicalBooklet.toString(),
				translated.path("question_regions").get(0).path("booklet_id").asText());
		assertEquals(canonicalBooklet.toString(),
				translated.path("question_regions").get(1).path("booklet_id").asText());
		assertEquals(canonicalImage.toString(),
				translated.path("question_content_parts").get(0).path("image_id").asText());
		assertEquals(canonicalQuestion.toString(), translatedImage.values().path("question_id").asText());

		// Ordinary UUID-looking text is not a relationship.
		assertEquals(originalBooklet.toString(), translated.path("question_text").asText());
		assertTrue(translated.path("shared_context_id").isNull());
		assertEquals(revision, translatedQuestion.revisionId());
		assertEquals(baseline, translatedQuestion.baselineRevisionId());
		assertEquals(0, translated.path("question_regions").get(0).path("region_order").asInt());
		assertEquals(1, translated.path("question_regions").get(1).path("region_order").asInt());

		// Original records are not mutated.
		assertEquals(originalQuestion, question.recordId());
		assertEquals(originalBooklet.toString(), question.values().path("booklet_id").asText());
		assertThrows(UnsupportedOperationException.class, result::clear);
	}

	@Test
	void producesIdenticalResultsRegardlessOfRecordOrder() throws Exception {
		UUID firstId = UUID.randomUUID();
		UUID secondId = UUID.randomUUID();
		var first = record("QUESTION", firstId, object().put("marks", 2));
		var second = record("QUESTION", secondId, object().put("marks", 3));
		var mappings = Map.of(new RecordKey("QUESTION", firstId), UUID.randomUUID(),
				new RecordKey("QUESTION", secondId), UUID.randomUUID());
		assertEquals(normaliser.normalise(List.of(first, second), mappings),
				normaliser.normalise(List.of(second, first), mappings));
	}

	@Test
	void rejectsAssetWithUnresolvedOwner() {
		String digest = "a".repeat(64);
		Reference reference = new Reference("assets/sha256/aa/" + digest + ".pdf", digest, 120L, "application/pdf",
				"subjects/Biology/exams/paper.pdf", "SOURCE_DOCUMENT", UUID.randomUUID(), "QUESTION_PDF");
		assertThrows(PackageFormatException.class,
				() -> normaliser.normaliseAssets(new ExamWorkAssetIndex(List.of(reference)), Map.of()));
	}

	@Test
	void rejectsCanonicalUuidCollisionAcrossEntityTypes() {
		UUID subjectId = UUID.randomUUID();
		UUID questionId = UUID.randomUUID();
		UUID canonicalId = UUID.randomUUID();
		var subject = record("SUBJECT", subjectId, object().put("subject_name", "Biology"));
		var question = record("QUESTION", questionId, object().put("marks", 2));
		assertThrows(PackageFormatException.class,
				() -> normaliser.normalise(List.of(subject, question), Map.of(new RecordKey("SUBJECT", subjectId),
						canonicalId, new RecordKey("QUESTION", questionId), canonicalId)));
	}

	@Test
	void rejectsMalformedRelationshipUuid() {
		UUID id = UUID.randomUUID();
		var question = record("QUESTION", id, object().put("booklet_id", "not-a-uuid"));
		assertThrows(PackageFormatException.class, () -> normaliser.normalise(List.of(question),
				Map.of(new RecordKey("QUESTION", id), UUID.randomUUID())));
	}

	@Test
	void rejectsMissingRecordIdentity() {
		ExchangeRecord record = record("SUBJECT", UUID.randomUUID(), object().put("subject_name", "Biology"));
		assertThrows(PackageFormatException.class, () -> normaliser.normalise(List.of(record), Map.of()));
	}

	@Test
	void rejectsMissingRelationshipIdentity() {
		UUID questionId = UUID.randomUUID();
		ExchangeRecord record = record("QUESTION", questionId,
				object().put("booklet_id", UUID.randomUUID().toString()));
		assertThrows(PackageFormatException.class, () -> normaliser.normalise(List.of(record),
				Map.of(new RecordKey("QUESTION", questionId), UUID.randomUUID())));
	}

	@Test
	void rejectsNumericRelationshipIdentity() {
		UUID id = UUID.randomUUID();
		var question = record("QUESTION", id, object().put("booklet_id", 12));
		assertThrows(PackageFormatException.class, () -> normaliser.normalise(List.of(question),
				Map.of(new RecordKey("QUESTION", id), UUID.randomUUID())));
	}

	@Test
	void rejectsRelationshipOutsideNormalisedGraph() {
		UUID questionId = UUID.randomUUID();
		UUID bookletId = UUID.randomUUID();
		ExchangeRecord question = record("QUESTION", questionId, object().put("booklet_id", bookletId.toString()));
		var mappings = Map.of(new RecordKey("QUESTION", questionId), UUID.randomUUID(),
				new RecordKey("EXAM_BOOKLET", bookletId), UUID.randomUUID());

		// A mapping alone does not establish that its
		// target record belongs to the represented graph.
		assertThrows(PackageFormatException.class, () -> normaliser.normalise(List.of(question), mappings));
	}

	@Test
	void rejectsTwoOriginatingRecordsMappingToOneIdentity() {
		UUID firstId = UUID.randomUUID();
		UUID secondId = UUID.randomUUID();
		UUID canonicalId = UUID.randomUUID();
		var first = record("QUESTION", firstId, object().put("marks", 2));
		var second = record("QUESTION", secondId, object().put("marks", 3));
		assertThrows(PackageFormatException.class,
				() -> normaliser.normalise(List.of(first, second), Map.of(new RecordKey("QUESTION", firstId),
						canonicalId, new RecordKey("QUESTION", secondId), canonicalId)));
	}

	private ObjectNode object() {
		return JsonNodeFactory.instance.objectNode();
	}

	private ExchangeRecord record(String type, UUID id, ObjectNode values) {
		return new ExchangeRecord(ExchangeRecord.CURRENT_RECORD_VERSION, type, id, UUID.randomUUID(), null, values);
	}
}
