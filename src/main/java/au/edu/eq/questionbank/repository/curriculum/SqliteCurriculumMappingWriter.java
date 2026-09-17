package au.edu.eq.questionbank.repository.curriculum;

import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import au.edu.eq.questionbank.model.CurriculumMapping;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.MappingStatus;

/**
 * Writes directional curriculum mappings from a non-current syllabus version
 * to the current version of the same subject. Each operation validates
 * persisted endpoint identities and current-version flags and writes within one
 * transaction; syllabus names do not determine direction.
 */
public final class SqliteCurriculumMappingWriter {
	private final SqliteDatabase database;

	/**
	 * Creates a writer for directional curriculum mappings.
	 *
	 * @param database the initialised question-bank database
	 * @throws NullPointerException if {@code database} is {@code null}
	 */
	public SqliteCurriculumMappingWriter(SqliteDatabase database) {
		if (database == null) {
			throw new NullPointerException("database");
		}
		this.database = database;
	}

	/**
	 * Inserts one source-to-target mapping after checking both Java relationships
	 * and persisted subject, syllabus and level identities. An existing pair is
	 * rejected rather than replaced.
	 *
	 * @param source the persisted source node
	 * @param target the persisted target node
	 * @param status the review state to store
	 * @return the mapping with its generated persistent identifier
	 * @throws NullPointerException if an argument is {@code null}
	 * @throws IllegalArgumentException if an endpoint is missing, misrepresents its
	 *                                  persisted identity, is directed other than from
	 *                                  a non-current version to the current version, or
	 *                                  violates another mapping invariant
	 * @throws SQLException if the pair already exists or the transaction fails
	 */
	public CurriculumMapping insertMapping(CurriculumNode source, CurriculumNode target, MappingStatus status)
			throws SQLException {
		validateMapping(source, target, status);
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				validatePersistentMapping(connection, source, target);
				CurriculumMapping mapping;
				try (PreparedStatement statement = connection.prepareStatement("""
						INSERT INTO curriculum_mappings
						    (source_node_id, target_node_id, mapping_status)
						VALUES (?, ?, ?)
						RETURNING id
						""")) {
					statement.setLong(1, source.getId());
					statement.setLong(2, target.getId());
					statement.setString(3, status.name());
					try (ResultSet result = statement.executeQuery()) {
						if (!result.next()) {
							throw new SQLException("Curriculum mapping insert did not return an id");
						}
						mapping = new CurriculumMapping(result.getLong("id"), source, target, status);
					}
				}
				connection.commit();
				return mapping;
			} catch (SQLException | RuntimeException e) {
				rollback(connection, e);
				throw e;
			}
		}
	}

	/**
	 * Updates exactly the identified mapping without changing its endpoints.
	 * Endpoint identities are revalidated; the object's previous status is not a
	 * precondition for the update.
	 *
	 * @param mapping the mapping whose persistent identity and endpoints must match
	 * @param status the review state to store
	 * @return a new mapping with the stored status and unchanged identity/endpoints
	 * @throws NullPointerException if either argument is {@code null}
	 * @throws IllegalArgumentException if the mapping or its endpoints misrepresent
	 *                                  persistent state or violate mapping invariants
	 * @throws SQLException if the mapping is missing or the transaction fails
	 */
	public CurriculumMapping updateStatus(CurriculumMapping mapping, MappingStatus status) throws SQLException {
		if (mapping == null) {
			throw new NullPointerException("mapping");
		}
		if (status == null) {
			throw new NullPointerException("status");
		}
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				validatePersistentMapping(connection, mapping.getSource(), mapping.getTarget());
				validatePersistentMappingIdentity(connection, mapping);
				try (PreparedStatement statement = connection.prepareStatement("""
						UPDATE curriculum_mappings
						SET mapping_status = ?
						WHERE id = ?
						  AND source_node_id = ? AND target_node_id = ?
						""")) {
					statement.setString(1, status.name());
					statement.setLong(2, mapping.getId());
					statement.setLong(3, mapping.getSource().getId());
					statement.setLong(4, mapping.getTarget().getId());
					int updatedRows = statement.executeUpdate();
					if (updatedRows != 1) {
						throw new SQLException("Curriculum mapping " + mapping.getId() + " was not found or changed");
					}
				}
				CurriculumMapping updated = new CurriculumMapping(mapping.getId(), mapping.getSource(),
						mapping.getTarget(), status);
				connection.commit();
				return updated;
			} catch (SQLException | RuntimeException e) {
				rollback(connection, e);
				throw e;
			}
		}
	}

	private void rollback(Connection connection, Exception failure) {
		try {
			connection.rollback();
		} catch (SQLException rollbackFailure) {
			failure.addSuppressed(rollbackFailure);
		}
	}

	private void validateMapping(CurriculumNode source, CurriculumNode target, MappingStatus status) {
		if (source == null) {
			throw new NullPointerException("source");
		}
		if (target == null) {
			throw new NullPointerException("target");
		}
		if (status == null) {
			throw new NullPointerException("status");
		}
		if (!source.getSyllabusVersion().getSubject().equals(target.getSyllabusVersion().getSubject())) {
			throw new IllegalArgumentException("source and target must belong to the same subject");
		}
		if (source.getSyllabusVersion().equals(target.getSyllabusVersion())) {
			throw new IllegalArgumentException("source and target must belong to different syllabus versions");
		}
		if (source.getLevel() != target.getLevel()) {
			throw new IllegalArgumentException("source and target must be the same curriculum level");
		}
		if (source.getSyllabusVersion().isCurrent()) {
			throw new IllegalArgumentException("source syllabus version must not be current");
		}
		if (!target.getSyllabusVersion().isCurrent()) {
			throw new IllegalArgumentException("target syllabus version must be current");
		}
	}

	private void validatePersistentMapping(Connection connection, CurriculumNode source, CurriculumNode target)
			throws SQLException {
		PersistentMappingEndpoints endpoints = readPersistentMappingEndpoints(connection, source, target);
		validatePersistentEndpoint(source, endpoints.sourceVersionId(), endpoints.sourceSubjectId(),
				endpoints.sourceLevel(), endpoints.sourceCurrent(), "source");
		validatePersistentEndpoint(target, endpoints.targetVersionId(), endpoints.targetSubjectId(),
				endpoints.targetLevel(), endpoints.targetCurrent(), "target");
		validatePersistentRelationship(endpoints);
	}

	private PersistentMappingEndpoints readPersistentMappingEndpoints(Connection connection, CurriculumNode source,
			CurriculumNode target) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT
				    source.syllabus_version_id AS source_version_id,
				    source.curriculum_level AS source_level,
				    source_version.subject_id AS source_subject_id,
				    source_version.is_current AS source_is_current,
				    target.syllabus_version_id AS target_version_id,
				    target.curriculum_level AS target_level,
				    target_version.subject_id AS target_subject_id,
				    target_version.is_current AS target_is_current
				FROM curriculum_nodes source
				JOIN syllabus_versions source_version
				    ON source_version.id = source.syllabus_version_id
				JOIN curriculum_nodes target
				    ON target.id = ?
				JOIN syllabus_versions target_version
				    ON target_version.id = target.syllabus_version_id
				WHERE source.id = ?
				""")) {
			statement.setLong(1, target.getId());
			statement.setLong(2, source.getId());
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new IllegalArgumentException("source or target curriculum node does not exist");
				}
				return new PersistentMappingEndpoints(result.getLong("source_version_id"),
						result.getLong("target_version_id"), result.getLong("source_subject_id"),
						result.getLong("target_subject_id"), result.getString("source_level"),
						result.getString("target_level"), result.getInt("source_is_current") != 0,
						result.getInt("target_is_current") != 0);
			}
		}
	}

	private void validatePersistentEndpoint(CurriculumNode node, long versionId, long subjectId, String level,
			boolean current, String endpointName) {
		if (versionId != node.getSyllabusVersion().getId()
				|| subjectId != node.getSyllabusVersion().getSubject().getId()
				|| !level.equals(node.getLevel().name()) || current != node.getSyllabusVersion().isCurrent()) {
			throw new IllegalArgumentException(endpointName + " does not match persisted curriculum node");
		}
	}

	private void validatePersistentRelationship(PersistentMappingEndpoints endpoints) {
		if (endpoints.sourceSubjectId() != endpoints.targetSubjectId()) {
			throw new IllegalArgumentException("source and target must belong to the same subject");
		}
		if (endpoints.sourceVersionId() == endpoints.targetVersionId()) {
			throw new IllegalArgumentException("source and target must belong to different syllabus versions");
		}
		if (!endpoints.sourceLevel().equals(endpoints.targetLevel())) {
			throw new IllegalArgumentException("source and target must be the same curriculum level");
		}
		if (endpoints.sourceCurrent()) {
			throw new IllegalArgumentException("source syllabus version must not be current");
		}
		if (!endpoints.targetCurrent()) {
			throw new IllegalArgumentException("target syllabus version must be current");
		}
	}

	private void validatePersistentMappingIdentity(Connection connection, CurriculumMapping mapping)
			throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT source_node_id, target_node_id
				FROM curriculum_mappings
				WHERE id = ?
				""")) {
			statement.setLong(1, mapping.getId());
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new SQLException("Curriculum mapping " + mapping.getId() + " was not found");
				}
				long sourceNodeId = result.getLong("source_node_id");
				long targetNodeId = result.getLong("target_node_id");
				if (sourceNodeId != mapping.getSource().getId() || targetNodeId != mapping.getTarget().getId()) {
					throw new IllegalArgumentException("mapping does not match persisted curriculum mapping");
				}
			}
		}
	}

	private record PersistentMappingEndpoints(long sourceVersionId, long targetVersionId, long sourceSubjectId,
			long targetSubjectId, String sourceLevel, String targetLevel, boolean sourceCurrent, boolean targetCurrent) {
	}
}
