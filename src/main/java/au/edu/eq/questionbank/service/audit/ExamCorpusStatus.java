package au.edu.eq.questionbank.service.audit;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamAssetExpectations;
import au.edu.eq.questionbank.model.ExamCaptureState;

/**
 * Calculated audit state for one Exam.
 *
 * @param exam                  audited Exam
 * @param assetExpectations     expected and currently persisted Exam assets
 * @param bookletStatuses       calculated status for every persisted Question
 *                              booklet
 * @param questionSummary       aggregate Question-level completeness across the
 *                              Exam
 * @param mcqExplanationSummary optional MCQ explanation coverage
 * @param findings              Exam-level advisory findings
 */
public record ExamCorpusStatus(Exam exam, ExamAssetExpectations assetExpectations,
		List<BookletCorpusStatus> bookletStatuses, QuestionCorpusSummary questionSummary,
		McqExplanationSummary mcqExplanationSummary, Set<ExamCorpusFinding> findings) {

	/**
	 * Creates an immutable Exam audit snapshot.
	 */
	public ExamCorpusStatus {
		if (exam == null) {
			throw new NullPointerException("exam");
		}
		if (assetExpectations == null) {
			throw new NullPointerException("assetExpectations");
		}
		if (bookletStatuses == null) {
			throw new NullPointerException("bookletStatuses");
		}
		if (questionSummary == null) {
			throw new NullPointerException("questionSummary");
		}
		if (mcqExplanationSummary == null) {
			throw new NullPointerException("mcqExplanationSummary");
		}
		if (findings == null) {
			throw new NullPointerException("findings");
		}
		Set<Long> bookletIds = new HashSet<>();
		int bookletQuestionCount = 0;
		int explanationCapableBooklets = 0;
		int eligibleExplanations = 0;
		int capturedExplanations = 0;
		for (BookletCorpusStatus bookletStatus : bookletStatuses) {
			if (bookletStatus == null) {
				throw new NullPointerException("bookletStatuses contains null");
			}
			if (bookletStatus.booklet().getExam().getId() != exam.getId()) {
				throw new IllegalArgumentException("Booklet audit status must belong to the audited Exam");
			}
			if (!bookletIds.add(bookletStatus.booklet().getId())) {
				throw new IllegalArgumentException("Duplicate booklet audit status");
			}

			// Every Question represented by the Exam summary must come from exactly one
			// audited booklet.
			bookletQuestionCount += bookletStatus.questionPartCount();
			McqExplanationCoverage coverage = bookletStatus.mcqExplanationCoverage();
			if (coverage.explanationCapable()) {
				explanationCapableBooklets++;
			}
			eligibleExplanations += coverage.eligibleQuestionCount();
			capturedExplanations += coverage.capturedExplanationCount();
		}
		if (assetExpectations.availableQuestionBookletCount() != bookletStatuses.size()) {
			throw new IllegalArgumentException(
					"Available Question-booklet count must equal supplied booklet audit statuses");
		}
		if (questionSummary.totalQuestions() != bookletQuestionCount) {
			throw new IllegalArgumentException(
					"Exam Question summary total must equal the combined booklet Question count");
		}
		if (mcqExplanationSummary.explanationCapableBookletCount() != explanationCapableBooklets
				|| mcqExplanationSummary.eligibleQuestionCount() != eligibleExplanations
				|| mcqExplanationSummary.capturedExplanationCount() != capturedExplanations) {
			throw new IllegalArgumentException("Exam MCQ explanation summary must equal combined booklet coverage");
		}

		// Prevent callers from mutating an already-calculated Dashboard snapshot.
		bookletStatuses = List.copyOf(bookletStatuses);
		findings = Set.copyOf(findings);
	}

	/**
	 * Returns whether this Exam has enough authoritative corpus structure,
	 * classification and content to be deliberately marked COMPLETE.
	 * <p>
	 * Completion requires recorded Exam-level asset expectations, matching
	 * available assets, recorded and satisfied top-level Question expectations for
	 * every booklet, available Question PDFs, descriptor-level classification for
	 * every Question and no remaining ordinary Question work. Optional MCQ
	 * explanation coverage does not affect lifecycle readiness.
	 *
	 * @return whether the audited Exam is ready to be marked COMPLETE
	 */
	public boolean isReadyForCompletion() {
		Integer expectedQuestionBooklets = assetExpectations.expectedQuestionBookletCount();
		Integer expectedAnswerFiles = assetExpectations.expectedAnswerFileCount();

		// Completion is a deliberate assertion about a known Exam structure. Missing
		// planning counts must not be interpreted as successful zero-defect counts.
		if (expectedQuestionBooklets == null || expectedAnswerFiles == null) {
			return false;
		}
		if (expectedQuestionBooklets.intValue() != assetExpectations.availableQuestionBookletCount()
				|| expectedAnswerFiles.intValue() != assetExpectations.availableAnswerFileCount()) {
			return false;
		}

		// Missing Question content, Answers, Shared Context or response type all keep
		// the Exam operationally incomplete.
		if (questionSummary.incompleteQuestions() != 0) {
			return false;
		}
		for (BookletCorpusStatus bookletStatus : bookletStatuses) {

			// A Question classified only to Subtopic cannot yet participate reliably in
			// descriptor-level curriculum mapping, so the Exam is not complete.
			if (bookletStatus.questionsWithoutDescriptorCount() != 0) {
				return false;
			}
			Integer expectedQuestions = bookletStatus.expectedTopLevelQuestionCount();

			// Every declared Question booklet must still have its authoritative source and
			// an explicit Question-count expectation that matches the encountered source.
			if (!bookletStatus.questionPdfAvailable() || expectedQuestions == null
					|| expectedQuestions.intValue() != bookletStatus.encounteredTopLevelQuestionCount()) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Returns the user-declared lifecycle state independently of audit findings.
	 *
	 * @return persisted Exam capture state
	 */
	public ExamCaptureState declaredCaptureState() {

		// ACTIVE/COMPLETE remains explicit user state rather than a derived audit
		// conclusion.
		return exam.getCaptureState();
	}

	/**
	 * Tests whether an Exam-level advisory finding is present.
	 *
	 * @param finding finding to inspect
	 * @return whether the finding is present
	 */
	public boolean hasFinding(ExamCorpusFinding finding) {
		if (finding == null) {
			throw new NullPointerException("finding");
		}

		// Exam findings cover Exam-wide structural observations rather than individual
		// booklet or Question problems.
		return findings.contains(finding);
	}

	/**
	 * Returns whether any calculated audit work requires attention.
	 *
	 * @return whether Exam, booklet or Question findings exist
	 */
	public boolean requiresAttention() {

		// MCQ explanation coverage is deliberately excluded. A valid A-D Answer is
		// complete even when optional explanation regions have not been captured.
		return !findings.isEmpty() || bookletStatuses.stream().anyMatch(BookletCorpusStatus::requiresAttention);
	}
}
