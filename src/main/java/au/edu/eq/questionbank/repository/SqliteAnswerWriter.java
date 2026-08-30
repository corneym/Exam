package au.edu.eq.questionbank.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import au.edu.eq.questionbank.model.Answer;
import au.edu.eq.questionbank.model.AnswerFile;
import au.edu.eq.questionbank.model.AnswerRegion;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.SourceDocument;

public final class SqliteAnswerWriter {

	private final SqliteDatabase database;
	private final SqliteExamWriter examWriter;

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

	public Answer insertAnswer(Question question, String answerText, List<AnswerRegion> regions) throws SQLException {
		if (question == null) {
			throw new NullPointerException("question");
		}
		if (regions == null) {
			throw new NullPointerException("regions");
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
}