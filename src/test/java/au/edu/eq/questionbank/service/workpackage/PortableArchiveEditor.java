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
import java.util.function.UnaryOperator;

import org.apache.commons.compress.archivers.zip.ZipFile;

import com.fasterxml.jackson.databind.node.ObjectNode;

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
		return rewriteRecords(original, destination, "records/subject-graph.jsonl", edit);
	}

	static Path rewriteRecords(Path original, Path destination, String recordPath, Consumer<List<ExchangeRecord>> edit)
			throws Exception {
		return rewriteContents(original, destination, contents -> {
			editRecords(contents, recordPath, edit);
		}, UnaryOperator.identity());
	}

	static void editRecords(Map<String, byte[]> contents, String recordPath, Consumer<List<ExchangeRecord>> edit)
			throws Exception {
		var codec = new ExamWorkJsonlCodec();
		var records = new ArrayList<>(codec.readRecords(new ByteArrayInputStream(contents.get(recordPath))));
		edit.accept(records);
		var output = new ByteArrayOutputStream();
		codec.writeRecords(records, output);
		contents.put(recordPath, output.toByteArray());
		if (recordPath.equals("records/subject-graph.jsonl")
				&& contents.containsKey("baseline/initial-records.jsonl")) {
			contents.put("baseline/initial-records.jsonl", output.toByteArray());
		}
	}

	static Path rewriteContents(Path original, Path destination, ContentsEdit edit,
			UnaryOperator<ExamWorkManifest> manifestEdit) throws Exception {
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
		edit.accept(contents);
		manifest = manifestEdit.apply(manifest);
		List<Entry> entries = new ArrayList<>();
		Map<String, EntrySource> sources = new LinkedHashMap<>();
		for (var item : contents.entrySet()) {
			String path = item.getKey();
			var role = manifest.entries().stream().filter(entry -> entry.path().equals(path)).map(Entry::role)
					.findFirst().orElse(ExamWorkManifest.EntryRole.ASSET);
			byte[] bytes = item.getValue();
			String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
			entries.add(new Entry(path, role, bytes.length, hash));
			sources.put(path, () -> new ByteArrayInputStream(bytes));
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

	static Path replaceQuestionPdf(Path original, Path destination, byte[] replacement) throws Exception {
		return rewriteContents(original, destination, contents -> {
			var codec = new ExamWorkJsonlCodec();
			var index = codec.readAssetIndex(new ByteArrayInputStream(contents.get(ExamWorkAssetIndex.INDEX_PATH)));
			var old = index.references().stream()
					.filter(reference -> reference.managedPath().endsWith("/questions.pdf")).findFirst().orElseThrow();
			String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(replacement));
			String path = "assets/sha256/" + hash.substring(0, 2) + "/" + hash + ".pdf";
			var references = new ArrayList<>(index.references());
			references.set(references.indexOf(old), new ExamWorkAssetIndex.Reference(path, hash, replacement.length,
					old.mediaType(), old.managedPath(), old.ownerType(), old.ownerId(), old.usage()));
			var output = new ByteArrayOutputStream();
			codec.writeAssetIndex(new ExamWorkAssetIndex(references), output);
			contents.put(ExamWorkAssetIndex.INDEX_PATH, output.toByteArray());
			contents.remove(old.archivePath());
			contents.put(path, replacement);
			editRecords(contents, "records/subject-graph.jsonl", records -> {
				for (int i = 0; i < records.size(); i++) {
					var record = records.get(i);
					if (record.recordType().equals("SOURCE_DOCUMENT") && record.recordId().equals(old.ownerId())) {
						var values = (ObjectNode) record.values();
						values.put("content_sha256", hash);
						records.set(i, new ExchangeRecord(record.recordVersion(), record.recordType(),
								record.recordId(), record.revisionId(), record.baselineRevisionId(), values));
					}
				}
			});
		}, UnaryOperator.identity());
	}

	@FunctionalInterface
	interface ContentsEdit {

		void accept(Map<String, byte[]> contents) throws Exception;
	}
}
