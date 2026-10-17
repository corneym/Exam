package au.edu.eq.questionbank.service.legacy;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.LinkedHashMap;
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
	 * Validates a confirmed legacy workbook, registers its examinations and
	 * establishes the authoritative expected Question booklet counts.
	 *
	 * <p>
	 * Every distinct workbook Paper code counts as one Question booklet within its
	 * confirmed Exam. Repeated question rows do not increase the count. Worksheets
	 * mapped to the same Exam contribute to the same distinct Paper set.
	 *
	 * <p>
	 * Registration and expected-count persistence are atomic. This operation never
	 * creates PDF assets, Question booklets or Questions.
	 *
	 * @param workbookPath retained legacy workbook
	 * @param subject      authoritative Working Subject
	 * @param syllabusName historical syllabus for classification validation
	 * @param mappings     confirmed worksheet/year examination identities
	 * @return distinct persisted examinations
	 * @throws IOException              if the workbook cannot be read
	 * @throws SQLException             if persistence fails
	 * @throws NullPointerException     if a required argument is null
	 * @throws IllegalArgumentException if the workbook or mapping is invalid
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

		// Validate all classifications and confirmed worksheet identities
		// before starting any Exam persistence.
		List<LegacyMappedBookletRequirement> requirements = importer.findRequiredBookletsMapped(workbookPath,
				subject.getName(), syllabusName, mappings);
		if (requirements.isEmpty()) {
			throw new IllegalArgumentException("The legacy workbook contains no question records");
		}
		Map<ExamRegistration, Set<String>> papersByExam = new LinkedHashMap<>();
		for (LegacyMappedBookletRequirement requirement : requirements) {
			ExamRegistration registration = new ExamRegistration(requirement.identity().providerName(),
					requirement.key().year(), requirement.identity().examName());

			// Distinct workbook Papers, not individual question rows,
			// establish the number of expected Question booklets.
			papersByExam.computeIfAbsent(registration, _ -> new LinkedHashSet<>()).add(requirement.bookletName());
		}
		Map<ExamRegistration, Integer> expectedCounts = new LinkedHashMap<>();
		for (Map.Entry<ExamRegistration, Set<String>> entry : papersByExam.entrySet()) {
			expectedCounts.put(entry.getKey(), entry.getValue().size());
		}

		// Registration and planning belong to one SQLite transaction.
		// Existing Answer-file expectations are not modified.
		return writer.findOrCreateExams(subject, List.copyOf(papersByExam.keySet()), expectedCounts);
	}
}
