package au.edu.eq.questionbank.service.audit;

/**
 * Optional filters for the corpus work queue.
 * <p>
 * Null identifiers mean "all". Completion is always explicit.
 *
 * @param subjectId  subject identifier, or null
 * @param providerId provider identifier, or null
 * @param year       exam year, or null
 * @param bookletId  booklet identifier, or null
 * @param completion completion-state filter
 * @param problem    specific problem, or null for any problem
 */
public record QuestionCorpusFilter(Long subjectId, Long providerId, Integer year, Long bookletId,
		QuestionCorpusCompletionFilter completion, QuestionCorpusProblem problem) {

	/**
	 * Creates a queue filter with positive optional identifiers and an explicit
	 * completion choice.
	 *
	 * @param subjectId  subject identifier, or null
	 * @param providerId provider identifier, or null
	 * @param year       exam year, or null
	 * @param bookletId  booklet identifier, or null
	 * @param completion completion-state filter
	 * @param problem    specific problem, or null for any problem
	 */
	public QuestionCorpusFilter {
		validatePositive(subjectId, "subjectId");
		validatePositive(providerId, "providerId");
		validatePositive(bookletId, "bookletId");
		if (year != null && year < 1) {
			throw new IllegalArgumentException("year must be positive");
		}
		if (completion == null) {
			throw new NullPointerException("completion");
		}
	}

	/**
	 * Creates a filter that includes every question in the supplied corpus.
	 *
	 * @return unrestricted queue filter
	 */
	public static QuestionCorpusFilter all() {
		return new QuestionCorpusFilter(null, null, null, null, QuestionCorpusCompletionFilter.ALL, null);
	}

	boolean matches(QuestionCorpusWorkItem item) {
		if (item == null) {
			throw new NullPointerException("item");
		}

		// Every selected restriction must match; null metadata fields leave that
		// dimension unrestricted.
		var question = item.question();
		var exam = question.getExam();
		if (subjectId != null && exam.getSubject().getId() != subjectId.longValue()) {
			return false;
		}
		if (providerId != null && exam.getProvider().getId() != providerId.longValue()) {
			return false;
		}
		if (year != null && exam.getYear() != year.intValue()) {
			return false;
		}
		if (bookletId != null && question.getBooklet().getId() != bookletId.longValue()) {
			return false;
		}
		if (completion == QuestionCorpusCompletionFilter.COMPLETE && !item.status().isComplete()) {
			return false;
		}
		if (completion == QuestionCorpusCompletionFilter.INCOMPLETE && item.status().isComplete()) {
			return false;
		}

		// Apply the problem restriction together with completion, so COMPLETE plus a
		// problem matches nothing.
		if (problem != null && !item.status().hasProblem(problem)) {
			return false;
		}
		return true;
	}

	private static void validatePositive(Long value, String name) {
		if (value != null && value.longValue() < 1) {
			throw new IllegalArgumentException(name + " must be positive");
		}
	}
}
