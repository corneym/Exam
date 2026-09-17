package au.edu.eq.questionbank.service.revision;

import java.util.List;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumNode;

/**
 * One current curriculum node in a revision presentation plan.
 */
public final class RevisionPresentationNode {

	private final CurriculumNode curriculumNode;
	private final List<RevisionPresentationNode> children;
	private final List<RevisionQuestionPresentation> presentations;

	RevisionPresentationNode(CurriculumNode curriculumNode, List<RevisionPresentationNode> children,
			List<RevisionQuestionPresentation> presentations) {
		if (curriculumNode == null) {
			throw new NullPointerException("curriculumNode");
		}
		if (children == null) {
			throw new NullPointerException("children");
		}
		if (presentations == null) {
			throw new NullPointerException("presentations");
		}
		for (RevisionPresentationNode child : children) {
			if (child == null) {
				throw new NullPointerException("children contains null");
			}
			if (!curriculumNode.equals(child.getCurriculumNode().getParent())) {
				throw new IllegalArgumentException("Presentation child belongs to another curriculum node");
			}
		}
		CurriculumLevel level = curriculumNode.getLevel();
		if (level != CurriculumLevel.SUBTOPIC && level != CurriculumLevel.DESCRIPTOR && !presentations.isEmpty()) {
			throw new IllegalArgumentException("Only Subtopic and Descriptor nodes may contain presentations");
		}
		for (RevisionQuestionPresentation presentation : presentations) {
			if (presentation == null) {
				throw new NullPointerException("presentations contains null");
			}
			if (!curriculumNode.equals(presentation.getCurrentNode())) {
				throw new IllegalArgumentException("Presentation belongs to another curriculum node");
			}
		}
		this.curriculumNode = curriculumNode;
		this.children = List.copyOf(children);
		this.presentations = List.copyOf(presentations);
	}

	/**
	 * Returns the next curriculum level in this presentation branch.
	 *
	 * @return immutable ordered child nodes
	 */
	public List<RevisionPresentationNode> getChildren() {
		return children;
	}

	/**
	 * Returns the current syllabus node represented by this presentation branch.
	 *
	 * @return underlying curriculum node
	 */
	public CurriculumNode getCurriculumNode() {
		return curriculumNode;
	}

	/**
	 * Returns student-facing questions assigned directly to this node.
	 *
	 * @return immutable ordered presentations, excluding descendants
	 */
	public List<RevisionQuestionPresentation> getPresentations() {
		return presentations;
	}
}
