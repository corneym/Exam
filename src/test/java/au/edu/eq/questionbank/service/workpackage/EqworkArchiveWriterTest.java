package au.edu.eq.questionbank.service.workpackage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

import au.edu.eq.questionbank.service.workpackage.EqworkArchiveWriter.EntrySource;
import au.edu.eq.questionbank.service.workpackage.EqworkAssetIndex.Reference;
import au.edu.eq.questionbank.service.workpackage.EqworkManifest.Entry;
import au.edu.eq.questionbank.service.workpackage.EqworkManifest.EntryRole;
import au.edu.eq.questionbank.service.workpackage.EqworkManifest.Scope;
import au.edu.eq.questionbank.service.workpackage.EqworkManifest.ScopeLevel;

class EqworkArchiveWriterTest {

	@TempDir
	Path directory;
	private final EqworkArchiveWriter writer = new EqworkArchiveWriter();
	private final EqworkArchiveValidator validator = new EqworkArchiveValidator();
	private final EqworkJsonlCodec jsonlCodec = new EqworkJsonlCodec();

	@Test
	void archiveBytesAreRepeatableForIdenticalInputs() throws Exception {
		Map<String, byte[]> contents = exampleContents();
		EqworkManifest manifest = manifest(EqworkPackageType.SUBJECT_SNAPSHOT, contents);
		byte[] first = write(manifest, sources(contents));
		byte[] second = write(manifest, sources(contents));
		assertArrayEquals(first, second);
	}

	@Test
	void rejectsChangedBinarySourceBytes() throws Exception {
		Map<String, byte[]> original = exampleContents();
		EqworkManifest manifest = manifest(EqworkPackageType.SUBJECT_SNAPSHOT, original);
		Map<String, byte[]> changed = new LinkedHashMap<>(original);
		changed.put(assetPath(), "changed PDF".getBytes(StandardCharsets.UTF_8));

		// The manifest still describes the original source bytes.
		assertThrows(EqworkFormatException.class, () -> write(manifest, sources(changed)));
	}

	@Test
	void rejectsDuplicateCurrentRecordsAcrossFiles() throws Exception {
		Map<String, byte[]> contents = exampleContents();
		contents.put("records/more-questions.jsonl", contents.get("records/questions.jsonl"));
		EqworkManifest manifest = manifest(EqworkPackageType.SUBJECT_SNAPSHOT, contents);
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		assertThrows(EqworkFormatException.class, () -> writer.write(manifest, sources(contents), output));
		assertEquals(0, output.size());
	}

	@Test
	void rejectsExcessiveCompressionBeforeReportingSuccess() throws Exception {
		Map<String, byte[]> contents = exampleContents();

		// Replace the ordinary PDF fixture with highly compressible bytes.
		contents.remove(assetPath());
		byte[] highlyCompressible = new byte[8 * 1024 * 1024];
		String digest = sha256(highlyCompressible);
		String archivePath = "assets/sha256/" + digest.substring(0, 2) + "/" + digest + ".pdf";
		Reference reference = new Reference(archivePath, digest, highlyCompressible.length, "application/pdf",
				"subjects/Biology/exams/QCAA/2025/Paper1/source.pdf", "EXAM_BOOKLET", uuid(20), "QUESTION_PDF");
		EqworkAssetIndex index = new EqworkAssetIndex(List.of(reference));
		ByteArrayOutputStream indexBytes = new ByteArrayOutputStream();
		jsonlCodec.writeAssetIndex(index, indexBytes);
		contents.put(EqworkAssetIndex.INDEX_PATH, indexBytes.toByteArray());
		contents.put(archivePath, highlyCompressible);
		EqworkManifest manifest = manifest(EqworkPackageType.SUBJECT_SNAPSHOT, contents);

		// The writer must not successfully return a ZIP that its own
		// validator would reject as excessive decompression.
		EqworkFormatException exception = assertThrows(EqworkFormatException.class,
				() -> write(manifest, sources(contents)));
		assertTrue(exception.getMessage().contains("compression ratio"));
	}

	@Test
	void rejectsMalformedStructuredDataBeforeWriting() throws Exception {
		Map<String, byte[]> contents = exampleContents();
		contents.put("records/questions.jsonl", "{bad-json}\n".getBytes(StandardCharsets.UTF_8));
		EqworkManifest manifest = manifest(EqworkPackageType.SUBJECT_SNAPSHOT, contents);
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		assertThrows(EqworkFormatException.class, () -> writer.write(manifest, sources(contents), output));
		assertEquals(0, output.size());
	}

	@Test
	void rejectsMissingAssetIndexBeforeWriting() throws Exception {
		Map<String, byte[]> contents = exampleContents();
		contents.remove(EqworkAssetIndex.INDEX_PATH);
		EqworkManifest manifest = manifest(EqworkPackageType.SUBJECT_SNAPSHOT, contents);
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		assertThrows(EqworkFormatException.class, () -> writer.write(manifest, sources(contents), output));
		assertEquals(0, output.size());
	}

