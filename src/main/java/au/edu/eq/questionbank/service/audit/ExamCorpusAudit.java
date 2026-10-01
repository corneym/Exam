package au.edu.eq.questionbank.service.audit;

import java.util.EnumSet;
import java.util.List;

import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamAssetExpectations;

/**
 * Calculates Exam-level corpus audit state from existing booklet audit
 * snapshots and persisted asset expectations.
 */
public final class ExamCorpusAudit {

	private ExamCorpusAudit() {
	}

	/**
	 * Audits one Exam.
	 *
	 * @param exam              Exam to assess
	 * @param assetExpectations expected and currently available Exam assets
	 * @param bookletStatuses   calculated status for all persisted Question
	 *                          booklets belonging to the Exam
	 * @return calculated Exam audit status
	 */
	public static ExamCorpusStatus assess(Exam exam, ExamAssetExpectations assetExpectations,
			List<BookletCorpusStatus> bookletStatuses) {
		if (exam == null) {
			throw new NullPointerException("exam");
		}
		if (assetExpectations == null) {
			throw new NullPointerException("assetExpectations");
		}
		if (bookletStatuses == null) {
			throw new NullPointerException("bookletStatuses");
		}

		for (BookletCorpusStatus bookletStatus : bookletStatuses) {
			if (bookletStatus == null) {
				throw new NullPointerException("bookletStatuses contains null");
			}
			if (bookletStatus.booklet().getExam().getId() != exam.getId()) {
				throw new IllegalArgumentException("Booklet audit status must belong to the audited Exam");
			}
		}

		QuestionCorpusSummary questionSummary = aggregateQuestionSummary(bookletStatuses);
		McqExplanationSummary explanationSummary = aggregateMcqExplanationSummary(bookletStatuses);
		EnumSet<ExamCorpusFinding> findings = EnumSet.noneOf(ExamCorpusFinding.class);

		Integer expectedQuestionBooklets = assetExpectations.expectedQuestionBookletCount();
		if (expectedQuestionBooklets != null
				&& expectedQuestionBooklets.intValue() != assetExpectations.availableQuestionBookletCount()) {

			// Report both shortages and unexpected extras as a mismatch. Audit must not
			// guess whether the planning value or persisted assets are wrong.
			findings.add(ExamCorpusFinding.EXPECTED_QUESTION_BOOKLET_COUNT_MISMATCH);
		}

		Integer expectedAnswerFiles = assetExpectations.expectedAnswerFileCount();
		if (expectedAnswerFiles != null
				&& expectedAnswerFiles.intValue() != assetExpectations.availableAnswerFileCount()) {

			// Answer-file planning is likewise advisory and does not determine ordinary
			// Question Answer completeness.
			findings.add(ExamCorpusFinding.EXPECTED_ANSWER_FILE_COUNT_MISMATCH);
		}

		return new ExamCorpusStatus(exam, assetExpectations, bookletStatuses, questionSummary, explanationSummary,
				findings);
	}

	private static McqExplanationSummary aggregateMcqExplanationSummary(List<BookletCorpusStatus> bookletStatuses) {
		int explanationCapableBooklets = 0;
		int eligibleQuestions = 0;
		int capturedExplanations = 0;

		for (BookletCorpusStatus bookletStatus : bookletStatuses) {
			McqExplanationCoverage coverage = bookletStatus.mcqExplanationCoverage();

			// Explanation-capable booklet count remains separate from Question counts so
			// the Dashboard can distinguish asset capability from capture coverage.
			if (coverage.explanationCapable()) {
				explanationCapableBooklets++;
			}
			eligibleQuestions += coverage.eligibleQuestionCount();
			capturedExplanations += coverage.capturedExplanationCount();
		}

		return new McqExplanationSummary(explanationCapableBooklets, eligibleQuestions, capturedExplanations);
	}

	private static QuestionCorpusSummary aggregateQuestionSummary(List<BookletCorpusStatus> bookletStatuses) {
		int totalQuestions = 0;
		int completeQuestions = 0;
		int incompleteQuestions = 0;
		int missingQuestionContent = 0;
		int missingAnswer = 0;
		int unresolvedSharedContext = 0;
		int unknownResponseType = 0;

		for (BookletCorpusStatus bookletStatus : bookletStatuses) {
			QuestionCorpusSummary summary = bookletStatus.questionSummary();

			// Booklets partition an Exam's Questions, so their independent audit totals
			// can be summed without reassessing Question completeness.
			totalQuestions += summary.totalQuestions();
			completeQuestions += summary.completeQuestions();
			incompleteQuestions += summary.incompleteQuestions();
			missingQuestionContent += summary.missingQuestionContent();
			missingAnswer += summary.missingAnswer();
			unresolvedSharedContext += summary.unresolvedSharedContext();
			unknownResponseType += summary.unknownResponseType();
		}

		return new QuestionCorpusSummary(totalQuestions, completeQuestions, incompleteQuestions, missingQuestionContent,
				missingAnswer, unresolvedSharedContext, unknownResponseType);
	}
}
