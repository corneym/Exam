package au.edu.eq.questionbank.service.workpackage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.ApplicationVersion;
import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.workpackage.ExamWorkAssetIndex.Reference;
import au.edu.eq.questionbank.service.workpackage.SubjectImportApprovalPlan.Action;
import au.edu.eq.questionbank.service.workpackage.SubjectImportApprovalPlan.Operation;
import au.edu.eq.questionbank.service.workpackage.SubjectImportBackupGate.Certificate;
import au.edu.eq.questionbank.service.workpackage.SubjectReconciliationReview.Key;

/**
 * Stages verified Subject import assets without publishing changes.
 *
 * <p>
 * The incoming archive and approved asset bytes are held beneath a private
 * package-specific directory outside the managed Subjects hierarchy. Validation
 * uses the copied archive rather than reopening the external contributor file.
 *
 * <p>
 * Ordinary managed files and embedded PNG database content are deliberately
 * distinguished. Neither is published by this service.
 *
 * <p>
 * A later publisher must verify staged bytes again, establish a durable
 * publication journal and coordinate filesystem changes with the SQLite package
 * ledger.
 */
public final class SubjectImportAssetStager {

	private static final long MAX_ARCHIVE_BYTES = 512L * 1024 * 1024;
	private final ApplicationConfig config;
	private final ManagedDataLayout layout;

	/**
	 * Creates a Subject import stager for the authoritative data root.
	 *
	 * @param config application filesystem configuration
	 */
	public SubjectImportAssetStager(ApplicationConfig config) {
		this.config = Objects.requireNonNull(config, "config");
		this.layout = new ManagedDataLayout(config.dataRoot());
	}

	/**
	 * Classifies the destination of one portable binary reference.
	 *
	 * <p>
	 * Embedded Question and Shared Context images belong in SQLite BLOBs. Their
	 * synthetic paths inside a snapshot are not managed file targets.
	 *
	 * @param reference validated logical asset reference
	 * @return required publication destination kind
	 */
	static AssetKind kindFor(Reference reference) {
		return switch (reference.usage()) {
		case "EMBEDDED_QUESTION_PNG", "EMBEDDED_CONTEXT_PNG" -> {
			if (!"image/png".equals(reference.mediaType())) {
				throw new IllegalArgumentException("Embedded image must contain PNG bytes");
			}
			yield AssetKind.EMBEDDED_PNG;
		}
		case "SOURCE_PDF", "SYLLABUS_PDF", "INVENTORIED" -> AssetKind.MANAGED_FILE;
		default -> throw new IllegalArgumentException("Unsupported Subject import asset usage: " + reference.usage());
		};
	}

