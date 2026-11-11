package au.edu.eq.questionbank.service.workpackage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

import au.edu.eq.questionbank.service.workpackage.EqworkAssetIndex.Reference;
import au.edu.eq.questionbank.service.workpackage.EqworkManifest.Entry;
import au.edu.eq.questionbank.service.workpackage.EqworkManifest.EntryRole;
import au.edu.eq.questionbank.service.workpackage.EqworkManifest.Scope;
import au.edu.eq.questionbank.service.workpackage.EqworkManifest.ScopeLevel;

class EqworkArchiveValidatorTest {

	@TempDir
	Path directory;
	private final EqworkArchiveValidator validator = new EqworkArchiveValidator();
	private final EqworkManifestCodec manifestCodec = new EqworkManifestCodec();
	private final EqworkJsonlCodec jsonlCodec = new EqworkJsonlCodec();

	@Test
	void acceptsValidPackagesOfAllFourTypes() throws Exception {
		for (EqworkPackageType type : EqworkPackageType.values()) {
			Map<String, byte[]> payloads = validPayloads();
			EqworkManifest manifest = manifest(type, payloads);
			Path archive = writeArchive(manifestBytes(manifest), payloads, null, null);
			assertEquals(manifest, validator.validate(archive, "1.0.1", 24));
		}
	}

	@Test
	void rejectsArchivesWithUnsafeOrUnexpectedPaths() throws Exception {
		Map<String, byte[]> payloads = validPayloads();
		EqworkManifest manifest = manifest(EqworkPackageType.SUBJECT_SNAPSHOT, payloads);
		for (String name : List.of("../outside.pdf", "/absolute.pdf", "C:/outside.pdf", "records/../outside.jsonl",
				"records\\outside.jsonl", "records/extra.jsonl")) {
			Path archive = writeArchive(manifestBytes(manifest), payloads, name, null);
			assertThrows(EqworkFormatException.class, () -> validator.validate(archive, "1.0.1", 24));
		}
	}

	@Test
	void rejectsCaseCollidingZipEntriesBeforeManifestProcessing() throws Exception {
		Map<String, byte[]> payloads = validPayloads();
		EqworkManifest manifest = manifest(EqworkPackageType.SUBJECT_SNAPSHOT, payloads);
		Path archive = writeArchive(manifestBytes(manifest), payloads, "records/QUESTIONS.jsonl", null);
		EqworkFormatException exception = assertThrows(EqworkFormatException.class,
				() -> validator.validate(archive, "1.0.1", 24));
		assertTrue(exception.getMessage().contains("Case-colliding ZIP entry name"));
	}

	@Test
	void rejectsCompressionBombBeforeDecompressingContents() throws Exception {
		Map<String, byte[]> payloads = new LinkedHashMap<>();
		payloads.put("records/expansion.jsonl", new byte[8 * 1024 * 1024]);
		EqworkManifest manifest = manifest(EqworkPackageType.SUBJECT_SNAPSHOT, payloads);
		Path archive = writeArchive(manifestBytes(manifest), payloads, null, null);
		EqworkFormatException exception = assertThrows(EqworkFormatException.class,
				() -> validator.validate(archive, "1.0.1", 24));
		assertEquals(true, exception.getMessage().contains("compression ratio"));
	}

	@Test
	void rejectsDuplicateArchiveEntryNames() throws Exception {
		Map<String, byte[]> payloads = validPayloads();
		EqworkManifest manifest = manifest(EqworkPackageType.SUBJECT_SNAPSHOT, payloads);
		Path archive = writeArchive(manifestBytes(manifest), payloads, "records/questions.jsonl", null);
		assertThrows(EqworkFormatException.class, () -> validator.validate(archive, "1.0.1", 24));
	}

