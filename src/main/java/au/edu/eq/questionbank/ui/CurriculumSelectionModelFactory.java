package au.edu.eq.questionbank.ui;

import java.sql.SQLException;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.repository.CurriculumRepository;
import au.edu.eq.questionbank.repository.SqliteCurriculumRepository;
import au.edu.eq.questionbank.repository.SqliteDatabase;
import au.edu.eq.questionbank.ui.model.CurriculumSelectionModel;

/**
 * Creates the curriculum selection model used when the desktop application
 * starts.
 */
final class CurriculumSelectionModelFactory {

	CurriculumSelectionModel create(ApplicationConfig config) throws SQLException {
		SqliteDatabase database = new SqliteDatabase(config.databasePath());
		database.initialiseSchema();
		CurriculumRepository repository = new SqliteCurriculumRepository(database);
		return new CurriculumSelectionModel(repository);
	}
}
