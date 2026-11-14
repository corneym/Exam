package au.edu.eq.questionbank.service.workpackage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import au.edu.eq.questionbank.service.workpackage.CurriculumReconciler.Classification;
import au.edu.eq.questionbank.service.workpackage.CurriculumReconciler.Proposal;
import au.edu.eq.questionbank.service.workpackage.SubjectImportSnapshotFingerprint.Fingerprint;
import au.edu.eq.questionbank.service.workpackage.SubjectReconciliationPreviewService.AssetEvidence;
import au.edu.eq.questionbank.service.workpackage.SubjectReconciliationReview.Choice;
import au.edu.eq.questionbank.service.workpackage.SubjectReconciliationReview.Decision;
import au.edu.eq.questionbank.service.workpackage.SubjectReconciliationReview.Key;

/**
 * Converts complete coordinator review decisions into a non-destructive Subject
 * import execution plan.
 *
 * <p>
 * The planner supports inserting new records, linking approved aliases,
 * preserving identical master content, copying new assets and recording
 * rejected proposals.
 *
 * <p>
 * Existing master records and conflicting files are never overwritten.
 * Unsupported accepted conflicts cause planning to fail.
 *
 * <p>
 * Planning performs no database or filesystem mutation. A later publication
 * service must revalidate all identities, dependencies and master state before
 * committing the resulting operations.
 */
public final class SubjectImportApprovalPlan {

	private final List<Action> actions;
	private final String approvalSha256;
	private final Fingerprint reviewedIncoming;
	private final Fingerprint reviewedMaster;

	/**
	 * Constructs an immutable approved plan.
	 */
	private SubjectImportApprovalPlan(Fingerprint reviewedMaster, Fingerprint reviewedIncoming, List<Action> actions) {
		this.reviewedMaster = reviewedMaster;
		this.reviewedIncoming = reviewedIncoming;
		this.actions = List.copyOf(actions);
		this.approvalSha256 = fingerprintApproval(reviewedMaster, reviewedIncoming, actions);
	}

	/**
	 * Builds a non-destructive import plan without conflict overrides.
	 *
	 * <p>
	 * Accepted conflicts must use the overload that supplies explicit,
	 * machine-readable resolutions. They are never inferred from free text.
	 *
	 * @param review           complete coordinator review
	 * @param reviewedMaster   reviewed master fingerprint
	 * @param reviewedIncoming reviewed incoming fingerprint
	 * @return non-destructive import plan
	 * @throws IllegalStateException if the review is incomplete or an accepted
	 *                               conflict lacks a resolution
	 */
	public static SubjectImportApprovalPlan prepare(SubjectReconciliationReview review, Fingerprint reviewedMaster,
			Fingerprint reviewedIncoming) {
		return prepare(review, reviewedMaster, reviewedIncoming, Map.of());
	}

