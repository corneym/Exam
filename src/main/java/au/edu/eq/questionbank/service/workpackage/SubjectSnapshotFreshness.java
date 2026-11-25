package au.edu.eq.questionbank.service.workpackage;

import java.util.Objects;

import au.edu.eq.questionbank.service.workpackage.SubjectImportSnapshotFingerprint.Fingerprint;
import au.edu.eq.questionbank.service.workpackage.SubjectImportSnapshotFingerprint.SnapshotEvidence;

/**
 * Evaluates the chronological eligibility of a subsequent authoritative Subject
 * snapshot.
 *
 * <p>
 * Freshness is evaluated against the last accepted originating snapshot, not
 * against the time at which the destination imported it. This service performs
 * no database or filesystem mutations.
 */
public final class SubjectSnapshotFreshness {

	/**
	 * Creates a stateless snapshot freshness evaluator.
	 */
	public SubjectSnapshotFreshness() {
	}

	/**
	 * Classifies an incoming snapshot against previously accepted originating
	 * evidence.
	 *
	 * <p>
	 * Matching provenance and an immutable Subject name are required before
	 * chronological evidence is considered. Reuse of a package identity with
	 * different archive bytes is always a conflict.
	 *
	 * @param accepted latest accepted originating snapshot evidence
	 * @param incoming verified incoming snapshot evidence
	 * @return chronological and identity classification
	 * @throws NullPointerException if either argument is null
	 */
	public Outcome classify(SnapshotEvidence accepted, SnapshotEvidence incoming) {
		Objects.requireNonNull(accepted, "accepted");
		Objects.requireNonNull(incoming, "incoming");
		Fingerprint previous = accepted.fingerprint();
		Fingerprint candidate = incoming.fingerprint();
		if (!previous.originInstallationId().equals(candidate.originInstallationId())
				|| !previous.subjectId().equals(candidate.subjectId())
				|| !previous.subjectName().equals(candidate.subjectName())) {
			return Outcome.AUTHORITY_MISMATCH;
		}

		// Package identity has precedence over chronology.
		// A reused UUID cannot legitimise substituted archive bytes.
		if (previous.packageId().equals(candidate.packageId())) {
			return previous.equals(candidate) && accepted.createdAt().equals(incoming.createdAt())
					? Outcome.IDENTICAL_REPLAY
					: Outcome.PACKAGE_ID_CONFLICT;
		}
		int chronology = incoming.createdAt().compareTo(accepted.createdAt());
		if (chronology < 0) {
			return Outcome.STALE;
		}
		if (chronology == 0) {
			return previous.contentSha256().equals(candidate.contentSha256()) ? Outcome.SAME_SNAPSHOT_TIME
					: Outcome.TIMESTAMP_CONFLICT;
		}

		// A newer export may contain unchanged authoritative content.
		// Distinguish this from an actual graph or asset change so
		// the later approval workflow can explain it accurately.
		return previous.contentSha256().equals(candidate.contentSha256()) ? Outcome.NEWER_UNCHANGED
				: Outcome.NEWER_CHANGED;
	}

	/**
	 * Requires a genuinely newer snapshot from the same authority.
	 *
	 * <p>
	 * Passing this check establishes chronological eligibility only. Explicit
	 * approval, destination comparison, verified backup and transactional
	 * revalidation are still required.
	 *
	 * @param accepted latest accepted originating snapshot evidence
	 * @param incoming verified incoming snapshot evidence
	 * @return classification of the eligible newer snapshot
	 * @throws PackageFormatException if the incoming snapshot is not eligible for
	 *                                refresh
	 * @throws NullPointerException   if either argument is null
	 */
	public Outcome requireNewer(SnapshotEvidence accepted, SnapshotEvidence incoming) throws PackageFormatException {
		Outcome outcome = classify(accepted, incoming);
		return switch (outcome) {
		case NEWER_UNCHANGED, NEWER_CHANGED -> outcome;
		case AUTHORITY_MISMATCH -> throw new PackageFormatException(
				"The incoming snapshot does not match " + "the established Subject authority.");
		case IDENTICAL_REPLAY -> throw new PackageFormatException("The incoming snapshot has already been accepted.");
		case PACKAGE_ID_CONFLICT -> throw new PackageFormatException(
				"The incoming package UUID has been reused " + "with different snapshot evidence.");
		case STALE ->
			throw new PackageFormatException("The incoming snapshot is older than " + "the latest accepted snapshot.");
		case SAME_SNAPSHOT_TIME -> throw new PackageFormatException(
				"The incoming snapshot has the same creation " + "time and content as the accepted snapshot.");
		case TIMESTAMP_CONFLICT -> throw new PackageFormatException(
				"The incoming snapshot has the same creation " + "time but different authoritative content.");
		};
	}

	/**
	 * Chronological and identity classification of an incoming snapshot.
	 */
	public enum Outcome {
		/** Originating installation, Subject identity or name differs. */
		AUTHORITY_MISMATCH,
		/** Exact previously accepted package and originating evidence. */
		IDENTICAL_REPLAY,
		/** Previously accepted package UUID has inconsistent evidence. */
		PACKAGE_ID_CONFLICT,
		/** Incoming snapshot predates the accepted snapshot. */
		STALE,
		/** Different package with identical creation time and content. */
		SAME_SNAPSHOT_TIME,
		/** Equal creation timestamps but different authoritative content. */
		TIMESTAMP_CONFLICT,
		/** Newer export with unchanged semantic Subject content. */
		NEWER_UNCHANGED,
		/** Newer export with changed semantic Subject content. */
		NEWER_CHANGED
	}
}
