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
import java.util.Set;

import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.SourceQuestionCodeParser;
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
				ImportContext context = findImportContext(connection, subjectName, syllabusName);
				List<ResolvedQuestion> questions = resolveQuestions(connection, sheets, context);
				LegacyQuestionImportResult result = writeQuestions(connection, questions);
				connection.commit();
				return result;
			} catch (SQLException | RuntimeException e) {
				try {
					connection.rollback();
				} catch (SQLException rollbackFailure) {
					e.addSuppressed(rollbackFailure);
				}
				throw e;
			}
		}
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

	private long findBookletId(Connection connection, long subjectId, String providerName, LegacyQuestionRow row)
			throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT eb.id
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
				long bookletId = result.getLong("id");
				if (result.next()) {
					throw new IllegalArgumentException(
							"More than one exam booklet matches " + description(providerName, row));
				}
				return bookletId;
			}
		}
	}

	private long findClassificationNodeId(Connection connection, long syllabusVersionId, String providerName,
			LegacyQuestionRow row) throws SQLException {
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
				    id,
				    classification_node_id,
				    marks,
				    preamble_capture_required,
				    source_question_id,
				    response_type
				FROM questions
				WHERE booklet_id = ?
				  AND question_code = ?
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
				return new ExistingQuestion(result.getLong("id"), result.getLong("classification_node_id"),
						result.getInt("marks"), result.getInt("preamble_capture_required") != 0, sourceQuestionId,
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

	private Long findOrCreateSourceQuestionId(Connection connection, long bookletId, String questionCode)
			throws SQLException {
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
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO questions
				    (booklet_id,
				     classification_node_id,
				     question_code,
				     question_text,
				     marks,
				     preamble_capture_required,
				     source_question_id,
				     response_type)
				VALUES (?, ?, ?, '', ?, ?, ?, ?)
				RETURNING id
				""")) {
			statement.setLong(1, question.bookletId());
			statement.setLong(2, question.classificationNodeId());
			statement.setString(3, question.questionCode());
			statement.setInt(4, question.marks());
			statement.setInt(5, question.preambleCaptureRequired() ? 1 : 0);
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

	private List<ResolvedQuestion> resolveQuestions(Connection connection, List<LegacyQuestionSheet> sheets,
			ImportContext context) throws SQLException {
		List<ResolvedQuestion> resolved = new ArrayList<>();
		Set<QuestionKey> workbookQuestions = new HashSet<>();
		for (LegacyQuestionSheet sheet : sheets) {
			for (LegacyQuestionRow row : sheet.questions()) {
				long bookletId = findBookletId(connection, context.subjectId(), sheet.providerName(), row);
				long classificationNodeId = findClassificationNodeId(connection, context.syllabusVersionId(),
						sheet.providerName(), row);
				QuestionKey key = new QuestionKey(bookletId, row.questionCode());
				if (!workbookQuestions.add(key)) {
					throw new IllegalArgumentException(
							"Duplicate workbook question: " + description(sheet.providerName(), row));
				}
				Long sourceQuestionId = findOrCreateSourceQuestionId(connection, bookletId, row.questionCode());
				QuestionResponseType importedResponseType = responseType(row.paperCode());
				ExistingQuestion existing = findExistingQuestion(connection, bookletId, row.questionCode());
				Long existingQuestionId = null;
				boolean insertAnswer = row.answer() != null;
				boolean updateExistingResponseType = false;
				if (existing != null) {
					verifyExistingQuestion(existing, classificationNodeId, sourceQuestionId, sheet.providerName(), row);
					existingQuestionId = existing.id();
					/*
					 * UNKNOWN means no authoritative decision has yet been made. Explicit MCQ
					 * workbook evidence may therefore resolve it.
					 *
					 * A non-UNKNOWN value is deliberately preserved because it may have been
					 * corrected after the legacy import.
					 */
					updateExistingResponseType = existing.responseType() == QuestionResponseType.UNKNOWN
							&& importedResponseType != QuestionResponseType.UNKNOWN;
					if (row.answer() != null) {
						ExistingAnswer existingAnswer = findExistingAnswer(connection, existing.id());
						if (existingAnswer.exists()) {
							if (!row.answer().equals(existingAnswer.answerText())) {
								throw new IllegalArgumentException(
										"Existing answer conflicts with " + description(sheet.providerName(), row));
							}
							insertAnswer = false;
						}
					}
				}
				resolved.add(new ResolvedQuestion(bookletId, classificationNodeId, sheet.providerName(), row.year(),
						row.paperCode(), importedResponseType, row.questionCode(), row.marks(), row.answer(),
						row.preambleCaptureRequired(), sourceQuestionId, existingQuestionId, insertAnswer,
						updateExistingResponseType));
			}
		}
		return resolved;
	}

	private QuestionResponseType responseType(String paperCode) {
		return switch (paperCode) {
		case "MCQ" -> QuestionResponseType.MULTIPLE_CHOICE;
		case "1", "2" -> QuestionResponseType.UNKNOWN;
		default -> throw new IllegalArgumentException("Unsupported paper code: " + paperCode);
		};
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

	private void verifyExistingQuestion(ExistingQuestion existing, long classificationNodeId, Long sourceQuestionId,
			String providerName, LegacyQuestionRow row) {
		String description = description(providerName, row);
		if (existing.classificationNodeId() != classificationNodeId) {
			throw new IllegalArgumentException("Existing classification conflicts with " + description);
		}
		if (existing.marks() != row.marks()) {
			throw new IllegalArgumentException("Existing marks conflict with " + description);
		}
		if (existing.preambleCaptureRequired() != row.preambleCaptureRequired()) {
			throw new IllegalArgumentException("Existing preamble metadata conflicts with " + description);
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
	}

	private record ExistingQuestion(long id, long classificationNodeId, int marks, boolean preambleCaptureRequired,
			Long sourceQuestionId, QuestionResponseType responseType) {
	}

	private record ImportContext(long subjectId, long syllabusVersionId) {
	}

	private record QuestionKey(long bookletId, String questionCode) {
	}

	private record ResolvedQuestion(long bookletId, long classificationNodeId, String providerName, int year,
			String paperCode, QuestionResponseType responseType, String questionCode, int marks, String answer,
			boolean preambleCaptureRequired, Long sourceQuestionId, Long existingQuestionId, boolean insertAnswer,
			boolean updateExistingResponseType) {
	}
}
