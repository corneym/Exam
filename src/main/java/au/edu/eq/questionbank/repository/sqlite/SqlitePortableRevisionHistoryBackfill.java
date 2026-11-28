package au.edu.eq.questionbank.repository.sqlite;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;

/**
 * Backfills immutable assignment-baseline revisions during schema migration.
 */
final class SqlitePortableRevisionHistoryBackfill {

	private SqlitePortableRevisionHistoryBackfill() {
	}

	static void backfillAssignmentBaselines(Connection connection) throws SQLException {
		ObjectReader reader = new ObjectMapper().readerFor(JsonNode.class);
		try (PreparedStatement sessions = connection.prepareStatement("""
				SELECT baseline_records, installed_at
				FROM contributor_assignment_sessions
				""");
				ResultSet rows = sessions.executeQuery();
				PreparedStatement insert = connection.prepareStatement("""
						INSERT INTO portable_revision_history (
						    revision_id, portable_id, entity_type, first_observed_at
						)
						VALUES (?, ?, ?, ?)
						ON CONFLICT (revision_id) DO NOTHING
						""");
				PreparedStatement verify = connection.prepareStatement("""
						SELECT portable_id, entity_type
						FROM portable_revision_history
						WHERE revision_id = ?
						""")) {
			while (rows.next()) {
				byte[] baseline = rows.getBytes("baseline_records");
				String observedAt = rows.getString("installed_at");
				try (MappingIterator<JsonNode> records = reader.readValues(new ByteArrayInputStream(baseline))) {
					while (records.hasNextValue()) {
						JsonNode record = records.nextValue();
						EntityType type = entityType(record);
						UUID portableId = uuid(record, "recordId");
						UUID revisionId = uuid(record, "revisionId");
						insert.setString(1, revisionId.toString());
						insert.setString(2, portableId.toString());
						insert.setString(3, type.name());
						insert.setString(4, observedAt);
						insert.executeUpdate();
						verifyAssociation(verify, type, portableId, revisionId);
					}
				} catch (IOException | IllegalArgumentException | NullPointerException failure) {
					throw new SQLException("Invalid contributor assignment baseline revision history", failure);
				}
			}
		}
	}

	private static EntityType entityType(JsonNode record) {
		return EntityType.valueOf(record.path("recordType").textValue());
	}

	private static UUID uuid(JsonNode record, String field) {
		String value = record.path(field).textValue();
		UUID result = UUID.fromString(value);
		if (!result.toString().equals(value)) {
			throw new IllegalArgumentException("Noncanonical UUID");
		}
		return result;
	}

	private static void verifyAssociation(PreparedStatement verify, EntityType type, UUID portableId, UUID revisionId)
			throws SQLException {
		verify.setString(1, revisionId.toString());
		try (ResultSet row = verify.executeQuery()) {
			if (!row.next() || !portableId.toString().equals(row.getString("portable_id"))
					|| !type.name().equals(row.getString("entity_type")) || row.next()) {
				throw new SQLException("Assignment baseline revision belongs to another portable entity");
			}
		}
	}
}
