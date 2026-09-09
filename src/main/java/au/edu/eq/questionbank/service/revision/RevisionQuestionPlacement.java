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

	public CurriculumNode getCurrentNode() {
		return currentNode;
	}

	public Question getQuestion() {
		return question;
	}

	public int getRevisionNumber() {
		if (!hasRevisionNumber()) {
			throw new IllegalStateException("Non-renderable placement has no revision number");
		}
		return revisionNumber;
	}

	public boolean hasAnswer() {
		return question.hasAnswer();
	}

	public boolean hasRevisionNumber() {
		return revisionNumber > 0;
	}

	/**
	 * Returns the historical legacy capture hint, independently of whether shared
	 * context has subsequently been linked.
	 *
	 * @return whether legacy metadata requested preamble capture
	 */
	public boolean isPreambleCaptureRequired() {
		return question.isPreambleCaptureRequired();
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
}
