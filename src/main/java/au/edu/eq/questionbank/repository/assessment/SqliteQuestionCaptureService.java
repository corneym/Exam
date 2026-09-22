package au.edu.eq.questionbank.repository.assessment;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.SharedContextStatus;
import au.edu.eq.questionbank.model.SharedQuestionContext;
import au.edu.eq.questionbank.model.SharedQuestionContextRegion;
import au.edu.eq.questionbank.model.SourceQuestion;
import au.edu.eq.questionbank.model.SourceQuestionCodeParser;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

/**
 * Persists one complete question-capture workflow in a single SQLite
 * transaction.
 */
public final class SqliteQuestionCaptureService {

	private final SqliteDatabase database;
	private final SqliteQuestionWriter questionWriter;
	private final SqliteSourceQuestionRepository sourceQuestionRepository;
	private final SqliteSharedQuestionContextRepository sharedContextRepository;

	/**
	 * Creates a capture service sharing one database across question and context
	 * writers.
	 *
	 * @param database initialised question-bank database
	 */
	public SqliteQuestionCaptureService(SqliteDatabase database) {
		if (database == null) {
			throw new NullPointerException("database");
		}
		this.database = database;
		this.questionWriter = new SqliteQuestionWriter(database);
		this.sourceQuestionRepository = new SqliteSourceQuestionRepository(database);
		this.sharedContextRepository = new SqliteSharedQuestionContextRepository(database);
	}

	/**
	 * Returns the shared context awaiting the next independent MCQ in a booklet.
	 * <p>
	 * This state is persisted so an interrupted capture sequence can resume after
	 * the application is restarted.
	 *
	 * @param booklet booklet whose pending MCQ continuation is requested
	 * @return the pending context, or empty when none is waiting
	 * @throws NullPointerException  if {@code booklet} is {@code null}
	 * @throws IllegalStateException if the stored relationship cannot be read or is
	 *                               inconsistent
	 */
	public Optional<SharedQuestionContext> findPendingMcqSharedContext(ExamBooklet booklet) {
		if (booklet == null) {
			throw new NullPointerException("booklet");
		}
		try (Connection connection = database.openConnection()) {
			return findPendingMcqSharedContext(connection, booklet);
		} catch (SQLException e) {
			throw new IllegalStateException("Could not read pending MCQ shared context", e);
		}
	}

	/**
	 * Returns the pending MCQ shared context only when the supplied Question code
	 * is the immediate numeric successor of the Question that established the
	 * continuation.
	 *
	 * <p>
	 * Examples of supported succession are {@code 5 -> 6}, {@code Q5 -> Q6} and
	 * {@code Q09 -> Q10}. Codes whose sequence cannot be established conservatively
	 * do not inherit automatically.
	 *
	 * @param booklet      active persisted booklet
	 * @param questionCode proposed new Question code
	 * @return applicable pending context, or empty when the code is not its
	 *         successor
	 */
	public Optional<SharedQuestionContext> findPendingMcqSharedContextForQuestion(ExamBooklet booklet,
			String questionCode) {
		if (booklet == null) {
			throw new NullPointerException("booklet");
		}
		if (questionCode == null) {
			throw new NullPointerException("questionCode");
		}
		if (questionCode.isBlank()) {
			return Optional.empty();
		}
		try (Connection connection = database.openConnection()) {
			Optional<SharedQuestionContext> pendingContext = findPendingMcqSharedContext(connection, booklet);
			if (pendingContext.isEmpty()) {
				return Optional.empty();
			}
			if (!pendingMcqAppliesToQuestion(connection, booklet, pendingContext.get(), questionCode, 0)) {
				return Optional.empty();
			}
			return pendingContext;
		} catch (SQLException e) {
			throw new IllegalStateException("Could not resolve pending MCQ shared context", e);
		}
	}

