package au.edu.eq.questionbank.service.workpackage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
import au.edu.eq.questionbank.repository.identity.SqliteSubjectGraphReader.SubjectGraph;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.workpackage.ExamWorkAssetIndex.Reference;

/**
 * Collects the binary dependencies of one Subject snapshot without modifying
 * its authoritative database or managed filesystem.
 *
 * <p>
 * SourceDocument PDFs, syllabus PDFs and retained managed files are read from
 * the Subject-first data layout. Embedded Question and Shared Context PNG
 * content is read from the consistent SQLite snapshot.
 *
 * <p>
 * Binary content is staged under a caller-owned temporary directory. Each
 * physical asset is addressed by its SHA-256 digest, and identical bytes share
 * one staged archive entry. Logical ownership and original managed paths are
 * preserved separately in the {@link ExamWorkAssetIndex}.
 *
 * <p>
 * Embedded images receive reserved package-only logical paths under the
 * Subject's {@code exams/_examwork_embedded/} namespace. These paths describe
 * portable content locations and must not be interpreted as existing managed
 * filesystem files when importing a package.
 *
 * <p>
 * Existing managed files are never renamed, rewritten or deleted. A missing,
 * inaccessible, unsafe or changed referenced file causes collection to fail.
 * The caller is responsible for deleting the temporary staging directory.
 *
 * @see ManagedDataLayout
 * @see ExamWorkAssetIndex
 * @see SubjectGraph
 */
public final class SubjectAssetCollector {

	private static final long MAX_BINARY_BYTES = 256L * 1024 * 1024;
	private final SqliteDatabase database;
	private final ManagedDataLayout layout;
	private final Path stagingDirectory;
	private final SqlitePortableIdentityRepository identities;
	private final List<Reference> references = new ArrayList<>();
	private final Map<String, Path> stagedFiles = new TreeMap<>();
	private final Map<String, String> archivePathsByDigest = new HashMap<>();
	private final Set<String> referencedManagedPaths = new HashSet<>();

	/**
	 * Creates a collector operating against a stable SQLite snapshot.
	 *
	 * @param snapshotDatabase read-only, immutable SQLite snapshot
	 * @param layout           authoritative managed-data layout
	 * @param stagingDirectory existing temporary directory owned by caller
	 * @throws NullPointerException if any argument is null
	 */
	public SubjectAssetCollector(SqliteDatabase snapshotDatabase, ManagedDataLayout layout, Path stagingDirectory) {
		database = Objects.requireNonNull(snapshotDatabase, "snapshotDatabase");
		this.layout = Objects.requireNonNull(layout, "layout");
		this.stagingDirectory = Objects.requireNonNull(stagingDirectory, "stagingDirectory");
		identities = new SqlitePortableIdentityRepository(database);
	}

	/**
	 * Collects all referenced and retained Subject assets into immutable staged
	 * copies suitable for the common archive writer.
	 *
	 * <p>
	 * The collector includes referenced source PDFs and syllabus PDFs, inventoried
	 * Subject-first files, Question image BLOBs and ordered Shared Context image
	 * BLOBs.
	 *
	 * @param graph selected Subject graph from the same SQLite snapshot
	 * @return asset index and staged binary archive sources
	 * @throws SQLException if database content or identity retrieval fails
	 * @throws IOException  if an asset is missing, unsafe, inconsistent, too large
	 *                      or cannot be staged
	 */
	public CollectedAssets collect(SubjectGraph graph) throws SQLException, IOException {
		Objects.requireNonNull(graph, "graph");
		if (!references.isEmpty() || !stagedFiles.isEmpty()) {
			throw new IllegalStateException("An asset collector can be used only once");
		}
		collectSourceDocuments(graph);
		collectSyllabusPdfs(graph);
		collectQuestionImages(graph);
		collectSharedContextImages(graph);
		inventoryManagedFiles(graph);
		ExamWorkAssetIndex index;
		try {
			index = new ExamWorkAssetIndex(references);
		} catch (IllegalArgumentException exception) {
			throw new PackageFormatException("Conflicting Subject asset references", exception);
		}
		return new CollectedAssets(index, stagedFiles);
	}

	private void collectManagedFile(SubjectGraph graph, String relativePath, String ownerType, UUID ownerId,
			String usage, String expectedHash) throws IOException {
		if (relativePath == null || relativePath.isBlank()) {
			throw new PackageFormatException("Missing managed path for " + ownerType + " " + ownerId);
		}
		Path subjectRoot = layout.subjectDirectory(graph.subjectName());
		Path path;
		try {
			path = layout.resolve(relativePath);
		} catch (IllegalArgumentException exception) {
			throw new PackageFormatException("Unsafe managed asset path: " + relativePath, exception);
		}
		String canonicalPath = layout.relativePath(path);
		if (!relativePath.equals(canonicalPath) || !path.startsWith(subjectRoot)) {
			throw new PackageFormatException("Asset is not in the selected Subject: " + relativePath);
		}
		requireSafeRegularFile(path, subjectRoot);
		long originalSize = Files.size(path);
		FileTime originalModified = Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS);
		String suffix = suffix(path);
		String mediaType = mediaType(suffix);
		try (InputStream input = Files.newInputStream(path)) {
			registerBinary(input, relativePath, ownerType, ownerId, usage, suffix, mediaType, expectedHash);
		}

