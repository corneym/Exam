package au.edu.eq.questionbank.repository.assessment;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamAssetExpectations;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamBookletQuestionFormat;
import au.edu.eq.questionbank.model.ExamCaptureState;
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
	 * Assigns an explicit question format to a legacy booklet whose format has not
	 * previously been recorded.
	 *
	 * @param booklet        legacy booklet to classify
	 * @param questionFormat explicit format to persist
	 * @return the same booklet identity with the explicit format
	 * @throws SQLException if persistence fails
	 */
	public ExamBooklet classifyLegacyBookletQuestionFormat(ExamBooklet booklet,
			ExamBookletQuestionFormat questionFormat) throws SQLException {
		if (booklet == null) {
			throw new NullPointerException("booklet");
		}
		if (questionFormat == null) {
			throw new NullPointerException("questionFormat");
		}
		if (booklet.getQuestionFormat() != ExamBookletQuestionFormat.UNSPECIFIED) {
			throw new IllegalArgumentException("Booklet question format is already recorded");
		}
		if (questionFormat == ExamBookletQuestionFormat.UNSPECIFIED) {
			throw new IllegalArgumentException("A legacy booklet must be assigned an explicit question format");
		}
		try (Connection connection = database.openConnection()) {

			// Booklet format is Exam structure, so a completed Exam must be explicitly
			// reactivated before this metadata can change.
			requireExamActive(connection, booklet.getExam().getId());
			try (PreparedStatement statement = connection.prepareStatement("""
					UPDATE exam_booklets
					SET question_format = ?
					WHERE id = ?
					  AND question_format = 'UNSPECIFIED'
					""")) {
				statement.setString(1, questionFormat.name());
				statement.setLong(2, booklet.getId());

				// Only an existing legacy row may be classified by this operation.
				if (statement.executeUpdate() != 1) {
					throw new IllegalStateException(
							"Legacy booklet question format could not be updated: " + booklet.getId());
				}
			}
		}
		return new ExamBooklet(booklet.getId(), booklet.getExam(), booklet.getName(), booklet.getSourceDocument(),
				questionFormat, booklet.getExpectedQuestionCount());
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
	 * Creates one ACTIVE Exam with no declared asset expectations.
	 *
	 * @param subject      owning Subject
	 * @param providerName non-blank provider name
	 * @param year         positive Exam year
	 * @param name         non-blank assessment name
	 * @return newly persisted ACTIVE Exam
	 * @throws SQLException             if persistence fails
	 * @throws NullPointerException     if {@code subject} is {@code null}
	 * @throws IllegalArgumentException if supplied metadata is invalid or the Exam
	 *                                  already exists
	 */
	public Exam createExam(Subject subject, String providerName, int year, String name) throws SQLException {

		// Existing callers retain the original behaviour while New Exam may use the
		// planning-aware overload below.
		return createExam(subject, providerName, year, name, null, null);
	}

	/**
	 * Creates one ACTIVE Exam together with its initial asset-planning
	 * expectations.
	 *
	 * @param subject                      owning Subject
	 * @param providerName                 non-blank provider name
	 * @param year                         positive Exam year
	 * @param name                         non-blank assessment name
	 * @param expectedQuestionBookletCount expected Question-booklet count, or
	 *                                     {@code null} when not yet recorded
	 * @param expectedAnswerFileCount      expected Answer-booklet count, or
	 *                                     {@code null} when not yet recorded
	 * @return newly persisted ACTIVE Exam
	 * @throws SQLException             if persistence fails
	 * @throws NullPointerException     if {@code subject} is {@code null}
	 * @throws IllegalArgumentException if supplied metadata or planning counts are
	 *                                  invalid, or the Exam already exists
	 */
	public Exam createExam(Subject subject, String providerName, int year, String name,
			Integer expectedQuestionBookletCount, Integer expectedAnswerFileCount) throws SQLException {
		if (subject == null) {
			throw new NullPointerException("subject");
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
		if (expectedQuestionBookletCount != null && expectedQuestionBookletCount < 1) {
			throw new IllegalArgumentException("expectedQuestionBookletCount must be positive when supplied");
		}
		if (expectedAnswerFileCount != null && expectedAnswerFileCount < 0) {
			throw new IllegalArgumentException("expectedAnswerFileCount must not be negative when supplied");
		}
		String normalizedProviderName = providerName.strip();
		String normalizedName = name.strip();
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				ExamProvider provider = findExamProviderByName(connection, normalizedProviderName);
				if (provider == null) {

					// Provider creation and Exam creation remain one transaction so a failed
					// New Exam save cannot leave an unused provider behind.
					provider = insertExamProvider(connection, normalizedProviderName);
				}
				Exam existing = findExam(connection, subject, provider, year, normalizedName);
				if (existing != null) {

					// New Exam mode must never reinterpret an existing natural-key match as
					// the newly created Exam.
					throw new IllegalArgumentException(
							"Exam already exists for " + normalizedProviderName + " " + year + " " + normalizedName);
				}

				// Identity and initial structural planning are persisted atomically. The
				// teacher must not need a second Edit transaction immediately after Save.
				Exam created = insertExam(connection, subject, provider, year, normalizedName,
						expectedQuestionBookletCount, expectedAnswerFileCount);
				connection.commit();
				return created;
			} catch (SQLException | RuntimeException exception) {
				try {
					connection.rollback();
				} catch (SQLException rollbackFailure) {

					// Preserve rollback failure without concealing the original persistence
					// problem.
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
						    eb.question_format,
						    eb.expected_question_count,
						    sd.id AS source_document_id,
						    sd.relative_path,
						    sd.content_sha256,
						    e.id AS exam_id,
						    e.exam_year,
						    e.exam_name,
						    e.capture_state,
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
				ExamCaptureState captureState = ExamCaptureState.valueOf(result.getString("capture_state"));
				Exam exam = new Exam(result.getLong("exam_id"), subject, provider, result.getInt("exam_year"),
						result.getString("exam_name"), captureState);
				SourceDocument sourceDocument = new SourceDocument(result.getLong("source_document_id"),
						result.getString("relative_path"), result.getString("content_sha256"));

				// Reconstruct both structural booklet values rather than allowing persisted
				// planning metadata to disappear in memory.
				ExamBookletQuestionFormat questionFormat = ExamBookletQuestionFormat
						.valueOf(result.getString("question_format"));
				Integer expectedQuestionCount = readNullableInteger(result, "expected_question_count");
				booklets.add(new ExamBooklet(result.getLong("booklet_id"), exam, result.getString("booklet_name"),
						sourceDocument, questionFormat, expectedQuestionCount));
			}
			return List.copyOf(booklets);
		}
	}

	/**
	 * Reads user-declared Exam asset expectations together with currently available
	 * authoritative assets.
	 *
	 * @param exam persisted Exam to inspect
	 * @return expected and available Question/Answer asset counts
	 * @throws SQLException             if persistence cannot be read
	 * @throws NullPointerException     if {@code exam} is {@code null}
	 * @throws IllegalArgumentException if the Exam identity does not exist
	 */
	public ExamAssetExpectations findExamAssetExpectations(Exam exam) throws SQLException {
		if (exam == null) {
			throw new NullPointerException("exam");
		}
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT
						    e.expected_question_booklet_count,
						    e.expected_answer_file_count,
						    (
						        SELECT COUNT(*)
						        FROM exam_booklets eb
						        WHERE eb.exam_id = e.id
						    ) AS available_question_booklet_count,
						    (
						        SELECT COUNT(*)
						        FROM answer_files af
						        WHERE af.exam_id = e.id
						    ) AS available_answer_file_count
						FROM exams e
						WHERE e.id = ?
						  AND e.subject_id = ?
						""")) {
			statement.setLong(1, exam.getId());
			statement.setLong(2, exam.getSubject().getId());
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new IllegalArgumentException("Exam does not exist for its stored subject");
				}
				Integer expectedQuestionBookletCount = readNullableInteger(result, "expected_question_booklet_count");
				Integer expectedAnswerFileCount = readNullableInteger(result, "expected_answer_file_count");

				// Availability is derived from real persisted assets. Planning metadata
				// therefore cannot make an unavailable PDF appear to exist.
				return new ExamAssetExpectations(expectedQuestionBookletCount,
						result.getInt("available_question_booklet_count"), expectedAnswerFileCount,
						result.getInt("available_answer_file_count"));
			}
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
						    eb.question_format,
						    eb.expected_question_count,
						    sd.id AS source_document_id,
						    sd.relative_path,
						    sd.content_sha256,
						    e.id AS exam_id,
						    e.exam_year,
						    e.exam_name,
						    e.capture_state,
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
				ExamCaptureState captureState = ExamCaptureState.valueOf(result.getString("capture_state"));
				Exam exam = new Exam(result.getLong("exam_id"), subject, provider, result.getInt("exam_year"),
						result.getString("exam_name"), captureState);
				SourceDocument sourceDocument = new SourceDocument(result.getLong("source_document_id"),
						result.getString("relative_path"), result.getString("content_sha256"));

				// Schema validation guarantees supported enum and expected-count values.
				ExamBookletQuestionFormat questionFormat = ExamBookletQuestionFormat
						.valueOf(result.getString("question_format"));
				Integer expectedQuestionCount = readNullableInteger(result, "expected_question_count");
				ExamBooklet booklet = new ExamBooklet(result.getLong("booklet_id"), exam,
						result.getString("booklet_name"), sourceDocument, questionFormat, expectedQuestionCount);

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
	 * Finds every persisted Question booklet belonging to one Exam.
	 *
	 * @param exam persisted Exam whose booklets should be listed
	 * @return immutable booklets ordered by persistent identifier
	 * @throws SQLException             if persistence cannot be read
	 * @throws NullPointerException     if {@code exam} is {@code null}
	 * @throws IllegalArgumentException if the Exam identity does not exist for its
	 *                                  stored Subject
	 */
	public List<ExamBooklet> findExamBooklets(Exam exam) throws SQLException {
		if (exam == null) {
			throw new NullPointerException("exam");
		}
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT
						    eb.id AS booklet_id,
						    eb.booklet_name,
						    eb.question_format,
						    eb.expected_question_count,
						    sd.id AS source_document_id,
						    sd.relative_path,
						    sd.content_sha256,
						    e.exam_year,
						    e.exam_name,
						    e.capture_state,
						    p.id AS provider_id,
						    p.provider_name
						FROM exams e
						JOIN exam_providers p
						    ON p.id = e.provider_id
						LEFT JOIN exam_booklets eb
						    ON eb.exam_id = e.id
						LEFT JOIN source_documents sd
						    ON sd.id = eb.source_document_id
						WHERE e.id = ?
						  AND e.subject_id = ?
						ORDER BY eb.id
						""")) {
			statement.setLong(1, exam.getId());
			statement.setLong(2, exam.getSubject().getId());
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new IllegalArgumentException("Exam does not exist for its stored subject");
				}
				ExamProvider provider = new ExamProvider(result.getLong("provider_id"),
						result.getString("provider_name"));
				Exam authoritativeExam = new Exam(exam.getId(), exam.getSubject(), provider, result.getInt("exam_year"),
						result.getString("exam_name"), ExamCaptureState.valueOf(result.getString("capture_state")));
				List<ExamBooklet> booklets = new ArrayList<>();
				do {

					// A LEFT JOIN keeps an Exam with no source assets visible to Exam
					// Setup; only real persisted booklet rows become domain objects.
					long bookletId = result.getLong("booklet_id");
					if (result.wasNull()) {
						continue;
					}
					SourceDocument sourceDocument = new SourceDocument(result.getLong("source_document_id"),
							result.getString("relative_path"), result.getString("content_sha256"));
					ExamBookletQuestionFormat questionFormat = ExamBookletQuestionFormat
							.valueOf(result.getString("question_format"));
					Integer expectedQuestionCount = readNullableInteger(result, "expected_question_count");
					booklets.add(new ExamBooklet(bookletId, authoritativeExam, result.getString("booklet_name"),
							sourceDocument, questionFormat, expectedQuestionCount));
				} while (result.next());

				// Return a stable repository snapshot for the Exam Setup UI.
				return List.copyOf(booklets);
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
						    e.capture_state,
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
				ExamCaptureState captureState = ExamCaptureState.valueOf(result.getString("capture_state"));
				Exam exam = new Exam(result.getLong("exam_id"), subject, provider, year, result.getString("exam_name"),
						captureState);

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
	 * Finds every persisted Exam belonging to one Subject.
	 * <p>
	 * Exams are returned independently of whether Question booklets or Answer files
	 * have already been registered, so Exam Setup can also display planned Exams
	 * whose source assets are not yet available.
	 *
	 * @param subject persisted Subject whose Exams should be listed
	 * @return immutable Exams ordered by most recent year, then provider and name
	 * @throws SQLException         if persistence cannot be read
	 * @throws NullPointerException if {@code subject} is {@code null}
	 */
	public List<Exam> findExamsForSubject(Subject subject) throws SQLException {
		if (subject == null) {
			throw new NullPointerException("subject");
		}
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT
						    e.id AS exam_id,
						    e.exam_year,
						    e.exam_name,
						    e.capture_state,
						    p.id AS provider_id,
						    p.provider_name
						FROM exams e
						JOIN exam_providers p
						    ON p.id = e.provider_id
						WHERE e.subject_id = ?
						ORDER BY
						    e.exam_year DESC,
						    p.provider_name,
						    e.exam_name,
						    e.id
						""")) {
			statement.setLong(1, subject.getId());
			List<Exam> exams = new ArrayList<>();
			try (ResultSet result = statement.executeQuery()) {
				while (result.next()) {
					ExamProvider provider = new ExamProvider(result.getLong("provider_id"),
							result.getString("provider_name"));
					ExamCaptureState captureState = ExamCaptureState.valueOf(result.getString("capture_state"));

					// Reconstruct the persisted lifecycle state because Exam Setup must
					// distinguish structurally editable and completed Exams.
					exams.add(new Exam(result.getLong("exam_id"), subject, provider, result.getInt("exam_year"),
							result.getString("exam_name"), captureState));
				}
			}

			// The setup UI receives a snapshot rather than a mutable repository result.
			return List.copyOf(exams);
		}
	}

	/**
	 * Finds every persisted source document whose recorded bytes have the supplied
	 * SHA-256 identity.
	 *
	 * <p>
	 * More than one result is valid because duplicate content is detected and
	 * resolved by application workflow rather than prevented by a database unique
	 * constraint.
	 * </p>
	 *
	 * @param contentSha256 canonical lower-case SHA-256 hexadecimal digest
	 * @return matching documents ordered by persistent identifier
	 * @throws SQLException             if the lookup fails
	 * @throws IllegalArgumentException if the hash is not canonical SHA-256
	 */
	public List<SourceDocument> findSourceDocumentsByHash(String contentSha256) throws SQLException {
		validateContentSha256(contentSha256);
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT
						    id,
						    relative_path,
						    content_sha256
						FROM source_documents
						WHERE content_sha256 = ?
						ORDER BY id
						""")) {
			statement.setString(1, contentSha256);
			List<SourceDocument> documents = new ArrayList<>();
			try (ResultSet result = statement.executeQuery()) {
				while (result.next()) {
					documents.add(new SourceDocument(result.getLong("id"), result.getString("relative_path"),
							result.getString("content_sha256")));
				}
			}
			return List.copyOf(documents);
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

		// Existing callers predate booklet-format metadata, so preserve their
		// behaviour without inventing a Question format.
		return insertExamBooklet(exam, sourceDocument, name, ExamBookletQuestionFormat.UNSPECIFIED);
	}

	/**
	 * Inserts a booklet backed by an existing source document with an explicit
	 * Question format.
	 *
	 * @param exam           the exam containing the booklet
	 * @param sourceDocument the booklet's source PDF reference
	 * @param name           the non-blank booklet name
	 * @param questionFormat expected Question format for the booklet
	 * @return the stored booklet
	 * @throws SQLException         if persistence fails
	 * @throws NullPointerException if {@code exam}, {@code sourceDocument} or
	 *                              {@code questionFormat} is {@code null}
	 */
	public ExamBooklet insertExamBooklet(Exam exam, SourceDocument sourceDocument, String name,
			ExamBookletQuestionFormat questionFormat) throws SQLException {

		// Existing callers do not yet know an expected count, so preserve that as
		// deliberately unknown rather than inventing a value.
		return insertExamBooklet(exam, sourceDocument, name, questionFormat, null);
	}

	/**
	 * Inserts a booklet with explicit capture-planning metadata.
	 *
	 * @param exam                  the active Exam containing the booklet
	 * @param sourceDocument        source PDF reference
	 * @param name                  booklet name
	 * @param questionFormat        expected Question format
	 * @param expectedQuestionCount expected top-level Question count, or
	 *                              {@code null} when not yet established
	 * @return persisted booklet
	 * @throws SQLException             if persistence fails
	 * @throws NullPointerException     if required arguments are null
	 * @throws IllegalArgumentException if supplied metadata is invalid
	 * @throws IllegalStateException    if the Exam is complete
	 */
	public ExamBooklet insertExamBooklet(Exam exam, SourceDocument sourceDocument, String name,
			ExamBookletQuestionFormat questionFormat, Integer expectedQuestionCount) throws SQLException {
		try (Connection connection = database.openConnection()) {

			// Use the transaction-aware implementation so every creation path receives
			// the same structural-lock and validation behaviour.
			return insertExamBooklet(connection, exam, sourceDocument, name, questionFormat, expectedQuestionCount);
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

		// Existing callers may create a source reference before its bytes have been
		// hashed. Preserve that workflow explicitly as an unknown hash.
		return insertSourceDocument(relativePath, null);
	}

	/**
	 * Inserts a reference to a source file with its optional SHA-256 content
	 * identity.
	 *
	 * @param relativePath  non-blank data-root-relative source path
	 * @param contentSha256 canonical lower-case SHA-256 digest, or {@code null}
	 * @return stored source-document reference
	 * @throws SQLException             if persistence fails
	 * @throws IllegalArgumentException if the path or supplied hash is invalid
	 */
	public SourceDocument insertSourceDocument(String relativePath, String contentSha256) throws SQLException {
		try (Connection connection = database.openConnection()) {
			return insertSourceDocument(connection, relativePath, contentSha256);
		}
	}

	/**
	 * Changes the user-declared structural capture state of an Exam.
	 *
	 * @param exam         persisted Exam to update
	 * @param captureState replacement lifecycle state
	 * @return the same Exam identity carrying the persisted replacement state
	 * @throws SQLException         if persistence fails
	 * @throws NullPointerException if {@code exam} or {@code captureState} is null
	 */
	public Exam setExamCaptureState(Exam exam, ExamCaptureState captureState) throws SQLException {
		if (exam == null) {
			throw new NullPointerException("exam");
		}
		if (captureState == null) {
			throw new NullPointerException("captureState");
		}
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						UPDATE exams
						SET capture_state = ?
						WHERE id = ?
						  AND subject_id = ?
						""")) {
			statement.setString(1, captureState.name());
			statement.setLong(2, exam.getId());
			statement.setLong(3, exam.getSubject().getId());

			// Never manufacture a lifecycle change for a stale or fabricated Exam.
			if (statement.executeUpdate() != 1) {
				throw new IllegalArgumentException("Exam does not exist for its stored subject");
			}
		}
		return new Exam(exam.getId(), exam.getSubject(), exam.getProvider(), exam.getYear(), exam.getName(),
				captureState);
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

	/**
	 * Updates user-declared Exam-level source-asset expectations.
	 *
	 * @param exam                         persisted active Exam
	 * @param expectedQuestionBookletCount expected Question booklets, or
	 *                                     {@code null} when unknown
	 * @param expectedAnswerFileCount      expected Answer/marking files, or
	 *                                     {@code null} when unknown
	 * @return persisted expectations together with current available counts
	 * @throws SQLException             if persistence fails
	 * @throws NullPointerException     if {@code exam} is {@code null}
	 * @throws IllegalArgumentException if supplied counts are invalid or the Exam
	 *                                  does not exist
	 * @throws IllegalStateException    if the Exam is complete
	 */
	public ExamAssetExpectations updateExamAssetExpectations(Exam exam, Integer expectedQuestionBookletCount,
			Integer expectedAnswerFileCount) throws SQLException {
		if (exam == null) {
			throw new NullPointerException("exam");
		}
		if (expectedQuestionBookletCount != null && expectedQuestionBookletCount < 1) {
			throw new IllegalArgumentException("expectedQuestionBookletCount must be positive when supplied");
		}
		if (expectedAnswerFileCount != null && expectedAnswerFileCount < 0) {
			throw new IllegalArgumentException("expectedAnswerFileCount must not be negative when supplied");
		}
		try (Connection connection = database.openConnection()) {

			// Expected asset counts describe Exam structure and are therefore locked once
			// the user declares the Exam complete.
			requireExamActive(connection, exam.getId());
			try (PreparedStatement statement = connection.prepareStatement("""
					UPDATE exams
					SET expected_question_booklet_count = ?,
					    expected_answer_file_count = ?
					WHERE id = ?
					  AND subject_id = ?
					""")) {
				statement.setObject(1, expectedQuestionBookletCount);
				statement.setObject(2, expectedAnswerFileCount);
				statement.setLong(3, exam.getId());
				statement.setLong(4, exam.getSubject().getId());
				if (statement.executeUpdate() != 1) {
					throw new IllegalArgumentException("Exam does not exist for its stored subject");
				}
			}
		}

		// Reload so available counts reflect authoritative booklet/AnswerFile rows and
		// cannot be confused with the declared expectations.
		return findExamAssetExpectations(exam);
	}

	/**
	 * Updates the editable structural metadata of one persisted Question booklet.
	 *
	 * @param booklet               persisted booklet to update
	 * @param name                  non-blank booklet label
	 * @param questionFormat        Question format recorded for the booklet
	 * @param expectedQuestionCount positive expected top-level Question count, or
	 *                              {@code null} when not recorded
	 * @return updated booklet with the same persistent identity and source document
	 * @throws SQLException             if persistence fails
	 * @throws NullPointerException     if {@code booklet} or {@code questionFormat}
	 *                                  is {@code null}
	 * @throws IllegalArgumentException if the name is blank, the expected count is
	 *                                  invalid, or the booklet does not exist
	 * @throws IllegalStateException    if the owning Exam is complete
	 */
	public ExamBooklet updateExamBookletMetadata(ExamBooklet booklet, String name,
			ExamBookletQuestionFormat questionFormat, Integer expectedQuestionCount) throws SQLException {
		try (Connection connection = database.openConnection()) {

			// The connection-aware implementation is also reused when booklet metadata and
			// Answer assignment must be committed as one transaction.
			return updateExamBookletMetadata(connection, booklet, name, questionFormat, expectedQuestionCount);
		}
	}

	/**
	 * Updates structural capture-planning metadata for an existing Exam booklet.
	 *
	 * @param booklet               persisted booklet
	 * @param questionFormat        replacement booklet Question format
	 * @param expectedQuestionCount expected top-level Question count, or
	 *                              {@code null} when no count has been established
	 * @return updated booklet with the same persistent identity
	 * @throws SQLException             if persistence fails
	 * @throws NullPointerException     if {@code booklet} or {@code questionFormat}
	 *                                  is null
	 * @throws IllegalArgumentException if a supplied expected count is not positive
	 * @throws IllegalStateException    if the owning Exam is complete
	 */
	public ExamBooklet updateExamBookletPlanning(ExamBooklet booklet, ExamBookletQuestionFormat questionFormat,
			Integer expectedQuestionCount) throws SQLException {

		// Planning is a subset of the authoritative booklet-metadata correction path.
		// Preserve the current booklet label while updating format and expected count.
		return updateExamBookletMetadata(booklet, booklet.getName(), questionFormat, expectedQuestionCount);
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
				SELECT id, capture_state
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
				ExamCaptureState captureState = ExamCaptureState.valueOf(result.getString("capture_state"));

				// Transactional importer callers must receive the persisted lifecycle state,
				// not an object silently defaulted to ACTIVE.
				return new Exam(result.getLong("id"), subject, provider, year, name, captureState);
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
				SELECT
				    id,
				    source_document_id,
				    question_format,
				    expected_question_count
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

				// Reusing a booklet name must not silently redirect its existing PDF
				// reference.
				long storedSourceDocumentId = result.getLong("source_document_id");
				if (storedSourceDocumentId != sourceDocument.getId()) {
					throw new SQLException("Existing exam booklet refers to a different source document");
				}

				// Existing persisted planning metadata is authoritative when reopening a
				// booklet.
				ExamBookletQuestionFormat questionFormat = ExamBookletQuestionFormat
						.valueOf(result.getString("question_format"));
				Integer expectedQuestionCount = readNullableInteger(result, "expected_question_count");
				return new ExamBooklet(result.getLong("id"), exam, name, sourceDocument, questionFormat,
						expectedQuestionCount);
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
				SELECT
				    id,
				    relative_path,
				    content_sha256
				FROM source_documents
				WHERE relative_path = ?
				""")) {
			statement.setString(1, relativePath);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					return null;
				}
				return new SourceDocument(result.getLong("id"), result.getString("relative_path"),
						result.getString("content_sha256"));
			}
		}
	}

	Exam insertExam(Connection connection, Subject subject, ExamProvider provider, int year, String name)
			throws SQLException {

		// Existing transactional import callers do not declare Exam-level expectations.
		return insertExam(connection, subject, provider, year, name, null, null);
	}

	Exam insertExam(Connection connection, Subject subject, ExamProvider provider, int year, String name,
			Integer expectedQuestionBookletCount, Integer expectedAnswerFileCount) throws SQLException {
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
		if (expectedQuestionBookletCount != null && expectedQuestionBookletCount < 1) {
			throw new IllegalArgumentException("expectedQuestionBookletCount must be positive when supplied");
		}
		if (expectedAnswerFileCount != null && expectedAnswerFileCount < 0) {
			throw new IllegalArgumentException("expectedAnswerFileCount must not be negative when supplied");
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO exams
				    (subject_id,
				     provider_id,
				     exam_year,
				     exam_name,
				     expected_question_booklet_count,
				     expected_answer_file_count)
				VALUES (?, ?, ?, ?, ?, ?)
				RETURNING id
				""")) {
			statement.setLong(1, subject.getId());
			statement.setLong(2, provider.getId());
			statement.setInt(3, year);
			statement.setString(4, name);
			statement.setObject(5, expectedQuestionBookletCount);
			statement.setObject(6, expectedAnswerFileCount);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new SQLException("Exam insert did not return an id");
				}

				// Schema defaults still establish ACTIVE lifecycle state; the additional
				// columns only establish the initial structural plan.
				return new Exam(result.getLong("id"), subject, provider, year, name, ExamCaptureState.ACTIVE);
			}
		}
	}

	ExamBooklet insertExamBooklet(Connection connection, Exam exam, SourceDocument sourceDocument, String name)
			throws SQLException {

		// Transactional callers that have not yet supplied booklet-format metadata
		// must remain compatible and explicitly persist UNSPECIFIED.
		return insertExamBooklet(connection, exam, sourceDocument, name, ExamBookletQuestionFormat.UNSPECIFIED);
	}

	ExamBooklet insertExamBooklet(Connection connection, Exam exam, SourceDocument sourceDocument, String name,
			ExamBookletQuestionFormat questionFormat) throws SQLException {

		// Route transactional import through the structural-locking implementation.
		// This prevents SqliteExamImporter from adding a booklet to a COMPLETE Exam
		// merely because it uses the package-private writer API.
		return insertExamBooklet(connection, exam, sourceDocument, name, questionFormat, null);
	}

	ExamBooklet insertExamBooklet(Connection connection, Exam exam, SourceDocument sourceDocument, String name,
			ExamBookletQuestionFormat questionFormat, Integer expectedQuestionCount) throws SQLException {
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
		if (questionFormat == null) {
			throw new NullPointerException("questionFormat");
		}
		if (expectedQuestionCount != null && expectedQuestionCount < 1) {
			throw new IllegalArgumentException("expectedQuestionCount must be positive when supplied");
		}

		// Adding a booklet changes the declared structure of the Exam.
		requireExamActive(connection, exam.getId());
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO exam_booklets
				    (exam_id,
				     source_document_id,
				     booklet_name,
				     question_format,
				     expected_question_count)
				VALUES (?, ?, ?, ?, ?)
				RETURNING id
				""")) {
			statement.setLong(1, exam.getId());
			statement.setLong(2, sourceDocument.getId());
			statement.setString(3, name);

			// Enum names are persisted directly because the schema constrains the
			// supported vocabulary.
			statement.setString(4, questionFormat.name());
			statement.setObject(5, expectedQuestionCount);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new SQLException("Exam booklet insert did not return an id");
				}
				return new ExamBooklet(result.getLong("id"), exam, name, sourceDocument, questionFormat,
						expectedQuestionCount);
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
		return insertSourceDocument(connection, relativePath, null);
	}

	SourceDocument insertSourceDocument(Connection connection, String relativePath, String contentSha256)
			throws SQLException {
		if (connection == null) {
			throw new NullPointerException("connection");
		}
		if (relativePath == null || relativePath.isBlank()) {
			throw new IllegalArgumentException("relativePath must not be blank");
		}
		if (contentSha256 != null) {
			validateContentSha256(contentSha256);
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO source_documents
				    (relative_path,
				     content_sha256)
				VALUES (?, ?)
				RETURNING id
				""")) {
			statement.setString(1, relativePath);
			statement.setString(2, contentSha256);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new SQLException("Source document insert did not return an id");
				}
				return new SourceDocument(result.getLong("id"), relativePath, contentSha256);
			}
		}
	}

	SourceDocument recordSourceDocumentHash(Connection connection, SourceDocument sourceDocument, String contentSha256)
			throws SQLException {
		if (connection == null) {
			throw new NullPointerException("connection");
		}
		if (sourceDocument == null) {
			throw new NullPointerException("sourceDocument");
		}
		validateContentSha256(contentSha256);
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT relative_path, content_sha256
				FROM source_documents
				WHERE id = ?
				""")) {
			statement.setLong(1, sourceDocument.getId());
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new IllegalArgumentException("Source document does not exist: " + sourceDocument.getId());
				}
				String relativePath = result.getString("relative_path");
				String storedHash = result.getString("content_sha256");

				// A previously recorded byte identity is authoritative. The same managed
				// source path must never silently acquire different content.
				if (storedHash != null) {
					if (!storedHash.equals(contentSha256)) {
						throw new IllegalArgumentException(
								"Persisted source document hash conflicts with the selected file");
					}
					return new SourceDocument(sourceDocument.getId(), relativePath, storedHash);
				}
				try (PreparedStatement update = connection.prepareStatement("""
						UPDATE source_documents
						SET content_sha256 = ?
						WHERE id = ?
						  AND content_sha256 IS NULL
						""")) {
					update.setString(1, contentSha256);
					update.setLong(2, sourceDocument.getId());

					// Back-filling identity is safe only for the exact persisted row that was
					// just confirmed to have no hash.
					if (update.executeUpdate() != 1) {
						throw new SQLException("Source document hash could not be recorded: " + sourceDocument.getId());
					}
				}
				return new SourceDocument(sourceDocument.getId(), relativePath, contentSha256);
			}
		}
	}

	void requireExamActive(Connection connection, long examId) throws SQLException {
		if (connection == null) {
			throw new NullPointerException("connection");
		}
		if (examId < 1) {
			throw new IllegalArgumentException("examId must be positive");
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT capture_state
				FROM exams
				WHERE id = ?
				""")) {
			statement.setLong(1, examId);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new IllegalArgumentException("Exam does not exist: " + examId);
				}
				ExamCaptureState captureState = ExamCaptureState.valueOf(result.getString("capture_state"));

				// COMPLETE records a deliberate human decision. Structural writers must not
				// silently override that decision.
				if (captureState == ExamCaptureState.COMPLETE) {
					throw new IllegalStateException("Exam is complete; reactivate it before changing Exam structure");
				}
			}
		}
	}

	ExamBooklet updateExamBookletMetadata(Connection connection, ExamBooklet booklet, String name,
			ExamBookletQuestionFormat questionFormat, Integer expectedQuestionCount) throws SQLException {
		if (connection == null) {
			throw new NullPointerException("connection");
		}
		if (booklet == null) {
			throw new NullPointerException("booklet");
		}
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("name must not be blank");
		}
		if (questionFormat == null) {
			throw new NullPointerException("questionFormat");
		}
		if (expectedQuestionCount != null && expectedQuestionCount < 1) {
			throw new IllegalArgumentException("expectedQuestionCount must be positive when supplied");
		}
		String normalizedName = name.strip();

		// Name, format and expected count all describe Exam structure, so a completed
		// Exam must be reactivated before any of them can change.
		requireExamActive(connection, booklet.getExam().getId());
		try (PreparedStatement statement = connection.prepareStatement("""
				UPDATE exam_booklets
				SET booklet_name = ?,
				    question_format = ?,
				    expected_question_count = ?
				WHERE id = ?
				  AND exam_id = ?
				""")) {
			statement.setString(1, normalizedName);
			statement.setString(2, questionFormat.name());
			statement.setObject(3, expectedQuestionCount);
			statement.setLong(4, booklet.getId());
			statement.setLong(5, booklet.getExam().getId());

			// Match both persistent identities so a stale booklet object cannot update a
			// row belonging to another Exam.
			if (statement.executeUpdate() != 1) {
				throw new IllegalArgumentException("Exam booklet does not exist for its stored Exam");
			}
		}
		return new ExamBooklet(booklet.getId(), booklet.getExam(), normalizedName, booklet.getSourceDocument(),
				questionFormat, expectedQuestionCount);
	}

	private Exam correctExamMetadata(Connection connection, Exam exam, String providerName, int year, String name)
			throws SQLException {

		// Provider/year/name define Exam structure. A completed Exam must be
		// deliberately reactivated before any of them are corrected.
		requireExamActive(connection, exam.getId());
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

		// requireExamActive has established the authoritative persisted state even if
		// the caller supplied an older in-memory Exam object.
		return new Exam(exam.getId(), exam.getSubject(), provider, year, name, ExamCaptureState.ACTIVE);
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

	private Integer readNullableInteger(ResultSet result, String columnName) throws SQLException {

		// ResultSet#getInt maps SQL NULL to zero, so inspect wasNull before exposing
		// the value to a domain object where null has deliberate meaning.
		int value = result.getInt(columnName);
		return result.wasNull() ? null : value;
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

	private void validateContentSha256(String contentSha256) {
		if (contentSha256 == null || !contentSha256.matches("[0-9a-f]{64}")) {
			throw new IllegalArgumentException(
					"contentSha256 must contain exactly 64 lower-case hexadecimal characters");
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
