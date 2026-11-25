package au.edu.eq.questionbank.service.workpackage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import au.edu.eq.questionbank.service.workpackage.ExamWorkAssetIndex.Reference;
import au.edu.eq.questionbank.service.workpackage.SubjectRefreshChangeClassifier.ChangeKind;
import au.edu.eq.questionbank.service.workpackage.SubjectRefreshChangeClassifier.RecordChange;

class SubjectRefreshChangeClassifierTest {

	private static final String CHANGED_DIGEST = "b".repeat(64);
	private static final String ORIGINAL_DIGEST = "a".repeat(64);
	private final SubjectRefreshChangeClassifier classifier = new SubjectRefreshChangeClassifier();

	@Test
	void assetComparisonClassifiesAddRemoveUpdateAndUnchanged() {
		String prefix = "subjects/Biology/exams/";
		Reference unchanged = asset(prefix + "unchanged.pdf", ORIGINAL_DIGEST, 100, null);
		Reference removed = asset(prefix + "removed.pdf", ORIGINAL_DIGEST, 100, null);
		Reference existing = asset(prefix + "changed.pdf", ORIGINAL_DIGEST, 100, null);
		Reference replacement = asset(prefix + "changed.pdf", CHANGED_DIGEST, 120, null);
		Reference added = asset(prefix + "added.pdf", CHANGED_DIGEST, 120, null);
		var changes = classifier.compareAssets(new ExamWorkAssetIndex(List.of(removed, existing, unchanged)),
				new ExamWorkAssetIndex(List.of(replacement, added, unchanged)));
		assertEquals(4, changes.size());
		assertEquals(prefix + "added.pdf", changes.get(0).managedPath());
		assertEquals(ChangeKind.ADD, changes.get(0).kind());
		assertNull(changes.get(0).current());
		assertEquals(ChangeKind.UPDATE, changes.get(1).kind());
		assertEquals(ORIGINAL_DIGEST, changes.get(1).current().sha256());
		assertEquals(CHANGED_DIGEST, changes.get(1).incoming().sha256());
		assertEquals(120L, changes.get(1).incoming().size());
		assertEquals(ChangeKind.REMOVE, changes.get(2).kind());
		assertNull(changes.get(2).incoming());
		assertEquals(ChangeKind.UNCHANGED, changes.get(3).kind());
	}

	@Test
	void assetComparisonDoesNotDuplicateSharedFileChanges() {
		String path = "subjects/Biology/exams/shared.pdf";
		UUID firstOwner = UUID.randomUUID();
		UUID secondOwner = UUID.randomUUID();
		var first = asset(path, ORIGINAL_DIGEST, 100, firstOwner);
		var second = asset(path, ORIGINAL_DIGEST, 100, secondOwner);
		var changedFirst = asset(path, CHANGED_DIGEST, 120, firstOwner);
		var changedSecond = asset(path, CHANGED_DIGEST, 120, secondOwner);
		var changes = classifier.compareAssets(new ExamWorkAssetIndex(List.of(first, second)),
				new ExamWorkAssetIndex(List.of(changedFirst, changedSecond)));
		assertEquals(1, changes.size());
		assertEquals(path, changes.getFirst().managedPath());
		assertEquals(ChangeKind.UPDATE, changes.getFirst().kind());
	}

	@Test
	void comparisonResultsAreImmutable() {
		ExchangeRecord record = question(UUID.randomUUID(), UUID.randomUUID(), 2);
		var records = classifier.compareRecords(List.of(), List.of(record));
		assertThrows(UnsupportedOperationException.class, records::clear);
		var assets = classifier.compareAssets(new ExamWorkAssetIndex(List.of()),
				new ExamWorkAssetIndex(List.of(asset("subjects/Biology/exams/added.pdf", ORIGINAL_DIGEST, 100, null))));
		assertThrows(UnsupportedOperationException.class, assets::clear);
	}

