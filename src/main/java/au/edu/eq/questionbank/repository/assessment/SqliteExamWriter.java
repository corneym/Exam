package au.edu.eq.questionbank.repository.assessment;

import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.Subject;

/**
 * Inserts examination metadata and source-document references into SQLite,
 * returning domain objects carrying their generated persistent identifiers.
 * Public operations own their connections; package-level overloads participate
 * in a caller-managed transaction.
 */
public final class SqliteExamWriter {

	private final SqliteDatabase database;

	/**
	 * Creates a writer for the supplied database.
	 *
	 * @param database the initialized question-bank database
	 * @throws NullPointerException if {@code database} is {@code null}
	 */
	public SqliteExamWriter(SqliteDatabase database) {
		if (database == null) {
			throw new NullPointerException("database");
		}
		this.database = database;
	}

	/**
	 * Inserts an exam for an existing subject and provider.
	 *
	 * @param subject  the subject being assessed
	 * @param provider the organisation issuing the exam
	 * @param year     the positive calendar year of the exam
	 * @param name     the non-blank exam name
	 * @return the stored exam
	 * @throws SQLException             if the insert fails, including a foreign-key
	 *                                  or uniqueness violation
	 * @throws NullPointerException     if {@code subject} or {@code provider} is
	 *                                  {@code null}
	 * @throws IllegalArgumentException if {@code year} is not positive or
	 *                                  {@code name} is null or blank
	 */
	public Exam insertExam(Subject subject, ExamProvider provider, int year, String name) throws SQLException {
		try (Connection connection = database.openConnection()) {
			return insertExam(connection, subject, provider, year, name);
		}
	}

	/**
	 * Inserts a booklet backed by an existing source document.
	 *
	 * @param exam           the exam containing the booklet
	 * @param sourceDocument the booklet's source PDF reference
	 * @param name           the non-blank booklet name
	 * @return the stored booklet
	 * @throws SQLException             if the insert fails, including a foreign-key
	 *                                  or uniqueness violation
	 * @throws NullPointerException     if {@code exam} or {@code sourceDocument} is
	 *                                  {@code null}
	 * @throws IllegalArgumentException if {@code name} is null or blank
	 */
	public ExamBooklet insertExamBooklet(Exam exam, SourceDocument sourceDocument, String name) throws SQLException {
		try (Connection connection = database.openConnection()) {
			return insertExamBooklet(connection, exam, sourceDocument, name);
		}
	}

	/**
	 * Inserts an examination provider.
	 *
	 * @param name the non-blank provider name
	 * @return the stored provider
	 * @throws SQLException             if the insert fails, including a uniqueness
	 *                                  violation
	 * @throws IllegalArgumentException if {@code name} is null or blank
	 */
	public ExamProvider insertExamProvider(String name) throws SQLException {
		try (Connection connection = database.openConnection()) {
			return insertExamProvider(connection, name);
		}
	}

	/**
	 * Inserts a reference to a source file. The caller is responsible for supplying
	 * a path relative to the configured PDF data root; resolution must still pass
	 * through the containment-checking PDF store.
	 *
	 * @param relativePath the non-blank data-root-relative source path
	 * @return the stored source-document reference
	 * @throws SQLException             if the insert fails, including a uniqueness
	 *                                  violation
	 * @throws IllegalArgumentException if {@code relativePath} is null or blank
	 */
	public SourceDocument insertSourceDocument(String relativePath) throws SQLException {
		try (Connection connection = database.openConnection()) {
			return insertSourceDocument(connection, relativePath);
		}
	}

