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

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import au.edu.eq.questionbank.ApplicationVersion;
import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.repository.identity.SqliteExamAssignmentGraphReader;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
import au.edu.eq.questionbank.repository.identity.SqliteSubjectGraphReader.SubjectGraph;
import au.edu.eq.questionbank.repository.identity.SqliteSubjectPortableRecordReader;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.workpackage.ExamWorkArchiveWriter.EntrySource;
import au.edu.eq.questionbank.service.workpackage.ExamWorkManifest.Entry;
import au.edu.eq.questionbank.service.workpackage.ExamWorkManifest.EntryRole;
import au.edu.eq.questionbank.service.workpackage.ExamWorkManifest.Scope;
import au.edu.eq.questionbank.service.workpackage.ExamWorkManifest.ScopeLevel;

/**
 * Builds and validates a single whole-Exam offline assignment archive.
 *
 * <p>
 * This service produces a portable package but does not record formal
 * assignment issuance in the coordinator's database.
 */
public final class WorkAssignmentExporter {

	private static final String ASSIGNMENT_CONTEXT_PATH = "records/assignment-context.jsonl";
	private static final String BASELINE_PATH = "baseline/initial-records.jsonl";
	private static final String RECORDS_PATH = "records/subject-graph.jsonl";
	private final SqliteDatabase database;
	private final ManagedDataLayout layout;

	/**
	 * Creates an assignment exporter for the authoritative master.
	 *
	 * @param database coordinator's SQLite database
	 * @param layout   coordinator's managed source-asset layout
	 */
	public WorkAssignmentExporter(SqliteDatabase database, ManagedDataLayout layout) {
		this.database = Objects.requireNonNull(database, "database");
		this.layout = Objects.requireNonNull(layout, "layout");
	}

