package au.edu.eq.questionbank.service.retrieval;

import java.util.ArrayList;
import java.util.List;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.repository.curriculum.CurriculumRepository;

/**
 * Expands a current curriculum search node into the current nodes that should
 * participate in question retrieval.
 * <p>
 * Descriptor searches are exact. Subtopic searches include the subtopic itself
 * and its descriptor children.
 */
public final class CurriculumSearchNodeExpansionService {

	private final CurriculumRepository curriculumRepository;

	public CurriculumSearchNodeExpansionService(CurriculumRepository curriculumRepository) {
		if (curriculumRepository == null) {
			throw new NullPointerException("curriculumRepository");
		}

		this.curriculumRepository = curriculumRepository;
	}

	/**
	 * Expands a supported current search node.
	 *
	 * @param currentNode the current descriptor or subtopic being searched
	 * @return nodes that should participate in retrieval, in deterministic order
	 * @throws NullPointerException     if {@code currentNode} is {@code null}
	 * @throws IllegalArgumentException if the node is not current or its level is
	 *                                  not supported
	 * @throws IllegalStateException    if the curriculum repository returns an
	 *                                  invalid subtopic hierarchy
	 */
	public List<CurriculumNode> expandSearchNode(CurriculumNode currentNode) {
		validateSearchNode(currentNode);

		if (currentNode.getLevel() == CurriculumLevel.DESCRIPTOR) {
			return List.of(currentNode);
		}

		List<CurriculumNode> expandedNodes = new ArrayList<CurriculumNode>();
		expandedNodes.add(currentNode);

		List<CurriculumNode> children = curriculumRepository.findChildren(currentNode);

		if (children == null) {
			throw new IllegalStateException("Curriculum repository returned null children");
		}

		for (CurriculumNode child : children) {
			if (child == null) {
				throw new IllegalStateException("Curriculum repository returned a null child");
			}
			if (child.getLevel() != CurriculumLevel.DESCRIPTOR) {
				throw new IllegalStateException("Subtopic child must be a DESCRIPTOR");
			}
			if (!currentNode.equals(child.getParent())) {
				throw new IllegalStateException("Curriculum repository returned a child of another node");
			}
			if (!currentNode.getSyllabusVersion().equals(child.getSyllabusVersion())) {
				throw new IllegalStateException("Subtopic child must belong to the same syllabus version");
			}
			if (!child.getSyllabusVersion().isCurrent()) {
				throw new IllegalStateException("Subtopic child must belong to a current syllabus");
			}

			expandedNodes.add(child);
		}

		return List.copyOf(expandedNodes);
	}

	private void validateSearchNode(CurriculumNode currentNode) {
		if (currentNode == null) {
			throw new NullPointerException("currentNode");
		}
		if (!currentNode.getSyllabusVersion().isCurrent()) {
			throw new IllegalArgumentException("Search node must belong to a current syllabus");
		}

		CurriculumLevel level = currentNode.getLevel();

		if (level != CurriculumLevel.DESCRIPTOR && level != CurriculumLevel.SUBTOPIC) {
			throw new IllegalArgumentException("Search node must currently be a DESCRIPTOR or SUBTOPIC");
		}
	}
}
