package au.edu.eq.questionbank.service.workpackage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import au.edu.eq.questionbank.service.workpackage.ExamWorkManifest.Entry;
import au.edu.eq.questionbank.service.workpackage.ExamWorkManifest.EntryRole;
import au.edu.eq.questionbank.service.workpackage.ExamWorkManifest.Scope;
import au.edu.eq.questionbank.service.workpackage.ExamWorkManifest.ScopeLevel;

class ManifestCodecTest {

	private final ManifestCodec codec = new ManifestCodec();

	@Test
	void checksApplicationAndSchemaCompatibility() throws Exception {
		ExamWorkManifest manifest = example(ExamWorkPackageType.SUBJECT_SNAPSHOT);
		manifest.requireCompatibleReader("1.0.1", 24);
		manifest.requireCompatibleReader("1.1.0", 25);
		assertThrows(PackageFormatException.class, () -> manifest.requireCompatibleReader("1.0.0", 24));
		assertThrows(PackageFormatException.class, () -> manifest.requireCompatibleReader("1.0.1", 23));
	}

	@Test
	void excludesOptionalIdentifiersWhenAbsent() throws Exception {
		String json = asJson(example(ExamWorkPackageType.SUBJECT_SNAPSHOT));
		assertTrue(!json.contains("\"assignmentId\""));
		assertTrue(!json.contains("\"baselineSnapshotId\""));
		assertTrue(!json.contains("\"intendedMasterInstallationId\""));
	}

	@Test
	void minimumReaderVersionsControlCompatibility() throws Exception {
		ExamWorkManifest base = example(ExamWorkPackageType.SUBJECT_SNAPSHOT);
		ExamWorkManifest compatible = new ExamWorkManifest(base.formatVersion(), base.packageId(), base.packageType(),
				base.createdAt(), base.originInstallationId(), base.intendedMasterInstallationId(), base.subjectId(),
				base.subjectName(), base.assignmentId(), base.baselineSnapshotId(), "1.1.0", "1.0.0", 25, 24,
				base.scope(), base.entries());

		// A version 1.0.1/schema 24 reader is permitted because those
		// versions exceed the declared minimum reader requirements.
		compatible.requireCompatibleReader("1.0.1", 24);
		assertThrows(PackageFormatException.class, () -> compatible.requireCompatibleReader("0.9.9", 24));
		assertThrows(PackageFormatException.class, () -> compatible.requireCompatibleReader("1.0.1", 23));
		assertThrows(IllegalArgumentException.class,
				() -> new ExamWorkManifest(base.formatVersion(), base.packageId(), base.packageType(), base.createdAt(),
						base.originInstallationId(), base.intendedMasterInstallationId(), base.subjectId(),
						base.subjectName(), base.assignmentId(), base.baselineSnapshotId(), "1.0.0", "1.1.0", 24, 24,
						base.scope(), base.entries()));
	}

	@Test
	void rejectsCaseCollidingManifestEntries() {
		ExamWorkManifest base = example(ExamWorkPackageType.SUBJECT_SNAPSHOT);
		List<Entry> entries = new ArrayList<>(base.entries());
		entries.add(new Entry("records/QUESTIONS.jsonl", EntryRole.RECORDS, 0, "b".repeat(64)));
		assertThrows(IllegalArgumentException.class,
				() -> new ExamWorkManifest(base.formatVersion(), base.packageId(), base.packageType(), base.createdAt(),
						base.originInstallationId(), base.intendedMasterInstallationId(), base.subjectId(),
						base.subjectName(), base.assignmentId(), base.baselineSnapshotId(),
						base.producerApplicationVersion(), base.minimumReaderApplicationVersion(),
						base.producerSchemaVersion(), base.minimumReaderSchemaVersion(), base.scope(), entries));
	}

	@Test
	void rejectsContributionWithoutBaselineIdentity() throws Exception {
		String json = asJson(example(ExamWorkPackageType.CONTRIBUTION));
		String invalid = json.replaceAll("\"baselineSnapshotId\":\"[^\"]+\"", "\"baselineSnapshotId\":null");
		assertThrows(PackageFormatException.class, () -> readJson(invalid));
	}

	@Test
	void rejectsDuplicateJsonProperties() throws Exception {
		String json = asJson(example(ExamWorkPackageType.SUBJECT_SNAPSHOT));
		String invalid = json.replace("\"formatVersion\":1", "\"formatVersion\":1,\"formatVersion\":1");
		assertThrows(PackageFormatException.class, () -> readJson(invalid));
	}

	@Test
	void rejectsMissingRequiredMetadata() throws Exception {
		String json = asJson(example(ExamWorkPackageType.SUBJECT_SNAPSHOT));
		String invalid = json.replace("\"subjectName\":\"Biology\"", "\"subjectName\":null");
		assertThrows(PackageFormatException.class, () -> readJson(invalid));
	}

	@Test
	void rejectsOversizedManifest() {
		String oversized = " ".repeat(65537);
		assertThrows(PackageFormatException.class, () -> readJson(oversized));
	}

	@Test
	void rejectsUnknownManifestProperties() throws Exception {
		String json = asJson(example(ExamWorkPackageType.SUBJECT_SNAPSHOT));
		String invalid = json.replace("\"subjectName\":\"Biology\"",
				"\"unexpectedField\":1,\"subjectName\":\"Biology\"");
		assertThrows(PackageFormatException.class, () -> readJson(invalid));
	}

