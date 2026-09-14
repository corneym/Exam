package au.edu.eq.questionbank.repository.curriculum;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import au.edu.eq.questionbank.model.CurriculumStatus;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

/**
 * SQLite persistence for managed curriculum source-PDF metadata.
 */
public final class SqliteCurriculumSourcePdfRepository implements CurriculumSourcePdfRepository {

	private final SqliteDatabase database;

	public SqliteCurriculumSourcePdfRepository(SqliteDatabase database) {
		if (database == null) {
			throw new NullPointerException("database");
		}
		this.database = database;
	}

	@Override
	public SyllabusVersion updateSourcePdfPath(SyllabusVersion syllabusVersion, String relativePath) {
		if (syllabusVersion == null) {
			throw new NullPointerException("syllabusVersion");
		}
		if (relativePath == null || relativePath.isBlank()) {
			throw new IllegalArgumentException("relativePath must not be blank");
		}
		if (syllabusVersion.getCurriculumStatus() != CurriculumStatus.IN_PROGRESS) {
			throw new IllegalStateException("Final curriculum must be reopened before changing its source PDF");
		}
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						UPDATE syllabus_versions
						SET source_pdf_path = ?
						WHERE id = ?
						  AND curriculum_status = 'IN_PROGRESS'
						""")) {
			statement.setString(1, relativePath);
			statement.setLong(2, syllabusVersion.getId());
			if (statement.executeUpdate() != 1) {
				throw new IllegalStateException(
						"Syllabus version is not available for source-PDF update: " + syllabusVersion.getId());
			}
		} catch (SQLException e) {
			throw new IllegalStateException("Could not store curriculum source-PDF path", e);
		}
		return new SyllabusVersion(syllabusVersion.getId(), syllabusVersion.getSubject(), syllabusVersion.getName(),
				syllabusVersion.isCurrent(), syllabusVersion.getCurriculumStatus(),
				syllabusVersion.getCurriculumFinalisedAt(), relativePath);
	}
}
