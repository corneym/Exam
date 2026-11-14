package au.edu.eq.questionbank.service.workpackage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import au.edu.eq.questionbank.service.workpackage.CurriculumReconciler.Classification;
import au.edu.eq.questionbank.service.workpackage.CurriculumReconciler.Proposal;
import au.edu.eq.questionbank.service.workpackage.SubjectImportApprovalPlan.Operation;
import au.edu.eq.questionbank.service.workpackage.SubjectImportSnapshotFingerprint.Fingerprint;
import au.edu.eq.questionbank.service.workpackage.SubjectReconciliationPreviewService.Result;
import au.edu.eq.questionbank.service.workpackage.SubjectReconciliationReview.Decision;

/**
 * Verifies explicit, non-destructive coordinator import planning.
 *
 * <p>
 * These are service-level planning tests. Real archive and SQLite integration
 * tests are covered separately.
 */
class SubjectImportApprovalPlanTest {

	/**
	 * An accepted conflicting record is not permission to overwrite existing master
	 * content.
	 */
	@Test
	void acceptedConflictCannotBecomeDestructiveOperation() {
		Proposal proposal = proposal("QUESTION", Classification.CONFLICT, UUID.randomUUID());
		SubjectReconciliationReview review = review(proposal);
		review.decide(SubjectReconciliationReview.recordKey(proposal.recordType(), proposal.incomingId()),
				Decision.ACCEPT, "Coordinator has reviewed the conflicting Question");
		assertThrows(IllegalStateException.class, () -> prepare(review));
	}

	/**
	 * The approved operation digest must be independent of repeated plan
	 * construction with unchanged decisions.
	 */
	@Test
	void approvalFingerprintIsDeterministic() {
		Proposal proposal = proposal("SUBJECT", Classification.NEW, null);
		SubjectReconciliationReview review = review(proposal);
		review.decide(SubjectReconciliationReview.recordKey(proposal.recordType(), proposal.incomingId()),
				Decision.ACCEPT, "Approved");
		Fingerprint master = fingerprint(review.preview().masterPackageId(), "a");
		Fingerprint incoming = fingerprint(review.preview().incomingPackageId(), "b");
		var first = SubjectImportApprovalPlan.prepare(review, master, incoming);
		var second = SubjectImportApprovalPlan.prepare(review, master, incoming);
		assertEquals(first.approvalSha256(), second.approvalSha256());
		assertTrue(first.actions().stream().allMatch(action -> action.decision() != Decision.UNDECIDED));
	}

	/**
	 * An unambiguous identical record becomes an approved alias.
	 */
	@Test
	void approvedIdenticalRecordProducesAliasAction() {
		UUID canonical = UUID.randomUUID();
		Proposal proposal = proposal("QUESTION", Classification.IDENTICAL, canonical);
		SubjectReconciliationReview review = review(proposal);
		review.decide(SubjectReconciliationReview.recordKey(proposal.recordType(), proposal.incomingId()),
				Decision.ACCEPT, "Matching source and captured content");
		SubjectImportApprovalPlan plan = prepare(review);
		assertEquals(Operation.LINK_ALIAS, plan.actions().getFirst().operation());
		assertEquals(canonical, plan.actions().getFirst().masterId());
	}

	/**
	 * An explicitly accepted new record becomes an insert.
	 */
	@Test
	void approvedNewRecordProducesInsertAction() {
		Proposal proposal = proposal("SUBJECT", Classification.NEW, null);
		SubjectReconciliationReview review = review(proposal);
		review.decide(SubjectReconciliationReview.recordKey(proposal.recordType(), proposal.incomingId()),
				Decision.ACCEPT, "Approved new Biology Subject");
		SubjectImportApprovalPlan plan = prepare(review);
		assertEquals(Operation.INSERT_RECORD, plan.actions().getFirst().operation());
		assertEquals(64, plan.approvalSha256().length());
	}

	/**
	 * Two independently proposed aliases must not silently collapse distinct
	 * incoming records into one canonical master identity.
	 */
	@Test
	void duplicateCanonicalClaimsAreBlocked() {
		UUID canonical = UUID.randomUUID();
		Proposal first = proposal("QUESTION", Classification.IDENTICAL, canonical);
		Proposal second = proposal("QUESTION", Classification.IDENTICAL, canonical);
		SubjectReconciliationReview review = review(first, second);
		for (Proposal proposal : List.of(first, second)) {
			review.decide(SubjectReconciliationReview.recordKey(proposal.recordType(), proposal.incomingId()),
					Decision.ACCEPT, "Approved identical Question evidence");
		}
		assertThrows(IllegalStateException.class, () -> prepare(review));
	}

	/**
	 * Unreviewed proposals must not produce an executable plan.
	 */
	@Test
	void incompleteReviewCannotBePrepared() {
		Proposal proposal = proposal("SUBJECT", Classification.NEW, null);
		SubjectReconciliationReview review = review(proposal);
		assertThrows(IllegalStateException.class, () -> prepare(review));
	}

	/**
	 * Rejected conflicts remain represented as explicit skip actions.
	 */
	@Test
	void rejectedConflictRemainsRecordedInPlan() {
		Proposal proposal = proposal("QUESTION", Classification.CONFLICT, UUID.randomUUID());
		SubjectReconciliationReview review = review(proposal);
		review.decide(SubjectReconciliationReview.recordKey(proposal.recordType(), proposal.incomingId()),
				Decision.REJECT, "Retain original master capture");
		SubjectImportApprovalPlan plan = prepare(review);
		assertEquals(Operation.SKIP, plan.actions().getFirst().operation());
		assertEquals(Decision.REJECT, plan.actions().getFirst().decision());
	}

	/**
	 * Creates a synthetic immutable archive fingerprint.
	 */
	private Fingerprint fingerprint(UUID packageId, String hex) {
		return new Fingerprint(packageId, UUID.randomUUID(), UUID.randomUUID(), "Biology", hex.repeat(64),
				hex.repeat(64));
	}

	/**
	 * Creates a complete plan using synthetic verified identity values.
	 */
	private SubjectImportApprovalPlan prepare(SubjectReconciliationReview review) {
		return SubjectImportApprovalPlan.prepare(review, fingerprint(review.preview().masterPackageId(), "a"),
				fingerprint(review.preview().incomingPackageId(), "b"));
	}

	/**
	 * Creates one inspectable reconciliation proposal.
	 */
	private Proposal proposal(String type, Classification classification, UUID canonical) {
		return new Proposal(type, UUID.randomUUID(), canonical, null, null, classification,
				"Independent Biology reconciliation evidence");
	}

	/**
	 * Constructs a read-only coordinator review.
	 */
	private SubjectReconciliationReview review(Proposal... proposals) {
		Result preview = new Result(UUID.randomUUID(), UUID.randomUUID(), "Biology", List.of(proposals), List.of(),
				List.of(), List.of());
		return new SubjectReconciliationReview(preview);
	}
}
