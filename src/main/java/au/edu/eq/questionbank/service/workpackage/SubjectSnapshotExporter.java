package au.edu.eq.questionbank.service.workpackage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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

import au.edu.eq.questionbank.ApplicationVersion;
import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
import au.edu.eq.questionbank.repository.identity.SqliteSubjectGraphReader;
import au.edu.eq.questionbank.repository.identity.SqliteSubjectGraphReader.SubjectGraph;
import au.edu.eq.questionbank.repository.identity.SqliteSubjectPortableRecordReader;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.workpackage.ExamWorkArchiveWriter.EntrySource;
import au.edu.eq.questionbank.service.workpackage.ExamWorkManifest.Entry;
import au.edu.eq.questionbank.service.workpackage.ExamWorkManifest.EntryRole;
import au.edu.eq.questionbank.service.workpackage.ExamWorkManifest.Scope;
import au.edu.eq.questionbank.service.workpackage.ExamWorkManifest.ScopeLevel;
import au.edu.eq.questionbank.service.workpackage.SubjectAssetCollector.CollectedAssets;

/**
 * Exports one complete Subject as a validated, self-contained
 * {@code SUBJECT_SNAPSHOT} work package.
 *
 * <p>
 * Export begins with a transactionally consistent temporary SQLite snapshot.
 * All domain records, portable identities, revision information and embedded
 * binary content are subsequently read from that immutable snapshot.
 *
 * <p>
 * Managed PDF and workbook assets are staged as immutable copies. The exporter
 * also inventories retained Subject-first files that are not directly
 * referenced by persistent database records.
 *
 * <p>
 * The archive contains canonical JSONL records, an asset-reference index and
 * SHA-256-addressed binary assets. Neither the live database nor the temporary
 * SQLite snapshot is included in the portable exchange package.
 *
 * <p>
 * The completed archive is checked by the common read-only package validator
 * before publication. The final publication uses an atomic filesystem move.
 * Existing destination files are not intentionally overwritten.
 *
 * <p>
 * This service performs export only. It does not reconcile independent
 * installations, modify canonical identities, import data or change the
 * selected Subject's authoritative source paths.
 *
 * @see ExamWorkArchiveWriter
 * @see ExamWorkArchiveValidator
 * @see SqliteSubjectGraphReader
 * @see SubjectAssetCollector
 */
public final class SubjectSnapshotExporter {

	private static final String RECORDS_PATH = "records/subject-graph.jsonl";
	private final SqliteDatabase database;
	private final ManagedDataLayout layout;
	private final ExamWorkArchiveValidator validator;
	private final ExamWorkArchiveWriter writer;
	private final ExamWorkJsonlCodec jsonlCodec;

	/**
	 * Creates a Subject snapshot exporter.
	 *
	 * @param database initialised authoritative SQLite database
	 * @param layout   authoritative managed-data layout
	 * @throws NullPointerException if either argument is null
	 */
	public SubjectSnapshotExporter(SqliteDatabase database, ManagedDataLayout layout) {
		this.database = Objects.requireNonNull(database, "database");
		this.layout = Objects.requireNonNull(layout, "layout");
		validator = new ExamWorkArchiveValidator();
		writer = new ExamWorkArchiveWriter();
		jsonlCodec = new ExamWorkJsonlCodec();
	}

