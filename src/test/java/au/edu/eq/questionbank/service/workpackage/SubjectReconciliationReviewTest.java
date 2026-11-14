package au.edu.eq.questionbank.service.workpackage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.service.workpackage.CurriculumReconciler.Classification;
import au.edu.eq.questionbank.service.workpackage.CurriculumReconciler.Proposal;
import au.edu.eq.questionbank.service.workpackage.SubjectReconciliationPreviewService.AssetEvidence;
import au.edu.eq.questionbank.service.workpackage.SubjectReconciliationPreviewService.Result;
import au.edu.eq.questionbank.service.workpackage.SubjectReconciliationReview.Decision;

/**
 * Verifies explicit, dependency-safe coordinator decisions without any import.
 */
class SubjectReconciliationReviewTest {

	@TempDir
	Path directory;

	@Test
	void acceptAllIdenticalIncludesEligibleRecordsAndAssets() {
		SubjectReconciliationReview review = fixtureWithBookletAsset();
		var plan = review.planAcceptAllIdentical();
		assertEquals(5, plan.recordCount());
		assertEquals(1, plan.assetCount());
		assertEquals(1, plan.blockedCount());

		// Preparing the plan never changes the review.
		assertTrue(review.snapshot().decisions().isEmpty());
		assertEquals(plan, review.acceptAllIdentical());
		assertEquals(Decision.ACCEPT, review.choice(key("SUBJECT", 1)).decision());
		assertEquals(Decision.ACCEPT, review.choice(key("EXAM_BOOKLET", 4)).decision());
		assertEquals(Decision.UNDECIDED, review.choice(key("QUESTION", 3)).decision());
		var bookletAsset = review.reviewKeys().stream().filter(key -> "ASSET".equals(key.kind()))
				.filter(key -> key.value().contains("booklet.pdf")).findFirst().orElseThrow();
		var questionAsset = review.reviewKeys().stream().filter(key -> "ASSET".equals(key.kind()))
				.filter(key -> key.value().contains("questions.pdf")).findFirst().orElseThrow();
		assertEquals(Decision.ACCEPT, review.choice(bookletAsset).decision());
		assertEquals(Decision.UNDECIDED, review.choice(questionAsset).decision());
		assertTrue(review.snapshot().dependencySafe());
		assertFalse(review.snapshot().complete());
	}

	@Test
	void bulkAcceptanceIsIdempotent() {
		SubjectReconciliationReview review = fixtureWithBookletAsset();
		review.decide(key("SUBJECT", 1), Decision.ACCEPT, "Previously inspected");
		var first = review.acceptAllIdentical();
		var afterFirst = review.snapshot().decisions();
		assertEquals(4, first.recordCount());
		assertEquals(1, first.assetCount());
		var second = review.acceptAllIdentical();
		assertEquals(0, second.recordCount());
		assertEquals(0, second.assetCount());
		assertEquals(afterFirst, review.snapshot().decisions());
		assertEquals("Previously inspected", review.choice(key("SUBJECT", 1)).justification());
	}

	@Test
	void bulkAcceptancePreservesRejectionsAndBlockedDependencies() {
		SubjectReconciliationReview review = fixtureWithBookletAsset();
		review.decide(key("SOURCE_DOCUMENT", 5), Decision.REJECT, "Source document rejected");
		var result = review.acceptAllIdentical();
		assertEquals(3, result.recordCount());
		assertEquals(0, result.assetCount());
		assertEquals(3, result.blockedCount());
		assertEquals(Decision.REJECT, review.choice(key("SOURCE_DOCUMENT", 5)).decision());
		assertEquals(Decision.UNDECIDED, review.choice(key("EXAM_BOOKLET", 4)).decision());
		assertTrue(review.snapshot().dependencySafe());
	}

