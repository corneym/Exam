package au.edu.eq.questionbank.service.workpackage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import au.edu.eq.questionbank.service.workpackage.CurriculumReconciler.Classification;
import au.edu.eq.questionbank.service.workpackage.CurriculumReconciler.Proposal;
import au.edu.eq.questionbank.service.workpackage.SubjectReconciliationPreviewService.AssetEvidence;
import au.edu.eq.questionbank.service.workpackage.SubjectReconciliationPreviewService.Result;

/**
 * Coordinator's in-memory, explicit accept/reject decisions for a read-only
 * preview.
 *
 * <p>
 * This is a review plan, not an importer or an authorised database mutation.
 * Every portable record and every incoming asset reference starts UNDECIDED.
 * Rejecting a parent is incompatible with accepting any of its descendants. An
 * accepted CONFLICT requires a written coordinator justification and remains a
 * conflict in the original preview; only later import planning may determine
 * whether and how an override can be safely implemented.
 */
public final class SubjectReconciliationReview {

	private final Result preview;
	private final Map<Key, Choice> choices = new HashMap<>();
	private final Map<Key, Proposal> records = new HashMap<>();
	private final Map<Key, AssetEvidence> assets = new HashMap<>();

	/**
	 * Constructs a new coordinator review with no implicit approvals.
	 *
	 * @param preview fully validated, read-only Subject comparison
	 */
	public SubjectReconciliationReview(Result preview) {
		this.preview = Objects.requireNonNull(preview, "preview");
		for (Proposal proposal : preview.proposals()) {
			Key key = recordKey(proposal.recordType(), proposal.incomingId());
			if (records.putIfAbsent(key, proposal) != null) {
				throw new IllegalArgumentException("Duplicate review record " + key);
			}
		}
		for (AssetEvidence asset : preview.assetEvidence()) {
			Key key = assetKey(asset);
			if (assets.putIfAbsent(key, asset) != null) {
				throw new IllegalArgumentException("Duplicate logical asset review " + key);
			}
		}
	}

	/**
	 * Creates the stable key for an incoming logical asset usage.
	 *
	 * @param asset indexed asset comparison
	 * @return stable logical asset review key
	 */
	public static Key assetKey(AssetEvidence asset) {
		Objects.requireNonNull(asset, "asset");
		return new Key("ASSET", asset.usage() + ":" + asset.incomingPath() + ":"
				+ (asset.ownerType() == null ? "" : asset.ownerType() + ":" + asset.incomingOwnerId()));
	}

	/**
	 * Creates the stable key for a portable incoming record.
	 *
	 * @param type       portable entity type
	 * @param incomingId portable incoming UUID
	 * @return stable record review key
	 */
	public static Key recordKey(String type, UUID incomingId) {
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(incomingId, "incomingId");
		return new Key("RECORD", type + ":" + incomingId);
	}

	/**
	 * Accepts every eligible, undecided IDENTICAL record and asset.
	 *
	 * <p>
	 * Existing decisions are preserved. Items whose required dependencies cannot
	 * also be accepted remain undecided. This operation changes only the in-memory
	 * review, never authoritative data.
	 *
	 * @return the applied plan and the number of blocked items
	 */
	public BulkAcceptancePlan acceptAllIdentical() {
		BulkAcceptancePlan plan = planAcceptAllIdentical();
		for (Key key : plan.keys()) {
			decide(key, Decision.ACCEPT, "");
		}
		return plan;
	}

	/**
	 * Returns the entry's explicit choice, defaulting to UNDECIDED.
	 *
	 * @param key existing review key
	 * @return stored choice or an undecided choice
	 */
	public Choice choice(Key key) {
		if (!records.containsKey(key) && !assets.containsKey(key)) {
			throw new IllegalArgumentException("Unknown review key: " + key);
		}
		return choices.getOrDefault(key, new Choice(Decision.UNDECIDED, ""));
	}

