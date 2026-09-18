package au.edu.eq.questionbank.service.curriculum;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.repository.curriculum.CurriculumAuthoringRepository;
import au.edu.eq.questionbank.repository.curriculum.PersistedCurriculumNode;

/**
 * Reconstructs an editable transient draft from persisted curriculum data.
 */
public final class CurriculumDraftLoader {

	private final CurriculumAuthoringRepository repository;

	/**
	 * Creates a loader for persisted authoring hierarchies.
	 *
	 * @param repository source of syllabus nodes and their persistent identities
	 */
	public CurriculumDraftLoader(CurriculumAuthoringRepository repository) {
		if (repository == null) {
			throw new NullPointerException("repository");
		}
		this.repository = repository;
	}

	/**
	 * Loads persisted nodes into a fresh draft with session-local IDs bound to
	 * their existing database IDs. Retains codes, wording, parent relationships,
	 * sibling order and optional source pages without renumbering the curriculum.
	 * Records the complete persisted node snapshot for stale-save detection. The
	 * supplied syllabus snapshot is retained; loading a final curriculum does not
	 * reopen it or authorise saving it.
	 *
	 * @param syllabusVersion syllabus snapshot whose persisted nodes are loaded
	 * @return fresh session, possibly containing an empty draft
	 * @throws NullPointerException  if {@code syllabusVersion} is {@code null}
	 * @throws IllegalStateException if reading fails or the stored hierarchy has
	 *                               duplicate identities, orphans or cycles
	 */
	public CurriculumAuthoringSession load(SyllabusVersion syllabusVersion) {
		if (syllabusVersion == null) {
			throw new NullPointerException("syllabusVersion");
		}
		List<PersistedCurriculumNode> persistedNodes = repository.findNodesForVersion(syllabusVersion);
		CurriculumDraft draft = new CurriculumDraft();
		CurriculumAuthoringSession session = new CurriculumAuthoringSession(syllabusVersion, draft);
		Map<Long, PersistedCurriculumNode> byId = new HashMap<>();
		for (PersistedCurriculumNode node : persistedNodes) {
			if (byId.put(node.persistentId(), node) != null) {
				throw new IllegalStateException("Duplicate persistent curriculum node id: " + node.persistentId());
			}
		}
		Map<Long, List<PersistedCurriculumNode>> childrenByParent = persistedNodes.stream()
				.filter(node -> node.parentPersistentId() != null)
				.collect(java.util.stream.Collectors.groupingBy(PersistedCurriculumNode::parentPersistentId));
		List<PersistedCurriculumNode> roots = persistedNodes.stream().filter(node -> node.parentPersistentId() == null)
				.sorted(nodeOrder()).toList();
		Set<Long> loadedPersistentIds = new HashSet<>();
		for (PersistedCurriculumNode root : roots) {
			loadNode(root, null, childrenByParent, draft, session, loadedPersistentIds);
		}
		if (loadedPersistentIds.size() != persistedNodes.size()) {
			// Root traversal cannot reach orphaned nodes or a disconnected cycle.
			throw new IllegalStateException("Persisted curriculum contains an orphaned or cyclic hierarchy");
		}
		// Save uses this stored baseline to detect edits made by another authoring session.
		session.recordPersistedSnapshot(persistedNodes);
		return session;
	}

	private void loadNode(PersistedCurriculumNode persistedNode, Long parentDraftId,
			Map<Long, List<PersistedCurriculumNode>> childrenByParent, CurriculumDraft draft,
			CurriculumAuthoringSession session, Set<Long> loadedPersistentIds) {
		if (!loadedPersistentIds.add(persistedNode.persistentId())) {
			throw new IllegalStateException(
					"Cyclic curriculum hierarchy at persistent node " + persistedNode.persistentId());
		}
		// Load parents first so child links use new draft IDs, never database IDs.
		CurriculumDraftNode draftNode = draft.addNode(persistedNode.level(), persistedNode.code(), persistedNode.name(),
				parentDraftId, persistedNode.sourcePageNumber());
		session.bindPersistentId(draftNode.draftId(), persistedNode.persistentId());
		List<PersistedCurriculumNode> children = childrenByParent.getOrDefault(persistedNode.persistentId(), List.of());
		for (PersistedCurriculumNode child : children.stream().sorted(nodeOrder()).toList()) {
			loadNode(child, draftNode.draftId(), childrenByParent, draft, session, loadedPersistentIds);
		}
	}

	private Comparator<PersistedCurriculumNode> nodeOrder() {
		return Comparator.comparingInt(PersistedCurriculumNode::displayOrder)
				.thenComparing(PersistedCurriculumNode::code);
	}
}
