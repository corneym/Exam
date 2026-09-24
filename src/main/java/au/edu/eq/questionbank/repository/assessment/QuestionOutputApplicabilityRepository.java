package au.edu.eq.questionbank.repository.assessment;

import java.util.Set;

import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Question;

/**
 * Persists explicit exceptions to a Question's derived current-curriculum
 * output applicability.
 * <p>
 * Absence of an exclusion means that normal curriculum retrieval determines
 * applicability. An exclusion suppresses only one Question/current-node
 * placement and does not alter curriculum mappings or the Question's stored
 * historical classification.
 */
public interface QuestionOutputApplicabilityRepository {

	/**
	 * Returns the current curriculum node identifiers from which the supplied
	 * Question has been explicitly excluded.
	 *
	 * @param question persisted Question whose exclusions are required
	 * @return immutable excluded current curriculum node identifiers
	 * @throws NullPointerException     if {@code question} is {@code null}
	 * @throws IllegalArgumentException if the Question has no persistent identifier
	 * @throws IllegalStateException    if persisted exclusions cannot be read
	 */
	Set<Long> findExcludedCurrentNodeIds(Question question);

	/**
	 * Adds or removes one explicit output exclusion.
	 * <p>
	 * The curriculum node must belong to the Question's Subject, must belong to a
	 * current syllabus, and must be a Subtopic or Descriptor because only those
	 * levels can receive Question placements.
	 *
	 * @param question    persisted Question whose output applicability is changing
	 * @param currentNode current Subtopic or Descriptor
	 * @param excluded    {@code true} to exclude the placement; {@code false} to
	 *                    restore ordinary derived applicability
	 * @throws NullPointerException     if {@code question} or {@code currentNode}
	 *                                  is {@code null}
	 * @throws IllegalArgumentException if either object is not valid for an output
	 *                                  exclusion
	 * @throws IllegalStateException    if the exclusion cannot be persisted
	 */
	void setExcluded(Question question, CurriculumNode currentNode, boolean excluded);
}
