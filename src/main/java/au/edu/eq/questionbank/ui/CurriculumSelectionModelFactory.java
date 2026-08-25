package au.edu.eq.questionbank.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.importer.CurriculumRepositoryLoader;
import au.edu.eq.questionbank.importer.CurriculumSource;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.repository.CurriculumRepository;
import au.edu.eq.questionbank.ui.model.CurriculumSelectionModel;

/**
 * Creates the curriculum selection model used when the desktop application
 * starts.
 */
final class CurriculumSelectionModelFactory {

	CurriculumSelectionModel create(ApplicationConfig config) throws IOException {
		Subject chemistry = new Subject(1, "Chemistry");
		SyllabusVersion syllabus2019 = new SyllabusVersion(1, chemistry, "2019", false);
		SyllabusVersion syllabus2025 = new SyllabusVersion(2, chemistry, "2025", true);
		Path chemistryRoot = config.curriculumDataRoot().resolve("chemistry");
		CurriculumSource source2019 = new CurriculumSource(syllabus2019,
				List.of(chemistryRoot.resolve("2019").resolve("CHM Study Checklist [2019 Syllabus].xlsx")));
		CurriculumSource source2025 = new CurriculumSource(syllabus2025, List.of(
				chemistryRoot.resolve("2025").resolve("CHM Study Checklist - Unit 1 and 2 [2025 Syllabus].xlsx"),
				chemistryRoot.resolve("2025").resolve("CHM Study Checklist - Unit 3 and 4 [2025 Syllabus].xlsx")));
		CurriculumRepository repository = new CurriculumRepositoryLoader().load(List.of(source2019, source2025));

		return new CurriculumSelectionModel(repository);
	}
}
