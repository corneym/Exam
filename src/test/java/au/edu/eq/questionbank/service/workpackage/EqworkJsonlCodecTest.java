package au.edu.eq.questionbank.service.workpackage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import au.edu.eq.questionbank.service.workpackage.EqworkAssetIndex.Reference;
import au.edu.eq.questionbank.service.workpackage.EqworkManifest.Entry;
import au.edu.eq.questionbank.service.workpackage.EqworkManifest.EntryRole;
import au.edu.eq.questionbank.service.workpackage.EqworkManifest.Scope;
import au.edu.eq.questionbank.service.workpackage.EqworkManifest.ScopeLevel;

class EqworkJsonlCodecTest {

	private final EqworkJsonlCodec codec = new EqworkJsonlCodec();
	private final ObjectMapper jsonMapper = new ObjectMapper();

	@Test
	void assetIndexAcceptsMultipleLogicalReferencesToOneBinary() throws Exception {
		EqworkAssetIndex index = exampleAssetIndex();
		assertEquals(2, index.references().size());
		assertEquals(index.references().get(0).archivePath(), index.references().get(1).archivePath());
		EqworkManifest manifest = manifest(index, List.of(examplePhysicalAsset()));
		index.requireMatchingManifest(manifest);
		byte[] encoded = writeAssetIndex(index);
		EqworkAssetIndex decoded = codec.readAssetIndex(new ByteArrayInputStream(encoded));
		assertEquals(index, decoded);
		assertArrayEquals(encoded, writeAssetIndex(decoded));
	}

	@Test
	void assetIndexAllowsInventoriedFileWithoutDatabaseOwner() throws Exception {
		Reference example = exampleAssetIndex().references().getFirst();
		Reference inventoried = new Reference(example.archivePath(), example.sha256(), example.size(),
				example.mediaType(), "subjects/Biology/legacy/Retained workbook copy.pdf", null, null,
				"INVENTORIED_FILE");
		EqworkAssetIndex index = new EqworkAssetIndex(List.of(inventoried));
		assertEquals(null, index.references().getFirst().ownerId());
		assertEquals(null, index.references().getFirst().ownerType());
		index.requireMatchingManifest(manifest(index, List.of(examplePhysicalAsset())));
		assertEquals(index, codec.readAssetIndex(new ByteArrayInputStream(writeAssetIndex(index))));
	}

	@Test
	void assetIndexRejectsDuplicateLogicalReferences() {
		Reference reference = exampleAssetIndex().references().getFirst();
		assertThrows(IllegalArgumentException.class, () -> new EqworkAssetIndex(List.of(reference, reference)));
	}

	@Test
	void assetIndexRejectsMissingUnreferencedOrInconsistentAssets() throws Exception {
		EqworkAssetIndex index = exampleAssetIndex();
		Entry existing = examplePhysicalAsset();
		Entry missingFromReferences = new Entry("assets/sha256/bb/" + "b".repeat(64) + ".pdf", EntryRole.ASSET, 3,
				"b".repeat(64));
		assertThrows(EqworkFormatException.class,
				() -> index.requireMatchingManifest(manifest(index, List.of(existing, missingFromReferences))));
		assertThrows(EqworkFormatException.class, () -> index.requireMatchingManifest(manifest(index, List.of())));
		Entry wrongSize = new Entry(existing.path(), EntryRole.ASSET, existing.size() + 1, existing.sha256());
		assertThrows(EqworkFormatException.class,
				() -> index.requireMatchingManifest(manifest(index, List.of(wrongSize))));
		Entry wrongDigest = new Entry(existing.path(), EntryRole.ASSET, existing.size(), "c".repeat(64));
		assertThrows(EqworkFormatException.class,
				() -> index.requireMatchingManifest(manifest(index, List.of(wrongDigest))));
	}

	@Test
	void assetReferenceRejectsUnsafeManagedAndArchivePaths() {
		Reference valid = exampleAssetIndex().references().getFirst();
		for (String path : List.of("../outside.pdf", "C:/Users/Teacher/source.pdf",
				"subjects/Biology/../../outside.pdf", "subjects/Biology/exams\\paper.pdf")) {
			assertThrows(IllegalArgumentException.class, () -> new Reference(valid.archivePath(), valid.sha256(),
					valid.size(), valid.mediaType(), path, valid.ownerType(), valid.ownerId(), valid.usage()));
		}
		assertThrows(IllegalArgumentException.class,
				() -> new Reference("assets/sha256/aa/" + "b".repeat(64) + ".pdf", valid.sha256(), valid.size(),
						valid.mediaType(), valid.managedPath(), valid.ownerType(), valid.ownerId(), valid.usage()));
	}

	@Test
	void duplicateRecordIdentityIsRejectedOnReadAndWrite() throws Exception {
		EqworkRecord first = questionRecord(1, "{\"code\":\"1\",\"marks\":2}");
		EqworkRecord duplicate = questionRecord(1, "{\"code\":\"1\",\"marks\":3}");
		assertThrows(EqworkFormatException.class, () -> writeRecords(List.of(first, duplicate)));
		String json = new String(writeRecords(List.of(first)), StandardCharsets.UTF_8);
		assertThrows(EqworkFormatException.class, () -> readRecords(json + json));
	}