	/**
	 * Exports one Subject into a validated {@code .examwork} archive.
	 *
	 * <p>
	 * All output is staged in a temporary directory beside the destination. The
	 * final destination is published only after the common validator accepts the
	 * fully written archive.
	 *
	 * <p>
	 * The destination must end in {@code .examwork}, must not already exist and
	 * must be outside the application-managed Subjects directory.
	 *
	 * @param subjectId   SQLite-local identifier of the selected Subject
	 * @param destination new package destination
	 * @return immutable manifest of the published package
	 * @throws SQLException             if the database or Subject graph is invalid
	 * @throws IOException              if a referenced asset is unavailable,
	 *                                  package creation fails, validation fails or
	 *                                  publication cannot be completed safely
	 * @throws IllegalArgumentException if the Subject identifier or destination is
	 *                                  invalid
	 * @throws NullPointerException     if the destination is null
	 */
	public ExamWorkManifest export(long subjectId, Path destination) throws SQLException, IOException {
		if (subjectId < 1) {
			throw new IllegalArgumentException("subjectId must be positive");
		}
		Path target = requireDestination(destination);
		Path stagingDirectory = Files.createTempDirectory(target.getParent(), ".examwork-subject-");
		try {
			Path databaseSnapshot = stagingDirectory.resolve("subject-snapshot.db");

			// The database snapshot is a private read source.
			// It must never be included in the exchange archive.
			database.createConsistentSnapshot(databaseSnapshot);
			SqliteDatabase snapshotDatabase = SqliteDatabase.readOnly(databaseSnapshot);
			SqliteSubjectGraphReader graphReader = new SqliteSubjectGraphReader(databaseSnapshot);
			SubjectGraph graph = graphReader.read(subjectId);
			SqliteSubjectPortableRecordReader recordReader = new SqliteSubjectPortableRecordReader(databaseSnapshot);
			List<ExchangeRecord> records = recordReader.read(graph);
			SqlitePortableIdentityRepository identities = new SqlitePortableIdentityRepository(snapshotDatabase);
			UUID subjectPortableId = identities.requireByLocalId(EntityType.SUBJECT, subjectId).portableId();
			UUID originInstallationId = identities.installationId();
			SubjectAssetCollector collector = new SubjectAssetCollector(snapshotDatabase, layout, stagingDirectory);
			CollectedAssets assets = collector.collect(graph);
			Map<String, EntrySource> sources = new LinkedHashMap<>();
			Map<String, Entry> entries = new LinkedHashMap<>();
			addStructuredEntry(sources, entries, RECORDS_PATH, EntryRole.RECORDS, encodeRecords(records));
			addStructuredEntry(sources, entries, ExamWorkAssetIndex.INDEX_PATH, EntryRole.INDEX,
					encodeAssetIndex(assets.index()));
			addBinaryEntries(sources, entries, assets);
			String applicationVersion = ApplicationVersion.current();
			int schemaVersion = snapshotDatabase.schemaVersion();
			ExamWorkManifest manifest = new ExamWorkManifest(ExamWorkManifest.CURRENT_FORMAT_VERSION, UUID.randomUUID(),
					ExamWorkPackageType.SUBJECT_SNAPSHOT, Instant.now(), originInstallationId, null, subjectPortableId,
					graph.subjectName(), null, null, applicationVersion, applicationVersion, schemaVersion,
					schemaVersion, new Scope(ScopeLevel.SUBJECT, List.of(), List.of()),
					new ArrayList<>(entries.values()));
			Path stagedArchive = stagingDirectory.resolve("package.examwork");
			try (var output = Files.newOutputStream(stagedArchive, StandardOpenOption.CREATE_NEW,
					StandardOpenOption.WRITE)) {
				writer.write(manifest, sources, output);
			}
			ExamWorkManifest verified = validator.validate(stagedArchive, applicationVersion, schemaVersion);
			if (!manifest.equals(verified)) {
				throw new PackageFormatException("Validated package manifest differs " + "from exported manifest");
			}
			if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
				throw new IOException("Export destination already exists: " + target);
			}
			try {
				Files.move(stagedArchive, target, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException exception) {

				// Do not substitute an unverified publication path.
				// A destination on an unsupported filesystem must fail.
				throw new IOException("Atomic package publication is not supported " + "by the destination filesystem",
						exception);
			}
			return verified;
		} finally {
			deleteTemporaryTree(stagingDirectory);
		}
	}

	private void addBinaryEntries(Map<String, EntrySource> sources, Map<String, Entry> entries, CollectedAssets assets)
			throws IOException {
		for (Map.Entry<String, Path> asset : assets.stagedFiles().entrySet()) {
			String archivePath = asset.getKey();
			Path stagedPath = asset.getValue();
			long size = Files.size(stagedPath);
			String hash = sha256(stagedPath);
			entries.put(archivePath, new Entry(archivePath, EntryRole.ASSET, size, hash));
			sources.put(archivePath, () -> Files.newInputStream(stagedPath));
		}
	}

	private void addStructuredEntry(Map<String, EntrySource> sources, Map<String, Entry> entries, String archivePath,
			EntryRole role, byte[] contents) {
		String hash = sha256(contents);
		entries.put(archivePath, new Entry(archivePath, role, contents.length, hash));
		sources.put(archivePath, () -> new ByteArrayInputStream(contents));
	}

	private void deleteTemporaryTree(Path root) throws IOException {
		if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		try (Stream<Path> paths = Files.walk(root)) {
			List<Path> removalOrder = paths.sorted(Comparator.reverseOrder()).toList();
			for (Path path : removalOrder) {
				Files.deleteIfExists(path);
			}
		}
	}

	private byte[] encodeAssetIndex(ExamWorkAssetIndex index) throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		jsonlCodec.writeAssetIndex(index, output);
		return output.toByteArray();
	}

	private byte[] encodeRecords(List<ExchangeRecord> records) throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		jsonlCodec.writeRecords(records, output);
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
		String filename = target.getFileName().toString();
		if (!filename.endsWith(".examwork")) {
			throw new IllegalArgumentException("Export destination must end in .examwork");
		}
		if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Export destination already exists: " + target);
		}
		Path parent = target.getParent();
		if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Export destination directory does not exist: " + parent);
		}
		Path physicalParent = parent.toRealPath();
		Path subjectsRoot = layout.subjectsRoot().toAbsolutePath().normalize();
		if (physicalParent.startsWith(subjectsRoot)) {
			throw new IOException("Export destination cannot be inside " + "the managed Subjects directory");
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
			for (int count; (count = input.read(buffer)) != -1;) {
				digest.update(buffer, 0, count);
			}
		}
		return HexFormat.of().formatHex(digest.digest());
	}
}
