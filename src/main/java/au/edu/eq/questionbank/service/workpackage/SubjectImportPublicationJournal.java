package au.edu.eq.questionbank.service.workpackage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Stream;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.repository.sqlite.SqliteSubjectImportLedger;
import au.edu.eq.questionbank.repository.sqlite.SqliteSubjectImportLedger.PackageOutcome;
import au.edu.eq.questionbank.service.workpackage.SubjectImportAssetStager.AssetKind;
import au.edu.eq.questionbank.service.workpackage.SubjectImportAssetStager.StagedAsset;
import au.edu.eq.questionbank.service.workpackage.SubjectImportAssetStager.StagedPackage;
import au.edu.eq.questionbank.service.workpackage.SubjectImportBackupGate.Certificate;

/**
 * Crash-recovery journal for non-overwriting Subject asset publication.
 *
 * <p>
 * The journal is made durable before any managed destination is created. Files
 * are published as hard links to verified staged files, atomically and without
 * a replace option. A committed package ledger is the sole indicator that a
 * published file belongs to a successful import.
 *
 * <p>
 * All operations require the caller to hold the exclusive import lock and close
 * or roll back any failed SQLite transaction before recovery. Recovery is
 * deliberately fail-closed if staged evidence or published bytes differ.
 */
public final class SubjectImportPublicationJournal {

	private static final String JOURNAL = "publication.properties";
	private static final String FORMAT = "subject-import-publication-v1";
	private final ApplicationConfig config;
	private final ManagedDataLayout layout;
	private final SqliteSubjectImportLedger ledger;

	/**
	 * Creates a journal manager for one authoritative application data root.
	 *
	 * @param config master application configuration
	 */
	public SubjectImportPublicationJournal(ApplicationConfig config) {
		this.config = Objects.requireNonNull(config, "config");
		layout = new ManagedDataLayout(config.dataRoot());
		ledger = new SqliteSubjectImportLedger(new SqliteDatabase(config.databasePath()));
	}

	/**
	 * Writes the immutable publication intent before any file publication.
	 *
	 * @param staged      verified private package staging result
	 * @param certificate verified full-backup evidence
	 * @throws IOException if the intent cannot be durably written
	 */
	public void prepare(StagedPackage staged, Certificate certificate) throws IOException {
		Objects.requireNonNull(staged, "staged");
		Objects.requireNonNull(certificate, "certificate");
		new SubjectImportBackupGate(config).requireIntact(staged.packageId(), certificate);
		Path directory = expectedDirectory(staged.packageId());
		if (!staged.directory().equals(directory) || !staged.archive().equals(directory.resolve("incoming.examwork"))
				|| !Files.isRegularFile(staged.archive(), LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Unexpected or missing private Subject staging directory");
		}
		Path target = directory.resolve(JOURNAL);
		if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Publication intent already exists: " + target);
		}
		Properties values = new Properties();
		values.setProperty("format", FORMAT);
		values.setProperty("packageId", staged.packageId().toString());
		values.setProperty("approvalSha256", staged.approvalSha256());
		values.setProperty("backupPath", certificate.backupPath().toString());
		values.setProperty("backupSha256", certificate.archiveSha256());
		List<StagedAsset> files = staged.assets().stream().filter(asset -> asset.kind() == AssetKind.MANAGED_FILE)
				.sorted(Comparator.comparing(StagedAsset::managedPath)).toList();
		values.setProperty("count", Integer.toString(files.size()));
		for (int i = 0; i < files.size(); i++) {
			StagedAsset asset = files.get(i);
			Path stage = requireStagedAsset(directory, asset);
			if (!sha256(stage).equals(asset.sha256()) || Files.size(stage) != asset.size()) {
				throw new IOException("Staged asset content changed before journal preparation");
			}
			requireDestination(asset.managedPath());
			String prefix = "file." + i + ".";
			values.setProperty(prefix + "path", asset.managedPath());
			values.setProperty(prefix + "stage", stage.getFileName().toString());
			values.setProperty(prefix + "sha256", asset.sha256());
			values.setProperty(prefix + "size", Long.toString(asset.size()));
		}
		Path temporary = directory.resolve(JOURNAL + ".tmp");
		try {
			try (OutputStream out = Files.newOutputStream(temporary, StandardOpenOption.CREATE_NEW,
					StandardOpenOption.WRITE)) {
				values.store(out, "Subject import publication intent");
			}
			try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
				channel.force(true);
			}
			Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	/**
	 * Publishes each approved managed file without replacing any existing file.
	 *
	 * <p>
	 * The caller must have established the exclusive import lock and SQLite
	 * transaction, and revalidated Subject state and full backup.
	 *
	 * @param packageId approved incoming package UUID
	 * @throws IOException if the intent, staging, or destination is unsafe
	 */
	public void publishFiles(UUID packageId) throws IOException {
		Intent intent = read(packageId);
		for (FileIntent file : intent.files()) {
			Path source = requireStagedFile(intent.directory(), file);
			Path destination = requireDestination(file.path());
			if (!sha256(source).equals(file.sha256()) || Files.size(source) != file.size()) {
				throw new IOException("Staged file changed after approval: " + file.path());
			}
			Path parent = destination.getParent();
			createSafeParents(parent);
			if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
				throw new IOException("Import must not replace an existing managed file: " + file.path());
			}

			// Hard-link creation is atomic, cannot overwrite a destination, and
			// preserves the source for reliable same-file recovery evidence.
			Files.createLink(destination, source);
		}
	}

