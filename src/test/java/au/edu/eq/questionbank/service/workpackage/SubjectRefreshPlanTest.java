package au.edu.eq.questionbank.service.workpackage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import au.edu.eq.questionbank.repository.sqlite.SqliteImportedSubjectAuthorityRepository;

class SubjectRefreshPlanTest {

	private static final String DESTINATION_DIGEST = "a".repeat(64);

	@Test
	void approvalFingerprintBindsApprovalRationale() throws Exception {
		SubjectRefreshPlan reviewed = createPlan();
		String first = reviewed.approve("Approved authoritative refresh").approvalSha256();
		String second = reviewed.approve("Different approval rationale").approvalSha256();
		assertTrue(first.matches("[0-9a-f]{64}"));
		assertFalse(first.equals(second));
	}

	@Test
	void approvalFingerprintBindsIdentityMappingsAndOperations() throws Exception {
		SubjectRefreshPlan reviewed = createPlan();
		String original = reviewed.approve("Approved refresh").approvalSha256();
		var mappings = new HashMap<>(reviewed.identities());
		mappings.replaceAll((_, _) -> UUID.randomUUID());
		var changedIdentities = new SubjectRefreshPlan(reviewed.evidence(), reviewed.destinationContentSha256(),
				mappings, reviewed.recordChanges(), reviewed.assetChanges());
		assertFalse(original.equals(changedIdentities.approve("Approved refresh").approvalSha256()));
		var changedRecords = new SubjectRefreshPlan(reviewed.evidence(), reviewed.destinationContentSha256(),
				reviewed.identities(), List.of(), reviewed.assetChanges());
		assertFalse(original.equals(changedRecords.approve("Approved refresh").approvalSha256()));
		var changedAssets = new SubjectRefreshPlan(reviewed.evidence(), reviewed.destinationContentSha256(),
				reviewed.identities(), reviewed.recordChanges(), List.of());
		assertFalse(original.equals(changedAssets.approve("Approved refresh").approvalSha256()));
		var changedDestination = new SubjectRefreshPlan(reviewed.evidence(), "f".repeat(64), reviewed.identities(),
				reviewed.recordChanges(), reviewed.assetChanges());
		assertFalse(original.equals(changedDestination.approve("Approved refresh").approvalSha256()));
	}

	@Test
	void approvalFingerprintIsDeterministicForRegeneratedPlan() throws Exception {
		SubjectRefreshPlan reviewed = createPlan();
		var regenerated = new SubjectRefreshPlan(reviewed.evidence(), reviewed.destinationContentSha256(),
				new HashMap<>(reviewed.identities()), new ArrayList<>(reviewed.recordChanges()),
				new ArrayList<>(reviewed.assetChanges()));
		var first = reviewed.approve("Approved refresh");
		var second = regenerated.approve(" Approved refresh ");
		assertEquals(first.approvalSha256(), second.approvalSha256());
		assertTrue(first.approvalSha256().matches("[0-9a-f]{64}"));
		first.requireUnchanged(regenerated);
	}

	@Test
	void approvalFingerprintUsesCanonicalRecordValues() throws Exception {
		SubjectRefreshPlan reviewed = createPlan();
		var original = reviewed.recordChanges().getFirst();
		var record = original.incoming();
		var firstValues = JsonNodeFactory.instance.objectNode();
		firstValues.put("marks", 3);
		firstValues.put("question_text", "Updated question");
		var secondValues = JsonNodeFactory.instance.objectNode();
		secondValues.put("question_text", "Updated question");
		secondValues.put("marks", 3);
		var firstRecord = new ExchangeRecord(record.recordVersion(), record.recordType(), record.recordId(),
				record.revisionId(), record.baselineRevisionId(), firstValues);
		var secondRecord = new ExchangeRecord(record.recordVersion(), record.recordType(), record.recordId(),
				record.revisionId(), record.baselineRevisionId(), secondValues);
		var classifier = new SubjectRefreshChangeClassifier();
		var first = new SubjectRefreshPlan(reviewed.evidence(), reviewed.destinationContentSha256(),
				reviewed.identities(), classifier.compareRecords(List.of(original.current()), List.of(firstRecord)),
				reviewed.assetChanges());
		var second = new SubjectRefreshPlan(reviewed.evidence(), reviewed.destinationContentSha256(),
				reviewed.identities(), classifier.compareRecords(List.of(original.current()), List.of(secondRecord)),
				reviewed.assetChanges());
		assertEquals(first, second);
		assertEquals(first.approve("Approved refresh").approvalSha256(),
				second.approve("Approved refresh").approvalSha256());
	}

