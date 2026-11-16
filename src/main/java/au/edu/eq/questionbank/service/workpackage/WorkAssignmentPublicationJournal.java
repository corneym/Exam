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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Stream;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.ApplicationVersion;
import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.workpackage.ExamWorkAssetIndex.Reference;
import au.edu.eq.questionbank.service.workpackage.ExamWorkManifest.EntryRole;
import au.edu.eq.questionbank.service.workpackage.WorkAssignmentAssetExtractor.Extraction;
import au.edu.eq.questionbank.service.workpackage.WorkAssignmentManagedAssetGate.Disposition;

/**
 * Durable publication journal for contributor assignment source files.
 *
 * <p>
 * A journal is written and flushed before managed files are created. Newly
 * published files are hard links to independently verified private staging
 * files, allowing recovery to distinguish installation-owned files from
 * existing identical files.
 *
 * <p>
 * The committed contributor assignment session determines whether a publication
 * succeeded. An absent session permits rollback only of files proven to be
 * links created by this publication. Existing files are never deleted.
 *
 * <p>
 * The caller must serialise assignment installation and recovery, complete or
 * roll back the SQLite transaction before calling recover, and prevent
 * concurrent application publication into the same data root.
 */
public final class WorkAssignmentPublicationJournal {

	private static final String FORMAT = "contributor-assignment-publication-v1";
	private static final String JOURNAL = "publication.properties";
	private final ApplicationConfig config;
	private final ManagedDataLayout layout;

	/**
	 * Creates a journal manager for one contributor installation.
	 *
	 * @param config contributor filesystem configuration
	 */
	public WorkAssignmentPublicationJournal(ApplicationConfig config) {
		this.config = Objects.requireNonNull(config, "config");
		layout = new ManagedDataLayout(config.dataRoot());
	}

