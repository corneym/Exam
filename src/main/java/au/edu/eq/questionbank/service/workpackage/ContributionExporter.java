package au.edu.eq.questionbank.service.workpackage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
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
import au.edu.eq.questionbank.ManagedAssetPublicationLock;
import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.repository.identity.SqliteExamAssignmentGraphReader;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository;
import au.edu.eq.questionbank.repository.identity.SqliteSubjectGraphReader.SubjectGraph;
import au.edu.eq.questionbank.repository.sqlite.SqliteContributorAssignmentVisibilityRepository;
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
 * Binary dependencies are collected and the complete archive is validated before
 * a durable publication attempt is recorded. Final bytes are created without
 * replacement and forced on the destination filesystem. Interruption after
 * publication may have started remains fail-closed until the exact archive is
 * authenticated and detached return evidence is recorded.
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
		try (ManagedAssetPublicationLock lock = ManagedAssetPublicationLock.acquire(layout.dataRoot())) {
			ContributionPublicationJournal journal = new ContributionPublicationJournal(database, layout);
			journal.recoverPending(lock);
			Path target = requireDestination(destination);
			SqliteContributionReturnLedger ledger = new SqliteContributionReturnLedger(database);
			if (ledger.findLatest(assignmentId).isPresent()) {
				throw new IllegalStateException("This assignment has already been submitted. "
						+ "Resend the original contribution package if necessary. "
						+ "Further work requires a new assignment.");
			}
			UUID previousPackageId = null;
			int returnNumber = 1;
			Path buildDirectory = Files.createTempDirectory("examwork-contribution-build-");
			ContributionPublicationJournal.Attempt attempt = null;
			Path destinationStaging = null;
			boolean publicationMayHaveStarted = false;
			try {
				Path snapshotPath = buildDirectory.resolve("contributor-snapshot.db");
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
						buildDirectory).collectAssignment(graph);
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
				Path builtArchive = buildDirectory.resolve("package.examwork");
				try (OutputStream output = Files.newOutputStream(builtArchive, StandardOpenOption.CREATE_NEW,
						StandardOpenOption.WRITE)) {
					new ExamWorkArchiveWriter().write(manifest, sources, output);
				}
				ExamWorkManifest verified = new ExamWorkArchiveValidator().validate(builtArchive, applicationVersion,
						schemaVersion);
				if (!manifest.equals(verified)) {
					throw new PackageFormatException(
							"Validated contribution manifest differs from exported manifest");
				}
				String digest = sha256(builtArchive);
				destinationStaging = target.resolveSibling(".examwork-contribution-" + packageId + ".tmp");
				Files.copy(builtArchive, destinationStaging);
				forceFile(destinationStaging);
				if (!digest.equals(sha256(destinationStaging))) {
					throw new IOException("Contribution destination staging verification failed");
				}
				attempt = journal.prepare(verified, digest, target, destinationStaging, lock);
				attempt = journal.markPublishing(attempt, lock);
				publicationMayHaveStarted = true;
				publishCreateNew(destinationStaging, target);
				if (!digest.equals(sha256(target))) {
					throw new IOException("Published Contribution archive verification failed");
				}
				Files.delete(destinationStaging);
				attempt = journal.markPublished(attempt, lock);
				journal.finalisePublished(attempt, verified, lock);
				return verified;
			} catch (IOException | SQLException | RuntimeException failure) {
				if (attempt != null && !publicationMayHaveStarted) {
					try {
						journal.cancelPrepared(attempt, lock);
					} catch (IOException | SQLException cleanupFailure) {
						failure.addSuppressed(cleanupFailure);
					}
				} else if (attempt == null && destinationStaging != null) {
					try {
						Files.deleteIfExists(destinationStaging);
					} catch (IOException cleanupFailure) {
						failure.addSuppressed(cleanupFailure);
					}
				}
				throw failure;
			} finally {
				deleteTemporaryTree(buildDirectory);
			}
		}
	}

	/**
	 * Determines whether an installed assignment has saved work that may be
	 * exported as a contribution.
	 *
	 * <p>
	 * The lifecycle state and saved-work delta are inspected using a consistent
	 * database snapshot. No contribution archive is created and no return evidence
	 * is recorded.
	 *
	 * <p>
	 * This advisory check does not replace the validation performed by the final
	 * export operation.
	 *
	 * @param assignmentId installed contributor assignment UUID
	 * @throws SQLException             if assignment persistence cannot be
	 *                                  inspected
	 * @throws IOException              if the snapshot cannot be inspected, the
	 *                                  contribution is empty, or its changes are
	 *                                  invalid
	 * @throws IllegalStateException    if the assignment has been removed or
	 *                                  already submitted
	 * @throws IllegalArgumentException if the assignment is not installed
	 * @throws NullPointerException     if the assignment identity is null
	 */
	public void preflight(UUID assignmentId) throws SQLException, IOException {
		Objects.requireNonNull(assignmentId, "assignmentId");
		try (ManagedAssetPublicationLock lock = ManagedAssetPublicationLock.acquire(layout.dataRoot())) {
			new ContributionPublicationJournal(database, layout).recoverPending(lock);
			Path stagingDirectory = Files.createTempDirectory("examwork-contribution-preflight-");
			try {
				Path snapshotPath = stagingDirectory.resolve("contributor-snapshot.db");
				database.createConsistentSnapshot(snapshotPath);
				SqliteDatabase snapshotDatabase = SqliteDatabase.readOnly(snapshotPath);
				var visibility = new SqliteContributorAssignmentVisibilityRepository(snapshotDatabase);
				var state = visibility.find(assignmentId).orElseThrow(
						() -> new IllegalArgumentException("Unknown contributor assignment: " + assignmentId));
				if (state == SqliteContributorAssignmentVisibilityRepository.State.SUBMITTED) {
					throw new IllegalStateException("This assignment has already been submitted. "
							+ "Resend the original contribution package if necessary. "
							+ "Further work requires a new assignment.");
				}
				if (state != SqliteContributorAssignmentVisibilityRepository.State.ACTIVE) {
					throw new IllegalStateException("This assignment is no longer available for contribution export.");
				}
				var snapshot = new ContributionSnapshotReader(snapshotPath).read(assignmentId);

				// An unchanged assignment must be rejected without
				// requiring a destination or producing any return evidence.
				if (snapshot.delta().candidates().isEmpty()) {
					throw new PackageFormatException("No contributor changes to export. "
							+ "Capture and save new work before exporting a contribution.");
				}
			} finally {
				deleteTemporaryTree(stagingDirectory);
			}
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

	private void forceFile(Path file) throws IOException {
		try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
			channel.force(true);
		}
	}

	private MessageDigest newDigest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException failure) {
			throw new IllegalStateException("SHA-256 is unavailable", failure);
		}
	}

	private void publishCreateNew(Path staging, Path target) throws IOException {
		try (FileChannel input = FileChannel.open(staging, StandardOpenOption.READ);
				FileChannel output = FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
			long position = 0;
			while (position < input.size()) {
				long transferred = input.transferTo(position, input.size() - position, output);
				if (transferred <= 0) {
					throw new IOException("Contribution publication made no progress");
				}
				position += transferred;
			}
			output.force(true);
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
