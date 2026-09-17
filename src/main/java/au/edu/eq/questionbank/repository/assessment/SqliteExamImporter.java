package au.edu.eq.questionbank.repository.assessment;

import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

import java.sql.Connection;
import java.sql.SQLException;

import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.Subject;

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
		if (subject == null) {
			throw new NullPointerException("subject");
		}
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {

				// Reuse or create the metadata chain on one connection, then commit the
				// complete booklet.
				ExamProvider provider = writer.findExamProviderByName(connection, providerName);
				if (provider == null) {
					provider = writer.insertExamProvider(connection, providerName);
				}
				SourceDocument sourceDocument = writer.findSourceDocumentByPath(connection, relativePath);
				if (sourceDocument == null) {
					sourceDocument = writer.insertSourceDocument(connection, relativePath);
				}
				Exam exam = writer.findExam(connection, subject, provider, year, examName);
				if (exam == null) {
					exam = writer.insertExam(connection, subject, provider, year, examName);
				}
				ExamBooklet booklet = writer.findExamBooklet(connection, exam, bookletName, sourceDocument);
				if (booklet == null) {
					booklet = writer.insertExamBooklet(connection, exam, sourceDocument, bookletName);
				}
				connection.commit();
				return booklet;
			} catch (SQLException | RuntimeException e) {
				connection.rollback();
				throw e;
			}
		}
	}
}
