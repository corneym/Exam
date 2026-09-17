package au.edu.eq.questionbank.service.curriculum;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import au.edu.eq.questionbank.model.CurriculumLevel;

/**
 * Mutable transient curriculum being prepared for validation and persistence.
 * <p>
 * This class owns stable draft identifiers and sibling display ordering. It
 * does not decide whether hierarchy relationships are legal; whole-draft
 * structural validation remains the responsibility of
 * {@link CurriculumDraftValidator}.
 */
public final class CurriculumDraft {

	/**
	 * Creates an empty authoring draft with session-local node identities.
	 */
	public CurriculumDraft() {
	}

	private final List<CurriculumDraftNode> nodes = new ArrayList<>();
	private final CurriculumDraftValidator validator = new CurriculumDraftValidator();
	private long nextDraftId = 1;

	/**
	 * Adds a node to the draft.
	 *
	 * @param level            explicitly selected curriculum level
	 * @param code             curriculum code
	 * @param name             curriculum label or descriptor text
	 * @param parentDraftId    parent draft identifier, or {@code null}
	 * @param sourcePageNumber one-based source page, or {@code null}
	 * @return the newly created draft node
	 */
	public CurriculumDraftNode addNode(CurriculumLevel level, String code, String name, Long parentDraftId,
			Integer sourcePageNumber) {
		long draftId = nextDraftId++;
		int displayOrder = siblingCount(parentDraftId);
		CurriculumDraftNode node = new CurriculumDraftNode(draftId, level, code, name, parentDraftId, displayOrder,
				sourcePageNumber);
		nodes.add(node);
		return node;
	}

	/**
	 * Returns the nodes having the supplied parent, ordered by sibling display
	 * order.
	 *
	 * @param parentDraftId parent identifier, or {@code null} for root nodes
	 * @return ordered sibling nodes
	 */
	public List<CurriculumDraftNode> childrenOf(Long parentDraftId) {
		return nodes.stream().filter(node -> Objects.equals(node.parentDraftId(), parentDraftId))
				.sorted(Comparator.comparingInt(CurriculumDraftNode::displayOrder)).toList();
	}

	/**
	 * Returns the node with the supplied draft identifier.
	 *
	 * @param draftId draft identifier
	 * @return matching node, when present
	 */
	public Optional<CurriculumDraftNode> findNode(long draftId) {
		return nodes.stream().filter(node -> node.draftId() == draftId).findFirst();
	}

	/**
	 * Moves a node one position later among siblings having the same parent.
	 *
	 * @param draftId node to move
	 * @return {@code true} if the node moved, otherwise {@code false}
	 * @throws IllegalArgumentException if the draft identifier is unknown
	 */
	public boolean moveDown(long draftId) {
		CurriculumDraftNode node = requireNode(draftId);
		List<CurriculumDraftNode> siblings = childrenOf(node.parentDraftId());
		int siblingIndex = siblingIndex(siblings, draftId);
		if (siblingIndex == siblings.size() - 1) {
			return false;
		}
		swapDisplayOrder(node, siblings.get(siblingIndex + 1));
		return true;
	}

	/**
	 * Moves a node one position earlier among siblings having the same parent.
	 *
	 * @param draftId node to move
	 * @return {@code true} if the node moved, otherwise {@code false}
	 * @throws IllegalArgumentException if the draft identifier is unknown
	 */
	public boolean moveUp(long draftId) {
		CurriculumDraftNode node = requireNode(draftId);
		List<CurriculumDraftNode> siblings = childrenOf(node.parentDraftId());
		int siblingIndex = siblingIndex(siblings, draftId);
		if (siblingIndex == 0) {
			return false;
		}
		swapDisplayOrder(siblings.get(siblingIndex - 1), node);
		return true;
	}

	/**
	 * Returns an immutable snapshot of all nodes in creation order.
	 *
	 * @return draft nodes
	 */
	public List<CurriculumDraftNode> nodes() {
		return List.copyOf(nodes);
	}

