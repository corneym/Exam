package au.edu.eq.questionbank.importer.legacy;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import au.edu.eq.questionbank.model.ExamBookletQuestionFormat;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.SourceQuestionCodeParser;
import au.edu.eq.questionbank.repository.assessment.LegacyExamImportEligibility;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

/**
 * Preflights and atomically imports validated legacy question metadata into an
 * existing subject, syllabus, and exam-booklet hierarchy.
 * <p>
 * Question identity is {@code (booklet, question code)}. Re-import reuses
 * compatible questions and answers, while conflicting persisted metadata is
 * rejected without partially importing the workbook.
 */
public final class LegacyQuestionMetadataImporter {

	private final SqliteDatabase database;
	private final LegacyQuestionWorkbookReader reader;

	/**
	 * Creates an importer for an initialised question-bank database.
	 *
	 * @param database the target database
	 * @throws NullPointerException if {@code database} is {@code null}
	 */
	public LegacyQuestionMetadataImporter(SqliteDatabase database) {
		if (database == null) {
			throw new NullPointerException("database");
		}
		this.database = database;
		reader = new LegacyQuestionWorkbookReader();
	}

	/**
	 * Validates workbook classifications and reports booklets that must exist
	 * before question metadata can be imported.
	 *
	 * @param workbookPath the legacy workbook
	 * @param subjectName  the existing subject receiving the questions
	 * @param syllabusName the existing historical syllabus used by workbook topic
	 *                     codes
	 * @return immutable, provider/year/booklet-sorted missing requirements
	 * @throws IOException              if the workbook cannot be read
	 * @throws SQLException             if preflight queries fail
	 * @throws NullPointerException     if {@code workbookPath} is {@code null}
	 * @throws IllegalArgumentException if import context, workbook data, or an
	 *                                  existing booklet match is invalid
	 */
	public List<LegacyBookletRequirement> findMissingBooklets(Path workbookPath, String subjectName,
			String syllabusName) throws IOException, SQLException {
		if (subjectName == null || subjectName.isBlank()) {
			throw new IllegalArgumentException("subjectName must not be blank");
		}
		if (syllabusName == null || syllabusName.isBlank()) {
			throw new IllegalArgumentException("syllabusName must not be blank");
		}
		List<LegacyQuestionSheet> sheets = reader.read(workbookPath);
		try (Connection connection = database.openConnection()) {
			ImportContext context = findImportContext(connection, subjectName, syllabusName);
			Set<LegacyBookletRequirement> missing = new LinkedHashSet<>();

			// Validate classifications even when a row's booklet still needs to be
			// supplied.
			for (LegacyQuestionSheet sheet : sheets) {
				for (LegacyQuestionRow row : sheet.questions()) {
					findClassificationNodeId(connection, context.syllabusVersionId(), sheet.providerName(), row);
					if (!bookletExists(connection, context.subjectId(), sheet.providerName(), row)) {
						missing.add(new LegacyBookletRequirement(sheet.providerName(), row.year(),
								bookletName(row.paperCode())));
					}
				}
			}
			List<LegacyBookletRequirement> result = new ArrayList<>(missing);
			result.sort(Comparator.comparing(LegacyBookletRequirement::providerName)
					.thenComparingInt(LegacyBookletRequirement::year)
					.thenComparing(LegacyBookletRequirement::bookletName));
			return List.copyOf(result);
		}
	}

	/**
	 * Finds missing Question booklets using conventional booklet names and
	 * confirmed examination identities.
	 *
	 * @param workbookPath source workbook
	 * @param subjectName  existing Subject
	 * @param syllabusName historical syllabus
	 * @param mappings     confirmed examination identities
	 * @return outstanding booklet requirements
	 * @throws IOException  if the workbook cannot be read
	 * @throws SQLException if persistence queries fail
	 */
	public List<LegacyMappedBookletRequirement> findMissingBookletsMapped(Path workbookPath, String subjectName,
			String syllabusName, Map<LegacyExamKey, LegacyExamIdentity> mappings) throws IOException, SQLException {
		return findMissingBookletsMapped(workbookPath, subjectName, syllabusName, mappings, Map.of());
	}

	/**
	 * Preflights a mapped legacy import with optional, explicitly selected
	 * persisted Question Booklet identities.
	 *
	 * @param workbookPath      source workbook
	 * @param subjectName       existing Subject
	 * @param syllabusName      historical syllabus
	 * @param mappings          confirmed examination identities
	 * @param bookletSelections persisted booklet IDs keyed by source paper
	 *                          requirement; absent entries retain conventional name
	 *                          matching
	 * @return outstanding booklet requirements
	 * @throws IOException              if the workbook cannot be read
	 * @throws SQLException             if database queries fail
	 * @throws IllegalArgumentException if a chosen booklet does not belong to its
	 *                                  confirmed examination
	 */
	public List<LegacyMappedBookletRequirement> findMissingBookletsMapped(Path workbookPath, String subjectName,
			String syllabusName, Map<LegacyExamKey, LegacyExamIdentity> mappings,
			Map<LegacyMappedBookletRequirement, Long> bookletSelections) throws IOException, SQLException {
		List<LegacyQuestionSheet> sheets = reader.read(workbookPath);
		validateMappings(sheets, mappings);
		validateBookletSelections(sheets, mappings, bookletSelections);
		Map<LegacyMappedBookletRequirement, Long> selections = Map.copyOf(bookletSelections);
		Set<LegacyMappedBookletRequirement> missing = new LinkedHashSet<>();
		try (Connection connection = database.openConnection()) {
			ImportContext context = findImportContext(connection, subjectName, syllabusName);
			for (LegacyQuestionSheet sheet : sheets) {
				for (LegacyQuestionRow row : sheet.questions()) {
					LegacyExamKey key = new LegacyExamKey(sheet.providerName(), row.year());
					LegacyExamIdentity identity = mappings.get(key);
					findClassificationNodeId(connection, context.syllabusVersionId(), identity.providerName(), row);
					LegacyMappedBookletRequirement requirement = new LegacyMappedBookletRequirement(key, identity,
							bookletName(row.paperCode()));
					Long selectedId = selections.get(requirement);
					if (selectedId != null) {

						// Explicit choices must be validated against the
						// authoritative persisted Exam, not just the ID.
						findSelectedBooklet(connection, context.subjectId(), requirement, selectedId.longValue());
					} else if (!bookletExists(connection, context.subjectId(), identity, row.year(),
							requirement.bookletName())) {

						// A conventional-name match was not available.
						missing.add(requirement);
					}
				}
			}
		}
		List<LegacyMappedBookletRequirement> result = new ArrayList<>(missing);
		result.sort(Comparator
				.comparing((LegacyMappedBookletRequirement requirement) -> requirement.identity().providerName())
				.thenComparingInt(requirement -> requirement.key().year())
				.thenComparing(requirement -> requirement.identity().examName())
				.thenComparing(LegacyMappedBookletRequirement::bookletName));
		return List.copyOf(result);
	}

