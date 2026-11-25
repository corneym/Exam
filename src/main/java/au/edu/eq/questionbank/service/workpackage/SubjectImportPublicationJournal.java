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
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.ManagedAssetPublicationLock;
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
 * a replace option. The package ledger identifies completed imports;
 * authoritative database references also protect files adopted by later
 * operations.
 *
 * <p>
 * Publication requires the shared data-root lock. Recovery acquires that lock
 * and a SQLite writer reservation; close or roll back failed transactions
 * first. It is deliberately fail-closed if staged evidence or published bytes
 * differ.
 */
public final class SubjectImportPublicationJournal {

	private static final String JOURNAL = "publication.properties";
	private static final String FORMAT = "subject-import-publication-v1";
	private static final String REFRESH_FORMAT = "subject-import-publication-v2";
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
	 * Prepares a recoverable publication intent for approved managed-file
	 * additions, replacements and removals.
	 *
	 * <p>
	 * The caller must hold the managed-asset publication lock and the SQLite writer
	 * reservation, have revalidated the approved refresh comparison, and have
	 * established that replacement and removal cannot affect unrelated
	 * authoritative records.
	 *
	 * <p>
	 * Original files are retained through private hard links before the publication
	 * journal becomes visible. Neither this method nor journal preparation modifies
	 * any managed destination.
	 *
	 * @param staged      verified private package and incoming asset staging
	 * @param certificate verified full-backup evidence
	 * @param changes     approved changed managed-file inventory, excluding
	 *                    embedded database images and unchanged assets
	 * @param lock        held managed-asset publication lock
	 * @throws IOException if the approved inventory, staging, original files,
	 *                     backup or publication intent cannot be verified
	 */
	public void prepareRefresh(StagedPackage staged, Certificate certificate,
			List<SubjectRefreshChangeClassifier.AssetChange> changes, ManagedAssetPublicationLock lock)
			throws IOException {
		Objects.requireNonNull(staged, "staged");
		Objects.requireNonNull(certificate, "certificate");
		Objects.requireNonNull(changes, "changes");
		Objects.requireNonNull(lock, "lock");
		lock.requireHeld(config.dataRoot());
		SubjectImportBackupGate gate = new SubjectImportBackupGate(config);
		gate.requireIntact(staged.packageId(), certificate);
		Path directory = expectedDirectory(staged.packageId());
		Path assetDirectory = directory.resolve("assets");
		if (!staged.directory().equals(directory) || !staged.archive().equals(directory.resolve("incoming.examwork"))
				|| !Files.isDirectory(directory.getParent(), LinkOption.NOFOLLOW_LINKS)
				|| !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
				|| !Files.isDirectory(assetDirectory, LinkOption.NOFOLLOW_LINKS)
				|| !Files.isRegularFile(staged.archive(), LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Unexpected or unsafe private Subject refresh staging");
		}
		if (Files.exists(directory.resolve(JOURNAL), LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Subject publication intent already exists");
		}
		var incoming = new SubjectImportSnapshotFingerprint().inspect(staged.archive());
		if (!incoming.packageId().equals(staged.packageId())) {
			throw new IOException("Refresh staging package identity differs from approval");
		}
		Map<String, StagedAsset> incomingFiles = new HashMap<>();
		for (StagedAsset asset : staged.assets()) {
			if (asset.kind() != AssetKind.MANAGED_FILE) {
				continue;
			}
			if (incomingFiles.putIfAbsent(asset.managedPath(), asset) != null) {
				throw new IOException("Duplicate staged managed refresh asset");
			}
		}
		List<SubjectRefreshChangeClassifier.AssetChange> ordered = changes.stream()
				.sorted(Comparator.comparing(SubjectRefreshChangeClassifier.AssetChange::managedPath)).toList();
		if (ordered.size() > 4096) {
			throw new IOException("Too many managed refresh publication operations");
		}
		Properties values = new Properties();
		values.setProperty("format", REFRESH_FORMAT);
		values.setProperty("packageId", staged.packageId().toString());
		values.setProperty("approvalSha256", staged.approvalSha256());
		values.setProperty("backupPath", certificate.backupPath().toString());
		values.setProperty("backupSha256", certificate.archiveSha256());
		values.setProperty("archiveSha256", incoming.archiveSha256());
		values.setProperty("subjectName", incoming.subjectName());
		values.setProperty("count", Integer.toString(ordered.size()));
		Set<Path> destinations = new HashSet<>();
		Set<Path> stagedFiles = new HashSet<>();
		for (int i = 0; i < ordered.size(); i++) {
			var change = ordered.get(i);
			String managedPath = change.managedPath();
			Path destination = requireRefreshDestination(managedPath, incoming.subjectName());
			if (!destinations.add(destination)) {
				throw new IOException("Duplicate managed refresh destination: " + managedPath);
			}
			switch (change.kind()) {
			case ADD, UPDATE, REMOVE -> {

				// Only physical changes belong in the publication journal.
			}
			default -> throw new IOException("Unsupported managed refresh operation: " + change.kind());
			}
			String prefix = "file." + i + ".";
			values.setProperty(prefix + "path", managedPath);
			if (change.incoming() != null) {
				StagedAsset asset = incomingFiles.remove(managedPath);
				if (asset == null || !asset.sha256().equals(change.incoming().sha256())
						|| asset.size() != change.incoming().size()) {
					throw new IOException("Staged refresh asset differs from approval: " + managedPath);
				}
				Path source = requireStagedAsset(directory, asset);
				if (!source.getFileName().toString().matches("[0-9]{5}\\.bin") || !stagedFiles.add(source)
						|| !sha256(source).equals(asset.sha256()) || Files.size(source) != asset.size()) {
					throw new IOException("Invalid or changed staged refresh asset: " + managedPath);
				}
				values.setProperty(prefix + "stage", source.getFileName().toString());
				values.setProperty(prefix + "sha256", asset.sha256());
				values.setProperty(prefix + "size", Long.toString(asset.size()));
			}
			if (change.current() == null) {
				if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
					throw new IOException("Refresh addition would overwrite an existing file: " + managedPath);
				}
				continue;
			}
			if (!Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)
					|| Files.size(destination) != change.current().size()
					|| !sha256(destination).equals(change.current().sha256())) {
				throw new IOException("Original managed asset changed after refresh approval: " + managedPath);
			}
			String originalFilename = String.format(Locale.ROOT, "original-%05d.bin", i);
			Path original = assetDirectory.resolve(originalFilename);

			// Preserve the original inode before any replacement or removal can occur.
			Files.createLink(original, destination);
			if (!Files.isSameFile(original, destination) || Files.size(original) != change.current().size()
					|| !sha256(original).equals(change.current().sha256())) {
				throw new IOException("Cannot establish original asset recovery evidence");
			}
			values.setProperty(prefix + "original", originalFilename);
			values.setProperty(prefix + "originalSha256", change.current().sha256());
			values.setProperty(prefix + "originalSize", Long.toString(change.current().size()));
		}
		if (!incomingFiles.isEmpty()) {
			throw new IOException("Unapproved managed files remain in refresh staging");
		}
		gate.requireIntact(staged.packageId(), certificate);
		writeRefreshIntent(directory, values);
	}

