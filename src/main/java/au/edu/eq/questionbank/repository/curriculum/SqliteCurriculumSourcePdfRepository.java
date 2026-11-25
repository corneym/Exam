package au.edu.eq.questionbank.repository.curriculum;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import au.edu.eq.questionbank.model.CurriculumStatus;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.repository.sqlite.SqliteDirectChanges;
import au.edu.eq.questionbank.repository.sqlite.SqliteImportedSubjectAuthorityRepository;

/**
 * SQLite persistence for managed curriculum source-PDF metadata.
 */
public final class SqliteCurriculumSourcePdfRepository implements CurriculumSourcePdfRepository {

	private final SqliteDatabase database;

	/**
	 * Creates a writer for managed syllabus source-PDF references.
	 *
	 * @param database initialised question-bank database
	 */
	public SqliteCurriculumSourcePdfRepository(SqliteDatabase database) {
		if (database == null) {
			throw new NullPointerException("database");
		}
		this.database = database;
	}

	/**
	 * Requires ordinary coordinator mode before attaching or replacing an
	 * authoritative syllabus source PDF.
	 *
	 * <p>
	 * This method prevents managed file creation before the later syllabus metadata
	 * update. SQLite structural triggers remain responsible for rejecting changes
	 * at persistence time.
	 *
	 * @throws IllegalStateException if an assignment is selected or contributor
	 *                               selection cannot be inspected safely
	 */
	@Override
	public void requireCoordinatorStructureEdit() {
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT assignment_id
						FROM contributor_assignment_selection
						WHERE singleton_id = 1
						""");
				ResultSet result = statement.executeQuery()) {
			if (!result.next()) {
				throw new IllegalStateException("Contributor assignment selection is missing");
			}
			if (result.getString("assignment_id") != null) {
				throw new IllegalStateException("Authoritative syllabus PDF attachment is prohibited "
						+ "while a contributor assignment is selected");
			}
			if (result.next()) {
				throw new IllegalStateException("Multiple contributor assignment selection rows");
			}
		} catch (SQLException exception) {
			throw new IllegalStateException("Cannot verify contributor assignment mode", exception);
		}
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

		// Recheck editability in SQL before changing the reference; file copying
		// belongs to the service.
		try (Connection connection = database.openConnection()) {
			new SqliteImportedSubjectAuthorityRepository(database).requireLocallyAuthoritativeSyllabusVersion(connection,
					syllabusVersion.getId());
			try (PreparedStatement statement = connection.prepareStatement("""
						UPDATE syllabus_versions
						SET source_pdf_path = ?
						WHERE id = ?
						  AND curriculum_status = 'IN_PROGRESS'
						""")) {
				statement.setString(1, relativePath);
				statement.setLong(2, syllabusVersion.getId());
				if (SqliteDirectChanges.executeUpdate(statement) != 1) {
					throw new IllegalStateException(
							"Syllabus version is not available for source-PDF update: " + syllabusVersion.getId());
				}
			}
		} catch (SQLException e) {
			throw new IllegalStateException("Could not store curriculum source-PDF path", e);
		}
		return new SyllabusVersion(syllabusVersion.getId(), syllabusVersion.getSubject(), syllabusVersion.getName(),
				syllabusVersion.isCurrent(), syllabusVersion.getCurriculumStatus(),
				syllabusVersion.getCurriculumFinalisedAt(), relativePath);
	}
}
