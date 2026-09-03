package au.edu.eq.questionbank.repository.assessment;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Question;

/**
 * One persisted question/current-curriculum applicability match.
 * <p>
 * A question may produce more than one match when it is applicable to several
 * searched current curriculum nodes. Higher application layers are responsible
 * for combining those matches into unique question results.
 */
public final class QuestionApplicabilityMatch {

	private final Question question;
	private final CurriculumNode currentNode;

	/**
	 * Creates one question applicability match.
	 *
	 * @param question    the stored question
	 * @param currentNode the current descriptor or subtopic to which it applies
	 * @throws NullPointerException     if either argument is {@code null}
	 * @throws IllegalArgumentException if the node is not current, is not a
	 *                                  descriptor or subtopic, or belongs to a
	 *                                  different subject
	 */
	public QuestionApplicabilityMatch(Question question, CurriculumNode currentNode) {
		if (question == null) {
			throw new NullPointerException("question");
		}
		if (currentNode == null) {
			throw new NullPointerException("currentNode");
		}
		if (!currentNode.getSyllabusVersion().isCurrent()) {
			throw new IllegalArgumentException("currentNode must belong to a current syllabus");
		}

		CurriculumLevel level = currentNode.getLevel();

		if (level != CurriculumLevel.DESCRIPTOR && level != CurriculumLevel.SUBTOPIC) {
			throw new IllegalArgumentException("currentNode must be a DESCRIPTOR or SUBTOPIC");
		}

		if (!question.getExam().getSubject().equals(currentNode.getSyllabusVersion().getSubject())) {
			throw new IllegalArgumentException("question and currentNode must belong to the same subject");
		}

		this.question = question;
		this.currentNode = currentNode;
	}

	public CurriculumNode getCurrentNode() {
		return currentNode;
	}

	public Question getQuestion() {
		return question;
	}
}