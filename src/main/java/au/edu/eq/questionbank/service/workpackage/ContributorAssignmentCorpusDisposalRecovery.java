package au.edu.eq.questionbank.service.workpackage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.ManagedAssetPublicationLock;
import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.repository.sqlite.SqliteDirectChanges;

/** Resumes exact managed-file release for database-released assignments. */
final class ContributorAssignmentCorpusDisposalRecovery {

	private final ApplicationConfig config;
	private final FaultInjector faults;
	private final ManagedDataLayout layout;

	ContributorAssignmentCorpusDisposalRecovery(ApplicationConfig config) {
		this(config, FaultInjector.NONE);
	}

	ContributorAssignmentCorpusDisposalRecovery(ApplicationConfig config, FaultInjector faults) {
		this.config = Objects.requireNonNull(config, "config");
		this.faults = Objects.requireNonNull(faults, "faults");
		layout = new ManagedDataLayout(config.dataRoot());
	}

	void recoverPending(ManagedAssetPublicationLock lock) throws IOException, SQLException {
		lock.requireHeld(config.dataRoot());
		while (recoverNext(lock)) {

			// Reopen the authoritative reference snapshot after each committed operation.
		}
	}

	void requireNoPlanned(ManagedAssetPublicationLock lock, UUID allowedAssignmentId) throws IOException, SQLException {
		lock.requireHeld(config.dataRoot());
		String sql = allowedAssignmentId == null ? """
				SELECT scope_id
				FROM corpus_disposal_operations
				WHERE policy = 'CONTRIBUTOR_ASSIGNMENT'
				  AND policy_version = 1
				  AND state = 'PLANNED'
				ORDER BY planned_at, operation_id
				LIMIT 1
				""" : """
				SELECT scope_id
				FROM corpus_disposal_operations
				WHERE policy = 'CONTRIBUTOR_ASSIGNMENT'
				  AND policy_version = 1
				  AND state = 'PLANNED'
				  AND scope_id <> ?
				ORDER BY planned_at, operation_id
				LIMIT 1
				""";
		try (Connection connection = new SqliteDatabase(config.databasePath()).openConnection();
				PreparedStatement statement = connection.prepareStatement(sql)) {
			if (allowedAssignmentId != null) {
				statement.setString(1, allowedAssignmentId.toString());
			}
			try (ResultSet row = statement.executeQuery()) {
				if (row.next()) {
					throw new IOException("Planned contributor corpus disposal requires exact service recovery: "
							+ row.getString("scope_id"));
				}
			}
		}
	}

	private FileState inspectCandidate(String relativePath) throws IOException {
		Path path;
		try {
			path = layout.resolve(relativePath);
		} catch (IllegalArgumentException | NullPointerException failure) {
			throw new IOException("Unsafe managed asset in corpus disposal evidence", failure);
		}
		if (!path.startsWith(layout.subjectsRoot()) || path.equals(layout.subjectsRoot())) {
			throw new IOException("Corpus disposal asset is outside the managed Subject tree: " + relativePath);
		}
		Path current = layout.dataRoot();
		for (Path component : layout.dataRoot().relativize(path)) {
			current = current.resolve(component);
			try {
				BasicFileAttributes attributes = Files.readAttributes(current, BasicFileAttributes.class,
						LinkOption.NOFOLLOW_LINKS);
				if (attributes.isSymbolicLink() || attributes.isOther()) {
					throw new IOException("Indirect managed asset in corpus disposal evidence: " + current);
				}
				if (!current.equals(path) && !attributes.isDirectory()) {
					throw new IOException("Non-directory managed asset ancestor: " + current);
				}
				if (current.equals(path)) {
					if (!attributes.isRegularFile()) {
						throw new IOException("Corpus disposal asset is not an ordinary file: " + relativePath);
					}
					return new FileState(path, attributes);
				}
			} catch (NoSuchFileException absent) {
				return new FileState(path, null);
			}
		}
		throw new IOException("Corpus disposal asset path did not resolve to a file: " + relativePath);
	}

