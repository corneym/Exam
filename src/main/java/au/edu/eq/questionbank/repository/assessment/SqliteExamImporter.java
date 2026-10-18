package au.edu.eq.questionbank.repository.assessment;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import au.edu.eq.questionbank.model.AnswerFile;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamBookletQuestionFormat;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

/**
 * Finds or creates the complete exam, provider, source-document, and booklet
 * hierarchy in one SQLite transaction.
 */
public final class SqliteExamImporter {

	private final SqliteDatabase database;
	private final SqliteExamWriter writer;

	/**
	 * Creates an exam importer.
	 *
	 * @param database the question-bank database
	 * @param writer   the exam metadata writer used within the transaction
	 * @throws NullPointerException if either argument is {@code null}
	 */
	public SqliteExamImporter(SqliteDatabase database, SqliteExamWriter writer) {
		if (database == null) {
			throw new NullPointerException("database");
		}
		if (writer == null) {
			throw new NullPointerException("writer");
		}
		this.database = database;
		this.writer = writer;
	}

	/**
	 * Finds or creates exam metadata matching the supplied natural keys.
	 *
	 * @param subject      the persisted subject being assessed
	 * @param providerName the non-blank issuing organisation name
	 * @param year         the positive assessment year
	 * @param examName     the non-blank assessment name
	 * @param bookletName  the non-blank booklet name
	 * @param relativePath the non-blank data-root-relative source path
	 * @return the existing or newly stored booklet
	 * @throws SQLException             if the transaction cannot be completed
	 * @throws NullPointerException     if {@code subject} is {@code null}
	 * @throws IllegalArgumentException if supplied metadata is invalid
	 */
	public ExamBooklet importExam(Subject subject, String providerName, int year, String examName, String bookletName,
			String relativePath) throws SQLException {

		// Legacy callers may not yet know the source-document content identity.
		return importExamInternal(subject, providerName, year, examName, bookletName, relativePath, null, null);
	}

	/**
	 * Finds or creates Exam metadata with an explicit Question format.
	 *
	 * @param subject        persisted Subject being assessed
	 * @param providerName   issuing organisation
	 * @param year           assessment year
	 * @param examName       assessment name
	 * @param bookletName    booklet name
	 * @param relativePath   managed source-document path
	 * @param questionFormat Question format for a newly created booklet
	 * @return existing or newly stored booklet
	 * @throws SQLException             if persistence fails
	 * @throws NullPointerException     if {@code subject} or {@code questionFormat}
	 *                                  is {@code null}
	 * @throws IllegalArgumentException if supplied metadata is invalid
	 */
	public ExamBooklet importExam(Subject subject, String providerName, int year, String examName, String bookletName,
			String relativePath, ExamBookletQuestionFormat questionFormat) throws SQLException {
		if (questionFormat == null) {
			throw new NullPointerException("questionFormat");
		}

		// Existing callers remain compatible when no hash has yet been calculated.
		return importExamInternal(subject, providerName, year, examName, bookletName, relativePath, questionFormat,
				null);
	}

	/**
	 * Finds or creates Exam metadata while recording complete planning metadata for
	 * a newly created Question booklet.
	 *
	 * @param subject               persisted Subject being assessed
	 * @param providerName          issuing organisation
	 * @param year                  assessment year
	 * @param examName              assessment name
	 * @param bookletName           booklet name
	 * @param relativePath          managed source-document path
	 * @param questionFormat        explicit Question format
	 * @param expectedQuestionCount expected top-level Question count, or
	 *                              {@code null} when it has not yet been reviewed
	 * @param contentSha256         canonical SHA-256 digest, or {@code null}
	 * @return existing or newly stored Question booklet
	 * @throws SQLException             if persistence fails
	 * @throws NullPointerException     if {@code subject} or {@code questionFormat}
	 *                                  is {@code null}
	 * @throws IllegalArgumentException if supplied metadata is invalid
	 */
	public ExamBooklet importExam(Subject subject, String providerName, int year, String examName, String bookletName,
			String relativePath, ExamBookletQuestionFormat questionFormat, Integer expectedQuestionCount,
			String contentSha256) throws SQLException {
		if (questionFormat == null) {
			throw new NullPointerException("questionFormat");
		}
		if (expectedQuestionCount != null && (expectedQuestionCount < 1 || expectedQuestionCount > 99)) {

			// Exam/Assets uses the agreed one- or two-digit top-level Question count.
			throw new IllegalArgumentException("expectedQuestionCount must be between 1 and 99 when supplied");
		}
		return importExamInternal(subject, providerName, year, examName, bookletName, relativePath, questionFormat,
				expectedQuestionCount, contentSha256);
	}

