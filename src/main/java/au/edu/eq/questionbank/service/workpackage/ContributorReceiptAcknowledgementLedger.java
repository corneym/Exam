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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

/**
 * Persists independently authenticated contributor import receipts.
 *
 * <p>
 * Acknowledgement is append-only and idempotent. The retained receipt outcomes
 * and accepted canonical master records are a separate known-master
 * observation; they never replace contributor working records, portable
 * identities or the immutable issued baseline.
 *
 * <p>
 * The caller must obtain the expected archive SHA-256 from a trusted,
 * independent coordinator channel. Archive integrity alone is not proof of
 * coordinator authority.
 */
public final class ContributorReceiptAcknowledgementLedger {

	private final SqliteDatabase database;

	/**
	 * Creates an acknowledgement ledger for a contributor installation.
	 *
	 * @param database initialised contributor database
	 * @throws NullPointerException if database is null
	 */
	public ContributorReceiptAcknowledgementLedger(SqliteDatabase database) {
		this.database = Objects.requireNonNull(database, "database");
	}

	/**
	 * Authenticates and durably acknowledges a completed master receipt.
	 *
	 * <p>
	 * Replays of the same authenticated receipt return the original
	 * acknowledgement. Reuse of a return identity with another receipt is rejected.
	 * No contributor domain record or assignment baseline is changed, including
	 * when newer local work exists.
	 *
	 * @param archive        original IMPORT_RECEIPT archive
	 * @param expectedSha256 independently confirmed archive SHA-256
	 * @return newly recorded or previously retained acknowledgement
	 * @throws IOException          if receipt authentication or encoding fails
	 * @throws SQLException         if lineage, uniqueness or persistence fails
	 * @throws NullPointerException if an argument is null
	 */
	public Acknowledgement acknowledge(Path archive, String expectedSha256) throws IOException, SQLException {
		Objects.requireNonNull(archive, "archive");
		Objects.requireNonNull(expectedSha256, "expectedSha256");
		ContributorReceiptInspector.Inspection inspected = new ContributorReceiptInspector(database)
				.inspectTrusted(archive, expectedSha256);
		ExamWorkManifest manifest = inspected.manifest();
		byte[] outcomeBytes = encode(inspected.outcomes());
		byte[] masterBytes = encode(inspected.acceptedMasterRecords());
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				Optional<Acknowledgement> existing = find(connection, manifest.packageId());
				if (existing.isPresent()) {
					Acknowledgement previous = existing.orElseThrow();

					// A replay must be identical to the original
					// authenticated and recorded evidence.
					if (!previous.returnPackageId().equals(inspected.returnPackageId())
							|| !previous.assignmentId().equals(manifest.assignmentId())
							|| !previous.reviewId().equals(inspected.reviewId())
							|| !previous.masterInstallationId().equals(manifest.originInstallationId())
							|| !previous.archiveSha256().equals(inspected.archiveSha256())
							|| !previous.outcomes().equals(inspected.outcomes())
							|| !previous.acceptedMasterRecords().equals(inspected.acceptedMasterRecords())) {
						throw new SQLException("Receipt identity was reused with " + "different evidence");
					}
					connection.commit();
					return previous;
				}
				Instant acknowledgedAt = Instant.now();
				try (PreparedStatement insert = connection.prepareStatement("""
						INSERT INTO
						    contributor_receipt_acknowledgements (
						        receipt_package_id,
						        return_package_id,
						        assignment_id,
						        review_id,
						        master_installation_id,
						        archive_sha256,
						        outcomes_jsonl,
						        master_records_jsonl,
						        acknowledged_at
						    )
						VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
						""")) {
					insert.setString(1, manifest.packageId().toString());
					insert.setString(2, inspected.returnPackageId().toString());
					insert.setString(3, manifest.assignmentId().toString());
					insert.setString(4, inspected.reviewId().toString());
					insert.setString(5, manifest.originInstallationId().toString());
					insert.setString(6, inspected.archiveSha256());
					insert.setBytes(7, outcomeBytes);
					insert.setBytes(8, masterBytes);
					insert.setString(9, acknowledgedAt.toString());
					if (insert.executeUpdate() != 1) {
						throw new SQLException("Receipt acknowledgement was not recorded");
					}
				}
				Acknowledgement stored = find(connection, manifest.packageId())
						.orElseThrow(() -> new SQLException("Acknowledgement was not retained"));
				connection.commit();
				return stored;
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

	/**
	 * Reads previously authenticated receipt evidence without requiring the
	 * original receipt archive.
	 *
	 * @param receiptPackageId immutable receipt package UUID
	 * @return retained acknowledgement, or empty if not acknowledged
	 * @throws IOException          if retained canonical JSONL cannot be decoded
	 * @throws SQLException         if database reading fails
	 * @throws NullPointerException if receiptPackageId is null
	 */
	public Optional<Acknowledgement> find(UUID receiptPackageId) throws SQLException, IOException {
		Objects.requireNonNull(receiptPackageId, "receiptPackageId");
		try (Connection connection = database.openConnection()) {
			return find(connection, receiptPackageId);
		}
	}

	private byte[] encode(List<ExchangeRecord> records) throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		new ExamWorkJsonlCodec().writeRecords(records, output);
		return output.toByteArray();
	}