	/**
	 * Builds an import plan with explicitly authorised conflict resolutions.
	 *
	 * <p>
	 * Every accepted conflict requires exactly one resolution bound to the original
	 * review key and master identity or asset checksum. Rejected and
	 * non-conflicting entries cannot acquire overrides.
	 *
	 * <p>
	 * No resolution can overwrite an existing master record or file.
	 *
	 * @param review           complete coordinator review
	 * @param reviewedMaster   verified reviewed master snapshot
	 * @param reviewedIncoming verified reviewed incoming snapshot
	 * @param resolutions      explicit accepted-conflict resolutions
	 * @return immutable, non-destructive import plan
	 * @throws IllegalStateException    if review or resolution is unsafe
	 * @throws IllegalArgumentException if an override targets an ineligible review
	 *                                  item
	 */
	public static SubjectImportApprovalPlan prepare(SubjectReconciliationReview review, Fingerprint reviewedMaster,
			Fingerprint reviewedIncoming, Map<Key, ConflictResolution> resolutions) {
		Objects.requireNonNull(review, "review");
		Objects.requireNonNull(reviewedMaster, "reviewedMaster");
		Objects.requireNonNull(reviewedIncoming, "reviewedIncoming");
		Objects.requireNonNull(resolutions, "resolutions");
		var preview = review.preview();
		var state = review.snapshot();
		if (!state.complete() || !state.dependencySafe()) {
			throw new IllegalStateException("Import requires complete, dependency-safe coordinator decisions");
		}
		if (!preview.masterPackageId().equals(reviewedMaster.packageId())
				|| !preview.incomingPackageId().equals(reviewedIncoming.packageId())) {
			throw new IllegalStateException("Review does not belong to the verified snapshot pair");
		}
		if (!preview.subjectName().equals(reviewedMaster.subjectName())
				|| !preview.subjectName().equals(reviewedIncoming.subjectName())) {
			throw new IllegalStateException("Reviewed Subject names disagree");
		}
		List<Action> actions = new ArrayList<>();
		Set<String> canonicalTargets = new HashSet<>();
		Set<Key> consumedResolutions = new HashSet<>();
		for (Proposal proposal : preview.proposals()) {
			Key key = SubjectReconciliationReview.recordKey(proposal.recordType(), proposal.incomingId());
			Choice choice = review.choice(key);
			ConflictResolution resolution = resolutions.get(key);
			Operation operation;
			String justification = choice.justification();
			if (choice.decision() == Decision.REJECT) {
				operation = Operation.SKIP;
			} else if (proposal.classification() == Classification.CONFLICT) {
				if (resolution == null) {
					throw new IllegalStateException("Accepted conflict needs an explicit resolution: " + key);
				}
				operation = recordConflictOperation(proposal, resolution);
				consumedResolutions.add(key);
				justification = justification + " | Resolution: " + resolution.mode() + " | "
						+ resolution.explanation();
			} else {
				operation = recordOperation(proposal);
			}
			if (operation == Operation.LINK_ALIAS) {
				String canonicalKey = proposal.recordType() + ":" + proposal.masterId();
				if (!canonicalTargets.add(canonicalKey)) {
					throw new IllegalStateException("Multiple incoming records claim master identity " + canonicalKey);
				}
			}
			actions.add(new Action(key, choice.decision(), operation, proposal.classification().name(),
					proposal.incomingId(), proposal.masterId(), null, null, null, proposal.reason(), justification));
		}
		for (AssetEvidence asset : preview.assetEvidence()) {
			Key key = SubjectReconciliationReview.assetKey(asset);
			Choice choice = review.choice(key);
			ConflictResolution resolution = resolutions.get(key);
			Operation operation;
			String justification = choice.justification();
			if (choice.decision() == Decision.REJECT) {
				operation = Operation.SKIP;
			} else if (asset.classification() == Classification.CONFLICT) {
				if (resolution == null) {
					throw new IllegalStateException("Accepted asset conflict needs a resolution: " + key);
				}
				operation = assetConflictOperation(asset, resolution);
				consumedResolutions.add(key);
				justification = justification + " | Resolution: " + resolution.mode() + " | "
						+ resolution.explanation();
			} else {
				operation = assetOperation(asset);
			}
			actions.add(new Action(key, choice.decision(), operation, asset.classification().name(),
					asset.incomingOwnerId(), null, asset.incomingPath(), asset.masterPath(), asset.incomingSha256(),
					asset.reason(), justification));
		}
		if (!consumedResolutions.equals(resolutions.keySet())) {
			throw new IllegalArgumentException("Conflict resolutions contain missing, stale or ineligible keys");
		}
		checkPreservedDependencies(preview, actions);
		actions.sort(Comparator.comparing((Action action) -> action.key().kind())
				.thenComparing(action -> action.key().value()));
		return new SubjectImportApprovalPlan(reviewedMaster, reviewedIncoming, actions);
	}

	/**
	 * Resolves an accepted asset conflict without replacing master bytes.
	 */
	private static Operation assetConflictOperation(AssetEvidence asset, ConflictResolution resolution) {
		if (resolution.mode() != ConflictMode.PRESERVE_MASTER) {
			throw new IllegalStateException("Unsupported asset conflict operation: " + resolution.mode());
		}
		if (asset.masterPath() == null || asset.masterSha256() == null
				|| !asset.masterSha256().equals(resolution.expectedMasterSha256())) {
			throw new IllegalStateException("Cannot preserve an unidentified or changed master asset");
		}
		if (resolution.expectedMasterId() != null) {
			throw new IllegalArgumentException("Asset conflict resolution must use checksum evidence");
		}
		return Operation.PRESERVE_CONFLICT;
	}

