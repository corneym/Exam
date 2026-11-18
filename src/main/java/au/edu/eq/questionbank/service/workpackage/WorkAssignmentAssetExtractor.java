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
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.ApplicationVersion;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.workpackage.ExamWorkManifest.Entry;
import au.edu.eq.questionbank.service.workpackage.ExamWorkManifest.EntryRole;
import au.edu.eq.questionbank.service.workpackage.WorkAssignmentArchiveStager.StagedAssignment;
import au.edu.eq.questionbank.service.workpackage.WorkAssignmentManagedAssetGate.Decision;

/**
 * Extracts all physical WORK_ASSIGNMENT assets into private staging.
 *
 * <p>
 * The staged archive is independently validated immediately before extraction.
 * All extracted bytes are checked against their declared length and SHA-256,
 * and written to private files without replacing any existing entry. PDFs must
 * also open successfully with the application's PDF reader before publication.
 *
 * <p>
 * Physical assets are extracted once even when multiple Booklets or other
 * logical references share the same source file.
 *
 * <p>
 * This service does not publish managed files, create database rows, or
 * activate contributor mode. The later installation service must coordinate
 * those operations using durable recovery evidence.
 */
public final class WorkAssignmentAssetExtractor {

	private final ApplicationConfig config;
	private final WorkAssignmentManagedAssetGate gate;

	/**
	 * Creates an asset extractor for one contributor data root.
	 *
	 * @param config contributor application's filesystem configuration
	 * @throws NullPointerException if config is null
	 */
	public WorkAssignmentAssetExtractor(ApplicationConfig config) {
		this.config = Objects.requireNonNull(config, "config");
		gate = new WorkAssignmentManagedAssetGate(config);
	}