	@Test
	void approvalRejectsChangedDestinationContent() throws Exception {
		SubjectRefreshPlan reviewed = createPlan();
		var approval = reviewed.approve("Approved refresh");
		var changed = new SubjectRefreshPlan(reviewed.evidence(), "b".repeat(64), reviewed.identities(),
				reviewed.recordChanges(), reviewed.assetChanges());
		var failure = assertThrows(PackageFormatException.class, () -> approval.requireUnchanged(changed));
		assertTrue(failure.getMessage().contains("Destination Subject content changed"));
	}

	@Test
	void approvalRejectsChangedIdentityMappings() throws Exception {
		SubjectRefreshPlan reviewed = createPlan();
		var approval = reviewed.approve("Approved refresh");
		var mappings = new HashMap<>(reviewed.identities());
		mappings.replaceAll((_, _) -> UUID.randomUUID());
		var changed = new SubjectRefreshPlan(reviewed.evidence(), reviewed.destinationContentSha256(), mappings,
				reviewed.recordChanges(), reviewed.assetChanges());
		var failure = assertThrows(PackageFormatException.class, () -> approval.requireUnchanged(changed));
		assertTrue(failure.getMessage().contains("identity mappings changed"));
	}

	@Test
	void approvalRejectsChangedIncomingPackage() throws Exception {
		SubjectRefreshPlan reviewed = createPlan();
		var evidence = reviewed.evidence();
		var incoming = evidence.incoming().fingerprint();
		var replacementFingerprint = new SubjectImportSnapshotFingerprint.Fingerprint(UUID.randomUUID(),
				incoming.originInstallationId(), incoming.subjectId(), incoming.subjectName(), incoming.contentSha256(),
				"f".repeat(64));
		var replacementEvidence = new SubjectSnapshotIntakePreview.RefreshEvidence(evidence.authority(),
				evidence.accepted(), new SubjectImportSnapshotFingerprint.SnapshotEvidence(replacementFingerprint,
						evidence.incoming().createdAt()),
				evidence.outcome());
		var changed = new SubjectRefreshPlan(replacementEvidence, reviewed.destinationContentSha256(),
				reviewed.identities(), reviewed.recordChanges(), reviewed.assetChanges());
		assertThrows(PackageFormatException.class, () -> reviewed.approve("Approved").requireUnchanged(changed));
	}

	@Test
	void approvalRejectsChangedReviewedOperations() throws Exception {
		SubjectRefreshPlan reviewed = createPlan();
		var changed = new SubjectRefreshPlan(reviewed.evidence(), reviewed.destinationContentSha256(),
				reviewed.identities(), List.of(), reviewed.assetChanges());
		var failure = assertThrows(PackageFormatException.class,
				() -> reviewed.approve("Approved").requireUnchanged(changed));
		assertTrue(failure.getMessage().contains("operations changed"));
	}

	@Test
	void approvalRequiresMeaningfulNote() {
		SubjectRefreshPlan reviewed = createPlan();
		assertThrows(IllegalArgumentException.class, () -> reviewed.approve(null));
		assertThrows(IllegalArgumentException.class, () -> reviewed.approve("   "));
		assertThrows(IllegalArgumentException.class, () -> reviewed.approve("a".repeat(1001)));
		assertEquals("Approved", reviewed.approve(" Approved ").approvalNote());
	}

	@Test
	void equivalentRegeneratedPlanRetainsApproval() throws Exception {
		SubjectRefreshPlan reviewed = createPlan();
		var regenerated = new SubjectRefreshPlan(reviewed.evidence(), reviewed.destinationContentSha256(),
				new HashMap<>(reviewed.identities()), new ArrayList<>(reviewed.recordChanges()),
				new ArrayList<>(reviewed.assetChanges()));
		assertEquals(reviewed, regenerated);
		assertDoesNotThrow(() -> reviewed.approve("Approved").requireUnchanged(regenerated));
	}

	@Test
	void newerUnchangedSnapshotCanBeApproved() {
		SubjectRefreshPlan reviewed = createPlan();
		var unchanged = new SubjectRefreshPlan(reviewed.evidence(), reviewed.destinationContentSha256(), Map.of(),
				List.of(), List.of());
		assertFalse(unchanged.hasContentChanges());
		assertNotNull(unchanged.approve("Accept newer snapshot evidence"));
	}

	@Test
	void planDefensivelyCopiesReviewedCollections() {
		SubjectRefreshPlan reviewed = createPlan();
		var identities = new HashMap<>(reviewed.identities());
		var records = new ArrayList<>(reviewed.recordChanges());
		var assets = new ArrayList<>(reviewed.assetChanges());
		var copied = new SubjectRefreshPlan(reviewed.evidence(), reviewed.destinationContentSha256(), identities,
				records, assets);
		identities.clear();
		records.clear();
		assets.clear();
		assertEquals(reviewed, copied);
		assertThrows(UnsupportedOperationException.class, copied.identities()::clear);
		assertThrows(UnsupportedOperationException.class, copied.recordChanges()::clear);
		assertThrows(UnsupportedOperationException.class, copied.assetChanges()::clear);
	}

