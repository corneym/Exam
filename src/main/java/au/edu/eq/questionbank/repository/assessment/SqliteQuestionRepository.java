package au.edu.eq.questionbank.repository.assessment;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import au.edu.eq.questionbank.diagnostics.PerformanceOperation;
import au.edu.eq.questionbank.diagnostics.PerformanceRecorder;
import au.edu.eq.questionbank.model.Answer;
import au.edu.eq.questionbank.model.AnswerFile;
import au.edu.eq.questionbank.model.AnswerRegion;
import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamBookletQuestionFormat;
import au.edu.eq.questionbank.model.ExamCaptureState;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.ImageQuestionContentPart;
import au.edu.eq.questionbank.model.PdfQuestionContentPart;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionContentPart;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.SharedContextStatus;
import au.edu.eq.questionbank.model.SharedQuestionContext;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.SourceQuestion;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumRepository;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.repository.sqlite.SqliteReadProbe;

/**
 * SQLite-backed Question repository that reconstructs complete Question,
 * region, Exam, curriculum and Answer state from persistent records.
 * <p>
 * Curriculum-aware retrieval validates non-null, non-empty lists of current
 * SUBTOPIC or DESCRIPTOR nodes. Repeated requested node IDs are consolidated
 * before constructing the parameterised SQL query.
 * <p>
 * SQL restricts applicability to the Question's Subject and the target's
 * current syllabus. It recognises direct current classifications and confirmed
 * historical mappings, excluding unconfirmed or suggested mappings.
 * <p>
 * Broad and selected-Question retrieval share the same applicability semantics.
 * The selected-Question path constrains the Question ID in SQL before
 * reconstructing stored Questions, avoiding reconstruction of unrelated
 * Questions.
 * <p>
 * Reconstructed applicability matches retain the stored Question's
 * authoritative original classification. Retrieval does not persist derived
 * current placements.
 */
public final class SqliteQuestionRepository implements QuestionRepository, QuestionRetrievalRepository {

	// Persistence and service collaborators.
	private final SqliteCurriculumRepository curriculumRepository;
	private final SqliteDatabase database;
	private final PerformanceRecorder performanceRecorder;
	private final SqliteQuestionWriter writer;
	private final SqliteSharedQuestionContextRepository sharedContextRepository;

	/**
	 * Creates a SQLite Question repository with diagnostics disabled.
	 *
	 * @param database initialised Question database
	 */
	public SqliteQuestionRepository(SqliteDatabase database) {
		this(database, new PerformanceRecorder(false, Path.of("performance.csv")));
	}

	/**
	 * Creates a SQLite Question repository with shared diagnostics.
	 *
	 * @param database            initialised Question database
	 * @param performanceRecorder application performance recorder
	 */
	public SqliteQuestionRepository(SqliteDatabase database, PerformanceRecorder performanceRecorder) {
		if (database == null) {
			throw new NullPointerException("database");
		}
		if (performanceRecorder == null) {
			throw new NullPointerException("performanceRecorder");
		}
		this.database = database;
		this.performanceRecorder = performanceRecorder;
		this.writer = new SqliteQuestionWriter(database);
		this.curriculumRepository = new SqliteCurriculumRepository(database);
		this.sharedContextRepository = new SqliteSharedQuestionContextRepository(database);
	}

	@Override
	public int applySharedContextToSourceQuestion(SourceQuestion sourceQuestion, SharedQuestionContext sharedContext) {
		try {
			return writer.applySharedContextToSourceQuestion(sourceQuestion, sharedContext);
		} catch (SQLException e) {
			throw new IllegalStateException("Could not apply shared context to source question", e);
		}
	}

	@Override
	public Question attachRegions(long questionId, List<QuestionRegion> regions) {
		try {
			writer.attachRegions(questionId, regions);
			return findById(questionId)
					.orElseThrow(() -> new IllegalStateException("Question disappeared after attaching regions"));
		} catch (SQLException e) {
			throw new IllegalStateException("Could not attach question regions", e);
		}
	}