	/**
	 * Publishes all approved managed-file changes described by the durable journal.
	 *
	 * <p>
	 * Ordinary imports continue to publish only non-overwriting additions. Refresh
	 * journals may also authorise atomic replacements and removals for which
	 * verified original-file recovery evidence already exists.
	 *
	 * <p>
	 * The caller must hold the managed-asset publication lock and SQLite writer
	 * reservation and must have revalidated the approved operation, full backup and
	 * authoritative ownership of affected files.
	 *
	 * @param packageId approved incoming package UUID
	 * @throws IOException if the journal, staging or destination state is unsafe,
	 *                     or an authorised publication operation fails
	 */
	public void publishFiles(UUID packageId) throws IOException {
		Intent intent = read(packageId);

		// Validate the entire inventory before modifying any managed destination.
		for (FileIntent file : intent.files()) {
			FileState expected = file.originalFilename() == null ? FileState.ABSENT : FileState.ORIGINAL;
			if (inspectFileState(intent.directory(), file) != expected) {
				throw new IOException("Managed destination changed before publication: " + file.path());
			}
		}
		for (FileIntent file : intent.files()) {
			Path destination = requireDestination(file.path());
			FileState expected = file.originalFilename() == null ? FileState.ABSENT : FileState.ORIGINAL;
			if (inspectFileState(intent.directory(), file) != expected) {
				throw new IOException("Managed destination changed during publication: " + file.path());
			}
			if (file.stagedFilename() == null) {

				// The original inode remains available through its private link.
				Files.delete(destination);
			} else if (file.originalFilename() == null) {
				Path source = requireStagedFile(intent.directory(), file);
				createSafeParents(destination.getParent());

				// Addition remains atomic and cannot overwrite another file.
				Files.createLink(destination, source);
			} else {
				Path source = requireStagedFile(intent.directory(), file);

				// Replace atomically without introducing a missing-file interval.
				replaceWithLink(intent.directory(), file, source, destination, "publish");
			}
		}
	}

