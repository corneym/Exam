package au.edu.eq.questionbank.service.workpackage;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable comparison of an eligible incoming Subject snapshot against the
 * currently imported destination Subject.
 *
 * <p>
 * The plan retains the exact incoming archive identity, previously accepted
 * package evidence, destination identity mappings and complete record and asset
 * differences.
 *
 * <p>
 * Constructing a plan does not approve or execute a refresh.
 *
 * @param evidence                 verified originating freshness evidence
 * @param destinationContentSha256 reviewed destination content digest
 * @param identities               originating-to-comparison identity mappings
 * @param recordChanges            complete reviewed record differences
 * @param assetChanges             complete reviewed binary asset differences
 */
public record SubjectRefreshPlan(SubjectSnapshotIntakePreview.RefreshEvidence evidence, String destinationContentSha256,
		Map<SubjectRefreshChangeClassifier.RecordKey, UUID> identities,
		List<SubjectRefreshChangeClassifier.RecordChange> recordChanges,
		List<SubjectRefreshChangeClassifier.AssetChange> assetChanges) {

	/**
	 * Validates and defensively copies the complete comparison plan.
	 *
	 * @throws NullPointerException     if a required component is null
	 * @throws IllegalArgumentException if the destination digest or change
	 *                                  inventory is invalid
	 */
	public SubjectRefreshPlan {
		Objects.requireNonNull(evidence, "evidence");
		if (destinationContentSha256 == null || !destinationContentSha256.matches("[0-9a-f]{64}")) {
			throw new IllegalArgumentException("Invalid destination content fingerprint");
		}
		identities = Map.copyOf(Objects.requireNonNull(identities, "identities"));
		recordChanges = List.copyOf(Objects.requireNonNull(recordChanges, "recordChanges"));
		assetChanges = List.copyOf(Objects.requireNonNull(assetChanges, "assetChanges"));
		Set<SubjectRefreshChangeClassifier.RecordKey> recordKeys = new HashSet<>();
		for (var change : recordChanges) {
			if (!recordKeys.add(change.key())) {
				throw new IllegalArgumentException("Duplicate planned record change: " + change.key());
			}
		}
		Set<String> assetPaths = new HashSet<>();
		for (var change : assetChanges) {
			if (!assetPaths.add(change.managedPath())) {
				throw new IllegalArgumentException("Duplicate planned asset change: " + change.managedPath());
			}
		}
	}

	/**
	 * Creates an explicit approval of this exact reviewed plan.
	 *
	 * @param approvalNote non-blank approval rationale
	 * @return immutable approval bound to this plan
	 * @throws IllegalArgumentException if the approval note is invalid
	 */
	public Approval approve(String approvalNote) {
		return new Approval(this, approvalNote);
	}

	/**
	 * Counts planned binary asset changes of the specified kind.
	 *
	 * @param kind requested change classification
	 * @return number of matching asset changes
	 * @throws NullPointerException if kind is null
	 */
	public long countAssets(SubjectRefreshChangeClassifier.ChangeKind kind) {
		Objects.requireNonNull(kind, "kind");
		return assetChanges.stream().filter(change -> change.kind() == kind).count();
	}

	/**
	 * Counts planned domain record changes of the specified kind.
	 *
	 * @param kind requested change classification
	 * @return number of matching record changes
	 * @throws NullPointerException if kind is null
	 */
	public long countRecords(SubjectRefreshChangeClassifier.ChangeKind kind) {
		Objects.requireNonNull(kind, "kind");
		return recordChanges.stream().filter(change -> change.kind() == kind).count();
	}

	/**
	 * Counts planned changes for one portable entity type.
	 *
	 * @param recordType requested portable entity type
	 * @param kind       requested change classification
	 * @return number of matching record changes
	 * @throws NullPointerException if either argument is null
	 */
	public long countRecords(String recordType, SubjectRefreshChangeClassifier.ChangeKind kind) {
		Objects.requireNonNull(recordType, "recordType");
		Objects.requireNonNull(kind, "kind");
		return recordChanges.stream().filter(change -> change.key().recordType().equals(recordType))
				.filter(change -> change.kind() == kind).count();
	}

	/**
	 * Indicates whether the plan includes authoritative data changes.
	 *
	 * <p>
	 * A newer snapshot may contain identical domain content while still requiring
	 * advancement of the accepted package evidence.
	 *
	 * @return true if any record, revision or asset changes
	 */
	public boolean hasContentChanges() {
		return recordChanges.stream()
				.anyMatch(change -> change.kind() != SubjectRefreshChangeClassifier.ChangeKind.UNCHANGED)
				|| assetChanges.stream()
						.anyMatch(change -> change.kind() != SubjectRefreshChangeClassifier.ChangeKind.UNCHANGED);
	}

	/**
	 * Requires a regenerated plan to match this reviewed plan exactly.
	 *
	 * <p>
	 * The comparison includes originating evidence, destination content, identity
	 * mappings and every proposed operation.
	 *
	 * @param actual regenerated current plan
	 * @throws PackageFormatException if any reviewed evidence or proposed operation
	 *                                has changed
	 * @throws NullPointerException   if actual is null
	 */
	public void requireUnchanged(SubjectRefreshPlan actual) throws PackageFormatException {
		Objects.requireNonNull(actual, "actual");
		if (!evidence.equals(actual.evidence())) {
			throw new PackageFormatException("Subject refresh package or authority " + "changed after review");
		}
		if (!destinationContentSha256.equals(actual.destinationContentSha256())) {
			throw new PackageFormatException("Destination Subject content changed after review");
		}
		if (!identities.equals(actual.identities())) {
			throw new PackageFormatException("Subject refresh identity mappings changed after review");
		}
		if (!recordChanges.equals(actual.recordChanges()) || !assetChanges.equals(actual.assetChanges())) {
			throw new PackageFormatException("Subject refresh operations changed after review");
		}
	}

	/**
	 * Explicit approval of an immutable Subject refresh plan.
	 *
	 * @param plan         exact reviewed plan
	 * @param approvalNote non-blank approval rationale
	 */
	public record Approval(SubjectRefreshPlan plan, String approvalNote) {

		/**
		 * Validates the approved plan and approval rationale.
		 *
		 * @throws NullPointerException     if plan is null
		 * @throws IllegalArgumentException if the note is blank or exceeds 1000
		 *                                  characters
		 */
		public Approval {
			Objects.requireNonNull(plan, "plan");
			if (approvalNote == null || approvalNote.isBlank() || approvalNote.strip().length() > 1000) {
				throw new IllegalArgumentException("Approval note must contain " + "1 to 1000 characters");
			}
			approvalNote = approvalNote.strip();
		}

		/**
		 * Revalidates the exact approved comparison.
		 *
		 * @param actual regenerated current plan
		 * @throws PackageFormatException if approval is stale
		 */
		public void requireUnchanged(SubjectRefreshPlan actual) throws PackageFormatException {
			plan.requireUnchanged(actual);
		}
	}
}
