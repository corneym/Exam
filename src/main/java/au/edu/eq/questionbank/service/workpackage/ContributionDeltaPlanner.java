package au.edu.eq.questionbank.service.workpackage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;

/**
 * Calculates contributor changes against an immutable whole-Exam assignment
 * baseline.
 *
 * <p>
 * The planner operates entirely on portable record values and revision
 * identities. It does not modify the contributor database, advance the
 * assignment baseline, or assume that an earlier contribution has been
 * acknowledged.
 *
 * <p>
 * Each modified candidate retains its original baseline revision. Original
 * values are returned separately so that a coordinator can later perform
 * baseline-aware reconciliation.
 *
 * <p>
 * This class detects record changes only. Assignment scope, dependency closure,
 * asset packaging and master import validation are separate responsibilities.
 */
public final class ContributionDeltaPlanner {

	/**
	 * Creates a stateless contribution delta planner.
	 */
	public ContributionDeltaPlanner() {
	}

	/**
	 * Calculates the contributor's new and modified records relative to the
	 * original assignment baseline.
	 *
	 * <p>
	 * Records with identical values are omitted, even when their revision
	 * identifiers differ. Modified records retain their original baseline revision
	 * identifier, and the corresponding original record is preserved separately.
	 *
	 * <p>
	 * New records have no baseline revision. The planner does not interpret the
	 * absence of a newly created record from a later snapshot as an instruction to
	 * delete it from the master.
	 *
	 * @param baseline complete immutable assignment baseline
	 * @param current  complete current assignment record snapshot
	 * @return immutable, deterministically ordered contribution plan
	 * @throws PackageFormatException if records are duplicated, missing,
	 *                                structurally modified, or have invalid
	 *                                revision evidence
	 * @throws NullPointerException   if either input list is null
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