	/**
	 * Resolves a previously journalled Subject import or refresh publication.
	 *
	 * <p>
	 * The committed SQLite package outcome determines whether published changes
	 * must be retained or rolled back. Uncommitted replacements and removals are
	 * restored from verified original-file links. Independently adopted additions
	 * remain protected by authoritative database references.
	 *
	 * <p>
	 * Recovery acquires the common publication lock and a SQLite writer
	 * reservation. Unsafe or incomplete recovery evidence causes recovery to stop
	 * without discarding that evidence.
	 *
	 * @param packageId staged package UUID
	 * @return whether the ledger confirms a committed operation
	 * @throws IOException  if recovery evidence or managed-file state is unsafe
	 * @throws SQLException if the authoritative database cannot be inspected
	 */
	public boolean recover(UUID packageId) throws IOException, SQLException {
		try (var lock = ManagedAssetPublicationLock.acquire(config.dataRoot())) {
			return recover(packageId, lock);
		}
	}

	/**
	 * Verifies the final managed-file state required by the publication intent.
	 *
	 * <p>
	 * Added and replaced files must retain their verified incoming staging links.
	 * Removed files must be absent. Original recovery evidence must remain intact
	 * until the SQLite transaction outcome is established.
	 *
	 * <p>
	 * Call immediately before committing the SQLite package ledger. The caller must
	 * separately verify that removed files have no remaining authoritative
	 * references.
	 *
	 * @param packageId approved incoming package UUID
	 * @throws IOException if any published file, removal or recovery evidence
	 *                     differs from the approved publication intent
	 */
	public void verifyPublished(UUID packageId) throws IOException {
		Intent intent = read(packageId);
		for (FileIntent file : intent.files()) {
			FileState expected = file.stagedFilename() == null ? FileState.ABSENT : FileState.INCOMING;
			if (inspectFileState(intent.directory(), file) != expected) {
				throw new IOException("Published managed asset changed before commit: " + file.path());
			}
		}
	}

