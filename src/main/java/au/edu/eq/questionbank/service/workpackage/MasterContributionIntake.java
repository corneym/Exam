package au.edu.eq.questionbank.service.workpackage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.workpackage.ContributionArchiveInspector.InspectedContribution;

/**
 * Records validated offline contributor returns in an append-only master
 * ledger.
 *
 * <p>
 * Receipt is not application: neither proposed records nor assets are written
 * to the authoritative corpus. Missing, conflicting or unreviewed ancestry is
 * retained as held evidence rather than guessed into sequence. All package
 * metadata and canonical proposed record values are committed atomically.
 */
public final class MasterContributionIntake {

	private final SqliteDatabase database;

	/**
	 * Creates an intake service for the coordinator database.
	 *
	 * @param database initialised coordinator database
	 * @throws NullPointerException if database is null
	 */
	public MasterContributionIntake(SqliteDatabase database) {
		this.database = Objects.requireNonNull(database, "database");
	}

	/**
	 * Finds the assignment associated with a return that remains eligible for
	 * reconciliation.
	 *
	 * @param connection current database connection
	 * @param packageId  received contribution package identity
	 * @return eligible assignment identity, or empty if the return cannot be
	 *         reconciled
	 * @throws SQLException if persistence or lifecycle evidence is invalid
	 */
	static Optional<UUID> findReviewableAssignment(Connection connection, UUID packageId) throws SQLException {
		Objects.requireNonNull(connection, "connection");
		Objects.requireNonNull(packageId, "packageId");
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT packet.assignment_id
				FROM contribution_intake_packages packet
				JOIN exam_work_assignment_states states
				  ON states.assignment_id = packet.assignment_id
				JOIN exam_work_assignments assignment
				  ON assignment.assignment_id = packet.assignment_id
				JOIN exam_work_assignment_exam_locks locks
				  ON locks.assignment_id = packet.assignment_id
				 AND locks.exam_portable_id =
				     assignment.exam_portable_id
				WHERE packet.package_id = ?
				  AND packet.intake_status = 'READY_FOR_REVIEW'
				  AND packet.return_number = 1
				  AND packet.previous_package_id IS NULL
				  AND states.state = 'ACTIVE'
				  AND NOT EXISTS (
				      SELECT 1
				      FROM contribution_intake_packages competing
				      WHERE competing.assignment_id = packet.assignment_id
				        AND competing.package_id <> packet.package_id
				  )
				""")) {
			statement.setString(1, packageId.toString());
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					return Optional.empty();
				}
				UUID assignmentId = UUID.fromString(result.getString("assignment_id"));
				if (result.next()) {
					throw new SQLException("Duplicate active assignment reconciliation");
				}
				return Optional.of(assignmentId);
			}
		}
	}

	/**
	 * Finds durable receipt evidence for a package UUID.
	 *
	 * @param packageId return package identity
	 * @return persisted return, if received
	 * @throws SQLException if retrieval fails
	 */
	public Optional<ReceivedPackage> find(UUID packageId) throws SQLException {
		Objects.requireNonNull(packageId, "packageId");
		try (Connection connection = database.openConnection()) {
			return find(connection, packageId);
		}
	}

	/**
	 * Reads the canonical proposed record states retained at intake.
	 *
	 * @param packageId return package identity
	 * @return immutable, record-identity ordered proposed changes
	 * @throws SQLException if database retrieval fails
	 * @throws IOException  if stored portable records are invalid
	 */
	public List<ExchangeRecord> proposedChanges(UUID packageId) throws SQLException, IOException {
		Objects.requireNonNull(packageId, "packageId");
		List<ExchangeRecord> records = new ArrayList<>();
		ExamWorkJsonlCodec codec = new ExamWorkJsonlCodec();
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT record_type, record_id, record_jsonl
						FROM contribution_intake_proposals
						WHERE package_id = ?
						ORDER BY record_type, record_id
						""")) {
			statement.setString(1, packageId.toString());
			try (ResultSet result = statement.executeQuery()) {
				while (result.next()) {
					List<ExchangeRecord> decoded = codec
							.readRecords(new ByteArrayInputStream(result.getBytes("record_jsonl")));
					if (decoded.size() != 1 || !decoded.getFirst().recordType().equals(result.getString("record_type"))
							|| !decoded.getFirst().recordId().toString().equals(result.getString("record_id"))) {
						throw new PackageFormatException("Persisted intake proposal identity is inconsistent");
					}
					records.add(decoded.getFirst());
				}
			}
		}
		return List.copyOf(records);
	}

	/**
	 * Validates and durably receives an offline contributor return.
	 *
	 * <p>
	 * Receipt never applies proposed records to the master. A duplicate package
	 * with identical bytes is returned idempotently. Different bytes reusing the
	 * same package UUID are rejected.
	 *
	 * <p>
	 * One assignment permits one ready first return. Additional returns,
	 * out-of-order returns and returns received after assignment closure are
	 * retained as HELD_LINEAGE evidence rather than silently applied.
	 *
	 * @param archive existing contribution archive
	 * @return immutable receipt and duplicate indicator
	 * @throws IOException          if inspection or archive validation fails
	 * @throws SQLException         if persistence fails
	 * @throws NullPointerException if archive is null
	 */
	public Receipt receive(Path archive) throws IOException, SQLException {
		Objects.requireNonNull(archive, "archive");
		InspectedContribution inspected = new ContributionArchiveInspector(database).inspect(archive);
		UUID packageId = inspected.manifest().packageId();
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				Optional<ReceivedPackage> previous = find(connection, packageId);
				if (previous.isPresent()) {
					ReceivedPackage received = previous.orElseThrow();
					if (!received.archiveSha256().equals(inspected.archiveSha256())) {
						throw new PackageFormatException("Return package UUID was reused for different bytes");
					}
					connection.commit();
					return new Receipt(received, true);
				}
				IntakeStatus status = classify(connection, inspected);
				Instant recordedAt = Instant.now();
				insertPackage(connection, inspected, status, recordedAt);
				insertProposals(connection, inspected);
				ReceivedPackage received = new ReceivedPackage(packageId, inspected.assignment().assignmentId(),
						inspected.returnNumber(), inspected.previousPackageId(),
						inspected.assignment().baselineSnapshotId(), inspected.assignment().masterInstallationId(),
						inspected.assignment().contributorId(), inspected.manifest().originInstallationId(),
						inspected.archiveSha256(), status, recordedAt);
				connection.commit();
				return new Receipt(received, false);
			} catch (SQLException | IOException | RuntimeException failure) {
				try {
					connection.rollback();
				} catch (SQLException rollbackFailure) {
					failure.addSuppressed(rollbackFailure);
				}
				throw failure;
			}
		}
	}

	private IntakeStatus classify(Connection connection, InspectedContribution contribution) throws SQLException {
		UUID assignmentId = contribution.assignment().assignmentId();
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT state
				FROM exam_work_assignment_states
				WHERE assignment_id = ?
				""")) {
			statement.setString(1, assignmentId.toString());
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new SQLException("Received return has no issued assignment state");
				}

				// Closed assignments retain old returns as evidence.
				// They must never accept another actionable submission.
				if (!"ACTIVE".equals(result.getString("state"))) {
					return IntakeStatus.HELD_LINEAGE;
				}
			}
		}

		// Each assignment now has one actionable return. Returning more
		// work requires the coordinator to issue a fresh assignment.
		if (contribution.returnNumber() != 1 || contribution.previousPackageId() != null) {
			return IntakeStatus.HELD_LINEAGE;
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT 1
				FROM contribution_intake_packages
				WHERE assignment_id = ?
				  AND intake_status = 'READY_FOR_REVIEW'
				LIMIT 1
				""")) {
			statement.setString(1, assignmentId.toString());
			try (ResultSet result = statement.executeQuery()) {
				if (result.next()) {

					// Competing first returns cannot both be actionable.
					return IntakeStatus.HELD_LINEAGE;
				}
			}
		}
		return IntakeStatus.READY_FOR_REVIEW;
	}

	private Optional<ReceivedPackage> find(Connection connection, UUID packageId) throws SQLException {
		if (packageId == null) {
			return Optional.empty();
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT package_id, assignment_id, return_number, previous_package_id,
				       baseline_snapshot_id, master_installation_id, contributor_id,
				       origin_installation_id, archive_sha256, intake_status, received_at
				FROM contribution_intake_packages
				WHERE package_id = ?
				""")) {
			statement.setString(1, packageId.toString());
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					return Optional.empty();
				}
				String prior = result.getString("previous_package_id");
				ReceivedPackage received = new ReceivedPackage(UUID.fromString(result.getString("package_id")),
						UUID.fromString(result.getString("assignment_id")), result.getInt("return_number"),
						prior == null ? null : UUID.fromString(prior),
						UUID.fromString(result.getString("baseline_snapshot_id")),
						UUID.fromString(result.getString("master_installation_id")),
						UUID.fromString(result.getString("contributor_id")),
						UUID.fromString(result.getString("origin_installation_id")), result.getString("archive_sha256"),
						IntakeStatus.valueOf(result.getString("intake_status")),
						Instant.parse(result.getString("received_at")));
				if (result.next()) {
					throw new SQLException("Duplicate received return identity");
				}
				return Optional.of(received);
			} catch (IllegalArgumentException failure) {
				throw new SQLException("Invalid persisted contribution intake evidence", failure);
			}
		}
	}

	private void insertPackage(Connection connection, InspectedContribution contribution, IntakeStatus status,
			Instant recordedAt) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO contribution_intake_packages (
				    package_id, assignment_id, return_number, previous_package_id,
				    baseline_snapshot_id, master_installation_id, contributor_id,
				    origin_installation_id, archive_sha256, intake_status, received_at
				) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""")) {
			statement.setString(1, contribution.manifest().packageId().toString());
			statement.setString(2, contribution.assignment().assignmentId().toString());
			statement.setInt(3, contribution.returnNumber());
			statement.setString(4,
					contribution.previousPackageId() == null ? null : contribution.previousPackageId().toString());
			statement.setString(5, contribution.assignment().baselineSnapshotId().toString());
			statement.setString(6, contribution.assignment().masterInstallationId().toString());
			statement.setString(7, contribution.assignment().contributorId().toString());
			statement.setString(8, contribution.manifest().originInstallationId().toString());
			statement.setString(9, contribution.archiveSha256());
			statement.setString(10, status.name());
			statement.setString(11, recordedAt.toString());
			statement.executeUpdate();
		}
	}

	private void insertProposals(Connection connection, InspectedContribution contribution)
			throws SQLException, IOException {
		ExamWorkJsonlCodec codec = new ExamWorkJsonlCodec();
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO contribution_intake_proposals (
				    package_id, record_type, record_id, proposed_revision_id,
				    baseline_revision_id, record_jsonl
				) VALUES (?, ?, ?, ?, ?, ?)
				""")) {
			for (ExchangeRecord record : contribution.changes()) {
				ByteArrayOutputStream bytes = new ByteArrayOutputStream();
				codec.writeRecords(List.of(record), bytes);
				statement.setString(1, contribution.manifest().packageId().toString());
				statement.setString(2, record.recordType());
				statement.setString(3, record.recordId().toString());
				statement.setString(4, record.revisionId().toString());
				statement.setString(5,
						record.baselineRevisionId() == null ? null : record.baselineRevisionId().toString());
				statement.setBytes(6, bytes.toByteArray());
				statement.executeUpdate();
			}
		}
	}

	/** Outcome of structural lineage checks, not of applying a contribution. */
	public enum IntakeStatus {
		/** The return follows a structurally present, unheld sequence. */
		READY_FOR_REVIEW,
		/** Return order, parent or competing sequence requires human review. */
		HELD_LINEAGE
	}

	/**
	 * Immutable master-side evidence of one received return.
	 *
	 * @param packageId            immutable archive UUID
	 * @param assignmentId         issued assignment UUID
	 * @param returnNumber         contributor-declared return order
	 * @param previousPackageId    preceding package, or null for return one
	 * @param baselineSnapshotId   immutable issuance baseline UUID
	 * @param masterInstallationId coordinator installation UUID
	 * @param contributorId        issued contributor identity
	 * @param originInstallationId contributor installation UUID
	 * @param archiveSha256        received archive SHA-256
	 * @param status               initial structural review status
	 * @param receivedAt           time receipt was recorded
	 */
	public record ReceivedPackage(UUID packageId, UUID assignmentId, int returnNumber, UUID previousPackageId,
			UUID baselineSnapshotId, UUID masterInstallationId, UUID contributorId, UUID originInstallationId,
			String archiveSha256, IntakeStatus status, Instant receivedAt) {

		/** Validates the received evidence. */
		public ReceivedPackage {
			Objects.requireNonNull(packageId, "packageId");
			Objects.requireNonNull(assignmentId, "assignmentId");
			Objects.requireNonNull(baselineSnapshotId, "baselineSnapshotId");
			Objects.requireNonNull(masterInstallationId, "masterInstallationId");
			Objects.requireNonNull(contributorId, "contributorId");
			Objects.requireNonNull(originInstallationId, "originInstallationId");
			Objects.requireNonNull(status, "status");
			Objects.requireNonNull(receivedAt, "receivedAt");
			if (returnNumber < 1 || (returnNumber == 1) != (previousPackageId == null)) {
				throw new IllegalArgumentException("Invalid received contribution return order");
			}
			if (archiveSha256 == null || !archiveSha256.matches("[0-9a-f]{64}")) {
				throw new IllegalArgumentException("Invalid received archive SHA-256");
			}
		}
	}

	/**
	 * One intake call result.
	 *
	 * @param received  immutable persisted return evidence
	 * @param duplicate true when identical evidence was already received
	 */
	public record Receipt(ReceivedPackage received, boolean duplicate) {

		/** Requires durable receipt evidence. */
		public Receipt {
			Objects.requireNonNull(received, "received");
		}
	}
}
