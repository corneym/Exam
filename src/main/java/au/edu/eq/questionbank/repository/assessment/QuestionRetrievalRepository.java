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
}
