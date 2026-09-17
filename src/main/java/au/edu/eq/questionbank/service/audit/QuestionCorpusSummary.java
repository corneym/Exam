package au.edu.eq.questionbank.service.audit;

/**
 * Aggregate completeness counts for a corpus selection.
 *
 * @param totalQuestions          total Questions assessed
 * @param completeQuestions       Questions with no completeness problems
 * @param incompleteQuestions     Questions with at least one problem
 * @param missingQuestionSource   Questions without source regions
 * @param missingAnswer           Questions whose required Answer representation
 *                                is incomplete
 * @param unresolvedSharedContext Questions with unresolved shared context
 * @param unknownResponseType     Questions whose response type remains UNKNOWN
 */
public record QuestionCorpusSummary(int totalQuestions, int completeQuestions, int incompleteQuestions,
		int missingQuestionSource, int missingAnswer, int unresolvedSharedContext, int unknownResponseType) {

	/**
	 * Creates a corpus summary whose complete and incomplete counts equal the
	 * total.
	 *
	 * @param totalQuestions          total Questions assessed
	 * @param completeQuestions       Questions with no completeness problems
	 * @param incompleteQuestions     Questions with at least one problem
	 * @param missingQuestionSource   Questions without source regions
	 * @param missingAnswer           Questions whose required Answer representation
	 *                                is incomplete
	 * @param unresolvedSharedContext Questions with unresolved shared context
	 * @param unknownResponseType     Questions whose response type remains UNKNOWN
	 */
	public QuestionCorpusSummary {
		if (totalQuestions < 0 || completeQuestions < 0 || incompleteQuestions < 0 || missingQuestionSource < 0
				|| missingAnswer < 0 || unresolvedSharedContext < 0 || unknownResponseType < 0) {
			throw new IllegalArgumentException("Corpus summary counts must not be negative");
		}
		if (completeQuestions + incompleteQuestions != totalQuestions) {
			throw new IllegalArgumentException("Complete and incomplete counts must equal totalQuestions");
		}
	}
}
