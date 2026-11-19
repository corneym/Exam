package au.edu.eq.questionbank.service.workpackage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import au.edu.eq.questionbank.ApplicationVersion;
import au.edu.eq.questionbank.repository.identity.SqlitePortableContentRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.workpackage.ExamWorkArchiveWriter.EntrySource;
import au.edu.eq.questionbank.service.workpackage.ExamWorkManifest.Entry;
import au.edu.eq.questionbank.service.workpackage.ExamWorkManifest.EntryRole;
import au.edu.eq.questionbank.service.workpackage.ExamWorkManifest.Scope;
import au.edu.eq.questionbank.service.workpackage.ExamWorkManifest.ScopeLevel;
import au.edu.eq.questionbank.service.workpackage.MasterContributionApplicationCoordinator.ApplicationResult;
import au.edu.eq.questionbank.service.workpackage.MasterContributionApplicationCoordinator.Outcome;
import au.edu.eq.questionbank.service.workpackage.MasterContributionApplicationCoordinator.RecordOutcome;
import au.edu.eq.questionbank.service.workpackage.MasterContributionDecisionLedger.RecordDecision;
import au.edu.eq.questionbank.service.workpackage.MasterContributionDecisionLedger.RecordKey;
import au.edu.eq.questionbank.service.workpackage.MasterContributionDecisionLedger.RecordedReview;

/**
 * Exports immutable, validated import receipts for reconciled contributor
 * returns.
 *
 * <p>
 * Receipt contents come exclusively from retained intake, review, application,
 * canonical-mapping and assignment-closure evidence. Exporting a receipt does
 * not modify the master database or managed Subject assets.
 *
 * <p>
 * A receipt package identity is derived from the immutable review UUID.
 * Repeated exports of the same review therefore have identical logical content
 * and package identity.
 *
 * <p>
 * A receipt is evidence for subsequent contributor-side reconciliation. This
 * exporter does not itself modify contributor data or rebase edits.
 */
public final class MasterContributionReceiptExporter {

	private static final String INDEX_PATH = ExamWorkAssetIndex.INDEX_PATH;
	private static final String RECEIPT_PATH = "receipts/import-results.jsonl";
	private static final String MASTER_STATE_PATH = "records/accepted-master-state.jsonl";
	private final SqliteDatabase database;

	/**
	 * Creates a receipt exporter for the authoritative coordinator.
	 *
	 * @param database initialised master database
	 * @throws NullPointerException if database is null
	 */
	public MasterContributionReceiptExporter(SqliteDatabase database) {
		this.database = Objects.requireNonNull(database, "database");
	}