	/**
	 * Selects a safe operation for an accepted asset.
	 */
	private static Operation assetOperation(AssetEvidence asset) {
		if (asset.classification() == Classification.CONFLICT) {
			throw new IllegalStateException("Conflicting asset replacement is not supported: " + asset.incomingPath());
		}
		if (asset.masterSha256() != null && asset.masterSha256().equals(asset.incomingSha256())) {
			return Operation.KEEP_ASSET;
		}
		if (asset.classification() == Classification.IDENTICAL) {
			throw new IllegalStateException("Identical asset proposal has no matching master bytes");
		}
		if (asset.masterPath() != null) {
			throw new IllegalStateException(
					"Asset destination conflicts with existing master content: " + asset.masterPath());
		}
		return Operation.COPY_ASSET;
	}

	/**
	 * Rejects an import that would depend on discarded incoming content.
	 *
	 * <p>
	 * Accepted incoming descendants cannot rely on an incoming parent that was
	 * resolved by preserving the master without aliasing.
	 */
	private static void checkPreservedDependencies(SubjectReconciliationPreviewService.Result preview,
			List<Action> actions) {
		Map<Key, Action> indexed = new HashMap<>();
		Set<Key> preserved = new HashSet<>();
		for (Action action : actions) {
			indexed.put(action.key(), action);
			if (action.operation() == Operation.PRESERVE_CONFLICT) {
				preserved.add(action.key());
			}
		}
		for (var dependency : preview.dependencies()) {
			Key owner = SubjectReconciliationReview.recordKey(dependency.ownerType(), dependency.ownerId());
			Key related = SubjectReconciliationReview.recordKey(dependency.relatedType(), dependency.relatedId());
			Action ownerAction = indexed.get(owner);
			if (preserved.contains(related) && ownerAction != null && ownerAction.decision() == Decision.ACCEPT
					&& ownerAction.operation() != Operation.PRESERVE_CONFLICT) {
				throw new IllegalStateException("Accepted record depends on preserved conflicting content: " + owner);
			}
		}
		for (Proposal proposal : preview.proposals()) {
			if (proposal.incomingParentId() == null) {
				continue;
			}
			Action child = indexed
					.get(SubjectReconciliationReview.recordKey(proposal.recordType(), proposal.incomingId()));
			if (child == null || child.decision() != Decision.ACCEPT
					|| child.operation() == Operation.PRESERVE_CONFLICT) {
				continue;
			}
			boolean parentPreserved = preview.proposals().stream()
					.anyMatch(parent -> parent.incomingId().equals(proposal.incomingParentId()) && preserved
							.contains(SubjectReconciliationReview.recordKey(parent.recordType(), parent.incomingId())));
			if (parentPreserved) {
				throw new IllegalStateException(
						"Accepted child cannot use a preserved conflicting parent: " + child.key());
			}
		}
		for (AssetEvidence asset : preview.assetEvidence()) {
			Key assetKey = SubjectReconciliationReview.assetKey(asset);
			if (!preserved.contains(assetKey) || asset.ownerType() == null) {
				continue;
			}
			Action owner = indexed
					.get(SubjectReconciliationReview.recordKey(asset.ownerType(), asset.incomingOwnerId()));
			if (owner != null && owner.operation() == Operation.INSERT_RECORD) {
				throw new IllegalStateException("New imported record cannot use discarded incoming asset: " + assetKey);
			}
		}
	}

