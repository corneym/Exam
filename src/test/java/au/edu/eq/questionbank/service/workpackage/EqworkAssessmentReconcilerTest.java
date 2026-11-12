package au.edu.eq.questionbank.service.workpackage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import au.edu.eq.questionbank.service.workpackage.EqworkAssetIndex.Reference;
import au.edu.eq.questionbank.service.workpackage.EqworkCurriculumReconciler.Classification;
import au.edu.eq.questionbank.service.workpackage.EqworkCurriculumReconciler.Preview;
import au.edu.eq.questionbank.service.workpackage.EqworkCurriculumReconciler.Proposal;

/**
 * Verifies read-only matching of independent assessment graphs, PDF provenance,
 * legacy Paper identity and conservative handling of ambiguous records.
 */
class EqworkAssessmentReconcilerTest {

	private static final String QUESTIONS_HASH = "a".repeat(64);
	private static final String ANSWERS_HASH = "b".repeat(64);
	private final ObjectMapper mapper = new ObjectMapper();
	private final EqworkAssessmentReconciler reconciler = new EqworkAssessmentReconciler();
	private final EqworkCurriculumReconciler curriculum = new EqworkCurriculumReconciler();

	@Test
	void changedExamMetadataRemainsReviewableAndBlocksChildAliasAcceptance() {
		Fixture master = fixture("master", 2024, "External Assessment", "Paper 1", "1", QUESTIONS_HASH);
		Fixture incoming = fixture("contributor", 2024, "External Assessment", "Paper 1", "1", QUESTIONS_HASH);
		ObjectNode changed = (ObjectNode) incoming.exam().values();
		changed.put("capture_state", "COMPLETE");
		incoming = incoming.withReplaced(incoming.exam(), record("EXAM", "contributor:exam", changed));
		Preview preview = preview(master, incoming);
		assertEquals(Classification.CONFLICT, proposal(preview, incoming.exam()).classification());
		assertEquals(Classification.CONFLICT, proposal(preview, incoming.booklet()).classification());
	}

	@Test
	void changedSourcePdfIsBlockingConflictForMatchedBookletAndSourceDocument() {
		Fixture master = fixture("master", 2024, "External Assessment", "Paper 1", "1", QUESTIONS_HASH);
		Fixture incoming = fixture("contributor", 2024, "External Assessment", "Paper 1", "1", "c".repeat(64));
		Preview preview = preview(master, incoming);
		assertEquals(Classification.CONFLICT, proposal(preview, incoming.booklet()).classification());
		assertTrue(proposal(preview, incoming.booklet()).reason().contains("different source PDF bytes"));
		assertEquals(Classification.CONFLICT, proposal(preview, incoming.questionDocument()).classification());
		assertEquals(Classification.IDENTICAL, proposal(preview, incoming.answerFile()).classification());
	}

	@Test
	void collidingDisplayNameButDifferentLegacyPaperCannotBeSilentlyAdded() {
		Fixture master = fixture("master", 2024, "External Assessment", "Paper 1", "1", QUESTIONS_HASH);
		Fixture incoming = fixture("contributor", 2024, "External Assessment", "Paper 1", "2", QUESTIONS_HASH);
		Preview preview = preview(master, incoming);
		assertEquals(Classification.CONFLICT, proposal(preview, incoming.booklet()).classification());
		assertFalse(proposal(preview, incoming.booklet()).classification() == Classification.COMPATIBLE_ADDITION);
	}

	/** A changed or detached shared AnswerFile cannot be silently accepted. */
	@Test
	void differingSharedAnswerFileLinksFlagBookletConflict() {
		Fixture master = fixture("master", 2024, "External Assessment", "Paper 1", "1", QUESTIONS_HASH);
		Fixture incoming = fixture("contributor", 2024, "External Assessment", "Paper 1", "1", QUESTIONS_HASH);
		ObjectNode incomingValues = (ObjectNode) incoming.booklet().values();
		incomingValues.put("answer_file_id", incoming.answerFile().recordId().toString());
		incoming = incoming.withReplaced(incoming.booklet(),
				record("EXAM_BOOKLET", "contributor:paper1", incomingValues));
		Preview result = preview(master, incoming);
		assertEquals(Classification.CONFLICT, proposal(result, incoming.booklet()).classification());
	}

	@Test
	void identicalPdfInDifferentExamDoesNotAutomaticallyAliasDocument() {
		Fixture master = fixture("master", 2024, "External Assessment", "Paper 1", "1", QUESTIONS_HASH);
		Fixture incoming = fixture("contributor", 2025, "External Assessment", "Paper 1", "1", QUESTIONS_HASH);
		Preview preview = preview(master, incoming);
		assertEquals(Classification.COMPATIBLE_ADDITION, proposal(preview, incoming.exam()).classification());
		assertEquals(Classification.NEW, proposal(preview, incoming.booklet()).classification());
		Proposal document = proposal(preview, incoming.questionDocument());
		assertEquals(Classification.NEW, document.classification());
		assertNull(document.masterId());
		assertTrue(document.reason().contains("Identical PDF bytes alone")
				|| document.reason().contains("identical PDF bytes alone"));
	}