	/**
	 * Copies and verifies assets selected by an approved import plan.
	 *
	 * <p>
	 * The verified full backup must already exist. The package-specific staging
	 * directory must not already exist; an interrupted previous attempt must be
	 * recovered before another attempt begins.
	 *
	 * @param plan            approved non-destructive Subject import plan
	 * @param certificate     verified full-backup evidence
	 * @param incomingArchive contributor's Subject snapshot
	 * @return immutable description of the staged package and assets
	 * @throws IOException if source verification, staging or filesystem safety
	 *                     checks fail
	 */
	public StagedPackage stage(SubjectImportApprovalPlan plan, Certificate certificate, Path incomingArchive)
			throws IOException {
		Objects.requireNonNull(plan, "plan");
		Objects.requireNonNull(certificate, "certificate");
		Objects.requireNonNull(incomingArchive, "incomingArchive");
		UUID packageId = plan.reviewedIncoming().packageId();
		SubjectImportBackupGate backupGate = new SubjectImportBackupGate(config);
		backupGate.requireIntact(packageId, certificate);
		Path source = incomingArchive.toAbsolutePath().normalize();
		if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || Files.size(source) > MAX_ARCHIVE_BYTES) {
			throw new IOException(
					"Incoming Subject package must be an ordinary archive " + "within the supported size limit");
		}
		Path root = config.dataRoot();
		if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Import data root must be an ordinary directory");
		}
		Path stagingParent = root.resolve(".subject-import-staging");
		if (Files.exists(stagingParent, LinkOption.NOFOLLOW_LINKS)
				&& !Files.isDirectory(stagingParent, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Subject import staging root is not an ordinary directory");
		}
		Files.createDirectories(stagingParent);
		if (!Files.isDirectory(stagingParent, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Subject import staging root is unsafe");
		}
		Path packageDirectory = stagingParent.resolve(packageId.toString());

		// CREATE_DIRECTORY fails if a previous attempt left recovery
		// material behind. Never overwrite or silently discard it.
		Files.createDirectory(packageDirectory);
		try {
			Path archiveCopy = packageDirectory.resolve("incoming.examwork");
			Files.copy(source, archiveCopy, LinkOption.NOFOLLOW_LINKS);
			if (!Files.isRegularFile(archiveCopy, LinkOption.NOFOLLOW_LINKS)) {
				throw new IOException("Staged incoming archive is not an ordinary file");
			}

			// Validate the actual private bytes that later import stages
			// will consume, not a second reading of the source path.
			plan.requireFreshIncoming(archiveCopy);
			ExamWorkManifest manifest = new ExamWorkArchiveValidator().validate(archiveCopy,
					ApplicationVersion.current(), SqliteDatabase.latestSchemaVersion());
			if (!manifest.packageId().equals(packageId)) {
				throw new IOException("Staged package identity differs from approval");
			}
			Path assetDirectory = Files.createDirectory(packageDirectory.resolve("assets"));
			List<StagedAsset> staged = extractApprovedAssets(plan, manifest, archiveCopy, assetDirectory);

			// Backup verification is repeated after staging because a
			// publisher must not inherit stale recovery evidence.
			backupGate.requireIntact(packageId, certificate);
			return new StagedPackage(packageId, plan.approvalSha256(), packageDirectory, archiveCopy, staged);
		} catch (IOException | RuntimeException failure) {
			try {
				deleteStagingTree(packageDirectory);
			} catch (IOException cleanupFailure) {
				failure.addSuppressed(cleanupFailure);
			}
			throw failure;
		}
	}

	/**
	 * Deletes only a package staging tree created by this operation.
	 */
	private void deleteStagingTree(Path directory) throws IOException {
		if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		List<Path> paths;
		try (Stream<Path> stream = Files.walk(directory)) {
			paths = stream.sorted(Comparator.reverseOrder()).toList();
		}
		for (Path path : paths) {
			Files.deleteIfExists(path);
		}
	}

	/**
	 * Selects approved assets and verifies each physical ZIP member.
	 */
	private List<StagedAsset> extractApprovedAssets(SubjectImportApprovalPlan plan, ExamWorkManifest manifest,
			Path archiveCopy, Path assetDirectory) throws IOException {
		try (ZipFile archive = ZipFile.builder().setPath(archiveCopy).get()) {
			ZipArchiveEntry indexEntry = archive.getEntry(ExamWorkAssetIndex.INDEX_PATH);
			if (indexEntry == null) {
				throw new PackageFormatException("Subject package has no asset index");
			}
			ExamWorkAssetIndex index;
			try (InputStream input = archive.getInputStream(indexEntry)) {
				index = new ExamWorkJsonlCodec().readAssetIndex(input);
			}
			index.requireMatchingManifest(manifest);
			Map<Key, Reference> indexed = new HashMap<>();
			for (Reference reference : index.references()) {
				Key key = new Key("ASSET", reference.usage() + ":" + reference.managedPath() + ":"
						+ (reference.ownerType() == null ? "" : reference.ownerType() + ":" + reference.ownerId()));
				if (indexed.putIfAbsent(key, reference) != null) {
					throw new IOException("Duplicate incoming logical asset: " + key);
				}
			}
			Map<String, StagedAsset> stagedByDestination = new LinkedHashMap<>();
			for (Action action : plan.actions()) {
				if (action.operation() != Operation.COPY_ASSET) {
					continue;
				}
				if (!"ASSET".equals(action.key().kind())) {
					throw new IOException("Record action cannot copy a binary asset");
				}
				Reference reference = indexed.get(action.key());
				if (reference == null || !reference.managedPath().equals(action.incomingAssetPath())
						|| !reference.sha256().equals(action.incomingAssetSha256())) {
					throw new IOException("Approved asset differs from packaged evidence: " + action.key());
				}
				AssetKind kind = kindFor(reference);
				validateDestination(reference.managedPath(), plan.reviewedIncoming().subjectName(), kind);
				StagedAsset previous = stagedByDestination.get(reference.managedPath());
				if (previous != null) {
					if (!previous.sha256().equals(reference.sha256()) || previous.kind() != kind) {
						throw new IOException("Conflicting approved asset destination: " + reference.managedPath());
					}
					continue;
				}
				Path stagedPath = assetDirectory
						.resolve(String.format(Locale.ROOT, "%05d.bin", stagedByDestination.size()));
				StagedAsset staged = extractOne(archive, reference, stagedPath, kind);
				stagedByDestination.put(reference.managedPath(), staged);
			}
			return List.copyOf(stagedByDestination.values());
		}
	}

	/**
	 * Extracts exactly one packaged asset, checking size and SHA-256.
	 */
	private StagedAsset extractOne(ZipFile archive, Reference reference, Path stagedPath, AssetKind kind)
			throws IOException {
		ZipArchiveEntry entry = archive.getEntry(reference.archivePath());
		if (entry == null) {
			throw new PackageFormatException("Approved asset is absent from the archive: " + reference.archivePath());
		}
		MessageDigest digest = newDigest();
		long size = 0;
		try (InputStream input = archive.getInputStream(entry);
				FileChannel output = FileChannel.open(stagedPath, StandardOpenOption.CREATE_NEW,
						StandardOpenOption.WRITE)) {
			byte[] buffer = new byte[8192];
			int count;
			while ((count = input.read(buffer)) != -1) {
				size += count;
				if (size > reference.size()) {
					throw new IOException("Approved asset exceeds declared size");
				}
				digest.update(buffer, 0, count);
				ByteBuffer bytes = ByteBuffer.wrap(buffer, 0, count);
				while (bytes.hasRemaining()) {
					output.write(bytes);
				}
			}

			// Flush the extracted bytes before describing them as staged.
			output.force(true);
		}
		String actualSha256 = HexFormat.of().formatHex(digest.digest());
		if (size != reference.size() || !actualSha256.equals(reference.sha256())) {
			throw new IOException("Staged asset checksum or size mismatch: " + reference.managedPath());
		}
		return new StagedAsset(reference.managedPath(), reference.archivePath(), stagedPath, actualSha256, size, kind);
	}

	/**
	 * Creates a SHA-256 digest using the standard JDK provider.
	 */
	private MessageDigest newDigest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	/**
	 * Checks that an approved target stays within its Subject and that managed
	 * files would not overwrite existing data.
	 */
	private void validateDestination(String relativePath, String subjectName, AssetKind kind) throws IOException {
		Path destination = layout.resolve(relativePath);
		Path subjectRoot = layout.subjectDirectory(subjectName);
		if (!destination.startsWith(subjectRoot) || destination.equals(subjectRoot)) {
			throw new IOException("Approved asset escapes its Subject: " + relativePath);
		}
		if (kind == AssetKind.EMBEDDED_PNG) {
			Path embeddedRoot = subjectRoot.resolve("exams/_examwork_embedded");
			if (!destination.startsWith(embeddedRoot)) {
				throw new IOException("Embedded PNG has an unexpected logical path");
			}

			// No managed file is created for this logical reference.
			return;
		}
		Path current = config.dataRoot();

		// Reject linked ancestors and existing destinations rather than
		// trusting the path's lexical normalisation alone.
		for (Path component : config.dataRoot().relativize(destination)) {
			current = current.resolve(component);
			if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
				continue;
			}
			if (Files.isSymbolicLink(current) || current.equals(destination)
					|| !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
				throw new IOException(
						"Approved asset would replace or traverse " + "an existing managed entry: " + relativePath);
			}
		}
	}

	/**
	 * Distinguishes ordinary managed files from embedded database images.
	 */
	public enum AssetKind {
		/** Bytes intended for a verified new managed file. */
		MANAGED_FILE,
		/** PNG bytes intended for an imported SQLite image BLOB. */
		EMBEDDED_PNG
	}

	/**
	 * Immutable description of one verified staged binary asset.
	 *
	 * @param managedPath logical destination within the Subject
	 * @param archivePath original content-addressed ZIP member
	 * @param stagedPath  private extracted file path
	 * @param sha256      independently verified SHA-256 digest
	 * @param size        independently verified byte length
	 * @param kind        destination type required by the eventual importer
	 */
	public record StagedAsset(String managedPath, String archivePath, Path stagedPath, String sha256, long size,
			AssetKind kind) {

		/**
		 * Validates staged asset metadata.
		 */
		public StagedAsset {
			Objects.requireNonNull(managedPath, "managedPath");
			Objects.requireNonNull(archivePath, "archivePath");
			Objects.requireNonNull(stagedPath, "stagedPath");
			Objects.requireNonNull(kind, "kind");
			stagedPath = stagedPath.toAbsolutePath().normalize();
			if (sha256 == null || !sha256.matches("[0-9a-f]{64}") || size < 0) {
				throw new IllegalArgumentException("Invalid staged asset checksum or size");
			}
		}
	}

	/**
	 * Immutable description of one fully staged incoming package.
	 *
	 * @param packageId      approved incoming package UUID
	 * @param approvalSha256 approved plan checksum
	 * @param directory      package-specific private staging directory
	 * @param archive        verified private copy of the incoming ExamWork package
	 * @param assets         independently verified selected binary assets
	 */
	public record StagedPackage(UUID packageId, String approvalSha256, Path directory, Path archive,
			List<StagedAsset> assets) {

		/**
		 * Defensively copies staged package metadata.
		 */
		public StagedPackage {
			Objects.requireNonNull(packageId, "packageId");
			Objects.requireNonNull(directory, "directory");
			Objects.requireNonNull(archive, "archive");
			assets = List.copyOf(assets);
			if (approvalSha256 == null || !approvalSha256.matches("[0-9a-f]{64}")) {
				throw new IllegalArgumentException("Invalid approved import checksum");
			}
			directory = directory.toAbsolutePath().normalize();
			archive = archive.toAbsolutePath().normalize();
		}
	}
}
