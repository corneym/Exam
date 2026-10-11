package au.edu.eq.questionbank.repository.assessment;

import java.util.Set;

/**
 * Persistence boundary for explicit human confirmations that a Question
 * booklet's Revision Output applicability has been reviewed.
 *
 * A missing confirmation means not reviewed. No status is inferred from
 * Question classification or default output applicability.
 */
public interface BookletApplicabilityReviewRepository {

	/**
	 * Returns the IDs of explicitly reviewed booklets belonging to an Exam.
	 *
	 * @param examId persistent Exam identifier
	 * @return immutable set of reviewed booklet IDs
	 */
	Set<Long> findReviewedBookletIds(long examId);

	/**
	 * Tests whether a booklet has an explicit persisted confirmation.
	 *
	 * @param bookletId persistent booklet identifier
	 * @return true when a review confirmation exists
	 */
	boolean isReviewed(long bookletId);

	/**
	 * Records or clears a human review confirmation.
	 *
	 * Calling this method repeatedly with the same state is idempotent. Eligibility
	 * must be checked by the calling application service.
	 *
	 * @param bookletId persistent booklet identifier
	 * @param reviewed  whether the review is confirmed
	 */
	void setReviewed(long bookletId, boolean reviewed);
}
