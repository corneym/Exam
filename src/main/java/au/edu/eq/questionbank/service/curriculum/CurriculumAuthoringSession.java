package au.edu.eq.questionbank.service.curriculum;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.repository.curriculum.PersistedCurriculumNode;

/**
 * One resumable curriculum-authoring session.
 * <p>
 * Draft identifiers belong only to the current editing session. Existing
 * persisted nodes retain a separate mapping to their permanent SQLite IDs.
 */
public final class CurriculumAuthoringSession {

	private SyllabusVersion syllabusVersion;
	private final CurriculumDraft draft;
	private Set<PersistedCurriculumNode> persistedSnapshot = Set.of();
	private final Map<Long, Long> persistentIdByDraftId = new HashMap<>();

	/**
	 * Creates an authoring session with no persistent bindings and an empty stored
	 * baseline.
	 *
	 * @param syllabusVersion syllabus being authored
	 * @param draft           hierarchy to edit in this session
	 */
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
		// A binding must remain one-to-one so saving cannot update the wrong stored node.
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
		// Keep removed nodes' bindings until a successful save acknowledges their deletion.
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

	/**
	 * Returns the editable hierarchy belonging to this session.
	 *
	 * @return mutable session draft
	 */
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

	/**
	 * Returns the immutable node snapshot observed at load or the last successful
	 * save. A newly constructed session expects an empty persisted curriculum.
	 *
	 * @return baseline including identities, text, codes, hierarchy, order and
	 *         pages
	 */
	public Set<PersistedCurriculumNode> persistedSnapshot() {
		return persistedSnapshot;
	}

	/**
	 * Records the baseline from a completed load or committed save. Draft edits and
	 * failed saves must never advance this snapshot.
	 *
	 * @param nodes complete persisted node state observed by the loader or writer
	 */
	public void recordPersistedSnapshot(Collection<PersistedCurriculumNode> nodes) {
		persistedSnapshot = Set.copyOf(nodes);
	}

	/**
	 * Returns a snapshot of draft-to-database identity bindings.
	 *
	 * @return immutable map from transient draft IDs to persistent node IDs
	 */
	public Map<Long, Long> persistentBindings() {
		return Map.copyOf(persistentIdByDraftId);
	}

	/**
	 * Looks up the database identity associated with a transient draft node.
	 *
	 * @param draftId session-local node identifier
	 * @return persistent node ID, or empty if the node has no binding
	 */
	public OptionalLong persistentIdForDraftId(long draftId) {
		Long persistentId = persistentIdByDraftId.get(draftId);
		if (persistentId == null) {
			return OptionalLong.empty();
		}
		return OptionalLong.of(persistentId);
	}

	/**
	 * Refreshes syllabus metadata while retaining the session's persistent syllabus
	 * identity.
	 *
	 * @param syllabusVersion replacement snapshot with the same persistent ID
	 * @throws IllegalArgumentException if the replacement identifies another
	 *                                  syllabus
	 */
	public void replaceSyllabusVersion(SyllabusVersion syllabusVersion) {
		if (syllabusVersion == null) {
			throw new NullPointerException("syllabusVersion");
		}
		if (syllabusVersion.getId() != this.syllabusVersion.getId()) {
			throw new IllegalArgumentException("Replacement syllabus version must have the same persistent id");
		}
		this.syllabusVersion = syllabusVersion;
	}

	/**
	 * Returns the session's current syllabus metadata snapshot.
	 *
	 * @return syllabus version including its authoring lifecycle state
	 */
	public SyllabusVersion syllabusVersion() {
		return syllabusVersion;
	}
}
