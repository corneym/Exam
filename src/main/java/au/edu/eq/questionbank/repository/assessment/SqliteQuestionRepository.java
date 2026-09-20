package au.edu.eq.questionbank.repository.assessment;

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

import au.edu.eq.questionbank.model.Answer;
import au.edu.eq.questionbank.model.AnswerFile;
import au.edu.eq.questionbank.model.AnswerRegion;
import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.PreambleStatus;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.SharedQuestionContext;
import au.edu.eq.questionbank.model.SharedQuestionContextRegion;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.SourceQuestion;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.repository.curriculum.CurriculumRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumRepository;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

/**
 * SQLite-backed question repository that reconstructs complete question,
 * region, exam, curriculum, and answer state from persistent records.
 */
public final class SqliteQuestionRepository implements QuestionRepository, QuestionRetrievalRepository {

	private final SqliteDatabase database;
	private final SqliteQuestionWriter writer;
	private final CurriculumRepository curriculumRepository;

	/**
	 * Creates a repository for an initialised question-bank database.
	 *
	 * @param database the question-bank database
	 * @throws NullPointerException if {@code database} is {@code null}
	 */
	public SqliteQuestionRepository(SqliteDatabase database) {
		if (database == null) {
			throw new NullPointerException("database");
		}
		this.database = database;
		this.writer = new SqliteQuestionWriter(database);
		this.curriculumRepository = new SqliteCurriculumRepository(database);
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
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT id
						FROM questions
						ORDER BY id
						""")) {
			while (result.next()) {
				questionIds.add(result.getLong("id"));
			}
		} catch (SQLException e) {
			throw new IllegalStateException("Could not read questions from database", e);
		}
		List<Question> questions = new ArrayList<>();
		for (Long questionId : questionIds) {
			Optional<Question> question = findById(questionId.longValue());
			if (question.isEmpty()) {
				throw new IllegalStateException("Question disappeared while reading database: " + questionId);
			}
			questions.add(question.get());
		}
		return questions;
	}

	@Override
	public List<QuestionApplicabilityMatch> findApplicableToNodes(List<CurriculumNode> currentNodes) {
		Map<Long, CurriculumNode> requestedNodesById = validateRetrievalNodes(currentNodes);
		String requestedNodeValues = createRequestedNodeValues(requestedNodesById.size());

		// Match direct current classifications or confirmed historical-to-current
		// mappings.
		String sql = """
				WITH requested_nodes(node_id) AS (
				    VALUES %s
				)
				SELECT DISTINCT
				    q.id AS question_id,
				    requested.node_id AS current_node_id
				FROM requested_nodes requested
				JOIN curriculum_nodes target
				    ON target.id = requested.node_id
				JOIN syllabus_versions target_version
				    ON target_version.id = target.syllabus_version_id
				CROSS JOIN questions q
				JOIN exam_booklets eb
				    ON eb.id = q.booklet_id
				JOIN exams e
				    ON e.id = eb.exam_id
				JOIN curriculum_nodes classification
				    ON classification.id = q.classification_node_id
				JOIN syllabus_versions classification_version
				    ON classification_version.id =
				       classification.syllabus_version_id
				WHERE target_version.is_current = 1
				  AND target.curriculum_level IN ('SUBTOPIC', 'DESCRIPTOR')
				  AND e.subject_id = target_version.subject_id
				  AND (
				      (
				          q.classification_node_id = requested.node_id
				          AND classification_version.id = target_version.id
				      )
				      OR EXISTS (
				          SELECT 1
				          FROM curriculum_mappings mapping
				          WHERE mapping.source_node_id =
				                q.classification_node_id
				            AND mapping.target_node_id =
				                requested.node_id
				            AND mapping.mapping_status = 'CONFIRMED'
				            AND classification_version.is_current = 0
				            AND classification_version.subject_id =
				                target_version.subject_id
				            AND classification.curriculum_level =
				                target.curriculum_level
				      )
				  )
				ORDER BY q.id, requested.node_id
				""".formatted(requestedNodeValues);
		List<ApplicabilityRow> rows = new ArrayList<ApplicabilityRow>();
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement(sql)) {
			int parameterIndex = 1;
			for (CurriculumNode currentNode : requestedNodesById.values()) {
				statement.setLong(parameterIndex, currentNode.getId());
				parameterIndex++;
			}
			try (ResultSet result = statement.executeQuery()) {
				while (result.next()) {
					rows.add(new ApplicabilityRow(result.getLong("question_id"), result.getLong("current_node_id")));
				}
			}
		} catch (SQLException e) {
			throw new IllegalStateException("Could not retrieve questions by curriculum applicability", e);
		}
		return reconstructApplicabilityMatches(rows, requestedNodesById);
	}

	@Override
	public Optional<Question> findById(long id) {
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT
							q.id,
							q.question_code,
							q.question_text,
							q.marks,
							q.preamble_capture_required,
							q.response_type,
							q.source_question_id,
							q.shared_context_id,
							q.classification_node_id,
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
							p.provider_name,
							cn.syllabus_version_id,
							cn.curriculum_code
						FROM questions q
						JOIN exam_booklets eb
							ON eb.id = q.booklet_id
						JOIN source_documents sd
							ON sd.id = eb.source_document_id
						JOIN exams e
							ON e.id = eb.exam_id
						JOIN subjects s
							ON s.id = e.subject_id
						JOIN exam_providers p
							ON p.id = e.provider_id
						JOIN curriculum_nodes cn
							ON cn.id = q.classification_node_id
						WHERE q.id = ?
																		""")) {
			statement.setLong(1, id);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					return Optional.empty();
				}
				return Optional.of(readQuestion(connection, result, id));
			}
		} catch (SQLException e) {
			throw new IllegalStateException("Could not read question " + id + " from database", e);
		}
	}

	@Override
	public Question save(ExamBooklet booklet, String questionCode, String questionText, int marks,
			List<QuestionRegion> regions, CurriculumNode classification, boolean preambleCaptureRequired) {
		try {
			return writer.insertQuestion(booklet, questionCode, questionText, marks, regions, classification,
					preambleCaptureRequired);
		} catch (SQLException e) {
			throw new IllegalStateException("Could not save question", e);
		}
	}

	@Override
	public Question save(ExamBooklet booklet, String questionCode, String questionText, int marks,
			List<QuestionRegion> regions, CurriculumNode classification, boolean preambleCaptureRequired,
			SourceQuestion sourceQuestion, SharedQuestionContext sharedContext) {
		try {
			return writer.insertQuestion(booklet, questionCode, questionText, marks, regions, classification,
					preambleCaptureRequired, sourceQuestion, sharedContext);
		} catch (SQLException e) {
			throw new IllegalStateException("Could not save question", e);
		}
	}

	@Override
	public Question save(ExamBooklet booklet, String questionCode, String questionText, int marks,
			List<QuestionRegion> regions, CurriculumNode classification, boolean preambleCaptureRequired,
			SourceQuestion sourceQuestion, SharedQuestionContext sharedContext, QuestionResponseType responseType) {
		try {
			return writer.insertQuestion(booklet, questionCode, questionText, marks, regions, classification,
					preambleCaptureRequired, sourceQuestion, sharedContext, responseType);
		} catch (SQLException e) {
			throw new IllegalStateException("Could not save question", e);
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

	private List<QuestionApplicabilityMatch> reconstructApplicabilityMatches(List<ApplicabilityRow> rows,
			Map<Long, CurriculumNode> requestedNodesById) {

		// Load each question once while retaining a separate match for every applicable
		// node.
		Map<Long, Question> questionsById = new LinkedHashMap<Long, Question>();
		List<QuestionApplicabilityMatch> matches = new ArrayList<QuestionApplicabilityMatch>();
		for (ApplicabilityRow row : rows) {
			Question question = questionsById.get(row.questionId());
			if (question == null) {
				question = findById(row.questionId()).orElseThrow(() -> new IllegalStateException(
						"Question disappeared while retrieving applicability: " + row.questionId()));
				questionsById.put(row.questionId(), question);
			}
			CurriculumNode currentNode = requestedNodesById.get(row.currentNodeId());
			if (currentNode == null) {
				throw new IllegalStateException("Retrieval query returned an unrequested current node");
			}
			matches.add(new QuestionApplicabilityMatch(question, currentNode));
		}
		return List.copyOf(matches);
	}

	private String createRequestedNodeValues(int nodeCount) {
		StringBuilder values = new StringBuilder();
		for (int index = 0; index < nodeCount; index++) {
			if (index > 0) {
				values.append(", ");
			}
			values.append("(?)");
		}
		return values.toString();
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
				    sd.id AS source_document_id,
				    sd.relative_path
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
							result.getString("relative_path"));
					AnswerFile answerFile = new AnswerFile(result.getLong("answer_file_id"), exam,
							result.getString("answer_file_name"), sourceDocument);
					AnswerRegion region = new AnswerRegion(answerFile, result.getInt("page_number"),
							result.getDouble("x"), result.getDouble("y"), result.getDouble("width"),
							result.getDouble("height"));
					regions.add(region);
				}
			}
		}
		return regions;
	}

	private List<QuestionRegion> findRegions(Connection connection, long questionId, ExamBooklet questionBooklet)
			throws SQLException {
		List<QuestionRegion> regions = new ArrayList<>();
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT
				    qr.page_number,
				    qr.x,
				    qr.y,
				    qr.width,
				    qr.height,
				    eb.id AS booklet_id
				FROM question_regions qr
				JOIN exam_booklets eb
				    ON eb.id = qr.booklet_id
				WHERE qr.question_id = ?
				ORDER BY qr.region_order
				""")) {
			statement.setLong(1, questionId);
			try (ResultSet result = statement.executeQuery()) {
				while (result.next()) {
					if (result.getLong("booklet_id") != questionBooklet.getId()) {
						throw new IllegalStateException("Question region belongs to a different booklet");
					}
					QuestionRegion region = new QuestionRegion(questionBooklet, result.getInt("page_number"),
							result.getDouble("x"), result.getDouble("y"), result.getDouble("width"),
							result.getDouble("height"));
					regions.add(region);
				}
			}
		}
		return regions;
	}

	private SharedQuestionContext findSharedQuestionContext(Connection connection, long sharedContextId,
			ExamBooklet questionBooklet) throws SQLException {
		String label;
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT booklet_id, context_label
				FROM shared_question_contexts
				WHERE id = ?
				""")) {
			statement.setLong(1, sharedContextId);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new IllegalStateException("Missing shared question context " + sharedContextId);
				}
				if (result.getLong("booklet_id") != questionBooklet.getId()) {
					throw new IllegalStateException("Shared question context belongs to a different booklet");
				}
				label = result.getString("context_label");
			}
		}
		List<SharedQuestionContextRegion> regions = findSharedQuestionContextRegions(connection, sharedContextId);
		if (regions.isEmpty()) {
			throw new IllegalStateException("Shared question context has no regions: " + sharedContextId);
		}
		return new SharedQuestionContext(sharedContextId, questionBooklet, label, regions);
	}

	private List<SharedQuestionContextRegion> findSharedQuestionContextRegions(Connection connection,
			long sharedContextId) throws SQLException {
		List<SharedQuestionContextRegion> regions = new ArrayList<>();
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT
				    page_number,
				    x,
				    y,
				    width,
				    height
				FROM shared_question_context_regions
				WHERE shared_context_id = ?
				ORDER BY region_order
				""")) {
			statement.setLong(1, sharedContextId);
			try (ResultSet result = statement.executeQuery()) {
				while (result.next()) {
					regions.add(new SharedQuestionContextRegion(result.getInt("page_number"), result.getDouble("x"),
							result.getDouble("y"), result.getDouble("width"), result.getDouble("height")));
				}
			}
		}
		return regions;
	}

	private SourceQuestion findSourceQuestion(Connection connection, long sourceQuestionId, ExamBooklet questionBooklet)
			throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT booklet_id, source_question_code, preamble_status
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
						PreambleStatus.valueOf(result.getString("preamble_status")));
			}
		}
	}

	private Question readQuestion(Connection connection, ResultSet result, long questionId) throws SQLException {
		Subject subject = new Subject(result.getLong("subject_id"), result.getString("subject_name"));
		ExamProvider provider = new ExamProvider(result.getLong("provider_id"), result.getString("provider_name"));
		Exam exam = new Exam(result.getLong("exam_id"), subject, provider, result.getInt("exam_year"),
				result.getString("exam_name"));
		SourceDocument sourceDocument = new SourceDocument(result.getLong("source_document_id"),
				result.getString("relative_path"));
		ExamBooklet booklet = new ExamBooklet(result.getLong("booklet_id"), exam, result.getString("booklet_name"),
				sourceDocument);

		// Reconstruct the original classification even when retrieval matched a newer
		// syllabus.
		long syllabusVersionId = result.getLong("syllabus_version_id");
		SyllabusVersion syllabusVersion = curriculumRepository.findVersionById(syllabusVersionId)
				.orElseThrow(() -> new IllegalStateException("Missing syllabus version " + syllabusVersionId));
		String curriculumCode = result.getString("curriculum_code");
		CurriculumNode classification = curriculumRepository.findByCode(syllabusVersion, curriculumCode)
				.orElseThrow(() -> new IllegalStateException("Missing curriculum node " + curriculumCode));
		List<QuestionRegion> regions = findRegions(connection, questionId, booklet);
		long sourceQuestionId = result.getLong("source_question_id");
		SourceQuestion sourceQuestion = result.wasNull() ? null
				: findSourceQuestion(connection, sourceQuestionId, booklet);
		long sharedContextId = result.getLong("shared_context_id");
		SharedQuestionContext sharedContext = result.wasNull() ? null
				: findSharedQuestionContext(connection, sharedContextId, booklet);
		QuestionResponseType responseType = QuestionResponseType.valueOf(result.getString("response_type"));
		Question question = new Question(result.getLong("id"), booklet, result.getString("question_code"),
				result.getString("question_text"), result.getInt("marks"), regions, classification,
				result.getInt("preamble_capture_required") != 0, sourceQuestion, sharedContext, responseType);
		Answer answer = findAnswer(connection, questionId, exam);
		if (answer != null) {
			question.setAnswer(answer);
		}
		return question;
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
