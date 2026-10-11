package au.edu.eq.questionbank.service.audit;

import java.sql.SQLException;
import java.util.List;
import java.util.Set;

import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.repository.assessment.BookletApplicabilityReviewRepository;

/**
 * Validates and persists deliberate human review confirmations for
 * Question-booklet Revision Output applicability.
 *
 * Read-only audit and review-eligibility checks are performed before a new
 * confirmation is persisted.
 */
public final class BookletApplicabilityReviewService {

	private final ExamCorpusAuditService auditService;
	private final BookletApplicabilityReviewRepository repository;

	/**
	 * Creates the application service.
	 *
	 * @param auditService authoritative corpus audit service
	 * @param repository   persisted booklet review confirmations
	 */
	public BookletApplicabilityReviewService(ExamCorpusAuditService auditService,
			BookletApplicabilityReviewRepository repository) {
		if (auditService == null) {
			throw new NullPointerException("auditService");
		}
		if (repository == null) {
			throw new NullPointerException("repository");
		}
		this.auditService = auditService;
		this.repository = repository;
	}

	/**
	 * Returns the currently persisted review IDs for an Exam.
	 *
	 * @param exam persisted Exam
	 * @return immutable reviewed booklet identifiers
	 */
	public Set<Long> findReviewedBookletIds(Exam exam) {
		if (exam == null) {
			throw new NullPointerException("exam");
		}
		return repository.findReviewedBookletIds(exam.getId());
	}

	/**
	 * Determines whether the specified booklet may be confirmed as reviewed.
	 *
	 * @param booklet persisted Question booklet
	 * @return whether its captured corpus is eligible
	 * @throws SQLException if its audit cannot be loaded
	 */
	public boolean isEligibleForReview(ExamBooklet booklet) throws SQLException {
		return ExamApplicabilityReview.eligibleForReview(findBookletStatus(booklet));
	}

	/**
	 * Determines whether all expected Question booklets have valid reviews.
	 *
	 * @param exam persisted Exam
	 * @return whether the Exam is fully reviewed
	 * @throws SQLException if its audit cannot be loaded
	 */
	public boolean isExamReviewed(Exam exam) throws SQLException {
		ExamCorpusStatus status = findExamStatus(exam);
		return ExamApplicabilityReview.allBookletsReviewed(status.assetExpectations(), status.bookletStatuses(),
				repository.findReviewedBookletIds(exam.getId()));
	}

	/**
	 * Records or clears an explicit review confirmation.
	 *
	 * Clearing is always permitted. Confirming requires that the booklet's
	 * persisted Question capture and classification are complete.
	 *
	 * @param booklet  persisted Question booklet
	 * @param reviewed desired confirmation state
	 * @throws SQLException          if its audit cannot be loaded
	 * @throws IllegalStateException if confirmation is not permitted
	 */
	public void setReviewed(ExamBooklet booklet, boolean reviewed) throws SQLException {
		if (booklet == null) {
			throw new NullPointerException("booklet");
		}
		if (!reviewed) {
			repository.setReviewed(booklet.getId(), false);
			return;
		}
		BookletCorpusStatus status = findBookletStatus(booklet);
		if (!ExamApplicabilityReview.eligibleForReview(status)) {
			throw new IllegalStateException("Applicability review cannot be confirmed: "
					+ "the booklet has incomplete Question capture, "
					+ "missing classification, an unavailable source PDF, " + "or another required capture item.");
		}
		repository.setReviewed(booklet.getId(), true);
	}

	private BookletCorpusStatus findBookletStatus(ExamBooklet booklet) throws SQLException {
		if (booklet == null) {
			throw new NullPointerException("booklet");
		}
		return findExamStatus(booklet.getExam()).bookletStatuses().stream()
				.filter(status -> status.booklet().getId() == booklet.getId()).findFirst()
				.orElseThrow(() -> new IllegalArgumentException("Booklet does not exist in the persisted Exam audit"));
	}

	private ExamCorpusStatus findExamStatus(Exam exam) throws SQLException {
		if (exam == null) {
			throw new NullPointerException("exam");
		}
		List<ExamCorpusStatus> statuses = auditService.assessSubject(exam.getSubject());
		return statuses.stream().filter(status -> status.exam().getId() == exam.getId()).findFirst()
				.orElseThrow(() -> new IllegalArgumentException("Exam does not exist in the persisted corpus audit"));
	}
}
