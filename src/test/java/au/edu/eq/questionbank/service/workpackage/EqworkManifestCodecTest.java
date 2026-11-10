package au.edu.eq.questionbank.service.workpackage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import au.edu.eq.questionbank.service.workpackage.EqworkManifest.Entry;
import au.edu.eq.questionbank.service.workpackage.EqworkManifest.EntryRole;
import au.edu.eq.questionbank.service.workpackage.EqworkManifest.Scope;
import au.edu.eq.questionbank.service.workpackage.EqworkManifest.ScopeLevel;

class EqworkManifestCodecTest {

	private final EqworkManifestCodec codec = new EqworkManifestCodec();

	@Test
	void checksApplicationAndSchemaCompatibility() throws Exception {
		EqworkManifest manifest = example(EqworkPackageType.SUBJECT_SNAPSHOT);
		manifest.requireCompatibleReader("1.0.1", 24);
		manifest.requireCompatibleReader("1.1.0", 25);
		assertThrows(EqworkFormatException.class, () -> manifest.requireCompatibleReader("1.0.0", 24));
		assertThrows(EqworkFormatException.class, () -> manifest.requireCompatibleReader("1.0.1", 23));
	}

	@Test
	void excludesOptionalIdentifiersWhenAbsent() throws Exception {
		String json = asJson(example(EqworkPackageType.SUBJECT_SNAPSHOT));
		assertTrue(!json.contains("\"assignmentId\""));
		assertTrue(!json.contains("\"baselineSnapshotId\""));
		assertTrue(!json.contains("\"intendedMasterInstallationId\""));
	}

	@Test
	void rejectsContributionWithoutBaselineIdentity() throws Exception {
		String json = asJson(example(EqworkPackageType.CONTRIBUTION));
		String invalid = json.replaceAll("\"baselineSnapshotId\":\"[^\"]+\"", "\"baselineSnapshotId\":null");
		assertThrows(EqworkFormatException.class, () -> readJson(invalid));
	}

	@Test
	void rejectsDuplicateJsonProperties() throws Exception {
		String json = asJson(example(EqworkPackageType.SUBJECT_SNAPSHOT));
		String invalid = json.replace("\"formatVersion\":1", "\"formatVersion\":1,\"formatVersion\":1");
		assertThrows(EqworkFormatException.class, () -> readJson(invalid));
	}

	@Test
	void rejectsMissingRequiredMetadata() throws Exception {
		String json = asJson(example(EqworkPackageType.SUBJECT_SNAPSHOT));
		String invalid = json.replace("\"subjectName\":\"Biology\"", "\"subjectName\":null");
		assertThrows(EqworkFormatException.class, () -> readJson(invalid));
	}

	@Test
	void rejectsOversizedManifest() {
		String oversized = " ".repeat(65537);
		assertThrows(EqworkFormatException.class, () -> readJson(oversized));
	}

	@Test
	void rejectsUnknownManifestProperties() throws Exception {
		String json = asJson(example(EqworkPackageType.SUBJECT_SNAPSHOT));
		String invalid = json.replace("\"subjectName\":\"Biology\"",
				"\"unexpectedField\":1,\"subjectName\":\"Biology\"");
		assertThrows(EqworkFormatException.class, () -> readJson(invalid));
	}

	@Test
	void rejectsUnsafeIndexedArchivePaths() throws Exception {
		String json = asJson(example(EqworkPackageType.SUBJECT_SNAPSHOT));
		String invalid = json.replace("records/questions.jsonl", "../questions.jsonl");
		assertThrows(EqworkFormatException.class, () -> readJson(invalid));
	}

	@Test
	void rejectsUnsupportedMandatoryFormatVersion() throws Exception {
		String json = asJson(example(EqworkPackageType.SUBJECT_SNAPSHOT));
		String invalid = json.replace("\"formatVersion\":1", "\"formatVersion\":2");
		assertThrows(EqworkFormatException.class, () -> readJson(invalid));
	}

