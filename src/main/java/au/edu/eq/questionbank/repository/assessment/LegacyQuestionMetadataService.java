package au.edu.eq.questionbank.repository.assessment;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.SourceQuestionCodeParser;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

/**
 * Corrects editable legacy question metadata without requiring ordinary
 * source-region replacement.
 * <p>
 * This operation is deliberately separate from ordinary question editing.
 * Legacy-imported questions may exist before question regions have been
 * captured, so metadata correction must work with zero, one or many existing
 * regions.
 * <p>
 * The operation may change question code, marks, classification and the
 * historical preamble-capture-required flag. Booklet and source-document
 * identity remain fixed.
 * <p>
 * When a resulting single-part question changes from requiring a legacy
 * preamble to not requiring one, an attached shared context is converted into
 * leading ordinary question regions. Existing question regions follow those
 * converted regions in their original order. The question is then unlinked from
 * the shared context, which is deleted only when no other question still
 * references it.
 * <p>
 * A captured shared context cannot be removed from only one part of a multipart
 * question. Such a true-to-false correction is rejected while the resulting
 * question remains multipart.
 */
public final class LegacyQuestionMetadataService {

	private final SqliteDatabase database;
	private final SqliteQuestionRepository questionRepository;
	private final SqliteSharedQuestionContextRepository sharedContextRepository;

	/**
	 * Creates a metadata correction service.
	 *
	 * @param database question-bank database
	 */
	public LegacyQuestionMetadataService(SqliteDatabase database) {
		if (database == null) {
			throw new NullPointerException("database");
		}
		this.database = database;
		this.questionRepository = new SqliteQuestionRepository(database);
		this.sharedContextRepository = new SqliteSharedQuestionContextRepository(database);
	}

