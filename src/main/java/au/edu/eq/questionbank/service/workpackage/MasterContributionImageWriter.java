package au.edu.eq.questionbank.service.workpackage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import au.edu.eq.questionbank.repository.identity.SqlitePortableContentRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.workpackage.MasterContributionApprovedImagePreparer.PreparedImage;
import au.edu.eq.questionbank.service.workpackage.MasterContributionDecisionLedger.Choice;
import au.edu.eq.questionbank.service.workpackage.MasterContributionDecisionLedger.RecordDecision;
import au.edu.eq.questionbank.service.workpackage.MasterContributionDecisionLedger.RecordKey;
import au.edu.eq.questionbank.service.workpackage.MasterContributionReviewPlanner.ReviewStatus;

/**
 * Applies approved new and existing Question PNG content.
 *
 * <p>
 * All writes use the caller's SQLite transaction and require an active reviewed
 * parent Question scalar and region permit. New images receive canonical master
 * identities; replacement retains the existing image identity and validates its
 * original revision and content digest.
 *
 * <p>
 * Ordinary locked-master image changes and baseline image deletion remain
 * prohibited. No managed filesystem asset is published.
 */
final class MasterContributionImageWriter {

	private static final byte[] PNG_SIGNATURE = { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A };
	private final SqliteDatabase database;

	MasterContributionImageWriter(SqliteDatabase database) {
		this.database = Objects.requireNonNull(database, "database");
	}

