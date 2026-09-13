package au.edu.eq.questionbank.service.curriculum;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import au.edu.eq.questionbank.model.CurriculumLevel;

/**
 * Applies hierarchy-derived numeric codes to a transient curriculum draft.
 * <p>
 * Draft identifiers and explicit parent relationships remain authoritative.
 * Numeric codes are derived from sibling position and may therefore change when
 * nodes are reordered or deleted.
 */
public final class CurriculumDraftNumberingService {

	/**
	 * Adds a node and assigns hierarchy-derived numeric codes to the draft.
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
		CurriculumDraftNode added = draft.addNode(level, "pending", name, parentDraftId, sourcePageNumber);
		renumber(draft);
		return draft.findNode(added.draftId()).orElseThrow();
	}

	/**
	 * Moves a node later among its siblings and renumbers the complete draft.
	 *
	 * @param draft   draft to modify
	 * @param draftId node to move
	 * @return whether the node moved
	 */
	public boolean moveDown(CurriculumDraft draft, long draftId) {
		requireDraft(draft);
		boolean moved = draft.moveDown(draftId);
		if (moved) {
			renumber(draft);
		}
		return moved;
	}

	/**
	 * Moves a node earlier among its siblings and renumbers the complete draft.
	 *
	 * @param draft   draft to modify
	 * @param draftId node to move
	 * @return whether the node moved
	 */
	public boolean moveUp(CurriculumDraft draft, long draftId) {
		requireDraft(draft);
		boolean moved = draft.moveUp(draftId);
		if (moved) {
			renumber(draft);
		}
		return moved;
	}

	/**
	 * Removes a complete subtree and renumbers the remaining draft.
	 *
	 * @param draft   draft to modify
	 * @param draftId root of subtree to remove
	 * @return removed nodes
	 */
	public List<CurriculumDraftNode> removeSubtree(CurriculumDraft draft, long draftId) {
		requireDraft(draft);
		List<CurriculumDraftNode> removed = draft.removeSubtree(draftId);
		renumber(draft);
		return removed;
	}

	/**
	 * Recalculates all hierarchy-derived numeric codes.
	 *
	 * @param draft draft to renumber
	 */
	public void renumber(CurriculumDraft draft) {
		requireDraft(draft);
		Set<Long> visited = new HashSet<>();
		List<CurriculumDraftNode> roots = draft.childrenOf(null);
		for (int index = 0; index < roots.size(); index++) {
			renumberNode(draft, roots.get(index), Integer.toString(index + 1), visited);
		}
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

	private void renumberNode(CurriculumDraft draft, CurriculumDraftNode node, String code, Set<Long> visited) {
		if (!visited.add(node.draftId())) {
			return;
		}
		CurriculumDraftNode current = draft.findNode(node.draftId()).orElseThrow();
		if (!current.code().equals(code)) {
			current = draft.updateNode(current.draftId(), current.level(), code, current.name(),
					current.parentDraftId(), current.sourcePageNumber());
		}
		List<CurriculumDraftNode> children = draft.childrenOf(current.draftId());
		for (int index = 0; index < children.size(); index++) {
			renumberNode(draft, children.get(index), code + "." + (index + 1), visited);
		}
	}

	private void requireDraft(CurriculumDraft draft) {
		if (draft == null) {
			throw new NullPointerException("draft");
		}
	}
}
