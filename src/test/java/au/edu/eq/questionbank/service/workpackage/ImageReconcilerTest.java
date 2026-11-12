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
import com.fasterxml.jackson.databind.node.ObjectNode;

import au.edu.eq.questionbank.service.workpackage.ExamWorkAssetIndex.Reference;
import au.edu.eq.questionbank.service.workpackage.CurriculumReconciler.Classification;
import au.edu.eq.questionbank.service.workpackage.CurriculumReconciler.Preview;
import au.edu.eq.questionbank.service.workpackage.CurriculumReconciler.Proposal;
import au.edu.eq.questionbank.service.workpackage.ImageReconciler.Evidence;
import au.edu.eq.questionbank.service.workpackage.ImageReconciler.ImagePreview;

/**
 * Verifies read-only image evidence across independently identified Question
 * and Shared Context records, including ownership, digest and content order.
 */
class ImageReconcilerTest {

	private static final String RED = "a".repeat(64);
	private static final String BLUE = "b".repeat(64);
	private static final String GREEN = "c".repeat(64);
	private final ImageReconciler reconciler = new ImageReconciler();
	private final ObjectMapper mapper = new ObjectMapper();

	/**
	 * Different PNG bytes cannot be hidden by matching Question names or PDF
	 * regions.
	 */
	@Test
	void changedQuestionPngDigestIsConflict() {
		Fixture master = fixture("master", RED, List.of());
		Fixture incoming = fixture("incoming", GREEN, List.of());
		ImagePreview result = inspect(master, incoming, capturePreview(master, incoming, true));
		assertEquals(Classification.CONFLICT, evidence(result, incoming.question()).classification());
	}

	/**
	 * A rearranged pair of Context images remains a conflict despite identical hash
	 * sets.
	 */
	@Test
	void changedSharedContextImageOrderIsConflict() {
		Fixture master = fixture("master", RED, List.of(RED, BLUE));
		Fixture incoming = fixture("incoming", RED, List.of(BLUE, RED));
		ImagePreview result = inspect(master, incoming, capturePreview(master, incoming, true));
		assertEquals(Classification.IDENTICAL, evidence(result, incoming.question()).classification());
		assertEquals(Classification.CONFLICT, evidence(result, incoming.context()).classification());
		assertTrue(evidence(result, incoming.context()).reason().contains("order"));
	}

	/**
	 * A Shared Context image cannot be omitted from its asset-reference inventory.
	 */
	@Test
	void contextAssetMultisetMismatchFailsClosed() {
		Fixture master = fixture("master", null, List.of(RED, BLUE));
		Fixture incoming = fixture("incoming", null, List.of(RED, BLUE));
		ExamWorkAssetIndex missing = new ExamWorkAssetIndex(
				incoming.assets().references().stream().filter(reference -> !reference.sha256().equals(BLUE)).toList());
		assertThrows(IllegalArgumentException.class, () -> reconciler.preview(master.records(), master.assets(),
				incoming.records(), missing, capturePreview(master, incoming, true)));
	}

	/** An archive without embedded images produces no image-specific decisions. */
	@Test
	void emptyImageInventoriesNeedNoFurtherReview() {
		Fixture master = fixture("master", null, List.of());
		Fixture incoming = fixture("incoming", null, List.of());
		assertTrue(inspect(master, incoming, capturePreview(master, incoming, true)).evidence().isEmpty());
	}

	/** An image in a Question must also appear in its portable child image rows. */
	@Test
	void imageRecordDisagreeingWithQuestionChildRowsFailsClosed() {
		Fixture master = fixture("master", RED, List.of());
		Fixture incoming = fixture("incoming", RED, List.of());
		ObjectNode changed = (ObjectNode) incoming.question().values();
		((ObjectNode) changed.path("question_images").get(0)).put("image_png", BLUE);
		ExchangeRecord inconsistent = record("QUESTION", "incoming:question", changed);
		List<ExchangeRecord> rows = incoming.records().stream()
				.map(row -> row.recordId().equals(incoming.question().recordId()) ? inconsistent : row).toList();
		assertThrows(IllegalArgumentException.class, () -> reconciler.preview(master.records(), master.assets(), rows,
				incoming.assets(), capturePreview(master, incoming, true)));
	}

