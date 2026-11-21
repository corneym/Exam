package au.edu.eq.questionbank.service.workpackage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.workpackage.ExamWorkManifest.ScopeLevel;

/**
 * Records durable publication evidence for contributor returns.
 *
 * <p>
 * An assignment may publish one contribution package. Identical replay of that
 * same package identity and digest remains idempotent, but a second package for
 * the assignment is refused. Existing historical return evidence is retained
 * without alteration.
 *
 * <p>
 * Recording the return does not delete captured work or modify its original
 * assignment baseline. The presence of a recorded return means the assignment
 * is locally SUBMITTED and is no longer available for capture.
 */
public final class SqliteContributionReturnLedger {

	private final SqliteDatabase database;

	/**
	 * Creates a return ledger for a contributor installation.
	 *
	 * @param database initialised contributor database
	 * @throws NullPointerException if database is null
	 */
	public SqliteContributionReturnLedger(SqliteDatabase database) {
		this.database = Objects.requireNonNull(database, "database");
	}

	/**
	 * Finds a previously recorded contribution package.
	 *
	 * @param packageId immutable contribution package identity
	 * @return matching published return, or empty
	 * @throws SQLException         if database retrieval fails
	 * @throws NullPointerException if packageId is null
	 */
	public Optional<PublishedReturn> find(UUID packageId) throws SQLException {
		Objects.requireNonNull(packageId, "packageId");
		try (Connection connection = database.openConnection()) {
			return find(connection, packageId);
		}
	}

	/**
	 * Finds the most recently recorded return for an assignment.
	 *
	 * <p>
	 * This lookup is independent of the contributor's current active assignment
	 * selection.
	 *
	 * @param assignmentId installed assignment identity
	 * @return latest return, or empty when no return was recorded
	 * @throws SQLException         if retrieval fails
	 * @throws NullPointerException if assignmentId is null
	 */
	public Optional<PublishedReturn> findLatest(UUID assignmentId) throws SQLException {
		Objects.requireNonNull(assignmentId, "assignmentId");
		try (Connection connection = database.openConnection()) {
			return findLatest(connection, assignmentId);
		}
	}

