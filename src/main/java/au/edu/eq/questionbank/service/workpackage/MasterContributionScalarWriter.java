package au.edu.eq.questionbank.service.workpackage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
import au.edu.eq.questionbank.service.workpackage.MasterContributionDecisionLedger.RecordDecision;
import au.edu.eq.questionbank.service.workpackage.MasterContributionDecisionLedger.RecordKey;
import au.edu.eq.questionbank.service.workpackage.MasterContributionReviewPlanner.ReviewStatus;

/**
 * Applies reviewed Question and Answer changes within a caller-owned master
 * transaction.
 *
 * <p>
 * Supported operations include Question text and classification, Answer text,
 * verified PDF regions and ordered Question content parts referencing existing
 * images.
 *
 * <p>
 * New embedded images, output exclusions, Shared Context changes and other
 * unsupported dependencies are rejected. Every accepted mutation must match its
 * approved portable content snapshot.
 */
final class MasterContributionScalarWriter {

	private final SqliteDatabase database;

	MasterContributionScalarWriter(SqliteDatabase database) {
		this.database = Objects.requireNonNull(database, "database");
	}

	Applied apply(Connection connection, UUID reviewId, long examId, RecordDecision decision, ExchangeRecord proposed,
			Map<RecordKey, Long> newLocalIds, Map<RecordKey, UUID> newCanonicalIds, boolean allowPdfRegions,
			Map<RecordKey, RecordDecision> decisions, Map<RecordKey, ExchangeRecord> proposals,
			Map<RecordKey, PreparedImage> images) throws SQLException {
		Objects.requireNonNull(connection, "connection");
		EntityType type = EntityType.valueOf(decision.key().recordType());
		if (type != EntityType.QUESTION && type != EntityType.ANSWER) {
			throw new SQLException("Unsupported master scalar entity: " + type);
		}
		boolean create = decision.preliminaryStatus() == ReviewStatus.READY_NEW;
		if (!create && decision.preliminaryStatus() != ReviewStatus.READY_UPDATE) {
			throw new SQLException("Unsupported master scalar review status");
		}
		long currentId = -1;
		ExchangeRecord current = null;
		if (create) {
			if (decision.masterRevisionId() != null || decision.masterSha256() != null
					|| decision.baselineRevisionId() != null || new SqlitePortableIdentityRepository(database)
							.findByPortableId(connection, type, decision.key().recordId()).isPresent()) {
				throw new SQLException("New contribution record is no longer absent");
			}
		} else {
			currentId = new SqlitePortableIdentityRepository(database)
					.findByPortableId(connection, type, decision.key().recordId())
					.orElseThrow(() -> new SQLException("Updated master record is missing")).localId();
			current = new SqlitePortableContentRepository(database).snapshot(connection, type, currentId).record();
			requireScalarChange(type, current, proposed, allowPdfRegions);
		}
		String regionField = type == EntityType.QUESTION ? "question_regions" : "answer_regions";
		JsonNode proposedRegions = proposed.values().path(regionField);
		if (!proposedRegions.isArray()) {
			throw new SQLException("Missing PDF-region collection: " + regionField);
		}
		boolean regionsChanged = create ? !proposedRegions.isEmpty()
				: !current.values().path(regionField).equals(proposedRegions);
		boolean contentPartsChanged = false;
		boolean imagesChanged = false;
		if (type == EntityType.QUESTION) {
			JsonNode parts = proposed.values().path("question_content_parts");
			JsonNode questionImages = proposed.values().path("question_images");
			if (!parts.isArray() || !questionImages.isArray()) {
				throw new SQLException("Missing Question content collection");
			}
			contentPartsChanged = create ? !parts.isEmpty()
					: !current.values().path("question_content_parts").equals(parts);
			imagesChanged = create ? !questionImages.isEmpty()
					: !current.values().path("question_images").equals(questionImages);
		}
		if ((regionsChanged || contentPartsChanged || imagesChanged) && !allowPdfRegions) {
			throw new SQLException("PDF regions, content parts and embedded images "
					+ "require the verified archive application path");
		}
		permit(connection, reviewId, decision, examId, create);
		long localId;
		try {
			if (create) {
				localId = type == EntityType.QUESTION ? insertQuestion(connection, proposed, newLocalIds)
						: insertAnswer(connection, proposed, newLocalIds, allowPdfRegions);
			} else {
				localId = currentId;
				if (type == EntityType.QUESTION) {
					updateQuestion(connection, currentId, proposed, newLocalIds);
				} else {
					updateAnswer(connection, currentId, proposed);
				}
			}
			if (regionsChanged || contentPartsChanged || imagesChanged) {
				new MasterContributionRegionWriter(database).replace(connection, reviewId, decision, examId, localId,
						proposed, decisions, proposals, images, newLocalIds, newCanonicalIds);
			}
		} finally {
			clearPermit(connection);
		}
		ExchangeRecord actual = new SqlitePortableContentRepository(database).snapshot(connection, type, localId)
				.record();
		JsonNode expected = expectedValues(proposed, newCanonicalIds);
		if (!actual.values().equals(expected)) {
			throw new SQLException("Applied " + type + " differs from approved contribution");
		}
		if (!create && actual.revisionId().equals(current.revisionId())) {
			throw new SQLException("Master record mutation did not advance revision");
		}
		return new Applied(localId, actual.recordId(), actual.revisionId());
	}

