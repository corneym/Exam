package au.edu.eq.questionbank.service.audit;

import java.sql.SQLException;
import java.util.List;
import java.util.Set;

import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamCaptureState;
import au.edu.eq.questionbank.repository.assessment.BookletApplicabilityReviewRepository;
import au.edu.eq.questionbank.repository.assessment.SqliteExamWriter;

/**
 * Authoritative application service for deliberate Exam lifecycle changes.
 *
 * ACTIVE-to-COMPLETE transitions require both current corpus readiness and
 * valid human applicability-review confirmations. Reactivation does not require
 * either condition.
 */
public final class ExamLifecycleService {

	private final ExamCorpusAuditService auditService;
	private final SqliteExamWriter examWriter;
	private final BookletApplicabilityReviewRepository reviewRepository;

	/**
	 * Creates the lifecycle service.
	 *
	 * @param examWriter       authoritative Exam persistence boundary
	 * @param auditService     current persisted corpus audit
	 * @param reviewRepository persisted applicability confirmations
	 */
	public ExamLifecycleService(SqliteExamWriter examWriter, ExamCorpusAuditService auditService,
			BookletApplicabilityReviewRepository reviewRepository) {
		if (examWriter == null) {
			throw new NullPointerException("examWriter");
		}
		if (auditService == null) {
			throw new NullPointerException("auditService");
		}
		if (reviewRepository == null) {
			throw new NullPointerException("reviewRepository");
		}
		this.examWriter = examWriter;
		this.auditService = auditService;
		this.reviewRepository = reviewRepository;
	}

	/**
	 * Deliberately changes an Exam's persisted lifecycle state.
	 *
	 * A transition to COMPLETE requires a fresh audit and valid confirmations for
	 * all expected Question booklets.
	 *
	 * Existing COMPLETE Exams are not reassessed or changed unless a caller
	 * explicitly requests a lifecycle transition.
	 *
	 * @param exam        persisted Exam to change
	 * @param targetState requested lifecycle state
	 * @return Exam reconstructed with its persisted replacement state
	 * @throws SQLException          if the audit or update cannot be completed
	 * @throws IllegalStateException if COMPLETE requirements are unmet
	 */
	public Exam setCaptureState(Exam exam, ExamCaptureState targetState) throws SQLException {
		if (exam == null) {
			throw new NullPointerException("exam");
		}
		if (targetState == null) {
			throw new NullPointerException("targetState");
		}
		if (targetState == ExamCaptureState.ACTIVE) {

			// Reactivation must remain available even when review
			// confirmations have been cleared or source data is missing.
			return examWriter.setExamCaptureState(exam, ExamCaptureState.ACTIVE);
		}
		ExamCorpusStatus status = findCurrentExamStatus(exam);

		// Re-evaluate authoritative persisted data; never trust an
		// old Dashboard snapshot or a caller's in-memory Exam.
		if (!status.isReadyForCompletion()) {
			throw new IllegalStateException("Exam cannot be marked COMPLETE: expected assets, "
					+ "Question counts, content, Answers, classification "
					+ "or required source material remain incomplete.");
		}
		Set<Long> reviewedBookletIds = reviewRepository.findReviewedBookletIds(exam.getId());
		if (!auditService.isApplicabilityReviewed(status, reviewedBookletIds)) {
			throw new IllegalStateException("Exam cannot be marked COMPLETE: every expected "
					+ "Question booklet must have a valid " + "Applicability Reviewed confirmation.");
		}

		// The writer performs its own persisted review-coverage check
		// within the lifecycle-update transaction.
		return examWriter.setExamCaptureState(status.exam(), ExamCaptureState.COMPLETE);
	}

	private ExamCorpusStatus findCurrentExamStatus(Exam exam) throws SQLException {
		List<ExamCorpusStatus> statuses = auditService.assessSubject(exam.getSubject());
		return statuses.stream().filter(status -> status.exam().getId() == exam.getId()).findFirst()
				.orElseThrow(() -> new IllegalArgumentException("Exam does not exist in the persisted corpus audit"));
	}
}