		// Detect ordinary concurrent file replacement or modification
		// while its immutable package copy is being staged.
		if (Files.size(path) != originalSize
				|| !Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).equals(originalModified)) {
			throw new PackageFormatException("Managed asset changed during export: " + relativePath);
		}
		referencedManagedPaths.add(relativePath);
	}

	private void collectQuestionImages(SubjectGraph graph) throws SQLException, IOException {
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT image_png
						FROM question_images
						WHERE id = ?
						""")) {
			for (long localId : graph.ids(EntityType.QUESTION_IMAGE)) {
				statement.setLong(1, localId);
				try (ResultSet result = statement.executeQuery()) {
					if (!result.next()) {
						throw new SQLException("Missing QuestionImage " + localId);
					}
					UUID ownerId = identities.requireByLocalId(EntityType.QUESTION_IMAGE, localId).portableId();
					String logicalPath = embeddedPath(graph, "QUESTION_IMAGE", ownerId.toString() + ".png");
					try (InputStream input = result.getBinaryStream("image_png")) {
						registerBinary(input, logicalPath, "QUESTION_IMAGE", ownerId, "EMBEDDED_QUESTION_PNG", "png",
								"image/png", null);
					}
				}
			}
		}
	}

	private void collectSharedContextImages(SubjectGraph graph) throws SQLException, IOException {
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT image_order, image_png
						FROM shared_question_context_images
						WHERE shared_context_id = ?
						ORDER BY image_order
						""")) {
			for (long localId : graph.ids(EntityType.SHARED_QUESTION_CONTEXT)) {
				UUID ownerId = identities.requireByLocalId(EntityType.SHARED_QUESTION_CONTEXT, localId).portableId();
				statement.setLong(1, localId);
				try (ResultSet result = statement.executeQuery()) {
					while (result.next()) {
						int imageOrder = result.getInt("image_order");
						String logicalPath = embeddedPath(graph, "SHARED_QUESTION_CONTEXT",
								ownerId + "-" + imageOrder + ".png");
						try (InputStream input = result.getBinaryStream("image_png")) {
							registerBinary(input, logicalPath, "SHARED_QUESTION_CONTEXT", ownerId,
									"EMBEDDED_CONTEXT_PNG", "png", "image/png", null);
						}
					}
				}
			}
		}
	}

	private void collectSourceDocuments(SubjectGraph graph) throws SQLException, IOException {
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT relative_path, content_sha256
						FROM source_documents
						WHERE id = ?
						""")) {
			for (long localId : graph.ids(EntityType.SOURCE_DOCUMENT)) {
				statement.setLong(1, localId);
				try (ResultSet result = statement.executeQuery()) {
					if (!result.next()) {
						throw new SQLException("Missing SourceDocument " + localId);
					}
					String relativePath = result.getString("relative_path");
					String expectedHash = result.getString("content_sha256");
					UUID ownerId = identities.requireByLocalId(EntityType.SOURCE_DOCUMENT, localId).portableId();
					collectManagedFile(graph, relativePath, "SOURCE_DOCUMENT", ownerId, "SOURCE_PDF", expectedHash);
				}
			}
		}
	}

	private void collectSyllabusPdfs(SubjectGraph graph) throws SQLException, IOException {
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT source_pdf_path
						FROM syllabus_versions
						WHERE id = ?
						""")) {
			for (long localId : graph.ids(EntityType.SYLLABUS_VERSION)) {
				statement.setLong(1, localId);
				try (ResultSet result = statement.executeQuery()) {
					if (!result.next()) {
						throw new SQLException("Missing SyllabusVersion " + localId);
					}
					String relativePath = result.getString("source_pdf_path");
					if (relativePath == null) {
						continue;
					}
					UUID ownerId = identities.requireByLocalId(EntityType.SYLLABUS_VERSION, localId).portableId();
					collectManagedFile(graph, relativePath, "SYLLABUS_VERSION", ownerId, "SYLLABUS_PDF", null);
				}
			}
		}
	}

	private String embeddedPath(SubjectGraph graph, String kind, String filename) {
		return "subjects/" + graph.subjectName() + "/exams/_examwork_embedded/" + kind + "/" + filename;
	}

	private void inventoryManagedFiles(SubjectGraph graph) throws IOException {
		Path subjectRoot = layout.subjectDirectory(graph.subjectName());
		if (!Files.exists(subjectRoot, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		if (!Files.isDirectory(subjectRoot, LinkOption.NOFOLLOW_LINKS)) {
			throw new PackageFormatException("Subject asset root is not a directory: " + subjectRoot);
		}
		for (String section : List.of("curriculum", "legacy", "exams")) {
			Path root = subjectRoot.resolve(section);
			if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
				continue;
			}
			Files.walkFileTree(root, new SimpleFileVisitor<>() {

				@Override
				public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
						throws IOException {
					if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
						throw new PackageFormatException("Unsafe Subject directory: " + directory);
					}
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
					if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
						throw new PackageFormatException("Unsupported Subject file: " + file);
					}
					String relativePath = layout.relativePath(file);
					if (!referencedManagedPaths.contains(relativePath)) {
						collectManagedFile(graph, relativePath, null, null, "INVENTORIED", null);
					}
					return FileVisitResult.CONTINUE;
				}
			});
		}
	}

	private String mediaType(String suffix) {
		return switch (suffix) {
		case "pdf" -> "application/pdf";
		case "png" -> "image/png";
		case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
		case "xls" -> "application/vnd.ms-excel";
		default -> "application/octet-stream";
		};
	}

	private MessageDigest newDigest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private void registerBinary(InputStream input, String logicalPath, String ownerType, UUID ownerId, String usage,
			String suffix, String mediaType, String expectedHash) throws IOException {
		if (input == null) {
			throw new PackageFormatException("Missing binary content: " + logicalPath);
		}
		Path temporary = Files.createTempFile(stagingDirectory, "asset-", ".tmp");
		MessageDigest digest = newDigest();
		long size = 0;
		try {
			try (OutputStream output = Files.newOutputStream(temporary)) {
				byte[] buffer = new byte[8192];
				for (int count; (count = input.read(buffer)) != -1;) {
					size += count;
					if (size > MAX_BINARY_BYTES) {
						throw new PackageFormatException("Asset exceeds package size limit: " + logicalPath);
					}
					digest.update(buffer, 0, count);
					output.write(buffer, 0, count);
				}
			}
			String sha256 = HexFormat.of().formatHex(digest.digest());
			if (expectedHash != null && !expectedHash.equals(sha256)) {
				throw new PackageFormatException("Stored SHA-256 differs from asset bytes: " + logicalPath);
			}
			String archivePath = "assets/sha256/" + sha256.substring(0, 2) + "/" + sha256 + "." + suffix;
			String prior = archivePathsByDigest.putIfAbsent(sha256, archivePath);
			if (prior != null && !prior.equals(archivePath)) {
				throw new PackageFormatException("Identical bytes have conflicting asset types: " + logicalPath);
			}
			Path existing = stagedFiles.putIfAbsent(archivePath, temporary);
			if (existing != null) {
				Files.delete(temporary);
			}
			references.add(new Reference(archivePath, sha256, size, mediaType, logicalPath, ownerType, ownerId, usage));
		} catch (IOException | RuntimeException exception) {
			Files.deleteIfExists(temporary);
			throw exception;
		}
	}

	private void requireSafeRegularFile(Path file, Path subjectRoot) throws IOException {
		Path current = file;
		while (current != null && current.startsWith(subjectRoot)) {
			if (Files.isSymbolicLink(current)) {
				throw new PackageFormatException("Symbolic link in managed asset path: " + file);
			}
			current = current.getParent();
		}
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
			throw new PackageFormatException("Missing or invalid managed asset: " + file);
		}
	}

	private String suffix(Path path) {
		String filename = path.getFileName().toString().toLowerCase(Locale.ROOT);
		int dot = filename.lastIndexOf('.');
		if (dot < 0) {
			return "bin";
		}
		String extension = filename.substring(dot + 1);
		return extension.matches("[a-z0-9]{1,8}") ? extension : "bin";
	}

	/**
	 * Immutable description of collected archive assets.
	 *
	 * <p>
	 * The staged paths remain owned by the calling export service and must exist
	 * until the package writer has finished reading them.
	 *
	 * @param index       canonical logical asset-reference index
	 * @param stagedFiles archive paths mapped to temporary physical files
	 */
	public record CollectedAssets(ExamWorkAssetIndex index, Map<String, Path> stagedFiles) {

		/**
		 * Validates and copies the collected asset metadata.
		 *
		 * @throws NullPointerException if either component is null
		 */
		public CollectedAssets {
			Objects.requireNonNull(index, "index");
			Objects.requireNonNull(stagedFiles, "stagedFiles");
			stagedFiles = Collections.unmodifiableMap(new TreeMap<>(stagedFiles));
		}
	}
}
