package au.edu.eq.questionbank.service.retrieval;

import java.util.List;

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
	 * Creates a retrieval result.
	 *
	 * @param question             the stored question
	 * @param currentApplicability the current nodes that caused the question to
	 *                             match this search
	 * @throws NullPointerException     if either argument or a list element is
	 *                                  {@code null}
	 * @throws IllegalArgumentException if no current applicability is supplied
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
		for (CurriculumNode currentNode : currentApplicability) {
			if (currentNode == null) {
				throw new NullPointerException("currentApplicability contains null");
			}
			if (!currentNode.getSyllabusVersion().isCurrent()) {
				throw new IllegalArgumentException("currentApplicability must contain only current nodes");
			}
		}
		this.question = question;
		// Keep the search's applicability snapshot separate from the question's stored classification.
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
