package au.edu.eq.questionbank.service.workpackage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

import au.edu.eq.questionbank.model.SharedQuestionContextRegion;
import au.edu.eq.questionbank.repository.identity.SqlitePortableContentRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.workpackage.MasterContributionDecisionLedger.RecordDecision;
import au.edu.eq.questionbank.service.workpackage.MasterContributionDecisionLedger.RecordKey;
import au.edu.eq.questionbank.service.workpackage.MasterContributionReviewPlanner.ReviewStatus;

/**
 * Applies approved Source Question and Shared Context graph changes inside the
 * caller's authoritative SQLite transaction.
 *
 * <p>
 * The writer uses an exact reviewed graph permit. Source and Context parent
 * records cannot be reparented, and all nested Shared Context content is
 * replaced atomically with its authoritative parent.
 *
 * <p>
 * PNG bytes must already have passed the archive and intake preflight. No
 * managed filesystem assets are published by this writer.
 */
final class MasterContributionGraphWriter {

	private final SqliteDatabase database;

	MasterContributionGraphWriter(SqliteDatabase database) {
		this.database = Objects.requireNonNull(database, "database");
	}

	MasterContributionScalarWriter.Applied apply(Connection connection, UUID reviewId, long examId,
			RecordDecision decision, ExchangeRecord proposed,
			Map<RecordKey, MasterContributionApprovedContextPreparer.PreparedContext> prepared) throws SQLException {
		Objects.requireNonNull(connection, "connection");
		Objects.requireNonNull(reviewId, "reviewId");
		Objects.requireNonNull(decision, "decision");
		Objects.requireNonNull(proposed, "proposed");
		Objects.requireNonNull(prepared, "prepared");
		if (connection.getAutoCommit()) {
			throw new SQLException("Graph application requires a caller-owned transaction");
		}
		EntityType type;
		try {
			type = EntityType.valueOf(decision.key().recordType());
		} catch (IllegalArgumentException invalid) {
			throw new SQLException("Unsupported reviewed graph record", invalid);
		}
		if (type != EntityType.SOURCE_QUESTION && type != EntityType.SHARED_QUESTION_CONTEXT) {
			throw new SQLException("Unsupported reviewed graph entity");
		}
		boolean create = decision.preliminaryStatus() == ReviewStatus.READY_NEW;
		if (!create && decision.preliminaryStatus() != ReviewStatus.READY_UPDATE) {
			throw new SQLException("Invalid graph review status");
		}
		JsonNode values = proposed.values();
		long bookletId = requireBooklet(connection, examId, values.get("booklet_id"));
		long localId = -1;
		ExchangeRecord before = null;
		var identities = new SqlitePortableIdentityRepository(database);
		var content = new SqlitePortableContentRepository(database);
		if (create) {
			if (decision.baselineRevisionId() != null || decision.masterRevisionId() != null
					|| decision.masterSha256() != null
					|| identities.findByPortableId(connection, type, proposed.recordId()).isPresent()) {
				throw new SQLException("New graph identity collides with master evidence");
			}
		} else {
			localId = identities.findByPortableId(connection, type, proposed.recordId())
					.orElseThrow(() -> new SQLException("Reviewed graph identity is missing")).localId();
			before = content.snapshot(connection, type, localId).record();
			if (!before.values().path("booklet_id").equals(values.path("booklet_id"))) {
				throw new SQLException("Contributor cannot reparent Source or Shared Context");
			}
		}
		MasterContributionApprovedContextPreparer.PreparedContext context = null;
		if (type == EntityType.SHARED_QUESTION_CONTEXT) {
			context = prepared.get(decision.key());
			if (context == null || !context.proposal().equals(proposed)) {
				throw new SQLException("Approved Shared Context has no matching verified payload");
			}
			validateContext(values, context.images());
		}
		acquirePermit(connection, reviewId, decision, examId, create ? null : localId);
		try {
			if (create) {
				if (type == EntityType.SOURCE_QUESTION) {
					try (PreparedStatement statement = connection.prepareStatement("""
							INSERT INTO source_questions (
							    booklet_id, source_question_code
							) VALUES (?, ?)
							""")) {
						statement.setLong(1, bookletId);
						statement.setString(2, requiredText(values, "source_question_code"));
						requireOne(statement.executeUpdate());
					}
				} else {
					try (PreparedStatement statement = connection.prepareStatement("""
							INSERT INTO shared_question_contexts (
							    booklet_id, context_label
							) VALUES (?, ?)
							""")) {
						statement.setLong(1, bookletId);
						statement.setString(2, requiredText(values, "context_label"));
						requireOne(statement.executeUpdate());
					}
				}
				localId = lastInsertedId(connection);
				bindOwner(connection, localId);
			} else if (type == EntityType.SOURCE_QUESTION) {
				try (PreparedStatement statement = connection.prepareStatement("""
						UPDATE source_questions
						SET source_question_code = ?
						WHERE id = ?
						""")) {
					statement.setString(1, requiredText(values, "source_question_code"));
					statement.setLong(2, localId);
					requireOne(statement.executeUpdate());
				}
			} else {
				try (PreparedStatement statement = connection.prepareStatement("""
						UPDATE shared_question_contexts
						SET context_label = ?
						WHERE id = ?
						""")) {
					statement.setString(1, requiredText(values, "context_label"));
					statement.setLong(2, localId);
					requireOne(statement.executeUpdate());
				}
			}
			if (type == EntityType.SHARED_QUESTION_CONTEXT) {
				replaceContextChildren(connection, localId, values, context.images());
			}
		} finally {
			releasePermit(connection);
		}
		ExchangeRecord actual = content.snapshot(connection, type, localId).record();
		if (!actual.values().equals(values)) {
			throw new SQLException("Applied graph differs from approved portable content");
		}
		if (before != null && actual.revisionId().equals(before.revisionId())) {
			throw new SQLException("Reviewed graph mutation did not advance its revision");
		}
		return new MasterContributionScalarWriter.Applied(localId, actual.recordId(), actual.revisionId());
	}

