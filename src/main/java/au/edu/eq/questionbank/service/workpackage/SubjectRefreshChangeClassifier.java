package au.edu.eq.questionbank.service.workpackage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import au.edu.eq.questionbank.service.workpackage.ExamWorkAssetIndex.Reference;

/**
 * Classifies changes between the current and incoming authoritative
 * representations of a Subject.
 *
 * <p>
 * Record identities and relationship values must already have been normalised
 * into the same portable identity namespace by the caller. This classifier does
 * not resolve database aliases or modify data.
 */
public final class SubjectRefreshChangeClassifier {

	/**
	 * Creates a stateless Subject refresh change classifier.
	 */
	public SubjectRefreshChangeClassifier() {
	}

	/**
	 * Compares physical and embedded asset inventories by managed path.
	 *
	 * <p>
	 * Several logical references to the same managed path represent one binary
	 * asset change. Owner and relationship changes are handled independently by the
	 * graph comparison.
	 *
	 * @param current  current destination asset inventory
	 * @param incoming incoming authoritative asset inventory
	 * @return immutable and deterministically ordered asset changes
	 * @throws IllegalArgumentException if managed paths conflict
	 * @throws NullPointerException     if either inventory is null
	 */
	public List<AssetChange> compareAssets(ExamWorkAssetIndex current, ExamWorkAssetIndex incoming) {
		Map<String, AssetContent> before = indexAssets(current);
		Map<String, AssetContent> after = indexAssets(incoming);
		Set<String> paths = new TreeSet<>();
		paths.addAll(before.keySet());
		paths.addAll(after.keySet());
		List<AssetChange> changes = new ArrayList<>();
		for (String path : paths) {
			AssetContent existing = before.get(path);
			AssetContent replacement = after.get(path);
			ChangeKind kind;
			if (existing == null) {
				kind = ChangeKind.ADD;
			} else if (replacement == null) {
				kind = ChangeKind.REMOVE;
			} else if (!existing.equals(replacement)) {
				kind = ChangeKind.UPDATE;
			} else {
				kind = ChangeKind.UNCHANGED;
			}
			changes.add(new AssetChange(path, kind, existing, replacement));
		}
		return java.util.List.copyOf(changes);
	}

	/**
	 * Compares normalised domain records and classifies their changes.
	 *
	 * @param current  current destination records
	 * @param incoming incoming authoritative records
	 * @return immutable and deterministically ordered record changes
	 * @throws IllegalArgumentException if duplicate record keys exist
	 * @throws NullPointerException     if either list is null
	 */
	public List<RecordChange> compareRecords(List<ExchangeRecord> current, List<ExchangeRecord> incoming) {
		Map<RecordKey, ExchangeRecord> before = indexRecords(current);
		Map<RecordKey, ExchangeRecord> after = indexRecords(incoming);
		Set<RecordKey> keys = new TreeSet<>(
				Comparator.comparing(RecordKey::recordType).thenComparing(RecordKey::recordId));
		keys.addAll(before.keySet());
		keys.addAll(after.keySet());
		List<RecordChange> changes = new ArrayList<>();
		for (RecordKey key : keys) {
			ExchangeRecord existing = before.get(key);
			ExchangeRecord replacement = after.get(key);
			ChangeKind kind;
			if (existing == null) {
				kind = ChangeKind.ADD;
			} else if (replacement == null) {
				kind = ChangeKind.REMOVE;
			} else if (!existing.values().equals(replacement.values())) {
				kind = ChangeKind.UPDATE;
			} else if (!Objects.equals(existing.revisionId(), replacement.revisionId())) {

				// Equal domain values can still carry a changed
				// authoritative revision identity.
				kind = ChangeKind.REVISION_ONLY;
			} else {
				kind = ChangeKind.UNCHANGED;
			}
			changes.add(new RecordChange(key, kind, existing, replacement));
		}
		return java.util.List.copyOf(changes);
	}

	private Map<String, AssetContent> indexAssets(ExamWorkAssetIndex assets) {
		Objects.requireNonNull(assets, "assets");
		Map<String, AssetContent> indexed = new HashMap<>();
		for (Reference reference : assets.references()) {
			AssetContent content = new AssetContent(reference.sha256(), reference.size());
			AssetContent previous = indexed.putIfAbsent(reference.managedPath(), content);
			if (previous != null && !previous.equals(content)) {
				throw new IllegalArgumentException("Conflicting asset content at " + reference.managedPath());
			}
		}
		return indexed;
	}

	private Map<RecordKey, ExchangeRecord> indexRecords(List<ExchangeRecord> records) {
		Objects.requireNonNull(records, "records");
		Map<RecordKey, ExchangeRecord> indexed = new HashMap<>();
		for (ExchangeRecord record : records) {
			Objects.requireNonNull(record, "record");
			RecordKey key = new RecordKey(record.recordType(), record.recordId());
			if (indexed.putIfAbsent(key, record) != null) {
				throw new IllegalArgumentException("Duplicate portable record key: " + key);
			}
		}
		return indexed;
	}