	@Test
	void rejectsUnsafeIndexedArchivePaths() throws Exception {
		String json = asJson(example(ExamWorkPackageType.SUBJECT_SNAPSHOT));
		String invalid = json.replace("records/questions.jsonl", "../questions.jsonl");
		assertThrows(PackageFormatException.class, () -> readJson(invalid));
	}

	@Test
	void rejectsUnsafePortableSubjectNames() throws Exception {
		ExamWorkManifest base = example(ExamWorkPackageType.SUBJECT_SNAPSHOT);
		for (String invalidName : List.of("..", "CON", "Chemistry.", " Chemistry", "Physics/Engineering",
				"Physics\\Engineering", "C:Physics")) {
			assertThrows(IllegalArgumentException.class,
					() -> new ExamWorkManifest(base.formatVersion(), base.packageId(), base.packageType(),
							base.createdAt(), base.originInstallationId(), base.intendedMasterInstallationId(),
							base.subjectId(), invalidName, base.assignmentId(), base.baselineSnapshotId(),
							base.producerApplicationVersion(), base.minimumReaderApplicationVersion(),
							base.producerSchemaVersion(), base.minimumReaderSchemaVersion(), base.scope(),
							base.entries()));
		}
		String invalidJson = asJson(base).replace("\"subjectName\":\"Biology\"", "\"subjectName\":\"../Physics\"");
		assertThrows(PackageFormatException.class, () -> readJson(invalidJson));
	}

	@Test
	void rejectsUnsupportedMandatoryFormatVersion() throws Exception {
		String json = asJson(example(ExamWorkPackageType.SUBJECT_SNAPSHOT));
		String invalid = json.replace("\"formatVersion\":1", "\"formatVersion\":2");
		assertThrows(PackageFormatException.class, () -> readJson(invalid));
	}

	@Test
	void rejectsWindowsReservedArchiveEntryNames() {
		for (String path : List.of("records/CON.jsonl", "records/aux.jsonl", "records/PRN.jsonl",
				"records/questions.jsonl.", "baseline/NUL.jsonl", "receipts/COM1.jsonl")) {
			assertThrows(IllegalArgumentException.class, () -> new Entry(path, EntryRole.RECORDS, 0, "a".repeat(64)));
		}
	}

	@Test
	void roundTripsAllFourPackageTypes() throws Exception {
		for (ExamWorkPackageType type : ExamWorkPackageType.values()) {
			ExamWorkManifest original = example(type);
			ExamWorkManifest restored = codec.read(new ByteArrayInputStream(serialise(original)));
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
		ExamWorkManifest base = example(ExamWorkPackageType.WORK_ASSIGNMENT);
		ExamWorkManifest reordered = new ExamWorkManifest(base.formatVersion(), base.packageId(), base.packageType(),
				base.createdAt(), base.originInstallationId(), base.intendedMasterInstallationId(), base.subjectId(),
				base.subjectName(), base.assignmentId(), base.baselineSnapshotId(), base.producerApplicationVersion(),
				base.minimumReaderApplicationVersion(), base.producerSchemaVersion(), base.minimumReaderSchemaVersion(),
				scope, List.of(second, first));
		assertEquals(List.of(first, second), reordered.entries());
		assertEquals(List.of(firstId, secondId), reordered.scope().examIds());
	}

	@Test
	void serialisationIsDeterministic() throws Exception {
		ExamWorkManifest original = example(ExamWorkPackageType.WORK_ASSIGNMENT);
		byte[] first = serialise(original);
		byte[] second = serialise(original);
		assertArrayEquals(first, second);
		String json = new String(first, StandardCharsets.UTF_8);
		assertTrue(json.startsWith("{"));
		assertTrue(json.endsWith("}\n"));
		assertTrue(json.contains("\"formatVersion\":1"));
		assertTrue(json.contains("\"subjectName\":\"Biology\""));
	}

	private String asJson(ExamWorkManifest manifest) throws Exception {
		return new String(serialise(manifest), StandardCharsets.UTF_8);
	}

	private ExamWorkManifest example(ExamWorkPackageType type) {
		UUID master = type == ExamWorkPackageType.SUBJECT_SNAPSHOT ? null
				: UUID.fromString("10000000-0000-0000-0000-000000000001");
		boolean assigned = type == ExamWorkPackageType.WORK_ASSIGNMENT || type == ExamWorkPackageType.CONTRIBUTION;
		UUID assignmentId = assigned ? UUID.fromString("20000000-0000-0000-0000-000000000001") : null;
		UUID baselineId = assigned ? UUID.fromString("30000000-0000-0000-0000-000000000001") : null;
		return new ExamWorkManifest(ExamWorkManifest.CURRENT_FORMAT_VERSION,
				UUID.fromString("40000000-0000-0000-0000-000000000001"), type, Instant.parse("2026-11-10T10:00:00Z"),
				UUID.fromString("50000000-0000-0000-0000-000000000001"), master,
				UUID.fromString("60000000-0000-0000-0000-000000000001"), "Biology", assignmentId, baselineId, "1.0.1",
				"1.0.1", 24, 24, new Scope(ScopeLevel.SUBJECT, List.of(), List.of()),
				List.of(new Entry("records/questions.jsonl", EntryRole.RECORDS, 0, "a".repeat(64))));
	}

	private ExamWorkManifest readJson(String json) throws Exception {
		return codec.read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
	}

	private byte[] serialise(ExamWorkManifest manifest) throws Exception {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		codec.write(manifest, output);
		return output.toByteArray();
	}
}
