package au.edu.eq.questionbank.service.revision;

import java.util.List;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumNode;

/**
 * One current curriculum node in a generated revision corpus.
 */
public final class RevisionCorpusNode {

	private final CurriculumNode curriculumNode;
	private final List<RevisionCorpusNode> children;
	private final List<RevisionQuestionPlacement> questionPlacements;

	RevisionCorpusNode(CurriculumNode curriculumNode, List<RevisionCorpusNode> children,
			List<RevisionQuestionPlacement> questionPlacements) {
		if (curriculumNode == null) {
			throw new NullPointerException("curriculumNode");
		}
		if (children == null) {
			throw new NullPointerException("children");
		}
		if (questionPlacements == null) {
			throw new NullPointerException("questionPlacements");
		}
		for (RevisionCorpusNode child : children) {
			if (child == null) {
				throw new NullPointerException("children contains null");
			}
			if (!curriculumNode.equals(child.getCurriculumNode().getParent())) {
				throw new IllegalArgumentException("Corpus child must belong to the supplied curriculum node");
			}
		}
		CurriculumLevel level = curriculumNode.getLevel();
		if (level != CurriculumLevel.SUBTOPIC && level != CurriculumLevel.DESCRIPTOR && !questionPlacements.isEmpty()) {
			throw new IllegalArgumentException("Only Subtopic and Descriptor nodes may contain question placements");
		}
		for (RevisionQuestionPlacement placement : questionPlacements) {
			if (placement == null) {
				throw new NullPointerException("questionPlacements contains null");
			}
			if (!curriculumNode.equals(placement.getCurrentNode())) {
				throw new IllegalArgumentException("Question placement belongs to another curriculum node");
			}
		}
		this.curriculumNode = curriculumNode;
		this.children = List.copyOf(children);
		this.questionPlacements = List.copyOf(questionPlacements);
	}

	public List<RevisionCorpusNode> getChildren() {
		return children;
	}

	public CurriculumNode getCurriculumNode() {
		return curriculumNode;
	}

	public List<RevisionQuestionPlacement> getQuestionPlacements() {
		return questionPlacements;
	}
}
