package au.edu.eq.questionbank.service.workpackage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import au.edu.eq.questionbank.service.workpackage.SubjectImportSnapshotFingerprint.Fingerprint;
import au.edu.eq.questionbank.service.workpackage.SubjectImportSnapshotFingerprint.SnapshotEvidence;
import au.edu.eq.questionbank.service.workpackage.SubjectSnapshotFreshness.Outcome;

class SubjectSnapshotFreshnessTest {

	private static final Instant ACCEPTED_TIME = Instant.parse("2026-11-20T01:00:00Z");
	private static final String ARCHIVE_DIGEST = "a".repeat(64);
	private static final String CHANGED_ARCHIVE_DIGEST = "b".repeat(64);
	private static final String CHANGED_CONTENT_DIGEST = "d".repeat(64);
	private static final String CONTENT_DIGEST = "c".repeat(64);
	private static final UUID INSTALLATION_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
	private static final UUID PACKAGE_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
	private static final UUID SUBJECT_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
	private static final UUID SUBSEQUENT_PACKAGE_ID = UUID.fromString("20000000-0000-4000-8000-000000000002");
	private final SubjectSnapshotFreshness freshness = new SubjectSnapshotFreshness();

	@Test
	void acceptsNewerChangedSnapshot() throws Exception {
		SnapshotEvidence incoming = evidence(SUBSEQUENT_PACKAGE_ID, CHANGED_CONTENT_DIGEST, CHANGED_ARCHIVE_DIGEST,
				ACCEPTED_TIME.plusSeconds(60));
		assertEquals(Outcome.NEWER_CHANGED, freshness.classify(accepted(), incoming));
		assertEquals(Outcome.NEWER_CHANGED, freshness.requireNewer(accepted(), incoming));
	}

	@Test
	void acceptsNewerUnchangedSnapshotAsChronologicallyEligible() throws Exception {
		SnapshotEvidence incoming = evidence(SUBSEQUENT_PACKAGE_ID, CONTENT_DIGEST, CHANGED_ARCHIVE_DIGEST,
				ACCEPTED_TIME.plusSeconds(60));
		assertEquals(Outcome.NEWER_UNCHANGED, freshness.requireNewer(accepted(), incoming));
	}

	@Test
	void recognisesIdenticalReplayWithoutAuthorisingRefresh() {
		SnapshotEvidence accepted = accepted();
		assertEquals(Outcome.IDENTICAL_REPLAY, freshness.classify(accepted, accepted));
		assertThrows(PackageFormatException.class, () -> freshness.requireNewer(accepted, accepted));
	}

	@Test
	void rejectsChangedSubjectName() {
		SnapshotEvidence incoming = new SnapshotEvidence(new Fingerprint(SUBSEQUENT_PACKAGE_ID, INSTALLATION_ID,
				SUBJECT_ID, "Biological Science", CHANGED_CONTENT_DIGEST, CHANGED_ARCHIVE_DIGEST),
				ACCEPTED_TIME.plusSeconds(60));
		assertEquals(Outcome.AUTHORITY_MISMATCH, freshness.classify(accepted(), incoming));
		assertThrows(PackageFormatException.class, () -> freshness.requireNewer(accepted(), incoming));
	}

	@Test
	void rejectsDifferentOriginInstallation() {
		SnapshotEvidence incoming = new SnapshotEvidence(new Fingerprint(SUBSEQUENT_PACKAGE_ID, UUID.randomUUID(),
				SUBJECT_ID, "Biology", CONTENT_DIGEST, CHANGED_ARCHIVE_DIGEST), ACCEPTED_TIME.plusSeconds(60));
		assertEquals(Outcome.AUTHORITY_MISMATCH, freshness.classify(accepted(), incoming));
		assertThrows(PackageFormatException.class, () -> freshness.requireNewer(accepted(), incoming));
	}