	/**
	 * Proves that a conflicting portable record requires explicit coordinator
	 * rationale.
	 */
	@Test
	void conflictingAliasesRequireWrittenJustificationAndDoNotChangeClassification() {
		SubjectReconciliationReview review = fixture();
		assertThrows(IllegalArgumentException.class,
				() -> review.decide(key("QUESTION", 3), Decision.ACCEPT, "too short"));
		review.decide(key("QUESTION", 3), Decision.ACCEPT, "Reviewed the Question and PDF regions");
		assertEquals(Classification.CONFLICT, review.preview().forType("QUESTION").getFirst().classification());
		assertEquals(Decision.ACCEPT, review.choice(key("QUESTION", 3)).decision());
	}

	/**
	 * Proves that all decisions begin UNDECIDED despite identical content evidence.
	 */
	@Test
	void noMatchOrAssetIsApprovedAutomatically() {
		SubjectReconciliationReview review = fixture();
		assertTrue(review.reviewKeys().stream().allMatch(key -> review.choice(key).decision() == Decision.UNDECIDED));
		assertFalse(review.snapshot().complete());
		assertTrue(review.snapshot().decisions().isEmpty());
	}

	/**
	 * Verifies every item may explicitly be rejected without mutating any proposal.
	 */
	@Test
	void rejectedReviewMayBeCompleteAndSafeWithoutAnyAcceptedRecord() {
		SubjectReconciliationReview review = fixture();
		for (var key : review.reviewKeys()) {
			review.decide(key, Decision.REJECT, "not selected");
		}
		assertTrue(review.snapshot().complete());
		assertTrue(review.snapshot().dependencySafe());
		assertEquals(0, review.snapshot().decisions().values().stream()
				.filter(choice -> choice.decision() == Decision.ACCEPT).count());
	}

	/**
	 * Verifies that rejecting a parent prevents acceptance of its descendants, even
	 * when a conflicting descendant has a valid coordinator justification.
	 */
	@Test
	void rejectingParentBlocksAllDescendants() {
		SubjectReconciliationReview review = fixture();
		review.decide(key("SUBJECT", 1), Decision.REJECT, "not this Subject");
		assertThrows(IllegalStateException.class,
				() -> review.decide(key("QUESTION", 3), Decision.ACCEPT, "Reviewed conflicting PDF regions"));
		assertFalse(review.snapshot().complete());
	}

	/** Verifies a rejected SourceDocument cannot support an accepted Booklet. */
	@Test
	void sourceDocumentDependencyIsPreservedAcrossRecordTypes() {
		SubjectReconciliationReview review = fixture();
		review.decide(key("SOURCE_DOCUMENT", 5), Decision.REJECT, "Wrong source PDF");
		assertThrows(IllegalStateException.class, () -> review.decide(key("EXAM_BOOKLET", 4), Decision.ACCEPT, ""));
	}

	/** Proves that an accepted child requires explicitly accepted ancestry. */
	@Test
	void unresolvedParentsPreventDependencySafeFinalReview() {
		SubjectReconciliationReview review = fixture();
		review.decide(key("QUESTION", 3), Decision.ACCEPT, "Reviewed these different regions");
		assertFalse(review.snapshot().dependencySafe());
		review.decide(key("SUBJECT", 1), Decision.ACCEPT, "");
		review.decide(key("EXAM", 2), Decision.ACCEPT, "");
		review.decide(key("EXAM_BOOKLET", 4), Decision.ACCEPT, "");
		assertFalse(review.snapshot().dependencySafe());
		review.decide(key("SOURCE_DOCUMENT", 5), Decision.ACCEPT, "");
		review.decide(key("CURRICULUM_NODE", 6), Decision.ACCEPT, "");
		assertTrue(review.snapshot().dependencySafe());
		assertThrows(IllegalStateException.class, () -> review.decide(key("EXAM", 2), Decision.REJECT, "not accepted"));
	}

