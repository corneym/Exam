package au.edu.eq.questionbank.repository;

import java.sql.Connection;
import java.sql.SQLException;

import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.Subject;

public final class SqliteExamImporter {

	private final SqliteDatabase database;
	private final SqliteExamWriter writer;

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

	public ExamBooklet importExam(Subject subject, String providerName, int year, String examName, String bookletName,
			String relativePath) throws SQLException {
		if (subject == null) {
			throw new NullPointerException("subject");
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