	boolean recover(UUID packageId, ManagedAssetPublicationLock lock) throws IOException, SQLException {
		try (var references = new ManagedAssetRecoveryReferences(config, lock)) {
			return recoverOwnedFiles(packageId, references);
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
	 * Removes private publication evidence after all managed destinations have
	 * reached their verified terminal state.
	 *
	 * @param directory resolved private package directory
	 * @throws IOException if staging contains unsafe entries or cleanup fails
	 */
	private void deleteTree(Path directory) throws IOException {
		List<Path> paths;
		try (Stream<Path> stream = Files.walk(directory)) {
			paths = stream.sorted(Comparator.reverseOrder()).toList();
		}
		for (Path path : paths) {
			if (Files.isSymbolicLink(path)) {
				throw new IOException("Symbolic link in staging tree: " + path);
			}
		}
		Path journal = directory.resolve(JOURNAL);

		// Managed destinations have already been resolved. Remove the journal before
		// its evidence so interrupted cleanup cannot leave an incomplete live intent.
		Files.deleteIfExists(journal);
		for (Path path : paths) {
			if (!path.equals(journal)) {
				Files.deleteIfExists(path);
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
	 * Establishes whether a managed destination is absent, still contains its
	 * original inode, or contains the verified incoming inode.
	 *
	 * @param directory private publication directory
	 * @param file      immutable publication operation
	 * @return verified destination state
	 * @throws IOException if any evidence is missing, altered or ambiguous
	 */
	private FileState inspectFileState(Path directory, FileIntent file) throws IOException {
		Path incoming = file.stagedFilename() == null ? null : requireStagedFile(directory, file);
		Path original = file.originalFilename() == null ? null : requireOriginalFile(directory, file);
		Path destination = requireDestination(file.path());
		if (!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
			return FileState.ABSENT;
		}
		if (!Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Unsafe managed destination: " + file.path());
		}

		// Matching content alone does not establish ownership of a destination.
		if (original != null && Files.isSameFile(original, destination)
				&& Files.size(destination) == file.originalSize()
				&& sha256(destination).equals(file.originalSha256())) {
			return FileState.ORIGINAL;
		}
		if (incoming != null && Files.isSameFile(incoming, destination) && Files.size(destination) == file.size()
				&& sha256(destination).equals(file.sha256())) {
			return FileState.INCOMING;
		}
		throw new IOException("Unrecognised managed asset state; manual recovery required: " + file.path());
	}

	/**
	 * Loads and validates either supported generation of the immutable publication
	 * journal.
	 *
	 * @param packageId expected incoming package identity
	 * @return validated private publication intent
	 * @throws IOException if journal identity, paths, format or file specifications
	 *                     are invalid
	 */
	private Intent read(UUID packageId) throws IOException {
		Path directory = expectedDirectory(packageId);
		Path assetDirectory = directory.resolve("assets");
		Path journal = directory.resolve(JOURNAL);
		if (!Files.isDirectory(directory.getParent(), LinkOption.NOFOLLOW_LINKS)
				|| !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
				|| !Files.isDirectory(assetDirectory, LinkOption.NOFOLLOW_LINKS)
				|| !Files.isRegularFile(journal, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Missing or unsafe import publication journal: " + journal);
		}
		Properties values = new Properties();
		try (InputStream input = Files.newInputStream(journal)) {
			values.load(input);
		}
		String format = values.getProperty("format");
		boolean refresh = REFRESH_FORMAT.equals(format);
		if ((!refresh && !FORMAT.equals(format)) || !packageId.toString().equals(values.getProperty("packageId"))) {
			throw new IOException("Incorrect import journal identity or format");
		}
		String approval = values.getProperty("approvalSha256", "");
		if (!approval.matches("[0-9a-f]{64}")) {
			throw new IOException("Invalid publication approval fingerprint");
		}
		String subjectName = values.getProperty("subjectName", "");
		if (refresh) {
			Path archive = directory.resolve("incoming.examwork");
			String archiveSha256 = values.getProperty("archiveSha256", "");
			if (subjectName.isBlank() || !archiveSha256.matches("[0-9a-f]{64}")
					|| !Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)
					|| !sha256(archive).equals(archiveSha256)) {
				throw new IOException("Invalid or changed refresh archive evidence");
			}
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
		Set<Path> destinations = new HashSet<>();
		Set<String> evidenceNames = new HashSet<>();
		for (int i = 0; i < count; i++) {
			String prefix = "file." + i + ".";
			String path = values.getProperty(prefix + "path", "");
			String staged = values.getProperty(prefix + "stage");
			String sha = values.getProperty(prefix + "sha256");
			String original = refresh ? values.getProperty(prefix + "original") : null;
			String originalSha = refresh ? values.getProperty(prefix + "originalSha256") : null;
			long size = -1;
			long originalSize = -1;
			if (staged != null) {
				try {
					size = Long.parseLong(values.getProperty(prefix + "size", "-1"));
				} catch (NumberFormatException exception) {
					throw new IOException("Invalid journal asset size", exception);
				}
				if (!staged.matches("[0-9]{5}\\.bin") || sha == null || !sha.matches("[0-9a-f]{64}") || size < 0
						|| size > 256L * 1024 * 1024 || !evidenceNames.add(staged)) {
					throw new IOException("Invalid journal incoming asset specification");
				}
			} else if (!refresh || sha != null || values.containsKey(prefix + "size")) {
				throw new IOException("Incomplete incoming asset specification");
			}
			if (original != null) {
				try {
					originalSize = Long.parseLong(values.getProperty(prefix + "originalSize", "-1"));
				} catch (NumberFormatException exception) {
					throw new IOException("Invalid original asset size", exception);
				}
				String expectedOriginal = String.format(Locale.ROOT, "original-%05d.bin", i);
				if (!original.equals(expectedOriginal) || originalSha == null || !originalSha.matches("[0-9a-f]{64}")
						|| originalSize < 0 || originalSize > 256L * 1024 * 1024 || !evidenceNames.add(original)) {
					throw new IOException("Invalid original asset specification");
				}
			} else if (refresh && (originalSha != null || values.containsKey(prefix + "originalSize"))) {
				throw new IOException("Incomplete original asset specification");
			}
			if (staged == null && original == null) {
				throw new IOException("Publication operation contains no file evidence");
			}
			Path destination = refresh ? requireRefreshDestination(path, subjectName) : requireDestination(path);
			if (!destinations.add(destination)) {
				throw new IOException("Duplicate managed target in publication journal");
			}
			files.add(new FileIntent(path, staged, sha, size, original, originalSha, originalSize));
		}
		return new Intent(directory, List.copyOf(files), approval, refresh);
	}

	/**
	 * Resolves all journalled managed files according to the terminal SQLite
	 * package outcome.
	 *
	 * @param packageId  incoming package identity
	 * @param references authoritative references protected by a writer reservation
	 * @return whether the package was committed
	 * @throws IOException  if publication or recovery evidence is unsafe
	 * @throws SQLException if package history cannot be inspected
	 */
	private boolean recoverOwnedFiles(UUID packageId, ManagedAssetRecoveryReferences references)
			throws IOException, SQLException {
		Intent intent = read(packageId);
		var packageEntry = ledger.find(packageId);
		if (packageEntry.isPresent()) {
			var fingerprint = new SubjectImportSnapshotFingerprint()
					.inspect(intent.directory().resolve("incoming.examwork"));
			if (!packageEntry.get().archiveSha256().equals(fingerprint.archiveSha256())) {
				throw new IOException("Ledger and staged incoming package disagree");
			}
			if (intent.refresh() && !packageEntry.get().approvalSha256().equals(intent.approvalSha256())) {
				throw new IOException("Ledger and refresh publication approval disagree");
			}
		}
		boolean applied = packageEntry.isPresent() && packageEntry.get().outcome() == PackageOutcome.APPLIED;
		List<FileState> states = new ArrayList<>();

		// Establish the complete recovery decision before modifying any destination.
		for (FileIntent file : intent.files()) {
			Path destination = requireDestination(file.path());
			FileState state = inspectFileState(intent.directory(), file);
			states.add(state);
			if (applied) {
				if (file.stagedFilename() == null) {
					if (state != FileState.ABSENT || references.contains(destination)) {
						throw new IOException("Committed managed-file removal is inconsistent: " + file.path());
					}
				} else if (state != FileState.INCOMING) {
					throw new IOException("Committed managed file is missing or changed: " + file.path());
				}
				continue;
			}
			if (file.originalFilename() == null) {

				// Preserve the existing protection for independently adopted files.
				if (state == FileState.ABSENT && references.contains(destination)) {
					throw new IOException("Authoritatively referenced managed file is missing: " + file.path());
				}
			} else if (file.stagedFilename() != null && state == FileState.ABSENT) {

				// Atomic replacement never deliberately removes the destination first.
				throw new IOException("Unexpected missing replacement destination: " + file.path());
			}
		}
		if (!applied) {
			for (int i = 0; i < intent.files().size(); i++) {
				FileIntent file = intent.files().get(i);
				FileState state = states.get(i);
				Path destination = requireDestination(file.path());
				if (inspectFileState(intent.directory(), file) != state) {
					throw new IOException("Managed publication changed during recovery: " + file.path());
				}
				if (file.originalFilename() == null) {
					if (state == FileState.INCOMING && !references.contains(destination)) {
						Files.delete(destination);
					}
					continue;
				}
				Path original = requireOriginalFile(intent.directory(), file);
				if (state == FileState.INCOMING) {

					// Roll back a replacement without exposing a missing-file interval.
					replaceWithLink(intent.directory(), file, original, destination, "restore");
				} else if (state == FileState.ABSENT) {

					// Roll back a removal without overwriting an independently created file.
					createSafeParents(destination.getParent());
					Files.createLink(destination, original);
				}
				if (inspectFileState(intent.directory(), file) != FileState.ORIGINAL) {
					throw new IOException("Original managed file was not restored: " + file.path());
				}
			}
		}
		deleteTree(intent.directory());
		return applied;
	}

	/**
	 * Atomically replaces a destination with a hard link to verified private
	 * publication evidence.
	 *
	 * <p>
	 * Publication and restoration use separate deterministic temporary link names.
	 * Recovery can resume safely if an earlier attempt stopped after creating its
	 * temporary link but before the atomic replacement.
	 *
	 * @param directory   private publication directory
	 * @param file        immutable publication operation
	 * @param source      verified incoming or original file
	 * @param destination authorised managed destination
	 * @param phase       publication or restoration operation
	 * @throws IOException if the temporary link is unsafe or atomic replacement
	 *                     cannot be completed
	 */
	private void replaceWithLink(Path directory, FileIntent file, Path source, Path destination, String phase)
			throws IOException {
		if (!"publish".equals(phase) && !"restore".equals(phase)) {
			throw new IllegalArgumentException("Unknown replacement phase");
		}
		Path temporary = directory.resolve("assets").resolve(phase + "-" + file.originalFilename());
		if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
			if (!Files.isRegularFile(temporary, LinkOption.NOFOLLOW_LINKS) || !Files.isSameFile(temporary, source)) {
				throw new IOException("Unsafe temporary managed replacement link");
			}
		} else {
			Files.createLink(temporary, source);
		}

		// Never fall back to deleting the destination followed by an ordinary move.
		// Such a fallback would introduce a missing-file crash window.
		Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
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
	 * Requires the retained original asset recovery evidence to remain intact.
	 *
	 * @param directory private publication directory
	 * @param file      immutable publication operation
	 * @return verified original-file recovery link
	 * @throws IOException if the original evidence is missing or changed
	 */
	private Path requireOriginalFile(Path directory, FileIntent file) throws IOException {
		Path assetDirectory = directory.resolve("assets");
		Path original = assetDirectory.resolve(file.originalFilename()).normalize();
		if (!original.getParent().equals(assetDirectory) || !Files.isRegularFile(original, LinkOption.NOFOLLOW_LINKS)
				|| Files.size(original) != file.originalSize() || !sha256(original).equals(file.originalSha256())) {
			throw new IOException("Missing or altered original asset recovery evidence: " + file.path());
		}
		return original;
	}

	/**
	 * Restricts physical refresh publication to the originating Subject's managed
	 * directory and excludes synthetic embedded-image destinations.
	 *
	 * @param relativePath approved managed data-root-relative path
	 * @param subjectName  immutable originating Subject name
	 * @return validated managed destination
	 * @throws IOException if the destination is unsafe or outside the Subject
	 */
	private Path requireRefreshDestination(String relativePath, String subjectName) throws IOException {
		Path destination = requireDestination(relativePath);
		Path subjectRoot;
		try {
			subjectRoot = layout.subjectDirectory(subjectName);
		} catch (IllegalArgumentException exception) {
			throw new IOException("Invalid refresh Subject directory", exception);
		}
		Path embeddedRoot = subjectRoot.resolve("exams/_examwork_embedded");
		if (!destination.startsWith(subjectRoot) || destination.equals(subjectRoot)
				|| destination.startsWith(embeddedRoot)) {
			throw new IOException("Refresh publication target is outside its physical Subject assets: " + relativePath);
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
	 * Publishes the immutable refresh journal after forcing its contents to disk.
	 *
	 * @param directory private publication directory
	 * @param values    complete verified publication inventory
	 * @throws IOException if the journal cannot be written or atomically published
	 */
	private void writeRefreshIntent(Path directory, Properties values) throws IOException {
		Path target = directory.resolve(JOURNAL);
		Path temporary = directory.resolve(JOURNAL + ".tmp");
		if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Refresh publication intent already exists");
		}
		try {
			try (OutputStream output = Files.newOutputStream(temporary, StandardOpenOption.CREATE_NEW,
					StandardOpenOption.WRITE)) {
				values.store(output, "Subject refresh publication intent");
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
	 * One immutable managed-file publication operation.
	 *
	 * <p>
	 * A missing original specification represents an addition. A missing incoming
	 * specification represents a removal. Both specifications represent a
	 * replacement.
	 *
	 * @param path             managed destination
	 * @param stagedFilename   incoming staging filename, or null
	 * @param sha256           incoming content checksum, or null
	 * @param size             incoming content size, or -1
	 * @param originalFilename retained original filename, or null
	 * @param originalSha256   original content checksum, or null
	 * @param originalSize     original content size, or -1
	 */
	private record FileIntent(String path, String stagedFilename, String sha256, long size, String originalFilename,
			String originalSha256, long originalSize) {
	}

	/**
	 * Recognised managed destination states during publication and recovery.
	 */
	private enum FileState {
		/** No file exists at the managed destination. */
		ABSENT,
		/** The destination retains the verified original inode. */
		ORIGINAL,
		/** The destination contains the verified incoming inode. */
		INCOMING
	}

	/**
	 * Validated immutable journal state.
	 *
	 * @param directory      private publication directory
	 * @param files          complete managed-file publication inventory
	 * @param approvalSha256 approved operation fingerprint
	 * @param refresh        whether the intent uses the refresh journal format
	 */
	private record Intent(Path directory, List<FileIntent> files, String approvalSha256, boolean refresh) {
	}
}