	private List<Asset> readAssets(Connection connection, UUID operationId) throws SQLException {
		List<Asset> assets = new ArrayList<>();
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT relative_path, content_sha256
				FROM corpus_disposal_assets
				WHERE operation_id = ?
				ORDER BY relative_path
				""")) {
			statement.setString(1, operationId.toString());
			try (ResultSet rows = statement.executeQuery()) {
				while (rows.next()) {
					assets.add(new Asset(rows.getString("relative_path"), rows.getString("content_sha256")));
				}
			}
		}
		return List.copyOf(assets);
	}

	private UUID readNextOperation(Connection connection) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT operation_id
				FROM corpus_disposal_operations
				WHERE policy = 'CONTRIBUTOR_ASSIGNMENT'
				  AND policy_version = 1
				  AND state = 'DATABASE_RELEASED'
				ORDER BY planned_at, operation_id
				LIMIT 1
				"""); ResultSet row = statement.executeQuery()) {
			if (!row.next()) {
				return null;
			}
			String persistedId = row.getString("operation_id");
			UUID operationId;
			try {
				operationId = UUID.fromString(persistedId);
				if (!operationId.toString().equals(persistedId)) {
					throw new IllegalArgumentException("Noncanonical UUID");
				}
			} catch (IllegalArgumentException | NullPointerException failure) {
				throw new SQLException("Invalid corpus disposal operation identity", failure);
			}
			if (row.next()) {
				throw new SQLException("Duplicate corpus disposal recovery selection");
			}
			return operationId;
		}
	}

	private boolean recoverNext(ManagedAssetPublicationLock lock) throws IOException, SQLException {
		try (ManagedAssetRecoveryReferences references = new ManagedAssetRecoveryReferences(config, lock)) {
			Connection connection = references.connection();
			UUID operationId = readNextOperation(connection);
			if (operationId == null) {
				return false;
			}
			for (Asset asset : readAssets(connection, operationId)) {
				releaseAsset(references, asset);
			}
			markVerified(connection, operationId);
			faults.reach(FaultPoint.BEFORE_VERIFY_COMMIT);
			references.commit();
			return true;
		}
	}

	private void releaseAsset(ManagedAssetRecoveryReferences references, Asset asset) throws IOException {
		FileState state = inspectCandidate(asset.relativePath());
		if (references.contains(state.path())) {
			throw new IOException("Corpus disposal asset gained an authoritative reference: " + asset.relativePath());
		}
		if (state.attributes() == null) {
			return;
		}
		String actualHash = sha256(state.path());
		BasicFileAttributes afterHash = Files.readAttributes(state.path(), BasicFileAttributes.class,
				LinkOption.NOFOLLOW_LINKS);
		if (!asset.sha256().equals(actualHash) || !sameFileState(state.attributes(), afterHash)) {
			throw new IOException("Corpus disposal asset changed after planning: " + asset.relativePath());
		}
		Files.delete(state.path());
		faults.reach(FaultPoint.AFTER_ASSET_DELETE);
	}

	private void markVerified(Connection connection, UUID operationId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				UPDATE corpus_disposal_operations
				SET state = 'VERIFIED',
				    updated_at = ?,
				    verified_at = ?
				WHERE operation_id = ?
				  AND state = 'DATABASE_RELEASED'
				""")) {
			String now = Instant.now().toString();
			statement.setString(1, now);
			statement.setString(2, now);
			statement.setString(3, operationId.toString());
			if (SqliteDirectChanges.executeUpdate(statement) != 1) {
				throw new SQLException("Corpus disposal operation did not reach verified state");
			}
		}
	}

	private boolean sameFileState(BasicFileAttributes before, BasicFileAttributes after) {
		return after.isRegularFile() && before.size() == after.size()
				&& before.lastModifiedTime().equals(after.lastModifiedTime())
				&& Objects.equals(before.fileKey(), after.fileKey());
	}

	private String sha256(Path path) throws IOException {
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException failure) {
			throw new IllegalStateException("SHA-256 is unavailable", failure);
		}
		try (InputStream input = Files.newInputStream(path)) {
			byte[] buffer = new byte[8192];
			for (int count; (count = input.read(buffer)) != -1;) {
				digest.update(buffer, 0, count);
			}
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	private record Asset(String relativePath, String sha256) {
	}

	private record FileState(Path path, BasicFileAttributes attributes) {
	}

	enum FaultPoint {
		AFTER_ASSET_DELETE, BEFORE_VERIFY_COMMIT
	}

	@FunctionalInterface
	interface FaultInjector {

		FaultInjector NONE = point -> {
		};

		void reach(FaultPoint point) throws IOException;
	}
}
