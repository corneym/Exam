package au.edu.eq.questionbank.importer.legacy;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

public final class LegacyQuestionMetadataImporter {

	private record ExistingAnswer(boolean exists, String answerText) {
	}

	private record ExistingQuestion(long id, long classificationNodeId, int marks, boolean preambleCaptureRequired) {
	}

	private record ImportContext(long subjectId, long syllabusVersionId) {
	}

	private record QuestionKey(long bookletId, String questionCode) {
	}

	private record ResolvedQuestion(long bookletId, long classificationNodeId, String providerName, int year,
			String paperCode, String questionCode, int marks, String answer, boolean preambleCaptureRequired,
			Long existingQuestionId, boolean insertAnswer) {
	}

	private final SqliteDatabase database;
	private final LegacyQuestionWorkbookReader reader;

	public LegacyQuestionMetadataImporter(SqliteDatabase database) {
		if (database == null) {
			throw new NullPointerException("database");
		}
		this.database = database;
		reader = new LegacyQuestionWorkbookReader();
	}

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
				connection.rollback();
				throw e;
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
				    preamble_capture_required
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
				return new ExistingQuestion(result.getLong("id"), result.getLong("classification_node_id"),
						result.getInt("marks"), result.getInt("preamble_capture_required") != 0);
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
				     preamble_capture_required)
				VALUES (?, ?, ?, '', ?, ?)
				RETURNING id
				""")) {
			statement.setLong(1, question.bookletId());
			statement.setLong(2, question.classificationNodeId());
			statement.setString(3, question.questionCode());
			statement.setInt(4, question.marks());
			statement.setInt(5, question.preambleCaptureRequired() ? 1 : 0);
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
				ExistingQuestion existing = findExistingQuestion(connection, bookletId, row.questionCode());
				Long existingQuestionId = null;
				boolean insertAnswer = row.answer() != null;
				if (existing != null) {
					verifyExistingQuestion(existing, classificationNodeId, sheet.providerName(), row);
					existingQuestionId = existing.id();
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
						row.paperCode(), row.questionCode(), row.marks(), row.answer(), row.preambleCaptureRequired(),
						existingQuestionId, insertAnswer));
			}
		}
		return resolved;
	}

	private void verifyExistingQuestion(ExistingQuestion existing, long classificationNodeId, String providerName,
			LegacyQuestionRow row) {
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
				existingQuestions++;
			}
			if (question.insertAnswer()) {
				insertAnswer(connection, questionId, question.answer());
				insertedAnswers++;
			}
		}
		return new LegacyQuestionImportResult(insertedQuestions, existingQuestions, insertedAnswers);
	}
}
