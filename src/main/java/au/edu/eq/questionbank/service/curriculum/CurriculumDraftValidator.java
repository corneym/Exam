package au.edu.eq.questionbank.service.curriculum;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import au.edu.eq.questionbank.model.CurriculumLevel;

/**
 * Validates structural relationships across a complete curriculum-authoring
 * draft.
 * <p>
 * Hierarchy is determined only from explicit curriculum levels and draft-parent
 * identifiers. Curriculum-code format or depth has no role in determining the
 * structure.
 */
public final class CurriculumDraftValidator {

	/**
	 * Returns all structural problems found in the supplied draft.
	 *
	 * @param nodes draft nodes to validate
	 * @return immutable list of problems; empty when the draft is valid
	 * @throws NullPointerException if {@code nodes} is {@code null}
	 */
	public List<String> validate(List<CurriculumDraftNode> nodes) {
		if (nodes == null) {
			throw new NullPointerException("nodes");
		}
		if (nodes.isEmpty()) {
			return List.of("Curriculum draft must contain at least one node");
		}
		List<String> problems = new ArrayList<>();
		Map<Long, CurriculumDraftNode> nodesById = new HashMap<>();
		Set<String> codes = new HashSet<>();
		for (int index = 0; index < nodes.size(); index++) {
			CurriculumDraftNode node = nodes.get(index);
			if (node == null) {
				problems.add("Draft node at index " + index + " is null");
				continue;
			}
			if (nodesById.putIfAbsent(node.draftId(), node) != null) {
				problems.add("Duplicate draft id: " + node.draftId());
			}
			String normalisedCode = node.code().trim();
			if (!codes.add(normalisedCode)) {
				problems.add("Duplicate curriculum code: " + normalisedCode);
			}
		}
		for (CurriculumDraftNode node : nodes) {
			if (node == null) {
				continue;
			}
			validateParent(node, nodesById, problems);
		}
		validateHierarchyShape(nodes, nodesById, problems);
		return List.copyOf(problems);
	}

	private String description(CurriculumDraftNode node) {
		return node.level() + " " + node.code() + " (draft " + node.draftId() + ")";
	}

	private boolean isLegalParent(CurriculumLevel parentLevel, CurriculumLevel childLevel) {
		return switch (childLevel) {
		case UNIT -> false;
		case TOPIC -> parentLevel == CurriculumLevel.UNIT;
		case SUBTOPIC -> parentLevel == CurriculumLevel.TOPIC;
		case DESCRIPTOR -> parentLevel == CurriculumLevel.TOPIC || parentLevel == CurriculumLevel.SUBTOPIC;
		};
	}

	private void validateHierarchyShape(List<CurriculumDraftNode> nodes, Map<Long, CurriculumDraftNode> nodesById,
			List<String> problems) {
		boolean hasSubtopics = nodes.stream().filter(node -> node != null)
				.anyMatch(node -> node.level() == CurriculumLevel.SUBTOPIC);
		boolean hasDirectTopicDescriptors = nodes.stream().filter(node -> node != null)
				.filter(node -> node.level() == CurriculumLevel.DESCRIPTOR).anyMatch(node -> {
					if (node.parentDraftId() == null) {
						return false;
					}
					CurriculumDraftNode parent = nodesById.get(node.parentDraftId());
					return parent != null && parent.level() == CurriculumLevel.TOPIC;
				});
		if (hasSubtopics && hasDirectTopicDescriptors) {
			problems.add("Curriculum draft must not mix Subtopics with Descriptors directly under Topics");
		}
	}

	private void validateParent(CurriculumDraftNode node, Map<Long, CurriculumDraftNode> nodesById,
			List<String> problems) {
		Long parentDraftId = node.parentDraftId();
		if (node.level() == CurriculumLevel.UNIT) {
			if (parentDraftId != null) {
				problems.add(description(node) + " must not have a parent");
			}
			return;
		}
		if (parentDraftId == null) {
			problems.add(description(node) + " requires a parent");
			return;
		}
		CurriculumDraftNode parent = nodesById.get(parentDraftId);
		if (parent == null) {
			problems.add(description(node) + " references missing parent draft " + parentDraftId);
			return;
		}
		if (!isLegalParent(parent.level(), node.level())) {
			problems.add(description(node) + " cannot have parent level " + parent.level());
		}
	}
}
