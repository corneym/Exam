package au.edu.eq.questionbank.service.audit;

/**
 * Completion-state filter for the corpus work queue.
 */
public enum QuestionCorpusCompletionFilter {
	/** Include questions regardless of corpus completeness. */
	ALL,
	/** Include only questions with no outstanding corpus problems. */
	COMPLETE,
	/** Include only questions with at least one corpus problem. */
	INCOMPLETE
}