	/**
	 * Persists capture metadata, source identity and shared context in one
	 * transaction.
	 *
	 * @param request validated capture operation
	 * @return question resulting from the committed capture
	 * @throws IllegalStateException if the database transaction fails
	 */
	public Question save(Request request) {
		if (request == null) {
			throw new NullPointerException("request");
		}
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {

				// Resolve identities and shared context before writing the Question.
				SourceQuestion sourceQuestion = resolveSourceQuestion(connection, request);
				SharedQuestionContext sharedContext = resolveSharedContext(connection, request, sourceQuestion);
				if (sourceQuestion != null && sharedContext != null) {
					questionWriter.applySharedContextToSourceQuestion(connection, sourceQuestion, sharedContext);
				}
				sourceQuestion = resolvePreambleStatus(connection, sourceQuestion, sharedContext);
				Question question = persistQuestion(connection, request, sourceQuestion, sharedContext);

				// Update restart-safe MCQ continuation in the same transaction. The
				// persisted Question id is supplied so successor detection can ignore the
				// Question that has just been inserted.
				updatePendingMcqSharedContext(connection, request, sourceQuestion, sharedContext, question);

				deletePreviousSourceQuestionIfUnreferenced(connection, request, sourceQuestion);
				connection.commit();
				return question;
			} catch (SQLException | RuntimeException e) {
				try {
					connection.rollback();
				} catch (SQLException rollbackFailure) {
					e.addSuppressed(rollbackFailure);
				}
				throw e;
			}
		} catch (SQLException e) {
			throw new IllegalStateException("Could not save question capture atomically", e);
		}
	}

	private void clearPendingMcqSharedContext(Connection connection, ExamBooklet booklet) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				UPDATE exam_booklets
				SET pending_mcq_shared_context_id = NULL
				WHERE id = ?
				""")) {
			statement.setLong(1, booklet.getId());

			// Capture only operates on persisted booklets. A missing row indicates stale
			// or inconsistent application state rather than an ordinary empty value.
			if (statement.executeUpdate() != 1) {
				throw new IllegalStateException("Capture booklet could not be found: " + booklet.getId());
			}
		}
	}

	private void deletePreviousSourceQuestionIfUnreferenced(Connection connection, Request request,
			SourceQuestion currentSourceQuestion) throws SQLException {
		if (request.operation() != Operation.EDIT) {
			return;
		}
		Question existing = request.existingQuestion();
		if (!existing.hasSourceQuestion()) {
			return;
		}
		SourceQuestion previousSourceQuestion = existing.getSourceQuestion();
		if (currentSourceQuestion != null && currentSourceQuestion.getId() == previousSourceQuestion.getId()) {
			return;
		}
		sourceQuestionRepository.deleteIfUnreferenced(connection, previousSourceQuestion);
	}

	private boolean editingSourceMatches(Question question, String questionCode) {
		String derivedSourceCode = SourceQuestionCodeParser.derive(questionCode);
		if (!question.hasSourceQuestion()) {
			return derivedSourceCode == null;
		}
		return question.getSourceQuestion().getSourceQuestionCode().equals(derivedSourceCode);
	}

	private String expectedPendingMcqQuestionCode(Connection connection, ExamBooklet booklet,
			SharedQuestionContext sharedContext, long excludedQuestionId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT question_code
				FROM questions
				WHERE booklet_id = ?
				  AND shared_context_id = ?
				  AND source_question_id IS NULL
				  AND response_type = ?
				  AND id <> ?
				ORDER BY id DESC
				LIMIT 1
				""")) {
			statement.setLong(1, booklet.getId());
			statement.setLong(2, sharedContext.getId());
			statement.setString(3, QuestionResponseType.MULTIPLE_CHOICE.name());
			statement.setLong(4, excludedQuestionId);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					return null;
				}

				// The most recently stored independent MCQ carrying this context is the
				// origin of the currently pending continuation.
				return nextIndependentQuestionCode(result.getString("question_code"));
			}
		}
	}

	private Optional<SharedQuestionContext> findPendingMcqSharedContext(Connection connection, ExamBooklet booklet)
			throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT pending_mcq_shared_context_id
				FROM exam_booklets
				WHERE id = ?
				""")) {
			statement.setLong(1, booklet.getId());
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new IllegalStateException("Capture booklet could not be found: " + booklet.getId());
				}
				long contextId = result.getLong("pending_mcq_shared_context_id");
				if (result.wasNull()) {
					return Optional.empty();
				}

				// Reload through the booklet-scoped repository lookup so a malformed
				// cross-booklet relationship is rejected instead of silently reused.
				SharedQuestionContext context = sharedContextRepository.findById(connection, booklet, contextId)
						.orElseThrow(() -> new IllegalStateException(
								"Pending MCQ shared context does not belong to booklet " + booklet.getId()));
				return Optional.of(context);
			}
		}
	}

	private SharedQuestionContext findSharedContextForSourceQuestion(Connection connection,
			SourceQuestion sourceQuestion) throws SQLException {
		Long contextId = null;
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT id, booklet_id, shared_context_id
				FROM questions
				WHERE source_question_id = ?
				ORDER BY id
				""")) {
			statement.setLong(1, sourceQuestion.getId());
			try (ResultSet result = statement.executeQuery()) {
				while (result.next()) {
					long questionId = result.getLong("id");
					if (result.getLong("booklet_id") != sourceQuestion.getBooklet().getId()) {
						throw new IllegalStateException(
								"Question " + questionId + " is linked to a source question from another booklet");
					}
					long candidateId = result.getLong("shared_context_id");
					if (result.wasNull()) {
						continue;
					}
					if (contextId != null && contextId.longValue() != candidateId) {
						throw new IllegalStateException("Source question " + sourceQuestion.getSourceQuestionCode()
								+ " has inconsistent shared preamble links");
					}
					contextId = Long.valueOf(candidateId);
				}
			}
		}
		if (contextId == null) {
			return null;
		}
		long resolvedContextId = contextId.longValue();
		return sharedContextRepository.findById(connection, sourceQuestion.getBooklet(), resolvedContextId).orElseThrow(
				() -> new IllegalStateException("Linked shared context could not be found: " + resolvedContextId));
	}

	private String nextIndependentQuestionCode(String questionCode) {
		String code = questionCode == null ? "" : questionCode.trim();
		if (code.isEmpty()) {
			return null;
		}

		// Only a trailing decimal number is interpreted as sequence information.
		int digitStart = code.length();
		while (digitStart > 0 && Character.isDigit(code.charAt(digitStart - 1))) {
			digitStart--;
		}
		if (digitStart == code.length()) {
			return null;
		}

		// Be conservative about prefixes. Ordinary alphabetic prefixes such as Q are
		// supported; punctuation, embedded numbers and multipart suffixes are not
		// guessed.
		for (int index = 0; index < digitStart; index++) {
			if (!Character.isLetter(code.charAt(index))) {
				return null;
			}
		}

		String prefix = code.substring(0, digitStart);
		String digits = code.substring(digitStart);
		long number;
		try {
			number = Long.parseLong(digits);
		} catch (NumberFormatException e) {
			return null;
		}
		if (number == Long.MAX_VALUE) {
			return null;
		}

		String nextDigits = Long.toString(number + 1);
		if (nextDigits.length() < digits.length()) {

			// Preserve useful zero padding while the increment still fits within the
			// existing width: Q09 becomes Q10 and Q005 becomes Q006.
			nextDigits = "0".repeat(digits.length() - nextDigits.length()) + nextDigits;
		}
		return prefix + nextDigits;
	}

	private boolean pendingMcqAppliesToQuestion(Connection connection, ExamBooklet booklet,
			SharedQuestionContext sharedContext, String questionCode, long excludedQuestionId) throws SQLException {
		String expectedQuestionCode = expectedPendingMcqQuestionCode(connection, booklet, sharedContext,
				excludedQuestionId);
		return expectedQuestionCode != null && expectedQuestionCode.equals(questionCode.trim());
	}

	private Question persistQuestion(Connection connection, Request request, SourceQuestion sourceQuestion,
			SharedQuestionContext sharedContext) throws SQLException {
		if (request.operation() == Operation.NEW) {
			return questionWriter.insertQuestion(connection, request.booklet(), request.questionCode(), "",
					request.marks(), request.regions(), request.classification(), false, sourceQuestion, sharedContext,
					request.responseType());
		}
		Question existing = request.existingQuestion();

		// Imported questions keep previously captured regions; only empty captures
		// receive new ones.
		if (request.operation() == Operation.IMPORTED) {
			if (existing.getRegions().isEmpty()) {
				questionWriter.attachRegions(connection, existing.getId(), request.regions(), request.classification(),
						sourceQuestion, sharedContext);
			} else {
				questionWriter.updateCaptureRelationships(connection, existing.getId(), existing.getBooklet(),
						request.classification(), sourceQuestion, sharedContext);
			}
			questionWriter.updateResponseType(connection, existing.getId(), existing.getBooklet(),
					request.responseType());
			List<QuestionRegion> resultingRegions = existing.getRegions().isEmpty() ? request.regions()
					: existing.getRegions();
			return rebuildQuestion(existing, existing.getQuestionCode(), existing.getMarks(), resultingRegions,
					request.classification(), sourceQuestion, sharedContext, request.responseType());
		}
		questionWriter.updateQuestion(connection, existing.getId(), existing.getBooklet(), request.questionCode(),
				request.marks(), request.regions(), request.classification(), sourceQuestion, sharedContext);
		questionWriter.updateResponseType(connection, existing.getId(), existing.getBooklet(), request.responseType());
		return rebuildQuestion(existing, request.questionCode(), request.marks(), request.regions(),
				request.classification(), sourceQuestion, sharedContext, request.responseType());
	}

	private Question rebuildQuestion(Question existing, String questionCode, int marks, List<QuestionRegion> regions,
			CurriculumNode classification, SourceQuestion sourceQuestion, SharedQuestionContext sharedContext,
			QuestionResponseType responseType) {

		// Rebuild editable fields while retaining identity, legacy evidence and the
		// answer.
		Question updated = new Question(existing.getId(), existing.getBooklet(), questionCode,
				existing.getQuestionText(), marks, regions, classification, existing.isSharedContextCaptureRequired(),
				sourceQuestion, sharedContext, responseType);
		if (existing.hasAnswer()) {
			updated.setAnswer(existing.getAnswer());
		}
		return updated;
	}

	private SourceQuestion resolvePreambleStatus(Connection connection, SourceQuestion sourceQuestion,
			SharedQuestionContext sharedContext) throws SQLException {

		// Capture resolves unknown preamble status without replacing an explicit
		// decision.
		if (sourceQuestion == null || sourceQuestion.getSharedContextStatus() != SharedContextStatus.UNKNOWN) {
			return sourceQuestion;
		}
		SharedContextStatus status = sharedContext == null ? SharedContextStatus.NONE : SharedContextStatus.PRESENT;
		return sourceQuestionRepository.updatePreambleStatus(connection, sourceQuestion, status);
	}

	private SharedQuestionContext resolveSharedContext(Connection connection, Request request,
			SourceQuestion sourceQuestion) throws SQLException {
		Question existing = request.existingQuestion();
		if (request.operation() == Operation.IMPORTED && existing.hasSharedContext()) {
			return existing.getSharedContext();
		}
		if (request.selectedSharedContext() != null) {
			return request.selectedSharedContext();
		}
		if (request.operation() == Operation.EDIT && existing.hasSharedContext()
				&& editingSourceMatches(existing, request.questionCode())) {
			return existing.getSharedContext();
		}
		if (sourceQuestion != null) {
			SharedQuestionContext existingContext = findSharedContextForSourceQuestion(connection, sourceQuestion);
			if (existingContext != null) {
				return existingContext;
			}
		}

		// A context explicitly captured for this Question takes precedence over any
		// older independent-MCQ continuation stored for the booklet.
		PendingSharedContext pending = request.pendingSharedContext();
		if (pending != null) {
			return sharedContextRepository.save(connection, request.booklet(), pending.label(), pending.regions());
		}

		// Independent MCQ continuation is deliberately sequence-aware. A context
		// started on Q5 may be inherited by Q6, but never by Q7 merely because Q7
		// happens to be captured first.
		if (request.operation() == Operation.NEW && request.responseType() == QuestionResponseType.MULTIPLE_CHOICE
				&& sourceQuestion == null) {
			Optional<SharedQuestionContext> pendingContext = findPendingMcqSharedContext(connection, request.booklet());
			if (pendingContext.isPresent() && pendingMcqAppliesToQuestion(connection, request.booklet(),
					pendingContext.get(), request.questionCode(), 0)) {
				return pendingContext.get();
			}
		}

		return null;
	}

	private SourceQuestion resolveSourceQuestion(Connection connection, Request request) throws SQLException {
		String sourceCode = SourceQuestionCodeParser.derive(request.questionCode());
		if (sourceCode == null) {
			return null;
		}
		SourceQuestion existing = sourceQuestionRepository
				.findByBookletAndCode(connection, request.booklet(), sourceCode).orElse(null);
		if (existing != null) {
			return existing;
		}
		return sourceQuestionRepository.save(connection, request.booklet(), sourceCode);
	}

	private void setPendingMcqSharedContext(Connection connection, ExamBooklet booklet,
			SharedQuestionContext sharedContext) throws SQLException {
		if (sharedContext.getBooklet().getId() != booklet.getId()) {
			throw new IllegalArgumentException("Pending MCQ shared context must belong to the capture booklet");
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				UPDATE exam_booklets
				SET pending_mcq_shared_context_id = ?
				WHERE id = ?
				""")) {
			statement.setLong(1, sharedContext.getId());
			statement.setLong(2, booklet.getId());

			// Persist exactly one continuation owner. The foreign key ensures the
			// referenced context itself exists.
			if (statement.executeUpdate() != 1) {
				throw new IllegalStateException("Capture booklet could not be found: " + booklet.getId());
			}
		}
	}

	private void updatePendingMcqSharedContext(Connection connection, Request request, SourceQuestion sourceQuestion,
			SharedQuestionContext sharedContext, Question persistedQuestion) throws SQLException {

		// Imported capture and correction must never alter continuation intended for
		// new Question capture.
		if (request.operation() != Operation.NEW) {
			return;
		}

		Optional<SharedQuestionContext> pendingBeforeUpdate = findPendingMcqSharedContext(connection,
				request.booklet());

		// Exclude the Question just inserted when locating the origin of the previous
		// continuation. If Q6 inherited Q5's context, Q6 must not become the origin
		// until we have decided whether the Q5 -> Q6 continuation was consumed.
		boolean currentQuestionIsExpectedSuccessor = pendingBeforeUpdate.isPresent()
				&& pendingMcqAppliesToQuestion(connection, request.booklet(), pendingBeforeUpdate.get(),
						request.questionCode(), persistedQuestion.getId());

		if (request.continueSharedContextToNextMcq()) {
			if (sharedContext == null) {

				// A continuation flag without an actual context is invalid capture state.
				// Throwing here rolls the complete Question transaction back.
				throw new IllegalArgumentException(
						"MCQ shared-context continuation requires a shared context on the current Question");
			}

			// The current MCQ deliberately establishes or extends a continuation. The
			// newly persisted Question now becomes its sequence origin.
			setPendingMcqSharedContext(connection, request.booklet(), sharedContext);
			return;
		}

		if (currentQuestionIsExpectedSuccessor) {

			// Reaching the intended next Question consumes the one-shot continuation
			// unless the checkbox explicitly renewed it above. This also clears a stale
			// MCQ continuation if the expected Question is captured with a different
			// response/context.
			clearPendingMcqSharedContext(connection, request.booklet());
		}

		// An out-of-sequence Question does nothing to the pending continuation. For
		// example, capturing Q7 before Q6 leaves Q5's context waiting for Q6.
	}

	/**
	 * Kind of question capture to persist.
	 */
	public enum Operation {
		/** Create a newly captured question. */
		NEW,
		/** Capture source regions or context for an imported question. */
		IMPORTED,
		/** Correct an existing question while retaining its persistent identity. */
		EDIT
	}

	/**
	 * Shared preamble captured in memory and awaiting persistence.
	 *
	 * @param label   non-blank label for the new reusable preamble
	 * @param regions non-empty shared-context regions in source order
	 */
	public record PendingSharedContext(String label, List<SharedQuestionContextRegion> regions) {

		/**
		 * Validates a pending preamble and copies its ordered regions.
		 *
		 * @param label   non-blank label for the new reusable preamble
		 * @param regions non-empty shared-context regions in source order
		 */
		public PendingSharedContext {
			if (label == null || label.isBlank()) {
				throw new IllegalArgumentException("label must not be blank");
			}
			if (regions == null) {
				throw new NullPointerException("regions");
			}
			if (regions.isEmpty()) {
				throw new IllegalArgumentException("regions must not be empty");
			}
			regions = List.copyOf(regions);
		}
	}

	/**
	 * Validated inputs for an atomic question-capture operation.
	 *
	 * @param operation                      new, imported or edit capture
	 * @param booklet                        source examination booklet
	 * @param existingQuestion               stored question for imported or edit
	 *                                       capture; null for new capture
	 * @param questionCode                   non-blank examination question or part
	 *                                       code
	 * @param marks                          positive mark value
	 * @param regions                        ordered ordinary question regions
	 * @param classification                 original syllabus Subtopic or
	 *                                       Descriptor
	 * @param responseType                   authoritative response type, including
	 *                                       UNKNOWN when unresolved
	 * @param selectedSharedContext          existing reusable context, or null
	 * @param pendingSharedContext           newly captured context to persist, or
	 *                                       null
	 * @param continueSharedContextToNextMcq whether this independent MCQ's context
	 *                                       should also be applied to the next
	 *                                       independent MCQ
	 */
	public record Request(Operation operation, ExamBooklet booklet, Question existingQuestion, String questionCode,
			int marks, List<QuestionRegion> regions, CurriculumNode classification, QuestionResponseType responseType,
			SharedQuestionContext selectedSharedContext, PendingSharedContext pendingSharedContext,
			boolean continueSharedContextToNextMcq) {

		/**
		 * Preserves the existing response-type-aware constructor for callers that do
		 * not yet participate in independent-MCQ context continuation.
		 *
		 * @param operation             new, imported or edit capture
		 * @param booklet               source examination booklet
		 * @param existingQuestion      stored question for imported or edit capture;
		 *                              null for new capture
		 * @param questionCode          non-blank examination question or part code
		 * @param marks                 positive mark value
		 * @param regions               ordered ordinary question regions
		 * @param classification        original syllabus Subtopic or Descriptor
		 * @param responseType          authoritative response type
		 * @param selectedSharedContext existing reusable context, or null
		 * @param pendingSharedContext  newly captured context to persist, or null
		 */
		public Request(Operation operation, ExamBooklet booklet, Question existingQuestion, String questionCode,
				int marks, List<QuestionRegion> regions, CurriculumNode classification,
				QuestionResponseType responseType, SharedQuestionContext selectedSharedContext,
				PendingSharedContext pendingSharedContext) {
			this(operation, booklet, existingQuestion, questionCode, marks, regions, classification, responseType,
					selectedSharedContext, pendingSharedContext, false);
		}

		/**
		 * Compatibility constructor used by callers that predate explicit response-type
		 * input.
		 * <p>
		 * Existing Questions retain their stored response type. A new Question created
		 * through this compatibility form remains UNKNOWN.
		 *
		 * @param operation             new, imported or edit capture
		 * @param booklet               source examination booklet
		 * @param existingQuestion      stored question for imported or edit capture;
		 *                              null for new capture
		 * @param questionCode          non-blank examination question or part code
		 * @param marks                 positive mark value
		 * @param regions               ordered ordinary question regions
		 * @param classification        original syllabus Subtopic or Descriptor
		 * @param selectedSharedContext existing reusable context, or null
		 * @param pendingSharedContext  newly captured context to persist, or null
		 */
		public Request(Operation operation, ExamBooklet booklet, Question existingQuestion, String questionCode,
				int marks, List<QuestionRegion> regions, CurriculumNode classification,
				SharedQuestionContext selectedSharedContext, PendingSharedContext pendingSharedContext) {
			this(operation, booklet, existingQuestion, questionCode, marks, regions, classification,
					existingQuestion == null ? QuestionResponseType.UNKNOWN : existingQuestion.getResponseType(),
					selectedSharedContext, pendingSharedContext, false);
		}

		/**
		 * Validates capture state and retains an immutable copy of the ordinary
		 * regions.
		 */
		public Request {
			if (operation == null) {
				throw new NullPointerException("operation");
			}
			if (booklet == null) {
				throw new NullPointerException("booklet");
			}
			if (questionCode == null || questionCode.isBlank()) {
				throw new IllegalArgumentException("questionCode must not be blank");
			}
			if (marks < 1) {
				throw new IllegalArgumentException("marks must be positive");
			}
			if (regions == null) {
				throw new NullPointerException("regions");
			}
			if (classification == null) {
				throw new NullPointerException("classification");
			}
			if (responseType == null) {
				throw new NullPointerException("responseType");
			}
			if (responseType == QuestionResponseType.MULTIPLE_CHOICE && marks != 1) {

				// Reject inconsistent capture metadata before any persistence transaction can
				// begin, including edit and imported-question workflows.
				throw new IllegalArgumentException("Multiple-choice questions must be worth exactly 1 mark");
			}
			if (continueSharedContextToNextMcq && operation != Operation.NEW) {

				// Continuation is capture workflow state for newly encountered independent
				// MCQs, never an edit/import side effect.
				throw new IllegalArgumentException(
						"MCQ shared-context continuation is only valid for new Question capture");
			}
			if (continueSharedContextToNextMcq && responseType != QuestionResponseType.MULTIPLE_CHOICE) {
				throw new IllegalArgumentException(
						"Shared-context continuation to the next MCQ requires a Multiple Choice Question");
			}
			if (continueSharedContextToNextMcq && SourceQuestionCodeParser.derive(questionCode) != null) {

				// Multipart Questions already obtain context through SourceQuestion identity.
				// Never create a second continuation mechanism for those Questions.
				throw new IllegalArgumentException(
						"MCQ shared-context continuation is only valid for independent Question codes");
			}
			regions = List.copyOf(regions);
			if (operation == Operation.NEW && existingQuestion != null) {
				throw new IllegalArgumentException("New capture must not have an existing question");
			}
			if (operation != Operation.NEW && existingQuestion == null) {
				throw new IllegalArgumentException("Imported and edit capture require an existing question");
			}
			if (existingQuestion != null && existingQuestion.getBooklet().getId() != booklet.getId()) {
				throw new IllegalArgumentException("Existing question must belong to the capture booklet");
			}
			if (selectedSharedContext != null && selectedSharedContext.getBooklet().getId() != booklet.getId()) {
				throw new IllegalArgumentException("Shared context must belong to the capture booklet");
			}
			boolean regionsRequired = operation == Operation.NEW || operation == Operation.EDIT
					|| (operation == Operation.IMPORTED && existingQuestion.getRegions().isEmpty());
			if (regionsRequired && regions.isEmpty()) {
				throw new IllegalArgumentException("Question regions must not be empty");
			}
		}
	}
}
