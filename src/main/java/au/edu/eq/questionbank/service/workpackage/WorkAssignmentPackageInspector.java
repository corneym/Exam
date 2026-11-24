package au.edu.eq.questionbank.service.workpackage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import au.edu.eq.questionbank.service.workpackage.ExamWorkManifest.Entry;
import au.edu.eq.questionbank.service.workpackage.ExamWorkManifest.EntryRole;

/**
 * Performs read-only package, domain-semantic and scope validation before a
 * contributor installation may import a WORK_ASSIGNMENT.
 */
public final class WorkAssignmentPackageInspector {

	private static final String BASELINE_PATH = "baseline/initial-records.jsonl";
	private static final String CONTEXT_PATH = "records/assignment-context.jsonl";
	private static final String RECORDS_PATH = "records/subject-graph.jsonl";

	/**
	 * Creates a read-only assignment package inspector.
	 */
	public WorkAssignmentPackageInspector() {
	}

	/**
	 * Validates and inspects a whole-Exam assignment without modifying the
	 * contributor's database or managed files.
	 *
	 * @param archive            existing .examwork archive
	 * @param applicationVersion installed application version
	 * @param schemaVersion      installed SQLite schema version
	 * @return validated assignment and immutable baseline
	 * @throws IOException if the archive is invalid, unreadable or changes during
	 *                     inspection
	 */
	public Inspection inspect(Path archive, String applicationVersion, int schemaVersion) throws IOException {
		Objects.requireNonNull(archive, "archive");
		Objects.requireNonNull(applicationVersion, "applicationVersion");
		String originalHash = sha256(archive);
		ExamWorkManifest manifest = new ExamWorkArchiveValidator().validate(archive, applicationVersion, schemaVersion);
		if (manifest.packageType() != ExamWorkPackageType.WORK_ASSIGNMENT) {
			throw new PackageFormatException("Contributor import requires WORK_ASSIGNMENT");
		}
		requireStructure(manifest);
		ExamWorkJsonlCodec codec = new ExamWorkJsonlCodec();
		List<ExchangeRecord> records;
		List<ExchangeRecord> baseline;
		ExchangeRecord context;
		ExamWorkAssetIndex assets;
		try (ZipFile zip = ZipFile.builder().setPath(archive).get()) {
			records = codec.readRecords(new ByteArrayInputStream(readEntry(zip, manifest, RECORDS_PATH)));
			baseline = codec.readRecords(new ByteArrayInputStream(readEntry(zip, manifest, BASELINE_PATH)));
			List<ExchangeRecord> contexts = codec
					.readRecords(new ByteArrayInputStream(readEntry(zip, manifest, CONTEXT_PATH)));
			if (contexts.size() != 1) {
				throw new PackageFormatException("Assignment requires exactly one context");
			}
			context = contexts.getFirst();
			assets = codec
					.readAssetIndex(new ByteArrayInputStream(readEntry(zip, manifest, ExamWorkAssetIndex.INDEX_PATH)));
		}

		// An issued assignment begins with the baseline itself.
		// No contributor changes belong in the original package.
		if (!records.equals(baseline)) {
			throw new PackageFormatException("Issued baseline differs from initial Exam records");
		}
		assets.requireMatchingManifest(manifest);
		new WorkAssignmentScopeValidator().validate(manifest, records, context, assets);

		PortableDomainRecordValidator.validate(manifest.subjectId(), records);

		new AssignmentReconciliationEvidenceValidator().validate(manifest, context);

		// Detect replacement of the archive between validation and
		// inspection. The importer must still stage its own immutable
		// copy before publishing any database changes.
		if (!originalHash.equals(sha256(archive))) {
			throw new PackageFormatException("Assignment archive changed during inspection");
		}
		return new Inspection(manifest, records, baseline, context, assets, originalHash);
	}

	private MessageDigest newDigest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private byte[] readEntry(ZipFile zip, ExamWorkManifest manifest, String path) throws IOException {
		Entry declared = manifest.entries().stream().filter(entry -> entry.path().equals(path)).findFirst()
				.orElseThrow(() -> new PackageFormatException("Missing required assignment entry: " + path));
		if (declared.size() > 64L * 1024 * 1024) {
			throw new PackageFormatException("Assignment structured entry exceeds permitted size");
		}
		ZipArchiveEntry actual = zip.getEntry(path);
		if (actual == null) {
			throw new PackageFormatException("Missing assignment ZIP entry: " + path);
		}
		byte[] contents;
		try (InputStream input = zip.getInputStream(actual)) {
			contents = input.readNBytes((int) declared.size() + 1);
		}
		if (contents.length != declared.size() || !sha256(contents).equals(declared.sha256())) {
			throw new PackageFormatException("Assignment entry changed or failed integrity check: " + path);
		}
		return contents;
	}

	private void requireStructure(ExamWorkManifest manifest) throws PackageFormatException {
		Map<String, EntryRole> required = Map.of(RECORDS_PATH, EntryRole.RECORDS, BASELINE_PATH, EntryRole.BASELINE,
				CONTEXT_PATH, EntryRole.RECORDS, ExamWorkAssetIndex.INDEX_PATH, EntryRole.INDEX);
		Set<String> found = new HashSet<>();
		for (Entry entry : manifest.entries()) {
			EntryRole expected = required.get(entry.path());
			if (expected == null) {
				if (entry.role() != EntryRole.ASSET) {
					throw new PackageFormatException("Unexpected assignment structured entry: " + entry.path());
				}
				continue;
			}
			if (entry.role() != expected) {
				throw new PackageFormatException("Invalid assignment entry role: " + entry.path());
			}
			found.add(entry.path());
		}
		if (!found.equals(required.keySet())) {
			throw new PackageFormatException("Incomplete assignment archive structure");
		}
	}

	private String sha256(byte[] contents) {
		return HexFormat.of().formatHex(newDigest().digest(contents));
	}

	private String sha256(Path archive) throws IOException {
		MessageDigest digest = newDigest();
		try (InputStream input = Files.newInputStream(archive)) {
			byte[] buffer = new byte[8192];
			int count;
			while ((count = input.read(buffer)) != -1) {
				digest.update(buffer, 0, count);
			}
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	/**
	 * Read-only validated contributor assignment package.
	 *
	 * @param manifest      assignment manifest
	 * @param records       original Subject/Exam records
	 * @param baseline      immutable issuance baseline records
	 * @param context       validated contributor assignment context
	 * @param assets        content-addressed asset references
	 * @param archiveSha256 original archive digest
	 */
	public record Inspection(ExamWorkManifest manifest, List<ExchangeRecord> records, List<ExchangeRecord> baseline,
			ExchangeRecord context, ExamWorkAssetIndex assets, String archiveSha256) {

		/**
		 * Validates and copies inspection results.
		 */
		public Inspection {
			Objects.requireNonNull(manifest, "manifest");
			records = List.copyOf(records);
			baseline = List.copyOf(baseline);
			Objects.requireNonNull(context, "context");
			Objects.requireNonNull(assets, "assets");
			Objects.requireNonNull(archiveSha256, "archiveSha256");
		}
	}
}