	private static MessageDigest newDigest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static String sha256(Path file) throws IOException {
		MessageDigest digest = newDigest();
		try (InputStream input = Files.newInputStream(file)) {
			byte[] buffer = new byte[8192];
			int count;
			while ((count = input.read(buffer)) != -1) {
				digest.update(buffer, 0, count);
			}
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	/**
	 * Extracts every verified physical asset into private staging.
	 *
	 * <p>
	 * Managed destinations are checked before extraction, but are not modified. The
	 * publisher must check them again immediately before publication because
	 * external processes can change filesystem state.
	 *
	 * <p>
	 * If extraction fails, only this attempt's new asset directory is removed. The
	 * validated private archive remains available for inspection and retry.
	 *
	 * @param staged previously validated private assignment archive
	 * @return immutable extraction inventory and destination decisions
	 * @throws IOException          if the stage, archive, assets or destinations
	 *                              are invalid, or extraction fails
	 * @throws NullPointerException if staged is null
	 */
	public Extraction extract(StagedAssignment staged) throws IOException {
		Objects.requireNonNull(staged, "staged");
		requireStageLocation(staged);
		WorkAssignmentPackageInspector inspector = new WorkAssignmentPackageInspector();
		var verified = inspector.inspect(staged.archive(), ApplicationVersion.current(),
				SqliteDatabase.latestSchemaVersion());
		if (!verified.archiveSha256().equals(staged.inspection().archiveSha256())
				|| !verified.manifest().equals(staged.inspection().manifest())) {
			throw new IOException("Private assignment archive changed after staging");
		}
		List<Decision> destinations = gate.inspect(verified.manifest().subjectName(), verified.assets());
		Path assetDirectory = staged.directory().resolve("assets");

		// An existing directory may contain pending installation
		// evidence. Never merge or overwrite its contents.
		Files.createDirectory(assetDirectory);
		try {
			List<StagedAsset> assets = new ArrayList<>();
			try (ZipFile zip = ZipFile.builder().setPath(staged.archive()).get()) {
				int sequence = 0;
				for (Entry entry : verified.manifest().entries()) {
					if (entry.role() != EntryRole.ASSET) {
						continue;
					}
					Path target = assetDirectory.resolve("asset-%05d.bin".formatted(sequence++));
					assets.add(extractOne(zip, entry, target));
				}
			}
			return new Extraction(staged, assets, destinations);
		} catch (IOException | RuntimeException failure) {
			try {
				deleteAssetDirectory(assetDirectory);
			} catch (IOException cleanupFailure) {
				failure.addSuppressed(cleanupFailure);
			}
			throw failure;
		}
	}

	private void deleteAssetDirectory(Path directory) throws IOException {
		if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Unsafe assignment asset staging directory");
		}
		try (Stream<Path> entries = Files.walk(directory)) {
			for (Path path : entries.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(path);
			}
		}
	}

	private StagedAsset extractOne(ZipFile zip, Entry entry, Path target) throws IOException {
		ZipArchiveEntry member = zip.getEntry(entry.path());
		if (member == null) {
			throw new IOException("Missing assignment asset: " + entry.path());
		}
		MessageDigest digest = newDigest();
		long length = 0;
		try (InputStream input = zip.getInputStream(member);
				FileChannel output = FileChannel.open(target, StandardOpenOption.CREATE_NEW,
						StandardOpenOption.WRITE)) {
			byte[] buffer = new byte[8192];
			int count;
			while ((count = input.read(buffer)) != -1) {
				length += count;
				if (length > entry.size()) {
					throw new IOException("Assignment asset exceeds declared size: " + entry.path());
				}
				digest.update(buffer, 0, count);
				ByteBuffer bytes = ByteBuffer.wrap(buffer, 0, count);
				while (bytes.hasRemaining()) {
					output.write(bytes);
				}
			}
			output.force(true);
		}
		String actualHash = HexFormat.of().formatHex(digest.digest());
		if (length != entry.size() || !actualHash.equals(entry.sha256())) {
			throw new IOException("Extracted assignment asset failed integrity check: " + entry.path());
		}
		if (entry.path().endsWith(".pdf")) {
			WorkAssignmentPdfValidator.validate(target, entry.path());
		}
		return new StagedAsset(entry.path(), target, entry.sha256(), entry.size());
	}

	private void requireStageLocation(StagedAssignment staged) throws IOException {
		Path expectedDirectory = config.dataRoot().resolve(".assignment-install-staging")
				.resolve(staged.inspection().manifest().packageId().toString());
		if (!expectedDirectory.equals(staged.directory())
				|| !staged.archive().equals(expectedDirectory.resolve("incoming.examwork"))) {
			throw new IOException("Assignment stage belongs to another data root");
		}
		if (!Files.isDirectory(expectedDirectory, LinkOption.NOFOLLOW_LINKS)
				|| !Files.isRegularFile(staged.archive(), LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Assignment stage or private archive is unsafe");
		}
	}

	/**
	 * Complete private binary extraction evidence.
	 *
	 * @param staged       original validated private archive
	 * @param assets       immutable physical extracted asset inventory
	 * @param destinations immutable logical managed-destination decisions
	 */
	public record Extraction(StagedAssignment staged, List<StagedAsset> assets, List<Decision> destinations) {

		/**
		 * Defensively copies the asset and destination inventories.
		 *
		 * @throws NullPointerException if a required value is null
		 */
		public Extraction {
			Objects.requireNonNull(staged, "staged");
			assets = List.copyOf(assets);
			destinations = List.copyOf(destinations);
		}

		/**
		 * Returns a private asset after independently rechecking its bytes.
		 *
		 * <p>
		 * This operation must be called before a consumer reads an asset. A publication
		 * service must additionally verify the asset while publishing or immediately
		 * before creating a protected hard link.
		 *
		 * @param archivePath physical content-addressed archive member
		 * @return verified private asset file
		 * @throws IOException if the asset is absent, unsafe or changed
		 */
		public Path requireVerifiedAsset(String archivePath) throws IOException {
			Objects.requireNonNull(archivePath, "archivePath");
			StagedAsset found = null;
			for (StagedAsset asset : assets) {
				if (asset.archivePath().equals(archivePath)) {
					if (found != null) {
						throw new IOException("Duplicate extracted physical asset");
					}
					found = asset;
				}
			}
			if (found == null) {
				throw new IOException("Missing staged assignment asset: " + archivePath);
			}
			Path assetDirectory = staged.directory().resolve("assets");
			Path file = found.stagedPath();
			if (!file.startsWith(assetDirectory) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
					|| Files.size(file) != found.size() || !sha256(file).equals(found.sha256())) {
				throw new IOException("Staged assignment asset changed: " + archivePath);
			}
			return file;
		}
	}

	/**
	 * One independently verified physical archive asset.
	 *
	 * @param archivePath original content-addressed archive member
	 * @param stagedPath  private extracted file
	 * @param sha256      original content digest
	 * @param size        original byte length
	 */
	public record StagedAsset(String archivePath, Path stagedPath, String sha256, long size) {

		/**
		 * Validates and normalises the extraction metadata.
		 *
		 * @throws NullPointerException     if a required value is null
		 * @throws IllegalArgumentException if the digest or size is invalid
		 */
		public StagedAsset {
			Objects.requireNonNull(archivePath, "archivePath");
			Objects.requireNonNull(stagedPath, "stagedPath");
			stagedPath = stagedPath.toAbsolutePath().normalize();
			if (sha256 == null || !sha256.matches("[0-9a-f]{64}") || size < 0) {
				throw new IllegalArgumentException("Invalid staged assignment asset metadata");
			}
		}
	}
}
