package au.edu.eq.questionbank.service.curriculum;

import java.sql.SQLException;

import au.edu.eq.questionbank.repository.curriculum.CurriculumImportResult;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumImporter;

/**
 * Creates a new persisted syllabus version ready for curriculum authoring.
 */
public final class CurriculumAuthoringCreationService {

	private final SqliteCurriculumImporter curriculumImporter;
	private final CurriculumDraftLoader draftLoader;

	public CurriculumAuthoringCreationService(SqliteCurriculumImporter curriculumImporter,
			CurriculumDraftLoader draftLoader) {
		if (curriculumImporter == null) {
			throw new NullPointerException("curriculumImporter");
		}
		if (draftLoader == null) {
			throw new NullPointerException("draftLoader");
		}
		this.curriculumImporter = curriculumImporter;
		this.draftLoader = draftLoader;
	}

	/**
	 * Creates an empty persisted syllabus version and opens an authoring session
	 * for it.
	 *
	 * @param subjectName  existing or new subject name
	 * @param syllabusName syllabus/version name
	 * @param current      whether this is the current syllabus for the subject
	 * @return empty authoring session backed by the newly persisted syllabus
	 * @throws SQLException             if persistence fails
	 * @throws IllegalArgumentException if either name is blank or the syllabus
	 *                                  already exists
	 */
	public CurriculumAuthoringSession create(String subjectName, String syllabusName, boolean current)
			throws SQLException {
		if (subjectName == null || subjectName.isBlank()) {
			throw new IllegalArgumentException("Subject name must not be blank");
		}
		if (syllabusName == null || syllabusName.isBlank()) {
			throw new IllegalArgumentException("Syllabus name must not be blank");
		}
		String normalisedSubjectName = subjectName.strip();
		String normalisedSyllabusName = syllabusName.strip();
		CurriculumImportResult result = curriculumImporter.importSyllabusWithResult(normalisedSubjectName,
				normalisedSyllabusName, current);
		if (!result.imported()) {
			throw new IllegalArgumentException(
					normalisedSubjectName + " " + normalisedSyllabusName + " already exists");
		}
		return draftLoader.load(result.syllabusVersion());
	}
}
