package au.edu.eq.questionbank.repository.assessment;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.PreambleStatus;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.QuestionResponseType;
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

	public SqliteQuestionCaptureService(SqliteDatabase database) {
		if (database == null) {
			throw new NullPointerException("database");
		}
		this.database = database;
		this.questionWriter = new SqliteQuestionWriter(database);
		this.sourceQuestionRepository = new SqliteSourceQuestionRepository(database);
		this.sharedContextRepository = new SqliteSharedQuestionContextRepository(database);
	}

	public Question save(Request request) {
		if (request == null) {
			throw new NullPointerException("request");
		}
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				SourceQuestion sourceQuestion = resolveSourceQuestion(connection, request);
				SharedQuestionContext sharedContext = resolveSharedContext(connection, request, sourceQuestion);
				if (sourceQuestion != null && sharedContext != null) {
					questionWriter.applySharedContextToSourceQuestion(connection, sourceQuestion, sharedContext);
				}
				sourceQuestion = resolvePreambleStatus(connection, sourceQuestion, sharedContext);
				Question question = persistQuestion(connection, request, sourceQuestion, sharedContext);
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

	private Question persistQuestion(Connection connection, Request request, SourceQuestion sourceQuestion,
			SharedQuestionContext sharedContext) throws SQLException {
		if (request.operation() == Operation.NEW) {
			return questionWriter.insertQuestion(connection, request.booklet(), request.questionCode(), "",
					request.marks(), request.regions(), request.classification(), false, sourceQuestion, sharedContext,
					request.responseType());
		}
		Question existing = request.existingQuestion();
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
		Question updated = new Question(existing.getId(), existing.getBooklet(), questionCode,
				existing.getQuestionText(), marks, regions, classification, existing.isPreambleCaptureRequired(),
				sourceQuestion, sharedContext, responseType);
		if (existing.hasAnswer()) {
			updated.setAnswer(existing.getAnswer());
		}
		return updated;
	}

	private SourceQuestion resolvePreambleStatus(Connection connection, SourceQuestion sourceQuestion,
			SharedQuestionContext sharedContext) throws SQLException {
		if (sourceQuestion == null || sourceQuestion.getPreambleStatus() != PreambleStatus.UNKNOWN) {
			return sourceQuestion;
		}
		PreambleStatus status = sharedContext == null ? PreambleStatus.NONE : PreambleStatus.PRESENT;
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
		PendingSharedContext pending = request.pendingSharedContext();
		if (pending == null) {
			return null;
		}
		return sharedContextRepository.save(connection, request.booklet(), pending.label(), pending.regions());
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

	public enum Operation {
		NEW, IMPORTED, EDIT
	}

	public record PendingSharedContext(String label, List<SharedQuestionContextRegion> regions) {

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

	public record Request(Operation operation, ExamBooklet booklet, Question existingQuestion, String questionCode,
			int marks, List<QuestionRegion> regions, CurriculumNode classification, QuestionResponseType responseType,
			SharedQuestionContext selectedSharedContext, PendingSharedContext pendingSharedContext) {

		/**
		 * Compatibility constructor used by existing capture callers while
		 * response-type-aware UI is introduced.
		 * <p>
		 * Existing Questions retain their stored response type. A new Question created
		 * through this compatibility form remains UNKNOWN.
		 */
		public Request(Operation operation, ExamBooklet booklet, Question existingQuestion, String questionCode,
				int marks, List<QuestionRegion> regions, CurriculumNode classification,
				SharedQuestionContext selectedSharedContext, PendingSharedContext pendingSharedContext) {
			this(operation, booklet, existingQuestion, questionCode, marks, regions, classification,
					existingQuestion == null ? QuestionResponseType.UNKNOWN : existingQuestion.getResponseType(),
					selectedSharedContext, pendingSharedContext);
		}

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