	@Override
	public Question attachRegions(long questionId, List<QuestionRegion> regions, CurriculumNode classification,
			SourceQuestion sourceQuestion, SharedQuestionContext sharedContext) {
		try {
			writer.attachRegions(questionId, regions, classification, sourceQuestion, sharedContext);
			return findById(questionId).orElseThrow(() -> new IllegalStateException(
					"Question disappeared after attaching regions and capture details"));
		} catch (SQLException e) {
			throw new IllegalStateException("Could not attach question regions and capture details", e);
		}
	}

	@Override
	public Question attachRegions(long questionId, List<QuestionRegion> regions, SourceQuestion sourceQuestion,
			SharedQuestionContext sharedContext) {
		try {
			writer.attachRegions(questionId, regions, sourceQuestion, sharedContext);
			return findById(questionId).orElseThrow(
					() -> new IllegalStateException("Question disappeared after attaching regions and relationships"));
		} catch (SQLException e) {
			throw new IllegalStateException("Could not attach question regions and relationships", e);
		}
	}

	@Override
	public List<Question> findAll() {
		List<Long> questionIds = new ArrayList<>();

		// Keep one connection throughout the bulk retrieval. The ID
		// cursor is closed before reconstructing complete Questions.
		try (Connection connection = database.openConnection()) {
			try (Statement statement = connection.createStatement();
					ResultSet result = statement.executeQuery(SqliteQuestionSelectSql.allIds())) {
				while (result.next()) {
					questionIds.add(result.getLong("id"));
				}
			}
			List<Question> questions = new ArrayList<>(questionIds.size());
			for (Long questionId : questionIds) {
				Optional<Question> question;
				try {
					question = findById(connection, questionId);
				} catch (SQLException failure) {
					throw new IllegalStateException("Could not read question " + questionId + " from database",
							failure);
				}
				if (question.isEmpty()) {
					throw new IllegalStateException("Question disappeared while reading database: " + questionId);
				}
				questions.add(question.get());
			}
			return questions;
		} catch (SQLException failure) {
			throw new IllegalStateException("Could not read questions from database", failure);
		}
	}

	@Override
	public List<QuestionApplicabilityMatch> findApplicableToNodes(List<CurriculumNode> currentNodes) {
		return findApplicableToNodes(currentNodes, 0);
	}

	@Override
	public List<QuestionApplicabilityMatch> findApplicableToNodes(List<CurriculumNode> currentNodes,
			long parentOperationId) {
		return retrieveApplicabilityMatches(currentNodes, null, parentOperationId);
	}

	/**
	 * Retrieves current-curriculum applicability for one persisted Question.
	 *
	 * The Question ID is constrained in SQL before any complete Question
	 * reconstruction occurs.
	 *
	 * @param currentNodes      current Subtopic/Descriptor retrieval nodes
	 * @param questionId        persistent Question identifier
	 * @param parentOperationId enclosing diagnostic operation identifier
	 * @return the selected Question's applicable current-node matches
	 */
	@Override
	public List<QuestionApplicabilityMatch> findApplicableToNodesForQuestion(List<CurriculumNode> currentNodes,
			long questionId, long parentOperationId) {
		if (questionId <= 0) {
			throw new IllegalArgumentException("questionId must be positive");
		}
		return retrieveApplicabilityMatches(currentNodes, questionId, parentOperationId);
	}

	@Override
	public Optional<Question> findById(long id) {
		try (Connection connection = database.openConnection()) {
			return findById(connection, id);
		} catch (SQLException failure) {
			throw new IllegalStateException("Could not read question " + id + " from database", failure);
		}
	}

