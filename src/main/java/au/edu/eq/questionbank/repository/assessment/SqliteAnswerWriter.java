package au.edu.eq.questionbank.repository.assessment;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import au.edu.eq.questionbank.model.Answer;
import au.edu.eq.questionbank.model.AnswerFile;
import au.edu.eq.questionbank.model.AnswerRegion;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

/**
 * Persists answer source files, answers, and ordered answer regions in SQLite.
 * Multi-row writes are committed atomically.
 */
public final class SqliteAnswerWriter {

	private final SqliteDatabase database;
	private final SqliteExamWriter examWriter;

	/**
	 * Creates an answer writer.
	 *
	 * @param database   the question-bank database
	 * @param examWriter the writer used for shared source-document records
	 * @throws NullPointerException if either argument is {@code null}
	 */
	public SqliteAnswerWriter(SqliteDatabase database, SqliteExamWriter examWriter) {
		if (database == null) {
			throw new NullPointerException("database");
		}
		if (examWriter == null) {
			throw new NullPointerException("examWriter");
		}
		this.database = database;
		this.examWriter = examWriter;
	}

	/**
	 * Finds the persisted answer source files registered for an exam, ordered by
	 * persistent identifier.
	 *
	 * @param exam the persisted exam whose answer files are required
	 * @return answer files reconstructed with the supplied exam
	 * @throws NullPointerException if {@code exam} is {@code null}
	 * @throws SQLException         if the files cannot be read
	 */
	public List<AnswerFile> findAnswerFiles(Exam exam) throws SQLException {
		if (exam == null) {
			throw new NullPointerException("exam");
		}
		List<AnswerFile> answerFiles = new ArrayList<>();
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT
						    af.id AS answer_file_id,
						    af.answer_file_name,
						    sd.id AS source_document_id,
						    sd.relative_path
						FROM answer_files af
						JOIN source_documents sd
						    ON sd.id = af.source_document_id
						WHERE af.exam_id = ?
						ORDER BY af.id
						""")) {
			statement.setLong(1, exam.getId());
			try (ResultSet result = statement.executeQuery()) {
				while (result.next()) {
					SourceDocument sourceDocument = new SourceDocument(result.getLong("source_document_id"),
							result.getString("relative_path"));
					answerFiles.add(new AnswerFile(result.getLong("answer_file_id"), exam,
							result.getString("answer_file_name"), sourceDocument));
				}
			}
		}
		return answerFiles;
	}

	/**
	 * Finds or creates an answer file for an exam and source document.
	 *
	 * @param exam         the exam whose answers the file contains
	 * @param name         the non-blank answer-file name
	 * @param relativePath the non-blank data-root-relative source path
	 * @return the existing or newly stored answer file
	 * @throws SQLException             if the transaction cannot be completed
	 * @throws NullPointerException     if {@code exam} is {@code null}
	 * @throws IllegalArgumentException if a string argument is null or blank
	 */
	public AnswerFile findOrCreateAnswerFile(Exam exam, String name, String relativePath) throws SQLException {
		if (exam == null) {
			throw new NullPointerException("exam");
		}
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("name must not be blank");
		}
		if (relativePath == null || relativePath.isBlank()) {
			throw new IllegalArgumentException("relativePath must not be blank");
		}
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				SourceDocument sourceDocument = examWriter.findSourceDocumentByPath(connection, relativePath);
				if (sourceDocument == null) {
					sourceDocument = examWriter.insertSourceDocument(connection, relativePath);
				}
				AnswerFile answerFile = findAnswerFile(connection, exam, name, sourceDocument);
				if (answerFile == null) {
					answerFile = insertAnswerFile(connection, exam, name, sourceDocument);
				}
				connection.commit();
				return answerFile;
			} catch (SQLException | RuntimeException e) {
				connection.rollback();
				throw e;
			}
		}
	}

	/**
	 * Stores one answer and its regions in list order.
	 *
	 * @param question   the persisted question being answered
	 * @param answerText optional answer text; a region-only answer may use
	 *                   {@code null}
	 * @param regions    answer regions in display order
	 * @return the stored answer with its generated identifier
	 * @throws SQLException             if the transaction cannot be completed
	 * @throws NullPointerException     if {@code question}, {@code regions}, or a
	 *                                  region is {@code null}
	 * @throws IllegalArgumentException if the answer has neither text nor regions,
	 *                                  or a region belongs to another exam
	 */
	public Answer insertAnswer(Question question, String answerText, List<AnswerRegion> regions) throws SQLException {
		if (question == null) {
			throw new NullPointerException("question");
		}
		if (regions == null) {
			throw new NullPointerException("regions");
		}
		if ((answerText == null || answerText.isBlank()) && regions.isEmpty()) {
			throw new IllegalArgumentException("Answer must contain text or at least one region");
		}
		for (AnswerRegion region : regions) {
			if (region == null) {
				throw new NullPointerException("regions must not contain null");
			}
			if (region.answerFile().getExam().getId() != question.getExam().getId()) {
				throw new IllegalArgumentException("Answer region file must belong to the question's exam");
			}
		}
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				long answerId = insertAnswerRow(connection, question, answerText);
				insertAnswerRegions(connection, answerId, regions);
				Answer answer = new Answer(answerId, answerText, regions);
				connection.commit();
				return answer;
			} catch (SQLException | RuntimeException e) {
				connection.rollback();
				throw e;
			}
		}
	}

	/**
	 * Replaces an existing answer's text and ordered regions while preserving its
	 * persistent identity and question ownership.
	 *
	 * @param question   question owning the answer
	 * @param answerId   persistent answer identifier
	 * @param answerText optional replacement text
	 * @param regions    replacement ordered answer regions
	 * @return the updated answer with its existing identifier
	 * @throws SQLException if the transaction fails
	 */
	public Answer updateAnswer(Question question, long answerId, String answerText, List<AnswerRegion> regions)
			throws SQLException {
		if (question == null) {
			throw new NullPointerException("question");
		}
		if (answerId < 1) {
			throw new IllegalArgumentException("answerId must be positive");
		}
		if (regions == null) {
			throw new NullPointerException("regions");
		}
		if ((answerText == null || answerText.isBlank()) && regions.isEmpty()) {
			throw new IllegalArgumentException("Answer must contain text or at least one region");
		}
		for (AnswerRegion region : regions) {
			if (region == null) {
				throw new NullPointerException("regions must not contain null");
			}
			if (region.answerFile().getExam().getId() != question.getExam().getId()) {
				throw new IllegalArgumentException("Answer region file must belong to the question's exam");
			}
		}
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {

				// Check ownership before replacing text and regions under the existing answer
				// ID.
				verifyAnswerBelongsToQuestion(connection, answerId, question);
				updateAnswerRow(connection, answerId, answerText);

				// Keep deletion and replacement atomic so a failed insert restores the old
				// answer.
				deleteAnswerRegions(connection, answerId);
				insertAnswerRegions(connection, answerId, regions);
				Answer answer = new Answer(answerId, answerText, regions);
				connection.commit();
				return answer;
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

	private void deleteAnswerRegions(Connection connection, long answerId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				DELETE FROM answer_regions
				WHERE answer_id = ?
				""")) {
			statement.setLong(1, answerId);
			statement.executeUpdate();
		}
	}

	private AnswerFile findAnswerFile(Connection connection, Exam exam, String name, SourceDocument sourceDocument)
			throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT id, source_document_id
				FROM answer_files
				WHERE exam_id = ?
				  AND answer_file_name = ?
				""")) {
			statement.setLong(1, exam.getId());
			statement.setString(2, name);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					return null;
				}
				long storedSourceDocumentId = result.getLong("source_document_id");
				if (storedSourceDocumentId != sourceDocument.getId()) {
					throw new SQLException("Existing answer file refers to a different source document");
				}
				return new AnswerFile(result.getLong("id"), exam, name, sourceDocument);
			}
		}
	}

	private AnswerFile insertAnswerFile(Connection connection, Exam exam, String name, SourceDocument sourceDocument)
			throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO answer_files
				    (exam_id, source_document_id, answer_file_name)
				VALUES (?, ?, ?)
				RETURNING id
				""")) {
			statement.setLong(1, exam.getId());
			statement.setLong(2, sourceDocument.getId());
			statement.setString(3, name);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new SQLException("Answer file insert did not return an id");
				}
				return new AnswerFile(result.getLong("id"), exam, name, sourceDocument);
			}
		}
	}

	private void insertAnswerRegions(Connection connection, long answerId, List<AnswerRegion> regions)
			throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO answer_regions
				    (answer_id,
				     region_order,
				     answer_file_id,
				     page_number,
				     x,
				     y,
				     width,
				     height)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?)
				""")) {
			for (int i = 0; i < regions.size(); i++) {
				AnswerRegion region = regions.get(i);
				statement.setLong(1, answerId);
				statement.setInt(2, i);
				statement.setLong(3, region.answerFile().getId());
				statement.setInt(4, region.pageNumber());
				statement.setDouble(5, region.x());
				statement.setDouble(6, region.y());
				statement.setDouble(7, region.width());
				statement.setDouble(8, region.height());
				statement.executeUpdate();
			}
		}
	}

	private long insertAnswerRow(Connection connection, Question question, String answerText) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO answers
				    (question_id, answer_text)
				VALUES (?, ?)
				RETURNING id
				""")) {
			statement.setLong(1, question.getId());
			statement.setString(2, answerText);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new SQLException("Answer insert did not return an id");
				}
				return result.getLong("id");
			}
		}
	}

	private void updateAnswerRow(Connection connection, long answerId, String answerText) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				UPDATE answers
				SET answer_text = ?
				WHERE id = ?
				""")) {
			statement.setString(1, answerText);
			statement.setLong(2, answerId);
			if (statement.executeUpdate() != 1) {
				throw new SQLException("Answer update affected an unexpected number of rows");
			}
		}
	}

	private void verifyAnswerBelongsToQuestion(Connection connection, long answerId, Question question)
			throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT question_id
				FROM answers
				WHERE id = ?
				""")) {
			statement.setLong(1, answerId);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new IllegalArgumentException("Answer does not exist: " + answerId);
				}
				if (result.getLong("question_id") != question.getId()) {
					throw new IllegalArgumentException("Answer does not belong to the supplied question");
				}
			}
		}
	}
}
