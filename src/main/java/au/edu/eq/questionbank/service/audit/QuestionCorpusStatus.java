package au.edu.eq.questionbank.service.audit;

import java.util.Set;

/**
 * Completeness state for one Question in the corpus.
 *
 * @param questionSourceCaptured whether at least one Question source region
 *                               exists
 * @param responseTypeResolved   whether the response type is authoritative
 * @param answerComplete         whether the required Answer representation
 *                               exists
 * @param sharedContextResolved  whether any required shared context has been
 *                               resolved
 * @param problems               current corpus problems
 */
public record QuestionCorpusStatus(boolean questionSourceCaptured, boolean responseTypeResolved, boolean answerComplete,
		boolean sharedContextResolved, Set<QuestionCorpusProblem> problems) {

	/**
	 * Creates a completeness snapshot with an immutable set of reported problems.
	 *
	 * @param questionSourceCaptured whether at least one Question source region
	 *                               exists
	 * @param responseTypeResolved   whether the response type is authoritative
	 * @param answerComplete         whether the required Answer representation
	 *                               exists
	 * @param sharedContextResolved  whether any required shared context has been
	 *                               resolved
	 * @param problems               current corpus problems
	 */
	public QuestionCorpusStatus {
		if (problems == null) {
			throw new NullPointerException("problems");
		}
		problems = Set.copyOf(problems);
	}

	/**
	 * Indicates whether the audit found no outstanding corpus problems.
	 *
	 * @return whether no corpus-completeness problems remain
	 */
	public boolean isComplete() {
		return problems.isEmpty();
	}

	/**
	 * Tests whether a specific corpus problem was reported.
	 *
	 * @param problem problem to test
	 * @return whether this status contains the problem
	 */
	public boolean hasProblem(QuestionCorpusProblem problem) {
		if (problem == null) {
			throw new NullPointerException("problem");
		}
		return problems.contains(problem);
	}
}
