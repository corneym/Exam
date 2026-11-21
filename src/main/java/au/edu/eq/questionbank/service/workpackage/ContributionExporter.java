package au.edu.eq.questionbank.service.workpackage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import au.edu.eq.questionbank.ApplicationVersion;
import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.repository.identity.SqliteExamAssignmentGraphReader;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository;
import au.edu.eq.questionbank.repository.identity.SqliteSubjectGraphReader.SubjectGraph;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.workpackage.ExamWorkArchiveWriter.EntrySource;
import au.edu.eq.questionbank.service.workpackage.ExamWorkManifest.Entry;
import au.edu.eq.questionbank.service.workpackage.ExamWorkManifest.EntryRole;
import au.edu.eq.questionbank.service.workpackage.ExamWorkManifest.Scope;
import au.edu.eq.questionbank.service.workpackage.ExamWorkManifest.ScopeLevel;

/**
 * Publishes one final contributor return for an installed whole-Exam
 * assignment.
 *
 * <p>
 * The exporter compares a consistent SQLite snapshot against the original
 * immutable assignment baseline. Only supported new capture is exported.
 * Coordinator-owned baseline records cannot be modified by the return.
 *
 * <p>
 * Binary dependencies are collected, the complete archive is validated, and the
 * contribution is published atomically before durable return evidence is
 * recorded. Published files are retained if ledger recording fails.
 *
 * <p>
 * A successfully recorded contribution makes the assignment locally submitted.
 * Further exports require a newly issued assignment.
 */
public final class ContributionExporter {

	private static final String BASELINE_PATH = "baseline/initial-records.jsonl";
	private static final String CHANGES_PATH = "records/contribution-changes.jsonl";
	private static final String CONTEXT_PATH = "records/contribution-context.jsonl";
	private final SqliteDatabase database;
	private final ManagedDataLayout layout;

	/**
	 * Creates an exporter for a contributor installation.
	 *
	 * @param database contributor's initialised SQLite database
	 * @param layout   contributor's managed-data layout
	 * @throws NullPointerException if either argument is null
	 */
	public ContributionExporter(SqliteDatabase database, ManagedDataLayout layout) {
		this.database = Objects.requireNonNull(database, "database");
		this.layout = Objects.requireNonNull(layout, "layout");
	}