	/**
	 * Validates workbook classifications and reports every exam booklet referenced
	 * by the workbook, irrespective of whether it already exists.
	 *
	 * @param workbookPath the legacy workbook
	 * @param subjectName  the existing subject receiving the questions
	 * @param syllabusName the existing historical syllabus used by workbook topic
	 *                     codes
	 * @return immutable, provider/year/booklet-sorted requirements
	 * @throws IOException              if the workbook cannot be read
	 * @throws SQLException             if validation queries fail
	 * @throws NullPointerException     if {@code workbookPath} is {@code null}
	 * @throws IllegalArgumentException if import context or workbook data is
	 *                                  invalid
	 */
	public List<LegacyBookletRequirement> findRequiredBooklets(Path workbookPath, String subjectName,
			String syllabusName) throws IOException, SQLException {
		if (subjectName == null || subjectName.isBlank()) {
			throw new IllegalArgumentException("subjectName must not be blank");
		}
		if (syllabusName == null || syllabusName.isBlank()) {
			throw new IllegalArgumentException("syllabusName must not be blank");
		}
		List<LegacyQuestionSheet> sheets = reader.read(workbookPath);
		try (Connection connection = database.openConnection()) {
			ImportContext context = findImportContext(connection, subjectName, syllabusName);
			Set<LegacyBookletRequirement> required = new LinkedHashSet<>();

			// Collapse repeated question references into one requirement per
			// provider/year/booklet.
			for (LegacyQuestionSheet sheet : sheets) {
				for (LegacyQuestionRow row : sheet.questions()) {
					findClassificationNodeId(connection, context.syllabusVersionId(), sheet.providerName(), row);
					required.add(new LegacyBookletRequirement(sheet.providerName(), row.year(),
							bookletName(row.paperCode())));
				}
			}
			List<LegacyBookletRequirement> result = new ArrayList<>(required);
			result.sort(Comparator.comparing(LegacyBookletRequirement::providerName)
					.thenComparingInt(LegacyBookletRequirement::year)
					.thenComparing(LegacyBookletRequirement::bookletName));
			return List.copyOf(result);
		}
	}

	/**
	 * Returns every booklet referenced by a mapped workbook, regardless of whether
	 * the booklet already exists.
	 *
	 * @param workbookPath source workbook
	 * @param subjectName  existing subject
	 * @param syllabusName existing historical syllabus
	 * @param mappings     confirmed worksheet/year identities
	 * @return all distinct required booklets
	 * @throws IOException  when workbook reading fails
	 * @throws SQLException when database access fails
	 */
	public List<LegacyMappedBookletRequirement> findRequiredBookletsMapped(Path workbookPath, String subjectName,
			String syllabusName, Map<LegacyExamKey, LegacyExamIdentity> mappings) throws IOException, SQLException {
		List<LegacyQuestionSheet> sheets = reader.read(workbookPath);
		validateMappings(sheets, mappings);
		Set<LegacyMappedBookletRequirement> required = new LinkedHashSet<>();
		try (Connection connection = database.openConnection()) {
			ImportContext context = findImportContext(connection, subjectName, syllabusName);
			for (LegacyQuestionSheet sheet : sheets) {
				for (LegacyQuestionRow row : sheet.questions()) {
					LegacyExamKey key = new LegacyExamKey(sheet.providerName(), row.year());
					LegacyExamIdentity identity = mappings.get(key);
					findClassificationNodeId(connection, context.syllabusVersionId(), identity.providerName(), row);
					required.add(new LegacyMappedBookletRequirement(key, identity, bookletName(row.paperCode())));
				}
			}
		}
		List<LegacyMappedBookletRequirement> result = new ArrayList<>(required);
		result.sort(Comparator.comparing((LegacyMappedBookletRequirement r) -> r.identity().providerName())
				.thenComparingInt(r -> r.key().year()).thenComparing(r -> r.identity().examName())
				.thenComparing(LegacyMappedBookletRequirement::bookletName));
		return List.copyOf(result);
	}