	private Optional<Acknowledgement> find(Connection connection, UUID receiptPackageId)
			throws SQLException, IOException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT receipt_package_id,
				       return_package_id,
				       assignment_id,
				       review_id,
				       master_installation_id,
				       archive_sha256,
				       outcomes_jsonl,
				       master_records_jsonl,
				       acknowledged_at
				FROM contributor_receipt_acknowledgements
				WHERE receipt_package_id = ?
				""")) {
			statement.setString(1, receiptPackageId.toString());
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					return Optional.empty();
				}
				Acknowledgement stored = read(result);
				if (result.next()) {
					throw new SQLException("Duplicate receipt acknowledgement identity");
				}
				return Optional.of(stored);
			}
		}
	}

	private Acknowledgement read(ResultSet result) throws SQLException, IOException {
		try {
			ExamWorkJsonlCodec codec = new ExamWorkJsonlCodec();
			byte[] outcomes = result.getBytes("outcomes_jsonl");
			byte[] masterRecords = result.getBytes("master_records_jsonl");
			if (outcomes == null || masterRecords == null) {
				throw new SQLException("Retained receipt evidence is incomplete");
			}
			return new Acknowledgement(UUID.fromString(result.getString("receipt_package_id")),
					UUID.fromString(result.getString("return_package_id")),
					UUID.fromString(result.getString("assignment_id")), UUID.fromString(result.getString("review_id")),
					UUID.fromString(result.getString("master_installation_id")), result.getString("archive_sha256"),
					Instant.parse(result.getString("acknowledged_at")),
					codec.readRecords(new ByteArrayInputStream(outcomes)),
					codec.readRecords(new ByteArrayInputStream(masterRecords)));
		} catch (IllegalArgumentException failure) {
			throw new SQLException("Invalid retained receipt acknowledgement", failure);
		}
	}

	/**
	 * Immutable acknowledgement of one coordinator reconciliation.
	 *
	 * @param receiptPackageId      authenticated receipt UUID
	 * @param returnPackageId       acknowledged contributor return UUID
	 * @param assignmentId          original assignment UUID
	 * @param reviewId              committed coordinator review UUID
	 * @param masterInstallationId  originating coordinator UUID
	 * @param archiveSha256         authenticated receipt archive SHA-256
	 * @param acknowledgedAt        time acknowledgement was first recorded
	 * @param outcomes              retained terminal per-record outcomes
	 * @param acceptedMasterRecords retained accepted canonical master records
	 */
	public record Acknowledgement(UUID receiptPackageId, UUID returnPackageId, UUID assignmentId, UUID reviewId,
			UUID masterInstallationId, String archiveSha256, Instant acknowledgedAt, List<ExchangeRecord> outcomes,
			List<ExchangeRecord> acceptedMasterRecords) {

		/**
		 * Validates the acknowledgement and retains immutable lists.
		 *
		 * @throws NullPointerException     if required evidence is missing
		 * @throws IllegalArgumentException if the archive digest is invalid
		 */
		public Acknowledgement {
			Objects.requireNonNull(receiptPackageId, "receiptPackageId");
			Objects.requireNonNull(returnPackageId, "returnPackageId");
			Objects.requireNonNull(assignmentId, "assignmentId");
			Objects.requireNonNull(reviewId, "reviewId");
			Objects.requireNonNull(masterInstallationId, "masterInstallationId");
			Objects.requireNonNull(acknowledgedAt, "acknowledgedAt");
			if (archiveSha256 == null || !archiveSha256.matches("[0-9a-f]{64}")) {
				throw new IllegalArgumentException("Invalid acknowledged receipt SHA-256");
			}
			outcomes = List.copyOf(Objects.requireNonNull(outcomes, "outcomes"));
			acceptedMasterRecords = List.copyOf(Objects.requireNonNull(acceptedMasterRecords, "acceptedMasterRecords"));
		}
	}
}
