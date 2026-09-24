package au.edu.eq.questionbank.repository.assessment;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
 * Converts one legacy single Question into explicitly persisted multipart
 * Questions.
 * <p>
 * The split is atomic. One resulting part reuses the original Question row and
 * the remaining parts are inserted as new Questions.
 */
public final class LegacyQuestionSplitService {

	private final SqliteDatabase database;
	private final SqliteQuestionWriter questionWriter;
	private final SqliteQuestionRepository questionRepository;
	private final SqliteSourceQuestionRepository sourceQuestionRepository;
	private final SqliteSharedQuestionContextRepository sharedContextRepository;

	/**
	 * Creates the split service.
	 *
	 * @param database question-bank database
	 */
	public LegacyQuestionSplitService(SqliteDatabase database) {
		if (database == null) {
			throw new NullPointerException("database");
		}
		this.database = database;
		this.questionWriter = new SqliteQuestionWriter(database);
		this.questionRepository = new SqliteQuestionRepository(database);
		this.sourceQuestionRepository = new SqliteSourceQuestionRepository(database);
		this.sharedContextRepository = new SqliteSharedQuestionContextRepository(database);
	}

	/**
	 * Atomically converts one legacy Question into multipart Questions.
	 *
	 * @param request explicit split definition
	 * @return reloaded resulting Questions and their common source relationships
	 */
	public SplitResult split(SplitRequest request) {
		validateRequest(request);
		List<Long> resultingQuestionIds = new ArrayList<>();
		SourceQuestion persistedSourceQuestion;
		SharedQuestionContext persistedSharedContext;
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				StoredOriginalState originalState = readStoredOriginalState(connection,
						request.originalQuestion().getId());
				validatePersistedOriginal(request, originalState);
				validateDestinationParts(connection, request, originalState);

				// Resolve all multipart and shared-context relationships inside the
				// same transaction as the Question changes.
				SplitRelationships relationships = resolveSplitRelationships(connection, request);
				persistedSourceQuestion = relationships.sourceQuestion();
				persistedSharedContext = relationships.sharedContext();
				for (int index = 0; index < request.parts().size(); index++) {
					SplitPart part = request.parts().get(index);
					if (index == request.retainedPartIndex()) {

						// Reuse the original Question identity for the nominated part.
						// Any existing Answer remains attached to this Question row.
						questionWriter.updateQuestion(connection, request.originalQuestion().getId(),
								request.originalQuestion().getBooklet(), part.questionCode(), part.marks(),
								part.regions(), part.classification(), persistedSourceQuestion, persistedSharedContext);
						questionWriter.updateResponseType(connection, request.originalQuestion().getId(),
								request.originalQuestion().getBooklet(), part.responseType());

						// The explicit SourceQuestion relationship now represents the
						// multipart shared context semantics, so the old legacy hint is cleared.
						clearLegacySharedContextRequirement(connection, request.originalQuestion().getId(),
								request.originalQuestion().getBooklet());
						resultingQuestionIds.add(request.originalQuestion().getId());
					} else {

						// Every additional part is inserted only after the complete split
						// request and its source relationships have been validated.
						Question inserted = questionWriter.insertQuestion(connection,
								request.originalQuestion().getBooklet(), part.questionCode(), "", part.marks(),
								part.regions(), part.classification(), false, persistedSourceQuestion,
								persistedSharedContext, part.responseType());
						resultingQuestionIds.add(inserted.getId());
					}
				}
				connection.commit();
			} catch (SQLException | RuntimeException failure) {
				try {
					connection.rollback();
				} catch (SQLException rollbackFailure) {
					failure.addSuppressed(rollbackFailure);
				}
				throw failure;
			}
		} catch (SQLException failure) {
			throw new IllegalStateException("Could not split legacy Question atomically", failure);
		}
		List<Question> resultingQuestions = new ArrayList<>();

		// Reload after commit so callers receive exactly the relationships persisted
		// in SQLite rather than objects assembled during the transaction.
		for (Long questionId : resultingQuestionIds) {
			resultingQuestions.add(questionRepository.findById(questionId.longValue())
					.orElseThrow(() -> new IllegalStateException("Question disappeared after split: " + questionId)));
		}
		return new SplitResult(resultingQuestions, persistedSourceQuestion, persistedSharedContext);
	}

	private void clearLegacySharedContextRequirement(Connection connection, long questionId, ExamBooklet booklet)
			throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				UPDATE questions
				SET shared_context_capture_required = 0
				WHERE id = ?
				  AND booklet_id = ?
				""")) {
			statement.setLong(1, questionId);
			statement.setLong(2, booklet.getId());
			if (statement.executeUpdate() != 1) {
				throw new SQLException("Question shared context update affected an unexpected number of rows");
			}
		}
	}

	private Long nullableLong(ResultSet result, String column) throws SQLException {
		long value = result.getLong(column);
		return result.wasNull() ? null : Long.valueOf(value);
	}

	private StoredOriginalState readStoredOriginalState(Connection connection, long questionId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT
				    q.booklet_id,
				    q.question_code,
				    q.source_question_id,
				    q.shared_context_id,
				    q.response_type,
				    cn.syllabus_version_id,
				    EXISTS (
				        SELECT 1
				        FROM answers a
				        WHERE a.question_id = q.id
				    ) AS has_answer
				FROM questions q
				JOIN curriculum_nodes cn
				    ON cn.id = q.classification_node_id
				WHERE q.id = ?
				""")) {
			statement.setLong(1, questionId);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new IllegalArgumentException("Question does not exist: " + questionId);
				}
				Long sourceQuestionId = nullableLong(result, "source_question_id");
				Long sharedContextId = nullableLong(result, "shared_context_id");
				return new StoredOriginalState(result.getLong("booklet_id"), result.getString("question_code"),
						sourceQuestionId, sharedContextId,
						QuestionResponseType.valueOf(result.getString("response_type")),
						result.getLong("syllabus_version_id"), result.getInt("has_answer") != 0);
			}
		}
	}

	private SplitRelationships resolveSplitRelationships(Connection connection, SplitRequest request)
			throws SQLException {
		var existingSourceQuestion = sourceQuestionRepository.findByBookletAndCode(connection,
				request.originalQuestion().getBooklet(), request.sourceQuestionCode());
		if (request.newSharedContext() != null) {

			// A newly captured shared context establishes a new multipart source identity.
			// It must not overwrite or reinterpret an existing source group.
			if (existingSourceQuestion.isPresent()) {
				throw new IllegalArgumentException(
						"Cannot create a new shared context for an existing source question: "
								+ request.sourceQuestionCode());
			}
			SourceQuestion createdSourceQuestion = sourceQuestionRepository.save(connection,
					request.originalQuestion().getBooklet(), request.sourceQuestionCode());
			String contextLabel = "Question " + request.sourceQuestionCode() + " shared context";
			SharedQuestionContext sharedContext = sharedContextRepository.save(connection,
					request.originalQuestion().getBooklet(), contextLabel, request.newSharedContext().regions());
			SourceQuestion resolvedSourceQuestion = sourceQuestionRepository.updateSharedContextStatus(connection,
					createdSourceQuestion, SharedContextStatus.PRESENT);
			return new SplitRelationships(resolvedSourceQuestion, sharedContext);
		}
		if (request.existingSharedContext() != null) {
			if (existingSourceQuestion.isEmpty()) {
				throw new IllegalArgumentException(
						"Cannot reuse shared context because source question does not exist: "
								+ request.sourceQuestionCode());
			}
			SourceQuestion sourceQuestion = existingSourceQuestion.get();

			// Reload the requested context through the same transaction and booklet.
			// This rejects stale objects and contexts belonging to another booklet.
			SharedQuestionContext persistedContext = sharedContextRepository
					.findById(connection, request.originalQuestion().getBooklet(),
							request.existingSharedContext().getId())
					.orElseThrow(() -> new IllegalArgumentException(
							"Shared context does not belong to the split Question booklet"));
			validateCompatibleExistingSharedContextGroup(connection, sourceQuestion, persistedContext);
			return new SplitRelationships(sourceQuestion, persistedContext);
		}
		if (existingSourceQuestion.isPresent()) {

			// A no-shared context split may join only an already-confirmed no-shared
			// context
			// source group.
			SourceQuestion sourceQuestion = existingSourceQuestion.get();
			validateCompatibleNoSharedContextSourceGroup(connection, sourceQuestion);
			return new SplitRelationships(sourceQuestion, null);
		}

		// No existing group and no shared shared context means a new explicitly
		// resolved
		// no-shared context SourceQuestion.
		SourceQuestion createdSourceQuestion = sourceQuestionRepository.save(connection,
				request.originalQuestion().getBooklet(), request.sourceQuestionCode());
		SourceQuestion resolvedSourceQuestion = sourceQuestionRepository.updateSharedContextStatus(connection,
				createdSourceQuestion, SharedContextStatus.NONE);
		return new SplitRelationships(resolvedSourceQuestion, null);
	}

	private void validateCompatibleExistingSharedContextGroup(Connection connection, SourceQuestion sourceQuestion,
			SharedQuestionContext sharedContext) throws SQLException {
		if (sourceQuestion.getSharedContextStatus() != SharedContextStatus.PRESENT) {

			// Reusing a persisted shared context is valid only when the SourceQuestion
			// already declares that shared introductory material is present.
			throw new IllegalArgumentException("Existing source question " + sourceQuestion.getSourceQuestionCode()
					+ " does not have PRESENT shared context status");
		}
		if (sharedContext.getBooklet().getId() != sourceQuestion.getBooklet().getId()) {
			throw new IllegalArgumentException("Shared context belongs to another booklet");
		}
		boolean memberFound = false;
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT
				    booklet_id,
				    shared_context_id
				FROM questions
				WHERE source_question_id = ?
				""")) {
			statement.setLong(1, sourceQuestion.getId());
			try (ResultSet result = statement.executeQuery()) {
				while (result.next()) {
					memberFound = true;
					if (result.getLong("booklet_id") != sourceQuestion.getBooklet().getId()) {
						throw new IllegalStateException(
								"Existing source question contains a Question from another booklet");
					}
					long memberContextId = result.getLong("shared_context_id");
					if (result.wasNull() || memberContextId != sharedContext.getId()) {

						// Every established member must already agree on exactly the
						// context being reused by the split.
						throw new IllegalArgumentException(
								"Existing source question has incompatible shared-context links");
					}
				}
			}
		}
		if (!memberFound) {
			throw new IllegalArgumentException(
					"Existing source question has no Questions from which shared-context ownership can be confirmed");
		}
	}

	private void validateCompatibleNoSharedContextSourceGroup(Connection connection, SourceQuestion sourceQuestion)
			throws SQLException {
		if (sourceQuestion.getSharedContextStatus() != SharedContextStatus.NONE) {

			// UNKNOWN and PRESENT both require an explicit shared context decision rather
			// than being silently converted by a no-shared context split.
			throw new IllegalArgumentException("Existing source question " + sourceQuestion.getSourceQuestionCode()
					+ " does not have compatible no-shared context status");
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT
				    booklet_id,
				    shared_context_id
				FROM questions
				WHERE source_question_id = ?
				""")) {
			statement.setLong(1, sourceQuestion.getId());
			try (ResultSet result = statement.executeQuery()) {
				while (result.next()) {

					// A SourceQuestion must never span booklets. Reject inconsistent
					// persisted data rather than attaching more Questions to it.
					if (result.getLong("booklet_id") != sourceQuestion.getBooklet().getId()) {
						throw new IllegalStateException(
								"Existing source question contains a Question from another booklet");
					}
					result.getLong("shared_context_id");
					if (!result.wasNull()) {

						// A no-s split cannot join a source group whose existing
						// members already use shared material.
						throw new IllegalArgumentException("Existing source question "
								+ sourceQuestion.getSourceQuestionCode() + " already uses shared context");
					}
				}
			}
		}
	}

	private void validateDestinationParts(Connection connection, SplitRequest request,
			StoredOriginalState originalState) throws SQLException {
		Set<String> requestedCodes = new HashSet<>();
		for (SplitPart part : request.parts()) {

			// The caller explicitly supplies both the source identity and destination
			// part codes. This check validates that structure rather than deriving it.
			String derivedSourceCode = SourceQuestionCodeParser.derive(part.questionCode());
			if (!request.sourceQuestionCode().equals(derivedSourceCode)) {
				throw new IllegalArgumentException("Part code does not belong to source question "
						+ request.sourceQuestionCode() + ": " + part.questionCode());
			}
			if (!requestedCodes.add(part.questionCode())) {
				throw new IllegalArgumentException("Duplicate destination Question code: " + part.questionCode());
			}
			if (part.classification().getSyllabusVersion().getId() != originalState.syllabusVersionId()) {
				throw new IllegalArgumentException("Split classifications must remain within the original syllabus");
			}
			for (QuestionRegion region : part.regions()) {
				if (region.booklet().getId() != request.originalQuestion().getBooklet().getId()) {
					throw new IllegalArgumentException("Split regions must belong to the original Question booklet");
				}
			}
			verifyDestinationQuestionCodeAvailable(connection, request.originalQuestion(), part.questionCode());
		}

		// An existing Answer remains attached to the retained original row. Until
		// explicit Answer-discard handling is added, changing its response type
		// would make that ownership unsafe.
		if (originalState.hasAnswer()) {
			SplitPart retainedPart = request.parts().get(request.retainedPartIndex());
			if (retainedPart.responseType() != originalState.responseType()) {
				throw new IllegalArgumentException(
						"An existing Answer can only be retained when the retained part keeps the original response type.");
			}
		}
	}

	private void validatePersistedOriginal(SplitRequest request, StoredOriginalState originalState) {
		Question originalQuestion = request.originalQuestion();
		if (originalState.bookletId() != originalQuestion.getBooklet().getId()) {
			throw new IllegalArgumentException("Stored Question belongs to another booklet");
		}
		if (!request.sourceQuestionCode().equals(originalState.questionCode())) {
			throw new IllegalArgumentException("Source question code must match the legacy Question being split");
		}

		// This workflow converts a genuinely single legacy Question. An already
		// linked multipart/shared-context Question must be corrected through its
		// existing relationships instead of being split again.
		if (originalState.sourceQuestionId() != null) {
			throw new IllegalArgumentException("Question already belongs to a SourceQuestion");
		}
		if (originalState.sharedContextId() != null) {
			throw new IllegalArgumentException("Question already has shared context");
		}
	}

	private void validateRequest(SplitRequest request) {
		if (request == null) {
			throw new NullPointerException("request");
		}
		if (request.parts().size() < 2) {
			throw new IllegalArgumentException("A split requires at least two resulting parts");
		}
		if (request.retainedPartIndex() < 0 || request.retainedPartIndex() >= request.parts().size()) {
			throw new IllegalArgumentException("retainedPartIndex is outside the resulting parts");
		}

		// A split has exactly one shared context strategy: none, create new, or reuse
		// the established context of an existing source group.
		if (request.newSharedContext() != null && request.existingSharedContext() != null) {
			throw new IllegalArgumentException("A split cannot both create and reuse shared context");
		}
	}

	private void verifyDestinationQuestionCodeAvailable(Connection connection, Question originalQuestion,
			String questionCode) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT id
				FROM questions
				WHERE booklet_id = ?
				  AND question_code = ?
				  AND id <> ?
				""")) {
			statement.setLong(1, originalQuestion.getBooklet().getId());
			statement.setString(2, questionCode);
			statement.setLong(3, originalQuestion.getId());
			try (ResultSet result = statement.executeQuery()) {
				if (result.next()) {
					throw new IllegalArgumentException("Question code already exists in this booklet: " + questionCode);
				}
			}
		}
	}

	/**
	 * Explicit metadata and captured regions for one resulting part.
	 */
	public record SplitPart(String questionCode, int marks, CurriculumNode classification,
			QuestionResponseType responseType, List<QuestionRegion> regions) {

		public SplitPart {
			if (questionCode == null || questionCode.isBlank()) {
				throw new IllegalArgumentException("questionCode must not be blank");
			}
			questionCode = questionCode.trim();
			if (marks < 1) {
				throw new IllegalArgumentException("marks must be positive");
			}
			if (classification == null) {
				throw new NullPointerException("classification");
			}
			if (responseType == null) {
				throw new NullPointerException("responseType");
			}
			if (responseType != QuestionResponseType.WRITTEN_RESPONSE) {

				// Legacy splitting exists only to reconstruct multipart written-response
				// Questions. Multiple-choice Questions are never valid split destinations.
				throw new IllegalArgumentException("Legacy split parts must be written response");
			}
			if (regions == null) {
				throw new NullPointerException("regions");
			}
			if (regions.isEmpty()) {
				throw new IllegalArgumentException("Each split part requires at least one Question region");
			}

			// Freeze the capture definition before the transaction begins.
			regions = List.copyOf(regions);
		}
	}

	/**
	 * Complete explicit definition of one legacy Question split.
	 * <p>
	 * The part at {@code retainedPartIndex} reuses the original Question row and
	 * therefore owns any existing Answer that is deliberately retained.
	 */
	public record SplitRequest(Question originalQuestion, String sourceQuestionCode, List<SplitPart> parts,
			int retainedPartIndex, NewSharedContext newSharedContext, SharedQuestionContext existingSharedContext) {

		public SplitRequest(Question originalQuestion, String sourceQuestionCode, List<SplitPart> parts,
				int retainedPartIndex) {

			// Four arguments represent an explicitly confirmed no-shared context split.
			this(originalQuestion, sourceQuestionCode, parts, retainedPartIndex, null, null);
		}

		public SplitRequest(Question originalQuestion, String sourceQuestionCode, List<SplitPart> parts,
				int retainedPartIndex, NewSharedContext newSharedContext) {

			// A staged new shared context will be created inside the split transaction.
			this(originalQuestion, sourceQuestionCode, parts, retainedPartIndex, newSharedContext, null);
		}

		public SplitRequest(Question originalQuestion, String sourceQuestionCode, List<SplitPart> parts,
				int retainedPartIndex, SharedQuestionContext existingSharedContext) {

			// An existing shared context may be reused only when it is already the
			// authoritative context of the destination SourceQuestion.
			this(originalQuestion, sourceQuestionCode, parts, retainedPartIndex, null, existingSharedContext);
		}

		public SplitRequest {
			if (originalQuestion == null) {
				throw new NullPointerException("originalQuestion");
			}
			if (sourceQuestionCode == null || sourceQuestionCode.isBlank()) {
				throw new IllegalArgumentException("sourceQuestionCode must not be blank");
			}
			sourceQuestionCode = sourceQuestionCode.trim();
			if (parts == null) {
				throw new NullPointerException("parts");
			}

			// Freeze the requested part order before the transaction starts.
			parts = List.copyOf(parts);
		}
	}

	/**
	 * Newly captured shared-context regions to create as part of the split
	 * transaction.
	 */
	public record NewSharedContext(List<SharedQuestionContextRegion> regions) {

		public NewSharedContext {
			if (regions == null) {
				throw new NullPointerException("regions");
			}
			if (regions.isEmpty()) {
				throw new IllegalArgumentException("Shared context requires at least one region");
			}

			// Freeze the staged capture before persistence begins.
			regions = List.copyOf(regions);
			for (SharedQuestionContextRegion region : regions) {
				if (region == null) {
					throw new NullPointerException("regions contains null");
				}
			}
		}
	}

	/**
	 * Persisted result of a successful split.
	 */
	public record SplitResult(List<Question> questions, SourceQuestion sourceQuestion,
			SharedQuestionContext sharedContext) {

		public SplitResult {
			if (questions == null) {
				throw new NullPointerException("questions");
			}
			if (sourceQuestion == null) {
				throw new NullPointerException("sourceQuestion");
			}
			questions = List.copyOf(questions);
		}
	}

	private record SplitRelationships(SourceQuestion sourceQuestion, SharedQuestionContext sharedContext) {
	}

	private record StoredOriginalState(long bookletId, String questionCode, Long sourceQuestionId, Long sharedContextId,
			QuestionResponseType responseType, long syllabusVersionId, boolean hasAnswer) {
	}
}
