package au.edu.eq.questionbank.service.retrieval;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.repository.curriculum.CurriculumRepository;

/**
 * Expands a current curriculum search scope into the current classification
 * nodes that should participate in question retrieval.
 * <p>
 * Questions may be classified only at subtopic or descriptor level. Unit and
 * topic searches therefore expand downwards without inventing unit or topic
 * applicability for matching questions.
 */
public final class CurriculumSearchNodeExpansionService {

	private final CurriculumRepository curriculumRepository;

	/**
	 * Creates a current-curriculum search-scope expansion service.
	 *
	 * @param curriculumRepository curriculum hierarchy lookup
	 * @throws NullPointerException if {@code curriculumRepository} is {@code null}
	 */
	public CurriculumSearchNodeExpansionService(CurriculumRepository curriculumRepository) {
		if (curriculumRepository == null) {
			throw new NullPointerException("curriculumRepository");
		}
		this.curriculumRepository = curriculumRepository;
	}

	/**
	 * Expands a current Unit, Topic, Subtopic or Descriptor search scope into the
	 * Subtopic and Descriptor nodes that can participate in question retrieval.
	 *
	 * @param currentNode current curriculum search node
	 * @return question-classifiable nodes within the scope, in hierarchy order
	 * @throws NullPointerException     if {@code currentNode} is {@code null}
	 * @throws IllegalArgumentException if the node is historical or unsupported
	 * @throws IllegalStateException    if the hierarchy is invalid
	 */
	public List<CurriculumNode> expandSearchNode(CurriculumNode currentNode) {
		validateSearchNode(currentNode);
		List<CurriculumNode> expandedNodes = new ArrayList<CurriculumNode>();
		Set<Long> visitedNodeIds = new HashSet<Long>();
		collectApplicableNodes(currentNode, expandedNodes, visitedNodeIds);
		return List.copyOf(expandedNodes);
	}

	/**
	 * Expands a Subject into all question-classifiable nodes in its current
	 * syllabus.
	 *
	 * @param subject subject to expand
	 * @return current Subtopic and Descriptor retrieval nodes across all Units
	 * @throws NullPointerException  if {@code subject} is {@code null}
	 * @throws IllegalStateException if the subject has multiple current syllabuses
	 *                               or the hierarchy is invalid
	 */
	public List<CurriculumNode> expandSearchSubject(Subject subject) {
		if (subject == null) {
			throw new NullPointerException("subject");
		}
		SyllabusVersion currentVersion = findCurrentVersion(subject);
		if (currentVersion == null) {

			// A subject without a current syllabus has no current classification scope to
			// search.
			return List.of();
		}
		List<CurriculumNode> rootNodes = curriculumRepository.findRootNodes(currentVersion);
		if (rootNodes == null) {
			throw new IllegalStateException("Curriculum repository returned null root nodes");
		}
		List<CurriculumNode> expandedNodes = new ArrayList<CurriculumNode>();

		// Share the visited set across roots to detect nodes repeated in separate
		// branches.
		Set<Long> visitedNodeIds = new HashSet<Long>();
		for (CurriculumNode rootNode : rootNodes) {
			if (rootNode == null) {
				throw new IllegalStateException("Curriculum repository returned a null root node");
			}
			if (rootNode.getLevel() != CurriculumLevel.UNIT) {
				throw new IllegalStateException("Current syllabus root nodes must be UNIT nodes");
			}
			if (!currentVersion.equals(rootNode.getSyllabusVersion())) {
				throw new IllegalStateException("Root node belongs to another syllabus version");
			}
			collectApplicableNodes(rootNode, expandedNodes, visitedNodeIds);
		}
		return List.copyOf(expandedNodes);
	}

	private SyllabusVersion findCurrentVersion(Subject subject) {
		List<SyllabusVersion> versions = curriculumRepository.findVersionsForSubject(subject);
		if (versions == null) {
			throw new IllegalStateException("Curriculum repository returned null syllabus versions");
		}

		// Inspect every version: selecting the first current one would hide ambiguous
		// stored state.
		SyllabusVersion currentVersion = null;
		for (SyllabusVersion version : versions) {
			if (version == null) {
				throw new IllegalStateException("Curriculum repository returned a null syllabus version");
			}
			if (!subject.equals(version.getSubject())) {
				throw new IllegalStateException("Syllabus version belongs to another subject");
			}
			if (!version.isCurrent()) {
				continue;
			}
			if (currentVersion != null) {
				throw new IllegalStateException("Subject has multiple current syllabus versions");
			}
			currentVersion = version;
		}
		return currentVersion;
	}

