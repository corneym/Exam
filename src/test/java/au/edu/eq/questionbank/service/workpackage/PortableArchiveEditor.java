package au.edu.eq.questionbank.service.workpackage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.apache.commons.compress.archivers.zip.ZipFile;

import au.edu.eq.questionbank.service.workpackage.ExamWorkArchiveWriter.EntrySource;
import au.edu.eq.questionbank.service.workpackage.ExamWorkManifest.Entry;

/**
 * Builds integrity-valid adversarial packages, including matching assignment
 * baselines.
 */
final class PortableArchiveEditor {

	private PortableArchiveEditor() {
	}

	static Path rewrite(Path original, Path destination, Consumer<List<ExchangeRecord>> edit) throws Exception {
		Map<String, byte[]> contents = new LinkedHashMap<>();
		ExamWorkManifest manifest;
		try (ZipFile zip = ZipFile.builder().setPath(original).get()) {
			try (var input = zip.getInputStream(zip.getEntry("manifest.json"))) {
				manifest = new ManifestCodec().read(input);
			}
			for (Entry entry : manifest.entries()) {
				try (var input = zip.getInputStream(zip.getEntry(entry.path()))) {
					contents.put(entry.path(), input.readAllBytes());
				}
			}
		}
		var codec = new ExamWorkJsonlCodec();
		var records = new ArrayList<>(
				codec.readRecords(new ByteArrayInputStream(contents.get("records/subject-graph.jsonl"))));
		edit.accept(records);
		var output = new ByteArrayOutputStream();
		codec.writeRecords(records, output);
		contents.put("records/subject-graph.jsonl", output.toByteArray());
		if (contents.containsKey("baseline/initial-records.jsonl")) {
			contents.put("baseline/initial-records.jsonl", output.toByteArray());
		}
		List<Entry> entries = new ArrayList<>();
		Map<String, EntrySource> sources = new LinkedHashMap<>();
		for (Entry entry : manifest.entries()) {
			byte[] bytes = contents.get(entry.path());
			String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
			entries.add(new Entry(entry.path(), entry.role(), bytes.length, hash));
			sources.put(entry.path(), () -> new ByteArrayInputStream(bytes));
		}
		var rewritten = new ExamWorkManifest(manifest.formatVersion(), manifest.packageId(), manifest.packageType(),
				manifest.createdAt(), manifest.originInstallationId(), manifest.intendedMasterInstallationId(),
				manifest.subjectId(), manifest.subjectName(), manifest.assignmentId(), manifest.baselineSnapshotId(),
				manifest.producerApplicationVersion(), manifest.minimumReaderApplicationVersion(),
				manifest.producerSchemaVersion(), manifest.minimumReaderSchemaVersion(), manifest.scope(), entries);
		try (var stream = Files.newOutputStream(destination)) {
			new ExamWorkArchiveWriter().write(rewritten, sources, stream);
		}
		return destination;
	}
}
