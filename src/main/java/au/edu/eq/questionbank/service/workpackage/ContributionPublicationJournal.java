package au.edu.eq.questionbank.service.workpackage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
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
import java.util.Optional;
import java.util.UUID;

import au.edu.eq.questionbank.ApplicationVersion;
import au.edu.eq.questionbank.ManagedAssetPublicationLock;
import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

/**
 * Persists the narrow recovery boundary between a built Contribution and its
 * detached terminal evidence.
 *
 * <p>
 * A {@code PREPARED} attempt proves that final publication has not started. A
 * {@code PUBLISHING} or {@code PUBLISHED} attempt is fail-closed until the
 * exact archive can be authenticated and terminal evidence is recorded. Missing
 * or inaccessible destinations never reopen an uncertain assignment.
 */
final class ContributionPublicationJournal {

	private final SqliteDatabase database;
	private final ManagedDataLayout layout;

	ContributionPublicationJournal(SqliteDatabase database, ManagedDataLayout layout) {
		this.database = Objects.requireNonNull(database, "database");
		this.layout = Objects.requireNonNull(layout, "layout");
	}

	void cancelPrepared(Attempt attempt, ManagedAssetPublicationLock lock) throws IOException, SQLException {
		Objects.requireNonNull(attempt, "attempt");
		lock.requireHeld(layout.dataRoot());
		StoredPaths paths = requireStoredPaths(attempt);
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				try (PreparedStatement statement = connection.prepareStatement("""
						DELETE FROM contribution_publication_attempts
						WHERE assignment_id = ?
						  AND package_id = ?
						  AND state = 'PREPARED'
						""")) {
					statement.setString(1, attempt.assignmentId().toString());
					statement.setString(2, attempt.packageId().toString());
					if (statement.executeUpdate() != 1) {
						throw new SQLException("Prepared Contribution publication attempt changed concurrently");
					}
				}
				if (attempt.selectionWasActive()) {
					try (PreparedStatement restore = connection.prepareStatement("""
							UPDATE contributor_assignment_selection
							SET assignment_id = ?, updated_at = ?
							WHERE singleton_id = 1
							  AND assignment_id IS NULL
							""")) {
						restore.setString(1, attempt.assignmentId().toString());
						restore.setString(2, Instant.now().toString());
						restore.executeUpdate();
					}
				}
				connection.commit();
			} catch (SQLException | RuntimeException failure) {
				try {
					connection.rollback();
				} catch (SQLException rollbackFailure) {
					failure.addSuppressed(rollbackFailure);
				}
				throw failure;
			}
		}
		deleteStaging(paths.staging());
	}

	Optional<Attempt> find(UUID assignmentId) throws SQLException {
		Objects.requireNonNull(assignmentId, "assignmentId");
		try (Connection connection = database.openConnection()) {
			return find(connection, assignmentId);
		}
	}

	SqliteContributionReturnLedger.PublishedReturn finalisePublished(Attempt attempt, ExamWorkManifest manifest,
			ManagedAssetPublicationLock lock) throws IOException, SQLException {
		Objects.requireNonNull(attempt, "attempt");
		Objects.requireNonNull(manifest, "manifest");
		lock.requireHeld(layout.dataRoot());
		requireManifest(attempt, manifest);
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				Attempt current = find(connection, attempt.assignmentId())
						.orElseThrow(() -> new SQLException("Contribution publication attempt is missing"));
				if (!current.equals(attempt) || current.state() != State.PUBLISHED) {
					throw new SQLException("Contribution publication is not ready for terminal recording");
				}
				var returned = new SqliteContributionReturnLedger(database).recordPublished(connection, manifest, null,
						attempt.archiveSha256());
				try (PreparedStatement clear = connection.prepareStatement("""
						UPDATE contributor_assignment_selection
						SET assignment_id = NULL,
						    updated_at = ?
						WHERE singleton_id = 1
						  AND assignment_id = ?
						""")) {
					clear.setString(1, Instant.now().toString());
					clear.setString(2, attempt.assignmentId().toString());
					clear.executeUpdate();
				}
				try (PreparedStatement delete = connection.prepareStatement("""
						DELETE FROM contribution_publication_attempts
						WHERE assignment_id = ?
						  AND package_id = ?
						  AND state = 'PUBLISHED'
						""")) {
					delete.setString(1, attempt.assignmentId().toString());
					delete.setString(2, attempt.packageId().toString());
					if (delete.executeUpdate() != 1) {
						throw new SQLException("Contribution publication attempt was not cleared");
					}
				}
				connection.commit();
				return returned;
			} catch (SQLException | RuntimeException failure) {
				try {
					connection.rollback();
				} catch (SQLException rollbackFailure) {
					failure.addSuppressed(rollbackFailure);
				}
				throw failure;
			}
		}
	}

	Attempt markPublished(Attempt attempt, ManagedAssetPublicationLock lock) throws IOException, SQLException {
		return transition(attempt, State.PUBLISHING, State.PUBLISHED, lock);
	}

	Attempt markPublishing(Attempt attempt, ManagedAssetPublicationLock lock) throws IOException, SQLException {
		return transition(attempt, State.PREPARED, State.PUBLISHING, lock);
	}

	Attempt prepare(ExamWorkManifest manifest, String archiveSha256, Path destination, Path staging,
			ManagedAssetPublicationLock lock) throws IOException, SQLException {
		Objects.requireNonNull(manifest, "manifest");
		Objects.requireNonNull(archiveSha256, "archiveSha256");
		lock.requireHeld(layout.dataRoot());
		if (manifest.packageType() != ExamWorkPackageType.CONTRIBUTION || !archiveSha256.matches("[0-9a-f]{64}")) {
			throw new IllegalArgumentException("Invalid Contribution publication evidence");
		}
		Instant now = Instant.now();
		Attempt attempt = new Attempt(manifest.assignmentId(), manifest.packageId(), archiveSha256,
				manifest.createdAt(), destination.toAbsolutePath().normalize().toString(),
				staging.toAbsolutePath().normalize().toString(), State.PREPARED, false, now, now);
		StoredPaths paths = requireStoredPaths(attempt);
		if (Files.exists(paths.destination(), LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Contribution destination already exists: " + paths.destination());
		}
		if (!Files.isRegularFile(paths.staging(), LinkOption.NOFOLLOW_LINKS)
				|| !archiveSha256.equals(sha256(paths.staging()))) {
			throw new IOException("Contribution destination staging is missing or modified");
		}
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				boolean selected;
				try (PreparedStatement selection = connection.prepareStatement("""
						SELECT assignment_id = ? AS selected
						FROM contributor_assignment_selection
						WHERE singleton_id = 1
						""")) {
					selection.setString(1, attempt.assignmentId().toString());
					try (ResultSet result = selection.executeQuery()) {
						if (!result.next()) {
							throw new SQLException("Contributor assignment selection is unavailable");
						}
						selected = result.getInt("selected") == 1;
					}
				}
				attempt = new Attempt(attempt.assignmentId(), attempt.packageId(), attempt.archiveSha256(),
						attempt.contributionCreatedAt(), attempt.destinationPath(), attempt.stagingPath(),
						attempt.state(), selected, attempt.preparedAt(), attempt.updatedAt());
				try (PreparedStatement statement = connection.prepareStatement("""
						INSERT INTO contribution_publication_attempts (
						    assignment_id, package_id, archive_sha256,
						    contribution_created_at, destination_path, staging_path,
						    state, selection_was_active, prepared_at, updated_at
						)
						VALUES (?, ?, ?, ?, ?, ?, 'PREPARED', ?, ?, ?)
						""")) {
					statement.setString(1, attempt.assignmentId().toString());
					statement.setString(2, attempt.packageId().toString());
					statement.setString(3, attempt.archiveSha256());
					statement.setString(4, attempt.contributionCreatedAt().toString());
					statement.setString(5, attempt.destinationPath());
					statement.setString(6, attempt.stagingPath());
					statement.setInt(7, attempt.selectionWasActive() ? 1 : 0);
					statement.setString(8, attempt.preparedAt().toString());
					statement.setString(9, attempt.updatedAt().toString());
					if (statement.executeUpdate() != 1) {
						throw new SQLException("Contribution publication attempt was not prepared");
					}
				}
				if (selected) {
					try (PreparedStatement clear = connection.prepareStatement("""
							UPDATE contributor_assignment_selection
							SET assignment_id = NULL, updated_at = ?
							WHERE singleton_id = 1 AND assignment_id = ?
							""")) {
						clear.setString(1, now.toString());
						clear.setString(2, attempt.assignmentId().toString());
						if (clear.executeUpdate() != 1) {
							throw new SQLException("Contributor assignment selection changed concurrently");
						}
					}
				}
				connection.commit();
			} catch (SQLException | RuntimeException failure) {
				try {
					connection.rollback();
				} catch (SQLException rollbackFailure) {
					failure.addSuppressed(rollbackFailure);
				}
				throw failure;
			}
		}
		return attempt;
	}

	void recoverPending(ManagedAssetPublicationLock lock) throws IOException, SQLException {
		Objects.requireNonNull(lock, "lock");
		lock.requireHeld(layout.dataRoot());
		for (Attempt attempt : findAll()) {
			if (attempt.state() == State.PREPARED) {
				cancelPrepared(attempt, lock);
				continue;
			}
			StoredPaths paths = requireStoredPaths(attempt);
			if (!Files.isRegularFile(paths.destination(), LinkOption.NOFOLLOW_LINKS)) {
				throw unresolved(attempt, "destination is missing or inaccessible");
			}
			String digest = sha256(paths.destination());
			if (!attempt.archiveSha256().equals(digest)) {
				throw unresolved(attempt, "destination bytes do not match the prepared Contribution");
			}
			ExamWorkManifest manifest = new ExamWorkArchiveValidator().validate(paths.destination(),
					ApplicationVersion.current(), database.schemaVersion());
			requireManifest(attempt, manifest);
			deleteStaging(paths.staging());
			Attempt published = attempt.state() == State.PUBLISHING ? markPublished(attempt, lock) : attempt;
			finalisePublished(published, manifest, lock);
		}
	}

	private void deleteStaging(Path staging) throws IOException {
		if (!Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		if (!Files.isRegularFile(staging, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Unsafe Contribution staging path: " + staging);
		}
		Files.delete(staging);
	}

	private Optional<Attempt> find(Connection connection, UUID assignmentId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT assignment_id, package_id, archive_sha256,
				       contribution_created_at, destination_path, staging_path,
				       state, selection_was_active, prepared_at, updated_at
				FROM contribution_publication_attempts
				WHERE assignment_id = ?
				""")) {
			statement.setString(1, assignmentId.toString());
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					return Optional.empty();
				}
				Attempt attempt = read(result);
				if (result.next()) {
					throw new SQLException("Duplicate Contribution publication assignment identity");
				}
				return Optional.of(attempt);
			}
		}
	}

	private List<Attempt> findAll() throws SQLException {
		List<Attempt> attempts = new ArrayList<>();
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT assignment_id, package_id, archive_sha256,
						       contribution_created_at, destination_path, staging_path,
						       state, selection_was_active, prepared_at, updated_at
						FROM contribution_publication_attempts
						ORDER BY prepared_at, assignment_id
						""");
				ResultSet result = statement.executeQuery()) {
			while (result.next()) {
				attempts.add(read(result));
			}
		}
		return List.copyOf(attempts);
	}

	private Attempt read(ResultSet result) throws SQLException {
		try {
			return new Attempt(UUID.fromString(result.getString("assignment_id")),
					UUID.fromString(result.getString("package_id")), result.getString("archive_sha256"),
					Instant.parse(result.getString("contribution_created_at")), result.getString("destination_path"),
					result.getString("staging_path"), State.valueOf(result.getString("state")),
					result.getInt("selection_was_active") == 1, Instant.parse(result.getString("prepared_at")),
					Instant.parse(result.getString("updated_at")));
		} catch (IllegalArgumentException | NullPointerException failure) {
			throw new SQLException("Invalid persisted Contribution publication attempt", failure);
		}
	}

	private void requireManifest(Attempt attempt, ExamWorkManifest manifest) throws IOException {
		if (manifest.packageType() != ExamWorkPackageType.CONTRIBUTION
				|| !manifest.assignmentId().equals(attempt.assignmentId())
				|| !manifest.packageId().equals(attempt.packageId())
				|| !manifest.createdAt().equals(attempt.contributionCreatedAt())) {
			throw unresolved(attempt, "archive manifest does not match the prepared Contribution");
		}
	}

	private StoredPaths requireStoredPaths(Attempt attempt) throws IOException {
		try {
			Path destination = Path.of(attempt.destinationPath());
			Path staging = Path.of(attempt.stagingPath());
			if (!destination.isAbsolute() || !staging.isAbsolute() || !destination.equals(destination.normalize())
					|| !staging.equals(staging.normalize()) || destination.getParent() == null
					|| !destination.getParent().equals(staging.getParent()) || destination.equals(staging)
					|| destination.getFileName() == null || !destination.getFileName().toString().endsWith(".examwork")
					|| staging.getFileName() == null
					|| !staging.getFileName().toString().startsWith(".examwork-contribution-")
					|| !staging.getFileName().toString().endsWith(".tmp")) {
				throw unresolved(attempt, "stored publication paths are unsafe");
			}
			Path physicalParent;
			try {
				physicalParent = destination.getParent().toRealPath();
			} catch (IOException failure) {
				throw unresolved(attempt, "stored publication directory is missing or inaccessible", failure);
			}
			if (!physicalParent.equals(destination.getParent())
					|| physicalParent.startsWith(layout.subjectsRoot().toAbsolutePath().normalize())) {
				throw unresolved(attempt, "stored publication directory is unsafe");
			}
			return new StoredPaths(destination, staging);
		} catch (InvalidPathException failure) {
			throw unresolved(attempt, "stored publication paths are invalid", failure);
		}
	}

	private String sha256(Path source) throws IOException {
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException failure) {
			throw new IllegalStateException("SHA-256 is unavailable", failure);
		}
		try (InputStream input = Files.newInputStream(source)) {
			byte[] buffer = new byte[8192];
			int count;
			while ((count = input.read(buffer)) != -1) {
				digest.update(buffer, 0, count);
			}
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	private Attempt transition(Attempt attempt, State expected, State next, ManagedAssetPublicationLock lock)
			throws IOException, SQLException {
		Objects.requireNonNull(attempt, "attempt");
		lock.requireHeld(layout.dataRoot());
		Instant updatedAt = Instant.now();
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						UPDATE contribution_publication_attempts
						SET state = ?, updated_at = ?
						WHERE assignment_id = ?
						  AND package_id = ?
						  AND state = ?
						""")) {
			statement.setString(1, next.name());
			statement.setString(2, updatedAt.toString());
			statement.setString(3, attempt.assignmentId().toString());
			statement.setString(4, attempt.packageId().toString());
			statement.setString(5, expected.name());
			if (statement.executeUpdate() != 1) {
				throw new SQLException("Contribution publication state changed concurrently");
			}
		}
		return new Attempt(attempt.assignmentId(), attempt.packageId(), attempt.archiveSha256(),
				attempt.contributionCreatedAt(), attempt.destinationPath(), attempt.stagingPath(), next,
				attempt.selectionWasActive(), attempt.preparedAt(), updatedAt);
	}

	private IOException unresolved(Attempt attempt, String reason) {
		return new IOException("Contribution publication remains unresolved for assignment " + attempt.assignmentId()
				+ ": " + reason + ". Preserve the assignment corpus and resolve the original archive before retrying.");
	}

	private IOException unresolved(Attempt attempt, String reason, Exception cause) {
		return new IOException("Contribution publication remains unresolved for assignment " + attempt.assignmentId()
				+ ": " + reason + ". Preserve the assignment corpus and resolve the original archive before retrying.",
				cause);
	}

	record Attempt(UUID assignmentId, UUID packageId, String archiveSha256, Instant contributionCreatedAt,
			String destinationPath, String stagingPath, State state, boolean selectionWasActive, Instant preparedAt,
			Instant updatedAt) {

		Attempt {
			Objects.requireNonNull(assignmentId, "assignmentId");
			Objects.requireNonNull(packageId, "packageId");
			Objects.requireNonNull(contributionCreatedAt, "contributionCreatedAt");
			Objects.requireNonNull(destinationPath, "destinationPath");
			Objects.requireNonNull(stagingPath, "stagingPath");
			Objects.requireNonNull(state, "state");
			Objects.requireNonNull(preparedAt, "preparedAt");
			Objects.requireNonNull(updatedAt, "updatedAt");
			if (archiveSha256 == null || !archiveSha256.matches("[0-9a-f]{64}")) {
				throw new IllegalArgumentException("Invalid Contribution archive digest");
			}
		}
	}

	enum State {
		PREPARED, PUBLISHING, PUBLISHED
	}

	private record StoredPaths(Path destination, Path staging) {
	}
}
