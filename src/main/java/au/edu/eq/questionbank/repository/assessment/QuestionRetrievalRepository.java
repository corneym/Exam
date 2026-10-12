package au.edu.eq.questionbank.repository.assessment;

import java.util.List;

import au.edu.eq.questionbank.model.CurriculumNode;

/**
 * Persistence boundary for curriculum-aware question retrieval.
 * <p>
 * Implementations find stored questions applicable to supplied current
 * curriculum nodes. Applicability may arise from direct current classification
 * or from confirmed historical curriculum mappings.
 */
public interface QuestionRetrievalRepository {

	/**
	 * Finds persisted question applicability matches for the supplied current
	 * descriptor or subtopic nodes.
	 * <p>
	 * The same question may appear in more than one returned match when several
	 * supplied nodes apply to it. Callers must not assume that the returned list
	 * contains unique questions.
	 *
	 * @param currentNodes one or more current descriptor or subtopic nodes
	 * @return the persisted applicability matches
	 * @throws NullPointerException     if {@code currentNodes} or an element is
	 *                                  {@code null}
	 * @throws IllegalArgumentException if a supplied node is not a supported
	 *                                  current curriculum node
	 * @throws IllegalStateException    if persistence cannot be read
	 */
	List<QuestionApplicabilityMatch> findApplicableToNodes(List<CurriculumNode> currentNodes);

	/**
	 * Retrieves applicability matches with an optional diagnostic parent.
	 * <p>
	 * Implementations without instrumentation retain their existing retrieval
	 * behaviour.
	 *
	 * @param currentNodes      requested current curriculum nodes
	 * @param parentOperationId enclosing diagnostic operation ID
	 * @return persisted applicability matches
	 */
	default List<QuestionApplicabilityMatch> findApplicableToNodes(List<CurriculumNode> currentNodes,
			long parentOperationId) {
		return findApplicableToNodes(currentNodes);
	}

	/**
	 * Finds current-curriculum applicability matches for one Question.
	 *
	 * Implementations backed by persistence should constrain the Question
	 * identifier before reconstructing Question objects.
	 *
	 * The default implementation preserves compatibility with lightweight retrieval
	 * repositories used in tests.
	 *
	 * @param currentNodes      all requested current classification nodes
	 * @param questionId        persistent Question identifier
	 * @param parentOperationId enclosing diagnostic operation identifier
	 * @return matches belonging only to the requested Question
	 * @throws IllegalArgumentException if the Question ID is not positive
	 */
	default List<QuestionApplicabilityMatch> findApplicableToNodesForQuestion(List<CurriculumNode> currentNodes,
			long questionId, long parentOperationId) {
		if (questionId <= 0) {
			throw new IllegalArgumentException("questionId must be positive");
		}

		// The fallback is intentionally broad for compatibility.
		// SQLite supplies an overriding selective implementation.
		return findApplicableToNodes(currentNodes, parentOperationId).stream()
				.filter(match -> match.getQuestion().getId() == questionId).toList();
	}
}