	/**
	 * Atomically resolves UNKNOWN response types for a selected set of questions.
	 * No other question metadata or capture data is modified.
	 *
	 * @param questions    questions whose response type is currently UNKNOWN
	 * @param responseType replacement MULTIPLE_CHOICE or WRITTEN_RESPONSE value
	 * @return reloaded questions after successful persistence
	 */
	public List<Question> resolveUnknownResponseTypes(List<Question> questions, QuestionResponseType responseType) {
		if (questions == null) {
			throw new NullPointerException("questions");
		}
		if (responseType == null) {
			throw new NullPointerException("responseType");
		}
		if (responseType == QuestionResponseType.UNKNOWN) {
			throw new IllegalArgumentException("UNKNOWN cannot resolve an unknown response type");
		}
		if (questions.isEmpty()) {
			throw new IllegalArgumentException("At least one question is required");
		}
		for (Question question : questions) {
			if (question == null) {
				throw new NullPointerException("questions contains null");
			}
			if (question.getResponseType() != QuestionResponseType.UNKNOWN) {
				throw new IllegalArgumentException("Question response type is not UNKNOWN: " + question.getId());
			}
		}
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try (PreparedStatement statement = connection.prepareStatement("""
					UPDATE questions
					SET response_type = ?
					WHERE id = ?
					  AND response_type = ?
					""")) {
				for (Question question : questions) {
					statement.setString(1, responseType.name());
					statement.setLong(2, question.getId());
					statement.setString(3, QuestionResponseType.UNKNOWN.name());
					int updated = statement.executeUpdate();
					if (updated != 1) {
						throw new IllegalStateException(
								"Question is missing or response type is no longer UNKNOWN: " + question.getId());
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
			throw new IllegalStateException("Could not resolve question response types atomically", failure);
		}
		return questions
				.stream().map(
						question -> questionRepository.findById(question.getId())
								.orElseThrow(() -> new IllegalStateException(
										"Question disappeared after response-type update: " + question.getId())))
				.toList();
	}

	/**
	 * Atomically corrects editable legacy metadata.
	 *
	 * @param question                persisted question being corrected
	 * @param questionCode            replacement non-blank question code
	 * @param marks                   replacement positive mark value
	 * @param classification          replacement Subtopic or Descriptor from the
	 *                                question's existing syllabus version
	 * @param preambleCaptureRequired corrected historical preamble-capture hint
	 * @return reloaded question after successful persistence
	 * @throws NullPointerException     if question or classification is null
	 * @throws IllegalArgumentException if metadata or classification is invalid
	 * @throws IllegalStateException    if persistence fails
	 */
	public Question updateMetadata(Question question, String questionCode, int marks, CurriculumNode classification,
			boolean preambleCaptureRequired) {
		return updateMetadataWithResult(question, questionCode, marks, classification, preambleCaptureRequired)
				.question();
	}

	/**
	 * Atomically corrects legacy metadata including the response type.
	 *
	 * @param question                persisted question being corrected
	 * @param questionCode            replacement non-blank question code
	 * @param marks                   replacement positive mark value
	 * @param classification          replacement Subtopic or Descriptor from the
	 *                                existing syllabus
	 * @param preambleCaptureRequired corrected historical preamble-capture hint
	 * @param responseType            replacement question response type
	 * @return reloaded question after successful persistence
	 * @throws IllegalArgumentException if metadata or classification is invalid
	 * @throws IllegalStateException    if persistence fails
	 */
	public Question updateMetadata(Question question, String questionCode, int marks, CurriculumNode classification,
			boolean preambleCaptureRequired, QuestionResponseType responseType) {
		return updateMetadataWithResult(question, questionCode, marks, classification, preambleCaptureRequired,
				responseType).question();
	}

	/**
	 * Atomically corrects legacy metadata while preserving the response type.
	 *
	 * @param question                persisted question being corrected
	 * @param questionCode            replacement non-blank question code
	 * @param marks                   replacement positive mark value
	 * @param classification          replacement Subtopic or Descriptor from the
	 *                                existing syllabus
	 * @param preambleCaptureRequired corrected historical preamble-capture hint
	 * @return reloaded question and the effect on preamble capture
	 * @throws IllegalArgumentException if metadata or classification is invalid
	 * @throws IllegalStateException    if persistence fails
	 */
	public LegacyQuestionMetadataUpdateResult updateMetadataWithResult(Question question, String questionCode,
			int marks, CurriculumNode classification, boolean preambleCaptureRequired) {
		return updateMetadataWithResultInternal(question, questionCode, marks, classification, preambleCaptureRequired,
				null);
	}

	/**
	 * Atomically corrects legacy metadata including the response type.
	 *
	 * @param question                persisted question being corrected
	 * @param questionCode            replacement non-blank question code
	 * @param marks                   replacement positive mark value
	 * @param classification          replacement Subtopic or Descriptor from the
	 *                                existing syllabus
	 * @param preambleCaptureRequired corrected historical preamble-capture hint
	 * @param responseType            replacement question response type
	 * @return reloaded question and the effect on preamble capture
	 * @throws IllegalArgumentException if metadata or classification is invalid
	 * @throws IllegalStateException    if persistence fails
	 */
	public LegacyQuestionMetadataUpdateResult updateMetadataWithResult(Question question, String questionCode,
			int marks, CurriculumNode classification, boolean preambleCaptureRequired,
			QuestionResponseType responseType) {
		if (responseType == null) {
			throw new NullPointerException("responseType");
		}
		return updateMetadataWithResultInternal(question, questionCode, marks, classification, preambleCaptureRequired,
				responseType);
	}

	private void convertSharedContextToQuestionRegions(Connection connection, long questionId, long bookletId,
			long sharedContextId) throws SQLException {
		verifySharedContextBooklet(connection, sharedContextId, bookletId);
		List<StoredRegion> sharedRegions = readSharedContextRegions(connection, sharedContextId);
		if (sharedRegions.isEmpty()) {
			throw new IllegalStateException("Linked shared context has no regions: " + sharedContextId);
		}
		List<StoredRegion> existingQuestionRegions = readQuestionRegions(connection, questionId, bookletId);
		List<StoredRegion> replacementRegions = new ArrayList<>(sharedRegions.size() + existingQuestionRegions.size());
		replacementRegions.addAll(sharedRegions);
		replacementRegions.addAll(existingQuestionRegions);
		deleteQuestionRegions(connection, questionId);
		insertQuestionRegions(connection, questionId, bookletId, replacementRegions);
	}

	private void deleteQuestionRegions(Connection connection, long questionId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				DELETE FROM question_regions
				WHERE question_id = ?
				""")) {
			statement.setLong(1, questionId);
			statement.executeUpdate();
		}
	}

	private void deleteSourceQuestionIfUnreferenced(Connection connection, long sourceQuestionId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				DELETE FROM source_questions
				WHERE id = ?
				  AND NOT EXISTS (
				      SELECT 1
				      FROM questions
				      WHERE source_question_id =
				            source_questions.id
				  )
				""")) {
			statement.setLong(1, sourceQuestionId);
			statement.executeUpdate();
		}
	}

