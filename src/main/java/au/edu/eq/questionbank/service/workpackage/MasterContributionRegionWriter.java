package au.edu.eq.questionbank.service.workpackage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

import au.edu.eq.questionbank.model.SharedQuestionContextRegion;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.workpackage.MasterContributionApprovedImagePreparer.PreparedImage;
import au.edu.eq.questionbank.service.workpackage.MasterContributionDecisionLedger.RecordDecision;
import au.edu.eq.questionbank.service.workpackage.MasterContributionDecisionLedger.RecordKey;

/**
 * Applies approved Question and Answer PDF regions and ordered Question content
 * parts within a caller-owned SQLite transaction.
 *
 * <p>
 * Mutations require the existing owner-specific master region permit. Question
 * image references are resolved by portable UUID to existing master-local image
 * identities. This writer does not create, modify or publish image bytes or
 * managed source PDFs.
 *
 * <p>
 * The caller must have verified the original contribution archive and source
 * PDF dependencies while holding the managed-asset publication lock.
 */
final class MasterContributionRegionWriter {

	private final SqliteDatabase database;

	MasterContributionRegionWriter(SqliteDatabase database) {
		this.database = Objects.requireNonNull(database, "database");
	}

	void replace(Connection connection, UUID reviewId, RecordDecision decision, long examId, long ownerLocalId,
			ExchangeRecord proposed, Map<RecordKey, RecordDecision> decisions, Map<RecordKey, ExchangeRecord> proposals,
			Map<RecordKey, PreparedImage> images, Map<RecordKey, Long> localIds, Map<RecordKey, UUID> canonicalIds)
			throws SQLException {
		Objects.requireNonNull(connection, "connection");
		Objects.requireNonNull(reviewId, "reviewId");
		Objects.requireNonNull(decision, "decision");
		Objects.requireNonNull(proposed, "proposed");
		String type = decision.key().recordType();
		if (!type.equals("QUESTION") && !type.equals("ANSWER")) {
			throw new SQLException("Only Question and Answer content is supported");
		}
		List<Region> regions = validate(connection, type, examId, ownerLocalId, proposed);
		acquirePermit(connection, reviewId, decision, examId, ownerLocalId);
		try {
			List<ContentPart> contentParts = List.of();
			if (type.equals("QUESTION")) {

				// Insert approved new images while the exact Question
				// region permit is held. This permission cannot be
				// reused for another Question or outside the review.
				new MasterContributionImageWriter(database).insertApproved(connection, proposed, ownerLocalId,
						decisions, proposals, images, localIds, canonicalIds);

				// Resolve new image UUIDs after insertion but before
				// removing any authoritative content-part rows.
				contentParts = validateQuestionParts(connection, ownerLocalId, proposed, regions, localIds);
				deleteQuestionParts(connection, ownerLocalId);
			}
			replaceRows(connection, type, ownerLocalId, regions);
			if (type.equals("QUESTION")) {
				insertQuestionParts(connection, ownerLocalId, contentParts);
			}
		} finally {
			releasePermit(connection);
		}
	}