	/**
	 * Creates or reuses a booklet and persists its legacy Paper identity in the
	 * same SQLite transaction.
	 *
	 * <p>
	 * A null legacyPaperCode retains the ordinary non-legacy workflow.
	 *
	 * @param subject               owning Subject
	 * @param providerName          issuing provider
	 * @param year                  examination year
	 * @param examName              examination name
	 * @param bookletName           editable booklet name
	 * @param relativePath          managed PDF path
	 * @param questionFormat        explicit booklet format
	 * @param expectedQuestionCount expected top-level Questions, or null
	 * @param contentSha256         source-byte identity, or null
	 * @param legacyPaperCode       MCQ, 1, 2, or null
	 * @return saved booklet with its persistent identity
	 * @throws SQLException if persistence fails
	 */
	public ExamBooklet importExam(Subject subject, String providerName, int year, String examName, String bookletName,
			String relativePath, ExamBookletQuestionFormat questionFormat, Integer expectedQuestionCount,
			String contentSha256, String legacyPaperCode) throws SQLException {
		if (questionFormat == null) {
			throw new NullPointerException("questionFormat");
		}
		if (expectedQuestionCount != null && (expectedQuestionCount < 1 || expectedQuestionCount > 99)) {
			throw new IllegalArgumentException("expectedQuestionCount must be between 1 and 99");
		}
		if (legacyPaperCode != null && !List.of("MCQ", "1", "2").contains(legacyPaperCode)) {
			throw new IllegalArgumentException("Unsupported legacy Paper code: " + legacyPaperCode);
		}
		return importExamInternal(subject, providerName, year, examName, bookletName, relativePath, questionFormat,
				expectedQuestionCount, contentSha256, legacyPaperCode);
	}

	/**
	 * Imports a Question booklet while atomically attaching a saved Answer booklet
	 * and, when supplied, a legacy workbook Paper identity.
	 *
	 * <p>
	 * The Answer must be a persisted asset of the resolved Exam. No Answer
	 * assignment is made when {@code answerFile} is null. Existing matching
	 * booklets are never silently reassigned.
	 * </p>
	 *
	 * @param subject               owning Subject
	 * @param providerName          issuing provider
	 * @param year                  examination year
	 * @param examName              examination name
	 * @param bookletName           editable booklet name
	 * @param relativePath          managed Question PDF path
	 * @param questionFormat        explicit booklet Type
	 * @param expectedQuestionCount expected top-level count, or null
	 * @param contentSha256         Question PDF hash, or null
	 * @param legacyPaperCode       MCQ, 1, 2, or null for ordinary creation
	 * @param answerFile            saved Answer booklet, or null
	 * @return saved Question booklet
	 * @throws SQLException             if persistence fails
	 * @throws IllegalArgumentException if metadata, Paper or Answer identity is
	 *                                  invalid
	 */
	public ExamBooklet importExam(Subject subject, String providerName, int year, String examName, String bookletName,
			String relativePath, ExamBookletQuestionFormat questionFormat, Integer expectedQuestionCount,
			String contentSha256, String legacyPaperCode, AnswerFile answerFile) throws SQLException {
		if (questionFormat == null) {
			throw new NullPointerException("questionFormat");
		}
		if (expectedQuestionCount != null && (expectedQuestionCount < 1 || expectedQuestionCount > 99)) {
			throw new IllegalArgumentException("expectedQuestionCount must be between 1 and 99");
		}
		if (legacyPaperCode != null && !List.of("MCQ", "1", "2").contains(legacyPaperCode)) {
			throw new IllegalArgumentException("Unsupported legacy Paper code: " + legacyPaperCode);
		}
		return importExamInternal(subject, providerName, year, examName, bookletName, relativePath, questionFormat,
				expectedQuestionCount, contentSha256, legacyPaperCode, answerFile);
	}

	/**
	 * Finds or creates Exam metadata while retaining a supplied source-document
	 * SHA-256 identity.
	 *
	 * @param subject        persisted Subject being assessed
	 * @param providerName   issuing organisation
	 * @param year           assessment year
	 * @param examName       assessment name
	 * @param bookletName    booklet name
	 * @param relativePath   managed source-document path
	 * @param questionFormat Question format for a newly created booklet
	 * @param contentSha256  canonical SHA-256 digest, or {@code null} when unknown
	 * @return existing or newly stored booklet
	 * @throws SQLException             if persistence fails
	 * @throws NullPointerException     if {@code subject} or {@code questionFormat}
	 *                                  is {@code null}
	 * @throws IllegalArgumentException if supplied metadata is invalid
	 */
	public ExamBooklet importExam(Subject subject, String providerName, int year, String examName, String bookletName,
			String relativePath, ExamBookletQuestionFormat questionFormat, String contentSha256) throws SQLException {
		if (questionFormat == null) {
			throw new NullPointerException("questionFormat");
		}

		// Fresh Exam setup supplies both explicit booklet structure and the known
		// identity of the managed source bytes.
		return importExamInternal(subject, providerName, year, examName, bookletName, relativePath, questionFormat,
				contentSha256);
	}