	/**
	 * Matching owner content proves image equivalence but never accepts a capture
	 * alias.
	 */
	@Test
	void independentUuidImagesAndSharedContextAreIdenticalEvidenceOnly() {
		Fixture master = fixture("master", RED, List.of(RED, BLUE));
		Fixture incoming = fixture("incoming", RED, List.of(RED, BLUE));
		Preview captures = capturePreview(master, incoming, true);
		ImagePreview result = inspect(master, incoming, captures);
		assertEquals(2, result.evidence().size());
		assertTrue(
				result.evidence().stream().allMatch(evidence -> evidence.classification() == Classification.IDENTICAL));
		assertEquals(master.question().recordId(), evidence(result, incoming.question()).masterOwnerId());
		assertEquals(master.context().recordId(), evidence(result, incoming.context()).masterOwnerId());
		assertTrue(captures.proposals().stream()
				.allMatch(proposal -> proposal.classification() == Classification.CONFLICT));
	}

	/** An absent indexed embedded image prevents any reconciliation result. */
	@Test
	void missingQuestionPngAssetFailsClosed() {
		Fixture master = fixture("master", RED, List.of());
		Fixture incoming = fixture("incoming", RED, List.of());
		ExamWorkAssetIndex missing = new ExamWorkAssetIndex(incoming.assets().references().stream()
				.filter(reference -> !reference.usage().equals("EMBEDDED_QUESTION_PNG")).toList());
		assertThrows(IllegalArgumentException.class, () -> reconciler.preview(master.records(), master.assets(),
				incoming.records(), missing, capturePreview(master, incoming, true)));
	}

	/**
	 * A verified SHA-256 on an unrelated image owner is not provenance for this
	 * image.
	 */
	@Test
	void pngAssetWithIncorrectOwnerFailsClosed() {
		Fixture master = fixture("master", RED, List.of());
		Fixture incoming = fixture("incoming", RED, List.of());
		List<Reference> changed = new ArrayList<>();
		for (Reference reference : incoming.assets().references()) {
			if (reference.usage().equals("EMBEDDED_QUESTION_PNG")) {
				changed.add(new Reference(reference.archivePath(), reference.sha256(), reference.size(),
						reference.mediaType(), reference.managedPath(), "QUESTION_IMAGE", id("wrong-owner"),
						reference.usage()));
			} else {
				changed.add(reference);
			}
		}
		assertThrows(IllegalArgumentException.class, () -> reconciler.preview(master.records(), master.assets(),
				incoming.records(), new ExamWorkAssetIndex(changed), capturePreview(master, incoming, true)));
	}

	/**
	 * Matching bytes from an unrelated independent Question do not establish
	 * identity.
	 */
	@Test
	void samePngInIndependentQuestionDoesNotProposeAlias() {
		Fixture master = fixture("master", RED, List.of());
		Fixture incoming = fixture("incoming", RED, List.of());
		ImagePreview result = inspect(master, incoming, capturePreview(master, incoming, false));
		Evidence evidence = evidence(result, incoming.question());
		assertEquals(Classification.COMPATIBLE_ADDITION, evidence.classification());
		assertNull(evidence.masterOwnerId());
	}

	/** Creates a valid immutable logical reference to a content-addressed PNG. */
	private Reference asset(String ownerType, UUID owner, String usage, String hash, String suffix) {
		return new Reference("assets/sha256/" + hash.substring(0, 2) + "/" + hash + ".png", hash, 16L, "image/png",
				"subjects/Biology/exams/_examwork_embedded/" + suffix, ownerType, owner, usage);
	}

	/**
	 * Produces capture evidence without changing source records or accepting
	 * aliases.
	 */
	private Preview capturePreview(Fixture master, Fixture incoming, boolean proposedMatch) {
		return new Preview(List.of(
				captureProposal(incoming.question(), proposedMatch ? master.question() : null,
						proposedMatch ? Classification.CONFLICT : Classification.COMPATIBLE_ADDITION),
				captureProposal(incoming.context(), proposedMatch ? master.context() : null,
						proposedMatch ? Classification.CONFLICT : Classification.COMPATIBLE_ADDITION)));
	}