	private void clearPermit(Connection connection) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				DELETE FROM master_contribution_write_permits
				WHERE singleton_id = 1
				""")) {
			if (statement.executeUpdate() != 1) {
				throw new SQLException("Master scalar write permit was not released");
			}
		}
	}

	private JsonNode expectedValues(ExchangeRecord proposed, Map<RecordKey, UUID> canonicalIds) {
		ObjectNode expected = ((ObjectNode) proposed.values()).deepCopy();
		if ("ANSWER".equals(proposed.recordType())) {
			String original = expected.path("question_id").asText();
			UUID canonical = canonicalIds.get(new RecordKey("QUESTION", UUID.fromString(original)));
			if (canonical != null) {
				expected.put("question_id", canonical.toString());
			}
		}
		if ("QUESTION".equals(proposed.recordType())) {
			for (String field : List.of("source_question_id", "shared_context_id")) {
				JsonNode original = expected.get(field);
				if (original == null || original.isNull()) {
					continue;
				}
				String type = "source_question_id".equals(field) ? "SOURCE_QUESTION" : "SHARED_QUESTION_CONTEXT";
				UUID canonical = canonicalIds.get(new RecordKey(type, UUID.fromString(original.asText())));
				if (canonical != null) {
					expected.put(field, canonical.toString());
				}
			}
			for (JsonNode part : expected.path("question_content_parts")) {
				if (!"IMAGE".equals(part.path("content_type").asText())) {
					continue;
				}
				String original = part.path("image_id").asText();
				UUID canonical = canonicalIds.get(new RecordKey("QUESTION_IMAGE", UUID.fromString(original)));
				if (canonical != null) {
					((ObjectNode) part).put("image_id", canonical.toString());
				}
			}
		}
		return expected;
	}

	private long insertAnswer(Connection connection, ExchangeRecord proposed, Map<RecordKey, Long> newLocalIds,
			boolean allowPdfRegions) throws SQLException {
		JsonNode values = proposed.values();
		JsonNode text = values.get("answer_text");
		JsonNode regions = values.path("answer_regions");
		boolean hasText = text != null && text.isTextual() && !text.asText().isBlank();
		boolean hasRegions = regions.isArray() && !regions.isEmpty();
		if (!hasText && !(allowPdfRegions && hasRegions)) {
			throw new SQLException("New Answer requires text or verified PDF regions");
		}
		if (text != null && !text.isNull() && !text.isTextual()) {
			throw new SQLException("New Answer text has an invalid type");
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO answers (
				    question_id, answer_text
				)
				VALUES (?, ?)
				""")) {
			statement.setLong(1, reference(connection, EntityType.QUESTION, values.get("question_id"), newLocalIds));
			statement.setString(2, text == null || text.isNull() ? null : text.asText());
			if (statement.executeUpdate() != 1) {
				throw new SQLException("New Answer was not inserted");
			}
		}
		return lastInsertedId(connection);
	}

	private long insertQuestion(Connection connection, ExchangeRecord proposed, Map<RecordKey, Long> newLocalIds)
			throws SQLException {
		requireEmptyChildren(proposed, "question_output_exclusions");
		JsonNode values = proposed.values();
		long bookletId = reference(connection, EntityType.EXAM_BOOKLET, values.get("booklet_id"), newLocalIds);
		Long sourceId = sameBookletDependency(connection, EntityType.SOURCE_QUESTION, values.get("source_question_id"),
				newLocalIds, bookletId);
		Long contextId = sameBookletDependency(connection, EntityType.SHARED_QUESTION_CONTEXT,
				values.get("shared_context_id"), newLocalIds, bookletId);
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO questions (
				    booklet_id,
				    classification_node_id,
				    question_code,
				    question_text,
				    marks,
				    shared_context_capture_required,
				    source_question_id,
				    shared_context_id,
				    response_type,
				    source_capture_required
				) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""")) {
			statement.setLong(1, bookletId);
			statement.setLong(2, reference(connection, EntityType.CURRICULUM_NODE, values.get("classification_node_id"),
					newLocalIds));
			statement.setString(3, requiredText(values, "question_code"));
			statement.setString(4, requiredText(values, "question_text"));
			statement.setInt(5, requiredInt(values, "marks"));
			statement.setInt(6, requiredInt(values, "shared_context_capture_required"));
			statement.setObject(7, sourceId);
			statement.setObject(8, contextId);
			statement.setString(9, requiredText(values, "response_type"));
			statement.setInt(10, requiredInt(values, "source_capture_required"));
			if (statement.executeUpdate() != 1) {
				throw new SQLException("Reviewed Question insertion did not affect one row");
			}
		}
		return lastInsertedId(connection);
	}

	private long lastInsertedId(Connection connection) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("SELECT last_insert_rowid()");
				ResultSet result = statement.executeQuery()) {
			if (!result.next() || result.getLong(1) < 1) {
				throw new SQLException("Inserted contribution row has no SQLite ID");
			}
			return result.getLong(1);
		}
	}

	private void permit(Connection connection, UUID reviewId, RecordDecision decision, long examId, boolean create)
			throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO master_contribution_write_permits (
				    singleton_id,
				    review_id,
				    record_type,
				    record_id,
				    operation,
				    exam_local_id
				)
				VALUES (1, ?, ?, ?, ?, ?)
				""")) {
			statement.setString(1, reviewId.toString());
			statement.setString(2, decision.key().recordType());
			statement.setString(3, decision.key().recordId().toString());
			statement.setString(4, create ? "INSERT" : "UPDATE");
			statement.setLong(5, examId);
			if (statement.executeUpdate() != 1) {
				throw new SQLException("Master scalar permit was not acquired");
			}
		}
	}

	private long reference(Connection connection, EntityType type, JsonNode value, Map<RecordKey, Long> newLocalIds)
			throws SQLException {
		if (value == null || !value.isTextual()) {
			throw new SQLException("Missing portable dependency " + type);
		}
		UUID portableId;
		try {
			portableId = UUID.fromString(value.asText());
			if (!portableId.toString().equals(value.asText())) {
				throw new IllegalArgumentException("Noncanonical portable UUID");
			}
		} catch (IllegalArgumentException failure) {
			throw new SQLException("Invalid portable dependency " + type, failure);
		}
		Long newlyInserted = newLocalIds.get(new RecordKey(type.name(), portableId));
		if (newlyInserted != null) {
			return newlyInserted;
		}
		return new SqlitePortableIdentityRepository(database).findByPortableId(connection, type, portableId)
				.orElseThrow(() -> new SQLException("Unresolved master dependency: " + type + " " + portableId))
				.localId();
	}

	private int requiredInt(JsonNode values, String field) throws SQLException {
		JsonNode value = values.get(field);
		if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
			throw new SQLException("Invalid integer field in contribution: " + field);
		}
		return value.intValue();
	}

	private String requiredText(JsonNode values, String field) throws SQLException {
		JsonNode value = values.get(field);
		if (value == null || !value.isTextual()) {
			throw new SQLException("Invalid text field in contribution: " + field);
		}
		return value.asText();
	}

	private void requireEmptyChildren(ExchangeRecord proposed, String child) throws SQLException {
		JsonNode value = proposed.values().get(child);
		if (value == null || !value.isArray() || !value.isEmpty()) {
			throw new SQLException("Import of " + child + " requires a later asset/region batch");
		}
	}

	private void requireScalarChange(EntityType type, ExchangeRecord current, ExchangeRecord proposed,
			boolean allowPdfRegions) throws SQLException {
		if (!(current.values() instanceof ObjectNode before) || !(proposed.values() instanceof ObjectNode after)) {
			throw new SQLException("Reviewed scalar values must be portable JSON objects");
		}
		ObjectNode currentOther = before.deepCopy();
		ObjectNode proposedOther = after.deepCopy();
		if (type == EntityType.QUESTION) {

			// An existing non-null Source or Shared Context association
			// cannot be redirected to a different parent.
			for (String field : List.of("source_question_id", "shared_context_id")) {
				JsonNode original = before.path(field);
				JsonNode replacement = after.path(field);
				if (!original.equals(replacement) && !original.isNull()) {
					throw new SQLException("Contributor cannot replace an existing Question dependency");
				}
			}
			for (String field : List.of("question_text", "classification_node_id", "source_question_id",
					"shared_context_id")) {
				currentOther.remove(field);
				proposedOther.remove(field);
			}
			if (allowPdfRegions) {
				for (String field : List.of("question_regions", "question_images", "question_content_parts")) {
					currentOther.remove(field);
					proposedOther.remove(field);
				}
			}
		} else {
			currentOther.remove("answer_text");
			proposedOther.remove("answer_text");
			if (allowPdfRegions) {
				currentOther.remove("answer_regions");
				proposedOther.remove("answer_regions");
			}
		}
		if (!currentOther.equals(proposedOther) || before.equals(after)) {
			throw new SQLException(
					"Reviewed scalar change contains unsupported fields " + "or no permitted content change");
		}
	}

	private Long sameBookletDependency(Connection connection, EntityType type, JsonNode portableId,
			Map<RecordKey, Long> newLocalIds, long bookletId) throws SQLException {
		if (portableId == null || portableId.isNull()) {
			return null;
		}
		if (type != EntityType.SOURCE_QUESTION && type != EntityType.SHARED_QUESTION_CONTEXT) {
			throw new SQLException("Unsupported Question dependency type");
		}
		long localId = reference(connection, type, portableId, newLocalIds);
		String sql = type == EntityType.SOURCE_QUESTION ? "SELECT booklet_id FROM source_questions WHERE id = ?"
				: "SELECT booklet_id FROM shared_question_contexts WHERE id = ?";
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setLong(1, localId);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next() || result.getLong(1) != bookletId || result.next()) {
					throw new SQLException("Question dependency belongs to another Booklet");
				}
			}
		}
		return localId;
	}

	private void updateAnswer(Connection connection, long localId, ExchangeRecord proposed) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				UPDATE answers
				SET answer_text = ?
				WHERE id = ?
				""")) {
			JsonNode value = proposed.values().get("answer_text");
			statement.setString(1,
					value == null || value.isNull() ? null : requiredText(proposed.values(), "answer_text"));
			statement.setLong(2, localId);
			if (statement.executeUpdate() != 1) {
				throw new SQLException("Reviewed Answer update did not affect one row");
			}
		}
	}

	private void updateQuestion(Connection connection, long localId, ExchangeRecord proposed,
			Map<RecordKey, Long> newLocalIds) throws SQLException {
		JsonNode values = proposed.values();
		long bookletId = reference(connection, EntityType.EXAM_BOOKLET, values.get("booklet_id"), newLocalIds);
		Long sourceId = sameBookletDependency(connection, EntityType.SOURCE_QUESTION, values.get("source_question_id"),
				newLocalIds, bookletId);
		Long contextId = sameBookletDependency(connection, EntityType.SHARED_QUESTION_CONTEXT,
				values.get("shared_context_id"), newLocalIds, bookletId);
		try (PreparedStatement statement = connection.prepareStatement("""
				UPDATE questions
				SET question_text = ?,
				    classification_node_id = ?,
				    source_question_id = ?,
				    shared_context_id = ?
				WHERE id = ?
				  AND booklet_id = ?
				""")) {
			statement.setString(1, requiredText(values, "question_text"));
			statement.setLong(2, reference(connection, EntityType.CURRICULUM_NODE, values.get("classification_node_id"),
					newLocalIds));
			statement.setObject(3, sourceId);
			statement.setObject(4, contextId);
			statement.setLong(5, localId);
			statement.setLong(6, bookletId);
			if (statement.executeUpdate() != 1) {
				throw new SQLException("Reviewed Question update did not affect one row");
			}
		}
	}

	record Applied(long localId, UUID canonicalId, UUID revisionId) {
	}
}