	/**
	 * Exports the first and only contribution for an installed assignment.
	 *
	 * <p>
	 * Empty returns and modifications to issued baseline records are refused. A
	 * successfully recorded return prevents another contribution for that
	 * assignment. The original published archive remains available for resending.
	 *
	 * @param assignmentId installed contributor assignment UUID
	 * @param destination  new .examwork archive destination
	 * @return validated published contribution manifest
	 * @throws SQLException          if assignment or publication evidence cannot be
	 *                               recorded
	 * @throws IOException           if snapshot, validation, asset packaging or
	 *                               publication fails
	 * @throws IllegalStateException if the assignment has already been submitted
	 * @throws NullPointerException  if an argument is null
	 */
	public ExamWorkManifest export(UUID assignmentId, Path destination) throws SQLException, IOException {
		Objects.requireNonNull(assignmentId, "assignmentId");
		Path target = requireDestination(destination);
		SqliteContributionReturnLedger ledger = new SqliteContributionReturnLedger(database);
		var latest = ledger.findLatest(assignmentId);
		if (latest.isPresent()) {
			throw new IllegalStateException("This assignment has already been submitted. "
					+ "Resend the original contribution package if necessary. "
					+ "Further work requires a new assignment.");
		}
		UUID previousPackageId = null;
		int returnNumber = 1;
		Path stagingDirectory = Files.createTempDirectory(target.getParent(), ".examwork-contribution-");
		try {
			Path snapshotPath = stagingDirectory.resolve("contributor-snapshot.db");
			database.createConsistentSnapshot(snapshotPath);
			SqliteDatabase snapshotDatabase = SqliteDatabase.readOnly(snapshotPath);
			ContributionSnapshotReader.Snapshot snapshot = new ContributionSnapshotReader(snapshotPath)
					.read(assignmentId);
			List<ExchangeRecord> changes = snapshot.delta().candidates();
			if (changes.isEmpty()) {
				throw new PackageFormatException("No contributor changes to export");
			}
			var session = snapshot.session();
			SubjectGraph graph = new SqliteExamAssignmentGraphReader(snapshotPath).read(session.subjectLocalId(),
					session.examLocalId());
			SubjectAssetCollector.CollectedAssets assets = new SubjectAssetCollector(snapshotDatabase, layout,
					stagingDirectory).collectAssignment(graph);
			Map<String, EntrySource> sources = new LinkedHashMap<>();
			Map<String, Entry> entries = new LinkedHashMap<>();
			addStructuredEntry(sources, entries, CHANGES_PATH, EntryRole.RECORDS, encodeRecords(changes));
			addStructuredEntry(sources, entries, BASELINE_PATH, EntryRole.BASELINE,
					encodeRecords(snapshot.baselineRecords()));
			UUID packageId = UUID.randomUUID();
			UUID installationId = new SqlitePortableIdentityRepository(snapshotDatabase).installationId();
			Instant createdAt = Instant.now();
			ExchangeRecord context = contributionContext(session, installationId, packageId, previousPackageId,
					returnNumber, changes.size(), createdAt);
			addStructuredEntry(sources, entries, CONTEXT_PATH, EntryRole.RECORDS, encodeRecords(List.of(context)));
			addStructuredEntry(sources, entries, ExamWorkAssetIndex.INDEX_PATH, EntryRole.INDEX,
					encodeAssetIndex(assets.index()));
			addBinaryEntries(sources, entries, assets);
			String applicationVersion = ApplicationVersion.current();
			int schemaVersion = snapshotDatabase.schemaVersion();
			ExamWorkManifest manifest = new ExamWorkManifest(ExamWorkManifest.CURRENT_FORMAT_VERSION, packageId,
					ExamWorkPackageType.CONTRIBUTION, createdAt, installationId, session.masterInstallationId(),
					session.subjectPortableId(), graph.subjectName(), session.assignmentId(),
					session.baselineSnapshotId(), applicationVersion, applicationVersion, schemaVersion, schemaVersion,
					new Scope(ScopeLevel.EXAMS, List.of(session.examPortableId()), List.of()),
					new ArrayList<>(entries.values()));
			Path stagedArchive = stagingDirectory.resolve("package.examwork");
			try (OutputStream output = Files.newOutputStream(stagedArchive, StandardOpenOption.CREATE_NEW,
					StandardOpenOption.WRITE)) {
				new ExamWorkArchiveWriter().write(manifest, sources, output);
			}
			ExamWorkManifest verified = new ExamWorkArchiveValidator().validate(stagedArchive, applicationVersion,
					schemaVersion);
			if (!manifest.equals(verified)) {
				throw new PackageFormatException("Validated contribution manifest differs " + "from exported manifest");
			}

			// The archive is fully written and verified before
			// it becomes visible at the requested destination.
			if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
				throw new IOException("Contribution destination already exists: " + target);
			}
			try {
				Files.move(stagedArchive, target, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException failure) {
				throw new IOException("Atomic contribution publication is unavailable", failure);
			}

			// Record only a genuinely published archive.
			// On ledger failure, retain the archive as recovery
			// evidence; never discard potentially returned work.
			ledger.recordPublished(verified, previousPackageId, sha256(target));
			return verified;
		} finally {
			deleteTemporaryTree(stagingDirectory);
		}
	}