	void insertApproved(Connection connection, ExchangeRecord parent, long parentLocalId,
			Map<RecordKey, RecordDecision> decisions, Map<RecordKey, ExchangeRecord> proposals,
			Map<RecordKey, PreparedImage> prepared, Map<RecordKey, Long> localIds, Map<RecordKey, UUID> canonicalIds)
			throws SQLException {
		Objects.requireNonNull(connection, "connection");
		Objects.requireNonNull(parent, "parent");
		Objects.requireNonNull(decisions, "decisions");
		Objects.requireNonNull(proposals, "proposals");
		Objects.requireNonNull(prepared, "prepared");
		Objects.requireNonNull(localIds, "localIds");
		Objects.requireNonNull(canonicalIds, "canonicalIds");
		if (connection.getAutoCommit()) {
			throw new SQLException("Question image application requires an open transaction");
		}
		if (!"QUESTION".equals(parent.recordType()) || parentLocalId < 1) {
			throw new SQLException("Invalid approved parent Question");
		}
		var identities = new SqlitePortableIdentityRepository(database);
		var contents = new SqlitePortableContentRepository(database);
		UUID canonicalParent = identities.findByLocalId(connection, EntityType.QUESTION, parentLocalId)
				.orElseThrow(() -> new SQLException("Approved image parent has no master identity")).portableId();
		for (PreparedImage image : prepared.values().stream()
				.filter(value -> value.questionId().equals(parent.recordId()))
				.sorted(Comparator.comparing(value -> value.imageId().toString())).toList()) {
			RecordKey key = new RecordKey("QUESTION_IMAGE", image.imageId());
			RecordDecision decision = decisions.get(key);
			ExchangeRecord proposed = proposals.get(key);
			if (decision == null || proposed == null || decision.choice() != Choice.QUEUE_APPLY) {
				throw new SQLException("Question image lacks an approved application decision");
			}
			boolean create = decision.preliminaryStatus() == ReviewStatus.READY_NEW;
			if (!create && decision.preliminaryStatus() != ReviewStatus.READY_UPDATE) {
				throw new SQLException("Unsupported Question image review status");
			}
			JsonNode values = proposed.values();
			if (!image.proposedRevisionId().equals(decision.proposedRevisionId())
					|| !image.proposedRevisionId().equals(proposed.revisionId())
					|| !image.questionId().toString().equals(values.path("question_id").asText())
					|| !image.sha256().equals(values.path("image_png").asText())) {
				throw new SQLException("Prepared Question PNG differs from reviewed evidence");
			}
			byte[] bytes = image.pngBytes();
			if (bytes.length < PNG_SIGNATURE.length
					|| !Arrays.equals(Arrays.copyOf(bytes, PNG_SIGNATURE.length), PNG_SIGNATURE)
					|| !image.sha256().equals(digest(bytes))) {
				throw new SQLException("Prepared Question PNG failed final byte verification");
			}
			var existing = identities.findByPortableId(connection, EntityType.QUESTION_IMAGE, image.imageId());
			long localId;
			if (create) {
				if (existing.isPresent() || decision.baselineRevisionId() != null || decision.masterRevisionId() != null
						|| decision.masterSha256() != null) {
					throw new SQLException("New Question image collides with master identity");
				}
				try (PreparedStatement statement = connection.prepareStatement("""
						INSERT INTO question_images (
						    question_id, image_png
						) VALUES (?, ?)
						""")) {
					statement.setLong(1, parentLocalId);
					statement.setBytes(2, bytes);
					if (statement.executeUpdate() != 1) {
						throw new SQLException("Approved Question image was not inserted");
					}
				}
				try (Statement statement = connection.createStatement();
						ResultSet result = statement.executeQuery("SELECT last_insert_rowid()")) {
					if (!result.next()) {
						throw new SQLException("Inserted Question image has no local ID");
					}
					localId = result.getLong(1);
					if (localId < 1 || result.next()) {
						throw new SQLException("Invalid inserted Question image identity");
					}
				}
			} else {
				var identity = existing
						.orElseThrow(() -> new SQLException("Reviewed existing Question image is missing"));
				localId = identity.localId();
				ExchangeRecord before = contents.snapshot(connection, EntityType.QUESTION_IMAGE, localId).record();
				if (!before.revisionId().equals(decision.masterRevisionId())
						|| !before.revisionId().equals(decision.baselineRevisionId())
						|| !recordDigest(before).equals(decision.masterSha256())
						|| !canonicalParent.toString().equals(before.values().path("question_id").asText())) {
					throw new SQLException("Existing Question image diverged from reviewed baseline");
				}
				try (PreparedStatement statement = connection.prepareStatement("""
						UPDATE question_images
						SET image_png = ?
						WHERE id = ?
						  AND question_id = ?
						""")) {
					statement.setBytes(1, bytes);
					statement.setLong(2, localId);
					statement.setLong(3, parentLocalId);
					if (statement.executeUpdate() != 1) {
						throw new SQLException("Reviewed Question image replacement failed");
					}
				}
			}
			var canonical = identities.findByLocalId(connection, EntityType.QUESTION_IMAGE, localId)
					.orElseThrow(() -> new SQLException("Applied Question image has no portable identity"));
			ExchangeRecord actual = contents.snapshot(connection, EntityType.QUESTION_IMAGE, localId).record();
			ObjectNode expected = ((ObjectNode) proposed.values()).deepCopy();
			expected.put("question_id", canonicalParent.toString());
			if (!actual.recordId().equals(canonical.portableId()) || !actual.values().equals(expected)
					|| !image.sha256().equals(actual.values().path("image_png").asText())) {
				throw new SQLException("Applied Question image differs from approved content");
			}
			if (localIds.putIfAbsent(key, localId) != null
					|| canonicalIds.putIfAbsent(key, canonical.portableId()) != null) {
				throw new SQLException("Duplicate canonical Question image mapping");
			}
		}
	}

	private String digest(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException failure) {
			throw new IllegalStateException("SHA-256 is unavailable", failure);
		}
	}

	private String recordDigest(ExchangeRecord record) throws SQLException {
		try {
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			new ExamWorkJsonlCodec().writeRecords(List.of(record), output);
			return digest(output.toByteArray());
		} catch (IOException failure) {
			throw new SQLException("Cannot verify reviewed Question image digest", failure);
		}
	}
}
