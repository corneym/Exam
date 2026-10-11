package au.edu.eq.questionbank.service.audit;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import au.edu.eq.questionbank.model.ExamAssetExpectations;

/**
 * Determines whether every required Question booklet in an Exam has a valid,
 * explicit Revision Output applicability review.
 *
 * Review confirmations are supplied by persistence. They must not be inferred
 * from default Include/Exclude decisions.
 */
public final class ExamApplicabilityReview {

	private ExamApplicabilityReview() {
	}

	/**
	 * Determines whether every expected Question booklet has been reviewed and
	 * remains eligible for that review confirmation.
	 *
	 * @param expectations       authoritative Exam asset expectations
	 * @param booklets           calculated statuses of persisted Question booklets
	 * @param reviewedBookletIds IDs with valid, explicit review confirmations
	 * @return true only when all expected booklets are validly reviewed
	 */
	public static boolean allBookletsReviewed(ExamAssetExpectations expectations, List<BookletCorpusStatus> booklets,
			Set<Long> reviewedBookletIds) {
		if (expectations == null) {
			throw new NullPointerException("expectations");
		}
		if (booklets == null) {
			throw new NullPointerException("booklets");
		}
		if (reviewedBookletIds == null) {
			throw new NullPointerException("reviewedBookletIds");
		}
		Integer expectedCount = expectations.expectedQuestionBookletCount();

		// Unknown or mismatched Exam structure cannot be considered reviewed.
		if (expectedCount == null || expectedCount.intValue() != expectations.availableQuestionBookletCount()
				|| expectedCount.intValue() != booklets.size() || booklets.isEmpty()) {
			return false;
		}
		Set<Long> encounteredIds = new HashSet<>();
		for (BookletCorpusStatus status : booklets) {
			if (status == null) {
				throw new NullPointerException("booklets contains null");
			}
			long bookletId = status.booklet().getId();

			// Duplicate identities must not satisfy the expected booklet count.
			if (!encounteredIds.add(bookletId)) {
				return false;
			}
			if (!reviewedBookletIds.contains(bookletId)) {
				return false;
			}
			if (!eligibleForReview(status)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Determines whether a booklet has sufficient completed capture and
	 * classification to permit a human applicability-review confirmation.
	 *
	 * @param status authoritative booklet audit status
	 * @return true when the booklet is eligible for review
	 */
	public static boolean eligibleForReview(BookletCorpusStatus status) {
		if (status == null) {
			throw new NullPointerException("status");
		}
		Integer expectedQuestions = status.expectedTopLevelQuestionCount();

		// Do not attest to a booklet whose source structure is incomplete.
		if (!status.questionPdfAvailable() || expectedQuestions == null
				|| expectedQuestions.intValue() != status.encounteredTopLevelQuestionCount()) {
			return false;
		}

		// Every captured Question must have complete ordinary content and
		// reach descriptor-level classification before review.
		if (status.questionSummary().incompleteQuestions() != 0 || status.questionsWithoutDescriptorCount() != 0) {
			return false;
		}

		// A declared explanation requirement is also outstanding booklet work.
		if (status.mcqExplanationCoverage().missingExplanationCount() != 0) {
			return false;
		}
		return true;
	}
}