	@Override
	public Question save(ExamBooklet booklet, String questionCode, String questionText, int marks,
			List<QuestionRegion> regions, CurriculumNode classification, boolean sharedContextCaptureRequired) {
		try {
			return writer.insertQuestion(booklet, questionCode, questionText, marks, regions, classification,
					sharedContextCaptureRequired);
		} catch (SQLException e) {
			throw new IllegalStateException("Could not save question", e);
		}
	}

	@Override
	public Question save(ExamBooklet booklet, String questionCode, String questionText, int marks,
			List<QuestionRegion> regions, CurriculumNode classification, boolean sharedContextCaptureRequired,
			SourceQuestion sourceQuestion, SharedQuestionContext sharedContext) {
		try {
			return writer.insertQuestion(booklet, questionCode, questionText, marks, regions, classification,
					sharedContextCaptureRequired, sourceQuestion, sharedContext);
		} catch (SQLException e) {
			throw new IllegalStateException("Could not save question", e);
		}
	}

	@Override
	public Question save(ExamBooklet booklet, String questionCode, String questionText, int marks,
			List<QuestionRegion> regions, CurriculumNode classification, boolean sharedContextCaptureRequired,
			SourceQuestion sourceQuestion, SharedQuestionContext sharedContext, QuestionResponseType responseType) {
		try {
			return writer.insertQuestion(booklet, questionCode, questionText, marks, regions, classification,
					sharedContextCaptureRequired, sourceQuestion, sharedContext, responseType);
		} catch (SQLException e) {
			throw new IllegalStateException("Could not save question", e);
		}
	}

	/**
	 * Persists a Question whose body may contain both PDF regions and stored
	 * images.
	 *
	 * @param booklet                      source booklet
	 * @param questionCode                 question code
	 * @param questionText                 supplementary text
	 * @param marks                        positive mark value
	 * @param contentParts                 authoritative mixed body order
	 * @param classification               original classification
	 * @param sharedContextCaptureRequired historical shared-context evidence
	 * @param sourceQuestion               source identity, or null
	 * @param sharedContext                shared context, or null
	 * @param responseType                 authoritative response type
	 * @return persisted Question
	 */
	public Question saveWithContent(ExamBooklet booklet, String questionCode, String questionText, int marks,
			List<QuestionContentPart> contentParts, CurriculumNode classification, boolean sharedContextCaptureRequired,
			SourceQuestion sourceQuestion, SharedQuestionContext sharedContext, QuestionResponseType responseType) {
		try {
			return writer.insertQuestionWithContent(booklet, questionCode, questionText, marks, contentParts,
					classification, sharedContextCaptureRequired, sourceQuestion, sharedContext, responseType);
		} catch (SQLException e) {
			throw new IllegalStateException("Could not save mixed-content question", e);
		}
	}

	@Override
	public Question updateCaptureRelationships(long questionId, CurriculumNode classification,
			SourceQuestion sourceQuestion, SharedQuestionContext sharedContext) {
		Question existing = findById(questionId)
				.orElseThrow(() -> new IllegalArgumentException("Question does not exist: " + questionId));
		try {
			writer.updateCaptureRelationships(questionId, existing.getBooklet(), classification, sourceQuestion,
					sharedContext);
			return findById(questionId).orElseThrow(
					() -> new IllegalStateException("Question disappeared after updating capture relationships"));
		} catch (SQLException e) {
			throw new IllegalStateException("Could not update question capture relationships", e);
		}
	}

	/**
	 * Updates only the stored curriculum classification of an existing Question.
	 *
	 * @param questionId     persistent Question identifier
	 * @param classification replacement classification in the existing syllabus
	 * @return the reloaded Question after the classification change
	 */
	@Override
	public Question updateClassification(long questionId, CurriculumNode classification) {
		Question existing = findById(questionId)
				.orElseThrow(() -> new IllegalArgumentException("Question does not exist: " + questionId));
		try {

			// Delegate the narrow persistence change to the writer so Question regions,
			// relationships and other metadata remain untouched.
			writer.updateClassification(questionId, existing.getBooklet(), classification);
			return findById(questionId)
					.orElseThrow(() -> new IllegalStateException("Question disappeared after classification update"));
		} catch (SQLException e) {
			throw new IllegalStateException("Could not update question classification", e);
		}
	}