	@Test
	void independentAdditionalBookletIsCompatibleWithoutOverwritingExistingOne() {
		Fixture master = fixture("master", 2024, "External Assessment", "Paper 1", "1", QUESTIONS_HASH);
		Fixture incoming = fixture("contributor", 2024, "External Assessment", "Paper 1", "1", QUESTIONS_HASH);
		EqworkRecord second = booklet("contributor:paper2", incoming.exam().recordId(),
				incoming.questionDocument().recordId(), "Paper 2", "2");
		incoming = incoming.withAdded(second);
		Preview preview = preview(master, incoming);
		Proposal newBooklet = proposal(preview, second);
		assertEquals(Classification.COMPATIBLE_ADDITION, newBooklet.classification());
		assertNull(newBooklet.masterId());
		assertEquals(master.exam().recordId(), newBooklet.masterParentId());
		assertEquals(Classification.IDENTICAL, proposal(preview, incoming.booklet()).classification());
	}

	@Test
	void independentlyCreatedIdsProduceUnapprovedAliasesWhenEvidenceAgrees() {
		Fixture master = fixture("master", 2024, "External Assessment", "Paper 1", "1", QUESTIONS_HASH);
		Fixture incoming = fixture("contributor", 2024, "External Assessment", "Paper 1", "1", QUESTIONS_HASH);
		Preview preview = preview(master, incoming);
		assertEquals(6, preview.proposals().size());
		assertTrue(preview.proposals().stream().allMatch(p -> p.classification() == Classification.IDENTICAL));
		assertTrue(preview.proposals().stream().allMatch(Proposal::proposesAlias));
		assertEquals(master.exam().recordId(), proposal(preview, incoming.exam()).masterId());
		assertEquals(master.exam().recordId(), proposal(preview, incoming.booklet()).masterParentId());
		assertEquals(master.questionDocument().recordId(), proposal(preview, incoming.questionDocument()).masterId());
		assertEquals(incoming.exam().recordId().toString(), incoming.booklet().values().path("exam_id").asText());
	}

	/**
	 * Two independently created Booklets may share one logically identical
	 * AnswerFile.
	 */
	@Test
	void independentSharedAnswerFileUuidsDoNotCreateFalseBookletConflicts() {
		Fixture master = fixture("master", 2024, "External Assessment", "Paper 1", "1", QUESTIONS_HASH);
		Fixture incoming = fixture("contributor", 2024, "External Assessment", "Paper 1", "1", QUESTIONS_HASH);
		ObjectNode masterValues = (ObjectNode) master.booklet().values();
		masterValues.put("answer_file_id", master.answerFile().recordId().toString());
		master = master.withReplaced(master.booklet(), record("EXAM_BOOKLET", "master:paper1", masterValues));
		ObjectNode incomingValues = (ObjectNode) incoming.booklet().values();
		incomingValues.put("answer_file_id", incoming.answerFile().recordId().toString());
		incoming = incoming.withReplaced(incoming.booklet(),
				record("EXAM_BOOKLET", "contributor:paper1", incomingValues));
		EqworkRecord masterPaper2 = booklet("master:paper2", master.exam().recordId(),
				master.questionDocument().recordId(), "Paper 2", "2");
		ObjectNode masterPaper2Values = (ObjectNode) masterPaper2.values();
		masterPaper2Values.put("answer_file_id", master.answerFile().recordId().toString());
		master = master.withAdded(record("EXAM_BOOKLET", "master:paper2", masterPaper2Values));
		EqworkRecord incomingPaper2 = booklet("contributor:paper2", incoming.exam().recordId(),
				incoming.questionDocument().recordId(), "Paper 2", "2");
		ObjectNode incomingPaper2Values = (ObjectNode) incomingPaper2.values();
		incomingPaper2Values.put("answer_file_id", incoming.answerFile().recordId().toString());
		incoming = incoming.withAdded(record("EXAM_BOOKLET", "contributor:paper2", incomingPaper2Values));
		Preview result = preview(master, incoming);
		assertEquals(2, result.forType("EXAM_BOOKLET").size());
		assertTrue(result.forType("EXAM_BOOKLET").stream()
				.allMatch(proposal -> proposal.classification() == Classification.IDENTICAL));
		assertEquals(Classification.IDENTICAL, proposal(result, incoming.answerFile()).classification());
	}

	/**
	 * Verifies that a legacy Paper code establishes the proposed Booklet identity
	 * even when the editable display name differs.
	 *
	 * <p>
	 * The metadata difference must remain a reviewable conflict.
	 */
	@Test
	void legacyPaperCodeTakesPrecedenceOverEditableBookletName() {
		Fixture master = fixture("master", 2024, "External Assessment", "Paper 1", "1", QUESTIONS_HASH);

		Fixture incoming = fixture("contributor", 2024, "External Assessment", "Renamed Paper", "1", QUESTIONS_HASH);

		Preview preview = preview(master, incoming);
		Proposal booklet = proposal(preview, incoming.booklet());

		assertEquals(master.booklet().recordId(), booklet.masterId());
		assertEquals(Classification.CONFLICT, booklet.classification());
		assertTrue(booklet.reason().contains("EXAM_BOOKLET metadata"));
		assertTrue(booklet.reason().contains("coordinator review required"));
	}