	/**
	 * Creates a validated whole-Exam assignment archive.
	 *
	 * <p>
	 * All database records are read from one consistent snapshot. The immutable
	 * baseline records and initial contributor records therefore have identical
	 * values and revisions at issuance.
	 *
	 * @param request validated coordinator assignment request
	 * @return manifest of the published archive
	 * @throws SQLException if master persistence is invalid
	 * @throws IOException  if preflight, asset staging, validation or archive
	 *                      publication fails
	 */
	public ExamWorkManifest export(Request request) throws SQLException, IOException {
		Objects.requireNonNull(request, "request");
		Path destination = requireDestination(request.destination());
		Path stagingDirectory = Files.createTempDirectory(destination.getParent(), ".examwork-assignment-");
		try {
			Path databaseSnapshot = stagingDirectory.resolve("assignment-snapshot.db");
			database.createConsistentSnapshot(databaseSnapshot);
			SqliteDatabase snapshotDatabase = SqliteDatabase.readOnly(databaseSnapshot);

			// Preflight and record selection must observe the same
			// database snapshot, not two independently changing views.
			ExamAssignmentPreflight.Result readiness = new ExamAssignmentPreflight(snapshotDatabase, layout)
					.check(request.exam(), request.legacyCaptureEnabled());
			long subjectId = readiness.exam().getSubject().getId();
			long examId = readiness.exam().getId();
			SubjectGraph graph = new SqliteExamAssignmentGraphReader(databaseSnapshot).read(subjectId, examId);
			if (!graph.ids(EntityType.EXAM).equals(List.of(examId))) {
				throw new PackageFormatException("Assignment must contain exactly one Exam");
			}
			List<ExchangeRecord> records = new SqliteSubjectPortableRecordReader(databaseSnapshot).read(graph);
			SqlitePortableIdentityRepository identities = new SqlitePortableIdentityRepository(snapshotDatabase);
			UUID subjectPortableId = identities.requireByLocalId(EntityType.SUBJECT, subjectId).portableId();
			UUID examPortableId = identities.requireByLocalId(EntityType.EXAM, examId).portableId();
			UUID currentSyllabusId = identities
					.requireByLocalId(EntityType.SYLLABUS_VERSION, readiness.currentSyllabus().getId()).portableId();
			UUID legacySyllabusId = null;
			if (request.legacyCaptureEnabled()) {
				legacySyllabusId = identities
						.requireByLocalId(EntityType.SYLLABUS_VERSION, readiness.legacy2019Syllabus().getId())
						.portableId();
			}
			UUID masterId = identities.installationId();
			UUID baselineId = UUID.randomUUID();
			Instant issuedAt = Instant.now();
			SubjectAssetCollector.CollectedAssets assets = new SubjectAssetCollector(snapshotDatabase, layout,
					stagingDirectory).collectAssignment(graph);
			validateStagedPdfs(assets);
			Map<String, EntrySource> sources = new LinkedHashMap<>();
			Map<String, Entry> entries = new LinkedHashMap<>();
			byte[] initialRecords = encodeRecords(records);
			addStructuredEntry(sources, entries, RECORDS_PATH, EntryRole.RECORDS, initialRecords);

			// Preserve the original record values and revision tokens.
			// Contributor modifications must never overwrite this entry.
			addStructuredEntry(sources, entries, BASELINE_PATH, EntryRole.BASELINE, initialRecords);
			List<ExchangeRecord> reconciliationEvidence = new AssignmentReconciliationEvidenceReader(snapshotDatabase)
					.read(examPortableId, request.contributorId());
			ExchangeRecord assignmentContext = assignmentContext(request, masterId, subjectPortableId, examPortableId,
					currentSyllabusId, legacySyllabusId, baselineId, issuedAt, reconciliationEvidence);
			addStructuredEntry(sources, entries, ASSIGNMENT_CONTEXT_PATH, EntryRole.RECORDS,
					encodeRecords(List.of(assignmentContext)));
			addStructuredEntry(sources, entries, ExamWorkAssetIndex.INDEX_PATH, EntryRole.INDEX,
					encodeAssetIndex(assets.index()));
			addBinaryEntries(sources, entries, assets);
			String applicationVersion = ApplicationVersion.current();
			int schemaVersion = snapshotDatabase.schemaVersion();
			ExamWorkManifest manifest = new ExamWorkManifest(ExamWorkManifest.CURRENT_FORMAT_VERSION, UUID.randomUUID(),
					ExamWorkPackageType.WORK_ASSIGNMENT, issuedAt, masterId, masterId, subjectPortableId,
					graph.subjectName(), request.assignmentId(), baselineId, applicationVersion, applicationVersion,
					schemaVersion, schemaVersion, new Scope(ScopeLevel.EXAMS, List.of(examPortableId), List.of()),
					new ArrayList<>(entries.values()));
			Path stagedArchive = stagingDirectory.resolve("package.examwork");
			try (OutputStream output = Files.newOutputStream(stagedArchive, StandardOpenOption.CREATE_NEW,
					StandardOpenOption.WRITE)) {
				new ExamWorkArchiveWriter().write(manifest, sources, output);
			}
			ExamWorkManifest verified = new WorkAssignmentPackageInspector()
					.inspect(stagedArchive, applicationVersion, schemaVersion).manifest();
			if (!manifest.equals(verified)) {
				throw new PackageFormatException("Validated assignment manifest differs from export");
			}

			// Do not publish an archive before all validation succeeds.
			if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
				throw new IOException("Assignment destination already exists: " + destination);
			}
			try {
				Files.move(stagedArchive, destination, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException exception) {
				throw new IOException("Atomic assignment publication is unavailable", exception);
			}
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

	private ExchangeRecord assignmentContext(Request request, UUID masterId, UUID subjectId, UUID examId,
			UUID currentSyllabusId, UUID legacySyllabusId, UUID baselineId, Instant issuedAt,
			List<ExchangeRecord> reconciliationEvidence) {
		ObjectNode values = JsonNodeFactory.instance.objectNode();
		values.put("assignment_id", request.assignmentId().toString());
		values.put("baseline_snapshot_id", baselineId.toString());
		values.put("contributor_id", request.contributorId().toString());
		values.put("contributor_name", request.contributorName());
		values.put("current_syllabus_id", currentSyllabusId.toString());
		values.put("exam_id", examId.toString());
		values.put("issued_at", issuedAt.toString());
		values.put("legacy_capture_enabled", request.legacyCaptureEnabled());
		values.put("master_installation_id", masterId.toString());
		values.put("subject_id", subjectId.toString());
		if (legacySyllabusId != null) {
			values.put("legacy_syllabus_id", legacySyllabusId.toString());
		}

		// Carry accepted historical identity mappings inside the
		// integrity-checked assignment context. No separate receipt
		// exchange is required.
		ArrayNode evidence = values.putArray("reconciliation_evidence");
		for (ExchangeRecord record : reconciliationEvidence) {
			ObjectNode entry = evidence.addObject();
			entry.put("evidence_id", record.recordId().toString());
			entry.set("mapping", record.values());
		}
		return new ExchangeRecord(ExchangeRecord.CURRENT_RECORD_VERSION, "WORK_ASSIGNMENT_CONTEXT",
				request.assignmentId(), baselineId, null, values);
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
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private Path requireDestination(Path destination) throws IOException {
		Objects.requireNonNull(destination, "destination");
		Path target = destination.toAbsolutePath().normalize();
		if (target.getFileName() == null || !target.getFileName().toString().endsWith(".examwork")) {
			throw new IllegalArgumentException("Assignment destination must end in .examwork");
		}
		if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Assignment destination already exists: " + target);
		}
		Path parent = target.getParent();
		if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Assignment destination directory does not exist");
		}
		Path physicalParent = parent.toRealPath();
		Path subjectsRoot = layout.subjectsRoot().toAbsolutePath().normalize();
		if (physicalParent.startsWith(subjectsRoot)) {
			throw new IOException("Assignment packages cannot be exported inside " + "the managed Subjects directory");
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

	private void validateStagedPdfs(SubjectAssetCollector.CollectedAssets assets) throws IOException {

		// Validate the immutable copies too: live sources may change after preflight.
		for (var asset : assets.stagedFiles().entrySet()) {
			if (asset.getKey().endsWith(".pdf")) {
				WorkAssignmentPdfValidator.validate(asset.getValue(), asset.getKey());
			}
		}
	}

	/**
	 * Coordinator-supplied information for an assignment export.
	 *
	 * @param exam                 existing master Exam
	 * @param assignmentId         stable proposed assignment identity
	 * @param contributorId        portable contributor identity
	 * @param contributorName      human-readable contributor label
	 * @param legacyCaptureEnabled permit original 2019 classification
	 * @param destination          new .examwork archive destination
	 */
	public record Request(Exam exam, UUID assignmentId, UUID contributorId, String contributorName,
			boolean legacyCaptureEnabled, Path destination) {

		/**
		 * Validates the assignment export request.
		 */
		public Request {
			Objects.requireNonNull(exam, "exam");
			Objects.requireNonNull(assignmentId, "assignmentId");
			Objects.requireNonNull(contributorId, "contributorId");
			Objects.requireNonNull(contributorName, "contributorName");
			Objects.requireNonNull(destination, "destination");
			contributorName = contributorName.strip();
			if (contributorName.isEmpty() || contributorName.length() > 256
					|| contributorName.chars().anyMatch(Character::isISOControl)) {
				throw new IllegalArgumentException("Invalid contributor name");
			}
		}
	}
}