	@Test
	void rejectsDifferentOriginSubjectIdentity() {
		SnapshotEvidence incoming = new SnapshotEvidence(new Fingerprint(SUBSEQUENT_PACKAGE_ID, INSTALLATION_ID,
				UUID.randomUUID(), "Biology", CONTENT_DIGEST, CHANGED_ARCHIVE_DIGEST), ACCEPTED_TIME.plusSeconds(60));
		assertEquals(Outcome.AUTHORITY_MISMATCH, freshness.classify(accepted(), incoming));
		assertThrows(PackageFormatException.class, () -> freshness.requireNewer(accepted(), incoming));
	}

	@Test
	void rejectsEqualTimestampWithChangedContent() {
		SnapshotEvidence incoming = evidence(SUBSEQUENT_PACKAGE_ID, CHANGED_CONTENT_DIGEST, CHANGED_ARCHIVE_DIGEST,
				ACCEPTED_TIME);
		assertEquals(Outcome.TIMESTAMP_CONFLICT, freshness.classify(accepted(), incoming));
		assertThrows(PackageFormatException.class, () -> freshness.requireNewer(accepted(), incoming));
	}

	@Test
	void rejectsEqualTimestampWithUnchangedContent() {
		SnapshotEvidence incoming = evidence(SUBSEQUENT_PACKAGE_ID, CONTENT_DIGEST, CHANGED_ARCHIVE_DIGEST,
				ACCEPTED_TIME);
		assertEquals(Outcome.SAME_SNAPSHOT_TIME, freshness.classify(accepted(), incoming));
		assertThrows(PackageFormatException.class, () -> freshness.requireNewer(accepted(), incoming));
	}

	@Test
	void rejectsPackageIdentityReusedWithChangedTimestamp() {
		SnapshotEvidence incoming = evidence(PACKAGE_ID, CONTENT_DIGEST, ARCHIVE_DIGEST, ACCEPTED_TIME.plusSeconds(60));
		assertEquals(Outcome.PACKAGE_ID_CONFLICT, freshness.classify(accepted(), incoming));
		assertThrows(PackageFormatException.class, () -> freshness.requireNewer(accepted(), incoming));
	}

	@Test
	void rejectsPackageIdentityReusedWithDifferentBytes() {
		SnapshotEvidence incoming = evidence(PACKAGE_ID, CHANGED_CONTENT_DIGEST, CHANGED_ARCHIVE_DIGEST,
				ACCEPTED_TIME.plusSeconds(60));
		assertEquals(Outcome.PACKAGE_ID_CONFLICT, freshness.classify(accepted(), incoming));
		assertThrows(PackageFormatException.class, () -> freshness.requireNewer(accepted(), incoming));
	}

	@Test
	void rejectsPreviouslyUnseenOlderSnapshot() {
		SnapshotEvidence incoming = evidence(SUBSEQUENT_PACKAGE_ID, CHANGED_CONTENT_DIGEST, CHANGED_ARCHIVE_DIGEST,
				ACCEPTED_TIME.minusSeconds(60));
		assertEquals(Outcome.STALE, freshness.classify(accepted(), incoming));
		assertThrows(PackageFormatException.class, () -> freshness.requireNewer(accepted(), incoming));
	}

	@Test
	void rejectsStaleSnapshotEvenWhenContentMatches() {
		SnapshotEvidence incoming = evidence(SUBSEQUENT_PACKAGE_ID, CONTENT_DIGEST, CHANGED_ARCHIVE_DIGEST,
				ACCEPTED_TIME.minusSeconds(60));
		assertEquals(Outcome.STALE, freshness.classify(accepted(), incoming));
		assertThrows(PackageFormatException.class, () -> freshness.requireNewer(accepted(), incoming));
	}

	private SnapshotEvidence accepted() {
		return evidence(PACKAGE_ID, CONTENT_DIGEST, ARCHIVE_DIGEST, ACCEPTED_TIME);
	}

	private SnapshotEvidence evidence(UUID packageId, String contentDigest, String archiveDigest, Instant createdAt) {
		return new SnapshotEvidence(
				new Fingerprint(packageId, INSTALLATION_ID, SUBJECT_ID, "Biology", contentDigest, archiveDigest),
				createdAt);
	}
}
