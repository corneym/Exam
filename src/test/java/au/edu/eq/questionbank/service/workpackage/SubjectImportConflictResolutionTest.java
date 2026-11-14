package au.edu.eq.questionbank.service.workpackage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import au.edu.eq.questionbank.service.workpackage.CurriculumReconciler.Classification;
import au.edu.eq.questionbank.service.workpackage.CurriculumReconciler.Proposal;
import au.edu.eq.questionbank.service.workpackage.SubjectImportApprovalPlan.ConflictMode;
import au.edu.eq.questionbank.service.workpackage.SubjectImportApprovalPlan.ConflictResolution;
import au.edu.eq.questionbank.service.workpackage.SubjectImportApprovalPlan.Operation;
import au.edu.eq.questionbank.service.workpackage.SubjectImportSnapshotFingerprint.Fingerprint;
import au.edu.eq.questionbank.service.workpackage.SubjectReconciliationPreviewService.AssetEvidence;
import au.edu.eq.questionbank.service.workpackage.SubjectReconciliationPreviewService.Result;
import au.edu.eq.questionbank.service.workpackage.SubjectReconciliationReview.Decision;
import au.edu.eq.questionbank.service.workpackage.SubjectReconciliationReview.Key;

/**
 * Verifies that accepted conflicts require explicit, executable,
 * non-destructive coordinator resolution operations.
 */
class SubjectImportConflictResolutionTest {

	/**
	 * An accepted conflict without an operation must remain blocked.
	 */
	@Test
	void acceptedConflictWithoutResolutionFails() {
		Proposal proposal = conflictingSubject();
		SubjectReconciliationReview review = review(List.of(proposal), List.of());
		accept(review, proposal);
		assertThrows(IllegalStateException.class, () -> plan(review, Map.of()));
	}

	/**
	 * A binary conflict cannot be resolved using an unchecked or incorrect master
	 * content digest.
	 */
	@Test
	void assetPreservationRequiresMatchingMasterChecksum() {
		UUID incomingOwner = UUID.randomUUID();
		Proposal owner = new Proposal("SUBJECT", incomingOwner, UUID.randomUUID(), null, null, Classification.IDENTICAL,
				"Existing Biology Subject");
		String path = "subjects/Biology/curriculum/syllabus.pdf";
		AssetEvidence asset = new AssetEvidence(path, "INVENTORIED", "SUBJECT", incomingOwner, path, "b".repeat(64),
				"a".repeat(64), Classification.CONFLICT, "The existing master file has different bytes");
		SubjectReconciliationReview review = review(List.of(owner), List.of(asset));
		accept(review, owner);
		Key assetKey = SubjectReconciliationReview.assetKey(asset);
		review.decide(assetKey, Decision.ACCEPT, "Coordinator reviewed the changed PDF asset");
		assertThrows(IllegalStateException.class,
				() -> plan(review, Map.of(assetKey, new ConflictResolution(ConflictMode.PRESERVE_MASTER, null,
						"c".repeat(64), "Preserve master syllabus PDF bytes"))));
		var plan = plan(review, Map.of(assetKey, new ConflictResolution(ConflictMode.PRESERVE_MASTER, null,
				"a".repeat(64), "Retain existing validated master syllabus PDF")));
		assertEquals(Operation.PRESERVE_CONFLICT, plan.actions().stream()
				.filter(action -> action.key().equals(assetKey)).findFirst().orElseThrow().operation());
	}

	/**
	 * The written operation and rationale are part of the approved fingerprint, not
	 * merely unstructured review metadata.
	 */
	@Test
	void differentResolutionRationaleChangesApprovalFingerprint() {
		Proposal proposal = conflictingSubject();
		SubjectReconciliationReview review = review(List.of(proposal), List.of());
		accept(review, proposal);
		Key key = SubjectReconciliationReview.recordKey(proposal.recordType(), proposal.incomingId());
		var first = plan(review, Map.of(key, new ConflictResolution(ConflictMode.LINK_TO_MASTER, proposal.masterId(),
				null, "Approved correspondence with existing Biology")));
		var second = plan(review, Map.of(key, new ConflictResolution(ConflictMode.LINK_TO_MASTER, proposal.masterId(),
				null, "Reviewed syllabus evidence for Biology alias")));
		assertNotEquals(first.approvalSha256(), second.approvalSha256());
	}

	/**
	 * A matching, specifically approved master Subject may be reused through an
	 * alias without changing its existing values.
	 */
	@Test
	void explicitMasterAliasPreservesOriginalConflictClassification() {
		Proposal proposal = conflictingSubject();
		SubjectReconciliationReview review = review(List.of(proposal), List.of());
		accept(review, proposal);
		Key key = SubjectReconciliationReview.recordKey(proposal.recordType(), proposal.incomingId());
		ConflictResolution resolution = new ConflictResolution(ConflictMode.LINK_TO_MASTER, proposal.masterId(), null,
				"Reuse the existing master Biology Subject");
		var plan = plan(review, Map.of(key, resolution));
		assertEquals(Operation.LINK_ALIAS, plan.actions().getFirst().operation());
		assertEquals("CONFLICT", plan.actions().getFirst().classification());
		assertEquals(proposal.masterId(), plan.actions().getFirst().masterId());
	}

