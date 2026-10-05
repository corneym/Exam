package au.edu.eq.questionbank.repository.assessment;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import au.edu.eq.questionbank.model.AnswerFile;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamCaptureState;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

/**
 * Safely changes the AnswerFile assigned to one ExamBooklet.
 * <p>
 * Answer regions are coordinates within a particular AnswerFile and therefore
 * cannot survive reassignment to another document. Independent textual answer
 * content is retained. Answers that consisted only of invalidated regions are
 * removed so their Questions return naturally to Answer capture. *
 * <p>
 * Reassignment changes only the selected booklet. The old AnswerFile is
 * retained while another booklet or Answer region still refers to it. Once it
 * becomes unreferenced, its AnswerFile row is retired. Its SourceDocument is
 * also retired only when no Question booklet or other AnswerFile still refers
 * to that source.
 */
public final class AnswerFileReassignmentService {

	private final SqliteDatabase database;

	/**
	 * Creates an AnswerFile reassignment service.
	 *
	 * @param database question-bank database
	 * @throws NullPointerException if {@code database} is {@code null}
	 */
	public AnswerFileReassignmentService(SqliteDatabase database) {
		if (database == null) {
			throw new NullPointerException("database");
		}
		this.database = database;
	}

	/**
	 * Reports the persisted Answer content that depends on the AnswerFile currently
	 * assigned to a booklet.
	 *
	 * @param booklet persisted booklet to inspect
	 * @return reassignment impact
	 * @throws SQLException          if persistence cannot be read
	 * @throws NullPointerException  if {@code booklet} is {@code null}
	 * @throws IllegalStateException if the booklet has no assigned AnswerFile
	 */
	public Impact assess(ExamBooklet booklet) throws SQLException {
		if (booklet == null) {
			throw new NullPointerException("booklet");
		}
		try (Connection connection = database.openConnection()) {
			verifyPersistedBooklet(connection, booklet);

			// Asset reassignment is structural. Reject a COMPLETE Exam before the UI
			// selects, copies or registers any replacement managed document.
			verifyExamActive(connection, booklet);
			AnswerFile current = findAssignedAnswerFile(connection, booklet);
			if (current == null) {
				throw new IllegalStateException("Exam booklet has no assigned AnswerFile");
			}
			return readImpact(connection, booklet.getId(), current.getId());
		}
	}