	/**
	 * Removes a node and every draft node descended from it.
	 * <p>
	 * Remaining siblings are compacted so their display orders remain contiguous.
	 * Removed draft identifiers are never reused.
	 *
	 * @param draftId root node of the subtree to remove
	 * @return removed nodes in draft creation order
	 * @throws IllegalArgumentException if the draft identifier is unknown
	 */
	public List<CurriculumDraftNode> removeSubtree(long draftId) {
		CurriculumDraftNode root = requireNode(draftId);
		Long formerParentDraftId = root.parentDraftId();
		Set<Long> idsToRemove = new HashSet<>();
		collectSubtreeIds(draftId, idsToRemove);
		List<CurriculumDraftNode> removed = nodes.stream().filter(node -> idsToRemove.contains(node.draftId()))
				.toList();
		nodes.removeIf(node -> idsToRemove.contains(node.draftId()));
		normaliseSiblingOrder(formerParentDraftId);
		return removed;
	}

	/**
	 * Replaces the editable values of an existing node while preserving its stable
	 * draft identifier.
	 * <p>
	 * If the node is assigned to a different parent, it becomes the last child of
	 * that parent and the old sibling list is compacted.
	 *
	 * @param draftId          node to update
	 * @param level            explicitly selected curriculum level
	 * @param code             curriculum code
	 * @param name             curriculum label or descriptor text
	 * @param parentDraftId    new parent identifier, or {@code null}
	 * @param sourcePageNumber one-based source page, or {@code null}
	 * @return updated node
	 * @throws IllegalArgumentException if the draft identifier is unknown
	 */
	public CurriculumDraftNode updateNode(long draftId, CurriculumLevel level, String code, String name,
			Long parentDraftId, Integer sourcePageNumber) {
		int index = indexOf(draftId);
		CurriculumDraftNode existing = nodes.get(index);
		boolean parentChanged = !Objects.equals(existing.parentDraftId(), parentDraftId);
		int displayOrder = parentChanged ? siblingCount(parentDraftId) : existing.displayOrder();
		CurriculumDraftNode updated = new CurriculumDraftNode(draftId, level, code, name, parentDraftId, displayOrder,
				sourcePageNumber);
		nodes.set(index, updated);
		if (parentChanged) {
			normaliseSiblingOrder(existing.parentDraftId());
		}
		return updated;
	}

	/**
	 * Validates the current complete draft.
	 *
	 * @return structural validation problems, or an empty list when valid
	 */
	public List<String> validationProblems() {
		return validator.validate(nodes);
	}

	private void collectSubtreeIds(long draftId, Set<Long> idsToRemove) {
		if (!idsToRemove.add(draftId)) {
			return;
		}
		for (CurriculumDraftNode node : nodes) {
			if (Objects.equals(node.parentDraftId(), draftId)) {
				collectSubtreeIds(node.draftId(), idsToRemove);
			}
		}
	}

	private int indexOf(long draftId) {
		for (int index = 0; index < nodes.size(); index++) {
			if (nodes.get(index).draftId() == draftId) {
				return index;
			}
		}
		throw new IllegalArgumentException("Unknown draft id: " + draftId);
	}

	private void normaliseSiblingOrder(Long parentDraftId) {
		List<CurriculumDraftNode> siblings = childrenOf(parentDraftId);
		for (int index = 0; index < siblings.size(); index++) {
			replaceDisplayOrder(siblings.get(index), index);
		}
	}

	private void replaceDisplayOrder(CurriculumDraftNode node, int displayOrder) {
		int index = indexOf(node.draftId());
		nodes.set(index, new CurriculumDraftNode(node.draftId(), node.level(), node.code(), node.name(),
				node.parentDraftId(), displayOrder, node.sourcePageNumber()));
	}

	private CurriculumDraftNode requireNode(long draftId) {
		return findNode(draftId).orElseThrow(() -> new IllegalArgumentException("Unknown draft id: " + draftId));
	}

	private int siblingCount(Long parentDraftId) {
		return (int) nodes.stream().filter(node -> Objects.equals(node.parentDraftId(), parentDraftId)).count();
	}

	private int siblingIndex(List<CurriculumDraftNode> siblings, long draftId) {
		for (int index = 0; index < siblings.size(); index++) {
			if (siblings.get(index).draftId() == draftId) {
				return index;
			}
		}
		throw new IllegalStateException("Draft node is absent from its sibling list: " + draftId);
	}

	private void swapDisplayOrder(CurriculumDraftNode first, CurriculumDraftNode second) {
		replaceDisplayOrder(first, second.displayOrder());
		replaceDisplayOrder(second, first.displayOrder());
	}
}