	private Long findOrCreateSourceQuestionId(Connection connection, long bookletId, String sourceQuestionCode)
			throws SQLException {
		if (sourceQuestionCode == null) {
			return null;
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT id
				FROM source_questions
				WHERE booklet_id = ?
				  AND source_question_code = ?
				""")) {
			statement.setLong(1, bookletId);
			statement.setString(2, sourceQuestionCode);
			try (ResultSet result = statement.executeQuery()) {
				if (result.next()) {
					return Long.valueOf(result.getLong("id"));
				}
			}
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO source_questions
				    (booklet_id,
				     source_question_code)
				VALUES (?, ?)
				RETURNING id
				""")) {
			statement.setLong(1, bookletId);
			statement.setString(2, sourceQuestionCode);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new SQLException("Source-question insert did not return an id");
				}
				return Long.valueOf(result.getLong("id"));
			}
		}
	}

	private StoredQuestionState findStoredQuestionState(Connection connection, long questionId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT
				    booklet_id,
				    classification_node_id,
				    preamble_capture_required,
				    source_question_id,
				    shared_context_id,
				    response_type
				FROM questions
				WHERE id = ?
				""")) {
			statement.setLong(1, questionId);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new IllegalArgumentException("Question does not exist: " + questionId);
				}
				Long sourceQuestionId = null;
				long storedSourceQuestionId = result.getLong("source_question_id");
				if (!result.wasNull()) {
					sourceQuestionId = Long.valueOf(storedSourceQuestionId);
				}
				Long sharedContextId = null;
				long storedSharedContextId = result.getLong("shared_context_id");
				if (!result.wasNull()) {
					sharedContextId = Long.valueOf(storedSharedContextId);
				}
				return new StoredQuestionState(result.getLong("booklet_id"), result.getLong("classification_node_id"),
						result.getInt("preamble_capture_required") != 0, sourceQuestionId, sharedContextId,
						QuestionResponseType.valueOf(result.getString("response_type")));
			}
		}
	}

	private long findSyllabusVersionId(Connection connection, long curriculumNodeId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT syllabus_version_id
				FROM curriculum_nodes
				WHERE id = ?
				""")) {
			statement.setLong(1, curriculumNodeId);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new IllegalStateException(
							"Stored question classification does not exist: " + curriculumNodeId);
				}
				return result.getLong("syllabus_version_id");
			}
		}
	}

	private void insertQuestionRegions(Connection connection, long questionId, long bookletId,
			List<StoredRegion> regions) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO question_regions
				    (question_id,
				     region_order,
				     booklet_id,
				     page_number,
				     x,
				     y,
				     width,
				     height)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?)
				""")) {
			for (int index = 0; index < regions.size(); index++) {
				StoredRegion region = regions.get(index);
				statement.setLong(1, questionId);
				statement.setInt(2, index);
				statement.setLong(3, bookletId);
				statement.setInt(4, region.pageNumber());
				statement.setDouble(5, region.x());
				statement.setDouble(6, region.y());
				statement.setDouble(7, region.width());
				statement.setDouble(8, region.height());
				statement.executeUpdate();
			}
		}
	}

	private List<StoredRegion> readQuestionRegions(Connection connection, long questionId, long expectedBookletId)
			throws SQLException {
		List<StoredRegion> regions = new ArrayList<>();
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT
				    booklet_id,
				    page_number,
				    x,
				    y,
				    width,
				    height
				FROM question_regions
				WHERE question_id = ?
				ORDER BY region_order
				""")) {
			statement.setLong(1, questionId);
			try (ResultSet result = statement.executeQuery()) {
				while (result.next()) {
					if (result.getLong("booklet_id") != expectedBookletId) {
						throw new IllegalStateException("Stored question region belongs to another booklet");
					}
					regions.add(new StoredRegion(result.getInt("page_number"), result.getDouble("x"),
							result.getDouble("y"), result.getDouble("width"), result.getDouble("height")));
				}
			}
		}
		return List.copyOf(regions);
	}

	private List<StoredRegion> readSharedContextRegions(Connection connection, long sharedContextId)
			throws SQLException {
		List<StoredRegion> regions = new ArrayList<>();
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
					regions.add(new StoredRegion(result.getInt("page_number"), result.getDouble("x"),
							result.getDouble("y"), result.getDouble("width"), result.getDouble("height")));
				}
			}
		}
		return List.copyOf(regions);
	}

	private LegacyQuestionMetadataUpdateResult updateMetadataWithResultInternal(Question question, String questionCode,
			int marks, CurriculumNode classification, boolean preambleCaptureRequired,
			QuestionResponseType requestedResponseType) {
		validateRequest(question, questionCode, marks, classification);
		long questionId = question.getId();
		boolean convertedSharedContext = false;
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				StoredQuestionState stored = findStoredQuestionState(connection, questionId);
				if (stored.bookletId() != question.getBooklet().getId()) {
					throw new IllegalArgumentException("Stored question does not belong to the supplied booklet");
				}
				long existingSyllabusVersionId = findSyllabusVersionId(connection, stored.classificationNodeId());
				verifyReplacementClassification(connection, classification, existingSyllabusVersionId);
				String sourceQuestionCode = SourceQuestionCodeParser.derive(questionCode);
				boolean removingLegacyPreambleHint = stored.preambleCaptureRequired() && !preambleCaptureRequired;
				boolean removingMultipartSourceIdentity = stored.sourceQuestionId() != null
						&& sourceQuestionCode == null;
				if (removingLegacyPreambleHint && sourceQuestionCode != null && stored.sharedContextId() != null) {
					throw new IllegalArgumentException("Cannot remove the preamble requirement from one part "
							+ "of a multipart question while shared context "
							+ "is attached. Correct the shared preamble at " + "source-question level instead.");
				}
				Long replacementSourceQuestionId = findOrCreateSourceQuestionId(connection, stored.bookletId(),
						sourceQuestionCode);
				convertedSharedContext = !preambleCaptureRequired && sourceQuestionCode == null
						&& stored.sharedContextId() != null
						&& (removingLegacyPreambleHint || removingMultipartSourceIdentity);
				if (convertedSharedContext) {
					convertSharedContextToQuestionRegions(connection, questionId, stored.bookletId(),
							stored.sharedContextId().longValue());
				}
				Long replacementSharedContextId = convertedSharedContext ? null : stored.sharedContextId();
				if (replacementSourceQuestionId != null
						&& !replacementSourceQuestionId.equals(stored.sourceQuestionId())) {
					verifyDestinationSharedContext(connection, questionId, replacementSourceQuestionId,
							replacementSharedContextId);
				}
				QuestionResponseType replacementResponseType = requestedResponseType == null ? stored.responseType()
						: requestedResponseType;
				updateQuestionMetadata(connection, questionId, stored.bookletId(), questionCode, marks,
						classification.getId(), preambleCaptureRequired, replacementSourceQuestionId,
						replacementSharedContextId, replacementResponseType);
				if (stored.sourceQuestionId() != null
						&& !stored.sourceQuestionId().equals(replacementSourceQuestionId)) {
					deleteSourceQuestionIfUnreferenced(connection, stored.sourceQuestionId());
				}
				if (convertedSharedContext) {
					sharedContextRepository.deleteIfUnreferenced(connection, stored.sharedContextId().longValue(),
							stored.bookletId());
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
			throw new IllegalStateException("Could not update legacy question metadata atomically", failure);
		}
		Question updated = questionRepository.findById(questionId).orElseThrow(
				() -> new IllegalStateException("Question disappeared after metadata update: " + questionId));
		LegacyQuestionMetadataUpdateResult.PreambleOutcome outcome = convertedSharedContext
				? LegacyQuestionMetadataUpdateResult.PreambleOutcome.CONVERTED_SHARED_CONTEXT_TO_QUESTION_REGIONS
				: LegacyQuestionMetadataUpdateResult.PreambleOutcome.NO_CAPTURE_CHANGE;
		return new LegacyQuestionMetadataUpdateResult(updated, outcome);
	}

	private void updateQuestionMetadata(Connection connection, long questionId, long bookletId, String questionCode,
			int marks, long classificationNodeId, boolean preambleCaptureRequired, Long sourceQuestionId,
			Long sharedContextId, QuestionResponseType responseType) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				UPDATE questions
				SET question_code = ?,
				    marks = ?,
				    classification_node_id = ?,
				    preamble_capture_required = ?,
				    source_question_id = ?,
				    shared_context_id = ?,
				    response_type = ?
				WHERE id = ?
				  AND booklet_id = ?
				""")) {
			statement.setString(1, questionCode);
			statement.setInt(2, marks);
			statement.setLong(3, classificationNodeId);
			statement.setInt(4, preambleCaptureRequired ? 1 : 0);
			if (sourceQuestionId == null) {
				statement.setNull(5, Types.BIGINT);
			} else {
				statement.setLong(5, sourceQuestionId.longValue());
			}
			if (sharedContextId == null) {
				statement.setNull(6, Types.BIGINT);
			} else {
				statement.setLong(6, sharedContextId.longValue());
			}
			statement.setString(7, responseType.name());
			statement.setLong(8, questionId);
			statement.setLong(9, bookletId);
			if (statement.executeUpdate() != 1) {
				throw new SQLException("Question metadata update affected an unexpected number of rows");
			}
		}
	}

	private void validateRequest(Question question, String questionCode, int marks, CurriculumNode classification) {
		if (question == null) {
			throw new NullPointerException("question");
		}
		if (questionCode == null || questionCode.isBlank()) {
			throw new IllegalArgumentException("questionCode must not be blank");
		}
		if (marks < 1) {
			throw new IllegalArgumentException("marks must be positive");
		}
		if (classification == null) {
			throw new NullPointerException("classification");
		}
		if (classification.getLevel() != CurriculumLevel.SUBTOPIC
				&& classification.getLevel() != CurriculumLevel.DESCRIPTOR) {
			throw new IllegalArgumentException("Question classification must be a SUBTOPIC or DESCRIPTOR");
		}
		if (!classification.getSyllabusVersion().getSubject().equals(question.getExam().getSubject())) {
			throw new IllegalArgumentException("Question classification must belong to the exam's subject");
		}
		if (!classification.getSyllabusVersion().equals(question.getClassification().getSyllabusVersion())) {
			throw new IllegalArgumentException(
					"Legacy metadata correction must remain within the question's existing syllabus version");
		}
	}

	private void verifyDestinationSharedContext(Connection connection, long questionId, Long sourceQuestionId,
			Long sharedContextId) throws SQLException {
		if (sourceQuestionId == null) {
			return;
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT shared_context_id
				FROM questions
				WHERE source_question_id = ?
				  AND id <> ?
				""")) {
			statement.setLong(1, sourceQuestionId.longValue());
			statement.setLong(2, questionId);
			try (ResultSet result = statement.executeQuery()) {
				while (result.next()) {
					Long siblingSharedContextId = null;
					long storedSharedContextId = result.getLong("shared_context_id");
					if (!result.wasNull()) {
						siblingSharedContextId = Long.valueOf(storedSharedContextId);
					}
					if (!Objects.equals(sharedContextId, siblingSharedContextId)) {
						throw new IllegalArgumentException("Cannot move a question into a multipart "
								+ "source question whose members use " + "a different shared context.");
					}
				}
			}
		}
	}

	private void verifyReplacementClassification(Connection connection, CurriculumNode classification,
			long expectedSyllabusVersionId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT
				    syllabus_version_id,
				    curriculum_level
				FROM curriculum_nodes
				WHERE id = ?
				""")) {
			statement.setLong(1, classification.getId());
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new IllegalArgumentException(
							"Replacement classification does not exist: " + classification.getId());
				}
				long syllabusVersionId = result.getLong("syllabus_version_id");
				String level = result.getString("curriculum_level");
				if (syllabusVersionId != expectedSyllabusVersionId) {
					throw new IllegalArgumentException(
							"Replacement classification must remain within the question's existing syllabus version");
				}
				if (!CurriculumLevel.SUBTOPIC.name().equals(level)
						&& !CurriculumLevel.DESCRIPTOR.name().equals(level)) {
					throw new IllegalArgumentException("Replacement classification must be a SUBTOPIC or DESCRIPTOR");
				}
				if (!classification.getLevel().name().equals(level)) {
					throw new IllegalArgumentException(
							"Replacement classification does not match persisted curriculum data");
				}
			}
		}
	}

	private void verifySharedContextBooklet(Connection connection, long sharedContextId, long bookletId)
			throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT booklet_id
				FROM shared_question_contexts
				WHERE id = ?
				""")) {
			statement.setLong(1, sharedContextId);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new IllegalStateException("Linked shared context does not exist: " + sharedContextId);
				}
				if (result.getLong("booklet_id") != bookletId) {
					throw new IllegalStateException("Linked shared context belongs to another booklet");
				}
			}
		}
	}

	private record StoredQuestionState(long bookletId, long classificationNodeId, boolean preambleCaptureRequired,
			Long sourceQuestionId, Long sharedContextId, QuestionResponseType responseType) {
	}

	private record StoredRegion(int pageNumber, double x, double y, double width, double height) {
	}
}