	/**
	 * Reassigns one active Exam booklet to another persisted AnswerFile.
	 * <p>
	 * Regions captured from the old document are deleted. Answers retaining
	 * non-blank independent text remain persisted; region-only Answers are removed.
	 * An old AnswerFile that becomes completely unreferenced is retired in the same
	 * transaction. Its SourceDocument is also retired when no other persisted asset
	 * refers to it.
	 *
	 * @param booklet         booklet whose AnswerFile is being corrected
	 * @param replacementFile replacement AnswerFile belonging to the same Exam
	 * @return committed reassignment result
	 * @throws SQLException             if persistence fails
	 * @throws NullPointerException     if either argument is {@code null}
	 * @throws IllegalArgumentException if either persistent identity is invalid or
	 *                                  the replacement belongs to another Exam
	 * @throws IllegalStateException    if the Exam is complete, no AnswerFile is
	 *                                  currently assigned, or persisted regions
	 *                                  contradict the current assignment
	 */
	public Result reassign(ExamBooklet booklet, AnswerFile replacementFile) throws SQLException {
		if (booklet == null) {
			throw new NullPointerException("booklet");
		}
		if (replacementFile == null) {
			throw new NullPointerException("replacementFile");
		}
		if (booklet.getExam().getId() != replacementFile.getExam().getId()) {
			throw new IllegalArgumentException("Replacement AnswerFile must belong to the booklet's Exam");
		}
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				verifyPersistedBooklet(connection, booklet);
				AnswerFile current = findAssignedAnswerFile(connection, booklet);
				if (current == null) {
					throw new IllegalStateException("Exam booklet has no assigned AnswerFile");
				}
				AnswerFile replacement = findAnswerFile(connection, booklet, replacementFile.getId());

				// Selecting the currently assigned file is recognition rather than a
				// structural change. No source-dependent data or assets are retired.
				if (current.getId() == replacement.getId()) {
					Impact impact = readImpact(connection, booklet.getId(), current.getId());
					connection.commit();
					return new Result(replacement, impact, false, null);
				}
				verifyExamActive(connection, booklet);

				// Existing repository invariants require all regions for this booklet to
				// agree with its current AnswerFile assignment before correction begins.
				verifyAnswerRegionsMatchCurrentAssignment(connection, booklet.getId(), current.getId());
				Impact impact = readImpact(connection, booklet.getId(), current.getId());
				List<Long> regionOnlyAnswerIds = findRegionOnlyAnswerIds(connection, booklet.getId(), current.getId());

				// Coordinates captured from the old file have no meaning in the new file.
				deleteOldAnswerRegions(connection, booklet.getId(), current.getId());

				// Once their only regions are gone, region-only Answers contain no answer
				// content and must return naturally to the unanswered queue.
				deleteRegionOnlyAnswers(connection, regionOnlyAnswerIds);
				assignAnswerFile(connection, booklet, replacement);

				// Retire the old database asset only after this booklet has been moved away
				// from it. Shared AnswerFiles and shared SourceDocuments remain intact.
				SourceDocument retiredSourceDocument = retireAnswerFileIfUnreferenced(connection, current);
				connection.commit();
				return new Result(replacement, impact, true, retiredSourceDocument);
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

	private void assignAnswerFile(Connection connection, ExamBooklet booklet, AnswerFile answerFile)
			throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				UPDATE exam_booklets
				SET answer_file_id = ?
				WHERE id = ?
				  AND exam_id = ?
				  AND EXISTS (
				      SELECT 1
				      FROM answer_files
				      WHERE id = ?
				        AND exam_id = ?
				  )
				""")) {
			statement.setLong(1, answerFile.getId());
			statement.setLong(2, booklet.getId());
			statement.setLong(3, booklet.getExam().getId());
			statement.setLong(4, answerFile.getId());
			statement.setLong(5, booklet.getExam().getId());
			if (statement.executeUpdate() != 1) {
				throw new IllegalArgumentException(
						"AnswerFile and booklet must both exist and belong to the same Exam");
			}
		}
	}

	private void deleteOldAnswerRegions(Connection connection, long bookletId, long answerFileId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				DELETE FROM answer_regions
				WHERE answer_file_id = ?
				  AND answer_id IN (
				      SELECT a.id
				      FROM answers a
				      JOIN questions q
				          ON q.id = a.question_id
				      WHERE q.booklet_id = ?
				  )
				""")) {
			statement.setLong(1, answerFileId);
			statement.setLong(2, bookletId);

			// Only coordinates tied to this booklet and its old AnswerFile are
			// invalidated. Other booklets sharing that file are untouched.
			statement.executeUpdate();
		}
	}

	private void deleteRegionOnlyAnswers(Connection connection, List<Long> answerIds) throws SQLException {
		if (answerIds.isEmpty()) {
			return;
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				DELETE FROM answers
				WHERE id = ?
				  AND NOT EXISTS (
				      SELECT 1
				      FROM answer_regions
				      WHERE answer_id = answers.id
				  )
				""")) {
			for (Long answerId : answerIds) {
				statement.setLong(1, answerId.longValue());

				// Delete only Answers identified before invalidation as having no
				// independent textual content.
				if (statement.executeUpdate() != 1) {
					throw new SQLException(
							"Region-only Answer could not be removed after AnswerFile reassignment: " + answerId);
				}
			}
		}
	}

	private AnswerFile findAnswerFile(Connection connection, ExamBooklet booklet, long answerFileId)
			throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT
				    af.id AS answer_file_id,
				    af.answer_file_name,
				    sd.id AS source_document_id,
				    sd.relative_path,
				    sd.content_sha256
				FROM answer_files af
				JOIN source_documents sd
				    ON sd.id = af.source_document_id
				WHERE af.id = ?
				  AND af.exam_id = ?
				""")) {
			statement.setLong(1, answerFileId);
			statement.setLong(2, booklet.getExam().getId());
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new IllegalArgumentException(
							"Replacement AnswerFile does not exist for this Exam: " + answerFileId);
				}
				SourceDocument sourceDocument = new SourceDocument(result.getLong("source_document_id"),
						result.getString("relative_path"), result.getString("content_sha256"));
				return new AnswerFile(result.getLong("answer_file_id"), booklet.getExam(),
						result.getString("answer_file_name"), sourceDocument);
			}
		}
	}

	private AnswerFile findAssignedAnswerFile(Connection connection, ExamBooklet booklet) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT
				    af.id AS answer_file_id,
				    af.answer_file_name,
				    sd.id AS source_document_id,
				    sd.relative_path,
				    sd.content_sha256
				FROM exam_booklets eb
				LEFT JOIN answer_files af
				    ON af.id = eb.answer_file_id
				LEFT JOIN source_documents sd
				    ON sd.id = af.source_document_id
				WHERE eb.id = ?
				  AND eb.exam_id = ?
				""")) {
			statement.setLong(1, booklet.getId());
			statement.setLong(2, booklet.getExam().getId());
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new IllegalArgumentException("Exam booklet does not exist: " + booklet.getId());
				}
				long answerFileId = result.getLong("answer_file_id");
				if (result.wasNull()) {
					return null;
				}
				SourceDocument sourceDocument = new SourceDocument(result.getLong("source_document_id"),
						result.getString("relative_path"), result.getString("content_sha256"));
				return new AnswerFile(answerFileId, booklet.getExam(), result.getString("answer_file_name"),
						sourceDocument);
			}
		}
	}

	private List<Long> findRegionOnlyAnswerIds(Connection connection, long bookletId, long answerFileId)
			throws SQLException {
		List<Long> answerIds = new ArrayList<>();
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT DISTINCT a.id
				FROM answers a
				JOIN questions q
				    ON q.id = a.question_id
				JOIN answer_regions ar
				    ON ar.answer_id = a.id
				WHERE q.booklet_id = ?
				  AND ar.answer_file_id = ?
				  AND (
				      a.answer_text IS NULL
				      OR TRIM(a.answer_text) = ''
				  )
				ORDER BY a.id
				""")) {
			statement.setLong(1, bookletId);
			statement.setLong(2, answerFileId);
			try (ResultSet result = statement.executeQuery()) {
				while (result.next()) {
					answerIds.add(result.getLong("id"));
				}
			}
		}
		return List.copyOf(answerIds);
	}

	private Impact readImpact(Connection connection, long bookletId, long answerFileId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT
				    COUNT(DISTINCT q.id) AS affected_question_count,
				    COUNT(ar.answer_id) AS answer_region_count,
				    COUNT(DISTINCT CASE
				        WHEN a.answer_text IS NOT NULL
				         AND TRIM(a.answer_text) <> ''
				        THEN a.id
				    END) AS preserved_text_answer_count,
				    COUNT(DISTINCT CASE
				        WHEN a.answer_text IS NULL
				          OR TRIM(a.answer_text) = ''
				        THEN a.id
				    END) AS region_only_answer_count
				FROM questions q
				JOIN answers a
				    ON a.question_id = q.id
				JOIN answer_regions ar
				    ON ar.answer_id = a.id
				WHERE q.booklet_id = ?
				  AND ar.answer_file_id = ?
				""")) {
			statement.setLong(1, bookletId);
			statement.setLong(2, answerFileId);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new SQLException("Could not calculate AnswerFile reassignment impact");
				}
				return new Impact(result.getInt("affected_question_count"), result.getInt("answer_region_count"),
						result.getInt("preserved_text_answer_count"), result.getInt("region_only_answer_count"));
			}
		}
	}

	private SourceDocument retireAnswerFileIfUnreferenced(Connection connection, AnswerFile answerFile)
			throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				DELETE FROM answer_files
				WHERE id = ?
				  AND NOT EXISTS (
				      SELECT 1
				      FROM exam_booklets
				      WHERE answer_file_id = ?
				  )
				  AND NOT EXISTS (
				      SELECT 1
				      FROM answer_regions
				      WHERE answer_file_id = ?
				  )
				""")) {
			statement.setLong(1, answerFile.getId());
			statement.setLong(2, answerFile.getId());
			statement.setLong(3, answerFile.getId());

			// Another booklet or surviving Answer region still makes the old AnswerFile
			// authoritative, so shared assets must remain untouched.
			if (statement.executeUpdate() == 0) {
				return null;
			}
		}
		SourceDocument sourceDocument = answerFile.getSourceDocument();
		try (PreparedStatement statement = connection.prepareStatement("""
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
				""")) {
			statement.setLong(1, sourceDocument.getId());
			statement.setLong(2, sourceDocument.getId());
			statement.setLong(3, sourceDocument.getId());

			// The physical managed PDF may be removed only when this deletion proves that
			// no Question booklet or remaining AnswerFile still owns the source.
			if (statement.executeUpdate() == 1) {
				return sourceDocument;
			}
		}
		return null;
	}

	private void verifyAnswerRegionsMatchCurrentAssignment(Connection connection, long bookletId,
			long currentAnswerFileId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT 1
				FROM questions q
				JOIN answers a
				    ON a.question_id = q.id
				JOIN answer_regions ar
				    ON ar.answer_id = a.id
				WHERE q.booklet_id = ?
				  AND ar.answer_file_id <> ?
				LIMIT 1
				""")) {
			statement.setLong(1, bookletId);
			statement.setLong(2, currentAnswerFileId);
			try (ResultSet result = statement.executeQuery()) {
				if (result.next()) {
					throw new IllegalStateException(
							"Persisted Answer regions contradict the booklet's assigned AnswerFile");
				}
			}
		}
	}

	private void verifyExamActive(Connection connection, ExamBooklet booklet) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT e.capture_state
				FROM exam_booklets eb
				JOIN exams e
				    ON e.id = eb.exam_id
				WHERE eb.id = ?
				  AND eb.exam_id = ?
				""")) {
			statement.setLong(1, booklet.getId());
			statement.setLong(2, booklet.getExam().getId());
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new IllegalArgumentException("Exam booklet does not exist: " + booklet.getId());
				}
				ExamCaptureState state = ExamCaptureState.valueOf(result.getString("capture_state"));
				if (state == ExamCaptureState.COMPLETE) {
					throw new IllegalStateException("Exam is complete; reactivate it before reassigning an AnswerFile");
				}
			}
		}
	}

	private void verifyPersistedBooklet(Connection connection, ExamBooklet booklet) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT exam_id
				FROM exam_booklets
				WHERE id = ?
				""")) {
			statement.setLong(1, booklet.getId());
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new IllegalArgumentException("Exam booklet does not exist: " + booklet.getId());
				}
				if (result.getLong("exam_id") != booklet.getExam().getId()) {
					throw new IllegalArgumentException("Exam booklet does not belong to the supplied Exam");
				}
			}
		}
	}

	/**
	 * Persisted Answer content affected by an AnswerFile reassignment.
	 *
	 * @param affectedQuestionCount    Questions whose Answer regions use the old
	 *                                 file
	 * @param answerRegionCount        Answer regions invalidated by reassignment
	 * @param preservedTextAnswerCount affected Answers retaining independent
	 *                                 textual content
	 * @param regionOnlyAnswerCount    affected Answers that become empty and are
	 *                                 removed
	 */
	public record Impact(int affectedQuestionCount, int answerRegionCount, int preservedTextAnswerCount,
			int regionOnlyAnswerCount) {
	}

	/**
	 * Result of an AnswerFile reassignment.
	 *
	 * @param answerFile            replacement AnswerFile assigned to the booklet
	 * @param impact                source-dependent content present before
	 *                              reassignment
	 * @param changed               whether the booklet assignment actually changed
	 * @param retiredSourceDocument SourceDocument whose final persisted reference
	 *                              was removed, or {@code null} when the old source
	 *                              remains in use
	 */
	public record Result(AnswerFile answerFile, Impact impact, boolean changed, SourceDocument retiredSourceDocument) {

		/**
		 * Validates a reassignment result.
		 */
		public Result {
			if (answerFile == null) {
				throw new NullPointerException("answerFile");
			}
			if (impact == null) {
				throw new NullPointerException("impact");
			}

			// retiredSourceDocument is deliberately nullable because a shared old asset
			// remains authoritative after this booklet is reassigned.
		}
	}
}
