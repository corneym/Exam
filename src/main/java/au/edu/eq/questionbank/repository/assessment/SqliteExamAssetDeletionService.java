package au.edu.eq.questionbank.repository.assessment;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import au.edu.eq.questionbank.model.AnswerFile;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

/**
 * Deletes persisted Exam assets and all metadata that depends on those assets.
 * <p>
 * Deletion is structural and is therefore permitted only while the owning Exam
 * is ACTIVE. Database cleanup is transactional. Managed-file deletion remains
 * an application responsibility after this service reports that the
 * corresponding SourceDocument became unreferenced.
 */
public final class SqliteExamAssetDeletionService {

	private final SqliteDatabase database;
	private final SqliteExamWriter examWriter;

	/**
	 * Creates the deletion service.
	 *
	 * @param database   authoritative question-bank database
	 * @param examWriter Exam writer used to enforce lifecycle structural locking
	 */
	public SqliteExamAssetDeletionService(SqliteDatabase database, SqliteExamWriter examWriter) {
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
	 * Deletes one AnswerFile, every Answer that depends on it, and all booklet
	 * assignments to it.
	 *
	 * @param answerFile persisted AnswerFile to remove
	 * @return managed-source cleanup information
	 * @throws SQLException         if persistence fails
	 * @throws NullPointerException if {@code answerFile} is {@code null}
	 */
	public DeletionResult deleteAnswerFile(AnswerFile answerFile) throws SQLException {
		if (answerFile == null) {
			throw new NullPointerException("answerFile");
		}
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				AssetIdentity identity = loadAnswerFileIdentity(connection, answerFile);
				examWriter.requireExamActive(connection, answerFile.getExam().getId());

				// An AnswerFile can be authoritative through booklet assignment, stored
				// Answer regions, or both. Capture the affected Answer identities before
				// either relationship is removed.
				List<Long> answerIds = findAffectedAnswerIds(connection, answerFile.getId());
				deleteAnswers(connection, answerIds);
				execute(connection, """
						UPDATE exam_booklets
						SET answer_file_id = NULL
						WHERE answer_file_id = ?
						""", answerFile.getId());
				int deleted = execute(connection, """
						DELETE FROM answer_files
						WHERE id = ?
						""", answerFile.getId());
				if (deleted != 1) {
					throw new IllegalStateException("Answer booklet no longer exists: " + answerFile.getId());
				}
				boolean sourceDocumentDeleted = deleteSourceDocumentIfUnreferenced(connection,
						identity.sourceDocumentId());
				connection.commit();
				return new DeletionResult(identity.relativePath(), sourceDocumentDeleted);
			} catch (SQLException | RuntimeException exception) {
				rollback(connection, exception);
				throw exception;
			}
		}
	}

	/**
	 * Deletes one Question booklet and every Question-side or Answer-side record
	 * owned through that booklet.
	 *
	 * @param booklet persisted Question booklet to remove
	 * @return managed-source cleanup information
	 * @throws SQLException         if persistence fails
	 * @throws NullPointerException if {@code booklet} is {@code null}
	 */
	public DeletionResult deleteQuestionBooklet(ExamBooklet booklet) throws SQLException {
		if (booklet == null) {
			throw new NullPointerException("booklet");
		}
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				AssetIdentity identity = loadBookletIdentity(connection, booklet);
				examWriter.requireExamActive(connection, booklet.getExam().getId());

				// Independent-MCQ continuation can itself reference a Shared Context
				// belonging to the booklet, so release that structural reference first.
				execute(connection, """
						UPDATE exam_booklets
						SET pending_mcq_shared_context_id = NULL
						WHERE id = ?
						""", booklet.getId());
				deleteQuestionBookletDependants(connection, booklet.getId());
				int deleted = execute(connection, """
						DELETE FROM exam_booklets
						WHERE id = ?
						""", booklet.getId());
				if (deleted != 1) {
					throw new IllegalStateException("Question booklet no longer exists: " + booklet.getId());
				}
				boolean sourceDocumentDeleted = deleteSourceDocumentIfUnreferenced(connection,
						identity.sourceDocumentId());
				connection.commit();
				return new DeletionResult(identity.relativePath(), sourceDocumentDeleted);
			} catch (SQLException | RuntimeException exception) {
				rollback(connection, exception);
				throw exception;
			}
		}
	}

	private void deleteAnswers(Connection connection, List<Long> answerIds) throws SQLException {
		if (answerIds.isEmpty()) {
			return;
		}
		try (PreparedStatement regions = connection.prepareStatement("""
				DELETE FROM answer_regions
				WHERE answer_id = ?
				"""); PreparedStatement answers = connection.prepareStatement("""
				DELETE FROM answers
				WHERE id = ?
				""")) {
			for (long answerId : answerIds) {

				// Remove region dependencies before their owning Answer row because the
				// original core schema intentionally has no delete cascade here.
				regions.setLong(1, answerId);
				regions.executeUpdate();
				answers.setLong(1, answerId);
				answers.executeUpdate();
			}
		}
	}

	private void deleteQuestionBookletDependants(Connection connection, long bookletId) throws SQLException {

		// Answer data depends on Questions, so remove it before deleting Question
		// identities.
		execute(connection, """
				DELETE FROM answer_regions
				WHERE answer_id IN (
				    SELECT a.id
				    FROM answers a
				    JOIN questions q
				        ON q.id = a.question_id
				    WHERE q.booklet_id = ?
				)
				""", bookletId);
		execute(connection, """
				DELETE FROM answers
				WHERE question_id IN (
				    SELECT id
				    FROM questions
				    WHERE booklet_id = ?
				)
				""", bookletId);

		// Version-12 applicability exceptions and version-14 mixed content are removed
		// explicitly rather than depending on SQLite cascade configuration.
		execute(connection, """
				DELETE FROM question_output_exclusions
				WHERE question_id IN (
				    SELECT id
				    FROM questions
				    WHERE booklet_id = ?
				)
				""", bookletId);
		execute(connection, """
				DELETE FROM question_content_parts
				WHERE question_id IN (
				    SELECT id
				    FROM questions
				    WHERE booklet_id = ?
				)
				""", bookletId);
		execute(connection, """
				DELETE FROM question_images
				WHERE question_id IN (
				    SELECT id
				    FROM questions
				    WHERE booklet_id = ?
				)
				""", bookletId);
		execute(connection, """
				DELETE FROM question_regions
				WHERE booklet_id = ?
				   OR question_id IN (
				       SELECT id
				       FROM questions
				       WHERE booklet_id = ?
				   )
				""", bookletId, bookletId);

		// Questions reference SourceQuestion and Shared Context identities, so remove
		// Questions before removing those shared source structures.
		execute(connection, """
				DELETE FROM questions
				WHERE booklet_id = ?
				""", bookletId);
		execute(connection, """
				DELETE FROM shared_question_context_regions
				WHERE shared_context_id IN (
				    SELECT id
				    FROM shared_question_contexts
				    WHERE booklet_id = ?
				)
				""", bookletId);
		execute(connection, """
				DELETE FROM shared_question_contexts
				WHERE booklet_id = ?
				""", bookletId);
		execute(connection, """
				DELETE FROM source_questions
				WHERE booklet_id = ?
				""", bookletId);
	}

	private boolean deleteSourceDocumentIfUnreferenced(Connection connection, long sourceDocumentId)
			throws SQLException {

		// Physical source metadata survives whenever any Question or Answer asset
		// still shares it.
		return execute(connection, """
				DELETE FROM source_documents
				WHERE id = ?
				  AND NOT EXISTS (
				      SELECT 1
				      FROM exam_booklets
				      WHERE source_document_id = ?
				  )
				  AND NOT EXISTS (
				      SELECT 1
				      FROM answer_files
				      WHERE source_document_id = ?
				  )
				""", sourceDocumentId, sourceDocumentId, sourceDocumentId) == 1;
	}

	private int execute(Connection connection, String sql, long... values) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			for (int index = 0; index < values.length; index++) {

				// SQL helpers receive only fixed application statements; the values remain
				// ordinary bound parameters.
				statement.setLong(index + 1, values[index]);
			}
			return statement.executeUpdate();
		}
	}

	private List<Long> findAffectedAnswerIds(Connection connection, long answerFileId) throws SQLException {
		List<Long> answerIds = new ArrayList<>();
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT DISTINCT a.id
				FROM answers a
				JOIN questions q
				    ON q.id = a.question_id
				JOIN exam_booklets eb
				    ON eb.id = q.booklet_id
				LEFT JOIN answer_regions ar
				    ON ar.answer_id = a.id
				WHERE eb.answer_file_id = ?
				   OR ar.answer_file_id = ?
				ORDER BY a.id
				""")) {
			statement.setLong(1, answerFileId);
			statement.setLong(2, answerFileId);
			try (ResultSet result = statement.executeQuery()) {
				while (result.next()) {

					// Freeze the affected Answer identities before assignments and regions
					// are destroyed later in the transaction.
					answerIds.add(result.getLong("id"));
				}
			}
		}
		return List.copyOf(answerIds);
	}

	private AssetIdentity loadAnswerFileIdentity(Connection connection, AnswerFile answerFile) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT af.exam_id, af.source_document_id, sd.relative_path
				FROM answer_files af
				JOIN source_documents sd
				    ON sd.id = af.source_document_id
				WHERE af.id = ?
				""")) {
			statement.setLong(1, answerFile.getId());
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new IllegalArgumentException("Answer booklet does not exist: " + answerFile.getId());
				}
				if (result.getLong("exam_id") != answerFile.getExam().getId()) {
					throw new IllegalArgumentException("Answer booklet belongs to a different Exam");
				}

				// Return the authoritative persisted source identity rather than trusting a
				// potentially stale presentation object.
				return new AssetIdentity(result.getLong("source_document_id"), result.getString("relative_path"));
			}
		}
	}

	private AssetIdentity loadBookletIdentity(Connection connection, ExamBooklet booklet) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT eb.exam_id, eb.source_document_id, sd.relative_path
				FROM exam_booklets eb
				JOIN source_documents sd
				    ON sd.id = eb.source_document_id
				WHERE eb.id = ?
				""")) {
			statement.setLong(1, booklet.getId());
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new IllegalArgumentException("Question booklet does not exist: " + booklet.getId());
				}
				if (result.getLong("exam_id") != booklet.getExam().getId()) {
					throw new IllegalArgumentException("Question booklet belongs to a different Exam");
				}

				// Persistent source identity determines which managed file may later be
				// removed by the application.
				return new AssetIdentity(result.getLong("source_document_id"), result.getString("relative_path"));
			}
		}
	}

	private void rollback(Connection connection, Exception failure) {
		try {
			connection.rollback();
		} catch (SQLException rollbackFailure) {

			// Preserve rollback failure without concealing the original deletion error.
			failure.addSuppressed(rollbackFailure);
		}
	}

	/**
	 * Result of one committed asset deletion.
	 *
	 * @param relativePath          managed source path formerly owned by the asset
	 * @param sourceDocumentDeleted whether no remaining asset references that
	 *                              source
	 */
	public record DeletionResult(String relativePath, boolean sourceDocumentDeleted) {

		/**
		 * Validates deletion cleanup information.
		 */
		public DeletionResult {
			if (relativePath == null || relativePath.isBlank()) {
				throw new IllegalArgumentException("relativePath must not be blank");
			}
		}
	}

	private record AssetIdentity(long sourceDocumentId, String relativePath) {
	}
}
