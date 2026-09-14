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

	public CurriculumDraftLoader(CurriculumAuthoringRepository repository) {
		if (repository == null) {
			throw new NullPointerException("repository");
		}
		this.repository = repository;
	}

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
			throw new IllegalStateException("Persisted curriculum contains an orphaned or cyclic hierarchy");
		}
		return session;
	}

	private void loadNode(PersistedCurriculumNode persistedNode, Long parentDraftId,
			Map<Long, List<PersistedCurriculumNode>> childrenByParent, CurriculumDraft draft,
			CurriculumAuthoringSession session, Set<Long> loadedPersistentIds) {
		if (!loadedPersistentIds.add(persistedNode.persistentId())) {
			throw new IllegalStateException(
					"Cyclic curriculum hierarchy at persistent node " + persistedNode.persistentId());
		}
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
