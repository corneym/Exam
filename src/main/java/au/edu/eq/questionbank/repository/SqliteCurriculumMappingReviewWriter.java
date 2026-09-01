package au.edu.eq.questionbank.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumMappingReviewOutcome;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.SyllabusVersion;

/**
 * Stores a completed descriptor-mapping review.
 */
public final class SqliteCurriculumMappingReviewWriter {
	private final SqliteDatabase database;

	public SqliteCurriculumMappingReviewWriter(SqliteDatabase database) {
		if (database == null) {
			throw new NullPointerException("database");
		}
		this.database = database;
	}

	public void confirmMappings(CurriculumNode source, SyllabusVersion targetVersion, List<CurriculumNode> targets)
			throws SQLException {
		validateSourceAndTargetVersion(source, targetVersion);
		if (targets == null) {
			throw new NullPointerException("targets");
		}
		if (targets.isEmpty()) {
			throw new IllegalArgumentException("at least one target descriptor is required");
		}
		validateTargets(source, targetVersion, targets);
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				validatePersistentSourceAndTargetVersion(connection, source, targetVersion);
				for (CurriculumNode target : targets) {
					validatePersistentTarget(connection, source, targetVersion, target);
					insertMapping(connection, source, target);
				}
				insertReview(connection, source, targetVersion, CurriculumMappingReviewOutcome.MATCHED);
				connection.commit();
			} catch (SQLException | RuntimeException e) {
				rollback(connection, e);
				throw e;
			}
		}
	}

	public void confirmNoMatch(CurriculumNode source, SyllabusVersion targetVersion) throws SQLException {
		validateSourceAndTargetVersion(source, targetVersion);
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				validatePersistentSourceAndTargetVersion(connection, source, targetVersion);
				if (hasConfirmedMappings(connection, source, targetVersion)) {
					throw new IllegalStateException(
							"source descriptor already has confirmed mappings for the target syllabus");
				}
				insertReview(connection, source, targetVersion, CurriculumMappingReviewOutcome.NO_MATCH);
				connection.commit();
			} catch (SQLException | RuntimeException e) {
				rollback(connection, e);
				throw e;
			}
		}
	}

	private void deleteMappings(Connection connection, CurriculumNode source, SyllabusVersion targetVersion)
			throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				DELETE FROM curriculum_mappings
				WHERE source_node_id = ?
				  AND target_node_id IN (
				      SELECT id
				      FROM curriculum_nodes
				      WHERE syllabus_version_id = ?
				  )
				""")) {
			statement.setLong(1, source.getId());
			statement.setLong(2, targetVersion.getId());
			statement.executeUpdate();
		}
	}

	private boolean hasConfirmedMappings(Connection connection, CurriculumNode source, SyllabusVersion targetVersion)
			throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT COUNT(*)
				FROM curriculum_mappings mapping
				JOIN curriculum_nodes target
				    ON target.id = mapping.target_node_id
				WHERE mapping.source_node_id = ?
				  AND target.syllabus_version_id = ?
				  AND mapping.mapping_status = 'CONFIRMED'
				""")) {
			statement.setLong(1, source.getId());
			statement.setLong(2, targetVersion.getId());
			try (ResultSet result = statement.executeQuery()) {
				result.next();
				return result.getInt(1) > 0;
			}
		}
	}

	private void insertMapping(Connection connection, CurriculumNode source, CurriculumNode target)
			throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO curriculum_mappings
				    (source_node_id, target_node_id, mapping_status)
				VALUES (?, ?, 'CONFIRMED')
				""")) {
			statement.setLong(1, source.getId());
			statement.setLong(2, target.getId());
			statement.executeUpdate();
		}
	}

	private void insertReview(Connection connection, CurriculumNode source, SyllabusVersion targetVersion,
			CurriculumMappingReviewOutcome outcome) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO curriculum_mapping_reviews
				    (source_node_id, target_syllabus_version_id, review_outcome)
				VALUES (?, ?, ?)
				""")) {
			statement.setLong(1, source.getId());
			statement.setLong(2, targetVersion.getId());
			statement.setString(3, outcome.name());
			statement.executeUpdate();
		}
	}

	public void replaceMappings(CurriculumNode source, SyllabusVersion targetVersion, List<CurriculumNode> targets)
			throws SQLException {
		validateSourceAndTargetVersion(source, targetVersion);
		if (targets == null) {
			throw new NullPointerException("targets");
		}
		if (targets.isEmpty()) {
			throw new IllegalArgumentException("at least one target descriptor is required");
		}
		validateTargets(source, targetVersion, targets);
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				validatePersistentSourceAndTargetVersion(connection, source, targetVersion);
				requireExistingReview(connection, source, targetVersion);
				deleteMappings(connection, source, targetVersion);
				for (CurriculumNode target : targets) {
					validatePersistentTarget(connection, source, targetVersion, target);
					insertMapping(connection, source, target);
				}
				updateReviewOutcome(connection, source, targetVersion, CurriculumMappingReviewOutcome.MATCHED);
				connection.commit();
			} catch (SQLException | RuntimeException e) {
				rollback(connection, e);
				throw e;
			}
		}
	}

	public void replaceWithNoMatch(CurriculumNode source, SyllabusVersion targetVersion) throws SQLException {
		validateSourceAndTargetVersion(source, targetVersion);
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				validatePersistentSourceAndTargetVersion(connection, source, targetVersion);
				requireExistingReview(connection, source, targetVersion);
				deleteMappings(connection, source, targetVersion);
				updateReviewOutcome(connection, source, targetVersion, CurriculumMappingReviewOutcome.NO_MATCH);
				connection.commit();
			} catch (SQLException | RuntimeException e) {
				rollback(connection, e);
				throw e;
			}
		}
	}

	private void requireExistingReview(Connection connection, CurriculumNode source, SyllabusVersion targetVersion)
			throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT 1
				FROM curriculum_mapping_reviews
				WHERE source_node_id = ?
				  AND target_syllabus_version_id = ?
				""")) {
			statement.setLong(1, source.getId());
			statement.setLong(2, targetVersion.getId());
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new IllegalStateException("curriculum mapping review does not exist");
				}
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

	private void updateReviewOutcome(Connection connection, CurriculumNode source, SyllabusVersion targetVersion,
			CurriculumMappingReviewOutcome outcome) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				UPDATE curriculum_mapping_reviews
				SET review_outcome = ?
				WHERE source_node_id = ?
				  AND target_syllabus_version_id = ?
				""")) {
			statement.setString(1, outcome.name());
			statement.setLong(2, source.getId());
			statement.setLong(3, targetVersion.getId());
			int updatedRows = statement.executeUpdate();
			if (updatedRows != 1) {
				throw new SQLException("Curriculum mapping review was not found or changed");
			}
		}
	}

	private void validatePersistentSourceAndTargetVersion(Connection connection, CurriculumNode source,
			SyllabusVersion targetVersion) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT
				    source.syllabus_version_id AS source_version_id,
				    source.curriculum_level AS source_level,
				    source_version.subject_id AS source_subject_id,
				    target_version.subject_id AS target_subject_id
				FROM curriculum_nodes source
				JOIN syllabus_versions source_version
				    ON source_version.id = source.syllabus_version_id
				JOIN syllabus_versions target_version
				    ON target_version.id = ?
				WHERE source.id = ?
				""")) {
			statement.setLong(1, targetVersion.getId());
			statement.setLong(2, source.getId());
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new IllegalArgumentException("source descriptor or target syllabus does not exist");
				}
				if (result.getLong("source_version_id") != source.getSyllabusVersion().getId()
						|| result.getLong("source_subject_id") != source.getSyllabusVersion().getSubject().getId()
						|| !result.getString("source_level").equals(source.getLevel().name())) {
					throw new IllegalArgumentException("source does not match persisted curriculum node");
				}
				if (result.getLong("target_subject_id") != targetVersion.getSubject().getId()) {
					throw new IllegalArgumentException("target syllabus does not match persisted syllabus version");
				}
				if (result.getLong("source_subject_id") != result.getLong("target_subject_id")) {
					throw new IllegalArgumentException("source and target syllabus must belong to the same subject");
				}
			}
		}
	}

	private void validatePersistentTarget(Connection connection, CurriculumNode source, SyllabusVersion targetVersion,
			CurriculumNode target) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT
				    node.syllabus_version_id,
				    node.curriculum_level,
				    version.subject_id
				FROM curriculum_nodes node
				JOIN syllabus_versions version
				    ON version.id = node.syllabus_version_id
				WHERE node.id = ?
				""")) {
			statement.setLong(1, target.getId());
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new IllegalArgumentException("target descriptor does not exist");
				}
				if (result.getLong("syllabus_version_id") != targetVersion.getId()
						|| result.getLong("syllabus_version_id") != target.getSyllabusVersion().getId()
						|| result.getLong("subject_id") != source.getSyllabusVersion().getSubject().getId()
						|| !result.getString("curriculum_level").equals(CurriculumLevel.DESCRIPTOR.name())) {
					throw new IllegalArgumentException("target does not match persisted target descriptor");
				}
			}
		}
	}

	private void validateSourceAndTargetVersion(CurriculumNode source, SyllabusVersion targetVersion) {
		if (source == null) {
			throw new NullPointerException("source");
		}
		if (targetVersion == null) {
			throw new NullPointerException("targetVersion");
		}
		if (source.getLevel() != CurriculumLevel.DESCRIPTOR) {
			throw new IllegalArgumentException("source must be a descriptor");
		}
		if (!source.getSyllabusVersion().getSubject().equals(targetVersion.getSubject())) {
			throw new IllegalArgumentException("source and target syllabus must belong to the same subject");
		}
		if (source.getSyllabusVersion().equals(targetVersion)) {
			throw new IllegalArgumentException("source and target syllabus versions must be different");
		}
	}

	private void validateTargets(CurriculumNode source, SyllabusVersion targetVersion, List<CurriculumNode> targets) {
		Set<Long> targetIds = new HashSet<>();
		for (CurriculumNode target : targets) {
			if (target == null) {
				throw new NullPointerException("target");
			}
			if (target.getLevel() != CurriculumLevel.DESCRIPTOR) {
				throw new IllegalArgumentException("target must be a descriptor");
			}
			if (!target.getSyllabusVersion().equals(targetVersion)) {
				throw new IllegalArgumentException("target must belong to the selected target syllabus");
			}
			if (!target.getSyllabusVersion().getSubject().equals(source.getSyllabusVersion().getSubject())) {
				throw new IllegalArgumentException("source and target must belong to the same subject");
			}
			if (!targetIds.add(target.getId())) {
				throw new IllegalArgumentException("duplicate target descriptor");
			}
		}
	}
}