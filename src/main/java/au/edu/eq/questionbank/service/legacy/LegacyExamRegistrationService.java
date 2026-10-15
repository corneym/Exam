package au.edu.eq.questionbank.service.legacy;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import au.edu.eq.questionbank.importer.legacy.LegacyExamIdentity;
import au.edu.eq.questionbank.importer.legacy.LegacyExamKey;
import au.edu.eq.questionbank.importer.legacy.LegacyMappedBookletRequirement;
import au.edu.eq.questionbank.importer.legacy.LegacyQuestionMetadataImporter;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.repository.assessment.SqliteExamWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteExamWriter.ExamRegistration;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

/**
 * Prepares persisted examinations for a confirmed legacy workbook import.
 *
 * <p>
 * Workbook validation occurs before any examination is created. Existing
 * examinations are reused, and new examinations are registered atomically
 * without creating source PDFs or question metadata.
 */
public final class LegacyExamRegistrationService {

	private final LegacyQuestionMetadataImporter importer;
	private final SqliteExamWriter writer;

	/**
	 * Creates a registration service using the authoritative database.
	 *
	 * @param database initialised Question Bank database
	 * @throws NullPointerException if database is null
	 */
	public LegacyExamRegistrationService(SqliteDatabase database) {
		if (database == null) {
			throw new NullPointerException("database");
		}

		importer = new LegacyQuestionMetadataImporter(database);
		writer = new SqliteExamWriter(database);
	}

	/**
	 * Creates or reuses all examinations identified by a confirmed workbook.
	 *
	 * <p>
	 * Booklet requirements and historical classification codes are checked before
	 * examination registration. No booklet or question is created by this
	 * operation.
	 *
	 * @param workbookPath retained legacy workbook
	 * @param subject      authoritative Working Subject
	 * @param syllabusName historical syllabus used for classifications
	 * @param mappings     confirmed worksheet/year examination identities
	 * @return distinct persisted examinations
	 * @throws IOException              if the workbook cannot be read
	 * @throws SQLException             if validation or persistence fails
	 * @throws NullPointerException     if a required argument is null
	 * @throws IllegalArgumentException if workbook or mapping is invalid
	 */
	public List<Exam> prepareExams(Path workbookPath, Subject subject, String syllabusName,
			Map<LegacyExamKey, LegacyExamIdentity> mappings) throws IOException, SQLException {

		if (workbookPath == null) {
			throw new NullPointerException("workbookPath");
		}
		if (subject == null) {
			throw new NullPointerException("subject");
		}
		if (syllabusName == null || syllabusName.isBlank()) {
			throw new IllegalArgumentException("Syllabus name must not be blank");
		}
		if (mappings == null) {
			throw new NullPointerException("mappings");
		}

		// This validates every worksheet/year mapping and every
		// classification before any Exam record is created.
		List<LegacyMappedBookletRequirement> requirements = importer.findRequiredBookletsMapped(workbookPath,
				subject.getName(), syllabusName, mappings);

		if (requirements.isEmpty()) {
			throw new IllegalArgumentException("The legacy workbook contains no question records");
		}

		Set<ExamRegistration> registrations = new LinkedHashSet<>();

		for (LegacyMappedBookletRequirement requirement : requirements) {

			registrations.add(new ExamRegistration(requirement.identity().providerName(), requirement.key().year(),
					requirement.identity().examName()));
		}

		// A single transaction creates all missing Exams and
		// reuses every existing natural-key match.
		return writer.findOrCreateExams(subject, List.copyOf(registrations));
	}
}