	/**
	 * Records one explicit decision, without changing the source comparison.
	 *
	 * @param key           existing record/asset review key
	 * @param decision      desired accept/reject/undecided state
	 * @param justification coordinator explanation, mandatory to accept CONFLICT
	 * @throws IllegalArgumentException for unknown keys or unexplained conflicts
	 * @throws IllegalStateException    if an accepted item would have a rejected
	 *                                  parent
	 */
	public void decide(Key key, Decision decision, String justification) {
		Objects.requireNonNull(key, "key");
		Objects.requireNonNull(decision, "decision");
		if (!records.containsKey(key) && !assets.containsKey(key)) {
			throw new IllegalArgumentException("Unknown review entry " + key);
		}
		if (decision == Decision.UNDECIDED) {
			choices.remove(key);
			return;
		}
		String note = justification == null ? "" : justification.trim();
		Classification status = records.containsKey(key) ? records.get(key).classification()
				: assets.get(key).classification();
		if (decision == Decision.ACCEPT && status == Classification.CONFLICT && note.length() < 12) {
			throw new IllegalArgumentException("Accepting a conflict requires a justification of 12+ characters");
		}
		if (decision == Decision.ACCEPT && hasRejectedDependency(key, choices)) {
			throw new IllegalStateException("Cannot accept an entry beneath a rejected parent or owner");
		}
		if (decision == Decision.REJECT) {
			for (Key accepted : choices.keySet()) {
				if (!accepted.equals(key) && choices.get(accepted).decision() == Decision.ACCEPT
						&& dependsOn(accepted, key)) {
					throw new IllegalStateException("Reject the accepted dependent entries first");
				}
			}
		}
		choices.put(key, new Choice(decision, note));
	}

	/**
	 * Calculates a dependency-closed bulk acceptance without changing decisions.
	 *
	 * @return eligible record and asset keys, counts and blocked count
	 */
	public BulkAcceptancePlan planAcceptAllIdentical() {
		List<Key> orderedKeys = reviewKeys();
		Set<Key> pending = new HashSet<>();
		for (Key key : orderedKeys) {
			if (choice(key).decision() == Decision.UNDECIDED && classification(key) == Classification.IDENTICAL) {
				pending.add(key);
			}
		}

		// Existing approvals may satisfy dependencies, but rejected and
		// undecided non-identical items cannot be included automatically.
		Set<Key> available = new HashSet<>(pending);
		for (Map.Entry<Key, Choice> entry : choices.entrySet()) {
			if (entry.getValue().decision() == Decision.ACCEPT) {
				available.add(entry.getKey());
			}
		}

		// Repeatedly remove candidates with unavailable prerequisites.
		// This also handles cycles: a complete accepted cycle remains eligible,
		// while a cycle depending on an unavailable record is removed.
		boolean changed;
		do {
			changed = false;
			for (Key key : new ArrayList<>(available)) {
				if (!available.containsAll(dependencies(key))) {
					available.remove(key);
					changed = true;
				}
			}
		} while (changed);
		List<Key> eligible = orderedKeys.stream().filter(pending::contains).filter(available::contains).toList();
		int recordCount = (int) eligible.stream().filter(key -> "RECORD".equals(key.kind())).count();
		int assetCount = eligible.size() - recordCount;
		int blockedCount = pending.size() - eligible.size();
		return new BulkAcceptancePlan(eligible, recordCount, assetCount, blockedCount);
	}

	/**
	 * Returns the original immutable comparison; decisions do not change it.
	 *
	 * @return complete preview
	 */
	public Result preview() {
		return preview;
	}

	/**
	 * Lists all review keys in deterministic record-first order.
	 *
	 * @return stable, unmodifiable review keys
	 */
	public List<Key> reviewKeys() {
		List<Key> result = new ArrayList<>(records.keySet());
		result.addAll(assets.keySet());
		result.sort(Comparator.comparing(Key::kind).reversed().thenComparing(Key::value));
		return List.copyOf(result);
	}

