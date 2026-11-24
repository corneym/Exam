package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

import au.edu.eq.questionbank.service.workpackage.ExamWorkArchiveWriter;
import au.edu.eq.questionbank.service.workpackage.ExamWorkAssetIndex;
import au.edu.eq.questionbank.service.workpackage.ExamWorkJsonlCodec;
import au.edu.eq.questionbank.service.workpackage.ExamWorkManifest;
import au.edu.eq.questionbank.service.workpackage.ExamWorkPackageType;
import au.edu.eq.questionbank.service.workpackage.ExchangeRecord;
import au.edu.eq.questionbank.service.workpackage.PackageFormatException;

class WorkPackageImportRouteTest {

	@TempDir
	Path directory;

	@Test
	void matchingPackageTypeDoesNotBypassArchiveValidation() throws Exception {
		Path archive = directory.resolve("invalid-assignment.examwork");
		Files.writeString(archive, "Not a valid ZIP archive");
		assertThrows(IOException.class,
				() -> WorkPackageImportRoute.validate(archive, "1.0.1", 24, ExamWorkPackageType.WORK_ASSIGNMENT));
	}

	@Test
	void receiptCannotBeRequestedAsAnImportOperation() {
		assertThrows(IllegalArgumentException.class,
				() -> WorkPackageImportRoute.title(ExamWorkPackageType.IMPORT_RECEIPT));
	}

	@Test
	void validatesManifestTypeForEverySupportedImportRoute() throws Exception {
		List<ExamWorkPackageType> supportedTypes = List.of(ExamWorkPackageType.SUBJECT_SNAPSHOT,
				ExamWorkPackageType.WORK_ASSIGNMENT, ExamWorkPackageType.CONTRIBUTION);
		for (ExamWorkPackageType actualType : ExamWorkPackageType.values()) {
			Path archive = directory.resolve(actualType.name() + ".examwork");
			ExamWorkManifest original = writeArchive(archive, actualType);
			byte[] originalBytes = Files.readAllBytes(archive);
			for (ExamWorkPackageType requestedType : supportedTypes) {
				if (actualType == requestedType) {
					assertEquals(original, WorkPackageImportRoute.validate(archive, "1.0.1", 24, requestedType));
				} else {
					PackageFormatException failure = assertThrows(PackageFormatException.class,
							() -> WorkPackageImportRoute.validate(archive, "1.0.1", 24, requestedType));
					assertTrue(failure.getMessage().contains(WorkPackageImportRoute.title(requestedType)));
					if (actualType == ExamWorkPackageType.IMPORT_RECEIPT) {
						assertTrue(failure.getMessage().contains("Receipt acknowledgement")
								|| failure.getMessage().contains("receipt acknowledgement"));
					} else {
						assertTrue(failure.getMessage().contains("Use " + WorkPackageImportRoute.title(actualType)));
					}
				}

				// Import routing is entirely read-only.
				assertArrayEquals(originalBytes, Files.readAllBytes(archive));
			}
		}
	}

	private String sha256(byte[] bytes) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
	}

	private ExamWorkManifest writeArchive(Path archive, ExamWorkPackageType type) throws Exception {
		ByteArrayOutputStream indexOutput = new ByteArrayOutputStream();
		ExamWorkJsonlCodec codec = new ExamWorkJsonlCodec();
		codec.writeAssetIndex(new ExamWorkAssetIndex(List.of()), indexOutput);
		byte[] indexBytes = indexOutput.toByteArray();
		UUID subjectId = UUID.randomUUID();
		ExchangeRecord record = new ExchangeRecord(ExchangeRecord.CURRENT_RECORD_VERSION, "SUBJECT", subjectId,
				UUID.randomUUID(), null, new ObjectMapper().createObjectNode().put("subject_name", "Biology"));
		ByteArrayOutputStream recordOutput = new ByteArrayOutputStream();
		codec.writeRecords(List.of(record), recordOutput);
		byte[] recordBytes = recordOutput.toByteArray();
		String indexPath = ExamWorkAssetIndex.INDEX_PATH;
		String recordPath = "records/subject.jsonl";
		List<ExamWorkManifest.Entry> entries = List.of(
				new ExamWorkManifest.Entry(indexPath, ExamWorkManifest.EntryRole.INDEX, indexBytes.length,
						sha256(indexBytes)),
				new ExamWorkManifest.Entry(recordPath, ExamWorkManifest.EntryRole.RECORDS, recordBytes.length,
						sha256(recordBytes)));
		boolean assigned = type == ExamWorkPackageType.WORK_ASSIGNMENT || type == ExamWorkPackageType.CONTRIBUTION;
		UUID masterId = type == ExamWorkPackageType.SUBJECT_SNAPSHOT ? null : UUID.randomUUID();
		ExamWorkManifest manifest = new ExamWorkManifest(ExamWorkManifest.CURRENT_FORMAT_VERSION, UUID.randomUUID(),
				type, Instant.parse("2026-11-24T10:00:00Z"), UUID.randomUUID(), masterId, subjectId, "Biology",
				assigned ? UUID.randomUUID() : null, assigned ? UUID.randomUUID() : null, "1.0.1", "1.0.1", 24, 24,
				new ExamWorkManifest.Scope(ExamWorkManifest.ScopeLevel.SUBJECT, List.of(), List.of()), entries);
		Map<String, ExamWorkArchiveWriter.EntrySource> sources = Map.of(indexPath,
				() -> new ByteArrayInputStream(indexBytes), recordPath, () -> new ByteArrayInputStream(recordBytes));
		try (OutputStream output = Files.newOutputStream(archive)) {
			new ExamWorkArchiveWriter().write(manifest, sources, output);
		}
		return manifest;
	}
}
