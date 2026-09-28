package au.edu.eq.questionbank.repository.assessment;

import java.sql.Connection;
import java.sql.SQLException;

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
			String relativePath, ExamBookletQuestionFormat questionFormat) throws SQLException {
		if (questionFormat == null) {
			throw new NullPointerException("questionFormat");
		}

		// Existing callers remain compatible when no hash has yet been calculated.
		return importExamInternal(subject, providerName, year, examName, bookletName, relativePath, questionFormat,
				null);
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
			String bookletName, String relativePath, ExamBookletQuestionFormat questionFormat, String contentSha256)
			throws SQLException {
		if (subject == null) {
			throw new NullPointerException("subject");
		}
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {

				// Provider, source, Exam and booklet form one atomic metadata import.
				ExamProvider provider = writer.findExamProviderByName(connection, providerName);
				if (provider == null) {
					provider = writer.insertExamProvider(connection, providerName);
				}
				SourceDocument sourceDocument = writer.findSourceDocumentByPath(connection, relativePath);
				if (sourceDocument == null) {

					// Newly managed documents persist their byte identity immediately when it
					// is available.
					sourceDocument = writer.insertSourceDocument(connection, relativePath, contentSha256);
				} else if (contentSha256 != null) {

					// Migrated rows are back-filled, while conflicting bytes for an already
					// identified source are rejected.
					sourceDocument = writer.recordSourceDocumentHash(connection, sourceDocument, contentSha256);
				}
				Exam exam = writer.findExam(connection, subject, provider, year, examName);
				if (exam == null) {
					exam = writer.insertExam(connection, subject, provider, year, examName);
				}
				ExamBooklet booklet = writer.findExamBooklet(connection, exam, bookletName, sourceDocument);
				if (booklet == null) {
					if (questionFormat == null) {

						// Legacy imports deliberately retain UNSPECIFIED booklet format until
						// the user reviews that structural metadata.
						booklet = writer.insertExamBooklet(connection, exam, sourceDocument, bookletName);
					} else {

						// Fresh imports persist the user's explicit booklet format.
						booklet = writer.insertExamBooklet(connection, exam, sourceDocument, bookletName,
								questionFormat);
					}
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
}