	/**
	 * Imports every workbook row in one SQLite transaction. Existing compatible
	 * questions are reused, supplied answers are added only when absent, and no
	 * placeholder regions are created.
	 *
	 * @param workbookPath the legacy workbook
	 * @param subjectName  the existing subject receiving the questions
	 * @param syllabusName the existing historical syllabus used by workbook topic
	 *                     codes
	 * @return counts of inserted and reused records
	 * @throws IOException              if the workbook cannot be read
	 * @throws SQLException             if the transaction fails
	 * @throws NullPointerException     if {@code workbookPath} is {@code null}
	 * @throws IllegalArgumentException if import context, workbook data, required
	 *                                  booklets, or existing question metadata is
	 *                                  invalid
	 */
	public LegacyQuestionImportResult importWorkbook(Path workbookPath, String subjectName, String syllabusName)
			throws IOException, SQLException {
		if (subjectName == null || subjectName.isBlank()) {
			throw new IllegalArgumentException("subjectName must not be blank");
		}
		if (syllabusName == null || syllabusName.isBlank()) {
			throw new IllegalArgumentException("syllabusName must not be blank");
		}
		List<LegacyQuestionSheet> sheets = reader.read(workbookPath);
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {

				// Resolution can create multipart source identities, so it belongs inside the
				// transaction.
				ImportContext context = findImportContext(connection, subjectName, syllabusName);
				List<ResolvedQuestion> questions = resolveQuestions(connection, sheets, context);

				// Write questions and answers only after every row has passed compatibility
				// checks.
				LegacyQuestionImportResult result = writeQuestions(connection, questions);
				connection.commit();
				return result;
			} catch (SQLException | RuntimeException e) {
				try {
					connection.rollback();
				} catch (SQLException rollbackFailure) {

					// Keep the original import failure as the primary diagnostic.
					e.addSuppressed(rollbackFailure);
				}
				throw e;
			}
		}
	}

	/**
	 * Imports legacy questions under confirmed examination identities, using
	 * conventional booklet-name resolution.
	 *
	 * @param workbookPath source workbook
	 * @param subjectName  existing Subject
	 * @param syllabusName historical syllabus
	 * @param mappings     confirmed examination identities
	 * @return persisted import counts
	 * @throws IOException  if the workbook cannot be read
	 * @throws SQLException if persistence fails
	 */
	public LegacyQuestionImportResult importWorkbookMapped(Path workbookPath, String subjectName, String syllabusName,
			Map<LegacyExamKey, LegacyExamIdentity> mappings) throws IOException, SQLException {
		return importWorkbookMapped(workbookPath, subjectName, syllabusName, mappings, Map.of());
	}

	/**
	 * Atomically imports legacy questions using confirmed examination identities
	 * and optional explicit persisted booklet selections.
	 *
	 * @param workbookPath      source workbook
	 * @param subjectName       existing Subject
	 * @param syllabusName      historical syllabus
	 * @param mappings          confirmed examination identities
	 * @param bookletSelections booklet IDs keyed by source paper requirement
	 * @return persisted import counts
	 * @throws IOException              if the workbook cannot be read
	 * @throws SQLException             if persistence fails
	 * @throws IllegalArgumentException if a mapping or source booklet conflicts
	 *                                  with the confirmed examination
	 */
	public LegacyQuestionImportResult importWorkbookMapped(Path workbookPath, String subjectName, String syllabusName,
			Map<LegacyExamKey, LegacyExamIdentity> mappings,
			Map<LegacyMappedBookletRequirement, Long> bookletSelections) throws IOException, SQLException {
		List<LegacyQuestionSheet> sheets = reader.read(workbookPath);
		validateMappings(sheets, mappings);
		validateBookletSelections(sheets, mappings, bookletSelections);
		Map<LegacyMappedBookletRequirement, Long> selections = Map.copyOf(bookletSelections);
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				ImportContext context = findImportContext(connection, subjectName, syllabusName);
				List<ResolvedQuestion> questions = resolveQuestions(connection, sheets, context, mappings, selections);

				// No Question or Answer is persisted until every workbook
				// row has passed identity and compatibility validation.
				LegacyQuestionImportResult result = writeQuestions(connection, questions);
				connection.commit();
				return result;
			} catch (SQLException | RuntimeException exception) {
				try {
					connection.rollback();
				} catch (SQLException rollbackFailure) {
					exception.addSuppressed(rollbackFailure);
				}
				throw exception;
			}
		}
	}

	/**
	 * Checks whether the confirmed examination has a uniquely matching Question
	 * booklet, accepting recognised historical booklet aliases.
	 *
	 * @param connection  active database connection
	 * @param subjectId   owning Subject identifier
	 * @param identity    confirmed examination identity
	 * @param year        examination year
	 * @param bookletName conventional booklet name expected by the workbook
	 * @return true when exactly one compatible booklet exists
	 * @throws SQLException             if the lookup fails
	 * @throws IllegalArgumentException if multiple compatible booklets exist
	 */
	private boolean bookletExists(Connection connection, long subjectId, LegacyExamIdentity identity, int year,
			String bookletName) throws SQLException {

		// Preflight must use precisely the same booklet resolution
		// as the final mapped question import.
		return findMappedBooklet(connection, subjectId, identity, year, bookletName) != null;
	}

	private boolean bookletExists(Connection connection, long subjectId, String providerName, LegacyQuestionRow row)
			throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT COUNT(*) AS booklet_count
				FROM exam_booklets eb
				JOIN exams e
				    ON e.id = eb.exam_id
				JOIN exam_providers p
				    ON p.id = e.provider_id
				WHERE e.subject_id = ?
				  AND p.provider_name = ?
				  AND e.exam_year = ?
				  AND eb.booklet_name = ?
				""")) {
			statement.setLong(1, subjectId);
			statement.setString(2, providerName);
			statement.setInt(3, row.year());
			statement.setString(4, bookletName(row.paperCode()));
			try (ResultSet result = statement.executeQuery()) {
				result.next();
				int count = result.getInt("booklet_count");
				if (count > 1) {
					throw new IllegalArgumentException(
							"More than one exam booklet matches " + description(providerName, row));
				}
				return count == 1;
			}
		}
	}

	private String bookletName(String paperCode) {
		switch (paperCode) {
		case "MCQ":
			return "MCQ booklet";
		case "1":
			return "Paper 1";
		case "2":
			return "Paper 2";
		default:
			throw new IllegalArgumentException("Unsupported paper code: " + paperCode);
		}
	}

	private String description(String providerName, LegacyQuestionRow row) {
		return providerName + " " + row.year() + " " + bookletName(row.paperCode()) + " question " + row.questionCode();
	}

	/**
	 * Resolves a legacy workbook row to one persisted Question booklet under its
	 * confirmed examination identity.
	 *
	 * @param connection active database connection
	 * @param subjectId  owning Subject identifier
	 * @param identity   confirmed examination identity
	 * @param row        workbook question row
	 * @return the uniquely resolved booklet
	 * @throws SQLException             if the lookup fails
	 * @throws IllegalArgumentException if no booklet or multiple booklets match
	 */
	private ResolvedBooklet findBooklet(Connection connection, long subjectId, LegacyExamIdentity identity,
			LegacyQuestionRow row) throws SQLException {
		String expectedName = bookletName(row.paperCode());
		ResolvedBooklet booklet = findMappedBooklet(connection, subjectId, identity, row.year(), expectedName);
		if (booklet == null) {
			throw new IllegalArgumentException(
					"No Question booklet is mapped to Paper " + row.paperCode() + " for " + identity.providerName()
							+ " / " + row.year() + " / " + identity.examName() + " / Question " + row.questionCode());
		}
		return booklet;
	}

	private ResolvedBooklet findBooklet(Connection connection, long subjectId, String providerName,
			LegacyQuestionRow row) throws SQLException {

		// The workbook has no assessment name; require one unique authoritative booklet
		// across all Exams matching Subject, provider and year.
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT
				    eb.id,
				    eb.question_format
				FROM exam_booklets eb
				JOIN exams e
				    ON e.id = eb.exam_id
				JOIN exam_providers p
				    ON p.id = e.provider_id
				WHERE e.subject_id = ?
				  AND p.provider_name = ?
				  AND e.exam_year = ?
				  AND eb.booklet_name = ?
				ORDER BY eb.id
				""")) {
			statement.setLong(1, subjectId);
			statement.setString(2, providerName);
			statement.setInt(3, row.year());
			statement.setString(4, bookletName(row.paperCode()));
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new IllegalArgumentException(
							"No existing exam booklet for " + description(providerName, row));
				}

				// Booklet format is authoritative structural evidence and therefore belongs
				// to legacy response-type resolution.
				ResolvedBooklet booklet = new ResolvedBooklet(result.getLong("id"),
						ExamBookletQuestionFormat.valueOf(result.getString("question_format")));
				if (result.next()) {
					throw new IllegalArgumentException(
							"More than one exam booklet matches " + description(providerName, row));
				}
				return booklet;
			}
		}
	}

	private long findClassificationNodeId(Connection connection, long syllabusVersionId, String providerName,
			LegacyQuestionRow row) throws SQLException {

		// Resolve codes in the explicitly selected syllabus, which need not be the
		// current syllabus.
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT id, curriculum_level
				FROM curriculum_nodes
				WHERE syllabus_version_id = ?
				  AND curriculum_code = ?
				""")) {
			statement.setLong(1, syllabusVersionId);
			statement.setString(2, row.classificationCode());
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new IllegalArgumentException("Unknown classification " + row.classificationCode() + " for "
							+ description(providerName, row));
				}
				long classificationNodeId = result.getLong("id");
				String level = result.getString("curriculum_level");

				// Both hierarchy shapes can classify questions: a subtopic or a descriptor is
				// valid.
				if (!"SUBTOPIC".equals(level) && !"DESCRIPTOR".equals(level)) {
					throw new IllegalArgumentException("Classification " + row.classificationCode()
							+ " is not a SUBTOPIC or DESCRIPTOR for " + description(providerName, row));
				}
				if (result.next()) {
					throw new IllegalArgumentException(
							"Duplicate classification code in syllabus: " + row.classificationCode());
				}
				return classificationNodeId;
			}
		}
	}

	private ExistingAnswer findExistingAnswer(Connection connection, long questionId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT answer_text
				FROM answers
				WHERE question_id = ?
				""")) {
			statement.setLong(1, questionId);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					return new ExistingAnswer(false, null);
				}
				return new ExistingAnswer(true, result.getString("answer_text"));
			}
		}
	}

	private ExistingQuestion findExistingQuestion(Connection connection, long bookletId, String questionCode)
			throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT
				    q.id,
				    q.classification_node_id,
				    cn.curriculum_code AS classification_code,
				    q.marks,
				    q.shared_context_capture_required,
				    q.source_question_id,
				    q.response_type
				FROM questions q
				JOIN curriculum_nodes cn
				  ON cn.id = q.classification_node_id
				WHERE q.booklet_id = ?
				  AND q.question_code = ?
				""")) {
			statement.setLong(1, bookletId);
			statement.setString(2, questionCode);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					return null;
				}
				Long sourceQuestionId = null;
				if (result.getObject("source_question_id") != null) {
					sourceQuestionId = Long.valueOf(result.getLong("source_question_id"));
				}

				// Preserve the human-readable classification code as well as its database
				// identity so a merge conflict can explain exactly what must be corrected.
				return new ExistingQuestion(result.getLong("id"), result.getLong("classification_node_id"),
						result.getString("classification_code"), result.getInt("marks"),
						result.getInt("shared_context_capture_required") != 0, sourceQuestionId,
						QuestionResponseType.valueOf(result.getString("response_type")));
			}
		}
	}

	private ImportContext findImportContext(Connection connection, String subjectName, String syllabusName)
			throws SQLException {
		long subjectId;
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT id
				FROM subjects
				WHERE subject_name = ?
				""")) {
			statement.setString(1, subjectName);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new IllegalArgumentException("Unknown subject: " + subjectName);
				}
				subjectId = result.getLong("id");
			}
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT id
				FROM syllabus_versions
				WHERE subject_id = ?
				  AND syllabus_name = ?
				""")) {
			statement.setLong(1, subjectId);
			statement.setString(2, syllabusName);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new IllegalArgumentException(
							"Unknown syllabus " + syllabusName + " for subject " + subjectName);
				}
				return new ImportContext(subjectId, result.getLong("id"));
			}
		}
	}

	/**
	 * Resolves a Question booklet under the exact confirmed Exam identity.
	 *
	 * <p>
	 * Explicit persisted legacy Paper identity takes precedence over the editable
	 * booklet name. Unlinked historical booklets temporarily retain
	 * conventional-name matching during migration to the new UI workflow.
	 *
	 * @param connection   active database connection
	 * @param subjectId    owning Subject identifier
	 * @param identity     confirmed examination identity
	 * @param year         examination year
	 * @param expectedName conventional name derived from workbook Paper
	 * @return resolved booklet, or null when not found
	 * @throws SQLException             if the lookup fails
	 * @throws IllegalArgumentException if conventional matching is ambiguous
	 */
	private ResolvedBooklet findMappedBooklet(Connection connection, long subjectId, LegacyExamIdentity identity,
			int year, String expectedName) throws SQLException {
		String paperCode = switch (expectedName) {
		case "MCQ booklet" -> "MCQ";
		case "Paper 1" -> "1";
		case "Paper 2" -> "2";
		default -> throw new IllegalArgumentException("Unsupported legacy booklet requirement: " + expectedName);
		};
		String alternativeName = "MCQ booklet".equals(expectedName) ? "MCQ" : expectedName;
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT
				    eb.id,
				    eb.question_format,
				    eb.legacy_paper_code
				FROM exam_booklets eb
				JOIN exams e
				    ON e.id = eb.exam_id
				JOIN exam_providers p
				    ON p.id = e.provider_id
				WHERE e.subject_id = ?
				  AND p.provider_name = ?
				  AND e.exam_year = ?
				  AND e.exam_name = ?
				  AND (
				      eb.legacy_paper_code = ?
				      OR (
				          eb.legacy_paper_code IS NULL
				          AND eb.booklet_name IN (?, ?)
				      )
				  )
				ORDER BY eb.id
				""")) {
			statement.setLong(1, subjectId);
			statement.setString(2, identity.providerName());
			statement.setInt(3, year);
			statement.setString(4, identity.examName());
			statement.setString(5, paperCode);
			statement.setString(6, expectedName);
			statement.setString(7, alternativeName);
			try (ResultSet result = statement.executeQuery()) {
				ResolvedBooklet linked = null;
				ResolvedBooklet named = null;
				int namedMatches = 0;
				while (result.next()) {
					ResolvedBooklet candidate = new ResolvedBooklet(result.getLong("id"),
							ExamBookletQuestionFormat.valueOf(result.getString("question_format")));
					String storedPaperCode = result.getString("legacy_paper_code");
					if (paperCode.equals(storedPaperCode)) {

						// The database uniqueness constraint guarantees
						// one explicitly linked booklet per Exam/Paper.
						linked = candidate;
						continue;
					}

					// Only NULL-coded historical booklets are eligible
					// for the temporary conventional-name fallback.
					namedMatches++;
					if (named == null) {
						named = candidate;
					}
				}

				// An explicit Paper assignment is authoritative even
				// when the booklet has subsequently been renamed.
				if (linked != null) {
					return linked;
				}
				if (namedMatches > 1) {
					throw new IllegalArgumentException("Ambiguous booklet identity for " + identity.providerName()
							+ " / " + year + " / " + identity.examName() + " / " + expectedName);
				}
				return named;
			}
		}
	}

	private Long findOrCreateSourceQuestionId(Connection connection, long bookletId, String questionCode)
			throws SQLException {

		// Derive multipart ownership from the question code, not from the
		// shared context-capture hint.
		String sourceQuestionCode = SourceQuestionCodeParser.derive(questionCode);
		if (sourceQuestionCode == null) {
			return null;
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT id
				FROM source_questions
				WHERE booklet_id = ?
				  AND source_question_code = ?
				""")) {
			statement.setLong(1, bookletId);
			statement.setString(2, sourceQuestionCode);
			try (ResultSet result = statement.executeQuery()) {
				if (result.next()) {
					return Long.valueOf(result.getLong("id"));
				}
			}
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO source_questions
				    (booklet_id, source_question_code)
				VALUES (?, ?)
				RETURNING id
				""")) {
			statement.setLong(1, bookletId);
			statement.setString(2, sourceQuestionCode);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new SQLException("Source-question insert did not return an id");
				}
				return Long.valueOf(result.getLong("id"));
			}
		}
	}

	/**
	 * Resolves an explicitly selected persisted Question booklet, requiring the
	 * exact confirmed examination identity.
	 */
	private ResolvedBooklet findSelectedBooklet(Connection connection, long subjectId,
			LegacyMappedBookletRequirement requirement, long bookletId) throws SQLException {
		LegacyExamKey key = requirement.key();
		LegacyExamIdentity identity = requirement.identity();
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT eb.question_format
				FROM exam_booklets eb
				JOIN exams e ON e.id = eb.exam_id
				JOIN exam_providers p ON p.id = e.provider_id
				WHERE eb.id = ?
				  AND e.subject_id = ?
				  AND p.provider_name = ?
				  AND e.exam_year = ?
				  AND e.exam_name = ?
				""")) {
			statement.setLong(1, bookletId);
			statement.setLong(2, subjectId);
			statement.setString(3, identity.providerName());
			statement.setInt(4, key.year());
			statement.setString(5, identity.examName());
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new IllegalArgumentException("Selected booklet ID " + bookletId
							+ " does not belong to the confirmed Exam: " + "worksheet '" + key.worksheetName() + "'"
							+ ", year " + key.year() + ", " + identity.providerName() + " / " + identity.examName()
							+ ", source " + requirement.bookletName());
				}

				// The saved booklet format remains authoritative even
				// when its name differs from the workbook Paper code.
				return new ResolvedBooklet(bookletId,
						ExamBookletQuestionFormat.valueOf(result.getString("question_format")));
			}
		}
	}

	private void insertAnswer(Connection connection, long questionId, String answer) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO answers
				    (question_id, answer_text)
				VALUES (?, ?)
				""")) {
			statement.setLong(1, questionId);
			statement.setString(2, answer);
			statement.executeUpdate();
		}
	}

	private long insertQuestion(Connection connection, ResolvedQuestion question) throws SQLException {

		// Import metadata only; authoritative question content will be supplied by
		// later PDF capture.
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO questions
				    (booklet_id,
				     classification_node_id,
				     question_code,
				     question_text,
				     marks,
				     shared_context_capture_required,
				     source_question_id,
				     response_type)
				VALUES (?, ?, ?, '', ?, ?, ?, ?)
				RETURNING id
				""")) {
			statement.setLong(1, question.bookletId());
			statement.setLong(2, question.classificationNodeId());
			statement.setString(3, question.questionCode());
			statement.setInt(4, question.marks());
			statement.setInt(5, question.sharedContextCaptureRequired() ? 1 : 0);
			if (question.sourceQuestionId() == null) {
				statement.setNull(6, Types.BIGINT);
			} else {
				statement.setLong(6, question.sourceQuestionId().longValue());
			}
			statement.setString(7, question.responseType().name());
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new SQLException("Question insert did not return an id");
				}
				return result.getLong("id");
			}
		}
	}

	private boolean isLegacyMultipleChoiceAnswer(String answer) {
		if (answer == null) {
			return false;
		}

		// A-D is explicit legacy MCQ evidence. Other historical answer text is retained
		// by import but must not silently classify an otherwise ambiguous Question.
		String normalized = answer.strip();
		return normalized.equalsIgnoreCase("A") || normalized.equalsIgnoreCase("B") || normalized.equalsIgnoreCase("C")
				|| normalized.equalsIgnoreCase("D");
	}

	private void requireBookletExamActive(Connection connection, long bookletId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT exam_id
				FROM exam_booklets
				WHERE id = ?
				""")) {
			statement.setLong(1, bookletId);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new IllegalArgumentException("Exam booklet does not exist: " + bookletId);
				}

				// This check is inside the Question import transaction. Even
				// non-UI callers cannot overwrite a developed Exam.
				LegacyExamImportEligibility.requireEligible(connection, result.getLong("exam_id"));
			}
		}
	}

	private List<ResolvedQuestion> resolveQuestions(Connection connection, List<LegacyQuestionSheet> sheets,
			ImportContext context) throws SQLException {
		return resolveQuestions(connection, sheets, context, null);
	}

	private List<ResolvedQuestion> resolveQuestions(Connection connection, List<LegacyQuestionSheet> sheets,
			ImportContext context, Map<LegacyExamKey, LegacyExamIdentity> mappings) throws SQLException {
		return resolveQuestions(connection, sheets, context, mappings, Map.of());
	}

	private List<ResolvedQuestion> resolveQuestions(Connection connection, List<LegacyQuestionSheet> sheets,
			ImportContext context, Map<LegacyExamKey, LegacyExamIdentity> mappings,
			Map<LegacyMappedBookletRequirement, Long> bookletSelections) throws SQLException {
		List<ResolvedQuestion> resolved = new ArrayList<>();
		Set<QuestionKey> workbookQuestions = new HashSet<>();
		for (LegacyQuestionSheet sheet : sheets) {
			for (LegacyQuestionRow row : sheet.questions()) {
				String providerName;
				ResolvedBooklet booklet;
				if (mappings == null) {

					// Preserve the original non-mapped legacy API.
					providerName = sheet.providerName();
					booklet = findBooklet(connection, context.subjectId(), providerName, row);
				} else {
					LegacyExamKey key = new LegacyExamKey(sheet.providerName(), row.year());
					LegacyExamIdentity identity = mappings.get(key);
					providerName = identity.providerName();
					LegacyMappedBookletRequirement requirement = new LegacyMappedBookletRequirement(key, identity,
							bookletName(row.paperCode()));
					Long selectedId = bookletSelections.get(requirement);
					if (selectedId != null) {
						booklet = findSelectedBooklet(connection, context.subjectId(), requirement,
								selectedId.longValue());
					} else {

						// Conventional matching remains the default,
						// including the existing unambiguous MCQ alias.
						booklet = findBooklet(connection, context.subjectId(), identity, row);
					}
				}
				long bookletId = booklet.id();
				requireBookletExamActive(connection, bookletId);
				long classificationNodeId = findClassificationNodeId(connection, context.syllabusVersionId(),
						providerName, row);
				QuestionKey key = new QuestionKey(bookletId, row.questionCode());
				if (!workbookQuestions.add(key)) {
					throw new IllegalArgumentException(
							"Duplicate workbook question: " + description(providerName, row));
				}
				QuestionResponseType importedResponseType = responseType(providerName, row, booklet.questionFormat());
				if (importedResponseType == QuestionResponseType.MULTIPLE_CHOICE && row.marks() != 1) {

					// Match the Question domain invariant before importing any
					// Question or Answer. All earlier workbook rows must roll back.
					String contextDescription = description(providerName, row);
					if (mappings != null) {
						LegacyExamKey examKey = new LegacyExamKey(sheet.providerName(), row.year());
						contextDescription += " (worksheet '" + sheet.providerName() + "', Exam '"
								+ mappings.get(examKey).examName() + "', booklet ID " + bookletId + ")";
					}
					throw new IllegalArgumentException("Multiple-choice questions must be worth exactly 1 mark for "
							+ contextDescription + "; workbook Marks = " + row.marks());
				}
				Long sourceQuestionId = findOrCreateSourceQuestionId(connection, bookletId, row.questionCode());
				ExistingQuestion existing = findExistingQuestion(connection, bookletId, row.questionCode());
				Long existingQuestionId = null;
				boolean insertAnswer = row.answer() != null;
				boolean updateExistingResponseType = false;
				if (existing != null) {
					verifyExistingQuestion(existing, classificationNodeId, sourceQuestionId, providerName, row);
					existingQuestionId = existing.id();
					updateExistingResponseType = existing.responseType() == QuestionResponseType.UNKNOWN
							&& importedResponseType != QuestionResponseType.UNKNOWN;
					insertAnswer = shouldInsertAnswer(connection, existing.id(), providerName, row);
				}
				resolved.add(new ResolvedQuestion(bookletId, classificationNodeId, providerName, row.year(),
						row.paperCode(), importedResponseType, row.questionCode(), row.marks(), row.answer(),
						row.sharedContextCaptureRequired(), sourceQuestionId, existingQuestionId, insertAnswer,
						updateExistingResponseType));
			}
		}
		return resolved;
	}

	private QuestionResponseType responseType(String providerName, LegacyQuestionRow row,
			ExamBookletQuestionFormat bookletFormat) {

		// The dedicated MCQ paper code and a valid A-D answer are both explicit
		// row-level evidence that this legacy Question is multiple choice.
		boolean legacyMultipleChoiceEvidence = "MCQ".equals(row.paperCode())
				|| isLegacyMultipleChoiceAnswer(row.answer());
		if (bookletFormat == ExamBookletQuestionFormat.WRITTEN_RESPONSE && legacyMultipleChoiceEvidence) {

			// Conflicting authoritative structure and workbook evidence must be corrected
			// rather than silently choosing one interpretation during import.
			throw new IllegalArgumentException("Legacy MCQ evidence conflicts with Written Response booklet for "
					+ description(providerName, row));
		}
		return switch (bookletFormat) {
		case MULTIPLE_CHOICE -> QuestionResponseType.MULTIPLE_CHOICE;
		case WRITTEN_RESPONSE -> QuestionResponseType.WRITTEN_RESPONSE;

		// Mixed and older unclassified booklets cannot determine response type alone.
		// Use explicit row evidence where available and leave genuine ambiguity visible
		// to the Dashboard otherwise.
		case MIXED, UNSPECIFIED ->
			legacyMultipleChoiceEvidence ? QuestionResponseType.MULTIPLE_CHOICE : QuestionResponseType.UNKNOWN;
		};
	}

	private boolean shouldInsertAnswer(Connection connection, long questionId, String providerName,
			LegacyQuestionRow row) throws SQLException {

		// A blank workbook answer leaves any persisted answer untouched.
		if (row.answer() == null) {
			return false;
		}
		ExistingAnswer existingAnswer = findExistingAnswer(connection, questionId);
		if (!existingAnswer.exists()) {
			return true;
		}

		// Reuse identical text, but reject conflicts instead of overwriting captured or
		// corrected answers.
		if (!row.answer().equals(existingAnswer.answerText())) {
			throw new IllegalArgumentException("Existing answer conflicts with " + description(providerName, row));
		}
		return false;
	}

	private void updateExistingResponseType(Connection connection, long questionId, QuestionResponseType responseType)
			throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				UPDATE questions
				SET response_type = ?
				WHERE id = ?
				  AND response_type = 'UNKNOWN'
				""")) {
			statement.setString(1, responseType.name());
			statement.setLong(2, questionId);
			int updated = statement.executeUpdate();
			if (updated > 1) {
				throw new SQLException("Response-type update affected an unexpected number of rows");
			}
		}
	}

	private void updateExistingSourceQuestionLink(Connection connection, long questionId, long sourceQuestionId)
			throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				UPDATE questions
				SET source_question_id = ?
				WHERE id = ?
				""")) {
			statement.setLong(1, sourceQuestionId);
			statement.setLong(2, questionId);
			if (statement.executeUpdate() != 1) {
				throw new SQLException("Source-question relationship update affected an unexpected number of rows");
			}
		}
	}

	/**
	 * Rejects selections unrelated to the workbook and invalid booklet IDs before
	 * the import opens its write transaction.
	 */
	private void validateBookletSelections(List<LegacyQuestionSheet> sheets,
			Map<LegacyExamKey, LegacyExamIdentity> mappings,
			Map<LegacyMappedBookletRequirement, Long> bookletSelections) {
		if (bookletSelections == null) {
			throw new NullPointerException("bookletSelections");
		}
		Set<LegacyMappedBookletRequirement> required = new LinkedHashSet<>();
		for (LegacyQuestionSheet sheet : sheets) {
			for (LegacyQuestionRow row : sheet.questions()) {
				LegacyExamKey key = new LegacyExamKey(sheet.providerName(), row.year());
				required.add(new LegacyMappedBookletRequirement(key, mappings.get(key), bookletName(row.paperCode())));
			}
		}
		for (Map.Entry<LegacyMappedBookletRequirement, Long> selection : bookletSelections.entrySet()) {
			LegacyMappedBookletRequirement requirement = selection.getKey();
			Long bookletId = selection.getValue();
			if (requirement == null || !required.contains(requirement)) {
				throw new IllegalArgumentException("Unexpected Paper-to-booklet selection: " + requirement);
			}
			if (bookletId == null || bookletId.longValue() <= 0) {
				throw new IllegalArgumentException("Invalid selected booklet ID for " + requirement + ": " + bookletId);
			}
		}
	}

	private void validateMappings(List<LegacyQuestionSheet> sheets, Map<LegacyExamKey, LegacyExamIdentity> mappings) {
		if (mappings == null) {
			throw new IllegalArgumentException("Confirmed examination mappings are required");
		}
		Set<LegacyExamKey> required = new LinkedHashSet<>();
		for (LegacyQuestionSheet sheet : sheets) {
			for (LegacyQuestionRow row : sheet.questions()) {
				required.add(new LegacyExamKey(sheet.providerName(), row.year()));
			}
		}
		for (LegacyExamKey key : required) {
			LegacyExamIdentity identity = mappings.get(key);
			if (identity == null) {
				throw new IllegalArgumentException("Missing confirmed examination mapping for " + "worksheet '"
						+ key.worksheetName() + "', year " + key.year());
			}
		}

		// Reject stale or unrelated mappings rather than silently
		// incorporating context from another workbook.
		for (LegacyExamKey key : mappings.keySet()) {
			if (key == null || !required.contains(key)) {
				throw new IllegalArgumentException("Unexpected examination mapping: " + key);
			}
		}
	}

	private void verifyExistingQuestion(ExistingQuestion existing, long classificationNodeId, Long sourceQuestionId,
			String providerName, LegacyQuestionRow row) {
		String description = description(providerName, row);
		if (existing.classificationNodeId() != classificationNodeId) {

			// Never overwrite a manually established classification during legacy import.
			// Report both codes so the existing Question can be corrected deliberately.
			throw new IllegalArgumentException(
					"Existing classification %s conflicts with workbook classification %s for %s"
							.formatted(existing.classificationCode(), row.classificationCode(), description));
		}
		if (existing.marks() != row.marks()) {
			throw new IllegalArgumentException("Existing marks conflict with " + description);
		}
		if (existing.sharedContextCaptureRequired() != row.sharedContextCaptureRequired()) {
			throw new IllegalArgumentException("Existing shared context metadata conflicts with " + description);
		}
		if (sourceQuestionId != null && existing.sourceQuestionId() != null
				&& !sourceQuestionId.equals(existing.sourceQuestionId())) {
			throw new IllegalArgumentException("Existing source-question relationship conflicts with " + description);
		}
	}

	private LegacyQuestionImportResult writeQuestions(Connection connection, List<ResolvedQuestion> questions)
			throws SQLException {
		int insertedQuestions = 0;
		int existingQuestions = 0;
		int insertedAnswers = 0;
		for (ResolvedQuestion question : questions) {
			long questionId;
			if (question.existingQuestionId() == null) {
				questionId = insertQuestion(connection, question);
				insertedQuestions++;
			} else {

				// Reuse the question identity and captured content, applying only the resolved
				// metadata updates.
				questionId = question.existingQuestionId();
				if (question.sourceQuestionId() != null) {
					updateExistingSourceQuestionLink(connection, questionId, question.sourceQuestionId().longValue());
				}
				if (question.updateExistingResponseType()) {
					updateExistingResponseType(connection, questionId, question.responseType());
				}
				existingQuestions++;
			}
			if (question.insertAnswer()) {
				insertAnswer(connection, questionId, question.answer());
				insertedAnswers++;
			}
		}
		return new LegacyQuestionImportResult(insertedQuestions, existingQuestions, insertedAnswers);
	}

	private record ExistingAnswer(boolean exists, String answerText) {

		// Legacy re-import distinguishes no stored Answer from an existing Answer whose
		// value must be checked before any additional persistence is attempted.
	}

	private record ResolvedBooklet(long id, ExamBookletQuestionFormat questionFormat) {
	}

	private record ExistingQuestion(long id, long classificationNodeId, String classificationCode, int marks,
			boolean sharedContextCaptureRequired, Long sourceQuestionId, QuestionResponseType responseType) {
	}

	private record ImportContext(long subjectId, long syllabusVersionId) {
	}

	private record QuestionKey(long bookletId, String questionCode) {
	}

	private record ResolvedQuestion(long bookletId, long classificationNodeId, String providerName, int year,
			String paperCode, QuestionResponseType responseType, String questionCode, int marks, String answer,
			boolean sharedContextCaptureRequired, Long sourceQuestionId, Long existingQuestionId, boolean insertAnswer,
			boolean updateExistingResponseType) {
	}
}
