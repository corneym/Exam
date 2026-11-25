package au.edu.eq.questionbank.service.workpackage;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
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
			throw new PackageFormatException("Subject refresh package or authority changed after review");
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
				throw new IllegalArgumentException("Approval note must contain 1 to 1000 characters");
			}
			approvalNote = approvalNote.strip();
		}

		/**
		 * Calculates a deterministic fingerprint of the complete approved refresh.
		 *
		 * @return lowercase SHA-256 approval fingerprint
		 * @throws IOException if canonical record serialisation fails
		 */
		public String approvalSha256() throws IOException {
			MessageDigest digest;
			try {
				digest = MessageDigest.getInstance("SHA-256");
			} catch (NoSuchAlgorithmException failure) {
				throw new IllegalStateException("SHA-256 is unavailable", failure);
			}
			try (DataOutputStream output = new DataOutputStream(
					new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
				output.writeUTF("subject-refresh-approval-v1");
				var authority = plan.evidence().authority();
				output.writeLong(authority.subjectLocalId());
				output.writeUTF(authority.originInstallationId().toString());
				output.writeUTF(authority.originSubjectPortableId().toString());
				output.writeUTF(authority.latestPackageId().toString());
				output.writeUTF(authority.archiveSha256());
				output.writeUTF(authority.importedAt().toString());
				writeSnapshotEvidence(output, plan.evidence().accepted());
				writeSnapshotEvidence(output, plan.evidence().incoming());
				output.writeUTF(plan.evidence().outcome().name());
				output.writeUTF(plan.destinationContentSha256());
				output.writeUTF(approvalNote);

				// Canonical ordering makes the digest independent of Map ordering.
				var mappings = plan.identities().entrySet().stream()
						.sorted(Comparator
								.comparing((Map.Entry<SubjectRefreshChangeClassifier.RecordKey, UUID> entry) -> entry
										.getKey().recordType())
								.thenComparing(entry -> entry.getKey().recordId()))
						.toList();
				output.writeInt(mappings.size());
				for (var mapping : mappings) {
					output.writeUTF(mapping.getKey().recordType());
					output.writeUTF(mapping.getKey().recordId().toString());
					output.writeUTF(mapping.getValue().toString());
				}
				var records = plan.recordChanges().stream()
						.sorted(Comparator.comparing(
								(SubjectRefreshChangeClassifier.RecordChange change) -> change.key().recordType())
								.thenComparing(change -> change.key().recordId()))
						.toList();
				output.writeInt(records.size());
				ExamWorkJsonlCodec codec = new ExamWorkJsonlCodec();
				for (var change : records) {
					output.writeUTF(change.key().recordType());
					output.writeUTF(change.key().recordId().toString());
					output.writeUTF(change.kind().name());
					writeRecord(output, codec, change.current());
					writeRecord(output, codec, change.incoming());
				}
				var assets = plan.assetChanges().stream()
						.sorted(Comparator.comparing(SubjectRefreshChangeClassifier.AssetChange::managedPath)).toList();
				output.writeInt(assets.size());
				for (var change : assets) {
					output.writeUTF(change.managedPath());
					output.writeUTF(change.kind().name());
					writeAssetContent(output, change.current());
					writeAssetContent(output, change.incoming());
				}
			}
			return HexFormat.of().formatHex(digest.digest());
		}

		/**
		 * Requires the supplied archive to retain the exact originating evidence
		 * reviewed and approved for this refresh.
		 *
		 * @param archive incoming Subject snapshot
		 * @throws IOException          if archive validation fails or approval is stale
		 * @throws NullPointerException if archive is null
		 */
		public void requireFreshIncoming(Path archive) throws IOException {
			Objects.requireNonNull(archive, "archive");
			var actual = new SubjectImportSnapshotFingerprint().inspectEvidence(archive);
			if (!plan.evidence().incoming().equals(actual)) {
				throw new PackageFormatException("Incoming Subject refresh archive changed after approval");
			}
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

		/**
		 * Writes an unambiguous representation of optional asset evidence.
		 */
		private static void writeAssetContent(DataOutputStream output,
				SubjectRefreshChangeClassifier.AssetContent content) throws IOException {
			output.writeBoolean(content != null);
			if (content != null) {
				output.writeUTF(content.sha256());
				output.writeLong(content.size());
			}
		}

		/**
		 * Writes an optional record using canonical JSONL representation.
		 */
		private static void writeRecord(DataOutputStream output, ExamWorkJsonlCodec codec, ExchangeRecord record)
				throws IOException {
			output.writeBoolean(record != null);
			if (record != null) {
				codec.writeRecords(List.of(record), output);
			}
		}

		/**
		 * Writes exact archive, originating identity and snapshot evidence.
		 */
		private static void writeSnapshotEvidence(DataOutputStream output,
				SubjectImportSnapshotFingerprint.SnapshotEvidence evidence) throws IOException {
			var fingerprint = evidence.fingerprint();
			output.writeUTF(fingerprint.packageId().toString());
			output.writeUTF(fingerprint.originInstallationId().toString());
			output.writeUTF(fingerprint.subjectId().toString());
			output.writeUTF(fingerprint.subjectName());
			output.writeUTF(fingerprint.contentSha256());
			output.writeUTF(fingerprint.archiveSha256());
			output.writeUTF(evidence.createdAt().toString());
		}
	}
}