	/**
	 * Verifies or rolls back a previously journalled publication attempt.
	 *
	 * <p>
	 * If the SQLite ledger says APPLIED, published bytes must be intact and the
	 * staging tree is removed. Otherwise, each published file must be a hard link
	 * to the original verified staging file before it is removed. Existing or
	 * independently replaced files are never deleted.
	 *
	 * @param packageId staged package UUID
	 * @return whether the master ledger confirms a committed import
	 * @throws IOException  if evidence is incomplete or unsafe
	 * @throws SQLException if the import ledger cannot be read
	 */
	public boolean recover(UUID packageId) throws IOException, SQLException {
		Intent intent = read(packageId);
		var packageEntry = ledger.find(packageId);
		if (packageEntry.isPresent()
				&& !packageEntry.get().archiveSha256().equals(new SubjectImportSnapshotFingerprint()
						.inspect(intent.directory().resolve("incoming.examwork")).archiveSha256())) {
			throw new IOException("Ledger and staged incoming package disagree");
		}
		boolean applied = packageEntry.isPresent() && packageEntry.get().outcome() == PackageOutcome.APPLIED;
		for (FileIntent file : intent.files()) {
			Path source = requireStagedFile(intent.directory(), file);
			Path destination = requireDestination(file.path());
			if (!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
				if (applied) {
					throw new IOException("Committed import file is missing: " + file.path());
				}
				continue;
			}
			if (!Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS) || !Files.isSameFile(source, destination)
					|| !sha256(destination).equals(file.sha256())) {
				throw new IOException("Unsafe published asset state; manual recovery required: " + file.path());
			}
			if (!applied) {
				Files.delete(destination);
			}
		}
		deleteTree(intent.directory());
		return applied;
	}

	/**
	 * Verifies every managed file still has its approved bytes and staging link.
	 *
	 * <p>
	 * Call immediately before the SQLite package ledger commits. A changed file
	 * must block the transaction rather than create a permanent database reference
	 * to unverified bytes.
	 *
	 * @param packageId approved incoming package UUID
	 * @throws IOException if any authorised published file is missing or changed
	 */
	public void verifyPublished(UUID packageId) throws IOException {
		Intent intent = read(packageId);
		for (FileIntent file : intent.files()) {
			Path source = requireStagedFile(intent.directory(), file);
			Path destination = requireDestination(file.path());
			if (!Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS) || !Files.isSameFile(source, destination)
					|| Files.size(destination) != file.size() || !sha256(destination).equals(file.sha256())) {
				throw new IOException("Published import file changed before commit: " + file.path());
			}
		}
	}

	/**
	 * Creates a directory chain only after checking all existing ancestors.
	 */
	private void createSafeParents(Path parent) throws IOException {
		Path current = config.dataRoot();
		for (Path component : config.dataRoot().relativize(parent)) {
			current = current.resolve(component);
			if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
				Files.createDirectory(current);
			}
			if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
				throw new IOException("Unsafe destination directory: " + current);
			}
		}
	}

	/**
	 * Removes the private staging tree after all managed files are resolved.
	 */
	private void deleteTree(Path directory) throws IOException {
		try (Stream<Path> stream = Files.walk(directory)) {
			List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
			for (Path path : paths) {
				if (Files.isSymbolicLink(path)) {
					throw new IOException("Symbolic link in staging tree: " + path);
				}
				Files.delete(path);
			}
		}
	}

	/**
	 * Locates the only permitted directory for the package journal.
	 */
	private Path expectedDirectory(UUID packageId) {
		Objects.requireNonNull(packageId, "packageId");
		return config.dataRoot().resolve(".subject-import-staging").resolve(packageId.toString());
	}

	/**
	 * Loads and validates the immutable on-disk recovery intent.
	 */
	private Intent read(UUID packageId) throws IOException {
		Path directory = expectedDirectory(packageId);
		Path journal = directory.resolve(JOURNAL);
		if (!Files.isRegularFile(journal, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Missing import publication journal: " + journal);
		}
		Properties values = new Properties();
		try (InputStream input = Files.newInputStream(journal)) {
			values.load(input);
		}
		if (!FORMAT.equals(values.getProperty("format"))
				|| !packageId.toString().equals(values.getProperty("packageId"))) {
			throw new IOException("Incorrect import journal identity or format");
		}
		String approval = values.getProperty("approvalSha256", "");
		if (!approval.matches("[0-9a-f]{64}")) {
			throw new IOException("Invalid publication approval fingerprint");
		}
		int count;
		try {
			count = Integer.parseInt(values.getProperty("count", "-1"));
		} catch (NumberFormatException exception) {
			throw new IOException("Invalid journal entry count", exception);
		}
		if (count < 0 || count > 4096) {
			throw new IOException("Unsafe journal entry count");
		}
		List<FileIntent> files = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			String prefix = "file." + i + ".";
			String path = values.getProperty(prefix + "path", "");
			String staged = values.getProperty(prefix + "stage", "");
			String sha = values.getProperty(prefix + "sha256", "");
			long size;
			try {
				size = Long.parseLong(values.getProperty(prefix + "size", "-1"));
			} catch (NumberFormatException exception) {
				throw new IOException("Invalid journal asset size", exception);
			}
			if (!staged.matches("[0-9]{5}\\.bin") || !sha.matches("[0-9a-f]{64}") || size < 0
					|| size > 256L * 1024 * 1024) {
				throw new IOException("Invalid journal asset specification");
			}
			requireDestination(path);
			files.add(new FileIntent(path, staged, sha, size));
		}
		if (files.stream().map(FileIntent::path).distinct().count() != files.size()) {
			throw new IOException("Duplicate managed target in publication journal");
		}
		return new Intent(directory, List.copyOf(files));
	}

	/**
	 * Rejects linked ancestors and destinations outside the managed tree.
	 */
	private Path requireDestination(String relativePath) throws IOException {
		Path destination;
		try {
			destination = layout.resolve(relativePath);
		} catch (IllegalArgumentException exception) {
			throw new IOException("Invalid journal destination", exception);
		}
		if (!destination.startsWith(layout.subjectsRoot()) || destination.equals(layout.subjectsRoot())) {
			throw new IOException("Journal target is outside the Subject tree");
		}
		Path current = config.dataRoot();
		for (Path component : config.dataRoot().relativize(destination)) {
			current = current.resolve(component);
			if (Files.isSymbolicLink(current)) {
				throw new IOException("Symbolic link in managed destination: " + current);
			}
			if (!current.equals(destination) && Files.exists(current, LinkOption.NOFOLLOW_LINKS)
					&& !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
				throw new IOException("Non-directory in managed destination: " + current);
			}
		}
		return destination;
	}

	/**
	 * Validates a staging file from the original staging result.
	 */
	private Path requireStagedAsset(Path directory, StagedAsset asset) throws IOException {
		Path stage = asset.stagedPath().toAbsolutePath().normalize();
		if (!stage.getParent().equals(directory.resolve("assets"))
				|| !Files.isRegularFile(stage, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Approved asset is outside private staging");
		}
		return stage;
	}

	/**
	 * Ensures an intended staged file is an ordinary, untampered file.
	 */
	private Path requireStagedFile(Path directory, FileIntent file) throws IOException {
		Path source = directory.resolve("assets").resolve(file.stagedFilename()).normalize();
		if (!source.getParent().equals(directory.resolve("assets"))
				|| !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || Files.size(source) != file.size()
				|| !sha256(source).equals(file.sha256())) {
			throw new IOException("Missing or altered staged publication evidence");
		}
		return source;
	}

	/**
	 * Computes a complete SHA-256 checksum for a staged or published file.
	 */
	private String sha256(Path path) throws IOException {
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 unavailable", exception);
		}
		try (InputStream input = Files.newInputStream(path)) {
			byte[] buffer = new byte[8192];
			for (int count; (count = input.read(buffer)) != -1;) {
				digest.update(buffer, 0, count);
			}
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	/**
	 * One immutable item in the publication journal.
	 */
	private record FileIntent(String path, String stagedFilename, String sha256, long size) {
	}

	/**
	 * Private validated journal state.
	 */
	private record Intent(Path directory, List<FileIntent> files) {
	}
}
