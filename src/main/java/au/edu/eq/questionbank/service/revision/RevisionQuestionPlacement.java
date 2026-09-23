package au.edu.eq.questionbank.service.revision;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Question;

/**
 * One placement of a stored question beneath a current curriculum node in a
 * revision corpus.
 */
public final class RevisionQuestionPlacement {

	private final Question question;
	private final CurriculumNode currentNode;
	private final int revisionNumber;

	RevisionQuestionPlacement(Question question, CurriculumNode currentNode, int revisionNumber) {
		if (question == null) {
			throw new NullPointerException("question");
		}
		if (currentNode == null) {
			throw new NullPointerException("currentNode");
		}

		// Zero denotes an unnumbered diagnostic placement, never a displayed question
		// number.
		boolean renderable = !question.getRegions().isEmpty();
		if (renderable && revisionNumber < 1) {
			throw new IllegalArgumentException("Renderable placement must have a positive revision number");
		}
		if (!renderable && revisionNumber != 0) {
			throw new IllegalArgumentException("Non-renderable placement must not have a revision number");
		}
		if (!currentNode.getSyllabusVersion().isCurrent()) {
			throw new IllegalArgumentException("currentNode must belong to a current syllabus");
		}
		CurriculumLevel level = currentNode.getLevel();
		if (level != CurriculumLevel.SUBTOPIC && level != CurriculumLevel.DESCRIPTOR) {
			throw new IllegalArgumentException("currentNode must be a SUBTOPIC or DESCRIPTOR");
		}
		if (!question.getExam().getSubject().equals(currentNode.getSyllabusVersion().getSubject())) {
			throw new IllegalArgumentException("Question and current node must belong to the same subject");
		}
		this.question = question;
		this.currentNode = currentNode;
		this.revisionNumber = revisionNumber;
	}

	/**
	 * Returns the current classification bucket containing this placement.
	 *
	 * @return current Subtopic or Descriptor
	 */
	public CurriculumNode getCurrentNode() {
		return currentNode;
	}

	/**
	 * Returns the stored question assigned to this revision bucket.
	 *
	 * @return question with its original source provenance
	 */
	public Question getQuestion() {
		return question;
	}

	/**
	 * Returns the generated revision number for a renderable placement.
	 *
	 * @return positive revision number
	 * @throws IllegalStateException if the placement is not renderable
	 */
	public int getRevisionNumber() {
		if (!hasRevisionNumber()) {
			throw new IllegalStateException("Non-renderable placement has no revision number");
		}
		return revisionNumber;
	}

	/**
	 * Indicates whether the placed question has an associated answer.
	 *
	 * @return true when answer text or regions are attached
	 */
	public boolean hasAnswer() {
		return question.hasAnswer();
	}

	/**
	 * Indicates whether this placement has a student-facing revision number.
	 *
	 * @return true for a numbered, renderable placement
	 */
	public boolean hasRevisionNumber() {
		return revisionNumber > 0;
	}

	/**
	 * Returns whether this question has source regions from which student-facing
	 * content can be rendered.
	 *
	 * @return {@code true} when at least one question region is available
	 */
	public boolean isRenderable() {
		return !question.getRegions().isEmpty();
	}

	/**
	 * Returns the historical legacy capture hint, independently of whether shared
	 * context has subsequently been linked.
	 *
	 * @return whether legacy metadata requested shared context capture
	 */
	public boolean issharedContextCaptureRequired() {
		return question.isSharedContextCaptureRequired();
	}
}