	/**
	 * Publishes a validated IMPORT_RECEIPT containing terminal decisions and the
	 * current canonical master values of accepted records.
	 *
	 * <p>
	 * Each accepted record must still have precisely the revision committed by the
	 * reviewed application. If master content changed after application, export
	 * fails rather than attributing the later change to the return.
	 *
	 * <p>
	 * The receipt is not cryptographically authenticated by its internal checksums.
	 * Contributors must independently confirm the archive digest before using its
	 * contents as authoritative evidence.
	 *
	 * @param reviewId    completed coordinator review UUID
	 * @param destination new receipt archive destination
	 * @return validated receipt manifest
	 * @throws SQLException             if application evidence, canonical mappings
	 *                                  or current master revisions are inconsistent
	 * @throws IOException              if serialisation, validation or publication
	 *                                  fails
	 * @throws IllegalArgumentException if the destination suffix is invalid
	 * @throws NullPointerException     if an argument is null
	 */
	public ExamWorkManifest export(UUID reviewId, Path destination) throws SQLException, IOException {
		Objects.requireNonNull(reviewId, "reviewId");
		Objects.requireNonNull(destination, "destination");
		Path target = requireDestination(destination);
		ApplicationResult application = new MasterContributionApplicationCoordinator(database).find(reviewId)
				.orElseThrow(() -> new SQLException("Receipt requires a completed application"));
		RecordedReview review = new MasterContributionDecisionLedger(database).find(reviewId)
				.orElseThrow(() -> new SQLException("Receipt review evidence is missing"));
		if (!review.packageId().equals(application.packageId())) {
			throw new SQLException("Review and application package identities differ");
		}
		var received = new MasterContributionIntake(database).find(application.packageId())
				.orElseThrow(() -> new SQLException("Received return evidence is missing"));
		var assignment = new WorkAssignmentLedger(database).find(received.assignmentId())
				.orElseThrow(() -> new SQLException("Receipt assignment is missing"));
		if (assignment.state() != WorkAssignmentLedger.State.COMPLETE
				|| !received.packageId().equals(application.packageId())
				|| !received.assignmentId().equals(assignment.assignmentId())
				|| !received.baselineSnapshotId().equals(assignment.baselineSnapshotId())
				|| !received.masterInstallationId().equals(assignment.masterInstallationId())
				|| !received.contributorId().equals(assignment.contributorId())) {
			throw new SQLException("Receipt assignment evidence is inconsistent");
		}
		requireReconciledClosure(application, assignment.assignmentId());
		Map<RecordKey, RecordDecision> decisions = new HashMap<>();
		for (RecordDecision decision : review.decisions()) {
			if (decisions.putIfAbsent(decision.key(), decision) != null) {
				throw new SQLException("Duplicate receipt review decision");
			}
		}
		Map<RecordKey, Mapping> mappings = readMappings(reviewId);
		if (application.outcomes().size() != decisions.size()) {
			throw new SQLException("Receipt outcomes do not cover the entire review");
		}

		// Read complete canonical master values before consuming the
		// mapping collection. A changed revision must stop export.
		List<ExchangeRecord> acceptedMasterState = currentAcceptedMasterState(application.outcomes(), mappings);
		UUID receiptId = UUID
				.nameUUIDFromBytes(("examwork-import-receipt-v1:" + reviewId).getBytes(StandardCharsets.UTF_8));
		List<ExchangeRecord> records = new ArrayList<>();
		ObjectNode context = JsonNodeFactory.instance.objectNode();
		context.put("receipt_package_id", receiptId.toString());
		context.put("review_id", reviewId.toString());
		context.put("contribution_package_id", application.packageId().toString());
		context.put("assignment_id", assignment.assignmentId().toString());
		context.put("baseline_snapshot_id", assignment.baselineSnapshotId().toString());
		context.put("master_installation_id", assignment.masterInstallationId().toString());
		context.put("contributor_id", assignment.contributorId().toString());
		context.put("contributor_installation_id", received.originInstallationId().toString());
		context.put("completed_at", application.completedAt().toString());
		context.put("record_count", application.outcomes().size());
		records.add(new ExchangeRecord(ExchangeRecord.CURRENT_RECORD_VERSION, "IMPORT_RECEIPT_CONTEXT", receiptId,
				receiptId, null, context));
		int mapped = 0;
		for (RecordOutcome outcome : application.outcomes()) {
			RecordKey key = outcome.key();
			RecordDecision decision = decisions.remove(key);
			if (decision == null) {
				throw new SQLException("Receipt outcome lacks a recorded review decision");
			}
			Mapping mapping = mappings.remove(key);
			if (outcome.outcome() == Outcome.REJECTED) {
				if (mapping != null || outcome.resultingRevisionId() != null) {
					throw new SQLException("Rejected receipt record has master mapping");
				}
			} else {
				if (mapping == null || outcome.resultingRevisionId() == null) {
					throw new SQLException("Accepted receipt record lacks master mapping");
				}
				mapped++;
			}
			ObjectNode values = JsonNodeFactory.instance.objectNode();
			values.put("review_id", reviewId.toString());
			values.put("record_type", key.recordType());
			values.put("record_id", key.recordId().toString());
			values.put("outcome", outcome.outcome().name());
			values.put("review_status", decision.preliminaryStatus().name());
			values.put("decision", decision.choice().name());
			values.put("proposed_revision_id", decision.proposedRevisionId().toString());
			if (mapping == null) {
				values.putNull("canonical_record_id");
			} else {
				values.put("canonical_record_id", mapping.canonicalId().toString());
			}
			if (outcome.resultingRevisionId() == null) {
				values.putNull("resulting_revision_id");
			} else {
				values.put("resulting_revision_id", outcome.resultingRevisionId().toString());
			}
			records.add(new ExchangeRecord(ExchangeRecord.CURRENT_RECORD_VERSION, "IMPORT_RECEIPT_" + key.recordType(),
					key.recordId(), outcome.resultingRevisionId(), null, values));
		}
		if (!decisions.isEmpty() || !mappings.isEmpty() || mapped != countMapped(application.outcomes())
				|| mapped != acceptedMasterState.size()) {
			throw new SQLException("Receipt decision or master mapping coverage is incomplete");
		}
		ExamWorkJsonlCodec codec = new ExamWorkJsonlCodec();
		ByteArrayOutputStream receiptOutput = new ByteArrayOutputStream();
		codec.writeRecords(records, receiptOutput);
		ByteArrayOutputStream masterStateOutput = new ByteArrayOutputStream();
		codec.writeRecords(acceptedMasterState, masterStateOutput);
		ByteArrayOutputStream indexOutput = new ByteArrayOutputStream();

		// Accepted records currently refer only to existing master PDF
		// dependencies. The receipt does not publish binary assets.
		codec.writeAssetIndex(new ExamWorkAssetIndex(List.of()), indexOutput);
		byte[] receiptBytes = receiptOutput.toByteArray();
		byte[] masterStateBytes = masterStateOutput.toByteArray();
		byte[] indexBytes = indexOutput.toByteArray();
		List<Entry> entries = List.of(entry(INDEX_PATH, EntryRole.INDEX, indexBytes),
				entry(RECEIPT_PATH, EntryRole.RECEIPT, receiptBytes),
				entry(MASTER_STATE_PATH, EntryRole.RECORDS, masterStateBytes));
		String version = ApplicationVersion.current();
		int schemaVersion = database.schemaVersion();
		ExamWorkManifest manifest = new ExamWorkManifest(ExamWorkManifest.CURRENT_FORMAT_VERSION, receiptId,
				ExamWorkPackageType.IMPORT_RECEIPT, application.completedAt(), assignment.masterInstallationId(),
				assignment.masterInstallationId(), assignment.subjectPortableId(),
				subjectName(assignment.subjectPortableId()), assignment.assignmentId(), assignment.baselineSnapshotId(),
				version, version, schemaVersion, schemaVersion,
				new Scope(ScopeLevel.EXAMS, List.of(assignment.examPortableId()), List.of()), entries);
		Map<String, EntrySource> sources = Map.of(INDEX_PATH, () -> new ByteArrayInputStream(indexBytes), RECEIPT_PATH,
				() -> new ByteArrayInputStream(receiptBytes), MASTER_STATE_PATH,
				() -> new ByteArrayInputStream(masterStateBytes));
		Path temporary = Files.createTempFile(target.getParent(), ".examwork-receipt-", ".examwork");
		try {
			try (OutputStream output = Files.newOutputStream(temporary, StandardOpenOption.TRUNCATE_EXISTING,
					StandardOpenOption.WRITE)) {
				new ExamWorkArchiveWriter().write(manifest, sources, output);
			}
			ExamWorkManifest validated = new ExamWorkArchiveValidator().validate(temporary, version, schemaVersion);
			if (!manifest.equals(validated)) {
				throw new PackageFormatException("Validated receipt differs from published evidence");
			}
			if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
				throw new IOException("Receipt destination already exists: " + target);
			}
			try {
				Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException failure) {
				throw new IOException("Atomic receipt publication is unavailable", failure);
			}
			return validated;
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	private int countMapped(List<RecordOutcome> outcomes) {
		int count = 0;
		for (RecordOutcome outcome : outcomes) {
			if (outcome.outcome() != Outcome.REJECTED) {
				count++;
			}
		}
		return count;
	}

	private List<ExchangeRecord> currentAcceptedMasterState(List<RecordOutcome> outcomes,
			Map<RecordKey, Mapping> mappings) throws SQLException {
		List<ExchangeRecord> records = new ArrayList<>();
		SqlitePortableContentRepository content = new SqlitePortableContentRepository(database);
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				for (RecordOutcome outcome : outcomes) {
					if (outcome.outcome() == Outcome.REJECTED) {
						continue;
					}
					RecordKey key = outcome.key();
					Mapping mapping = mappings.get(key);
					if (mapping == null || outcome.resultingRevisionId() == null) {
						throw new SQLException("Accepted record has no master mapping: " + key);
					}
					EntityType type;
					try {
						type = EntityType.valueOf(key.recordType());
					} catch (IllegalArgumentException failure) {
						throw new SQLException("Unsupported receipt master entity: " + key.recordType(), failure);
					}
					if (type != EntityType.QUESTION && type != EntityType.ANSWER) {
						throw new SQLException("Unsupported accepted master state: " + type);
					}
					ExchangeRecord current = content.snapshot(connection, type, mapping.localId()).record();

					// A later master edit must not be represented as
					// the earlier accepted contributor revision.
					if (!current.recordId().equals(mapping.canonicalId())
							|| !current.revisionId().equals(outcome.resultingRevisionId())) {
						throw new SQLException("Master record changed after contribution " + "application: " + key);
					}
					records.add(current);
				}
				connection.commit();
				return List.copyOf(records);
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

	private String digest(byte[] contents) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contents));
		} catch (NoSuchAlgorithmException failure) {
			throw new IllegalStateException("SHA-256 is unavailable", failure);
		}
	}

	private Entry entry(String path, EntryRole role, byte[] contents) {
		return new Entry(path, role, contents.length, digest(contents));
	}

	private Map<RecordKey, Mapping> readMappings(UUID reviewId) throws SQLException {
		Map<RecordKey, Mapping> mappings = new HashMap<>();
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT record_type,
						       record_id,
						       canonical_record_id,
						       local_id
						FROM contribution_application_entity_mappings
						WHERE review_id = ?
						""")) {
			statement.setString(1, reviewId.toString());
			try (ResultSet result = statement.executeQuery()) {
				while (result.next()) {
					RecordKey key = new RecordKey(result.getString("record_type"),
							UUID.fromString(result.getString("record_id")));
					Mapping mapping = new Mapping(UUID.fromString(result.getString("canonical_record_id")),
							result.getLong("local_id"));
					if (mapping.localId() < 1 || mappings.putIfAbsent(key, mapping) != null) {
						throw new SQLException("Invalid or duplicate canonical mapping");
					}
				}
			}
		}
		return mappings;
	}

	private Path requireDestination(Path destination) throws IOException {
		Path target = destination.toAbsolutePath().normalize();
		if (target.getFileName() == null || !target.getFileName().toString().endsWith(".examwork")) {
			throw new IllegalArgumentException("Receipt destination must end in .examwork");
		}
		Path parent = target.getParent();
		if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Receipt destination directory is unavailable");
		}
		if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Receipt destination already exists: " + target);
		}
		return target;
	}

	private void requireReconciledClosure(ApplicationResult application, UUID assignmentId) throws SQLException {
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT closure.closed_at
						FROM exam_work_assignment_closures closure
						JOIN exam_work_assignment_states state
						  ON state.assignment_id =
						     closure.assignment_id
						WHERE closure.assignment_id = ?
						  AND closure.return_package_id = ?
						  AND closure.closure_kind =
						      'RECONCILED_RETURN'
						  AND closure.closed_state = 'COMPLETE'
						  AND state.state = 'COMPLETE'
						""")) {
			statement.setString(1, assignmentId.toString());
			statement.setString(2, application.packageId().toString());
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next() || !application.completedAt().toString().equals(result.getString("closed_at"))
						|| result.next()) {
					throw new SQLException("Receipt requires an exact reconciled closure");
				}
			}
		}
	}

	private String subjectName(UUID subjectId) throws SQLException {
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT subject.subject_name
						FROM subjects subject
						JOIN portable_entity_identities identity
						  ON identity.entity_type = 'SUBJECT'
						 AND identity.local_id = subject.id
						 AND identity.retired = 0
						WHERE identity.portable_id = ?
						""")) {
			statement.setString(1, subjectId.toString());
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new SQLException("Receipt Subject is missing");
				}
				String name = result.getString(1);
				if (result.next()) {
					throw new SQLException("Duplicate receipt Subject");
				}
				return name;
			}
		}
	}

	private record Mapping(UUID canonicalId, long localId) {
	}
}
