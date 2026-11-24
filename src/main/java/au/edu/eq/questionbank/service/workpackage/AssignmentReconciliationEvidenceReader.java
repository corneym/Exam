package au.edu.eq.questionbank.service.workpackage;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

/**
 * Reads historical accepted contribution mappings for a subsequent whole-Exam
 * assignment.
 *
 * <p>
 * Only completed contribution applications for the specified Exam and
 * contributor are included. Reading evidence does not modify the authoritative
 * corpus or assignment history.
 */
final class AssignmentReconciliationEvidenceReader {

	private final SqliteDatabase database;

	AssignmentReconciliationEvidenceReader(SqliteDatabase database) {
		this.database = Objects.requireNonNull(database, "database");
	}

	List<ExchangeRecord> read(UUID examId, UUID contributorId) throws SQLException {

		Objects.requireNonNull(examId, "examId");
		Objects.requireNonNull(contributorId, "contributorId");

		List<ExchangeRecord> evidence = new ArrayList<>();

		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT
						    packet.assignment_id,
						    packet.package_id,
						    packet.baseline_snapshot_id,
						    packet.master_installation_id,
						    packet.contributor_id,
						    packet.origin_installation_id,
						    packet.archive_sha256,
						    application.review_id,
						    mapping.record_type,
						    mapping.record_id,
						    mapping.canonical_record_id,
						    proposal.proposed_revision_id,
						    outcome.resulting_revision_id
						FROM contribution_application_entity_mappings mapping
						JOIN contribution_application_batches application
						  ON application.review_id = mapping.review_id
						JOIN contribution_intake_packages packet
						  ON packet.package_id = application.package_id
						JOIN exam_work_assignments assignment
						  ON assignment.assignment_id = packet.assignment_id
						JOIN exam_work_assignment_states states
						  ON states.assignment_id = assignment.assignment_id
						JOIN contribution_intake_proposals proposal
						  ON proposal.package_id = packet.package_id
						 AND proposal.record_type = mapping.record_type
						 AND proposal.record_id = mapping.record_id
						JOIN contribution_application_results outcome
						  ON outcome.review_id = mapping.review_id
						 AND outcome.record_type = mapping.record_type
						 AND outcome.record_id = mapping.record_id
						WHERE assignment.exam_portable_id = ?
						  AND packet.contributor_id = ?
						  AND states.state = 'COMPLETE'
						  AND outcome.outcome IN ('APPLIED', 'SKIPPED')
						ORDER BY packet.assignment_id,
						         mapping.record_type,
						         mapping.record_id
						""")) {

			statement.setString(1, examId.toString());
			statement.setString(2, contributorId.toString());

			try (ResultSet rows = statement.executeQuery()) {
				while (rows.next()) {
					evidence.add(readRecord(rows));
				}
			}
		}

		return List.copyOf(evidence);
	}

	private UUID evidenceId(ResultSet row) throws SQLException {

		// Evidence identity must distinguish the same portable record
		// appearing in different historical contribution applications.
		String identity = "assignment-reconciliation-v1:" + row.getString("review_id") + ":"
				+ row.getString("record_type") + ":" + row.getString("record_id");

		return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
	}

	private ExchangeRecord readRecord(ResultSet row) throws SQLException {

		ObjectNode values = JsonNodeFactory.instance.objectNode();

		values.put("assignment_id", row.getString("assignment_id"));
		values.put("return_package_id", row.getString("package_id"));
		values.put("baseline_snapshot_id", row.getString("baseline_snapshot_id"));
		values.put("master_installation_id", row.getString("master_installation_id"));
		values.put("contributor_id", row.getString("contributor_id"));
		values.put("origin_installation_id", row.getString("origin_installation_id"));
		values.put("archive_sha256", row.getString("archive_sha256"));
		values.put("review_id", row.getString("review_id"));
		values.put("record_type", row.getString("record_type"));
		values.put("contributor_record_id", row.getString("record_id"));
		values.put("canonical_record_id", row.getString("canonical_record_id"));
		values.put("proposed_revision_id", row.getString("proposed_revision_id"));
		values.put("resulting_revision_id", row.getString("resulting_revision_id"));

		return new ExchangeRecord(ExchangeRecord.CURRENT_RECORD_VERSION, "ASSIGNMENT_RECONCILIATION_EVIDENCE",
				evidenceId(row), null, null, values);
	}
}
