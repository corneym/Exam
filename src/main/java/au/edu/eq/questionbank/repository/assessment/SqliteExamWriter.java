package au.edu.eq.questionbank.repository.assessment;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

		// Preserve the original metadata-only API for callers that do not need to
		// relocate managed source documents.
		return correctExamMetadataAndSourceDocumentPaths(exam, providerName, year, name, Map.of());
	}

	/**
	 * Corrects Exam metadata and the persisted paths of source documents belonging
	 * to that correction in one SQLite transaction.
	 *
	 * @param exam                existing persisted Exam
	 * @param providerName        corrected non-blank provider name
	 * @param year                corrected positive assessment year
	 * @param name                corrected non-blank assessment name
	 * @param sourceDocumentPaths replacement relative path keyed by source-document
	 *                            id
	 * @return corrected Exam with the same persistent identifier
	 * @throws SQLException             if persistence fails
	 * @throws NullPointerException     if {@code exam} or
	 *                                  {@code sourceDocumentPaths} is null
	 * @throws IllegalArgumentException if supplied metadata or a source-document
	 *                                  replacement is invalid
	 */
	public Exam correctExamMetadataAndSourceDocumentPaths(Exam exam, String providerName, int year, String name,
			Map<Long, String> sourceDocumentPaths) throws SQLException {
		validateExamCorrection(exam, providerName, year, name);
		if (sourceDocumentPaths == null) {
			throw new NullPointerException("sourceDocumentPaths");
		}
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				Exam corrected = correctExamMetadata(connection, exam, providerName, year, name);

				// Source paths are part of the same transaction as the Exam correction so
				// SQLite can never commit one without the other.
				updateSourceDocumentPaths(connection, sourceDocumentPaths);
				connection.commit();
				return corrected;
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
	 * Returns whether an examination provider with the supplied name still exists.
	 *
	 * @param providerName non-blank provider name
	 * @return {@code true} when the provider remains persisted
	 * @throws SQLException             if the lookup fails
	 * @throws IllegalArgumentException if the name is null or blank
	 */
	public boolean examProviderExists(String providerName) throws SQLException {
		if (providerName == null || providerName.isBlank()) {
			throw new IllegalArgumentException("providerName must not be blank");
		}
		try (Connection connection = database.openConnection()) {
			return findExamProviderByName(connection, providerName) != null;
		}
	}

	/**
	 * Returns all persisted examination booklets with their complete Exam and
	 * SourceDocument relationships.
	 *
	 * @return persisted booklets ordered by persistent booklet identifier
	 * @throws SQLException if the lookup fails
	 */
	public List<ExamBooklet> findAllExamBooklets() throws SQLException {
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT
						    eb.id AS booklet_id,
						    eb.booklet_name,
						    sd.id AS source_document_id,
						    sd.relative_path,
						    e.id AS exam_id,
						    e.exam_year,
						    e.exam_name,
						    s.id AS subject_id,
						    s.subject_name,
						    p.id AS provider_id,
						    p.provider_name
						FROM exam_booklets eb
						JOIN source_documents sd
						    ON sd.id = eb.source_document_id
						JOIN exams e
						    ON e.id = eb.exam_id
						JOIN subjects s
						    ON s.id = e.subject_id
						JOIN exam_providers p
						    ON p.id = e.provider_id
						ORDER BY eb.id
						""");
				ResultSet result = statement.executeQuery()) {
			List<ExamBooklet> booklets = new ArrayList<>();
			while (result.next()) {
				Subject subject = new Subject(result.getLong("subject_id"), result.getString("subject_name"));
				ExamProvider provider = new ExamProvider(result.getLong("provider_id"),
						result.getString("provider_name"));
				Exam exam = new Exam(result.getLong("exam_id"), subject, provider, result.getInt("exam_year"),
						result.getString("exam_name"));
				SourceDocument sourceDocument = new SourceDocument(result.getLong("source_document_id"),
						result.getString("relative_path"));
				booklets.add(new ExamBooklet(result.getLong("booklet_id"), exam, result.getString("booklet_name"),
						sourceDocument));
			}
			return List.copyOf(booklets);
		}
	}

	/**
	 * Finds the ExamBooklet backed by a persisted source-document path.
	 *
	 * @param relativePath data-root-relative source-document path
	 * @return the matching booklet, or {@code null} when the path is unknown
	 * @throws SQLException             if the lookup fails
	 * @throws IllegalArgumentException if the path is null or blank
	 * @throws IllegalStateException    if more than one booklet refers to the same
	 *                                  source document
	 */
	public ExamBooklet findExamBookletBySourceDocumentPath(String relativePath) throws SQLException {
		if (relativePath == null || relativePath.isBlank()) {
			throw new IllegalArgumentException("relativePath must not be blank");
		}
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT
						    eb.id AS booklet_id,
						    eb.booklet_name,
						    sd.id AS source_document_id,
						    sd.relative_path,
						    e.id AS exam_id,
						    e.exam_year,
						    e.exam_name,
						    s.id AS subject_id,
						    s.subject_name,
						    p.id AS provider_id,
						    p.provider_name
						FROM source_documents sd
						JOIN exam_booklets eb
						    ON eb.source_document_id = sd.id
						JOIN exams e
						    ON e.id = eb.exam_id
						JOIN subjects s
						    ON s.id = e.subject_id
						JOIN exam_providers p
						    ON p.id = e.provider_id
						WHERE sd.relative_path = ?
						ORDER BY eb.id
						""")) {
			statement.setString(1, relativePath);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					return null;
				}
				Subject subject = new Subject(result.getLong("subject_id"), result.getString("subject_name"));
				ExamProvider provider = new ExamProvider(result.getLong("provider_id"),
						result.getString("provider_name"));
				Exam exam = new Exam(result.getLong("exam_id"), subject, provider, result.getInt("exam_year"),
						result.getString("exam_name"));
				SourceDocument sourceDocument = new SourceDocument(result.getLong("source_document_id"),
						result.getString("relative_path"));
				ExamBooklet booklet = new ExamBooklet(result.getLong("booklet_id"), exam,
						result.getString("booklet_name"), sourceDocument);

				// A source document should identify one capture booklet. If legacy or
				// corrupted data makes that relationship ambiguous, do not guess.
				if (result.next()) {
					throw new IllegalStateException(
							"More than one exam booklet refers to source document " + relativePath);
				}
				return booklet;
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

	/**
	 * Returns whether a source document is referenced by a booklet or answer file
	 * belonging to some other Exam.
	 *
	 * @param sourceDocumentId source-document identifier
	 * @param examId           Exam that is being corrected
	 * @return whether another Exam also depends on the source document
	 * @throws SQLException if the lookup fails
	 */
	public boolean sourceDocumentReferencedOutsideExam(long sourceDocumentId, long examId) throws SQLException {
		if (sourceDocumentId < 1) {
			throw new IllegalArgumentException("sourceDocumentId must be positive");
		}
		if (examId < 1) {
			throw new IllegalArgumentException("examId must be positive");
		}
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT (
						    EXISTS (
						        SELECT 1
						        FROM exam_booklets
						        WHERE source_document_id = ?
						          AND exam_id <> ?
						    )
						    OR EXISTS (
						        SELECT 1
						        FROM answer_files
						        WHERE source_document_id = ?
						          AND exam_id <> ?
						    )
						) AS referenced_outside_exam
						""")) {
			statement.setLong(1, sourceDocumentId);
			statement.setLong(2, examId);
			statement.setLong(3, sourceDocumentId);
			statement.setLong(4, examId);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new SQLException("Source-document ownership lookup returned no result");
				}
				return result.getBoolean("referenced_outside_exam");
			}
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

	private Exam correctExamMetadata(Connection connection, Exam exam, String providerName, int year, String name)
			throws SQLException {
		long previousProviderId = exam.getProvider().getId();

		// Reuse an existing Provider rather than renaming a row that may still belong
		// to other Exams.
		ExamProvider provider = findExamProviderByName(connection, providerName);
		if (provider == null) {
			provider = insertExamProvider(connection, providerName);
		}
		Exam collision = findExam(connection, exam.getSubject(), provider, year, name);
		if (collision != null && collision.getId() != exam.getId()) {
			throw new IllegalArgumentException(
					"Another exam already exists with the requested subject, provider, year and assessment name");
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

		// Remove the old Provider only when correcting this Exam leaves it unused.
		if (previousProviderId != provider.getId()) {
			deleteExamProviderIfUnreferenced(connection, previousProviderId);
		}
		return new Exam(exam.getId(), exam.getSubject(), provider, year, name);
	}

	private void deleteExamProviderIfUnreferenced(Connection connection, long providerId) throws SQLException {
		if (connection == null) {
			throw new NullPointerException("connection");
		}
		if (providerId < 1) {
			throw new IllegalArgumentException("providerId must be positive");
		}

		// Delete only an orphan. The NOT EXISTS guard ensures an Exam corrected in
		// isolation cannot remove a provider still used by another Exam.
		try (PreparedStatement statement = connection.prepareStatement("""
				DELETE FROM exam_providers
				WHERE id = ?
				  AND NOT EXISTS (
				      SELECT 1
				      FROM exams
				      WHERE provider_id = ?
				  )
				""")) {
			statement.setLong(1, providerId);
			statement.setLong(2, providerId);
			statement.executeUpdate();
		}
	}

	private void updateSourceDocumentPaths(Connection connection, Map<Long, String> sourceDocumentPaths)
			throws SQLException {
		for (Map.Entry<Long, String> entry : sourceDocumentPaths.entrySet()) {
			Long sourceDocumentId = entry.getKey();
			String relativePath = entry.getValue();
			if (sourceDocumentId == null || sourceDocumentId < 1) {
				throw new IllegalArgumentException("Source document id must be positive");
			}
			if (relativePath == null || relativePath.isBlank()) {
				throw new IllegalArgumentException("Source document path must not be blank");
			}
			try (PreparedStatement statement = connection.prepareStatement("""
					UPDATE source_documents
					SET relative_path = ?
					WHERE id = ?
					""")) {
				statement.setString(1, relativePath);
				statement.setLong(2, sourceDocumentId);

				// A correction must never silently ignore a stale or fabricated source
				// document identifier.
				if (statement.executeUpdate() != 1) {
					throw new IllegalArgumentException("Source document does not exist: " + sourceDocumentId);
				}
			}
		}
	}

	private void validateExamCorrection(Exam exam, String providerName, int year, String name) {
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
	}
}
