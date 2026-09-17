package au.edu.eq.questionbank.repository.curriculum;

import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import au.edu.eq.questionbank.model.CurriculumMappingReviewOutcome;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.SyllabusVersion;

/**
 * Reads completed curriculum-mapping reviews from SQLite.
 */
public final class SqliteCurriculumMappingReviewRepository implements CurriculumMappingReviewRepository {

	private final SqliteDatabase database;

	/**
	 * Creates a reader for target-specific curriculum review decisions.
	 *
	 * @param database the initialised question-bank database
	 * @throws NullPointerException if {@code database} is {@code null}
	 */
	public SqliteCurriculumMappingReviewRepository(SqliteDatabase database) {
		if (database == null) {
			throw new NullPointerException("database");
		}
		this.database = database;
	}

	@Override
	public Optional<CurriculumMappingReviewOutcome> findOutcome(CurriculumNode source, SyllabusVersion targetVersion) {
		validateSourceAndTargetVersion(source, targetVersion);
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT review_outcome
						FROM curriculum_mapping_reviews
						WHERE source_node_id = ?
						  AND target_syllabus_version_id = ?
						""")) {
			statement.setLong(1, source.getId());
			statement.setLong(2, targetVersion.getId());
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					return Optional.empty();
				}
				String outcome = result.getString("review_outcome");
				try {
					return Optional.of(CurriculumMappingReviewOutcome.valueOf(outcome));
				} catch (IllegalArgumentException e) {
					throw new IllegalStateException("Unexpected curriculum mapping review outcome: " + outcome, e);
				}
			}
		} catch (SQLException e) {
			throw new IllegalStateException("Could not read curriculum mapping review from database", e);
		}
	}

	@Override
	public Set<Long> findReviewedSourceIds(SyllabusVersion sourceVersion, SyllabusVersion targetVersion) {
		validateVersions(sourceVersion, targetVersion);
		Set<Long> sourceIds = new HashSet<>();
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT review.source_node_id
						FROM curriculum_mapping_reviews review
						JOIN curriculum_nodes source
						    ON source.id = review.source_node_id
						WHERE source.syllabus_version_id = ?
						  AND review.target_syllabus_version_id = ?
						""")) {
			statement.setLong(1, sourceVersion.getId());
			statement.setLong(2, targetVersion.getId());
			try (ResultSet result = statement.executeQuery()) {
				while (result.next()) {
					sourceIds.add(result.getLong("source_node_id"));
				}
			}
			return sourceIds;
		} catch (SQLException e) {
			throw new IllegalStateException("Could not read curriculum mapping reviews from database", e);
		}
	}

	private void validateSourceAndTargetVersion(CurriculumNode source, SyllabusVersion targetVersion) {
		if (source == null) {
			throw new NullPointerException("source");
		}
		if (targetVersion == null) {
			throw new NullPointerException("targetVersion");
		}
		validateVersions(source.getSyllabusVersion(), targetVersion);
	}

	private void validateVersions(SyllabusVersion sourceVersion, SyllabusVersion targetVersion) {
		if (sourceVersion == null) {
			throw new NullPointerException("sourceVersion");
		}
		if (targetVersion == null) {
			throw new NullPointerException("targetVersion");
		}
		if (!sourceVersion.getSubject().equals(targetVersion.getSubject())) {
			throw new IllegalArgumentException("source and target syllabus must belong to the same subject");
		}
		if (sourceVersion.equals(targetVersion)) {
			throw new IllegalArgumentException("source and target syllabus versions must be different");
		}
	}
}
