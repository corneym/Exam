package au.edu.eq.questionbank.repository.assessment;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

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
	 * Corrects metadata owned by an existing Exam while preserving its persistent
	 * identity and all relationships that reference that Exam.
	 *
	 * <p>
	 * The Exam subject is deliberately not changed here. Provider, year and
	 * assessment name are the supported Exam-level correction fields.
	 * </p>
	 *
	 * @param exam         existing persisted Exam
	 * @param providerName corrected non-blank provider name
	 * @param year         corrected positive assessment year
	 * @param name         corrected non-blank assessment name
	 * @return the corrected Exam with the same persistent identifier
	 * @throws SQLException             if persistence fails
	 * @throws NullPointerException     if {@code exam} is {@code null}
	 * @throws IllegalArgumentException if supplied metadata is invalid or another
	 *                                  Exam already owns the requested natural
	 *                                  identity
	 */
	public Exam correctExamMetadata(Exam exam, String providerName, int year, String name) throws SQLException {
		if (exam == null) {
			throw new NullPointerException("exam");
		}
		if (providerName == null || providerName.isBlank()) {
			throw new IllegalArgumentException("providerName must not be blank");
		}
		if (year < 1) {
			throw new IllegalArgumentException("year must be positive");
		}
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("name must not be blank");
		}
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {

				// Reuse an existing provider where possible. Changing one Exam must
				// not rename a provider row that may also belong to other Exams.
				ExamProvider provider = findExamProviderByName(connection, providerName);
				if (provider == null) {
					provider = insertExamProvider(connection, providerName);
				}
				Exam collision = findExam(connection, exam.getSubject(), provider, year, name);

				// Updating this Exam to an identity already owned by a different Exam
				// would implicitly merge two persisted assessment entities. Reject it
				// rather than silently moving any relationships.
				if (collision != null && collision.getId() != exam.getId()) {
					throw new IllegalArgumentException("Another exam already exists with the requested "
							+ "subject, provider, year and assessment name");
				}
				try (PreparedStatement statement = connection.prepareStatement("""
						UPDATE exams
						SET provider_id = ?,
						    exam_year = ?,
						    exam_name = ?
						WHERE id = ?
						  AND subject_id = ?
						""")) {
					statement.setLong(1, provider.getId());
					statement.setInt(2, year);
					statement.setString(3, name);
					statement.setLong(4, exam.getId());
					statement.setLong(5, exam.getSubject().getId());
					if (statement.executeUpdate() != 1) {
						throw new IllegalArgumentException("Exam does not exist for its stored subject");
					}
				}
				Exam corrected = new Exam(exam.getId(), exam.getSubject(), provider, year, name);
				connection.commit();
				return corrected;
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

	/**
	 * Finds the single exam for a subject, provider, and year.
	 *
	 * @param subject      the persisted subject
	 * @param providerName the examination provider name
	 * @param year         the examination year
	 * @return the matching exam, or {@code null} when none exists
	 * @throws SQLException             if the lookup fails
	 * @throws NullPointerException     if {@code subject} is {@code null}
	 * @throws IllegalArgumentException if provider name is blank, year is invalid,
	 *                                  or more than one exam matches
	 */
	public Exam findExamByProviderAndYear(Subject subject, String providerName, int year) throws SQLException {
		if (subject == null) {
			throw new NullPointerException("subject");
		}
		if (providerName == null || providerName.isBlank()) {
			throw new IllegalArgumentException("providerName must not be blank");
		}
		if (year < 1) {
			throw new IllegalArgumentException("year must be positive");
		}
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT
						    e.id AS exam_id,
						    e.exam_name,
						    p.id AS provider_id,
						    p.provider_name
						FROM exams e
						JOIN exam_providers p
						    ON p.id = e.provider_id
						WHERE e.subject_id = ?
						  AND p.provider_name = ?
						  AND e.exam_year = ?
						ORDER BY e.id
						""")) {
			statement.setLong(1, subject.getId());
			statement.setString(2, providerName);
			statement.setInt(3, year);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					return null;
				}
				ExamProvider provider = new ExamProvider(result.getLong("provider_id"),
						result.getString("provider_name"));
				Exam exam = new Exam(result.getLong("exam_id"), subject, provider, year, result.getString("exam_name"));

				// A year alone cannot select safely when the provider has multiple exams.
				if (result.next()) {
					throw new IllegalArgumentException(
							"More than one exam matches " + providerName + " " + year + " for " + subject.getName());
				}
				return exam;
			}
		}
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

				// Reusing a booklet name must not silently redirect its existing PDF reference.
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