	/**
	 * Calculates an approval digest over decisions and source evidence.
	 */
	private static String fingerprintApproval(Fingerprint master, Fingerprint incoming, List<Action> actions) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			update(digest, "subject-import-approval-v1");
			update(digest, master.contentSha256());
			update(digest, incoming.archiveSha256());
			for (Action action : actions) {
				update(digest, action.key().kind());
				update(digest, action.key().value());
				update(digest, action.decision().name());
				update(digest, action.operation().name());
				update(digest, action.classification());
				update(digest, Objects.toString(action.incomingId(), ""));
				update(digest, Objects.toString(action.masterId(), ""));
				update(digest, Objects.toString(action.incomingAssetPath(), ""));
				update(digest, Objects.toString(action.masterAssetPath(), ""));
				update(digest, Objects.toString(action.incomingAssetSha256(), ""));
				update(digest, action.reason());
				update(digest, action.justification());
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	/**
	 * Selects a supported, explicitly authorised record conflict operation.
	 */
	private static Operation recordConflictOperation(Proposal proposal, ConflictResolution resolution) {
		if (proposal.masterId() == null || !proposal.masterId().equals(resolution.expectedMasterId())) {
			throw new IllegalStateException("Conflict resolution does not identify the reviewed master record");
		}
		if (resolution.expectedMasterSha256() != null) {
			throw new IllegalArgumentException("Record conflicts must be bound to master UUIDs");
		}
		return switch (resolution.mode()) {
		case LINK_TO_MASTER -> {

			// Reuse existing structural identities while keeping the
			// master curriculum and provider values unchanged.
			boolean supported = switch (proposal.recordType()) {
			case "SUBJECT", "SYLLABUS_VERSION", "CURRICULUM_NODE", "EXAM_PROVIDER" -> true;
			default -> false;
			};
			if (!supported) {
				throw new IllegalStateException("Conflict aliasing is not yet supported for " + proposal.recordType());
			}
			yield Operation.LINK_ALIAS;
		}
		case PRESERVE_MASTER -> Operation.PRESERVE_CONFLICT;
		case IMPORT_DISTINCT, REPLACE_MASTER ->
			throw new IllegalStateException("Conflict operation is not yet safely executable: " + resolution.mode());
		};
	}

	/**
	 * Selects the only supported non-destructive record operations.
	 */
	private static Operation recordOperation(Proposal proposal) {
		return switch (proposal.classification()) {
		case NEW, COMPATIBLE_ADDITION -> {
			if (proposal.masterId() != null) {
				throw new IllegalStateException("An addition cannot replace a master record: " + proposal.recordType());
			}
			yield Operation.INSERT_RECORD;
		}
		case IDENTICAL -> {
			if (proposal.masterId() == null) {
				throw new IllegalStateException(
						"Identical record has no canonical master identity: " + proposal.recordType());
			}
			yield Operation.LINK_ALIAS;
		}
		case CONFLICT -> throw new IllegalStateException("Conflicting master record cannot be overwritten: "
				+ proposal.recordType() + ":" + proposal.incomingId());
		};
	}

	/**
	 * Appends an unambiguous, length-delimited digest field.
	 */
	private static void update(MessageDigest digest, String value) {
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
		digest.update(bytes);
	}

	/**
	 * Returns the ordered, fully reviewed import operations.
	 *
	 * @return immutable operations
	 */
	public List<Action> actions() {
		return actions;
	}

	/**
	 * Returns a deterministic fingerprint of the approved plan.
	 *
	 * @return lower-case SHA-256 digest
	 */
	public String approvalSha256() {
		return approvalSha256;
	}

	/**
	 * Revalidates the incoming archive against its exact approved bytes.
	 *
	 * @param archive currently supplied incoming archive
	 * @throws IOException           if archive validation fails
	 * @throws IllegalStateException if the package has changed
	 */
	public void requireFreshIncoming(Path archive) throws IOException {
		new SubjectImportSnapshotFingerprint().requireSamePackage(reviewedIncoming, archive);
	}

	/**
	 * Revalidates the master against a newly exported Subject snapshot.
	 *
	 * @param archive fresh master Subject snapshot
	 * @throws IOException           if archive validation fails
	 * @throws IllegalStateException if master content has changed
	 */
	public void requireFreshMaster(Path archive) throws IOException {
		new SubjectImportSnapshotFingerprint().requireSameContent(reviewedMaster, archive);
	}

	/**
	 * Returns the approved incoming package fingerprint.
	 *
	 * @return incoming fingerprint
	 */
	public Fingerprint reviewedIncoming() {
		return reviewedIncoming;
	}

	/**
	 * Returns the original reviewed master fingerprint.
	 *
	 * @return master fingerprint
	 */
	public Fingerprint reviewedMaster() {
		return reviewedMaster;
	}

	/**
	 * Explicit coordinator operations for an accepted conflict.
	 *
	 * <p>
	 * Not every operation is executable in the first import release. Unsupported
	 * operations fail closed during planning.
	 */
	public enum ConflictMode {
		/** Import independently without replacing an existing entity. */
		IMPORT_DISTINCT,
		/** Preserve existing master values and approve a canonical alias. */
		LINK_TO_MASTER,
		/** Preserve existing master content without importing the conflict. */
		PRESERVE_MASTER,
		/** Explicit replacement, currently blocked pending recovery support. */
		REPLACE_MASTER
	}

	/**
	 * A specific operation selected for one accepted reconciliation conflict.
	 *
	 * @param mode                 requested resolution operation
	 * @param expectedMasterId     reviewed master record UUID, if applicable
	 * @param expectedMasterSha256 reviewed master asset checksum, if applicable
	 * @param explanation          coordinator's written resolution rationale
	 */
	public record ConflictResolution(ConflictMode mode, UUID expectedMasterId, String expectedMasterSha256,
			String explanation) {

		/**
		 * Validates an explicit resolution independently of the proposal.
		 */
		public ConflictResolution {
			Objects.requireNonNull(mode, "mode");
			if (explanation == null || explanation.isBlank() || explanation.strip().length() < 12
					|| explanation.length() > 4000) {
				throw new IllegalArgumentException("Conflict resolution requires 12–4000 characters");
			}
			explanation = explanation.strip();
			if (expectedMasterSha256 != null && !expectedMasterSha256.matches("[0-9a-f]{64}")) {
				throw new IllegalArgumentException("Expected master asset checksum must be SHA-256");
			}
		}
	}

	/**
	 * Approved, non-destructive operations available to an import planner.
	 */
	public enum Operation {
		/** Publish a new verified binary asset. */
		COPY_ASSET,
		/** Insert an independent new canonical master record. */
		INSERT_RECORD,
		/** Retain an existing verified asset with identical content. */
		KEEP_ASSET,
		/** Link an approved origin identity to a master canonical identity. */
		LINK_ALIAS,
		/** Preserve conflicting master content without importing incoming data. */
		PRESERVE_CONFLICT,
		/** Skip an explicitly rejected incoming proposal. */
		SKIP
	}

	/**
	 * One immutable import-plan action.
	 *
	 * @param key                 original coordinator review key
	 * @param decision            coordinator decision
	 * @param operation           supported non-destructive operation
	 * @param classification      original comparison classification
	 * @param incomingId          incoming record or asset-owner UUID
	 * @param masterId            proposed canonical master UUID
	 * @param incomingAssetPath   incoming logical asset path, if applicable
	 * @param masterAssetPath     matching master asset path, if applicable
	 * @param incomingAssetSha256 incoming verified asset digest, if applicable
	 * @param reason              original reconciliation evidence
	 * @param justification       coordinator's explicit explanation
	 */
	public record Action(Key key, Decision decision, Operation operation, String classification, UUID incomingId,
			UUID masterId, String incomingAssetPath, String masterAssetPath, String incomingAssetSha256, String reason,
			String justification) {

		/**
		 * Validates the immutable operation's review provenance.
		 */
		public Action {
			Objects.requireNonNull(key, "key");
			Objects.requireNonNull(decision, "decision");
			Objects.requireNonNull(operation, "operation");
			Objects.requireNonNull(classification, "classification");
			if (reason == null || reason.isBlank()) {
				throw new IllegalArgumentException("Import action must retain reconciliation evidence");
			}
			justification = justification == null ? "" : justification;
			if (decision == Decision.REJECT && operation != Operation.SKIP) {
				throw new IllegalArgumentException("Rejected proposals must not publish changes");
			}
			if (decision == Decision.ACCEPT && operation == Operation.SKIP) {
				throw new IllegalArgumentException("Accepted proposals require a supported operation");
			}
		}
	}
}
