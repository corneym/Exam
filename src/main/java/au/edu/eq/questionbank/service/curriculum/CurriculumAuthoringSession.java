package au.edu.eq.questionbank.service.curriculum;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

import au.edu.eq.questionbank.model.SyllabusVersion;

/**
 * One resumable curriculum-authoring session.
 * <p>
 * Draft identifiers belong only to the current editing session. Existing
 * persisted nodes retain a separate mapping to their permanent SQLite IDs.
 */
public final class CurriculumAuthoringSession {

	private SyllabusVersion syllabusVersion;
	private final CurriculumDraft draft;
	private final Map<Long, Long> persistentIdByDraftId = new HashMap<>();

	public CurriculumAuthoringSession(SyllabusVersion syllabusVersion, CurriculumDraft draft) {
		if (syllabusVersion == null) {
			throw new NullPointerException("syllabusVersion");
		}
		if (draft == null) {
			throw new NullPointerException("draft");
		}
		this.syllabusVersion = syllabusVersion;
		this.draft = draft;
	}

	/**
	 * Associates an existing or newly persisted database row with a draft node.
	 *
	 * @param draftId      transient draft identifier
	 * @param persistentId permanent curriculum-node identifier
	 */
	public void bindPersistentId(long draftId, long persistentId) {
		if (draft.findNode(draftId).isEmpty()) {
			throw new IllegalArgumentException("Unknown draft id: " + draftId);
		}
		if (persistentId < 1) {
			throw new IllegalArgumentException("persistentId must be positive");
		}
		Long existing = persistentIdByDraftId.get(draftId);
		if (existing != null && existing.longValue() != persistentId) {
			throw new IllegalStateException(
					"Draft node " + draftId + " is already bound to persistent node " + existing);
		}
		if (persistentIdByDraftId.containsValue(persistentId) && !Long.valueOf(persistentId).equals(existing)) {
			throw new IllegalStateException(
					"Persistent node " + persistentId + " is already bound to another draft node");
		}
		persistentIdByDraftId.put(draftId, persistentId);
	}

	/**
	 * Returns the persistent IDs whose draft nodes have been removed from the
	 * current draft.
	 *
	 * @return persistent curriculum-node IDs pending deletion
	 */
	public Set<Long> deletedPersistentIds() {
		Set<Long> currentDraftIds = new HashSet<>();
		for (CurriculumDraftNode node : draft.nodes()) {
			currentDraftIds.add(node.draftId());
		}
		Set<Long> deleted = new HashSet<>();
		for (Map.Entry<Long, Long> entry : persistentIdByDraftId.entrySet()) {
			if (!currentDraftIds.contains(entry.getKey())) {
				deleted.add(entry.getValue());
			}
		}
		return Set.copyOf(deleted);
	}

	public CurriculumDraft draft() {
		return draft;
	}

	/**
	 * Removes the transient binding for a persistent node that has been
	 * successfully deleted from storage.
	 *
	 * @param persistentId persistent curriculum-node identifier
	 */
	public void forgetPersistentId(long persistentId) {
		if (persistentId < 1) {
			throw new IllegalArgumentException("persistentId must be positive");
		}
		persistentIdByDraftId.entrySet().removeIf(entry -> entry.getValue().longValue() == persistentId);
	}

	public Map<Long, Long> persistentBindings() {
		return Map.copyOf(persistentIdByDraftId);
	}

	public OptionalLong persistentIdForDraftId(long draftId) {
		Long persistentId = persistentIdByDraftId.get(draftId);
		if (persistentId == null) {
			return OptionalLong.empty();
		}
		return OptionalLong.of(persistentId);
	}

	public void replaceSyllabusVersion(SyllabusVersion syllabusVersion) {
		if (syllabusVersion == null) {
			throw new NullPointerException("syllabusVersion");
		}
		if (syllabusVersion.getId() != this.syllabusVersion.getId()) {
			throw new IllegalArgumentException("Replacement syllabus version must have the same persistent id");
		}
		this.syllabusVersion = syllabusVersion;
	}

	public SyllabusVersion syllabusVersion() {
		return syllabusVersion;
	}
}