	private EqworkAssetIndex assets(String seed, UUID questionId, String questionHash, UUID answerId) {
		return new EqworkAssetIndex(List.of(reference(seed + "-questions.pdf", questionId, questionHash),
				reference(seed + "-answers.pdf", answerId, ANSWERS_HASH)));
	}

	private EqworkRecord booklet(String identity, UUID examId, UUID documentId, String name, String legacy) {
		ObjectNode fields = mapper.createObjectNode();
		fields.put("exam_id", examId.toString());
		fields.put("source_document_id", documentId.toString());
		fields.put("booklet_name", name);
		if (legacy == null) {
			fields.putNull("legacy_paper_code");
		} else {
			fields.put("legacy_paper_code", legacy);
		}
		return record("EXAM_BOOKLET", identity, fields);
	}

	private Fixture fixture(String seed, int year, String examName, String bookletName, String legacyCode,
			String pdfHash) {
		EqworkRecord subject = named("SUBJECT", seed + ":subject", "subject_name", "Biology");
		EqworkRecord provider = named("EXAM_PROVIDER", seed + ":provider", "provider_name", "QCAA");
		ObjectNode examFields = mapper.createObjectNode();
		examFields.put("subject_id", subject.recordId().toString());
		examFields.put("provider_id", provider.recordId().toString());
		examFields.put("exam_year", year);
		examFields.put("exam_name", examName);
		examFields.put("capture_state", "ACTIVE");
		EqworkRecord exam = record("EXAM", seed + ":exam", examFields);
		EqworkRecord questionDocument = named("SOURCE_DOCUMENT", seed + ":questions", "content_sha256", pdfHash);
		EqworkRecord answerDocument = named("SOURCE_DOCUMENT", seed + ":answers", "content_sha256", ANSWERS_HASH);
		EqworkRecord booklet = booklet(seed + ":paper1", exam.recordId(), questionDocument.recordId(), bookletName,
				legacyCode);
		ObjectNode answerFields = mapper.createObjectNode();
		answerFields.put("exam_id", exam.recordId().toString());
		answerFields.put("source_document_id", answerDocument.recordId().toString());
		answerFields.put("answer_file_name", "Marking Guide");
		EqworkRecord answerFile = record("ANSWER_FILE", seed + ":markingGuide", answerFields);
		return new Fixture(List.of(subject, provider, exam, questionDocument, answerDocument, booklet, answerFile),
				assets(seed, questionDocument.recordId(), pdfHash, answerDocument.recordId()), subject, exam,
				questionDocument, answerDocument, booklet, answerFile);
	}

	private UUID id(String value) {
		return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
	}

	private EqworkRecord named(String type, String identity, String field, String value) {
		ObjectNode fields = mapper.createObjectNode();
		fields.put(field, value);
		return record(type, identity, fields);
	}

	private Preview preview(Fixture master, Fixture incoming) {
		Preview curriculumPreview = curriculum.preview(master.records(), incoming.records());
		return reconciler.preview(master.records(), master.assets(), incoming.records(), incoming.assets(),
				curriculumPreview);
	}

	private Proposal proposal(Preview preview, EqworkRecord incoming) {
		return preview.forType(incoming.recordType()).stream().filter(p -> p.incomingId().equals(incoming.recordId()))
				.findFirst().orElseThrow();
	}

	private EqworkRecord record(String type, String identity, ObjectNode values) {
		return new EqworkRecord(EqworkRecord.CURRENT_RECORD_VERSION, type, id(identity), id(identity + ":revision"),
				null, values);
	}

	private Reference reference(String filename, UUID ownerId, String hash) {
		String path = "assets/sha256/" + hash.substring(0, 2) + "/" + hash + ".pdf";
		return new Reference(path, hash, 100, "application/pdf", "subjects/Biology/exams/" + filename,
				"SOURCE_DOCUMENT", ownerId, "SOURCE_PDF");
	}

	/**
	 * Immutable test package with graph, verified-index-shaped references and key
	 * records.
	 */
	private record Fixture(List<EqworkRecord> records, EqworkAssetIndex assets, EqworkRecord subject, EqworkRecord exam,
			EqworkRecord questionDocument, EqworkRecord answerDocument, EqworkRecord booklet, EqworkRecord answerFile) {

		private Fixture withAdded(EqworkRecord record) {
			List<EqworkRecord> updated = new ArrayList<>(records);
			updated.add(record);
			return new Fixture(List.copyOf(updated), assets, subject, exam, questionDocument, answerDocument, booklet,
					answerFile);
		}

		private Fixture withReplaced(EqworkRecord original, EqworkRecord replacement) {
			List<EqworkRecord> updated = new ArrayList<>(records);
			updated.set(updated.indexOf(original), replacement);
			return new Fixture(List.copyOf(updated), assets, subject, original == exam ? replacement : exam,
					questionDocument, answerDocument, booklet, answerFile);
		}
	}
}
