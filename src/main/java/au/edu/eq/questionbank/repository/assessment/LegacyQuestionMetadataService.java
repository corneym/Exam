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
 * historical shared context-capture-required flag. Booklet and source-document
 * identity remain fixed.
 * <p>
 * When a resulting single-part Question changes from requiring a legacy Shared
 * Context to not requiring one, the attached Shared Context is converted into
 * leading PDF-backed Question content. Existing Question content follows in its
 * original authoritative order, including any stored image parts. The Question
 * is then unlinked from the Shared Context, which is deleted only when no other
 * Question still references it.
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

				// Recheck persisted UNKNOWN state so a stale selection rolls back the whole
				// batch.
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
	 * @param question                     persisted question being corrected
	 * @param questionCode                 replacement non-blank question code
	 * @param marks                        replacement positive mark value
	 * @param classification               replacement Subtopic or Descriptor from
	 *                                     the question's existing syllabus version
	 * @param sharedContextCaptureRequired corrected historical shared
	 *                                     context-capture hint
	 * @return reloaded question after successful persistence
	 * @throws NullPointerException     if question or classification is null
	 * @throws IllegalArgumentException if metadata or classification is invalid
	 * @throws IllegalStateException    if persistence fails
	 */
	public Question updateMetadata(Question question, String questionCode, int marks, CurriculumNode classification,
			boolean sharedContextCaptureRequired) {
		return updateMetadataWithResult(question, questionCode, marks, classification, sharedContextCaptureRequired)
				.question();
	}

	/**
	 * Atomically corrects legacy metadata including the response type.
	 *
	 * @param question                     persisted question being corrected
	 * @param questionCode                 replacement non-blank question code
	 * @param marks                        replacement positive mark value
	 * @param classification               replacement Subtopic or Descriptor from
	 *                                     the existing syllabus
	 * @param sharedContextCaptureRequired corrected historical shared
	 *                                     context-capture hint
	 * @param responseType                 replacement question response type
	 * @return reloaded question after successful persistence
	 * @throws IllegalArgumentException if metadata or classification is invalid
	 * @throws IllegalStateException    if persistence fails
	 */
	public Question updateMetadata(Question question, String questionCode, int marks, CurriculumNode classification,
			boolean sharedContextCaptureRequired, QuestionResponseType responseType) {
		return updateMetadataWithResult(question, questionCode, marks, classification, sharedContextCaptureRequired,
				responseType).question();
	}

	/**
	 * Atomically corrects legacy metadata while preserving the response type.
	 *
	 * @param question                     persisted question being corrected
	 * @param questionCode                 replacement non-blank question code
	 * @param marks                        replacement positive mark value
	 * @param classification               replacement Subtopic or Descriptor from
	 *                                     the existing syllabus
	 * @param sharedContextCaptureRequired corrected historical shared
	 *                                     context-capture hint
	 * @return reloaded question and the effect on shared context capture
	 * @throws IllegalArgumentException if metadata or classification is invalid
	 * @throws IllegalStateException    if persistence fails
	 */
	public LegacyQuestionMetadataUpdateResult updateMetadataWithResult(Question question, String questionCode,
			int marks, CurriculumNode classification, boolean sharedContextCaptureRequired) {
		return updateMetadataWithResultInternal(question, questionCode, marks, classification,
				sharedContextCaptureRequired, null);
	}

	/**
	 * Atomically corrects legacy metadata including the response type.
	 *
	 * @param question                     persisted question being corrected
	 * @param questionCode                 replacement non-blank question code
	 * @param marks                        replacement positive mark value
	 * @param classification               replacement Subtopic or Descriptor from
	 *                                     the existing syllabus
	 * @param sharedContextCaptureRequired corrected historical shared
	 *                                     context-capture hint
	 * @param responseType                 replacement question response type
	 * @return reloaded question and the effect on shared context capture
	 * @throws IllegalArgumentException if metadata or classification is invalid
	 * @throws IllegalStateException    if persistence fails
	 */
	public LegacyQuestionMetadataUpdateResult updateMetadataWithResult(Question question, String questionCode,
			int marks, CurriculumNode classification, boolean sharedContextCaptureRequired,
			QuestionResponseType responseType) {
		if (responseType == null) {
			throw new NullPointerException("responseType");
		}
		return updateMetadataWithResultInternal(question, questionCode, marks, classification,
				sharedContextCaptureRequired, responseType);
	}

	private void convertSharedContextToQuestionRegions(Connection connection, long questionId, long bookletId,
			long sharedContextId) throws SQLException {
		verifySharedContextBooklet(connection, sharedContextId, bookletId);
		List<StoredRegion> sharedRegions = readSharedContextRegions(connection, sharedContextId);
		if (sharedRegions.isEmpty()) {
			throw new IllegalStateException("Linked shared context has no regions: " + sharedContextId);
		}
		List<StoredContentPart> existingContentParts = readQuestionContentParts(connection, questionId, bookletId);
		List<StoredContentPart> replacementContentParts = new ArrayList<>(
				sharedRegions.size() + existingContentParts.size());

		// Shared Context becomes leading PDF-backed Question content. The previously
		// stored mixed Question body follows without changing its internal order.
		for (StoredRegion sharedRegion : sharedRegions) {
			replacementContentParts.add(StoredContentPart.pdf(sharedRegion));
		}
		replacementContentParts.addAll(existingContentParts);

		// Remove composition rows before deleting PDF-region rows so foreign-key
		// cascades cannot disturb the image rows that will be reused below.
		deleteQuestionContentParts(connection, questionId);
		deleteQuestionRegions(connection, questionId);

		// Rebuild PDF-region identity and complete mixed assembly order while retaining
		// the existing question_images rows and their durable ids.
		insertQuestionContentParts(connection, questionId, bookletId, replacementContentParts);
	}

	private void deleteQuestionContentParts(Connection connection, long questionId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				DELETE FROM question_content_parts
				WHERE question_id = ?
				""")) {
			statement.setLong(1, questionId);

			// Delete only assembly references. Stored image rows remain available for
			// reinsertion into the rebuilt mixed sequence.
			statement.executeUpdate();
		}
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
				    shared_context_capture_required,
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
						result.getInt("shared_context_capture_required") != 0, sourceQuestionId, sharedContextId,
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

	private void insertQuestionContentParts(Connection connection, long questionId, long bookletId,
			List<StoredContentPart> contentParts) throws SQLException {
		try (PreparedStatement regionStatement = connection.prepareStatement("""
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
				"""); PreparedStatement contentStatement = connection.prepareStatement("""
				INSERT INTO question_content_parts
				    (question_id,
				     content_order,
				     content_type,
				     region_order,
				     image_id)
				VALUES (?, ?, ?, ?, ?)
				""")) {
			int regionOrder = 0;
			for (int contentOrder = 0; contentOrder < contentParts.size(); contentOrder++) {
				StoredContentPart part = contentParts.get(contentOrder);
				if (part.region() != null) {
					StoredRegion region = part.region();

					// PDF-region identity remains a compact projection of PDF parts in
					// encounter order within the complete mixed Question body.
					regionStatement.setLong(1, questionId);
					regionStatement.setInt(2, regionOrder);
					regionStatement.setLong(3, bookletId);
					regionStatement.setInt(4, region.pageNumber());
					regionStatement.setDouble(5, region.x());
					regionStatement.setDouble(6, region.y());
					regionStatement.setDouble(7, region.width());
					regionStatement.setDouble(8, region.height());
					regionStatement.executeUpdate();
					contentStatement.setLong(1, questionId);
					contentStatement.setInt(2, contentOrder);
					contentStatement.setString(3, "PDF_REGION");
					contentStatement.setInt(4, regionOrder);
					contentStatement.setNull(5, Types.BIGINT);
					contentStatement.executeUpdate();
					regionOrder++;
					continue;
				}

				// Image bytes remain in question_images. Reconnect the existing durable
				// image row at its preserved position in the rebuilt content sequence.
				contentStatement.setLong(1, questionId);
				contentStatement.setInt(2, contentOrder);
				contentStatement.setString(3, "IMAGE");
				contentStatement.setNull(4, Types.INTEGER);
				contentStatement.setLong(5, part.imageId().longValue());
				contentStatement.executeUpdate();
			}
		}
	}

	private List<StoredContentPart> readQuestionContentParts(Connection connection, long questionId,
			long expectedBookletId) throws SQLException {
		List<StoredContentPart> contentParts = new ArrayList<>();
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT
				    qcp.content_type,
				    qr.booklet_id,
				    qr.page_number,
				    qr.x,
				    qr.y,
				    qr.width,
				    qr.height,
				    qi.id AS stored_image_id
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
						long bookletId = result.getLong("booklet_id");
						if (result.wasNull()) {
							throw new IllegalStateException("Question PDF content has no stored region");
						}
						if (bookletId != expectedBookletId) {
							throw new IllegalStateException("Stored question region belongs to another booklet");
						}
						StoredRegion region = new StoredRegion(result.getInt("page_number"), result.getDouble("x"),
								result.getDouble("y"), result.getDouble("width"), result.getDouble("height"));
						contentParts.add(StoredContentPart.pdf(region));
						continue;
					}
					if ("IMAGE".equals(contentType)) {
						long imageId = result.getLong("stored_image_id");
						if (result.wasNull()) {
							throw new IllegalStateException("Question image content has no stored image");
						}
						contentParts.add(StoredContentPart.image(imageId));
						continue;
					}
					throw new IllegalStateException("Unsupported Question content type: " + contentType);
				}
			}
		}
		return List.copyOf(contentParts);
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
			int marks, CurriculumNode classification, boolean sharedContextCaptureRequired,
			QuestionResponseType requestedResponseType) {
		validateRequest(question, questionCode, marks, classification);
		long questionId = question.getId();
		boolean convertedSharedContext = false;
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {

				// Base transition decisions on persisted state rather than the supplied
				// snapshot.
				StoredQuestionState stored = findStoredQuestionState(connection, questionId);
				if (stored.bookletId() != question.getBooklet().getId()) {
					throw new IllegalArgumentException("Stored question does not belong to the supplied booklet");
				}
				long existingSyllabusVersionId = findSyllabusVersionId(connection, stored.classificationNodeId());
				verifyReplacementClassification(connection, classification, existingSyllabusVersionId);
				String sourceQuestionCode = SourceQuestionCodeParser.derive(questionCode);
				boolean removingLegacySharedContextHint = stored.sharedContextCaptureRequired()
						&& !sharedContextCaptureRequired;
				boolean removingMultipartSourceIdentity = stored.sourceQuestionId() != null
						&& sourceQuestionCode == null;
				if (removingLegacySharedContextHint && sourceQuestionCode != null && stored.sharedContextId() != null) {
					throw new IllegalArgumentException("Cannot remove the shared context requirement from one part "
							+ "of a multipart question while shared context "
							+ "is attached. Correct the shared context at " + "source-question level instead.");
				}
				Long replacementSourceQuestionId = findOrCreateSourceQuestionId(connection, stored.bookletId(),
						sourceQuestionCode);

				// Convert only on a relevant transition; unrelated metadata edits retain the
				// context.
				convertedSharedContext = !sharedContextCaptureRequired && sourceQuestionCode == null
						&& stored.sharedContextId() != null
						&& (removingLegacySharedContextHint || removingMultipartSourceIdentity);
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
						classification.getId(), sharedContextCaptureRequired, replacementSourceQuestionId,
						replacementSharedContextId, replacementResponseType);

				// Remove superseded identities only after relinking, and only if no siblings
				// use them.
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

		// Reload after commit so the result includes any converted source regions.
		Question updated = questionRepository.findById(questionId).orElseThrow(
				() -> new IllegalStateException("Question disappeared after metadata update: " + questionId));
		LegacyQuestionMetadataUpdateResult.SharedContextOutcome outcome = convertedSharedContext
				? LegacyQuestionMetadataUpdateResult.SharedContextOutcome.CONVERTED_SHARED_CONTEXT_TO_QUESTION_REGIONS
				: LegacyQuestionMetadataUpdateResult.SharedContextOutcome.NO_CAPTURE_CHANGE;
		return new LegacyQuestionMetadataUpdateResult(updated, outcome);
	}

	private void updateQuestionMetadata(Connection connection, long questionId, long bookletId, String questionCode,
			int marks, long classificationNodeId, boolean sharedContextCaptureRequired, Long sourceQuestionId,
			Long sharedContextId, QuestionResponseType responseType) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				UPDATE questions
				SET question_code = ?,
				    marks = ?,
				    classification_node_id = ?,
				    shared_context_capture_required = ?,
				    source_question_id = ?,
				    shared_context_id = ?,
				    response_type = ?
				WHERE id = ?
				  AND booklet_id = ?
				""")) {
			statement.setString(1, questionCode);
			statement.setInt(2, marks);
			statement.setLong(3, classificationNodeId);
			statement.setInt(4, sharedContextCaptureRequired ? 1 : 0);
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

	private record StoredContentPart(StoredRegion region, Long imageId) {

		private StoredContentPart {

			// Each persisted content part represents exactly one backing source.
			if ((region == null) == (imageId == null)) {
				throw new IllegalArgumentException("Stored content part must contain exactly one source");
			}
			if (imageId != null && imageId.longValue() < 1) {
				throw new IllegalArgumentException("Stored image id must be positive");
			}
		}

		private static StoredContentPart pdf(StoredRegion region) {
			return new StoredContentPart(Objects.requireNonNull(region), null);
		}

		private static StoredContentPart image(long imageId) {
			return new StoredContentPart(null, Long.valueOf(imageId));
		}
	}

	private record StoredQuestionState(long bookletId, long classificationNodeId, boolean sharedContextCaptureRequired,
			Long sourceQuestionId, Long sharedContextId, QuestionResponseType responseType) {
	}

	private record StoredRegion(int pageNumber, double x, double y, double width, double height) {
	}
}
