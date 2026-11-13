package au.edu.eq.questionbank.service.workpackage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import au.edu.eq.questionbank.service.workpackage.CurriculumReconciler.Classification;
import au.edu.eq.questionbank.service.workpackage.CurriculumReconciler.Preview;
import au.edu.eq.questionbank.service.workpackage.CurriculumReconciler.Proposal;

/**
 * Contract tests for baseline-free, read-only curriculum reconciliation.
 * Distinct, stable portable IDs represent independently initialised datasets;
 * numeric SQLite identifiers are never involved in deciding equivalence.
 */
class CurriculumReconcilerTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private final CurriculumReconciler reconciler = new CurriculumReconciler();

	@Test
	void additionToEstablishedSyllabusIsConflictNotAutomaticCompatibleAddition() {
		List<ExchangeRecord> master = biology("master");
		List<ExchangeRecord> incoming = new ArrayList<>(biology("contributor"));
		ExchangeRecord extra = node("contributor", "extra", incoming.get(1).recordId(), incoming.get(2).recordId(),
				"B19T2", "Another topic");
		incoming.add(extra);
		Preview preview = reconciler.preview(master, incoming);
		assertEquals(Classification.CONFLICT, preview.forType("SYLLABUS_VERSION").getFirst().classification());
		assertEquals(Classification.CONFLICT, proposal(preview, extra).classification());
		assertNull(proposal(preview, extra).masterId());
	}

	@Test
	void conflictingNodeMetadataBlocksIdenticalSyllabusClassification() {
		List<ExchangeRecord> master = biology("master");
		List<ExchangeRecord> incoming = new ArrayList<>(biology("contributor"));
		incoming.set(3, node("contributor", "topic", incoming.get(1).recordId(), incoming.get(2).recordId(), "B19T1",
				"Different topic title"));
		Preview preview = reconciler.preview(master, incoming);
		assertEquals(Classification.CONFLICT, preview.forType("SYLLABUS_VERSION").getFirst().classification());
		assertEquals(Classification.CONFLICT, proposal(preview, incoming.get(3)).classification());
		assertTrue(proposal(preview, incoming.get(3)).reason().contains("metadata differs"));
	}

	@Test
	void differentSubjectIsNewAndNeverMatchedByCollidingRecordPositions() {
		List<ExchangeRecord> master = biology("master");
		List<ExchangeRecord> incoming = new ArrayList<>(biology("contributor"));
		incoming.set(0, subject("contributor", "Chemistry"));
		incoming.set(1, version("contributor", "version", incoming.get(0).recordId(), "Biology 2019"));
		Preview preview = reconciler.preview(master, incoming);
		assertEquals(Classification.NEW, preview.forType("SUBJECT").getFirst().classification());
		assertEquals(Classification.NEW, preview.forType("SYLLABUS_VERSION").getFirst().classification());
		assertTrue(preview.proposals().stream().noneMatch(Proposal::proposesAlias));
	}

	@Test
	void differentSubjectNameCasingDoesNotCreateAnAlias() {
		List<ExchangeRecord> master = biology("master");
		List<ExchangeRecord> incoming = new ArrayList<>(biology("contributor"));
		incoming.set(0, subject("contributor", "BIOLOGY"));
		Preview preview = reconciler.preview(master, incoming);
		Proposal subject = preview.forType("SUBJECT").getFirst();
		assertEquals(Classification.NEW, subject.classification());
		assertNull(subject.masterId());
		assertFalse(subject.proposesAlias());
	}

	@Test
	void duplicateSyllabusNameIsAmbiguousAndProposesNoAlias() {
		List<ExchangeRecord> master = biology("master");
		List<ExchangeRecord> incoming = new ArrayList<>(biology("contributor"));
		ExchangeRecord duplicate = version("contributor", "duplicate", incoming.get(0).recordId(), "Biology 2019");
		incoming.add(duplicate);
		Preview preview = reconciler.preview(master, incoming);
		assertEquals(2, preview.forType("SYLLABUS_VERSION").size());
		assertTrue(preview.forType("SYLLABUS_VERSION").stream().allMatch(
				proposal -> proposal.classification() == Classification.CONFLICT && proposal.masterId() == null));
	}

	@Test
	void equivalentMappingReviewsWithIndependentSyllabusIdsAreIdentical() {
		List<ExchangeRecord> master = new ArrayList<>(biology("master"));
		List<ExchangeRecord> incoming = new ArrayList<>(biology("contributor"));
		addMappingReview(master, 2);
		addMappingReview(incoming, 2);
		String originalIncomingTarget = incoming.get(2).values().path("curriculum_mapping_reviews").get(0)
				.path("target_syllabus_version_id").asText();
		Preview preview = reconciler.preview(master, incoming);
		assertEquals(Classification.IDENTICAL, preview.forType("SYLLABUS_VERSION").getFirst().classification());
		assertEquals(Classification.IDENTICAL, proposal(preview, incoming.get(2)).classification());

		// Rebasing is comparison-only.
		assertEquals(originalIncomingTarget, incoming.get(2).values().path("curriculum_mapping_reviews").get(0)
				.path("target_syllabus_version_id").asText());
		assertFalse(master.get(1).recordId().equals(incoming.get(1).recordId()));
	}

	@Test
	void matchesIndependentSubjectAndCompleteHierarchyWithoutMatchingUuids() {
		List<ExchangeRecord> master = biology("master");
		List<ExchangeRecord> incoming = biology("contributor");
		Preview preview = reconciler.preview(master, incoming);
		assertEquals(4, preview.proposals().size());
		assertTrue(preview.proposals().stream().allMatch(p -> p.classification() == Classification.IDENTICAL));
		assertTrue(preview.proposals().stream().allMatch(Proposal::proposesAlias));
		assertEquals(master.get(0).recordId(), preview.forType("SUBJECT").getFirst().masterId());
		assertEquals(master.get(1).recordId(), preview.forType("SYLLABUS_VERSION").getFirst().masterId());
		Proposal nested = proposal(preview, incoming.get(3));
		assertEquals(incoming.get(2).recordId(), nested.incomingParentId());
		assertEquals(master.get(2).recordId(), nested.masterParentId());

		// Preview must not rewrite either dataset or its portable references.
		assertEquals(incoming.get(2).recordId().toString(), incoming.get(3).values().path("parent_id").asText());
	}

	@Test
	void missingMasterCurriculumNodeIsReportedOnSyllabus() {
		List<ExchangeRecord> master = biology("master");
		List<ExchangeRecord> incoming = biology("contributor").subList(0, 3);
		Preview preview = reconciler.preview(master, incoming);
		Proposal syllabus = preview.forType("SYLLABUS_VERSION").getFirst();
		assertEquals(Classification.CONFLICT, syllabus.classification());
		assertTrue(syllabus.reason().contains("master-only"));
	}

	@Test
	void rejectsInvalidParentGraphsAndDuplicatePortableIdentities() {
		List<ExchangeRecord> incoming = new ArrayList<>(biology("contributor"));
		incoming.add(incoming.get(3));
		assertThrows(IllegalArgumentException.class, () -> reconciler.preview(List.of(), incoming));
		List<ExchangeRecord> broken = new ArrayList<>(biology("contributor"));
		broken.set(3, node("contributor", "topic", broken.get(1).recordId(), id("missing"), "B19T1", "Topic 1"));
		assertThrows(IllegalArgumentException.class, () -> reconciler.preview(List.of(), broken));
	}

	@Test
	void separateNewSyllabusIsCompatibleAdditionAndRequiresReview() {
		List<ExchangeRecord> master = biology("master");
		List<ExchangeRecord> incoming = new ArrayList<>(biology("contributor"));
		ExchangeRecord version = version("contributor", "newVersion", incoming.get(0).recordId(), "Biology 2025");
		ExchangeRecord node = node("contributor", "newUnit", version.recordId(), null, "B25U1", "2025 Unit 1");
		incoming.add(version);
		incoming.add(node);
		Preview preview = reconciler.preview(master, incoming);
		assertEquals(Classification.COMPATIBLE_ADDITION, proposal(preview, version).classification());
		assertEquals(Classification.COMPATIBLE_ADDITION, proposal(preview, node).classification());
		assertFalse(proposal(preview, version).proposesAlias());
		assertTrue(proposal(preview, version).reason().contains("approval required"));
	}

	private void addMappingReview(List<ExchangeRecord> records, int nodeIndex) {
		ExchangeRecord original = records.get(nodeIndex);
		ObjectNode values = (ObjectNode) original.values();
		ObjectNode review = values.putArray("curriculum_mapping_reviews").addObject();
		review.put("target_syllabus_version_id", records.get(1).recordId().toString());
		review.put("review_outcome", "MATCHED");
		records.set(nodeIndex, new ExchangeRecord(original.recordVersion(), original.recordType(), original.recordId(),
				original.revisionId(), original.baselineRevisionId(), values));
	}

	private List<ExchangeRecord> biology(String origin) {
		ExchangeRecord subject = subject(origin, "Biology");
		ExchangeRecord version = version(origin, "version", subject.recordId(), "Biology 2019");
		ExchangeRecord unit = node(origin, "unit", version.recordId(), null, "B19U1", "Unit 1");
		ExchangeRecord topic = node(origin, "topic", version.recordId(), unit.recordId(), "B19T1", "Topic 1");
		return List.of(subject, version, unit, topic);
	}

	private UUID id(String label) {
		return UUID.nameUUIDFromBytes(label.getBytes(StandardCharsets.UTF_8));
	}

	private ExchangeRecord node(String origin, String localName, UUID versionId, UUID parentId, String code,
			String title) {
		ObjectNode values = mapper.createObjectNode();
		values.put("syllabus_version_id", versionId.toString());
		if (parentId == null) {
			values.putNull("parent_id");
		} else {
			values.put("parent_id", parentId.toString());
		}
		values.put("curriculum_code", code);
		values.put("curriculum_name", title);
		values.put("curriculum_level", parentId == null ? "UNIT" : "TOPIC");
		values.put("display_order", 0);
		return record("CURRICULUM_NODE", origin + ":" + localName, values);
	}

	private Proposal proposal(Preview preview, ExchangeRecord record) {
		return preview.forType(record.recordType()).stream()
				.filter(proposal -> proposal.incomingId().equals(record.recordId())).findFirst().orElseThrow();
	}

	private ExchangeRecord record(String type, String identity, ObjectNode values) {
		return new ExchangeRecord(ExchangeRecord.CURRENT_RECORD_VERSION, type, id(identity), id(identity + ":revision"),
				null, values);
	}

	private ExchangeRecord subject(String origin, String subjectName) {
		ObjectNode values = mapper.createObjectNode();
		values.put("subject_name", subjectName);
		return record("SUBJECT", origin + ":subject", values);
	}

	private ExchangeRecord version(String origin, String localName, UUID subjectId, String syllabusName) {
		ObjectNode values = mapper.createObjectNode();
		values.put("subject_id", subjectId.toString());
		values.put("syllabus_name", syllabusName);
		values.put("is_current", 0);
		return record("SYLLABUS_VERSION", origin + ":" + localName, values);
	}
}
