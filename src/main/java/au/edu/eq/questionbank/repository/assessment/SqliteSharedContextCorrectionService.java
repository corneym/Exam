package au.edu.eq.questionbank.repository.assessment;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.SharedContextContentPart;
import au.edu.eq.questionbank.model.SharedContextStatus;
import au.edu.eq.questionbank.model.SharedQuestionContext;
import au.edu.eq.questionbank.model.SharedQuestionContextRegion;
import au.edu.eq.questionbank.model.SourceQuestion;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

/**
 * Corrects the shared-context decision for an existing multipart SourceQuestion
 * without replacing its individual Question parts.
 *
 * All changes to context creation, sibling links and source-level status are
 * committed or rolled back together.
 */
public final class SqliteSharedContextCorrectionService {

	private final SqliteDatabase database;
	private final SqliteExamWriter examWriter;
	private final SqliteQuestionRepository questionRepository;
	private final SqliteQuestionWriter questionWriter;
	private final SqliteSharedQuestionContextRepository sharedContextRepository;
	private final SqliteSourceQuestionRepository sourceQuestionRepository;

	/**
	 * Creates the correction service.
	 *
	 * @param database initialised Question database
	 */
	public SqliteSharedContextCorrectionService(SqliteDatabase database) {
		this.database = Objects.requireNonNull(database, "database");
		this.examWriter = new SqliteExamWriter(database);
		this.questionRepository = new SqliteQuestionRepository(database);
		this.questionWriter = new SqliteQuestionWriter(database);
		this.sharedContextRepository = new SqliteSharedQuestionContextRepository(database);
		this.sourceQuestionRepository = new SqliteSourceQuestionRepository(database);
	}

	/**
	 * Creates a PDF-only Shared Context and links every Question in the selected
	 * multipart SourceQuestion. The context and all links commit atomically.
	 *
	 * @param questionId a persisted multipart Question identifier
	 * @param label      non-blank context label
	 * @param regions    non-empty ordered PDF regions
	 * @return committed context and affected Question identifiers
	 */
	public CorrectionResult captureNew(long questionId, String label, List<SharedQuestionContextRegion> regions) {
		return captureNewContentParts(questionId, label, regions);
	}

	/**
	 * Creates an ordered PDF/image Shared Context and links all Questions in the
	 * selected multipart SourceQuestion in one transaction.
	 *
	 * @param questionId   a persisted multipart Question identifier
	 * @param label        non-blank context label
	 * @param contentParts non-empty ordered PDF regions and/or PNG image parts
	 * @return committed context and affected Question identifiers
	 */
	public CorrectionResult captureNewContentParts(long questionId, String label,
			List<? extends SharedContextContentPart> contentParts) {
		return correct(questionId, null, label, contentParts);
	}

	/**
	 * Attaches an existing booklet-owned context to every member of the selected
	 * Question's SourceQuestion.
	 *
	 * @param questionId any persisted multipart member
	 * @param contextId  persistent context identifier
	 * @return committed context and affected Question identifiers
	 */
	public CorrectionResult linkExisting(long questionId, long contextId) {
		if (contextId < 1) {
			throw new IllegalArgumentException("contextId must be positive");
		}
		return correct(questionId, contextId, null, null);
	}

	/**
	 * Loads the source-level decision, affected Questions and available contexts
	 * without modifying persisted data.
	 *
	 * @param questionId selected persistent Question identifier
	 * @return correction review for the whole source group
	 */
	public Review review(long questionId) {
		Question selected = loadQuestion(questionId);
		if (!selected.hasSourceQuestion()) {
			throw new IllegalArgumentException("Question does not belong to a multipart SourceQuestion");
		}
		List<Long> partIds;
		try (Connection connection = database.openConnection()) {
			partIds = findPartIds(connection, selected.getSourceQuestion().getId());
		} catch (SQLException exception) {
			throw new IllegalStateException("Could not inspect multipart Questions", exception);
		}
		List<Question> parts = new ArrayList<>();
		for (long partId : partIds) {
			Question part = loadQuestion(partId);
			if (part.getBooklet().getId() != selected.getBooklet().getId()) {
				throw new IllegalStateException("SourceQuestion contains Questions from another booklet");
			}
			parts.add(part);
		}
		return new Review(selected.getSourceQuestion(), parts,
				sharedContextRepository.findByBooklet(selected.getBooklet()));
	}