	@Test
	void roundTripsAllFourPackageTypes() throws Exception {
		for (EqworkPackageType type : EqworkPackageType.values()) {
			EqworkManifest original = example(type);
			EqworkManifest restored = codec.read(new ByteArrayInputStream(serialise(original)));
			assertEquals(original, restored);
			assertEquals(type, restored.packageType());
		}
	}

	@Test
	void scopeAndEntryOrderingAreCanonical() throws Exception {
		UUID firstId = UUID.fromString("00000000-0000-0000-0000-000000000001");
		UUID secondId = UUID.fromString("00000000-0000-0000-0000-000000000002");
		Scope scope = new Scope(ScopeLevel.EXAMS, List.of(secondId, firstId), List.of());
		assertEquals(List.of(firstId, secondId), scope.examIds());
		Entry second = new Entry("records/questions.jsonl", EntryRole.RECORDS, 0, "b".repeat(64));
		Entry first = new Entry("records/exams.jsonl", EntryRole.RECORDS, 0, "a".repeat(64));
		EqworkManifest base = example(EqworkPackageType.WORK_ASSIGNMENT);
		EqworkManifest reordered = new EqworkManifest(base.formatVersion(), base.packageId(), base.packageType(),
				base.createdAt(), base.originInstallationId(), base.intendedMasterInstallationId(), base.subjectId(),
				base.subjectName(), base.assignmentId(), base.baselineSnapshotId(), base.producerApplicationVersion(),
				base.minimumReaderApplicationVersion(), base.producerSchemaVersion(), base.minimumReaderSchemaVersion(),
				scope, List.of(second, first));
		assertEquals(List.of(first, second), reordered.entries());
		assertEquals(List.of(firstId, secondId), reordered.scope().examIds());
	}

	@Test
	void serialisationIsDeterministic() throws Exception {
		EqworkManifest original = example(EqworkPackageType.WORK_ASSIGNMENT);
		byte[] first = serialise(original);
		byte[] second = serialise(original);
		assertArrayEquals(first, second);
		String json = new String(first, StandardCharsets.UTF_8);
		assertTrue(json.startsWith("{"));
		assertTrue(json.endsWith("}\n"));
		assertTrue(json.contains("\"formatVersion\":1"));
		assertTrue(json.contains("\"subjectName\":\"Biology\""));
	}

	private String asJson(EqworkManifest manifest) throws Exception {
		return new String(serialise(manifest), StandardCharsets.UTF_8);
	}

	private EqworkManifest example(EqworkPackageType type) {
		UUID master = type == EqworkPackageType.SUBJECT_SNAPSHOT ? null
				: UUID.fromString("10000000-0000-0000-0000-000000000001");
		boolean assigned = type == EqworkPackageType.WORK_ASSIGNMENT || type == EqworkPackageType.CONTRIBUTION;
		UUID assignmentId = assigned ? UUID.fromString("20000000-0000-0000-0000-000000000001") : null;
		UUID baselineId = assigned ? UUID.fromString("30000000-0000-0000-0000-000000000001") : null;
		return new EqworkManifest(EqworkManifest.CURRENT_FORMAT_VERSION,
				UUID.fromString("40000000-0000-0000-0000-000000000001"), type, Instant.parse("2026-11-10T10:00:00Z"),
				UUID.fromString("50000000-0000-0000-0000-000000000001"), master,
				UUID.fromString("60000000-0000-0000-0000-000000000001"), "Biology", assignmentId, baselineId, "1.0.1",
				"1.0.1", 24, 24, new Scope(ScopeLevel.SUBJECT, List.of(), List.of()),
				List.of(new Entry("records/questions.jsonl", EntryRole.RECORDS, 0, "a".repeat(64))));
	}

	private EqworkManifest readJson(String json) throws Exception {
		return codec.read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
	}

	private byte[] serialise(EqworkManifest manifest) throws Exception {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		codec.write(manifest, output);
		return output.toByteArray();
	}
}