	/**
	 * Creates an immutable, non-publishing snapshot of current decisions.
	 *
	 * @return current review choices and closure verification
	 */
	public Snapshot snapshot() {
		boolean complete = choices.size() == records.size() + assets.size();
		boolean dependenciesSafe = true;
		for (Map.Entry<Key, Choice> entry : choices.entrySet()) {
			if (entry.getValue().decision() == Decision.ACCEPT && !acceptedDependencies(entry.getKey())) {
				dependenciesSafe = false;
			}
		}
		return new Snapshot(preview.masterPackageId(), preview.incomingPackageId(), choices, complete,
				dependenciesSafe);
	}

	/** Checks closure of an accepted entry, including its transitive ancestors. */
	private boolean acceptedDependencies(Key key) {
		return acceptedDependencies(key, new HashSet<>());
	}

	/**
	 * Resolves cyclic Question/Image references only after every member is
	 * accepted.
	 */
	private boolean acceptedDependencies(Key key, Set<Key> visited) {
		if (!visited.add(key)) {
			return true;
		}
		for (Key parent : dependencies(key)) {
			Choice decision = choices.get(parent);
			if (decision == null || decision.decision() != Decision.ACCEPT || !acceptedDependencies(parent, visited)) {
				return false;
			}
		}
		return true;
	}

	private Classification classification(Key key) {
		Proposal record = records.get(key);
		if (record != null) {
			return record.classification();
		}
		AssetEvidence asset = assets.get(key);
		if (asset != null) {
			return asset.classification();
		}
		throw new IllegalArgumentException("Unknown review key: " + key);
	}

	/**
	 * Resolves the immediate required parent for a record or the owner of an asset.
	 */
	private List<Key> dependencies(Key key) {
		Proposal proposal = records.get(key);
		if (proposal != null) {
			List<Key> required = new ArrayList<>();
			for (var dependency : preview.dependencies()) {
				if (dependency.ownerType().equals(proposal.recordType())
						&& dependency.ownerId().equals(proposal.incomingId())) {
					Key other = recordKey(dependency.relatedType(), dependency.relatedId());
					if (!records.containsKey(other)) {
						throw new IllegalStateException("Unreviewed portable dependency " + other);
					}
					if (!required.contains(other)) {
						required.add(other);
					}
				}
			}
			UUID parent = proposal.incomingParentId();
			if (parent == null) {
				return List.copyOf(required);
			}
			String parentType = switch (proposal.recordType()) {
			case "SYLLABUS_VERSION", "EXAM" -> "SUBJECT";
			case "CURRICULUM_NODE" -> parentTypeForNode(parent);
			case "EXAM_BOOKLET", "ANSWER_FILE" -> "EXAM";
			case "SOURCE_QUESTION", "SHARED_QUESTION_CONTEXT", "QUESTION" -> "EXAM_BOOKLET";
			case "ANSWER", "QUESTION_IMAGE" -> "QUESTION";
			case "CURRICULUM_MAPPING" -> "CURRICULUM_NODE";
			default -> null;
			};
			if (parentType != null) {
				Key parentKey = recordKey(parentType, parent);
				if (records.containsKey(parentKey) && !required.contains(parentKey)) {
					required.add(parentKey);
				}
			}
			return List.copyOf(required);
		}
		AssetEvidence asset = assets.get(key);
		if (asset != null && asset.ownerType() != null) {
			Key ownerKey = recordKey(asset.ownerType(), asset.incomingOwnerId());
			if (records.containsKey(ownerKey)) {
				return List.of(ownerKey);
			}
		}
		return List.of();
	}

	/** Returns true when an entry requires acceptance of a particular ancestor. */
	private boolean dependsOn(Key key, Key ancestor) {
		return dependsOn(key, ancestor, new HashSet<>());
	}

	/**
	 * Checks transitive dependencies without infinite recursion on image owners.
	 */
	private boolean dependsOn(Key key, Key ancestor, Set<Key> visited) {
		if (!visited.add(key)) {
			return false;
		}
		for (Key dependency : dependencies(key)) {
			if (dependency.equals(ancestor) || dependsOn(dependency, ancestor, visited)) {
				return true;
			}
		}
		return false;
	}

