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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.ApplicationVersion;
import au.edu.eq.questionbank.ManagedAssetPublicationLock;
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
	 * Stages the exact approved incoming refresh archive and its binary assets.
	 *
	 * <p>
	 * Embedded image identities are normalised into the approved comparison
	 * namespace. Existing managed files are not modified.
	 *
	 * <p>
	 * The caller must retain the publication lock and revalidate the approved
	 * comparison under the SQLite writer reservation before publication.
	 *
	 * @param approval        exact approved refresh comparison
	 * @param certificate     verified pre-refresh full backup
	 * @param incomingArchive originating Subject snapshot
	 * @param lock            held managed-asset publication lock
	 * @return verified private archive and staged asset inventory
	 * @throws IOException          if archive, backup or filesystem validation
	 *                              fails
	 * @throws NullPointerException if a required argument is null
	 */
	public StagedPackage stageRefresh(SubjectRefreshPlan.Approval approval, Certificate certificate,
			Path incomingArchive, ManagedAssetPublicationLock lock) throws IOException {
		Objects.requireNonNull(approval, "approval");
		Objects.requireNonNull(certificate, "certificate");
		Objects.requireNonNull(incomingArchive, "incomingArchive");
		Objects.requireNonNull(lock, "lock");
		lock.requireHeld(config.dataRoot());
		SubjectRefreshPlan plan = approval.plan();
		var expected = plan.evidence().incoming().fingerprint();
		UUID packageId = expected.packageId();
		SubjectImportBackupGate gate = new SubjectImportBackupGate(config);
		gate.requireIntact(packageId, certificate);
		Path source = incomingArchive.toAbsolutePath().normalize();
		if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || Files.size(source) > MAX_ARCHIVE_BYTES) {
			throw new IOException("Incoming refresh package must be an ordinary supported archive");
		}
		Path root = config.dataRoot();
		if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Refresh data root must be an ordinary directory");
		}
		Path stagingParent = root.resolve(".subject-import-staging");
		if (Files.exists(stagingParent, LinkOption.NOFOLLOW_LINKS)
				&& !Files.isDirectory(stagingParent, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Unsafe Subject refresh staging root");
		}
		Files.createDirectories(stagingParent);
		if (!Files.isDirectory(stagingParent, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Unsafe Subject refresh staging directory");
		}
		Path packageDirectory = stagingParent.resolve(packageId.toString());

		// An existing package directory must be resolved through recovery.
		Files.createDirectory(packageDirectory);
		try {
			Path archiveCopy = packageDirectory.resolve("incoming.examwork");
			Files.copy(source, archiveCopy, LinkOption.NOFOLLOW_LINKS);
			if (!Files.isRegularFile(archiveCopy, LinkOption.NOFOLLOW_LINKS)) {
				throw new IOException("Staged refresh archive is not an ordinary file");
			}
			try (FileChannel channel = FileChannel.open(archiveCopy, StandardOpenOption.WRITE)) {
				channel.force(true);
			}

			// Validate the private archive used by subsequent operations.
			approval.requireFreshIncoming(archiveCopy);
			ExamWorkManifest manifest = new ExamWorkArchiveValidator().validate(archiveCopy,
					ApplicationVersion.current(), SqliteDatabase.latestSchemaVersion());
			if (!manifest.packageId().equals(packageId) || !manifest.subjectName().equals(expected.subjectName())) {
				throw new IOException("Staged refresh differs from approved identity");
			}
			Path assetDirectory = Files.createDirectory(packageDirectory.resolve("assets"));
			List<StagedAsset> assets = extractRefreshAssets(plan, manifest, archiveCopy, assetDirectory);
			gate.requireIntact(packageId, certificate);
			return new StagedPackage(packageId, approval.approvalSha256(), packageDirectory, archiveCopy, assets);
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
	 * Selects approved physical managed-file changes, excluding embedded images.
	 *
	 * @param plan approved refresh comparison
	 * @return immutable changed physical asset inventory
	 * @throws IOException if an approved destination is unsafe
	 */
	List<SubjectRefreshChangeClassifier.AssetChange> managedRefreshChanges(SubjectRefreshPlan plan) throws IOException {
		Objects.requireNonNull(plan, "plan");
		String subjectName = plan.evidence().incoming().fingerprint().subjectName();
		List<SubjectRefreshChangeClassifier.AssetChange> managed = new ArrayList<>();
		for (var change : plan.assetChanges()) {
			Path destination = requireRefreshPath(change.managedPath(), subjectName);
			if (isEmbeddedRefreshPath(destination, subjectName)) {

				// A real file must not occupy the synthetic BLOB namespace.
				if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
					throw new IOException(
							"Physical file occupies reserved embedded-image path: " + change.managedPath());
				}
				continue;
			}
			if (change.kind() != SubjectRefreshChangeClassifier.ChangeKind.UNCHANGED) {
				managed.add(change);
			}
		}
		return List.copyOf(managed);
	}

	/**
	 * Restricts complete refresh staging to physical additions and replacements.
	 *
	 * @param staged   complete verified incoming asset staging
	 * @param approval exact approved refresh
	 * @return publication-only view of the private staging directory
	 * @throws IOException if staging differs from approval
	 */
	StagedPackage publicationStage(StagedPackage staged, SubjectRefreshPlan.Approval approval) throws IOException {
		Objects.requireNonNull(staged, "staged");
		Objects.requireNonNull(approval, "approval");
		SubjectRefreshPlan plan = approval.plan();
		if (!staged.packageId().equals(plan.evidence().incoming().fingerprint().packageId())
				|| !staged.approvalSha256().equals(approval.approvalSha256())) {
			throw new IOException("Refresh staging belongs to a different approval");
		}
		Map<String, SubjectRefreshChangeClassifier.AssetChange> expected = new HashMap<>();
		for (var change : managedRefreshChanges(plan)) {
			if (change.incoming() != null) {
				expected.put(change.managedPath(), change);
			}
		}
		List<StagedAsset> selected = new ArrayList<>();
		for (StagedAsset asset : staged.assets()) {
			if (asset.kind() != AssetKind.MANAGED_FILE) {
				continue;
			}
			var change = expected.remove(asset.managedPath());
			if (change == null) {
				continue;
			}
			if (!asset.sha256().equals(change.incoming().sha256()) || asset.size() != change.incoming().size()) {
				throw new IOException("Staged publication bytes differ from approval: " + asset.managedPath());
			}
			selected.add(asset);
		}
		if (!expected.isEmpty()) {
			throw new IOException("Approved physical refresh assets are missing from staging");
		}
		return new StagedPackage(staged.packageId(), staged.approvalSha256(), staged.directory(), staged.archive(),
				selected);
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
	 * Extracts approved incoming assets in the comparison identity namespace.
	 */
	private List<StagedAsset> extractRefreshAssets(SubjectRefreshPlan plan, ExamWorkManifest manifest, Path archiveCopy,
			Path assetDirectory) throws IOException {
		Map<String, SubjectRefreshChangeClassifier.AssetChange> expected = new HashMap<>();
		Set<Path> destinations = new HashSet<>();
		String subjectName = manifest.subjectName();

		// Validate additions, existing assets and removals before extraction.
		for (var change : plan.assetChanges()) {
			Path destination = requireRefreshPath(change.managedPath(), subjectName);
			if (!destinations.add(destination)) {
				throw new IOException("Ambiguous refresh destination: " + change.managedPath());
			}
			boolean embedded = isEmbeddedRefreshPath(destination, subjectName);
			if (embedded) {
				if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
					throw new IOException("Physical file occupies reserved embedded-image path");
				}
			} else if (change.current() == null) {
				if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
					throw new IOException(
							"Refresh addition would overwrite an existing managed file: " + change.managedPath());
				}
			} else if (!Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)) {
				throw new IOException("Approved original managed file is missing: " + change.managedPath());
			}
			if (change.incoming() != null) {
				expected.put(change.managedPath(), change);
			}
		}
		try (ZipFile archive = ZipFile.builder().setPath(archiveCopy).get()) {
			ZipArchiveEntry indexEntry = archive.getEntry(ExamWorkAssetIndex.INDEX_PATH);
			if (indexEntry == null) {
				throw new PackageFormatException("Subject refresh has no asset index");
			}
			ExamWorkAssetIndex original;
			try (InputStream input = archive.getInputStream(indexEntry)) {
				original = new ExamWorkJsonlCodec().readAssetIndex(input);
			}
			original.requireMatchingManifest(manifest);
			ExamWorkAssetIndex normalised = new SubjectRefreshIdentityNormaliser().normaliseAssets(original,
					plan.identities());
			Map<String, StagedAsset> staged = new LinkedHashMap<>();
			long stagedBytes = 0;
			for (Reference reference : normalised.references()) {
				var change = expected.get(reference.managedPath());
				if (change == null || !change.incoming().sha256().equals(reference.sha256())
						|| change.incoming().size() != reference.size()) {
					throw new IOException(
							"Incoming refresh asset differs from approved comparison: " + reference.managedPath());
				}
				AssetKind kind;
				try {
					kind = kindFor(reference);
				} catch (IllegalArgumentException failure) {
					throw new IOException("Unsupported refresh asset usage", failure);
				}
				Path destination = requireRefreshPath(reference.managedPath(), subjectName);
				boolean embedded = isEmbeddedRefreshPath(destination, subjectName);
				if (embedded != (kind == AssetKind.EMBEDDED_PNG)) {
					throw new IOException("Refresh asset usage contradicts its managed namespace");
				}
				StagedAsset previous = staged.get(reference.managedPath());
				if (previous != null) {
					if (!previous.sha256().equals(reference.sha256()) || previous.size() != reference.size()
							|| previous.kind() != kind) {
						throw new IOException("Conflicting incoming refresh asset references");
					}
					continue;
				}

				// Bound logical staging expansion as well as ZIP expansion.
				stagedBytes += reference.size();
				if (stagedBytes > 1024L * 1024 * 1024 || staged.size() >= 100_000) {
					throw new IOException("Refresh asset staging exceeds supported limits");
				}
				Path stagedPath = assetDirectory.resolve(String.format(Locale.ROOT, "%05d.bin", staged.size()));
				StagedAsset asset = extractOne(archive, reference, stagedPath, kind);
				staged.put(reference.managedPath(), asset);
			}
			if (!staged.keySet().equals(expected.keySet())) {
				throw new IOException("Incoming refresh archive does not cover approved asset inventory");
			}
			return List.copyOf(staged.values());
		}
	}

	/**
	 * Identifies reserved synthetic paths for embedded database images.
	 */
	private boolean isEmbeddedRefreshPath(Path destination, String subjectName) {
		Path embeddedRoot = layout.subjectDirectory(subjectName).resolve("exams").resolve("_examwork_embedded");
		return destination.startsWith(embeddedRoot);
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
	 * Resolves a refresh path within the selected Subject without traversing
	 * symbolic links or non-directory intermediate components.
	 */
	private Path requireRefreshPath(String relativePath, String subjectName) throws IOException {
		Path destination;
		Path subjectRoot;
		try {
			destination = layout.resolve(relativePath);
			subjectRoot = layout.subjectDirectory(subjectName);
		} catch (IllegalArgumentException failure) {
			throw new IOException("Invalid Subject refresh path", failure);
		}
		if (!destination.startsWith(subjectRoot) || destination.equals(subjectRoot)) {
			throw new IOException("Refresh asset escapes its Subject: " + relativePath);
		}
		Path current = config.dataRoot();
		for (Path component : config.dataRoot().relativize(destination)) {
			current = current.resolve(component);
			if (Files.isSymbolicLink(current)) {
				throw new IOException("Symbolic link in refresh asset path: " + current);
			}
			if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
				continue;
			}
			if (!current.equals(destination) && !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
				throw new IOException("Non-directory refresh path component: " + current);
			}
		}
		return destination;
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