	/** Constructs a previously reviewed but still unapproved capture proposal. */
	private Proposal captureProposal(ExchangeRecord incoming, ExchangeRecord master, Classification classification) {
		return new Proposal(incoming.recordType(), incoming.recordId(), master == null ? null : master.recordId(), null,
				null, classification, "Capture decision requires independent parent and region verification");
	}

	/** Looks up exactly one image result for an incoming owner record. */
	private Evidence evidence(ImagePreview preview, ExchangeRecord owner) {
		return preview.evidence().stream().filter(item -> item.incomingOwnerId().equals(owner.recordId())).findFirst()
				.orElseThrow();
	}

	/** Constructs a portable Subject subset with real image ownership links. */
	private Fixture fixture(String installation, String questionHash, List<String> contextHashes) {
		UUID questionId = id(installation + ":question");
		UUID contextId = id(installation + ":context");
		UUID imageId = id(installation + ":question-image");
		ObjectNode questionValues = mapper.createObjectNode();
		questionValues.put("question_code", "1");
		questionValues.putArray("question_images");
		questionValues.putArray("question_content_parts").addObject().put("content_order", 0)
				.put("content_type", "PDF_REGION").put("region_order", 0);
		List<ExchangeRecord> records = new ArrayList<>();
		List<Reference> assets = new ArrayList<>();
		if (questionHash != null) {
			questionValues.withArray("question_images").addObject().put("image_png", questionHash);
			questionValues.withArray("question_content_parts").addObject().put("content_order", 1)
					.put("content_type", "IMAGE").put("image_id", imageId.toString());
			ObjectNode image = mapper.createObjectNode();
			image.put("question_id", questionId.toString());
			image.put("image_png", questionHash);
			records.add(record("QUESTION_IMAGE", installation + ":question-image", image));
			assets.add(asset("QUESTION_IMAGE", imageId, "EMBEDDED_QUESTION_PNG", questionHash,
					"QUESTION_IMAGE/" + imageId + ".png"));
		}
		ExchangeRecord question = record("QUESTION", installation + ":question", questionValues);
		records.add(question);
		ObjectNode contextValues = mapper.createObjectNode();
		contextValues.put("context_label", "Shared stimulus");
		contextValues.putArray("shared_question_context_images");
		contextValues.putArray("shared_question_context_content_parts");
		for (int position = 0; position < contextHashes.size(); position++) {
			String hash = contextHashes.get(position);
			contextValues.withArray("shared_question_context_images").addObject().put("image_order", position)
					.put("image_png", hash);
			contextValues.withArray("shared_question_context_content_parts").addObject().put("content_order", position)
					.put("content_type", "IMAGE").put("image_order", position);
			assets.add(asset("SHARED_QUESTION_CONTEXT", contextId, "EMBEDDED_CONTEXT_PNG", hash,
					"SHARED_QUESTION_CONTEXT/" + contextId + "-" + position + ".png"));
		}
		ExchangeRecord context = record("SHARED_QUESTION_CONTEXT", installation + ":context", contextValues);
		records.add(context);
		return new Fixture(List.copyOf(records), new ExamWorkAssetIndex(assets), question, context);
	}

	/**
	 * Uses stable per-installation test UUIDs without relying on local SQLite IDs.
	 */
	private UUID id(String text) {
		return UUID.nameUUIDFromBytes(text.getBytes(StandardCharsets.UTF_8));
	}

	/** Runs only the image evidence stage using the provided capture proposals. */
	private ImagePreview inspect(Fixture master, Fixture incoming, Preview captures) {
		return reconciler.preview(master.records(), master.assets(), incoming.records(), incoming.assets(), captures);
	}

	/**
	 * Constructs a version-one portable record with independent revision identity.
	 */
	private ExchangeRecord record(String type, String name, ObjectNode values) {
		return new ExchangeRecord(ExchangeRecord.CURRENT_RECORD_VERSION, type, id(name), id(name + ":revision"), null,
				values);
	}

	/** Minimal immutable Subject subset for independent image provenance tests. */
	private record Fixture(List<ExchangeRecord> records, ExamWorkAssetIndex assets, ExchangeRecord question,
			ExchangeRecord context) {
	}
}
