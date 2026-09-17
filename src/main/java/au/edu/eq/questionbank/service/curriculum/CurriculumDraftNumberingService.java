package au.edu.eq.questionbank.service.curriculum;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import au.edu.eq.questionbank.model.CurriculumLevel;

/**
 * Applies automatic hierarchical numeric codes to newly created curriculum
 * draft nodes.
 * <p>
 * Existing node codes remain stable during ordinary editing. Moving or deleting
 * nodes changes draft ordering or membership without renumbering surviving
 * nodes. When a new node is created, the lowest available positive child number
 * beneath its parent is assigned.
 * <p>
 * Node level and parent relationships remain explicit curriculum-authoring
 * decisions. A curriculum code does not determine whether a node is a Unit,
 * Topic, Subtopic or Descriptor.
 */
public final class CurriculumDraftNumberingService {

	/**
	 * Adds a node and assigns the lowest available hierarchical code beneath its
	 * explicit parent.
	 *
	 * @param draft            draft to modify
	 * @param level            explicitly selected curriculum level
	 * @param name             curriculum label or descriptor text
	 * @param parentDraftId    explicit parent draft id, or {@code null}
	 * @param sourcePageNumber one-based source PDF page, or {@code null}
	 * @return the newly created node after numbering
	 */
	public CurriculumDraftNode addNode(CurriculumDraft draft, CurriculumLevel level, String name, Long parentDraftId,
			Integer sourcePageNumber) {
		requireDraft(draft);
		String code = nextAvailableCode(draft, parentDraftId);
		return draft.addNode(level, code, name, parentDraftId, sourcePageNumber);
	}

	/**
	 * Moves a node later among its siblings without changing curriculum codes.
	 *
	 * @param draft   draft to modify
	 * @param draftId node to move
	 * @return whether the node moved
	 */
	public boolean moveDown(CurriculumDraft draft, long draftId) {
		requireDraft(draft);
		return draft.moveDown(draftId);
	}

	/**
	 * Moves a node earlier among its siblings without changing curriculum codes.
	 *
	 * @param draft   draft to modify
	 * @param draftId node to move
	 * @return whether the node moved
	 */
	public boolean moveUp(CurriculumDraft draft, long draftId) {
		requireDraft(draft);
		return draft.moveUp(draftId);
	}

	/**
	 * Removes a node and its descendants without renumbering surviving nodes.
	 *
	 * @param draft   draft to modify
	 * @param draftId root of subtree to remove
	 * @return removed nodes
	 */
	public List<CurriculumDraftNode> removeSubtree(CurriculumDraft draft, long draftId) {
		requireDraft(draft);
		return draft.removeSubtree(draftId);
	}

	/**
	 * Replaces only a node's authored text while preserving hierarchy, level,
	 * ordering, source page and stable draft identifier.
	 *
	 * @param draft   draft to modify
	 * @param draftId node to update
	 * @param name    replacement text
	 * @return updated node
	 */
	public CurriculumDraftNode updateText(CurriculumDraft draft, long draftId, String name) {
		requireDraft(draft);
		CurriculumDraftNode existing = draft.findNode(draftId)
				.orElseThrow(() -> new IllegalArgumentException("Unknown draft id: " + draftId));
		return draft.updateNode(existing.draftId(), existing.level(), existing.code(), name, existing.parentDraftId(),
				existing.sourcePageNumber());
	}

	private String nextAvailableCode(CurriculumDraft draft, Long parentDraftId) {
		String prefix;
		if (parentDraftId == null) {
			prefix = "";
		} else {
			CurriculumDraftNode parent = draft.findNode(parentDraftId)
					.orElseThrow(() -> new IllegalArgumentException("Unknown parent draft id: " + parentDraftId));
			prefix = parent.code() + ".";
		}
		Set<String> existingCodes = new HashSet<>();
		for (CurriculumDraftNode node : draft.nodes()) {
			existingCodes.add(node.code());
		}
		int candidateNumber = 1;
		String candidate = prefix + candidateNumber;
		while (existingCodes.contains(candidate)) {
			candidateNumber++;
			candidate = prefix + candidateNumber;
		}
		return candidate;
	}

	private void requireDraft(CurriculumDraft draft) {
		if (draft == null) {
			throw new NullPointerException("draft");
		}
	}
}