	@Test
	void invalidUtf8UnknownFieldsAndDuplicateJsonKeysAreRejected() throws Exception {
		EqworkRecord record = questionRecord(1, "{\"code\":\"1\"}");
		String valid = new String(writeRecords(List.of(record)), StandardCharsets.UTF_8);
		assertThrows(EqworkFormatException.class,
				() -> readRecords(valid.replace("\"recordVersion\":1", "\"unexpected\":true,\"recordVersion\":1")));
		assertThrows(EqworkFormatException.class,
				() -> readRecords(valid.replace("\"recordVersion\":1", "\"recordVersion\":1,\"recordVersion\":1")));
		assertThrows(EqworkFormatException.class,
				() -> codec.readRecords(new ByteArrayInputStream(new byte[] { (byte) 0xC3, (byte) 0x28, '\n' })));
	}

	@Test
	void jsonlRequiresBoundedCompleteLfTerminatedLines() throws Exception {
		String valid = new String(writeRecords(List.of(questionRecord(1, "{\"code\":\"1\"}"))), StandardCharsets.UTF_8);
		assertThrows(EqworkFormatException.class, () -> readRecords(valid.substring(0, valid.length() - 1)));
		assertThrows(EqworkFormatException.class, () -> readRecords(valid.replace("\n", "\r\n")));
		assertThrows(EqworkFormatException.class, () -> readRecords(valid + "\n"));
		String oversized = "{" + "x".repeat(1024 * 1024 + 1) + "}\n";
		assertThrows(EqworkFormatException.class, () -> readRecords(oversized));
	}

	@Test
	void recordEnvelopeRejectsUnknownVersionAndNonObjectValues() throws Exception {
		EqworkRecord valid = questionRecord(1, "{\"code\":\"1\"}");
		String json = new String(writeRecords(List.of(valid)), StandardCharsets.UTF_8);
		assertThrows(EqworkFormatException.class,
				() -> readRecords(json.replace("\"recordVersion\":1", "\"recordVersion\":2")));
		assertThrows(IllegalArgumentException.class,
				() -> new EqworkRecord(1, "QUESTION", uuid(1), null, null, jsonMapper.readTree("[1,2,3]")));
	}

	@Test
	void recordValuesAreDefensivelyCopied() throws Exception {
		ObjectNode original = (ObjectNode) jsonMapper.readTree("{\"marks\":2}");
		EqworkRecord record = new EqworkRecord(1, "QUESTION", uuid(1), null, null, original);
		original.put("marks", 10);
		assertEquals(2, record.values().get("marks").asInt());
		ObjectNode returned = (ObjectNode) record.values();
		returned.put("marks", 20);
		assertEquals(2, record.values().get("marks").asInt());
	}

	@Test
	void recordWritingSortsIdentitiesAndNestedProperties() throws Exception {
		EqworkRecord second = questionRecord(2, "{\"z\":{\"b\":2,\"a\":1},\"a\":[{\"y\":2,\"x\":1},3]}");
		EqworkRecord first = questionRecord(1, "{\"a\":[{\"x\":1,\"y\":2},3],\"z\":{\"a\":1,\"b\":2}}");
		byte[] ascending = writeRecords(List.of(first, second));
		byte[] descending = writeRecords(List.of(second, first));
		assertArrayEquals(ascending, descending);
		String json = new String(ascending, StandardCharsets.UTF_8);
		assertTrue(json.indexOf(uuid(1).toString()) < json.indexOf(uuid(2).toString()));
		assertTrue(json.contains("\"values\":{\"a\":[{\"x\":1,\"y\":2},3],\"z\":{\"a\":1,\"b\":2}}"));
		assertTrue(json.endsWith("\n"));
		assertFalse(json.contains("\r"));
		List<EqworkRecord> restored = codec.readRecords(new ByteArrayInputStream(ascending));
		assertEquals(List.of(first, second), restored);
		assertArrayEquals(ascending, writeRecords(restored));
	}

	@Test
	void rejectsAmbiguousManagedAssetDestinations() {
		Reference original = exampleAssetIndex().references().getFirst();
		String differentDigest = "b".repeat(64);
		Reference differentBytes = new Reference("assets/sha256/bb/" + differentDigest + ".pdf", differentDigest,
				original.size(), original.mediaType(), original.managedPath(), original.ownerType(), uuid(45),
				original.usage());
		assertThrows(IllegalArgumentException.class, () -> new EqworkAssetIndex(List.of(original, differentBytes)));
		String differentlyCased = original.managedPath().replace("subjects/Biology/", "subjects/biology/");
		Reference caseAlias = new Reference(original.archivePath(), original.sha256(), original.size(),
				original.mediaType(), differentlyCased, original.ownerType(), uuid(46), original.usage());
		assertThrows(IllegalArgumentException.class, () -> new EqworkAssetIndex(List.of(original, caseAlias)));
	}