	/** Finds whether any ancestor or asset owner was expressly rejected. */
	private boolean hasRejectedDependency(Key key, Map<Key, Choice> current) {
		return hasRejectedDependency(key, current, new HashSet<>());
	}

	/** Walks dependency cycles only once while detecting any rejected ancestor. */
	private boolean hasRejectedDependency(Key key, Map<Key, Choice> current, Set<Key> visited) {
		if (!visited.add(key)) {
			return false;
		}
		for (Key dependency : dependencies(key)) {
			Choice prior = current.get(dependency);
			if (prior != null && prior.decision() == Decision.REJECT) {
				return true;
			}
			if (hasRejectedDependency(dependency, current, visited)) {
				return true;
			}
		}
		return false;
	}

	/** Distinguishes a syllabus node from a descendant curriculum node. */
	private String parentTypeForNode(UUID parent) {
		return records.containsKey(recordKey("CURRICULUM_NODE", parent)) ? "CURRICULUM_NODE" : "SYLLABUS_VERSION";
	}

	/**
	 * An immutable preview or result of bulk identical-item acceptance.
	 *
	 * @param keys         record and asset keys eligible for acceptance
	 * @param recordCount  number of records eligible for acceptance
	 * @param assetCount   number of assets eligible for acceptance
	 * @param blockedCount undecided identical items blocked by dependencies
	 */
	public record BulkAcceptancePlan(List<Key> keys, int recordCount, int assetCount, int blockedCount) {

		/** Validates and defensively copies the proposed acceptance. */
		public BulkAcceptancePlan {
			keys = List.copyOf(Objects.requireNonNull(keys, "keys"));
			if (recordCount < 0 || assetCount < 0 || blockedCount < 0 || recordCount + assetCount != keys.size()) {
				throw new IllegalArgumentException("Invalid bulk acceptance counts");
			}
		}
	}

	/** Coordinator's explicit decision for one preview item. */
	public enum Decision {
		/** Not yet reviewed. */
		UNDECIDED,
		/** Explicitly accepted as an uncommitted review proposal. */
		ACCEPT,
		/** Explicitly rejected by the coordinator. */
		REJECT
	}

	/**
	 * Stable identifier for either an incoming record or an asset usage.
	 *
	 * @param kind  RECORD or ASSET
	 * @param value portable record identity or logical asset key
	 */
	public record Key(String kind, String value) {

		/** Validates a stable review identity. */
		public Key {
			if (!("RECORD".equals(kind) || "ASSET".equals(kind)) || value == null || value.isBlank()) {
				throw new IllegalArgumentException("Invalid reconciliation review key");
			}
		}
	}

	/**
	 * One deliberate coordinator choice and supporting explanation.
	 *
	 * @param decision      explicit decision
	 * @param justification optional reviewer note, mandatory for conflict
	 *                      acceptance
	 */
	public record Choice(Decision decision, String justification) {

		/** Normalises and checks a review choice. */
		public Choice {
			Objects.requireNonNull(decision, "decision");
			justification = justification == null ? "" : justification.trim();
		}
	}

	/**
	 * Immutable review state suitable for a later, separately authorised plan.
	 *
	 * @param masterPackageId   compared coordinator snapshot
	 * @param incomingPackageId compared contributor snapshot
	 * @param decisions         explicit review choices only
	 * @param complete          whether every record and asset has an explicit
	 *                          choice
	 * @param dependencySafe    whether accepted children/asset references have
	 *                          accepted owners
	 */
	public record Snapshot(UUID masterPackageId, UUID incomingPackageId, Map<Key, Choice> decisions, boolean complete,
			boolean dependencySafe) {

		/** Defensively copies the decisions before returning the snapshot. */
		public Snapshot {
			Objects.requireNonNull(masterPackageId, "masterPackageId");
			Objects.requireNonNull(incomingPackageId, "incomingPackageId");
			decisions = Map.copyOf(decisions);
		}
	}
}
