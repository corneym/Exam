package au.edu.eq.questionbank.service.workpackage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import au.edu.eq.questionbank.service.workpackage.EqworkCurriculumReconciler.Classification;
import au.edu.eq.questionbank.service.workpackage.EqworkCurriculumReconciler.Preview;
import au.edu.eq.questionbank.service.workpackage.EqworkCurriculumReconciler.Proposal;

/**
 * Tests dependency-aware, read-only reconciliation of independently identified
 * capture records, including question regions and AnswerFile provenance.
 */
class EqworkCaptureReconcilerTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private final EqworkCaptureReconciler reconciler = new EqworkCaptureReconciler();

	/** Shared Context and Answer region changes are independent conflicts. */
	@Test
	void changedAnswerRegionAndSharedContextRemainConflicts() {
		Fixture master = fixture("master");
		Fixture incoming = fixture("incoming");
		ObjectNode context = (ObjectNode) incoming.context().values();
		((ObjectNode) context.path("shared_question_context_regions").get(0)).put("page_number", 2);
		incoming = incoming.replacing(incoming.context(),
				record("SHARED_QUESTION_CONTEXT", "incoming:context", context));
		ObjectNode answer = (ObjectNode) incoming.answer().values();
		((ObjectNode) answer.path("answer_regions").get(0)).put("page_number", 8);
		incoming = incoming.replacing(incoming.answer(), record("ANSWER", "incoming:answer", answer));

		Preview result = preview(master, incoming);

		assertEquals(Classification.CONFLICT, proposal(result, incoming.context()).classification());
		assertEquals(Classification.CONFLICT, proposal(result, incoming.question()).classification());
		assertEquals(Classification.CONFLICT, proposal(result, incoming.answer()).classification());
	}

	/** A conflicting source PDF blocks downstream capture equivalence. */
	@Test
	void changedBookletPdfBlocksIdenticalQuestionAndDependentAnswer() {
		Fixture master = fixture("master");
		Fixture incoming = fixture("incoming");
		Preview result = reconciler.preview(master.records(), incoming.records(), curriculum(master, incoming),
				assessment(master, incoming, Classification.CONFLICT));

		assertEquals(Classification.CONFLICT, proposal(result, incoming.question()).classification());
		assertEquals(Classification.CONFLICT, proposal(result, incoming.answer()).classification());
		assertTrue(proposal(result, incoming.question()).reason().contains("PDF provenance"));
	}

	/** Matching text does not override an unmapped curriculum classification. */
	@Test
	void changedQuestionClassificationRemainsConflictEvenWhenTextMatches() {
		Fixture master = fixture("master");
		Fixture incoming = fixture("incoming");
		ObjectNode changed = (ObjectNode) incoming.question().values();
		changed.put("classification_node_id", id("unmapped-node").toString());
		incoming = incoming.replacing(incoming.question(), record("QUESTION", "incoming:question", changed));

		Preview result = preview(master, incoming);

		assertEquals(Classification.CONFLICT, proposal(result, incoming.question()).classification());
		assertTrue(proposal(result, incoming.question()).reason().contains("Unresolved portable dependency"));
		assertEquals(Classification.CONFLICT, proposal(result, incoming.answer()).classification());
	}

	/** Changed PDF-region coordinates must remain reviewable. */
	@Test
	void changedQuestionRegionCoordinatesCauseConflict() {
		Fixture master = fixture("master");
		Fixture incoming = fixture("incoming");
		ObjectNode changed = (ObjectNode) incoming.question().values();
		((ObjectNode) changed.path("question_regions").get(0)).put("x", 0.75);
		incoming = incoming.replacing(incoming.question(), record("QUESTION", "incoming:question", changed));

		Preview result = preview(master, incoming);

		assertEquals(Classification.CONFLICT, proposal(result, incoming.question()).classification());
		assertEquals(Classification.CONFLICT, proposal(result, incoming.answer()).classification());
	}

	/** Duplicate Context labels never establish a unique portable alias. */
	@Test
	void duplicateSharedContextLabelsAreNotMatchedAutomatically() {
		Fixture master = fixture("master");
		Fixture incoming = fixture("incoming");
		ObjectNode second = (ObjectNode) incoming.context().values();
		EqworkRecord duplicate = record("SHARED_QUESTION_CONTEXT", "incoming:other-context", second);
		incoming = incoming.with(duplicate);

		Preview result = preview(master, incoming);

		assertEquals(Classification.CONFLICT, proposal(result, incoming.context()).classification());
		assertEquals(Classification.CONFLICT, proposal(result, duplicate).classification());
	}

	/** Unreviewed embedded images cannot be silently deduplicated. */
	@Test
	void imageBearingQuestionIsReviewableUntilImageReferencesAreReconciled() {
		Fixture master = fixture("master");
		Fixture incoming = fixture("incoming");
		ObjectNode changed = (ObjectNode) incoming.question().values();
		ArrayNode images = changed.putArray("question_images");
		images.addObject().put("image_png", "a".repeat(64));
		incoming = incoming.replacing(incoming.question(), record("QUESTION", "incoming:question", changed));

		Preview result = preview(master, incoming);

		assertEquals(Classification.CONFLICT, proposal(result, incoming.question()).classification());
		assertTrue(proposal(result, incoming.question()).reason().contains("image ownership"));
	}

	/**
	 * Independent UUIDs with equivalent region provenance permit unapproved
	 * aliases.
	 */
	@Test
	void independentIdsWithEquivalentRegionsAndDependenciesAreIdentical() {
		Fixture master = fixture("master");
		Fixture incoming = fixture("incoming");
		Preview result = preview(master, incoming);

		assertEquals(4, result.proposals().size());
		assertTrue(result.proposals().stream().allMatch(p -> p.classification() == Classification.IDENTICAL));
		assertTrue(result.proposals().stream().allMatch(Proposal::proposesAlias));
		assertEquals(master.question().recordId(), proposal(result, incoming.question()).masterId());
		assertEquals(master.question().recordId(), proposal(result, incoming.answer()).masterParentId());
		assertEquals(master.context().recordId(), proposal(result, incoming.context()).masterId());
		assertEquals(incoming.booklet().recordId().toString(),
				incoming.question().values().path("question_regions").get(0).path("booklet_id").asText());
	}

	/** Independent captures are additions without replacing master records. */
	@Test
	void independentlyCapturedQuestionIsCompatibleAdditionWithNewAnswer() {
		Fixture master = fixture("master").withoutCapture();
		Fixture incoming = fixture("incoming");

		Preview result = preview(master, incoming);

		assertEquals(Classification.COMPATIBLE_ADDITION, proposal(result, incoming.question()).classification());
		assertEquals(Classification.NEW, proposal(result, incoming.answer()).classification());
		assertNull(proposal(result, incoming.question()).masterId());
		assertNull(proposal(result, incoming.answer()).masterId());
	}

	/** Missing provenance proposals fail before producing capture aliases. */
	@Test
	void missingParentProposalFailsClosed() {
		Fixture master = fixture("master");
		Fixture incoming = fixture("incoming");

		assertThrows(IllegalArgumentException.class, () -> reconciler.preview(master.records(), incoming.records(),
				curriculum(master, incoming), new Preview(List.of())));
	}

	/** Creates the preceding Exam/Booklet and AnswerFile review proposals. */
	private Preview assessment(Fixture master, Fixture incoming, Classification bookletStatus) {
		return new Preview(List.of(mapping("EXAM_BOOKLET", incoming.booklet(), master.booklet(), bookletStatus),
				mapping("ANSWER_FILE", incoming.answerFile(), master.answerFile(), Classification.IDENTICAL)));
	}

	/** Creates a preceding classification-node identity mapping. */
	private Preview curriculum(Fixture master, Fixture incoming) {
		return new Preview(List.of(mapping("CURRICULUM_NODE", incoming.classification(), master.classification(),
				Classification.IDENTICAL)));
	}

	/** Constructs independent portable capture identities with matching content. */
	private Fixture fixture(String origin) {
		EqworkRecord booklet = record("EXAM_BOOKLET", origin + ":booklet", mapper.createObjectNode());
		EqworkRecord answerFile = record("ANSWER_FILE", origin + ":answer-file", mapper.createObjectNode());
		EqworkRecord classification = record("CURRICULUM_NODE", origin + ":classification", mapper.createObjectNode());
		ObjectNode source = mapper.createObjectNode();
		source.put("booklet_id", booklet.recordId().toString());
		source.put("source_question_code", "1");
		EqworkRecord sourceQuestion = record("SOURCE_QUESTION", origin + ":source-question", source);
		ObjectNode contextFields = mapper.createObjectNode();
		contextFields.put("booklet_id", booklet.recordId().toString());
		contextFields.put("context_label", "Shared stimulus");
		contextFields.putArray("shared_question_context_regions").addObject().put("page_number", 1).put("x", 0.2);
		EqworkRecord context = record("SHARED_QUESTION_CONTEXT", origin + ":context", contextFields);
		ObjectNode questionFields = mapper.createObjectNode();
		questionFields.put("booklet_id", booklet.recordId().toString());
		questionFields.put("classification_node_id", classification.recordId().toString());
		questionFields.put("source_question_id", sourceQuestion.recordId().toString());
		questionFields.put("shared_context_id", context.recordId().toString());
		questionFields.put("question_code", "1");
		questionFields.put("question_text", "Explain the process");
		questionFields.putArray("question_regions").addObject().put("booklet_id", booklet.recordId().toString())
				.put("page_number", 2).put("x", 0.2);
		EqworkRecord question = record("QUESTION", origin + ":question", questionFields);
		ObjectNode answerFields = mapper.createObjectNode();
		answerFields.put("question_id", question.recordId().toString());
		answerFields.put("answer_text", "Photosynthesis");
		answerFields.putArray("answer_regions").addObject().put("answer_file_id", answerFile.recordId().toString())
				.put("page_number", 4);
		EqworkRecord answer = record("ANSWER", origin + ":answer", answerFields);
		return new Fixture(List.of(booklet, answerFile, classification, sourceQuestion, context, question, answer),
				booklet, answerFile, classification, sourceQuestion, context, question, answer);
	}

	/** Makes reproducible but origin-specific UUIDs for independent records. */
	private UUID id(String text) {
		return UUID.nameUUIDFromBytes(text.getBytes(StandardCharsets.UTF_8));
	}

	/** Constructs a test-only unapproved parent alias proposal. */
	private Proposal mapping(String type, EqworkRecord incoming, EqworkRecord master, Classification classification) {
		return new Proposal(type, incoming.recordId(), master.recordId(), null, null, classification,
				"Previously assessed matching records");
	}

	/** Runs both preceding proposal sets through the capture reconciler. */
	private Preview preview(Fixture master, Fixture incoming) {
		return reconciler.preview(master.records(), incoming.records(), curriculum(master, incoming),
				assessment(master, incoming, Classification.IDENTICAL));
	}

	/** Finds the capture proposal corresponding to an incoming record. */
	private Proposal proposal(Preview preview, EqworkRecord record) {
		return preview.forType(record.recordType()).stream().filter(p -> p.incomingId().equals(record.recordId()))
				.findFirst().orElseThrow();
	}

	/** Constructs a version-one portable record for the test graph. */
	private EqworkRecord record(String type, String identity, ObjectNode values) {
		return new EqworkRecord(EqworkRecord.CURRENT_RECORD_VERSION, type, id(identity), id(identity + ":revision"),
				null, values);
	}

	/** An independent portable capture graph used by the test methods. */
	private record Fixture(List<EqworkRecord> records, EqworkRecord booklet, EqworkRecord answerFile,
			EqworkRecord classification, EqworkRecord sourceQuestion, EqworkRecord context, EqworkRecord question,
			EqworkRecord answer) {

		/** Replaces a fixture record without changing its portable identity. */
		private Fixture replacing(EqworkRecord oldRecord, EqworkRecord replacement) {
			List<EqworkRecord> changed = records.stream()
					.map(record -> record.recordId().equals(oldRecord.recordId()) ? replacement : record).toList();
			return new Fixture(changed, booklet, answerFile, classification,
					sourceQuestion.recordId().equals(oldRecord.recordId()) ? replacement : sourceQuestion,
					context.recordId().equals(oldRecord.recordId()) ? replacement : context,
					question.recordId().equals(oldRecord.recordId()) ? replacement : question,
					answer.recordId().equals(oldRecord.recordId()) ? replacement : answer);
		}

		/** Adds one independent incoming record to a fixture. */
		private Fixture with(EqworkRecord record) {
			List<EqworkRecord> changed = new ArrayList<>(records);
			changed.add(record);
			return new Fixture(List.copyOf(changed), booklet, answerFile, classification, sourceQuestion, context,
					question, answer);
		}

		/** Retains only parent dependencies in the master fixture. */
		private Fixture withoutCapture() {
			return new Fixture(List.of(booklet, answerFile, classification), booklet, answerFile, classification,
					sourceQuestion, context, question, answer);
		}
	}
}
