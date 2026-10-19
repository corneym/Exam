package au.edu.eq.questionbank.service.retrieval;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Question;

/**
 * One unique question returned by a curriculum-aware search.
 * <p>
 * The question retains its authoritative original classification. Current
 * applicability is exposed separately and is derived rather than stored back
 * onto the question.
 */
public final class QuestionRetrievalResult {

	private final Question question;
	private final List<CurriculumNode> currentApplicability;

	/**
	 * Creates a retrieval result containing distinct current-syllabus applicability
	 * placements for one stored Question.
	 * <p>
	 * Every placement must belong to the Question's Subject and must be a current
	 * SUBTOPIC or DESCRIPTOR. Each persistent curriculum node may occur only once.
	 * The supplied placement order is preserved.
	 * <p>
	 * Applicability is a derived retrieval snapshot. The Question's authoritative
	 * original classification is never modified.
	 *
	 * @param question             the stored Question
	 * @param currentApplicability the distinct current placements that caused the
	 *                             Question to match
	 * @throws NullPointerException     if either argument or a list element is null
	 * @throws IllegalArgumentException if the list is empty, a placement is
	 *                                  invalid, or a node ID is repeated
	 */
	public QuestionRetrievalResult(Question question, List<CurriculumNode> currentApplicability) {
		if (question == null) {
			throw new NullPointerException("question");
		}
		if (currentApplicability == null) {
			throw new NullPointerException("currentApplicability");
		}
		if (currentApplicability.isEmpty()) {
			throw new IllegalArgumentException("currentApplicability must not be empty");
		}
		Set<Long> nodeIds = new HashSet<>();
		for (CurriculumNode currentNode : currentApplicability) {
			if (currentNode == null) {
				throw new NullPointerException("currentApplicability contains null");
			}
			if (!currentNode.getSyllabusVersion().isCurrent()) {
				throw new IllegalArgumentException("currentApplicability must contain only current nodes");
			}
			CurriculumLevel level = currentNode.getLevel();
			if (level != CurriculumLevel.SUBTOPIC && level != CurriculumLevel.DESCRIPTOR) {
				throw new IllegalArgumentException(
						"currentApplicability must contain only " + "SUBTOPIC or DESCRIPTOR nodes");
			}
			if (!question.getExam().getSubject().equals(currentNode.getSyllabusVersion().getSubject())) {
				throw new IllegalArgumentException("currentApplicability must belong to " + "the Question's Subject");
			}

			// Persistent node identity, not object identity,
			// determines whether a placement is duplicated.
			if (!nodeIds.add(currentNode.getId())) {
				throw new IllegalArgumentException("Duplicate current applicability node ID: " + currentNode.getId());
			}
		}
		this.question = question;

		// Retain the caller's validated order without exposing
		// a mutable list or changing the original classification.
		this.currentApplicability = List.copyOf(currentApplicability);
	}

	/**
	 * Returns the current curriculum nodes that explain why this question matched
	 * the search.
	 *
	 * @return matching current applicability
	 */
	public List<CurriculumNode> getCurrentApplicability() {
		return currentApplicability;
	}

	/**
	 * Returns the question's authoritative stored classification.
	 *
	 * @return the original classification
	 */
	public CurriculumNode getOriginalClassification() {
		return question.getClassification();
	}

	/**
	 * Returns the stored question represented by this search result.
	 *
	 * @return question with its original classification and capture state
	 */
	public Question getQuestion() {
		return question;
	}
}
