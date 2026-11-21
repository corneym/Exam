package au.edu.eq.questionbank.service.workpackage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.node.ObjectNode;

import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;

/**
 * Calculates newly captured contributor records against an immutable whole-Exam
 * assignment baseline.
 *
 * <p>
 * Baseline records are coordinator-owned and cannot be modified or removed by a
 * contributor. The only tolerated difference in an issued Booklet is automatic
 * applicability-review invalidation caused by new capture; that difference is
 * not exported as a proposed Booklet update.
 *
 * <p>
 * Only supported newly captured records are returned as candidates. The planner
 * does not modify the original baseline or contributor database. Assignment
 * scope and dependency validation are separate responsibilities.
 */
public final class ContributionDeltaPlanner {

	/**
	 * Creates a stateless contribution delta planner.
	 */
	public ContributionDeltaPlanner() {
	}

	/**
	 * Identifies supported new records while preserving the issued baseline.
	 *
	 * <p>
	 * Semantically unchanged baseline records are ignored. Changed or removed
	 * baseline content is rejected, except for automatic Booklet
	 * applicability-review invalidation.
	 *
	 * @param baseline complete immutable issued assignment records
	 * @param current  complete current assigned Exam records
	 * @return deterministic new-record candidates, with no baseline updates
	 * @throws PackageFormatException if baseline records are altered or removed,
	 *                                additions are unsupported, or record evidence
	 *                                is invalid
	 * @throws NullPointerException   if either record list is null
	 */
	public Plan plan(List<ExchangeRecord> baseline, List<ExchangeRecord> current) throws PackageFormatException {
		Map<String, ExchangeRecord> originalRecords = index(baseline);
		Map<String, ExchangeRecord> currentRecords = index(current);
		for (String key : originalRecords.keySet()) {
			if (!currentRecords.containsKey(key)) {
				throw new PackageFormatException("The contributor removed an issued baseline record: " + key);
			}
		}
		List<ExchangeRecord> candidates = new ArrayList<>();
		for (Map.Entry<String, ExchangeRecord> entry : currentRecords.entrySet()) {
			ExchangeRecord proposed = entry.getValue();
			ExchangeRecord original = originalRecords.get(entry.getKey());
			if (original != null) {
				if (original.values().equals(proposed.values())) {
					continue;
				}

				// New capture automatically invalidates the Booklet's previous
				// applicability-review confirmation. That invalidation is not
				// contributor-authored content and must not be returned as an edit.
				if ("EXAM_BOOKLET".equals(original.recordType()) && onlyBookletReviewChanged(original, proposed)) {
					continue;
				}
				throw new PackageFormatException(
						"Existing coordinator-owned records cannot be modified: " + entry.getKey());
			}
			if (!isAllowedAddition(proposed)) {
				throw new PackageFormatException("The contributor cannot create record type: " + proposed.recordType());
			}
			candidates.add(new ExchangeRecord(proposed.recordVersion(), proposed.recordType(), proposed.recordId(),
					proposed.revisionId(), null, proposed.values()));
		}
		return new Plan(candidates, List.of());
	}

	private Map<String, ExchangeRecord> index(List<ExchangeRecord> records) throws PackageFormatException {
		Objects.requireNonNull(records, "records");
		Map<String, ExchangeRecord> indexed = new TreeMap<>();
		for (ExchangeRecord record : records) {
			if (record == null) {
				throw new PackageFormatException("Contribution snapshot contains a null record");
			}
			try {
				EntityType.valueOf(record.recordType());
			} catch (IllegalArgumentException exception) {
				throw new PackageFormatException("Unsupported portable record type: " + record.recordType(), exception);
			}
			if (record.revisionId() == null) {
				throw new PackageFormatException(
						"Contribution record has no causal revision: " + record.recordType() + ":" + record.recordId());
			}

			// The supplied snapshots must be original current-state
			// records. Return lineage is added only by the planner.
			if (record.baselineRevisionId() != null) {
				throw new PackageFormatException("Input snapshot already contains return lineage: "
						+ record.recordType() + ":" + record.recordId());
			}
			String key = record.recordType() + "\0" + record.recordId();
			if (indexed.putIfAbsent(key, record) != null) {
				throw new PackageFormatException("Duplicate contribution record identity: " + key);
			}
		}
		return indexed;
	}

	private boolean isAllowedAddition(ExchangeRecord record) {

		// Match the contributor-owned entity families permitted
		// by the assignment capture model.
		return switch (record.recordType()) {
		case "QUESTION", "ANSWER", "SOURCE_QUESTION", "SHARED_QUESTION_CONTEXT", "QUESTION_IMAGE" -> true;
		default -> false;
		};
	}

	private boolean onlyBookletReviewChanged(ExchangeRecord original, ExchangeRecord proposed) {
		ObjectNode originalValues = (ObjectNode) original.values();
		ObjectNode proposedValues = (ObjectNode) proposed.values();
		String reviewField = "booklet_applicability_reviews";

		// An authentic portable Booklet snapshot includes
		// the review collection, even when it is empty.
		if (!originalValues.path(reviewField).isArray() || !proposedValues.path(reviewField).isArray()) {
			return false;
		}

		// Compare all other Booklet values unchanged.
		// These JSON objects are independent defensive copies.
		originalValues.remove(reviewField);
		proposedValues.remove(reviewField);
		return originalValues.equals(proposedValues);
	}

	/**
	 * Immutable result of comparing a contributor snapshot with its issued
	 * assignment baseline.
	 *
	 * @param candidates       complete new or modified contributor records,
	 *                         including baseline revision references for edits
	 * @param originalVersions original baseline records corresponding to modified
	 *                         candidates; new records do not have original versions
	 */
	public record Plan(List<ExchangeRecord> candidates, List<ExchangeRecord> originalVersions) {

		/**
		 * Defensively retains the ordered candidate and baseline lists.
		 */
		public Plan {
			candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
			originalVersions = List.copyOf(Objects.requireNonNull(originalVersions, "originalVersions"));
		}
	}
}