	private void acquirePermit(Connection connection, UUID reviewId, RecordDecision decision, long examId,
			long ownerLocalId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO master_contribution_region_permits (
				    singleton_id,
				    review_id,
				    record_type,
				    record_id,
				    exam_local_id,
				    owner_local_id
				)
				VALUES (1, ?, ?, ?, ?, ?)
				""")) {
			statement.setString(1, reviewId.toString());
			statement.setString(2, decision.key().recordType());
			statement.setString(3, decision.key().recordId().toString());
			statement.setLong(4, examId);
			statement.setLong(5, ownerLocalId);
			if (statement.executeUpdate() != 1) {
				throw new SQLException("Master PDF-region permit was not acquired");
			}
		}
	}

	private void deleteQuestionParts(Connection connection, long questionLocalId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				DELETE FROM question_content_parts
				WHERE question_id = ?
				""")) {
			statement.setLong(1, questionLocalId);
			statement.executeUpdate();
		}
	}

	private void insertQuestionParts(Connection connection, long questionLocalId, List<ContentPart> parts)
			throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO question_content_parts (
				    question_id,
				    content_order,
				    content_type,
				    region_order,
				    image_id
				)
				VALUES (?, ?, ?, ?, ?)
				""")) {
			for (ContentPart part : parts) {
				statement.setLong(1, questionLocalId);
				statement.setInt(2, part.contentOrder());
				statement.setString(3, part.contentType());
				if (part.regionOrder() == null) {
					statement.setNull(4, Types.INTEGER);
				} else {
					statement.setInt(4, part.regionOrder());
				}
				if (part.imageLocalId() == null) {
					statement.setNull(5, Types.INTEGER);
				} else {
					statement.setLong(5, part.imageLocalId());
				}
				if (statement.executeUpdate() != 1) {
					throw new SQLException("Question content part was not inserted");
				}
			}
		}
	}

	private long reference(Connection connection, EntityType type, JsonNode value) throws SQLException {
		if (value == null || !value.isTextual()) {
			throw new SQLException("PDF-region source reference must be a UUID");
		}
		UUID portableId;
		try {
			portableId = UUID.fromString(value.asText());
			if (!portableId.toString().equals(value.asText())) {
				throw new IllegalArgumentException("Noncanonical UUID");
			}
		} catch (IllegalArgumentException failure) {
			throw new SQLException("Invalid PDF-region source UUID", failure);
		}
		return new SqlitePortableIdentityRepository(database).findByPortableId(connection, type, portableId)
				.orElseThrow(() -> new SQLException("PDF-region source is missing: " + type + " " + portableId))
				.localId();
	}

	private long referenceImage(Connection connection, JsonNode value, Map<RecordKey, Long> localIds)
			throws SQLException {
		if (value == null || !value.isTextual()) {
			throw new SQLException("Question image reference must be a portable UUID");
		}
		UUID portableId;
		try {
			portableId = UUID.fromString(value.asText());
			if (!portableId.toString().equals(value.asText())) {
				throw new IllegalArgumentException("Noncanonical image UUID");
			}
		} catch (IllegalArgumentException failure) {
			throw new SQLException("Invalid Question image portable UUID", failure);
		}
		Long inserted = localIds.get(new RecordKey("QUESTION_IMAGE", portableId));
		if (inserted != null) {
			return inserted;
		}
		return reference(connection, EntityType.QUESTION_IMAGE, value);
	}

	private void releasePermit(Connection connection) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				DELETE FROM master_contribution_region_permits
				WHERE singleton_id = 1
				""")) {
			if (statement.executeUpdate() != 1) {
				throw new SQLException("Master PDF-region permit was not released");
			}
		}
	}

	private void replaceRows(Connection connection, String type, long ownerLocalId, List<Region> regions)
			throws SQLException {
		String ownerColumn = type.equals("QUESTION") ? "question_id" : "answer_id";
		String table = type.equals("QUESTION") ? "question_regions" : "answer_regions";

		// Both table names and owner columns are fixed constants,
		// never values obtained from the returned archive.
		String deleteSql = "DELETE FROM " + table + " WHERE " + ownerColumn + " = ?";
		String insertSql = type.equals("QUESTION") ? """
				INSERT INTO question_regions (
				    question_id, region_order, booklet_id,
				    page_number, x, y, width, height
				)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?)
				""" : """
				INSERT INTO answer_regions (
				    answer_id, region_order, answer_file_id,
				    page_number, x, y, width, height
				)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?)
				""";
		try (PreparedStatement delete = connection.prepareStatement(deleteSql);
				PreparedStatement insert = connection.prepareStatement(insertSql)) {
			delete.setLong(1, ownerLocalId);
			delete.executeUpdate();
			for (Region region : regions) {
				insert.setLong(1, ownerLocalId);
				insert.setInt(2, region.order());
				insert.setLong(3, region.sourceLocalId());
				insert.setInt(4, region.pageNumber());
				insert.setDouble(5, region.x());
				insert.setDouble(6, region.y());
				insert.setDouble(7, region.width());
				insert.setDouble(8, region.height());
				if (insert.executeUpdate() != 1) {
					throw new SQLException("PDF-region insertion did not affect one row");
				}
			}
		}
	}

	private int requireInt(JsonNode values, String field) throws SQLException {
		JsonNode value = values.get(field);
		if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
			throw new SQLException("Invalid PDF-region integer: " + field);
		}
		return value.intValue();
	}

	private double requireNumber(JsonNode values, String field) throws SQLException {
		JsonNode value = values.get(field);
		if (value == null || !value.isNumber()) {
			throw new SQLException("Invalid PDF-region coordinate: " + field);
		}
		return value.doubleValue();
	}

	private List<Region> validate(Connection connection, String type, long examId, long ownerLocalId,
			ExchangeRecord proposed) throws SQLException {
		String collection = type.equals("QUESTION") ? "question_regions" : "answer_regions";
		JsonNode supplied = proposed.values().get(collection);
		if (supplied == null || !supplied.isArray()) {
			throw new SQLException("Missing approved PDF-region collection: " + collection);
		}
		long expectedBooklet = -1;
		if (type.equals("QUESTION")) {
			try (PreparedStatement statement = connection.prepareStatement("""
					SELECT booklet_id
					FROM questions
					WHERE id = ?
					""")) {
				statement.setLong(1, ownerLocalId);
				try (ResultSet result = statement.executeQuery()) {
					if (!result.next()) {
						throw new SQLException("Question owning PDF regions is missing");
					}
					expectedBooklet = result.getLong(1);
				}
			}
		}
		List<Region> regions = new ArrayList<>();
		Set<Integer> orders = new HashSet<>();
		for (JsonNode row : supplied) {
			if (!row.isObject()) {
				throw new SQLException("PDF-region entry must be an object");
			}
			int order = requireInt(row, "region_order");
			int page = requireInt(row, "page_number");
			if (order < 0 || !orders.add(order)) {
				throw new SQLException("Invalid or duplicate PDF-region order");
			}
			double x = requireNumber(row, "x");
			double y = requireNumber(row, "y");
			double width = requireNumber(row, "width");
			double height = requireNumber(row, "height");
			try {
				new SharedQuestionContextRegion(page, x, y, width, height);
			} catch (IllegalArgumentException failure) {
				throw new SQLException("Invalid PDF-region geometry", failure);
			}
			EntityType sourceType = type.equals("QUESTION") ? EntityType.EXAM_BOOKLET : EntityType.ANSWER_FILE;
			String sourceField = type.equals("QUESTION") ? "booklet_id" : "answer_file_id";
			long sourceLocalId = reference(connection, sourceType, row.get(sourceField));
			if (type.equals("QUESTION")) {
				if (sourceLocalId != expectedBooklet) {
					throw new SQLException("Question region belongs to another booklet");
				}
			} else {
				try (PreparedStatement statement = connection.prepareStatement("""
						SELECT exam_id
						FROM answer_files
						WHERE id = ?
						""")) {
					statement.setLong(1, sourceLocalId);
					try (ResultSet result = statement.executeQuery()) {
						if (!result.next() || result.getLong(1) != examId) {
							throw new SQLException("Answer region refers to another Exam");
						}
					}
				}
			}
			regions.add(new Region(order, sourceLocalId, page, x, y, width, height));
		}
		return List.copyOf(regions);
	}

	private List<ContentPart> validateQuestionParts(Connection connection, long questionLocalId,
			ExchangeRecord proposed, List<Region> regions, Map<RecordKey, Long> localIds) throws SQLException {
		JsonNode supplied = proposed.values().get("question_content_parts");
		if (supplied == null || !supplied.isArray()) {
			throw new SQLException("Missing approved Question content parts");
		}
		Set<Integer> availableRegions = new HashSet<>();
		for (Region region : regions) {
			availableRegions.add(region.order());
		}
		Set<Integer> contentOrders = new HashSet<>();
		Set<Integer> usedRegions = new HashSet<>();
		List<ContentPart> result = new ArrayList<>();
		for (JsonNode row : supplied) {
			if (!row.isObject()) {
				throw new SQLException("Question content part must be an object");
			}
			int contentOrder = requireInt(row, "content_order");
			if (contentOrder < 0 || !contentOrders.add(contentOrder)) {
				throw new SQLException("Invalid or duplicate Question content order");
			}
			JsonNode typeNode = row.get("content_type");
			if (typeNode == null || !typeNode.isTextual()) {
				throw new SQLException("Question content part has no valid type");
			}
			String contentType = typeNode.asText();
			if ("PDF_REGION".equals(contentType)) {
				int regionOrder = requireInt(row, "region_order");
				JsonNode imageId = row.get("image_id");
				if (!availableRegions.contains(regionOrder) || !usedRegions.add(regionOrder) || imageId == null
						|| !imageId.isNull()) {
					throw new SQLException("Question PDF content part has " + "invalid region or image reference");
				}
				result.add(new ContentPart(contentOrder, contentType, regionOrder, null));
			} else if ("IMAGE".equals(contentType)) {
				JsonNode regionOrder = row.get("region_order");
				if (regionOrder == null || !regionOrder.isNull()) {
					throw new SQLException("Image content part cannot reference " + "a PDF region");
				}
				long imageLocalId = referenceImage(connection, row.get("image_id"), localIds);

				// The inserted or existing image must belong to
				// this exact Question. A portable UUID alone does
				// not establish parent ownership.
				try (PreparedStatement lookup = connection.prepareStatement("""
						SELECT question_id
						FROM question_images
						WHERE id = ?
						""")) {
					lookup.setLong(1, imageLocalId);
					try (ResultSet rows = lookup.executeQuery()) {
						if (!rows.next() || rows.getLong(1) != questionLocalId || rows.next()) {
							throw new SQLException("Question content part references " + "a missing or foreign image");
						}
					}
				}
				result.add(new ContentPart(contentOrder, contentType, null, imageLocalId));
			} else {
				throw new SQLException("Unsupported Question content type: " + contentType);
			}
		}
		return List.copyOf(result);
	}

	private record ContentPart(int contentOrder, String contentType, Integer regionOrder, Long imageLocalId) {
	}

	private record Region(int order, long sourceLocalId, int pageNumber, double x, double y, double width,
			double height) {
	}
}
