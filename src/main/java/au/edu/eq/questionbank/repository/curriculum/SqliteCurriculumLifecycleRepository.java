package au.edu.eq.questionbank.repository.curriculum;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;

import au.edu.eq.questionbank.model.CurriculumStatus;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

/**
 * SQLite persistence for curriculum lifecycle transitions.
 */
public final class SqliteCurriculumLifecycleRepository implements CurriculumLifecycleRepository {

	private final SqliteDatabase database;

	/**
	 * Creates a repository for curriculum finalisation and reopening.
	 *
	 * @param database initialised question-bank database
	 */
	public SqliteCurriculumLifecycleRepository(SqliteDatabase database) {
		if (database == null) {
			throw new NullPointerException("database");
		}
		this.database = database;
	}

	@Override
	public SyllabusVersion finalise(SyllabusVersion syllabusVersion, Instant finalisedAt) {
		if (syllabusVersion == null) {
			throw new NullPointerException("syllabusVersion");
		}
		if (finalisedAt == null) {
			throw new NullPointerException("finalisedAt");
		}
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						UPDATE syllabus_versions
						SET curriculum_status = 'FINAL',
						    curriculum_finalised_at = ?
						WHERE id = ?
						  AND curriculum_status = 'IN_PROGRESS'
						""")) {
			statement.setString(1, finalisedAt.toString());
			statement.setLong(2, syllabusVersion.getId());
			if (statement.executeUpdate() != 1) {
				throw new IllegalStateException(
						"Syllabus version is not available for finalisation: " + syllabusVersion.getId());
			}
		} catch (SQLException e) {
			throw new IllegalStateException("Could not finalise curriculum", e);
		}
		return new SyllabusVersion(syllabusVersion.getId(), syllabusVersion.getSubject(), syllabusVersion.getName(),
				syllabusVersion.isCurrent(), CurriculumStatus.FINAL, finalisedAt, syllabusVersion.getSourcePdfPath());
	}

	@Override
	public SyllabusVersion reopen(SyllabusVersion syllabusVersion) {
		if (syllabusVersion == null) {
			throw new NullPointerException("syllabusVersion");
		}
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						UPDATE syllabus_versions
						SET curriculum_status = 'IN_PROGRESS',
						    curriculum_finalised_at = NULL
						WHERE id = ?
						  AND curriculum_status = 'FINAL'
						""")) {
			statement.setLong(1, syllabusVersion.getId());
			if (statement.executeUpdate() != 1) {
				throw new IllegalStateException(
						"Syllabus version is not available for reopening: " + syllabusVersion.getId());
			}
		} catch (SQLException e) {
			throw new IllegalStateException("Could not reopen curriculum", e);
		}
		return new SyllabusVersion(syllabusVersion.getId(), syllabusVersion.getSubject(), syllabusVersion.getName(),
				syllabusVersion.isCurrent(), CurriculumStatus.IN_PROGRESS, null, syllabusVersion.getSourcePdfPath());
	}
}