	/**
	 * A resolution cannot silently point to an entity other than the master
	 * identity proposed in the reviewed evidence.
	 */
	@Test
	void masterIdentitySubstitutionIsRejected() {
		Proposal proposal = conflictingSubject();
		SubjectReconciliationReview review = review(List.of(proposal), List.of());
		accept(review, proposal);
		Key key = SubjectReconciliationReview.recordKey(proposal.recordType(), proposal.incomingId());
		assertThrows(IllegalStateException.class,
				() -> plan(review, Map.of(key, new ConflictResolution(ConflictMode.LINK_TO_MASTER, UUID.randomUUID(),
						null, "Attempt to redirect reviewed master identity"))));
	}

	/**
	 * A child cannot be imported if its required incoming parent was preserved
	 * without an alias.
	 */
	@Test
	void preservedParentBlocksAcceptedChild() {
		Proposal parent = conflictingSubject();
		Proposal child = new Proposal("SYLLABUS_VERSION", UUID.randomUUID(), null, parent.incomingId(),
				parent.masterId(), Classification.NEW, "New contributor syllabus version");
		SubjectReconciliationReview review = review(List.of(parent, child), List.of());
		accept(review, parent);
		accept(review, child);
		Key key = SubjectReconciliationReview.recordKey(parent.recordType(), parent.incomingId());
		assertThrows(IllegalStateException.class,
				() -> plan(review, Map.of(key, new ConflictResolution(ConflictMode.PRESERVE_MASTER, parent.masterId(),
						null, "Preserve original Subject and discard incoming variant"))));
	}

	/**
	 * A supported preservation resolution is explicitly represented as a
	 * non-publishing operation.
	 */
	@Test
	void preservingConflictDoesNotProduceInsertOrAlias() {
		Proposal proposal = conflictingSubject();
		SubjectReconciliationReview review = review(List.of(proposal), List.of());
		accept(review, proposal);
		Key key = SubjectReconciliationReview.recordKey(proposal.recordType(), proposal.incomingId());
		var plan = plan(review, Map.of(key, new ConflictResolution(ConflictMode.PRESERVE_MASTER, proposal.masterId(),
				null, "Preserve existing Biology record unchanged")));
		assertEquals(Operation.PRESERVE_CONFLICT, plan.actions().getFirst().operation());
	}

	/**
	 * A replacement request must fail before any publication occurs.
	 */
	@Test
	void replacementIsNotAccidentallyAuthorised() {
		Proposal proposal = conflictingSubject();
		SubjectReconciliationReview review = review(List.of(proposal), List.of());
		accept(review, proposal);
		Key key = SubjectReconciliationReview.recordKey(proposal.recordType(), proposal.incomingId());
		assertThrows(IllegalStateException.class,
				() -> plan(review, Map.of(key, new ConflictResolution(ConflictMode.REPLACE_MASTER, proposal.masterId(),
						null, "Requested replacement has no safe executor"))));
	}

	/**
	 * A conflict resolution cannot be attached to a rejected proposal.
	 */
	@Test
	void staleResolutionForRejectedProposalFails() {
		Proposal proposal = conflictingSubject();
		SubjectReconciliationReview review = review(List.of(proposal), List.of());
		Key key = SubjectReconciliationReview.recordKey(proposal.recordType(), proposal.incomingId());
		review.decide(key, Decision.REJECT, "Retain current master Subject");
		assertThrows(IllegalArgumentException.class,
				() -> plan(review, Map.of(key, new ConflictResolution(ConflictMode.LINK_TO_MASTER, proposal.masterId(),
						null, "Stale approval from an earlier review"))));
	}

	/**
	 * Accepts one proposal using the existing coordinator review API.
	 */
	private void accept(SubjectReconciliationReview review, Proposal proposal) {
		review.decide(SubjectReconciliationReview.recordKey(proposal.recordType(), proposal.incomingId()),
				Decision.ACCEPT, "Coordinator reviewed the portable record");
	}

	/**
	 * Creates a conflicting incoming Subject matched to a master Subject.
	 */
	private Proposal conflictingSubject() {
		return new Proposal("SUBJECT", UUID.randomUUID(), UUID.randomUUID(), null, null, Classification.CONFLICT,
				"Independent Biology Subject has conflicting metadata");
	}

	/**
	 * Creates an immutable synthetic snapshot fingerprint.
	 */
	private Fingerprint fingerprint(UUID packageId, String marker) {
		return new Fingerprint(packageId, UUID.randomUUID(), UUID.randomUUID(), "Biology", marker.repeat(64),
				marker.repeat(64));
	}

	/**
	 * Prepares a plan against fixed synthetic fingerprint identities.
	 */
	private SubjectImportApprovalPlan plan(SubjectReconciliationReview review,
			Map<Key, ConflictResolution> resolutions) {
		return SubjectImportApprovalPlan.prepare(review, fingerprint(review.preview().masterPackageId(), "a"),
				fingerprint(review.preview().incomingPackageId(), "b"), resolutions);
	}

	/**
	 * Creates a review with the exact proposals supplied by each test.
	 */
	private SubjectReconciliationReview review(List<Proposal> proposals, List<AssetEvidence> assets) {
		return new SubjectReconciliationReview(
				new Result(UUID.randomUUID(), UUID.randomUUID(), "Biology", proposals, List.of(), assets, List.of()));
	}
}
