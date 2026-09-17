package au.edu.eq.questionbank.ui.model;

import java.sql.SQLException;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.repository.curriculum.CurriculumRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumRepository;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

/**
 * Creates the curriculum selection model used when the desktop application
 * starts.
 */
public final class CurriculumSelectionModelFactory {

	/**
	 * Creates a factory for database-backed curriculum selection models.
	 */
	public CurriculumSelectionModelFactory() {
	}

	/**
	 * Initialises the configured database and creates its selection model.
	 *
	 * @param config application paths
	 * @return a model backed by the configured SQLite curriculum repository
	 * @throws SQLException if the database cannot be initialised
	 */
	public CurriculumSelectionModel create(ApplicationConfig config) throws SQLException {
		SqliteDatabase database = new SqliteDatabase(config.databasePath());
		database.initialiseSchema();
		CurriculumRepository repository = new SqliteCurriculumRepository(database);
		return new CurriculumSelectionModel(repository);
	}
}