	/**
	 * Verifies report saving is non-destructive and protected from managed paths.
	 */
	@Test
	void writesPortableReviewReportOutsideManagedDataAndNeverOverwrites() throws Exception {
		Path managed = directory.resolve("managed");
		Files.createDirectories(managed);
		SubjectReconciliationReview review = fixture();
		review.decide(key("SUBJECT", 1), Decision.ACCEPT, "");
		Path report = directory.resolve("biology-review.json");
		ReconciliationReviewWriter writer = new ReconciliationReviewWriter(managed);
		writer.write(report, review);
		String contents = Files.readString(report);
		assertTrue(contents.contains("examwork-two-way-review-v1"));
		assertTrue(contents.contains("masterPackageId"));
		assertTrue(contents.contains("UNDECIDED"));
		assertThrows(Exception.class, () -> writer.write(report, review));
		assertThrows(IllegalArgumentException.class, () -> writer.write(managed.resolve("review.json"), review));
		assertFalse(Files.exists(managed.resolve("review.json")));
	}

	/** Creates a small immutable preview with parent, child and asset evidence. */
	private SubjectReconciliationReview fixture() {
		Proposal subject = new Proposal("SUBJECT", uuid(1), uuid(11), null, null, Classification.IDENTICAL,
				"Subject names agree");
		Proposal exam = new Proposal("EXAM", uuid(2), uuid(12), uuid(1), uuid(11), Classification.IDENTICAL,
				"Exam metadata agrees");
		Proposal booklet = new Proposal("EXAM_BOOKLET", uuid(4), uuid(14), uuid(2), uuid(12), Classification.IDENTICAL,
				"Booklet content agrees");
		Proposal document = new Proposal("SOURCE_DOCUMENT", uuid(5), uuid(15), null, null, Classification.IDENTICAL,
				"Source PDF bytes agree");
		Proposal curriculumNode = new Proposal("CURRICULUM_NODE", uuid(6), uuid(16), null, null,
				Classification.IDENTICAL, "Classification nodes agree");
		Proposal question = new Proposal("QUESTION", uuid(3), uuid(13), uuid(4), uuid(14), Classification.CONFLICT,
				"PDF regions differ");
		AssetEvidence asset = new AssetEvidence("subjects/Biology/exams/questions.pdf", "SOURCE_PDF", "QUESTION",
				uuid(3), "subjects/Biology/exams/questions.pdf", "a".repeat(64), "a".repeat(64),
				Classification.IDENTICAL, "Same verified bytes");
		Result preview = new Result(uuid(100), uuid(200), "Biology",
				List.of(subject, exam, booklet, document, curriculumNode, question), List.of(), List.of(asset),
				List.of(new SubjectReconciliationPreviewService.Dependency("EXAM_BOOKLET", uuid(4), "SOURCE_DOCUMENT",
						uuid(5), "source_document_id"),
						new SubjectReconciliationPreviewService.Dependency("QUESTION", uuid(3), "CURRICULUM_NODE",
								uuid(6), "classification_node_id")));
		return new SubjectReconciliationReview(preview);
	}

	private SubjectReconciliationReview fixtureWithBookletAsset() {
		Result original = fixture().preview();
		AssetEvidence bookletAsset = new AssetEvidence("subjects/Biology/exams/booklet.pdf", "SOURCE_PDF",
				"EXAM_BOOKLET", uuid(4), "subjects/Biology/exams/booklet.pdf", "b".repeat(64), "b".repeat(64),
				Classification.IDENTICAL, "Matching verified booklet asset");
		List<AssetEvidence> assets = new ArrayList<>(original.assetEvidence());
		assets.add(bookletAsset);
		Result expanded = new Result(original.masterPackageId(), original.incomingPackageId(), original.subjectName(),
				original.proposals(), original.imageEvidence(), assets, original.dependencies());
		return new SubjectReconciliationReview(expanded);
	}

	/** Builds a record review key from the fixture's portable identity. */
	private SubjectReconciliationReview.Key key(String type, long number) {
		return SubjectReconciliationReview.recordKey(type, uuid(number));
	}

	/** Creates a stable UUID distinct from every other test identity. */
	private UUID uuid(long identifier) {
		return new UUID(0L, identifier);
	}
}