	private static String hash(Path file) throws IOException {
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
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
	 * Persists immutable publication intent before any managed-file write.
	 *
	 * <p>
	 * Records whether each destination already existed with identical bytes. The
	 * extraction's physical assets and private archive are verified again before
	 * the journal becomes durable.
	 *
	 * @param extraction fully validated and extracted assignment
	 * @throws IOException if evidence, destinations or journal creation fail
	 */
	public void prepare(Extraction extraction) throws IOException {
		Objects.requireNonNull(extraction, "extraction");
		UUID packageId = extraction.staged().inspection().manifest().packageId();
		WorkAssignmentPackageInspector.Inspection inspection = inspectStage(packageId);
		if (!inspection.archiveSha256().equals(extraction.staged().inspection().archiveSha256())
				|| !inspection.manifest().equals(extraction.staged().inspection().manifest())) {
			throw new IOException("Assignment changed between staging and publication");
		}
		Map<String, String> physicalNames = physicalNames(inspection);
		if (extraction.assets().size() != physicalNames.size()) {
			throw new IOException("Incomplete physical assignment extraction");
		}
		for (var asset : extraction.assets()) {
			String expectedName = physicalNames.get(asset.archivePath());
			if (expectedName == null
					|| !asset.stagedPath().equals(stageDirectory(packageId).resolve("assets").resolve(expectedName))) {
				throw new IOException("Assignment extraction differs from archive inventory");
			}
			extraction.requireVerifiedAsset(asset.archivePath());
		}
		List<FileIntent> expected = fileSpecs(inspection);
		var decisions = new WorkAssignmentManagedAssetGate(config).inspect(inspection.manifest().subjectName(),
				inspection.assets());
		Map<String, Disposition> dispositions = new HashMap<>();
		for (var decision : decisions) {
			if (decision.disposition() == Disposition.EMBEDDED) {
				continue;
			}
			Disposition previous = dispositions.putIfAbsent(decision.reference().managedPath(), decision.disposition());
			if (previous != null && previous != decision.disposition()) {
				throw new IOException("Conflicting shared assignment asset destination");
			}
		}
		List<FileIntent> files = new ArrayList<>();
		for (FileIntent item : expected) {
			Disposition disposition = dispositions.get(item.managedPath());
			if (disposition == null) {
				throw new IOException("Assignment publication has an unclassified file");
			}

			// Verify the bytes from which any hard link will be made.
			requireStagedFile(packageId, item);
			files.add(new FileIntent(item.managedPath(), item.archivePath(), item.stagedName(), item.sha256(),
					item.size(), disposition == Disposition.REUSE));
		}
		Properties properties = new Properties();
		properties.setProperty("format", FORMAT);
		properties.setProperty("packageId", packageId.toString());
		properties.setProperty("assignmentId", inspection.manifest().assignmentId().toString());
		properties.setProperty("archiveSha256", inspection.archiveSha256());
		properties.setProperty("count", Integer.toString(files.size()));
		for (int index = 0; index < files.size(); index++) {
			FileIntent file = files.get(index);
			String prefix = "file." + index + ".";
			properties.setProperty(prefix + "managedPath", file.managedPath());
			properties.setProperty(prefix + "archivePath", file.archivePath());
			properties.setProperty(prefix + "stagedName", file.stagedName());
			properties.setProperty(prefix + "sha256", file.sha256());
			properties.setProperty(prefix + "size", Long.toString(file.size()));
			properties.setProperty(prefix + "reused", Boolean.toString(file.reused()));
		}
		Path directory = stageDirectory(packageId);
		Path target = directory.resolve(JOURNAL);
		Path temporary = directory.resolve(JOURNAL + ".tmp");
		if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) || Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Assignment publication evidence already exists");
		}
		try {
			try (OutputStream output = Files.newOutputStream(temporary, StandardOpenOption.CREATE_NEW,
					StandardOpenOption.WRITE)) {
				properties.store(output, "Contributor assignment publication");
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
	 * Publishes new source files without replacing existing files.
	 *
	 * <p>
	 * Existing files recorded as reusable are reverified, not replaced. New files
	 * are created as hard links to staged content. A failed publication must be
	 * followed by recovery after the installation's SQLite transaction has rolled
	 * back.
	 *
	 * @param packageId validated assignment package identity
	 * @throws IOException if journal, staged files or destinations are unsafe
	 */
	public void publishFiles(UUID packageId) throws IOException {
		Intent intent = read(packageId);
		for (FileIntent file : intent.files()) {
			Path source = requireStagedFile(packageId, file);
			Path destination = requireDestination(file.managedPath());
			if (file.reused()) {
				requireMatchingBytes(destination, file);
				continue;
			}
			createSafeParents(destination.getParent());
			if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
				throw new IOException("Assignment cannot replace existing file: " + file.managedPath());
			}

			// Hard-link creation is atomic and fails if the target
			// already exists. The staged source remains available
			// as proof of ownership during recovery.
			Files.createLink(destination, source);
		}
	}

	/**
	 * Resolves a journal after the installation transaction has finished.
	 *
	 * <p>
	 * A committed matching assignment session preserves all published files.
	 * Without a committed session, only newly created hard links may be removed.
	 * Reused files remain untouched in both cases.
	 *
	 * <p>
	 * Any changed, replaced or unexplained destination causes recovery to fail
	 * closed, preserving evidence for manual investigation.
	 *
	 * @param packageId assignment package to resolve
	 * @return true if the assignment session was committed
	 * @throws IOException  if publication evidence or files are unsafe
	 * @throws SQLException if committed-session lookup fails
	 */
	public boolean recover(UUID packageId) throws IOException, SQLException {
		Intent intent = read(packageId);
		var session = new SqliteContributorAssignmentSessionRepository(new SqliteDatabase(config.databasePath()))
				.find(intent.assignmentId());
		boolean committed = session.isPresent();
		if (committed && (!session.orElseThrow().packageId().equals(packageId)
				|| !session.orElseThrow().archiveSha256().equals(intent.archiveSha256()))) {
			throw new IOException("Committed assignment and publication evidence disagree");
		}
		List<Path> created = new ArrayList<>();

		// Validate every destination before deleting even one.
		for (FileIntent file : intent.files()) {
			Path source = requireStagedFile(packageId, file);
			Path destination = requireDestination(file.managedPath());
			if (file.reused()) {
				requireMatchingBytes(destination, file);
				continue;
			}
			if (!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
				if (committed) {
					throw new IOException("Committed assignment source file is missing: " + file.managedPath());
				}
				continue;
			}
			requireMatchingBytes(destination, file);
			if (!Files.isSameFile(source, destination)) {
				throw new IOException("Assignment publication file is no longer " + "owned by its staging evidence: "
						+ file.managedPath());
			}
			if (!committed) {
				created.add(destination);
			}
		}
		if (!committed) {
			for (Path destination : created) {
				FileIntent matching = intent.files().stream()
						.filter(file -> requireDestinationUnchecked(file.managedPath()).equals(destination)).findFirst()
						.orElseThrow();
				Path source = requireStagedFile(packageId, matching);
				if (!Files.isSameFile(source, destination)) {
					throw new IOException("Assignment file changed during rollback");
				}
				Files.delete(destination);
			}
		}
		deleteStage(stageDirectory(packageId));
		return committed;
	}

	/**
	 * Verifies published files immediately before the SQLite session commits.
	 *
	 * <p>
	 * Newly created destinations must still be the same physical files as their
	 * staged sources. Reused destinations must retain the issued bytes.
	 *
	 * @param packageId assignment package identity
	 * @throws IOException if any publication or staged file changed
	 */
	public void verifyPublished(UUID packageId) throws IOException {
		Intent intent = read(packageId);
		for (FileIntent file : intent.files()) {
			Path source = requireStagedFile(packageId, file);
			Path destination = requireDestination(file.managedPath());
			requireMatchingBytes(destination, file);
			if (!file.reused() && !Files.isSameFile(source, destination)) {
				throw new IOException("Assignment publication lost its staged file link");
			}
		}
	}

	private void createSafeParents(Path parent) throws IOException {
		Path root = layout.dataRoot();
		if (!parent.startsWith(root)) {
			throw new IOException("Assignment destination is outside data root");
		}
		Path current = root;
		for (Path part : root.relativize(parent)) {
			current = current.resolve(part);
			if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
				Files.createDirectory(current);
			}
			if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
				throw new IOException("Unsafe assignment destination directory: " + current);
			}
		}
	}

	private void deleteStage(Path directory) throws IOException {
		try (Stream<Path> paths = Files.walk(directory)) {
			for (Path item : paths.sorted(Comparator.reverseOrder()).toList()) {
				if (Files.isSymbolicLink(item)) {
					throw new IOException("Symbolic link in assignment staging evidence");
				}
				Files.delete(item);
			}
		}
	}

	private List<FileIntent> fileSpecs(WorkAssignmentPackageInspector.Inspection inspection) throws IOException {
		Map<String, String> names = physicalNames(inspection);
		Map<String, FileIntent> byPath = new LinkedHashMap<>();
		for (Reference reference : inspection.assets().references()) {
			if (!reference.usage().equals("SOURCE_PDF") && !reference.usage().equals("SYLLABUS_PDF")) {
				continue;
			}
			String stagedName = names.get(reference.archivePath());
			if (stagedName == null) {
				throw new IOException("Assignment PDF has no physical archive asset");
			}
			FileIntent candidate = new FileIntent(reference.managedPath(), reference.archivePath(), stagedName,
					reference.sha256(), reference.size(), false);
			FileIntent previous = byPath.putIfAbsent(candidate.managedPath(), candidate);
			if (previous != null && (!previous.archivePath().equals(candidate.archivePath())
					|| !previous.sha256().equals(candidate.sha256()))) {
				throw new IOException("Conflicting assignment source-file identities");
			}
		}
		return byPath.values().stream().sorted(Comparator.comparing(FileIntent::managedPath)).toList();
	}

	private WorkAssignmentPackageInspector.Inspection inspectStage(UUID packageId) throws IOException {
		Path directory = stageDirectory(packageId);
		Path archive = directory.resolve("incoming.examwork");
		if (!Files.isDirectory(layout.dataRoot(), LinkOption.NOFOLLOW_LINKS)
				|| !Files.isDirectory(directory.getParent(), LinkOption.NOFOLLOW_LINKS)
				|| !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
				|| !Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Missing or unsafe private assignment stage");
		}
		var inspected = new WorkAssignmentPackageInspector().inspect(archive, ApplicationVersion.current(),
				SqliteDatabase.latestSchemaVersion());
		if (!packageId.equals(inspected.manifest().packageId())) {
			throw new IOException("Staged assignment package identity changed");
		}
		return inspected;
	}

	private long parseSize(String value) throws IOException {
		try {
			long size = Long.parseLong(value);
			if (size < 0 || size > 256L * 1024 * 1024) {
				throw new IOException("Invalid assignment journal asset size");
			}
			return size;
		} catch (NumberFormatException | NullPointerException exception) {
			throw new IOException("Invalid assignment journal asset size", exception);
		}
	}

	private Map<String, String> physicalNames(WorkAssignmentPackageInspector.Inspection inspection) {
		Map<String, String> names = new HashMap<>();
		int index = 0;

		// The extractor walks canonical manifest order.
		for (var entry : inspection.manifest().entries()) {
			if (entry.role() == EntryRole.ASSET) {
				names.put(entry.path(), "asset-%05d.bin".formatted(index++));
			}
		}
		return Map.copyOf(names);
	}

	private Intent read(UUID packageId) throws IOException {
		WorkAssignmentPackageInspector.Inspection inspection = inspectStage(packageId);
		Path journal = stageDirectory(packageId).resolve(JOURNAL);
		if (!Files.isRegularFile(journal, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Missing assignment publication journal");
		}
		Properties properties = new Properties();
		try (InputStream input = Files.newInputStream(journal)) {
			properties.load(input);
		}
		if (!FORMAT.equals(properties.getProperty("format"))
				|| !packageId.toString().equals(properties.getProperty("packageId"))
				|| !inspection.manifest().assignmentId().toString().equals(properties.getProperty("assignmentId"))
				|| !inspection.archiveSha256().equals(properties.getProperty("archiveSha256"))) {
			throw new IOException("Assignment publication journal identity mismatch");
		}
		List<FileIntent> expected = fileSpecs(inspection);
		int count;
		try {
			count = Integer.parseInt(properties.getProperty("count", "-1"));
		} catch (NumberFormatException exception) {
			throw new IOException("Invalid assignment publication entry count", exception);
		}
		if (count != expected.size()) {
			throw new IOException("Assignment publication inventory differs from archive");
		}
		List<FileIntent> files = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			String prefix = "file." + i + ".";
			FileIntent expectedFile = expected.get(i);
			String existing = properties.getProperty(prefix + "reused");
			if (!"true".equals(existing) && !"false".equals(existing)) {
				throw new IOException("Invalid assignment reuse evidence");
			}
			FileIntent file = new FileIntent(properties.getProperty(prefix + "managedPath"),
					properties.getProperty(prefix + "archivePath"), properties.getProperty(prefix + "stagedName"),
					properties.getProperty(prefix + "sha256"), parseSize(properties.getProperty(prefix + "size")),
					Boolean.parseBoolean(existing));
			if (!file.managedPath().equals(expectedFile.managedPath())
					|| !file.archivePath().equals(expectedFile.archivePath())
					|| !file.stagedName().equals(expectedFile.stagedName())
					|| !file.sha256().equals(expectedFile.sha256()) || file.size() != expectedFile.size()) {
				throw new IOException("Assignment journal differs from validated asset index");
			}
			files.add(file);
		}
		return new Intent(inspection.manifest().assignmentId(), inspection.archiveSha256(), List.copyOf(files));
	}

	private Path requireDestination(String relativePath) throws IOException {
		Path destination;
		try {
			destination = layout.resolve(relativePath);
		} catch (IllegalArgumentException exception) {
			throw new IOException("Unsafe assignment managed-file destination", exception);
		}
		if (!destination.startsWith(layout.subjectsRoot())) {
			throw new IOException("Assignment destination is outside Subjects");
		}
		Path current = layout.dataRoot();
		for (Path part : layout.dataRoot().relativize(destination)) {
			current = current.resolve(part);
			if (Files.isSymbolicLink(current)
					|| (!current.equals(destination) && Files.exists(current, LinkOption.NOFOLLOW_LINKS)
							&& !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS))) {
				throw new IOException("Unsafe assignment destination component: " + current);
			}
		}
		return destination;
	}

	private Path requireDestinationUnchecked(String relativePath) {
		return layout.resolve(relativePath);
	}

	private void requireMatchingBytes(Path destination, FileIntent file) throws IOException {
		if (!Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS) || Files.size(destination) != file.size()
				|| !hash(destination).equals(file.sha256())) {
			throw new IOException("Assignment managed file has changed: " + file.managedPath());
		}
	}

	private Path requireStagedFile(UUID packageId, FileIntent file) throws IOException {
		Path directory = stageDirectory(packageId).resolve("assets");
		Path source = directory.resolve(file.stagedName());
		if (!file.stagedName().matches("asset-[0-9]{5}\\.bin") || !source.getParent().equals(directory)
				|| !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || Files.size(source) != file.size()
				|| !hash(source).equals(file.sha256())) {
			throw new IOException("Assignment staging file is missing or changed");
		}
		return source;
	}

	private Path stageDirectory(UUID packageId) {
		Objects.requireNonNull(packageId, "packageId");
		return layout.dataRoot().resolve(".assignment-install-staging").resolve(packageId.toString());
	}

	private record FileIntent(String managedPath, String archivePath, String stagedName, String sha256, long size,
			boolean reused) {
	}

	private record Intent(UUID assignmentId, String archiveSha256, List<FileIntent> files) {
	}
}