	private void addBinaryEntries(Map<String, EntrySource> sources, Map<String, Entry> entries,
			SubjectAssetCollector.CollectedAssets assets) throws IOException {
		for (Map.Entry<String, Path> asset : assets.stagedFiles().entrySet()) {
			String path = asset.getKey();
			Path stagedFile = asset.getValue();
			entries.put(path, new Entry(path, EntryRole.ASSET, Files.size(stagedFile), sha256(stagedFile)));
			sources.put(path, () -> Files.newInputStream(stagedFile));
		}
	}

	private void addStructuredEntry(Map<String, EntrySource> sources, Map<String, Entry> entries, String path,
			EntryRole role, byte[] contents) {
		entries.put(path, new Entry(path, role, contents.length, sha256(contents)));
		sources.put(path, () -> new ByteArrayInputStream(contents));
	}

	private ExchangeRecord contributionContext(SqliteContributorAssignmentSessionRepository.Session session,
			UUID installationId, UUID packageId, UUID previousPackageId, int returnNumber, int candidateCount,
			Instant createdAt) {
		ObjectNode values = JsonNodeFactory.instance.objectNode();
		values.put("assignment_id", session.assignmentId().toString());
		values.put("baseline_snapshot_id", session.baselineSnapshotId().toString());
		values.put("contributor_id", session.contributorId().toString());
		values.put("contributor_name", session.contributorName());
		values.put("master_installation_id", session.masterInstallationId().toString());
		values.put("origin_installation_id", installationId.toString());
		values.put("issued_package_id", session.packageId().toString());
		values.put("package_id", packageId.toString());
		values.put("subject_id", session.subjectPortableId().toString());
		values.put("exam_id", session.examPortableId().toString());
		values.put("created_at", createdAt.toString());
		values.put("return_number", returnNumber);
		values.put("candidate_count", candidateCount);
		if (previousPackageId == null) {
			values.putNull("previous_return_package_id");
		} else {
			values.put("previous_return_package_id", previousPackageId.toString());
		}
		return new ExchangeRecord(ExchangeRecord.CURRENT_RECORD_VERSION, "CONTRIBUTION_CONTEXT", packageId, packageId,
				null, values);
	}

	private void deleteTemporaryTree(Path root) throws IOException {
		if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		try (Stream<Path> paths = Files.walk(root)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		}
	}

	private byte[] encodeAssetIndex(ExamWorkAssetIndex index) throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		new ExamWorkJsonlCodec().writeAssetIndex(index, output);
		return output.toByteArray();
	}

	private byte[] encodeRecords(List<ExchangeRecord> records) throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		new ExamWorkJsonlCodec().writeRecords(records, output);
		return output.toByteArray();
	}

	private MessageDigest newDigest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException failure) {
			throw new IllegalStateException("SHA-256 is unavailable", failure);
		}
	}

	private Path requireDestination(Path destination) throws IOException {
		Objects.requireNonNull(destination, "destination");
		Path target = destination.toAbsolutePath().normalize();
		if (target.getFileName() == null || !target.getFileName().toString().endsWith(".examwork")) {
			throw new IllegalArgumentException("Contribution destination must end in .examwork");
		}
		if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Contribution destination already exists: " + target);
		}
		Path parent = target.getParent();
		if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Contribution destination directory does not exist");
		}
		Path physicalParent = parent.toRealPath();
		Path subjectsRoot = layout.subjectsRoot().toAbsolutePath().normalize();
		if (physicalParent.startsWith(subjectsRoot)) {
			throw new IOException(
					"Contribution packages cannot be exported " + "inside the managed Subjects directory");
		}
		return target;
	}

	private String sha256(byte[] contents) {
		return HexFormat.of().formatHex(newDigest().digest(contents));
	}

	private String sha256(Path source) throws IOException {
		MessageDigest digest = newDigest();
		try (InputStream input = Files.newInputStream(source)) {
			byte[] buffer = new byte[8192];
			int count;
			while ((count = input.read(buffer)) != -1) {
				digest.update(buffer, 0, count);
			}
		}
		return HexFormat.of().formatHex(digest.digest());
	}
}