	/**
	 * Records an already validated and published contribution package.
	 *
	 * <p>
	 * The first submission must have no predecessor. An identical package replay is
	 * idempotent; a different subsequent package is rejected.
	 *
	 * @param manifest          validated contribution manifest
	 * @param previousPackageId must be null for the only permitted submission
	 * @param archiveSha256     SHA-256 of the published archive bytes
	 * @return durable published-return evidence
	 * @throws SQLException             if identity, provenance, duplicate or
	 *                                  lineage checks fail, or persistence cannot
	 *                                  complete
	 * @throws IllegalArgumentException if the digest is invalid
	 * @throws NullPointerException     if required arguments are null
	 */
	PublishedReturn recordPublished(ExamWorkManifest manifest, UUID previousPackageId, String archiveSha256)
			throws SQLException {
		Objects.requireNonNull(manifest, "manifest");
		Objects.requireNonNull(archiveSha256, "archiveSha256");
		if (!archiveSha256.matches("[0-9a-f]{64}")) {
			throw new IllegalArgumentException("Invalid contribution archive SHA-256");
		}
		if (manifest.packageType() != ExamWorkPackageType.CONTRIBUTION) {
			throw new SQLException("Only contribution packages may be recorded");
		}
		SqliteContributorAssignmentSessionRepository sessions = new SqliteContributorAssignmentSessionRepository(
				database);
		var session = sessions.find(manifest.assignmentId()).orElseThrow(
				() -> new SQLException("Contribution assignment is not installed: " + manifest.assignmentId()));
		UUID installationId = new SqlitePortableIdentityRepository(database).installationId();
		requireAssignment(manifest, session, installationId);
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {

				// Check for a genuine replay before checking the
				// current end of the lineage chain.
				Optional<PublishedReturn> existing = find(connection, manifest.packageId());
				if (existing.isPresent()) {
					PublishedReturn stored = existing.orElseThrow();
					if (!stored.assignmentId().equals(manifest.assignmentId())
							|| !Objects.equals(stored.previousPackageId(), previousPackageId)
							|| !stored.archiveSha256().equals(archiveSha256)) {
						throw new SQLException("Contribution package UUID was reused " + "with different evidence");
					}
					connection.commit();
					return stored;
				}
				Optional<PublishedReturn> latest = findLatest(connection, manifest.assignmentId());
				if (latest.isPresent()) {
					throw new SQLException("A contribution has already been published for this assignment. "
							+ "A second contribution is not permitted.");
				}
				if (previousPackageId != null) {
					throw new SQLException(
							"The first and only assignment contribution cannot have a previous package.");
				}
				int returnNumber = 1;
				Instant recordedAt = Instant.now();
				try (PreparedStatement statement = connection.prepareStatement("""
						INSERT INTO contributor_return_packages (
						    package_id,
						    assignment_id,
						    return_number,
						    previous_package_id,
						    baseline_snapshot_id,
						    master_installation_id,
						    contributor_id,
						    origin_installation_id,
						    archive_sha256,
						    recorded_at
						)
						VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
						""")) {
					statement.setString(1, manifest.packageId().toString());
					statement.setString(2, manifest.assignmentId().toString());
					statement.setInt(3, returnNumber);
					statement.setString(4, previousPackageId == null ? null : previousPackageId.toString());
					statement.setString(5, session.baselineSnapshotId().toString());
					statement.setString(6, session.masterInstallationId().toString());
					statement.setString(7, session.contributorId().toString());
					statement.setString(8, installationId.toString());
					statement.setString(9, archiveSha256);
					statement.setString(10, recordedAt.toString());
					if (statement.executeUpdate() != 1) {
						throw new SQLException("Contribution publication was not recorded");
					}
				}
				PublishedReturn stored = find(connection, manifest.packageId())
						.orElseThrow(() -> new SQLException("Recorded contribution was not retained"));
				connection.commit();
				return stored;
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

	private Optional<PublishedReturn> find(Connection connection, UUID packageId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT *
				FROM contributor_return_packages
				WHERE package_id = ?
				""")) {
			statement.setString(1, packageId.toString());
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					return Optional.empty();
				}
				PublishedReturn found = readReturn(result);
				if (result.next()) {
					throw new SQLException("Duplicate contribution package identity");
				}
				return Optional.of(found);
			}
		}
	}

	private Optional<PublishedReturn> findLatest(Connection connection, UUID assignmentId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT *
				FROM contributor_return_packages
				WHERE assignment_id = ?
				ORDER BY return_number DESC
				LIMIT 1
				""")) {
			statement.setString(1, assignmentId.toString());
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					return Optional.empty();
				}
				return Optional.of(readReturn(result));
			}
		}
	}

	private PublishedReturn readReturn(ResultSet result) throws SQLException {
		try {
			String previous = result.getString("previous_package_id");
			return new PublishedReturn(UUID.fromString(result.getString("package_id")),
					UUID.fromString(result.getString("assignment_id")), result.getInt("return_number"),
					previous == null ? null : UUID.fromString(previous),
					UUID.fromString(result.getString("baseline_snapshot_id")),
					UUID.fromString(result.getString("master_installation_id")),
					UUID.fromString(result.getString("contributor_id")),
					UUID.fromString(result.getString("origin_installation_id")), result.getString("archive_sha256"),
					Instant.parse(result.getString("recorded_at")));
		} catch (IllegalArgumentException | NullPointerException failure) {
			throw new SQLException("Invalid persisted contribution return evidence", failure);
		}
	}

	private void requireAssignment(ExamWorkManifest manifest,
			SqliteContributorAssignmentSessionRepository.Session session, UUID installationId) throws SQLException {
		if (!manifest.originInstallationId().equals(installationId)
				|| !manifest.assignmentId().equals(session.assignmentId())
				|| !manifest.baselineSnapshotId().equals(session.baselineSnapshotId())
				|| !manifest.intendedMasterInstallationId().equals(session.masterInstallationId())
				|| !manifest.subjectId().equals(session.subjectPortableId())
				|| manifest.scope().level() != ScopeLevel.EXAMS
				|| !manifest.scope().examIds().equals(List.of(session.examPortableId()))
				|| !manifest.scope().bookletIds().isEmpty() || manifest.packageId().equals(session.packageId())) {
			throw new SQLException("Contribution package does not match " + "the installed whole-Exam assignment");
		}
	}

	/**
	 * Immutable evidence of one published contributor return.
	 *
	 * @param packageId            contribution package UUID
	 * @param assignmentId         originating assignment UUID
	 * @param returnNumber         one-based return order for this assignment
	 * @param previousPackageId    preceding return package, or null for the first
	 *                             return
	 * @param baselineSnapshotId   original immutable baseline UUID
	 * @param masterInstallationId intended coordinator UUID
	 * @param contributorId        assigned contributor UUID
	 * @param originInstallationId exporting installation UUID
	 * @param archiveSha256        published archive SHA-256 digest
	 * @param recordedAt           time publication evidence was persisted
	 */
	public record PublishedReturn(UUID packageId, UUID assignmentId, int returnNumber, UUID previousPackageId,
			UUID baselineSnapshotId, UUID masterInstallationId, UUID contributorId, UUID originInstallationId,
			String archiveSha256, Instant recordedAt) {

		/**
		 * Validates published return evidence.
		 *
		 * @throws IllegalArgumentException if order or digest is invalid
		 * @throws NullPointerException     if required values are null
		 */
		public PublishedReturn {
			Objects.requireNonNull(packageId, "packageId");
			Objects.requireNonNull(assignmentId, "assignmentId");
			Objects.requireNonNull(baselineSnapshotId, "baselineSnapshotId");
			Objects.requireNonNull(masterInstallationId, "masterInstallationId");
			Objects.requireNonNull(contributorId, "contributorId");
			Objects.requireNonNull(originInstallationId, "originInstallationId");
			Objects.requireNonNull(recordedAt, "recordedAt");
			if (returnNumber < 1 || (returnNumber == 1 && previousPackageId != null)
					|| (returnNumber > 1 && previousPackageId == null)) {
				throw new IllegalArgumentException("Invalid contributor return order");
			}
			if (archiveSha256 == null || !archiveSha256.matches("[0-9a-f]{64}")) {
				throw new IllegalArgumentException("Invalid contributor archive digest");
			}
		}
	}
}