	/**
	 * Finds or creates Exam metadata without requiring explicit booklet-format
	 * metadata while retaining a known source-document hash.
	 *
	 * @param subject       persisted Subject being assessed
	 * @param providerName  issuing organisation
	 * @param year          assessment year
	 * @param examName      assessment name
	 * @param bookletName   booklet name
	 * @param relativePath  managed source-document path
	 * @param contentSha256 canonical SHA-256 digest, or {@code null}
	 * @return existing or newly stored booklet
	 * @throws SQLException if persistence fails
	 */
	public ExamBooklet importExam(Subject subject, String providerName, int year, String examName, String bookletName,
			String relativePath, String contentSha256) throws SQLException {

		// This overload is used by legacy import, where booklet format may remain
		// UNSPECIFIED until the booklet is explicitly reviewed.
		return importExamInternal(subject, providerName, year, examName, bookletName, relativePath, null,
				contentSha256);
	}

	private ExamBooklet importExamInternal(Subject subject, String providerName, int year, String examName,
			String bookletName, String relativePath, ExamBookletQuestionFormat questionFormat,
			Integer expectedQuestionCount, String contentSha256) throws SQLException {

		// Existing callers do not establish a legacy Paper identity.
		return importExamInternal(subject, providerName, year, examName, bookletName, relativePath, questionFormat,
				expectedQuestionCount, contentSha256, null);
	}

	private ExamBooklet importExamInternal(Subject subject, String providerName, int year, String examName,
			String bookletName, String relativePath, ExamBookletQuestionFormat questionFormat,
			Integer expectedQuestionCount, String contentSha256, String legacyPaperCode) throws SQLException {

		// Older import callers do not request a new Answer assignment.
		return importExamInternal(subject, providerName, year, examName, bookletName, relativePath, questionFormat,
				expectedQuestionCount, contentSha256, legacyPaperCode, null);
	}

	private ExamBooklet importExamInternal(Subject subject, String providerName, int year, String examName,
			String bookletName, String relativePath, ExamBookletQuestionFormat questionFormat,
			Integer expectedQuestionCount, String contentSha256, String legacyPaperCode, AnswerFile answerFile)
			throws SQLException {
		if (subject == null) {
			throw new NullPointerException("subject");
		}
		if (expectedQuestionCount != null && questionFormat == null) {
			throw new IllegalArgumentException("Expected Question count requires an explicit format");
		}
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				ExamProvider provider = writer.findExamProviderByName(connection, providerName);
				if (provider == null) {
					provider = writer.insertExamProvider(connection, providerName);
				}
				SourceDocument sourceDocument = writer.findSourceDocumentByPath(connection, relativePath);
				if (sourceDocument == null) {
					sourceDocument = writer.insertSourceDocument(connection, relativePath, contentSha256);
				} else if (contentSha256 != null) {
					sourceDocument = writer.recordSourceDocumentHash(connection, sourceDocument, contentSha256);
				}
				Exam exam = writer.findExam(connection, subject, provider, year, examName);
				if (exam == null) {
					exam = writer.insertExam(connection, subject, provider, year, examName);
				}
				if (answerFile != null && answerFile.getExam().getId() != exam.getId()) {
					throw new IllegalArgumentException("Answer booklet must belong to the same Exam");
				}
				ExamBooklet booklet = writer.findExamBooklet(connection, exam, bookletName, sourceDocument);
				if (booklet == null) {
					if (questionFormat == null) {
						booklet = writer.insertExamBooklet(connection, exam, sourceDocument, bookletName);
					} else {
						booklet = writer.insertExamBooklet(connection, exam, sourceDocument, bookletName,
								questionFormat, expectedQuestionCount);
					}
					if (answerFile != null) {

						// Validate the AnswerFile against persisted ownership in this
						// transaction. A stale ID rolls back PDF, booklet and Paper rows.
						new SqliteAnswerWriter(database, writer).assignAnswerFile(connection, booklet, answerFile);
					}
				} else if (answerFile != null) {

					// This API is for creation, not a hidden Answer reassignment of an
					// existing booklet. Existing Answer links must remain authoritative.
					throw new IllegalArgumentException("Existing booklet Answer assignment must be edited explicitly");
				}
				if (legacyPaperCode != null) {
					writer.updateLegacyPaperCode(connection, booklet, legacyPaperCode);
				}
				connection.commit();
				return booklet;
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

	private ExamBooklet importExamInternal(Subject subject, String providerName, int year, String examName,
			String bookletName, String relativePath, ExamBookletQuestionFormat questionFormat, String contentSha256)
			throws SQLException {

		// Existing import paths do not establish an authoritative expected Question
		// count, so retain null planning metadata for those callers.
		return importExamInternal(subject, providerName, year, examName, bookletName, relativePath, questionFormat,
				null, contentSha256);
	}
}