	@Override
	public Question updateQuestion(long questionId, String questionCode, int marks, List<QuestionRegion> regions,
			CurriculumNode classification, SourceQuestion sourceQuestion, SharedQuestionContext sharedContext) {
		Question existing = findById(questionId)
				.orElseThrow(() -> new IllegalArgumentException("Question does not exist: " + questionId));
		try {
			writer.updateQuestion(questionId, existing.getBooklet(), questionCode, marks, regions, classification,
					sourceQuestion, sharedContext);
			return findById(questionId)
					.orElseThrow(() -> new IllegalStateException("Question disappeared after update"));
		} catch (SQLException e) {
			throw new IllegalStateException("Could not update question", e);
		}
	}

	private Answer findAnswer(Connection connection, long questionId, Exam exam) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT id, answer_text
				FROM answers
				WHERE question_id = ?
				""")) {
			statement.setLong(1, questionId);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					return null;
				}
				long answerId = result.getLong("id");
				String answerText = result.getString("answer_text");
				List<AnswerRegion> regions = findAnswerRegions(connection, answerId, exam);
				return new Answer(answerId, answerText, regions);
			}
		}
	}

	private List<AnswerRegion> findAnswerRegions(Connection connection, long answerId, Exam exam) throws SQLException {
		List<AnswerRegion> regions = new ArrayList<>();
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT
				    ar.page_number,
				    ar.x,
				    ar.y,
				    ar.width,
				    ar.height,
				    af.id AS answer_file_id,
				    af.exam_id AS answer_file_exam_id,
				    af.answer_file_name,
				    af.contains_answer_explanations,
				    sd.id AS source_document_id,
				    sd.relative_path,
				    sd.content_sha256
				FROM answer_regions ar
				JOIN answer_files af
				    ON af.id = ar.answer_file_id
				JOIN source_documents sd
				    ON sd.id = af.source_document_id
				WHERE ar.answer_id = ?
				ORDER BY ar.region_order
				""")) {
			statement.setLong(1, answerId);
			try (ResultSet result = statement.executeQuery()) {
				while (result.next()) {
					if (result.getLong("answer_file_exam_id") != exam.getId()) {
						throw new IllegalStateException("Answer region file belongs to a different exam");
					}
					SourceDocument sourceDocument = new SourceDocument(result.getLong("source_document_id"),
							result.getString("relative_path"), result.getString("content_sha256"));

					// Question reconstruction must expose the same AnswerFile metadata as
					// direct Exam/asset lookup.
					AnswerFile answerFile = new AnswerFile(result.getLong("answer_file_id"), exam,
							result.getString("answer_file_name"), sourceDocument,
							result.getBoolean("contains_answer_explanations"));
					AnswerRegion region = new AnswerRegion(answerFile, result.getInt("page_number"),
							result.getDouble("x"), result.getDouble("y"), result.getDouble("width"),
							result.getDouble("height"));
					regions.add(region);
				}
			}
		}
		return regions;
	}

	private Optional<Question> findById(Connection connection, long id) throws SQLException {

		// The caller owns the connection. This method closes only its
		// statement and result set, allowing subsequent Question reads
		// to reuse the same connection.
		try (PreparedStatement statement = connection.prepareStatement(SqliteQuestionSelectSql.byId())) {
			statement.setLong(1, id);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					return Optional.empty();
				}
				return Optional.of(readQuestion(connection, result, id));
			}
		}
	}

	private List<QuestionContentPart> findContentParts(Connection connection, long questionId,
			ExamBooklet questionBooklet) throws SQLException {
		List<QuestionContentPart> contentParts = new ArrayList<>();
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT
				    qcp.content_type,
				    qr.page_number,
				    qr.x,
				    qr.y,
				    qr.width,
				    qr.height,
				    qr.booklet_id,
				    qi.image_png
				FROM question_content_parts qcp
				LEFT JOIN question_regions qr
				    ON qr.question_id = qcp.question_id
				   AND qr.region_order = qcp.region_order
				LEFT JOIN question_images qi
				    ON qi.question_id = qcp.question_id
				   AND qi.id = qcp.image_id
				WHERE qcp.question_id = ?
				ORDER BY qcp.content_order
				""")) {
			statement.setLong(1, questionId);
			try (ResultSet result = statement.executeQuery()) {
				while (result.next()) {
					String contentType = result.getString("content_type");
					if ("PDF_REGION".equals(contentType)) {
						if (result.getLong("booklet_id") != questionBooklet.getId()) {
							throw new IllegalStateException("Question PDF content belongs to a different booklet");
						}
						QuestionRegion region = new QuestionRegion(questionBooklet, result.getInt("page_number"),
								result.getDouble("x"), result.getDouble("y"), result.getDouble("width"),
								result.getDouble("height"));
						contentParts.add(new PdfQuestionContentPart(region));
						continue;
					}
					if ("IMAGE".equals(contentType)) {
						byte[] imageBytes = result.getBytes("image_png");
						if (imageBytes == null || imageBytes.length == 0) {
							throw new IllegalStateException("Question image content has no stored PNG data");
						}
						contentParts.add(new ImageQuestionContentPart(imageBytes));
						continue;
					}
					throw new IllegalStateException("Unsupported Question content type: " + contentType);
				}
			}
		}
		return List.copyOf(contentParts);
	}

	private SharedQuestionContext findSharedQuestionContext(Connection connection, long sharedContextId,
			ExamBooklet questionBooklet) throws SQLException {

		// Use the authoritative v24 mixed-content reader.
		// It preserves content order, validates stored image references,
		// and restricts the context to the Question's booklet.
		return sharedContextRepository.findById(connection, questionBooklet, sharedContextId)
				.orElseThrow(() -> new IllegalStateException("Missing shared question context " + sharedContextId
						+ " for booklet " + questionBooklet.getId()));
	}

	private SourceQuestion findSourceQuestion(Connection connection, long sourceQuestionId, ExamBooklet questionBooklet)
			throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT booklet_id, source_question_code, shared_context_status
				FROM source_questions
				WHERE id = ?
				""")) {
			statement.setLong(1, sourceQuestionId);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new IllegalStateException("Missing source question " + sourceQuestionId);
				}
				if (result.getLong("booklet_id") != questionBooklet.getId()) {
					throw new IllegalStateException("Source question belongs to a different booklet");
				}
				return new SourceQuestion(sourceQuestionId, questionBooklet, result.getString("source_question_code"),
						SharedContextStatus.valueOf(result.getString("shared_context_status")));
			}
		}
	}

	private Question readQuestion(Connection connection, ResultSet result, long questionId) throws SQLException {
		Subject subject = new Subject(result.getLong("subject_id"), result.getString("subject_name"));
		ExamProvider provider = new ExamProvider(result.getLong("provider_id"), result.getString("provider_name"));
		ExamCaptureState captureState = ExamCaptureState.valueOf(result.getString("capture_state"));
		Exam exam = new Exam(result.getLong("exam_id"), subject, provider, result.getInt("exam_year"),
				result.getString("exam_name"), captureState);

		// Question reload must retain managed-document byte identity.
		SourceDocument sourceDocument = new SourceDocument(result.getLong("source_document_id"),
				result.getString("relative_path"), result.getString("content_sha256"));
		ExamBookletQuestionFormat questionFormat = ExamBookletQuestionFormat
				.valueOf(result.getString("question_format"));
		int storedExpectedQuestionCount = result.getInt("expected_question_count");
		Integer expectedQuestionCount = result.wasNull() ? null : storedExpectedQuestionCount;
		ExamBooklet booklet = new ExamBooklet(result.getLong("booklet_id"), exam, result.getString("booklet_name"),
				sourceDocument, questionFormat, expectedQuestionCount);
		long classificationNodeId = result.getLong("classification_node_id");

		// Reconstruct classification and ancestors using the existing connection.
		CurriculumNode classification = curriculumRepository.findNodeById(connection, classificationNodeId)
				.orElseThrow(() -> new IllegalStateException("Missing curriculum node " + classificationNodeId));
		List<QuestionContentPart> contentParts = findContentParts(connection, questionId, booklet);
		List<QuestionRegion> regions = regionsFromContentParts(contentParts);
		long sourceQuestionId = result.getLong("source_question_id");
		SourceQuestion sourceQuestion = result.wasNull() ? null
				: findSourceQuestion(connection, sourceQuestionId, booklet);
		long sharedContextId = result.getLong("shared_context_id");
		SharedQuestionContext sharedContext = result.wasNull() ? null
				: findSharedQuestionContext(connection, sharedContextId, booklet);
		QuestionResponseType responseType = QuestionResponseType.valueOf(result.getString("response_type"));
		Question question = new Question(result.getLong("id"), booklet, result.getString("question_code"),
				result.getString("question_text"), result.getInt("marks"), regions, classification,
				result.getInt("shared_context_capture_required") != 0, sourceQuestion, sharedContext, responseType,
				contentParts, result.getInt("source_capture_required") != 0);
		Answer answer = findAnswer(connection, questionId, exam);
		if (answer != null) {
			question.setAnswer(answer);
		}

		// Count successfully reconstructed Questions only when profiling.
		SqliteReadProbe.recordQuestionReconstruction();
		return question;
	}

	private List<QuestionApplicabilityMatch> reconstructApplicabilityMatches(List<ApplicabilityRow> rows,
			Map<Long, CurriculumNode> requestedNodesById) {
		if (rows.isEmpty()) {
			return List.of();
		}
		Map<Long, Question> questionsById = new LinkedHashMap<>();
		List<QuestionApplicabilityMatch> matches = new ArrayList<>();

		// Applicability SQL has already completed. Reconstruct the
		// matching Questions using one connection rather than opening
		// a new connection for every unique Question.
		try (Connection connection = database.openConnection()) {
			for (ApplicabilityRow row : rows) {
				Question question = questionsById.get(row.questionId());
				if (question == null) {
					try {
						question = findById(connection, row.questionId()).orElseThrow(() -> new IllegalStateException(
								"Question disappeared while " + "retrieving applicability: " + row.questionId()));
					} catch (SQLException failure) {
						throw new IllegalStateException(
								"Could not read question " + row.questionId() + " from database", failure);
					}
					questionsById.put(row.questionId(), question);
				}
				CurriculumNode currentNode = requestedNodesById.get(row.currentNodeId());
				if (currentNode == null) {
					throw new IllegalStateException("Retrieval query returned an unrequested " + "current node");
				}

				// Keep a separate match for each applicable node while
				// sharing the reconstructed Question instance.
				matches.add(new QuestionApplicabilityMatch(question, currentNode));
			}
			return List.copyOf(matches);
		} catch (SQLException failure) {
			throw new IllegalStateException("Could not reconstruct applicable questions", failure);
		}
	}

	private List<QuestionRegion> regionsFromContentParts(List<QuestionContentPart> contentParts) {
		List<QuestionRegion> regions = new ArrayList<>();

		// Preserve the legacy getRegions() view by projecting only PDF fragments from
		// the authoritative mixed content sequence.
		for (QuestionContentPart part : contentParts) {
			if (part instanceof PdfQuestionContentPart pdfPart) {
				regions.add(pdfPart.region());
			}
		}
		return List.copyOf(regions);
	}

	private List<QuestionApplicabilityMatch> retrieveApplicabilityMatches(List<CurriculumNode> currentNodes,
			Long questionId, long parentOperationId) {
		Map<Long, CurriculumNode> requestedNodesById = validateRetrievalNodes(currentNodes);

		// Broad and Question-specific retrieval share the same applicability SQL
		// semantics. Only the optional Question-ID predicate differs.
		String sql = questionId == null ? SqliteQuestionSelectSql.applicability(requestedNodesById.size())
				: SqliteQuestionSelectSql.applicabilityForQuestion(requestedNodesById.size());
		List<ApplicabilityRow> rows = new ArrayList<>();
		try (PerformanceOperation sqlOperation = performanceRecorder.start("search.repository.sql",
				parentOperationId)) {
			try (Connection connection = database.openConnection();
					PreparedStatement statement = connection.prepareStatement(sql)) {
				int parameterIndex = 1;
				for (CurriculumNode currentNode : requestedNodesById.values()) {
					statement.setLong(parameterIndex++, currentNode.getId());
				}
				if (questionId != null) {
					statement.setLong(parameterIndex, questionId);
				}
				try (ResultSet result = statement.executeQuery()) {
					try (PerformanceOperation rowsOperation = performanceRecorder.start("search.repository.rows",
							sqlOperation.id())) {
						try {
							while (result.next()) {
								rows.add(new ApplicabilityRow(result.getLong("question_id"),
										result.getLong("current_node_id")));
							}
							rowsOperation.resultCount(rows.size());
						} catch (SQLException | RuntimeException | Error failure) {
							rowsOperation.failed();
							throw failure;
						}
					}
				}
			} catch (SQLException failure) {
				sqlOperation.failed();
				throw new IllegalStateException("Could not retrieve questions by curriculum applicability", failure);
			} catch (RuntimeException | Error failure) {
				sqlOperation.failed();
				throw failure;
			}
			sqlOperation.resultCount(rows.size());
		}

		// Reconstruction remains a separate measured phase.
		// The selective SQL path produces rows for at most one Question ID.
		try (PerformanceOperation reconstructionOperation = performanceRecorder.start("search.repository.reconstruct",
				parentOperationId)) {
			try {
				List<QuestionApplicabilityMatch> matches = reconstructApplicabilityMatches(rows, requestedNodesById);
				reconstructionOperation.resultCount(matches.size());
				return matches;
			} catch (RuntimeException | Error failure) {
				reconstructionOperation.failed();
				throw failure;
			}
		}
	}

	private Map<Long, CurriculumNode> validateRetrievalNodes(List<CurriculumNode> currentNodes) {
		if (currentNodes == null) {
			throw new NullPointerException("currentNodes");
		}
		if (currentNodes.isEmpty()) {
			throw new IllegalArgumentException("currentNodes must not be empty");
		}
		Map<Long, CurriculumNode> requestedNodesById = new LinkedHashMap<Long, CurriculumNode>();
		for (CurriculumNode currentNode : currentNodes) {
			if (currentNode == null) {
				throw new NullPointerException("currentNodes contains null");
			}
			if (!currentNode.getSyllabusVersion().isCurrent()) {
				throw new IllegalArgumentException("Retrieval nodes must belong to a current syllabus");
			}
			CurriculumLevel level = currentNode.getLevel();
			if (level != CurriculumLevel.SUBTOPIC && level != CurriculumLevel.DESCRIPTOR) {
				throw new IllegalArgumentException("Retrieval nodes must be SUBTOPIC or DESCRIPTOR");
			}
			requestedNodesById.putIfAbsent(currentNode.getId(), currentNode);
		}
		return requestedNodesById;
	}

	private record ApplicabilityRow(long questionId, long currentNodeId) {
	}
}