	@Test
	void rejectsUnexpectedOrMissingPayloadsBeforeWriting() throws Exception {
		Map<String, byte[]> contents = exampleContents();
		EqworkManifest manifest = manifest(EqworkPackageType.SUBJECT_SNAPSHOT, contents);
		Map<String, EntrySource> missing = sources(contents);
		missing.remove("records/questions.jsonl");
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		assertThrows(EqworkFormatException.class, () -> writer.write(manifest, missing, output));
		assertEquals(0, output.size());
		Map<String, EntrySource> extra = sources(contents);
		extra.put("records/unlisted.jsonl", () -> new ByteArrayInputStream(new byte[0]));
		assertThrows(EqworkFormatException.class, () -> writer.write(manifest, extra, new ByteArrayOutputStream()));
	}

	@Test
	void reopensStructuredSourcesAndStreamsBinarySources() throws Exception {
		Map<String, byte[]> contents = exampleContents();
		EqworkManifest manifest = manifest(EqworkPackageType.SUBJECT_SNAPSHOT, contents);
		Map<String, EntrySource> sources = new LinkedHashMap<>();
		Map<String, AtomicInteger> openings = new LinkedHashMap<>();
		for (Map.Entry<String, byte[]> entry : contents.entrySet()) {
			AtomicInteger counter = new AtomicInteger();
			openings.put(entry.getKey(), counter);
			byte[] bytes = entry.getValue();
			sources.put(entry.getKey(), () -> {
				counter.incrementAndGet();
				return new ByteArrayInputStream(bytes);
			});
		}
		write(manifest, sources);
		assertEquals(2, openings.get(EqworkAssetIndex.INDEX_PATH).get());
		assertEquals(2, openings.get("records/questions.jsonl").get());
		assertEquals(1, openings.get(assetPath()).get());
	}

	@Test
	void writesValidPackagesOfAllFourTypes() throws Exception {
		for (EqworkPackageType type : EqworkPackageType.values()) {
			Map<String, byte[]> contents = exampleContents();
			EqworkManifest manifest = manifest(type, contents);
			byte[] encoded = write(manifest, sources(contents));
			Path archive = Files.createTempFile(directory, "package-", ".eqwork");
			Files.write(archive, encoded);
			assertEquals(manifest, validator.validate(archive, "1.0.1", 24));
			assertTrue(Files.size(archive) > 0);
		}
	}

	private String assetPath() throws Exception {
		String digest = sha256("source PDF".getBytes(StandardCharsets.UTF_8));
		return "assets/sha256/" + digest.substring(0, 2) + "/" + digest + ".pdf";
	}

	private Map<String, byte[]> exampleContents() throws Exception {
		Map<String, byte[]> contents = new LinkedHashMap<>();
		byte[] pdf = "source PDF".getBytes(StandardCharsets.UTF_8);
		String digest = sha256(pdf);
		String path = assetPath();
		Reference first = new Reference(path, digest, pdf.length, "application/pdf",
				"subjects/Biology/exams/QCAA/2025/Paper1/questions.pdf", "EXAM_BOOKLET", uuid(20), "QUESTION_PDF");
		Reference second = new Reference(path, digest, pdf.length, "application/pdf",
				"subjects/Biology/exams/QCAA/2025/Paper2/questions.pdf", "EXAM_BOOKLET", uuid(22), "QUESTION_PDF");
		EqworkAssetIndex index = new EqworkAssetIndex(List.of(second, first));
		ByteArrayOutputStream indexOutput = new ByteArrayOutputStream();
		jsonlCodec.writeAssetIndex(index, indexOutput);
		contents.put(EqworkAssetIndex.INDEX_PATH, indexOutput.toByteArray());
		EqworkRecord record = new EqworkRecord(1, "QUESTION", uuid(21), null, null,
				new ObjectMapper().createObjectNode().put("code", "1.1").put("marks", 3));
		ByteArrayOutputStream recordsOutput = new ByteArrayOutputStream();
		jsonlCodec.writeRecords(List.of(record), recordsOutput);
		contents.put("records/questions.jsonl", recordsOutput.toByteArray());
		contents.put(path, pdf);
		return contents;
	}

	private EqworkManifest manifest(EqworkPackageType type, Map<String, byte[]> contents) throws Exception {
		List<Entry> entries = new ArrayList<>();
		for (Map.Entry<String, byte[]> value : contents.entrySet()) {
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

	private String sha256(byte[] bytes) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
	}

	private Map<String, EntrySource> sources(Map<String, byte[]> contents) {
		Map<String, EntrySource> result = new LinkedHashMap<>();
		for (Map.Entry<String, byte[]> entry : contents.entrySet()) {
			byte[] bytes = entry.getValue();
			result.put(entry.getKey(), () -> new ByteArrayInputStream(bytes));
		}
		return result;
	}

	private UUID uuid(int value) {
		return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(value));
	}

	private byte[] write(EqworkManifest manifest, Map<String, EntrySource> sources) throws Exception {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		writer.write(manifest, sources, output);
		return output.toByteArray();
	}
}