	private void collectApplicableNodes(CurriculumNode currentNode, List<CurriculumNode> expandedNodes,
			Set<Long> visitedNodeIds) {
		if (!visitedNodeIds.add(currentNode.getId())) {
			throw new IllegalStateException("Curriculum hierarchy contains a duplicate or cycle");
		}
		CurriculumLevel level = currentNode.getLevel();
		if (level == CurriculumLevel.DESCRIPTOR) {
			expandedNodes.add(currentNode);
			return;
		}
		if (level == CurriculumLevel.SUBTOPIC) {

			// Questions can be classified at the Subtopic itself as well as at its
			// Descriptors.
			expandedNodes.add(currentNode);
			collectSubtopicChildren(currentNode, expandedNodes, visitedNodeIds);
			return;
		}
		if (level == CurriculumLevel.TOPIC) {
			collectTopicChildren(currentNode, expandedNodes, visitedNodeIds);
			return;
		}
		if (level == CurriculumLevel.UNIT) {
			collectUnitChildren(currentNode, expandedNodes, visitedNodeIds);
			return;
		}
		throw new IllegalStateException("Unsupported curriculum level");
	}

	private void collectSubtopicChildren(CurriculumNode subtopic, List<CurriculumNode> expandedNodes,
			Set<Long> visitedNodeIds) {
		List<CurriculumNode> children = findChildren(subtopic);
		for (CurriculumNode child : children) {
			validateCommonChild(subtopic, child);
			if (child.getLevel() != CurriculumLevel.DESCRIPTOR) {
				throw new IllegalStateException("Subtopic children must be DESCRIPTOR nodes");
			}
			collectApplicableNodes(child, expandedNodes, visitedNodeIds);
		}
	}

	private void collectTopicChildren(CurriculumNode topic, List<CurriculumNode> expandedNodes,
			Set<Long> visitedNodeIds) {
		List<CurriculumNode> children = findChildren(topic);

		// Support Topics with direct Descriptors, but reject mixed child levels within
		// one Topic.
		CurriculumLevel childMode = null;
		for (CurriculumNode child : children) {
			validateCommonChild(topic, child);
			CurriculumLevel childLevel = child.getLevel();
			if (childLevel != CurriculumLevel.SUBTOPIC && childLevel != CurriculumLevel.DESCRIPTOR) {
				throw new IllegalStateException("Topic children must be SUBTOPIC or DESCRIPTOR nodes");
			}
			if (childMode == null) {
				childMode = childLevel;
			} else if (childMode != childLevel) {
				throw new IllegalStateException(
						"Topic must contain either SUBTOPIC children or DESCRIPTOR children, not both");
			}
			collectApplicableNodes(child, expandedNodes, visitedNodeIds);
		}
	}

	private void collectUnitChildren(CurriculumNode unit, List<CurriculumNode> expandedNodes,
			Set<Long> visitedNodeIds) {
		List<CurriculumNode> children = findChildren(unit);
		for (CurriculumNode child : children) {
			validateCommonChild(unit, child);
			if (child.getLevel() != CurriculumLevel.TOPIC) {
				throw new IllegalStateException("Unit children must be TOPIC nodes");
			}
			collectApplicableNodes(child, expandedNodes, visitedNodeIds);
		}
	}

	private List<CurriculumNode> findChildren(CurriculumNode parent) {
		List<CurriculumNode> children = curriculumRepository.findChildren(parent);
		if (children == null) {
			throw new IllegalStateException("Curriculum repository returned null children");
		}
		return children;
	}

	private void validateCommonChild(CurriculumNode parent, CurriculumNode child) {
		if (child == null) {
			throw new IllegalStateException("Curriculum repository returned a null child");
		}
		if (!parent.equals(child.getParent())) {
			throw new IllegalStateException("Curriculum repository returned a child of another node");
		}
		if (!parent.getSyllabusVersion().equals(child.getSyllabusVersion())) {
			throw new IllegalStateException("Curriculum child must belong to the same syllabus version");
		}
		if (!child.getSyllabusVersion().isCurrent()) {
			throw new IllegalStateException("Curriculum child must belong to a current syllabus");
		}
	}

	private void validateSearchNode(CurriculumNode currentNode) {
		if (currentNode == null) {
			throw new NullPointerException("currentNode");
		}
		if (!currentNode.getSyllabusVersion().isCurrent()) {
			throw new IllegalArgumentException("Search node must belong to a current syllabus");
		}
		CurriculumLevel level = currentNode.getLevel();
		if (level != CurriculumLevel.UNIT && level != CurriculumLevel.TOPIC && level != CurriculumLevel.SUBTOPIC
				&& level != CurriculumLevel.DESCRIPTOR) {
			throw new IllegalArgumentException("Unsupported curriculum search level");
		}
	}
}