	Exam findExam(Connection connection, Subject subject, ExamProvider provider, int year, String name)
			throws SQLException {
		if (connection == null) {
			throw new NullPointerException("connection");
		}
		if (subject == null) {
			throw new NullPointerException("subject");
		}
		if (provider == null) {
			throw new NullPointerException("provider");
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT id
				FROM exams
				WHERE subject_id = ?
				  AND provider_id = ?
				  AND exam_year = ?
				  AND exam_name = ?
				""")) {
			statement.setLong(1, subject.getId());
			statement.setLong(2, provider.getId());
			statement.setInt(3, year);
			statement.setString(4, name);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					return null;
				}
				return new Exam(result.getLong("id"), subject, provider, year, name);
			}
		}
	}

	ExamBooklet findExamBooklet(Connection connection, Exam exam, String name, SourceDocument sourceDocument)
			throws SQLException {
		if (connection == null) {
			throw new NullPointerException("connection");
		}
		if (exam == null) {
			throw new NullPointerException("exam");
		}
		if (sourceDocument == null) {
			throw new NullPointerException("sourceDocument");
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT id, source_document_id
				FROM exam_booklets
				WHERE exam_id = ?
				  AND booklet_name = ?
				""")) {
			statement.setLong(1, exam.getId());
			statement.setString(2, name);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					return null;
				}
				long storedSourceDocumentId = result.getLong("source_document_id");
				if (storedSourceDocumentId != sourceDocument.getId()) {
					throw new SQLException("Existing exam booklet refers to a different source document");
				}
				return new ExamBooklet(result.getLong("id"), exam, name, sourceDocument);
			}
		}
	}

	ExamProvider findExamProviderByName(Connection connection, String name) throws SQLException {
		if (connection == null) {
			throw new NullPointerException("connection");
		}
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("name must not be blank");
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT id, provider_name
				FROM exam_providers
				WHERE provider_name = ?
				""")) {
			statement.setString(1, name);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					return null;
				}
				return new ExamProvider(result.getLong("id"), result.getString("provider_name"));
			}
		}
	}

	SourceDocument findSourceDocumentByPath(Connection connection, String relativePath) throws SQLException {
		if (connection == null) {
			throw new NullPointerException("connection");
		}
		if (relativePath == null || relativePath.isBlank()) {
			throw new IllegalArgumentException("relativePath must not be blank");
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT id, relative_path
				FROM source_documents
				WHERE relative_path = ?
				""")) {
			statement.setString(1, relativePath);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					return null;
				}
				return new SourceDocument(result.getLong("id"), result.getString("relative_path"));
			}
		}
	}

	Exam insertExam(Connection connection, Subject subject, ExamProvider provider, int year, String name)
			throws SQLException {
		if (connection == null) {
			throw new NullPointerException("connection");
		}
		if (subject == null) {
			throw new NullPointerException("subject");
		}
		if (provider == null) {
			throw new NullPointerException("provider");
		}
		if (year < 1) {
			throw new IllegalArgumentException("year must be positive");
		}
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("name must not be blank");
		}

		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO exams
				    (subject_id,
				     provider_id,
				     exam_year,
				     exam_name)
				VALUES (?, ?, ?, ?)
				RETURNING id
				""")) {
			statement.setLong(1, subject.getId());
			statement.setLong(2, provider.getId());
			statement.setInt(3, year);
			statement.setString(4, name);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new SQLException("Exam insert did not return an id");
				}
				return new Exam(result.getLong("id"), subject, provider, year, name);
			}
		}
	}

	ExamBooklet insertExamBooklet(Connection connection, Exam exam, SourceDocument sourceDocument, String name)
			throws SQLException {
		if (connection == null) {
			throw new NullPointerException("connection");
		}
		if (exam == null) {
			throw new NullPointerException("exam");
		}
		if (sourceDocument == null) {
			throw new NullPointerException("sourceDocument");
		}
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("name must not be blank");
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO exam_booklets
				    (exam_id,
				     source_document_id,
				     booklet_name)
				VALUES (?, ?, ?)
				RETURNING id
				""")) {
			statement.setLong(1, exam.getId());
			statement.setLong(2, sourceDocument.getId());
			statement.setString(3, name);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new SQLException("Exam booklet insert did not return an id");
				}
				return new ExamBooklet(result.getLong("id"), exam, name, sourceDocument);
			}
		}
	}

	ExamProvider insertExamProvider(Connection connection, String name) throws SQLException {
		if (connection == null) {
			throw new NullPointerException("connection");
		}
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("name must not be blank");
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO exam_providers
				    (provider_name)
				VALUES (?)
				RETURNING id
				""")) {
			statement.setString(1, name);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new SQLException("Exam provider insert did not return an id");
				}
				return new ExamProvider(result.getLong("id"), name);
			}
		}
	}

	SourceDocument insertSourceDocument(Connection connection, String relativePath) throws SQLException {
		if (connection == null) {
			throw new NullPointerException("connection");
		}
		if (relativePath == null || relativePath.isBlank()) {
			throw new IllegalArgumentException("relativePath must not be blank");
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO source_documents
				    (relative_path)
				VALUES (?)
				RETURNING id
				""")) {
			statement.setString(1, relativePath);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new SQLException("Source document insert did not return an id");
				}
				return new SourceDocument(result.getLong("id"), relativePath);
			}
		}
	}
}