	@Test
	void planRejectsDuplicateRecordAndAssetChanges() {
		SubjectRefreshPlan reviewed = createPlan();
		var record = reviewed.recordChanges().getFirst();
		var asset = reviewed.assetChanges().getFirst();
		assertThrows(IllegalArgumentException.class,
				() -> new SubjectRefreshPlan(reviewed.evidence(), reviewed.destinationContentSha256(),
						reviewed.identities(), List.of(record, record), reviewed.assetChanges()));
		assertThrows(IllegalArgumentException.class,
				() -> new SubjectRefreshPlan(reviewed.evidence(), reviewed.destinationContentSha256(),
						reviewed.identities(), reviewed.recordChanges(), List.of(asset, asset)));
	}

	@Test
	void planReportsReviewedChangeCounts() {
		SubjectRefreshPlan reviewed = createPlan();
		assertTrue(reviewed.hasContentChanges());
		assertEquals(1L, reviewed.countRecords(SubjectRefreshChangeClassifier.ChangeKind.UPDATE));
		assertEquals(1L, reviewed.countRecords("QUESTION", SubjectRefreshChangeClassifier.ChangeKind.UPDATE));
		assertEquals(0L, reviewed.countRecords("EXAM", SubjectRefreshChangeClassifier.ChangeKind.UPDATE));
		assertEquals(1L, reviewed.countAssets(SubjectRefreshChangeClassifier.ChangeKind.UPDATE));
		assertEquals(0L, reviewed.countAssets(SubjectRefreshChangeClassifier.ChangeKind.REMOVE));
	}

	private SubjectRefreshPlan createPlan() {
		UUID origin = UUID.randomUUID();
		UUID subject = UUID.randomUUID();
		UUID acceptedPackage = UUID.randomUUID();
		UUID incomingPackage = UUID.randomUUID();
		Instant acceptedTime = Instant.parse("2026-11-20T01:00:00Z");
		Instant incomingTime = acceptedTime.plusSeconds(60);
		var acceptedFingerprint = new SubjectImportSnapshotFingerprint.Fingerprint(acceptedPackage, origin, subject,
				"Biology", "1".repeat(64), "2".repeat(64));
		var incomingFingerprint = new SubjectImportSnapshotFingerprint.Fingerprint(incomingPackage, origin, subject,
				"Biology", "3".repeat(64), "4".repeat(64));
		var authority = new SqliteImportedSubjectAuthorityRepository.ImportedAuthority(1L, origin, subject,
				acceptedPackage, acceptedFingerprint.archiveSha256(), acceptedTime.plusSeconds(10));
		var evidence = new SubjectSnapshotIntakePreview.RefreshEvidence(authority,
				new SubjectImportSnapshotFingerprint.SnapshotEvidence(acceptedFingerprint, acceptedTime),
				new SubjectImportSnapshotFingerprint.SnapshotEvidence(incomingFingerprint, incomingTime),
				SubjectSnapshotFreshness.Outcome.NEWER_CHANGED);
		UUID originatingQuestion = UUID.randomUUID();
		UUID canonicalQuestion = UUID.randomUUID();
		var current = new ExchangeRecord(ExchangeRecord.CURRENT_RECORD_VERSION, "QUESTION", canonicalQuestion,
				UUID.randomUUID(), null, JsonNodeFactory.instance.objectNode().put("marks", 2));
		var incoming = new ExchangeRecord(ExchangeRecord.CURRENT_RECORD_VERSION, "QUESTION", canonicalQuestion,
				UUID.randomUUID(), null, JsonNodeFactory.instance.objectNode().put("marks", 3));
		var classifier = new SubjectRefreshChangeClassifier();
		var records = classifier.compareRecords(List.of(current), List.of(incoming));
		var asset = new SubjectRefreshChangeClassifier.AssetChange("subjects/Biology/exams/paper.pdf",
				SubjectRefreshChangeClassifier.ChangeKind.UPDATE,
				new SubjectRefreshChangeClassifier.AssetContent("5".repeat(64), 100L),
				new SubjectRefreshChangeClassifier.AssetContent("6".repeat(64), 120L));
		var identities = Map.of(new SubjectRefreshChangeClassifier.RecordKey("QUESTION", originatingQuestion),
				canonicalQuestion);
		return new SubjectRefreshPlan(evidence, DESTINATION_DIGEST, identities, records, List.of(asset));
	}
}