	@Test
	void rejectsExcessiveArchiveEntryCount() throws Exception {
		Path archive = directory.resolve("too-many.eqwork");
		try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(Files.newOutputStream(archive))) {
			for (int index = 0; index < 4097; index++) {
				addEntry(zip, "records/entry%05d.jsonl".formatted(index), new byte[0], false);
			}
		}
		EqworkFormatException exception = assertThrows(EqworkFormatException.class,
				() -> validator.validate(archive, "1.0.1", 24));
		assertEquals(true, exception.getMessage().contains("entry count"));
	}

	@Test
	void rejectsInvalidRecordJsonlEvenWithMatchingDigest() throws Exception {
		Map<String, byte[]> payloads = validPayloads();
		payloads.put("records/questions.jsonl", "{invalid-json}\n".getBytes(StandardCharsets.UTF_8));
		EqworkManifest manifest = manifest(EqworkPackageType.SUBJECT_SNAPSHOT, payloads);
		Path archive = writeArchive(manifestBytes(manifest), payloads, null, null);
		assertThrows(EqworkFormatException.class, () -> validator.validate(archive, "1.0.1", 24));
	}

	@Test
	void rejectsMissingManifest() throws Exception {
		Path archive = writeArchive(null, validPayloads(), null, null);
		assertThrows(EqworkFormatException.class, () -> validator.validate(archive, "1.0.1", 24));
	}

	@Test
	void rejectsMissingOrMalformedAssetIndex() throws Exception {
		Map<String, byte[]> payloads = validPayloads();
		Map<String, byte[]> missing = new LinkedHashMap<>(payloads);
		missing.remove(EqworkAssetIndex.INDEX_PATH);
		EqworkManifest missingManifest = manifest(EqworkPackageType.SUBJECT_SNAPSHOT, missing);
		Path missingArchive = writeArchive(manifestBytes(missingManifest), missing, null, null);
		assertThrows(EqworkFormatException.class, () -> validator.validate(missingArchive, "1.0.1", 24));
		Map<String, byte[]> malformed = new LinkedHashMap<>(payloads);
		malformed.put(EqworkAssetIndex.INDEX_PATH, "invalid-index\n".getBytes(StandardCharsets.UTF_8));
		EqworkManifest malformedManifest = manifest(EqworkPackageType.SUBJECT_SNAPSHOT, malformed);
		Path malformedArchive = writeArchive(manifestBytes(malformedManifest), malformed, null, null);
		assertThrows(EqworkFormatException.class, () -> validator.validate(malformedArchive, "1.0.1", 24));
	}

	@Test
	void rejectsMissingOrUnreferencedBinaryAssets() throws Exception {
		Map<String, byte[]> payloads = validPayloads();
		EqworkManifest manifest = manifest(EqworkPackageType.SUBJECT_SNAPSHOT, payloads);
		Map<String, byte[]> missing = new LinkedHashMap<>(payloads);
		missing.remove(assetPath());
		Path missingArchive = writeArchive(manifestBytes(manifest), missing, null, null);
		assertThrows(EqworkFormatException.class, () -> validator.validate(missingArchive, "1.0.1", 24));
		Map<String, byte[]> extra = new LinkedHashMap<>(payloads);
		byte[] extraBytes = "an unused binary".getBytes(StandardCharsets.UTF_8);
		String extraDigest = sha256(extraBytes);
		String extraPath = "assets/sha256/" + extraDigest.substring(0, 2) + "/" + extraDigest + ".pdf";
		extra.put(extraPath, extraBytes);
		EqworkManifest extraManifest = manifest(EqworkPackageType.SUBJECT_SNAPSHOT, extra);
		Path extraArchive = writeArchive(manifestBytes(extraManifest), extra, null, null);
		assertThrows(EqworkFormatException.class, () -> validator.validate(extraArchive, "1.0.1", 24));
	}

	@Test
	void rejectsSymbolicLinkArchiveEntries() throws Exception {
		Map<String, byte[]> payloads = validPayloads();
		EqworkManifest manifest = manifest(EqworkPackageType.SUBJECT_SNAPSHOT, payloads);

		// Mark an otherwise valid, indexed file as a Unix symbolic link.
		Path archive = writeArchive(manifestBytes(manifest), payloads, null, "records/questions.jsonl");
		assertThrows(EqworkFormatException.class, () -> validator.validate(archive, "1.0.1", 24));
	}

	@Test
	void rejectsTamperedContentAndDeclaredSizes() throws Exception {
		Map<String, byte[]> payloads = validPayloads();
		EqworkManifest manifest = manifest(EqworkPackageType.SUBJECT_SNAPSHOT, payloads);
		Map<String, byte[]> tampered = new LinkedHashMap<>(payloads);
		tampered.put("records/questions.jsonl", "{}\n".getBytes(StandardCharsets.UTF_8));
		Path tamperedArchive = writeArchive(manifestBytes(manifest), tampered, null, null);
		assertThrows(EqworkFormatException.class, () -> validator.validate(tamperedArchive, "1.0.1", 24));
		List<Entry> incorrect = new ArrayList<>();
		for (Entry entry : manifest.entries()) {
			if (entry.path().equals("records/questions.jsonl")) {
				incorrect.add(new Entry(entry.path(), entry.role(), entry.size() + 1, entry.sha256()));
			} else {
				incorrect.add(entry);
			}
		}
		EqworkManifest wrongSize = withEntries(manifest, incorrect);
		Path wrongSizeArchive = writeArchive(manifestBytes(wrongSize), payloads, null, null);
		assertThrows(EqworkFormatException.class, () -> validator.validate(wrongSizeArchive, "1.0.1", 24));
	}

	@Test
	void rejectsUnsupportedManifestVersionAndIncompatibleReader() throws Exception {
		Map<String, byte[]> payloads = validPayloads();
		EqworkManifest manifest = manifest(EqworkPackageType.SUBJECT_SNAPSHOT, payloads);
		String json = new String(manifestBytes(manifest), StandardCharsets.UTF_8);
		String unsupported = json.replace("\"formatVersion\":1", "\"formatVersion\":2");
		Path unsupportedArchive = writeArchive(unsupported.getBytes(StandardCharsets.UTF_8), payloads, null, null);
		assertThrows(EqworkFormatException.class, () -> validator.validate(unsupportedArchive, "1.0.1", 24));
		Path validArchive = writeArchive(manifestBytes(manifest), payloads, null, null);
		assertThrows(EqworkFormatException.class, () -> validator.validate(validArchive, "1.0.0", 24));
		assertThrows(EqworkFormatException.class, () -> validator.validate(validArchive, "1.0.1", 23));
	}

	@Test
	void rejectsWindowsReservedZipEntryBeforeManifest() throws Exception {
		Map<String, byte[]> payloads = new LinkedHashMap<>();
		payloads.put("records/CON.jsonl", new byte[0]);
		Path archive = writeArchive(null, payloads, null, null);
		EqworkFormatException exception = assertThrows(EqworkFormatException.class,
				() -> validator.validate(archive, "1.0.1", 24));
		assertEquals(true, exception.getMessage().contains("Unsafe ZIP entry name"));
	}

	@Test
	void validationLeavesManagedFilesUnchanged() throws Exception {
		Path managed = Files.createDirectories(directory.resolve("managed/subjects/Biology"));
		Path existing = managed.resolve("questions.pdf");
		byte[] original = "authoritative file".getBytes(StandardCharsets.UTF_8);
		Files.write(existing, original);
		Path archive = writeArchive(null, validPayloads(), null, null);
		assertThrows(EqworkFormatException.class, () -> validator.validate(archive, "1.0.1", 24));
		assertEquals("authoritative file", Files.readString(existing, StandardCharsets.UTF_8));
	}

	private void addEntry(ZipArchiveOutputStream zip, String name, byte[] contents, boolean symbolicLink)
			throws IOException {
		ZipArchiveEntry entry = new ZipArchiveEntry(name);
		if (symbolicLink) {
			entry.setUnixMode(0120777);
		}
		zip.putArchiveEntry(entry);
		zip.write(contents);
		zip.closeArchiveEntry();
	}

	private String assetPath() throws Exception {
		String digest = sha256("source PDF".getBytes(StandardCharsets.UTF_8));
		return "assets/sha256/" + digest.substring(0, 2) + "/" + digest + ".pdf";
	}

	private EqworkManifest manifest(EqworkPackageType type, Map<String, byte[]> payloads) throws Exception {
		List<Entry> entries = new ArrayList<>();
		for (Map.Entry<String, byte[]> value : payloads.entrySet()) {
			String path = value.getKey();
			EntryRole role;
			if (path.equals(EqworkAssetIndex.INDEX_PATH)) {
				role = EntryRole.INDEX;
			} else if (path.startsWith("assets/")) {
				role = EntryRole.ASSET;
			} else if (path.startsWith("baseline/")) {
				role = EntryRole.BASELINE;
			} else if (path.startsWith("receipts/")) {
				role = EntryRole.RECEIPT;
			} else {
				role = EntryRole.RECORDS;
			}
			entries.add(new Entry(path, role, value.getValue().length, sha256(value.getValue())));
		}
		boolean assigned = type == EqworkPackageType.WORK_ASSIGNMENT || type == EqworkPackageType.CONTRIBUTION;
		UUID master = type == EqworkPackageType.SUBJECT_SNAPSHOT ? null : uuid(90);
		return new EqworkManifest(1, uuid(10), type, Instant.parse("2026-11-10T10:00:00Z"), uuid(11), master, uuid(12),
				"Biology", assigned ? uuid(13) : null, assigned ? uuid(14) : null, "1.0.1", "1.0.1", 24, 24,
				new Scope(ScopeLevel.SUBJECT, List.of(), List.of()), entries);
	}

	private byte[] manifestBytes(EqworkManifest manifest) throws Exception {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		manifestCodec.write(manifest, output);
		return output.toByteArray();
	}

	private String sha256(byte[] bytes) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
	}

	private UUID uuid(int value) {
		return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(value));
	}

	private Map<String, byte[]> validPayloads() throws Exception {
		Map<String, byte[]> payloads = new LinkedHashMap<>();
		byte[] pdf = "source PDF".getBytes(StandardCharsets.UTF_8);
		String digest = sha256(pdf);
		String path = assetPath();
		Reference reference = new Reference(path, digest, pdf.length, "application/pdf",
				"subjects/Biology/exams/QCAA/2025/Paper1/questions.pdf", "EXAM_BOOKLET", uuid(20), "QUESTION_PDF");
		EqworkAssetIndex index = new EqworkAssetIndex(List.of(reference));
		ByteArrayOutputStream indexOutput = new ByteArrayOutputStream();
		jsonlCodec.writeAssetIndex(index, indexOutput);
		payloads.put(EqworkAssetIndex.INDEX_PATH, indexOutput.toByteArray());
		EqworkRecord record = new EqworkRecord(1, "QUESTION", uuid(21), null, null,
				new ObjectMapper().createObjectNode().put("code", "1.1").put("marks", 3));
		ByteArrayOutputStream recordOutput = new ByteArrayOutputStream();
		jsonlCodec.writeRecords(List.of(record), recordOutput);
		payloads.put("records/questions.jsonl", recordOutput.toByteArray());
		payloads.put(path, pdf);
		return payloads;
	}

	private EqworkManifest withEntries(EqworkManifest original, List<Entry> entries) {
		return new EqworkManifest(original.formatVersion(), original.packageId(), original.packageType(),
				original.createdAt(), original.originInstallationId(), original.intendedMasterInstallationId(),
				original.subjectId(), original.subjectName(), original.assignmentId(), original.baselineSnapshotId(),
				original.producerApplicationVersion(), original.minimumReaderApplicationVersion(),
				original.producerSchemaVersion(), original.minimumReaderSchemaVersion(), original.scope(), entries);
	}

	private Path writeArchive(byte[] manifestData, Map<String, byte[]> payloads, String extraPath, String symlinkPath)
			throws Exception {
		Path archive = Files.createTempFile(directory, "fixture-", ".eqwork");
		try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(Files.newOutputStream(archive))) {
			if (manifestData != null) {
				addEntry(zip, "manifest.json", manifestData, false);
			}
			for (Map.Entry<String, byte[]> file : payloads.entrySet()) {
				addEntry(zip, file.getKey(), file.getValue(), file.getKey().equals(symlinkPath));
			}
			if (extraPath != null) {
				addEntry(zip, extraPath, new byte[0], false);
			}
		}
		return archive;
	}
}