	@Test
	void rejectsCrossSubjectAssetReferences() throws Exception {
		Reference original = exampleAssetIndex().references().getFirst();
		Reference wrongSubject = new Reference(original.archivePath(), original.sha256(), original.size(),
				original.mediaType(), "subjects/Physics/exams/QCAA/2025/questions.pdf", original.ownerType(),
				original.ownerId(), original.usage());
		EqworkAssetIndex index = new EqworkAssetIndex(List.of(wrongSubject));
		EqworkManifest manifest = manifest(index, List.of(examplePhysicalAsset()));
		assertThrows(EqworkFormatException.class, () -> index.requireMatchingManifest(manifest));
	}

	@Test
	void rejectsNonCanonicalAssetIndexOrdering() throws Exception {
		String encoded = new String(writeAssetIndex(exampleAssetIndex()), StandardCharsets.UTF_8);
		String[] lines = encoded.split("\n");
		assertEquals(2, lines.length);
		String reversed = lines[1] + "\n" + lines[0] + "\n";
		assertThrows(EqworkFormatException.class,
				() -> codec.readAssetIndex(new ByteArrayInputStream(reversed.getBytes(StandardCharsets.UTF_8))));
	}

	@Test
	void rejectsNonCanonicalRecordPropertyAndLineOrdering() throws Exception {
		String nonCanonical = """
				{"recordVersion":1,"recordType":"QUESTION","recordId":"%s","values":{"z":9,"a":1}}
				""".formatted(uuid(1));
		assertThrows(EqworkFormatException.class, () -> readRecords(nonCanonical));
		String canonical = new String(
				writeRecords(List.of(questionRecord(1, "{\"a\":1}"), questionRecord(2, "{\"a\":2}"))),
				StandardCharsets.UTF_8);
		String[] lines = canonical.split("\n");
		assertEquals(2, lines.length);
		String reversed = lines[1] + "\n" + lines[0] + "\n";
		assertThrows(EqworkFormatException.class, () -> readRecords(reversed));
	}

	@Test
	void rejectsWindowsReservedManagedPaths() {
		Reference valid = exampleAssetIndex().references().getFirst();
		for (String path : List.of("subjects/Biology/exams/CON.pdf", "subjects/Biology/exams/aux",
				"subjects/Biology/NUL/report.pdf", "subjects/Biology/exams/LPT1.txt", "subjects/Biology/exams/COM9.pdf",
				"subjects/Biology/exams/questions.pdf.")) {
			assertThrows(IllegalArgumentException.class, () -> new Reference(valid.archivePath(), valid.sha256(),
					valid.size(), valid.mediaType(), path, valid.ownerType(), valid.ownerId(), valid.usage()));
		}
	}

	private Reference assetReference(UUID owner, String managedPath) {
		String digest = "a".repeat(64);
		return new Reference("assets/sha256/aa/" + digest + ".pdf", digest, 3, "application/pdf", managedPath,
				"EXAM_BOOKLET", owner, "QUESTION_PDF");
	}

	private EqworkAssetIndex exampleAssetIndex() {
		Reference first = assetReference(uuid(1), "subjects/Biology/exams/QCAA/2025/Paper 1/questions.pdf");
		Reference second = assetReference(uuid(2), "subjects/Biology/exams/QCAA/2025/Paper 2/questions.pdf");
		return new EqworkAssetIndex(List.of(second, first));
	}

	private Entry examplePhysicalAsset() {
		String digest = "a".repeat(64);
		return new Entry("assets/sha256/aa/" + digest + ".pdf", EntryRole.ASSET, 3, digest);
	}

	private EqworkManifest manifest(EqworkAssetIndex index, List<Entry> physicalAssets) throws Exception {
		byte[] contents = writeAssetIndex(index);
		List<Entry> entries = new ArrayList<>(physicalAssets);
		entries.add(new Entry(EqworkAssetIndex.INDEX_PATH, EntryRole.INDEX, contents.length, sha256(contents)));
		return new EqworkManifest(1, uuid(10), EqworkPackageType.SUBJECT_SNAPSHOT,
				Instant.parse("2026-11-10T10:00:00Z"), uuid(11), null, uuid(12), "Biology", null, null, "1.0.1",
				"1.0.1", 24, 24, new Scope(ScopeLevel.SUBJECT, List.of(), List.of()), entries);
	}

	private EqworkRecord questionRecord(int identity, String values) throws Exception {
		JsonNode data = jsonMapper.readTree(values);
		return new EqworkRecord(1, "QUESTION", uuid(identity), null, null, data);
	}

	private List<EqworkRecord> readRecords(String json) throws Exception {
		return codec.readRecords(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
	}

	private String sha256(byte[] bytes) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
	}

	private UUID uuid(int value) {
		return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(value));
	}

	private byte[] writeAssetIndex(EqworkAssetIndex index) throws Exception {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		codec.writeAssetIndex(index, output);
		return output.toByteArray();
	}

	private byte[] writeRecords(List<EqworkRecord> records) throws Exception {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		codec.writeRecords(records, output);
		return output.toByteArray();
	}
}
