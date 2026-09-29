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
import au.edu.eq.questionbank.model.ExamBooklet;
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
	 * Assigns an existing AnswerFile to an ExamBooklet.
	 * <p>
	 * Reasserting the existing assignment is non-structural and remains permitted
	 * on a complete Exam. Changing or establishing the assignment requires the Exam
	 * to be active.
	 *
	 * @param booklet    booklet whose answers are contained in the file
	 * @param answerFile persisted answer file
	 * @throws SQLException             if persistence fails
	 * @throws NullPointerException     if either argument is {@code null}
	 * @throws IllegalArgumentException if the file belongs to another Exam or
	 *                                  either persistent identity does not exist
	 * @throws IllegalStateException    if existing Answer regions contradict the
	 *                                  assignment or the assignment would change a
	 *                                  complete Exam
	 */
	public void assignAnswerFile(ExamBooklet booklet, AnswerFile answerFile) throws SQLException {
		validateBookletAnswerFileOwnership(booklet, answerFile);
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				AnswerFile current = findAssignedAnswerFile(connection, booklet);

				// Reasserting the already-authoritative relationship changes no Exam
				// structure and is safe even after completion.
				if (current != null && current.getId() == answerFile.getId()) {
					connection.commit();
					return;
				}

				// Establishing or changing a booklet-to-AnswerFile mapping is structural.
				examWriter.requireExamActive(connection, booklet.getExam().getId());
				verifyNoConflictingBookletAnswerRegions(connection, booklet, answerFile.getId());
				assignAnswerFile(connection, booklet, answerFile);
				connection.commit();
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
	 * Finds the AnswerFile assigned to one ExamBooklet.
	 *
	 * @param booklet persisted booklet whose answer source is required
	 * @return assigned answer file, or {@code null} when the relationship has not
	 *         yet been established
	 * @throws SQLException         if persistence cannot be read
	 * @throws NullPointerException if {@code booklet} is {@code null}
	 */
	public AnswerFile findAnswerFile(ExamBooklet booklet) throws SQLException {
		if (booklet == null) {
			throw new NullPointerException("booklet");
		}
		try (Connection connection = database.openConnection()) {
			return findAssignedAnswerFile(connection, booklet);
		}
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
						    af.contains_answer_explanations,
						    sd.id AS source_document_id,
						    sd.relative_path,
						    sd.content_sha256
						FROM answer_files af
						JOIN source_documents sd
						    ON sd.id = af.source_document_id
						WHERE af.exam_id = ?
						ORDER BY af.id
						""")) {
			statement.setLong(1, exam.getId());
			try (ResultSet result = statement.executeQuery()) {
				while (result.next()) {

					// Reconstructed AnswerFiles retain both their source-byte identity and
					// their explicitly reviewed explanation metadata.
					SourceDocument sourceDocument = new SourceDocument(result.getLong("source_document_id"),
							result.getString("relative_path"), result.getString("content_sha256"));
					answerFiles.add(
							new AnswerFile(result.getLong("answer_file_id"), exam, result.getString("answer_file_name"),
									sourceDocument, result.getBoolean("contains_answer_explanations")));
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

		// Existing callers may register legacy answer documents whose hash is not yet
		// known.
		return findOrCreateAnswerFile(exam, name, relativePath, null);
	}

	/**
	 * Finds or creates an AnswerFile while retaining the known identity of its
	 * managed source bytes.
	 * <p>
	 * Reading an existing AnswerFile or back-filling the hash of its existing
	 * SourceDocument is non-structural. Registering a new source or AnswerFile
	 * requires an active Exam.
	 *
	 * @param exam          Exam whose answers the file contains
	 * @param name          answer-file name
	 * @param relativePath  managed source path
	 * @param contentSha256 canonical SHA-256 digest, or {@code null}
	 * @return existing or newly created AnswerFile
	 * @throws SQLException          if persistence fails
	 * @throws IllegalStateException if registration would change the structure of a
	 *                               complete Exam
	 */
	public AnswerFile findOrCreateAnswerFile(Exam exam, String name, String relativePath, String contentSha256)
			throws SQLException {
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

					// A new managed source becomes part of the Exam's structural asset set.
					examWriter.requireExamActive(connection, exam.getId());
					sourceDocument = examWriter.insertSourceDocument(connection, relativePath, contentSha256);
				} else if (contentSha256 != null) {

					// Recording byte identity for an already-known source does not change
					// which assets the Exam contains.
					sourceDocument = examWriter.recordSourceDocumentHash(connection, sourceDocument, contentSha256);
				}
				AnswerFile answerFile = findAnswerFile(connection, exam, name, sourceDocument);
				if (answerFile == null) {

					// Creating the AnswerFile relationship changes the Exam's asset structure.
					examWriter.requireExamActive(connection, exam.getId());
					answerFile = insertAnswerFile(connection, exam, name, sourceDocument);
				}
				connection.commit();
				return answerFile;
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
	 * Finds or creates an AnswerFile and assigns it to one ExamBooklet atomically.
	 *
	 * @param booklet      booklet whose answers the file supplies
	 * @param name         non-blank Answer-file name
	 * @param relativePath non-blank managed source path
	 * @return existing or newly created AnswerFile
	 * @throws SQLException             if persistence fails
	 * @throws NullPointerException     if {@code booklet} is {@code null}
	 * @throws IllegalArgumentException if a string argument is blank
	 * @throws IllegalStateException    if the operation would structurally change a
	 *                                  complete Exam
	 */
	public AnswerFile findOrCreateAnswerFile(ExamBooklet booklet, String name, String relativePath)
			throws SQLException {

		// Preserve the established convenience API. Callers that do not yet have a
		// source hash delegate to the structurally guarded implementation.
		return findOrCreateAnswerFile(booklet, name, relativePath, null);
	}

	/**
	 * Finds or creates an AnswerFile, records its source hash when known, and
	 * assigns it to one ExamBooklet atomically.
	 * <p>
	 * Existing registration and the existing booklet assignment may be reused on a
	 * complete Exam. Creating a source, creating an AnswerFile, or establishing a
	 * different booklet assignment requires an active Exam.
	 *
	 * @param booklet       booklet whose answers the file supplies
	 * @param name          answer-file name
	 * @param relativePath  managed source path
	 * @param contentSha256 canonical SHA-256 digest, or {@code null}
	 * @return existing or newly created AnswerFile
	 * @throws SQLException          if persistence fails
	 * @throws IllegalStateException if the operation would structurally change a
	 *                               complete Exam
	 */
	public AnswerFile findOrCreateAnswerFile(ExamBooklet booklet, String name, String relativePath,
			String contentSha256) throws SQLException {
		if (booklet == null) {
			throw new NullPointerException("booklet");
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
				Exam exam = booklet.getExam();
				SourceDocument sourceDocument = examWriter.findSourceDocumentByPath(connection, relativePath);
				if (sourceDocument == null) {

					// Registering another managed Answer source changes Exam structure.
					examWriter.requireExamActive(connection, exam.getId());
					sourceDocument = examWriter.insertSourceDocument(connection, relativePath, contentSha256);
				} else if (contentSha256 != null) {

					// Hash back-fill describes an existing asset and is not structural.
					sourceDocument = examWriter.recordSourceDocumentHash(connection, sourceDocument, contentSha256);
				}
				AnswerFile answerFile = findAnswerFile(connection, exam, name, sourceDocument);
				if (answerFile == null) {

					// A new AnswerFile is another structural Exam asset.
					examWriter.requireExamActive(connection, exam.getId());
					answerFile = insertAnswerFile(connection, exam, name, sourceDocument);
				}
				AnswerFile assigned = findAssignedAnswerFile(connection, booklet);
				if (assigned == null || assigned.getId() != answerFile.getId()) {

					// Establishing or changing the booklet mapping is also structural.
					examWriter.requireExamActive(connection, exam.getId());
					verifyNoConflictingBookletAnswerRegions(connection, booklet, answerFile.getId());
					assignAnswerFile(connection, booklet, answerFile);
				}
				connection.commit();
				return answerFile;
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

		// Regions must belong to this Exam and one Answer can never span separate
		// answer documents.
		validateAnswerRegionsForQuestion(question, regions);
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {

				// Existing capture paths may not yet have explicitly assigned the booklet.
				// A single unambiguous AnswerFile can establish that relationship here.
				ensureBookletAnswerFileForRegions(connection, question, regions);
				long answerId = insertAnswerRow(connection, question, answerText);
				insertAnswerRegions(connection, answerId, regions);
				Answer answer = new Answer(answerId, answerText, regions);
				connection.commit();
				return answer;
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
	 * Updates the descriptive explanation metadata for one persisted AnswerFile.
	 * <p>
	 * This does not add, remove or reassign an Exam asset, so it remains a
	 * non-structural metadata correction even when the Exam is complete.
	 *
	 * @param answerFile                 persisted AnswerFile to update
	 * @param containsAnswerExplanations whether explanatory answer material is
	 *                                   present
	 * @return updated AnswerFile with the same persistent identity
	 * @throws SQLException             if persistence fails
	 * @throws NullPointerException     if {@code answerFile} is {@code null}
	 * @throws IllegalArgumentException if the AnswerFile does not exist for its
	 *                                  Exam
	 */
	public AnswerFile setContainsAnswerExplanations(AnswerFile answerFile, boolean containsAnswerExplanations)
			throws SQLException {
		if (answerFile == null) {
			throw new NullPointerException("answerFile");
		}
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						UPDATE answer_files
						SET contains_answer_explanations = ?
						WHERE id = ?
						  AND exam_id = ?
						""")) {

			// SQLite stores the Java boolean as the schema's constrained 0/1 value.
			statement.setBoolean(1, containsAnswerExplanations);
			statement.setLong(2, answerFile.getId());
			statement.setLong(3, answerFile.getExam().getId());
			if (statement.executeUpdate() != 1) {
				throw new IllegalArgumentException("Answer file does not exist for its stored Exam");
			}
		}

		// The source asset itself is unchanged; expose only the revised descriptive
		// metadata through a new immutable domain object.
		return new AnswerFile(answerFile.getId(), answerFile.getExam(), answerFile.getName(),
				answerFile.getSourceDocument(), containsAnswerExplanations);
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

		// Updating an Answer cannot move its regions to another Exam or combine
		// material from separate answer documents.
		validateAnswerRegionsForQuestion(question, regions);
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {

				// Check ownership before replacing anything under the existing Answer ID.
				verifyAnswerBelongsToQuestion(connection, answerId, question);

				// Enforce the booklet-level answer source before deleting the old regions so
				// any failure leaves the existing Answer completely intact.
				ensureBookletAnswerFileForRegions(connection, question, regions);
				updateAnswerRow(connection, answerId, answerText);

				// Keep deletion and replacement atomic so a failed insert restores the old
				// answer.
				deleteAnswerRegions(connection, answerId);
				insertAnswerRegions(connection, answerId, regions);
				Answer answer = new Answer(answerId, answerText, regions);
				connection.commit();
				return answer;
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

		// Match both Exam identities in SQL as well as in Java. This rejects stale or
		// fabricated domain objects without relying only on caller-side validation.
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
						"Answer file and booklet must both exist and belong to the same exam");
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

	private void ensureBookletAnswerFileForRegions(Connection connection, Question question, List<AnswerRegion> regions)
			throws SQLException {
		if (regions.isEmpty()) {
			return;
		}
		AnswerFile regionAnswerFile = regions.getFirst().answerFile();
		ExamBooklet booklet = question.getBooklet();
		AnswerFile assignedAnswerFile = findAssignedAnswerFile(connection, booklet);
		if (assignedAnswerFile != null) {
			if (assignedAnswerFile.getId() != regionAnswerFile.getId()) {
				throw new IllegalArgumentException(
						"Answer regions must use the answer file assigned to the question's booklet");
			}
			return;
		}

		// Older capture workflows can establish the booklet mapping implicitly from
		// the first unambiguous Answer region. That still changes Exam structure and
		// therefore cannot happen after completion.
		examWriter.requireExamActive(connection, booklet.getExam().getId());
		verifyNoConflictingBookletAnswerRegions(connection, booklet, regionAnswerFile.getId());
		assignAnswerFile(connection, booklet, regionAnswerFile);
	}

	private AnswerFile findAnswerFile(Connection connection, Exam exam, String name, SourceDocument sourceDocument)
			throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT
				    id,
				    source_document_id,
				    contains_answer_explanations
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

				// Reusing an existing AnswerFile must preserve its reviewed explanation
				// metadata rather than reverting to the constructor default.
				return new AnswerFile(result.getLong("id"), exam, name, sourceDocument,
						result.getBoolean("contains_answer_explanations"));
			}
		}
	}

	private AnswerFile findAssignedAnswerFile(Connection connection, ExamBooklet booklet) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT
				    eb.answer_file_id AS mapped_answer_file_id,
				    af.id AS answer_file_id,
				    af.exam_id AS answer_file_exam_id,
				    af.answer_file_name,
				    af.contains_answer_explanations,
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
				long mappedAnswerFileId = result.getLong("mapped_answer_file_id");
				if (result.wasNull()) {
					return null;
				}
				long answerFileId = result.getLong("answer_file_id");
				if (result.wasNull()) {
					throw new SQLException(
							"Exam booklet refers to an answer file that does not exist: " + mappedAnswerFileId);
				}
				long answerFileExamId = result.getLong("answer_file_exam_id");
				if (answerFileExamId != booklet.getExam().getId()) {
					throw new SQLException("Exam booklet refers to an answer file belonging to another exam");
				}

				// Preserve content identity when an assigned AnswerFile is restored.
				// Preserve source identity and reviewed AnswerFile metadata when the booklet
				// assignment is reconstructed.
				SourceDocument sourceDocument = new SourceDocument(result.getLong("source_document_id"),
						result.getString("relative_path"), result.getString("content_sha256"));
				return new AnswerFile(answerFileId, booklet.getExam(), result.getString("answer_file_name"),
						sourceDocument, result.getBoolean("contains_answer_explanations"));
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

				// New AnswerFiles begin conservatively with no explanation metadata until the
				// user explicitly reviews the asset.
				return new AnswerFile(result.getLong("id"), exam, name, sourceDocument, false);
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

	private void validateAnswerRegionsForQuestion(Question question, List<AnswerRegion> regions) {
		long answerFileId = 0;
		for (AnswerRegion region : regions) {
			if (region == null) {
				throw new NullPointerException("regions must not contain null");
			}
			if (region.answerFile().getExam().getId() != question.getExam().getId()) {
				throw new IllegalArgumentException("Answer region file must belong to the question's exam");
			}
			if (answerFileId == 0) {
				answerFileId = region.answerFile().getId();
				continue;
			}

			// One Answer can never be assembled from more than one answer document.
			if (answerFileId != region.answerFile().getId()) {
				throw new IllegalArgumentException("All answer regions must use the same answer file");
			}
		}
	}

	private void validateBookletAnswerFileOwnership(ExamBooklet booklet, AnswerFile answerFile) {
		if (booklet == null) {
			throw new NullPointerException("booklet");
		}
		if (answerFile == null) {
			throw new NullPointerException("answerFile");
		}
		if (booklet.getExam().getId() != answerFile.getExam().getId()) {
			throw new IllegalArgumentException("Answer file must belong to the booklet's exam");
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

	private void verifyNoConflictingBookletAnswerRegions(Connection connection, ExamBooklet booklet, long answerFileId)
			throws SQLException {
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
			statement.setLong(1, booklet.getId());
			statement.setLong(2, answerFileId);
			try (ResultSet result = statement.executeQuery()) {
				if (result.next()) {
					throw new IllegalStateException(
							"Booklet already has persisted answer regions from a different answer file");
				}
			}
		}
	}
}