	@Test
	void recordComparisonClassifiesAllChangeKinds() {
		UUID revision = UUID.randomUUID();
		UUID addedId = UUID.randomUUID();
		UUID removedId = UUID.randomUUID();
		UUID changedId = UUID.randomUUID();
		UUID unchangedId = UUID.randomUUID();
		UUID revisedId = UUID.randomUUID();
		ExchangeRecord removed = question(removedId, revision, 1);
		ExchangeRecord existing = question(changedId, revision, 2);
		ExchangeRecord replacement = question(changedId, UUID.randomUUID(), 3);
		ExchangeRecord unchanged = question(unchangedId, revision, 4);
		ExchangeRecord originalRevision = question(revisedId, revision, 5);
		ExchangeRecord changedRevision = question(revisedId, UUID.randomUUID(), 5);
		ExchangeRecord added = question(addedId, revision, 6);
		var changes = classifier.compareRecords(List.of(removed, existing, unchanged, originalRevision),
				List.of(added, replacement, unchanged, changedRevision));
		assertEquals(5, changes.size());
		Map<UUID, RecordChange> byId = changes.stream()
				.collect(Collectors.toMap(change -> change.key().recordId(), change -> change));
		assertEquals(ChangeKind.ADD, byId.get(addedId).kind());
		assertEquals(ChangeKind.REMOVE, byId.get(removedId).kind());
		assertEquals(ChangeKind.UPDATE, byId.get(changedId).kind());
		assertEquals(ChangeKind.UNCHANGED, byId.get(unchangedId).kind());
		assertEquals(ChangeKind.REVISION_ONLY, byId.get(revisedId).kind());
		assertEquals(2, byId.get(changedId).current().values().path("marks").asInt());
		assertEquals(3, byId.get(changedId).incoming().values().path("marks").asInt());
		assertNull(byId.get(addedId).current());
		assertNull(byId.get(removedId).incoming());
	}

	@Test
	void recordComparisonDetectsChangedValuesWithSameRevision() {
		UUID recordId = UUID.randomUUID();
		UUID revision = UUID.randomUUID();
		var changes = classifier.compareRecords(List.of(question(recordId, revision, 2)),
				List.of(question(recordId, revision, 3)));
		assertEquals(1, changes.size());
		assertEquals(ChangeKind.UPDATE, changes.getFirst().kind());
	}

	@Test
	void recordComparisonIsIndependentOfInputOrder() {
		UUID revision = UUID.randomUUID();
		ExchangeRecord first = question(UUID.fromString("10000000-0000-4000-8000-000000000001"), revision, 1);
		ExchangeRecord second = question(UUID.fromString("10000000-0000-4000-8000-000000000002"), revision, 2);
		var ordered = classifier.compareRecords(List.of(first, second), List.of(first, second));
		var reversed = classifier.compareRecords(List.of(second, first), List.of(second, first));
		assertEquals(ordered, reversed);
		assertEquals(first.recordId(), ordered.getFirst().key().recordId());
	}

	@Test
	void rejectsDuplicateExistingRecordKeys() {
		ExchangeRecord record = question(UUID.randomUUID(), UUID.randomUUID(), 2);
		assertThrows(IllegalArgumentException.class,
				() -> classifier.compareRecords(List.of(record, record), List.of()));
	}

	@Test
	void rejectsDuplicateIncomingRecordKeys() {
		UUID id = UUID.randomUUID();
		assertThrows(IllegalArgumentException.class, () -> classifier.compareRecords(List.of(),
				List.of(question(id, UUID.randomUUID(), 1), question(id, UUID.randomUUID(), 2))));
	}

	@Test
	void unchangedContentDoesNotDependOnBaselineRevision() {
		UUID recordId = UUID.randomUUID();
		UUID revision = UUID.randomUUID();
		ExchangeRecord first = question(recordId, revision, 2);
		ExchangeRecord second = new ExchangeRecord(first.recordVersion(), first.recordType(), first.recordId(),
				first.revisionId(), UUID.randomUUID(), first.values());
		var changes = classifier.compareRecords(List.of(first), List.of(second));
		assertEquals(ChangeKind.UNCHANGED, changes.getFirst().kind());
	}

	private Reference asset(String path, String digest, long size, UUID owner) {
		String archivePath = "assets/sha256/" + digest.substring(0, 2) + "/" + digest + ".pdf";
		return new Reference(archivePath, digest, size, "application/pdf", path,
				owner == null ? null : "SOURCE_DOCUMENT", owner, "QUESTION_PDF");
	}

	private ExchangeRecord question(UUID recordId, UUID revision, int marks) {
		ObjectNode values = JsonNodeFactory.instance.objectNode();
		values.put("marks", marks);
		return new ExchangeRecord(ExchangeRecord.CURRENT_RECORD_VERSION, "QUESTION", recordId, revision, null, values);
	}
}