	private void acquirePermit(Connection connection, UUID reviewId, RecordDecision decision, long examId, Long ownerId)
			throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO master_contribution_graph_permits (
				    singleton_id, review_id, record_type,
				    record_id, operation, exam_local_id, owner_local_id
				) VALUES (1, ?, ?, ?, ?, ?, ?)
				""")) {
			statement.setString(1, reviewId.toString());
			statement.setString(2, decision.key().recordType());
			statement.setString(3, decision.key().recordId().toString());
			statement.setString(4, ownerId == null ? "INSERT" : "UPDATE");
			statement.setLong(5, examId);
			if (ownerId == null) {
				statement.setNull(6, Types.INTEGER);
			} else {
				statement.setLong(6, ownerId);
			}
			requireOne(statement.executeUpdate());
		}
	}

	private void bindOwner(Connection connection, long ownerId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				UPDATE master_contribution_graph_permits
				SET owner_local_id = ?
				WHERE singleton_id = 1
				  AND owner_local_id IS NULL
				  AND operation = 'INSERT'
				""")) {
			statement.setLong(1, ownerId);
			requireOne(statement.executeUpdate());
		}
	}

	private long lastInsertedId(Connection connection) throws SQLException {
		try (Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("SELECT last_insert_rowid()")) {
			if (!result.next() || result.getLong(1) < 1) {
				throw new SQLException("Inserted graph row has no SQLite ID");
			}
			long id = result.getLong(1);
			if (result.next()) {
				throw new SQLException("Ambiguous inserted graph identity");
			}
			return id;
		}
	}

	private void releasePermit(Connection connection) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				DELETE FROM master_contribution_graph_permits
				WHERE singleton_id = 1
				""")) {
			requireOne(statement.executeUpdate());
		}
	}

	private void replaceContextChildren(Connection connection, long contextId, JsonNode values,
			List<MasterContributionApprovedContextPreparer.PreparedContextImage> images) throws SQLException {

		// Remove dependent content references before replacing underlying
		// PDF regions and PNG rows. SQLite rollback restores the entire graph.
		try (PreparedStatement parts = connection.prepareStatement("""
				DELETE FROM shared_question_context_content_parts
				WHERE shared_context_id = ?
				"""); PreparedStatement oldImages = connection.prepareStatement("""
				DELETE FROM shared_question_context_images
				WHERE shared_context_id = ?
				"""); PreparedStatement regions = connection.prepareStatement("""
				DELETE FROM shared_question_context_regions
				WHERE shared_context_id = ?
				""")) {
			parts.setLong(1, contextId);
			parts.executeUpdate();
			oldImages.setLong(1, contextId);
			oldImages.executeUpdate();
			regions.setLong(1, contextId);
			regions.executeUpdate();
		}
		try (PreparedStatement insert = connection.prepareStatement("""
				INSERT INTO shared_question_context_regions (
				    shared_context_id, region_order, page_number,
				    x, y, width, height
				) VALUES (?, ?, ?, ?, ?, ?, ?)
				""")) {
			for (JsonNode row : values.path("shared_question_context_regions")) {
				insert.setLong(1, contextId);
				insert.setInt(2, requiredInt(row, "region_order"));
				insert.setInt(3, requiredInt(row, "page_number"));
				insert.setDouble(4, requiredNumber(row, "x"));
				insert.setDouble(5, requiredNumber(row, "y"));
				insert.setDouble(6, requiredNumber(row, "width"));
				insert.setDouble(7, requiredNumber(row, "height"));
				requireOne(insert.executeUpdate());
			}
		}
		try (PreparedStatement insert = connection.prepareStatement("""
				INSERT INTO shared_question_context_images (
				    shared_context_id, image_order, image_png
				) VALUES (?, ?, ?)
				""")) {
			for (var image : images) {
				insert.setLong(1, contextId);
				insert.setInt(2, image.imageOrder());
				insert.setBytes(3, image.pngBytes());
				requireOne(insert.executeUpdate());
			}
		}
		try (PreparedStatement insert = connection.prepareStatement("""
				INSERT INTO shared_question_context_content_parts (
				    shared_context_id, content_order, content_type,
				    region_order, image_order
				) VALUES (?, ?, ?, ?, ?)
				""")) {
			for (JsonNode row : values.path("shared_question_context_content_parts")) {
				String type = requiredText(row, "content_type");
				insert.setLong(1, contextId);
				insert.setInt(2, requiredInt(row, "content_order"));
				insert.setString(3, type);
				if ("PDF_REGION".equals(type)) {
					insert.setInt(4, requiredInt(row, "region_order"));
					insert.setNull(5, Types.INTEGER);
				} else if ("IMAGE".equals(type)) {
					insert.setNull(4, Types.INTEGER);
					insert.setInt(5, requiredInt(row, "image_order"));
				} else {
					throw new SQLException("Unsupported Shared Context content type");
				}
				requireOne(insert.executeUpdate());
			}
		}
	}

	private long requireBooklet(Connection connection, long examId, JsonNode portableId) throws SQLException {
		if (portableId == null || !portableId.isTextual()) {
			throw new SQLException("Missing graph Booklet identity");
		}
		UUID id;
		try {
			id = UUID.fromString(portableId.asText());
			if (!id.toString().equals(portableId.asText())) {
				throw new IllegalArgumentException("Noncanonical UUID");
			}
		} catch (IllegalArgumentException invalid) {
			throw new SQLException("Invalid graph Booklet identity", invalid);
		}
		long localId = new SqlitePortableIdentityRepository(database)
				.findByPortableId(connection, EntityType.EXAM_BOOKLET, id)
				.orElseThrow(() -> new SQLException("Graph Booklet is missing from master")).localId();
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT exam_id
				FROM exam_booklets
				WHERE id = ?
				""")) {
			statement.setLong(1, localId);
			try (ResultSet rows = statement.executeQuery()) {
				if (!rows.next() || rows.getLong(1) != examId || rows.next()) {
					throw new SQLException("Graph dependency belongs to another Exam");
				}
			}
		}
		return localId;
	}

	private int requiredInt(JsonNode values, String field) throws SQLException {
		JsonNode value = values.get(field);
		if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
			throw new SQLException("Invalid integer graph field: " + field);
		}
		return value.intValue();
	}

	private double requiredNumber(JsonNode values, String field) throws SQLException {
		JsonNode value = values.get(field);
		if (value == null || !value.isNumber() || !Double.isFinite(value.doubleValue())) {
			throw new SQLException("Invalid numeric graph field: " + field);
		}
		return value.doubleValue();
	}

	private String requiredText(JsonNode values, String field) throws SQLException {
		JsonNode value = values.get(field);
		if (value == null || !value.isTextual() || value.asText().isBlank()) {
			throw new SQLException("Invalid graph text field: " + field);
		}
		return value.asText();
	}

	private void requireOne(int rows) throws SQLException {
		if (rows != 1) {
			throw new SQLException("Reviewed graph operation did not affect exactly one row");
		}
	}

	private void validateContext(JsonNode values,
			List<MasterContributionApprovedContextPreparer.PreparedContextImage> images) throws SQLException {
		JsonNode regions = values.get("shared_question_context_regions");
		JsonNode imageRows = values.get("shared_question_context_images");
		JsonNode parts = values.get("shared_question_context_content_parts");
		if (regions == null || !regions.isArray() || imageRows == null || !imageRows.isArray() || parts == null
				|| !parts.isArray() || parts.isEmpty()) {
			throw new SQLException("Approved Shared Context has incomplete content");
		}
		Map<Integer, String> hashes = new HashMap<>();
		Set<Integer> regionOrders = new HashSet<>();
		Set<Integer> partOrders = new HashSet<>();
		for (JsonNode row : regions) {
			int order = requiredInt(row, "region_order");
			if (order < 0 || !regionOrders.add(order)) {
				throw new SQLException("Invalid Shared Context PDF-region order");
			}
			try {
				new SharedQuestionContextRegion(requiredInt(row, "page_number"), requiredNumber(row, "x"),
						requiredNumber(row, "y"), requiredNumber(row, "width"), requiredNumber(row, "height"));
			} catch (IllegalArgumentException invalid) {
				throw new SQLException("Invalid Shared Context PDF geometry", invalid);
			}
		}
		for (JsonNode row : imageRows) {
			int order = requiredInt(row, "image_order");
			String hash = requiredText(row, "image_png");
			if (order < 0 || !hash.matches("[0-9a-f]{64}") || hashes.putIfAbsent(order, hash) != null) {
				throw new SQLException("Invalid Shared Context PNG evidence");
			}
		}
		if (images.size() != hashes.size()) {
			throw new SQLException("Prepared Shared Context image collection is incomplete");
		}
		for (var image : images) {
			if (!image.sha256().equals(hashes.get(image.imageOrder()))) {
				throw new SQLException("Prepared PNG differs from approved Shared Context");
			}
		}
		for (JsonNode row : parts) {
			int order = requiredInt(row, "content_order");
			String type = requiredText(row, "content_type");
			if (order < 0 || !partOrders.add(order)) {
				throw new SQLException("Duplicate Shared Context content order");
			}
			if ("PDF_REGION".equals(type)) {
				if (!regionOrders.contains(requiredInt(row, "region_order")) || !row.path("image_order").isNull()) {
					throw new SQLException("Invalid Shared Context PDF content reference");
				}
			} else if ("IMAGE".equals(type)) {
				if (!hashes.containsKey(requiredInt(row, "image_order")) || !row.path("region_order").isNull()) {
					throw new SQLException("Invalid Shared Context image content reference");
				}
			} else {
				throw new SQLException("Unsupported Shared Context content type");
			}
		}
	}
}