	private CorrectionResult correct(long questionId, Long existingContextId, String label,
			List<? extends SharedContextContentPart> contentParts) {
		Question selected = loadQuestion(questionId);
		if (!selected.hasSourceQuestion()) {
			throw new IllegalArgumentException("Question does not belong to a multipart SourceQuestion");
		}
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {

				// Structural corrections are prohibited for a completed Exam.
				examWriter.requireExamActive(connection, selected.getBooklet().getExam().getId());

				// Re-read source identity under the transaction, rather than trusting
				// a Question object cached while Search was open.
				SourceQuestion source = loadSource(connection, selected);
				if (source.getSharedContextStatus() == SharedContextStatus.PRESENT) {
					throw new IllegalStateException("Source Question already has Shared Context. "
							+ "Use Recapture Shared Context to replace it.");
				}
				List<Long> affectedIds = findPartIds(connection, source.getId());
				if (!affectedIds.contains(questionId)) {
					throw new IllegalStateException("Selected Question is no longer linked " + "to its SourceQuestion");
				}
				SharedQuestionContext context;
				if (existingContextId != null) {

					// Reused contexts must belong to the selected Question's booklet.
					context = sharedContextRepository
							.findById(connection, selected.getBooklet(), existingContextId.longValue())
							.orElseThrow(() -> new IllegalArgumentException(
									"Shared Context does not exist in the selected booklet"));
				} else {

					// The v24 mixed-content writer participates in this same
					// transaction; do not call its separate-connection public API.
					context = sharedContextRepository.saveContentParts(connection, selected.getBooklet(), label,
							contentParts);
				}

				// Every sibling link, the source status and mixed-content rows
				// either commit together or are all rolled back.
				questionWriter.applySharedContextToSourceQuestion(connection, source, context);
				sourceQuestionRepository.updateSharedContextStatus(connection, source, SharedContextStatus.PRESENT);
				connection.commit();
				return new CorrectionResult(context, affectedIds);
			} catch (SQLException | RuntimeException exception) {
				try {
					connection.rollback();
				} catch (SQLException rollbackFailure) {
					exception.addSuppressed(rollbackFailure);
				}
				throw exception;
			}
		} catch (SQLException exception) {
			throw new IllegalStateException("Could not correct multipart Shared Context atomically", exception);
		}
	}

	private List<Long> findPartIds(Connection connection, long sourceQuestionId) throws SQLException {
		List<Long> ids = new ArrayList<>();
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT id
				FROM questions
				WHERE source_question_id = ?
				ORDER BY id
				""")) {
			statement.setLong(1, sourceQuestionId);
			try (ResultSet result = statement.executeQuery()) {
				while (result.next()) {
					ids.add(result.getLong("id"));
				}
			}
		}
		return List.copyOf(ids);
	}

	private Question loadQuestion(long questionId) {
		if (questionId < 1) {
			throw new IllegalArgumentException("questionId must be positive");
		}
		return questionRepository.findById(questionId)
				.orElseThrow(() -> new IllegalArgumentException("Question does not exist: " + questionId));
	}

	private SourceQuestion loadSource(Connection connection, Question selected) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT
				    sq.id,
				    sq.source_question_code,
				    sq.shared_context_status
				FROM questions q
				JOIN source_questions sq
				    ON sq.id = q.source_question_id
				   AND sq.booklet_id = q.booklet_id
				WHERE q.id = ?
				  AND q.booklet_id = ?
				  AND sq.id = ?
				""")) {
			statement.setLong(1, selected.getId());
			statement.setLong(2, selected.getBooklet().getId());
			statement.setLong(3, selected.getSourceQuestion().getId());
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new IllegalStateException("The selected Question's source relationship "
							+ "has changed. Refresh Search and try again.");
				}
				return new SourceQuestion(result.getLong("id"), selected.getBooklet(),
						result.getString("source_question_code"),
						SharedContextStatus.valueOf(result.getString("shared_context_status")));
			}
		}
	}

	/**
	 * Result of one committed source-wide correction.
	 *
	 * @param context             authoritative linked Shared Context
	 * @param affectedQuestionIds IDs of the linked Question parts
	 */
	public record CorrectionResult(SharedQuestionContext context, List<Long> affectedQuestionIds) {

		/**
		 * Validates the result and defensively copies affected Question IDs.
		 *
		 * @throws NullPointerException if a component is null
		 */
		public CorrectionResult {
			Objects.requireNonNull(context, "context");
			affectedQuestionIds = List.copyOf(affectedQuestionIds);
		}
	}

	/**
	 * Read-only information required before confirming a correction.
	 *
	 * @param sourceQuestion    persisted multipart source identity
	 * @param parts             affected persisted Question records
	 * @param availableContexts reusable contexts from the same booklet
	 */
	public record Review(SourceQuestion sourceQuestion, List<Question> parts,
			List<SharedQuestionContext> availableContexts) {

		/**
		 * Validates the source and defensively copies the supplied lists.
		 *
		 * @throws NullPointerException if a component is null
		 */
		public Review {
			Objects.requireNonNull(sourceQuestion, "sourceQuestion");
			parts = List.copyOf(parts);
			availableContexts = List.copyOf(availableContexts);
		}
	}
}