	/**
	 * Content identity of one managed binary asset.
	 *
	 * @param sha256 lowercase SHA-256 digest
	 * @param size   uncompressed content size in bytes
	 */
	public record AssetContent(String sha256, long size) {

		/**
		 * Validates binary content identity.
		 *
		 * @throws IllegalArgumentException if the digest or size is invalid
		 */
		public AssetContent {
			if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
				throw new IllegalArgumentException("Invalid asset SHA-256 digest");
			}
			if (size < 0) {
				throw new IllegalArgumentException("Asset size must not be negative");
			}
		}
	}

	/**
	 * Proposed change to one managed asset.
	 *
	 * @param managedPath managed data-root-relative path
	 * @param kind        classified change
	 * @param current     existing content, or null for an addition
	 * @param incoming    authoritative content, or null for a removal
	 */
	public record AssetChange(String managedPath, ChangeKind kind, AssetContent current, AssetContent incoming) {

		/**
		 * Validates the asset change.
		 *
		 * @throws IllegalArgumentException if the path or change is invalid
		 * @throws NullPointerException     if kind is null
		 */
		public AssetChange {
			if (managedPath == null || managedPath.isBlank()) {
				throw new IllegalArgumentException("Managed path must not be blank");
			}
			Objects.requireNonNull(kind, "kind");
			boolean valid = switch (kind) {
			case ADD -> current == null && incoming != null;
			case REMOVE -> current != null && incoming == null;
			case UPDATE -> current != null && incoming != null && !current.equals(incoming);
			case UNCHANGED -> current != null && current.equals(incoming);
			case REVISION_ONLY -> false;
			};
			if (!valid) {
				throw new IllegalArgumentException("Inconsistent asset change");
			}
		}
	}

	/**
	 * Classification of an authoritative snapshot difference.
	 */
	public enum ChangeKind {
		/** New authoritative record or asset. */
		ADD,
		/** Existing authoritative record or asset has changed. */
		UPDATE,
		/** Record or asset is absent from the incoming snapshot. */
		REMOVE,
		/** Domain values match but authoritative revision differs. */
		REVISION_ONLY,
		/** Compared domain values and revision or asset bytes match. */
		UNCHANGED
	}

	/**
	 * Proposed change to a normalised portable record.
	 *
	 * @param key      common portable record identity
	 * @param kind     classified change
	 * @param current  existing record, or null for an addition
	 * @param incoming authoritative record, or null for a removal
	 */
	public record RecordChange(RecordKey key, ChangeKind kind, ExchangeRecord current, ExchangeRecord incoming) {

		/**
		 * Validates record identities and consistency of the change.
		 *
		 * @throws IllegalArgumentException if the change is inconsistent
		 * @throws NullPointerException     if key or kind is null
		 */
		public RecordChange {
			Objects.requireNonNull(key, "key");
			Objects.requireNonNull(kind, "kind");
			if (current != null
					&& (!key.recordType().equals(current.recordType()) || !key.recordId().equals(current.recordId()))) {
				throw new IllegalArgumentException("Current record identity differs from key");
			}
			if (incoming != null && (!key.recordType().equals(incoming.recordType())
					|| !key.recordId().equals(incoming.recordId()))) {
				throw new IllegalArgumentException("Incoming record identity differs from key");
			}
			boolean valid = switch (kind) {
			case ADD -> current == null && incoming != null;
			case REMOVE -> current != null && incoming == null;
			case UPDATE -> current != null && incoming != null && !current.values().equals(incoming.values());
			case REVISION_ONLY -> current != null && incoming != null && current.values().equals(incoming.values())
					&& !Objects.equals(current.revisionId(), incoming.revisionId());
			case UNCHANGED -> current != null && incoming != null && current.values().equals(incoming.values())
					&& Objects.equals(current.revisionId(), incoming.revisionId());
			};
			if (!valid) {
				throw new IllegalArgumentException("Inconsistent record change");
			}
		}
	}

	/**
	 * Portable identity of one normalised domain record.
	 *
	 * @param recordType portable entity type
	 * @param recordId   portable entity UUID in the common namespace
	 */
	public record RecordKey(String recordType, UUID recordId) {

		/**
		 * Validates the portable identity.
		 *
		 * @throws IllegalArgumentException if the record type is invalid
		 * @throws NullPointerException     if recordId is null
		 */
		public RecordKey {
			if (recordType == null || !recordType.matches("[A-Z][A-Z0-9_]{0,63}")) {
				throw new IllegalArgumentException("Invalid portable record type");
			}
			Objects.requireNonNull(recordId, "recordId");
		}
	}
}
