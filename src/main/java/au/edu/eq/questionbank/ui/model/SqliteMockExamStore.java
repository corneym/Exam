package au.edu.eq.questionbank.ui.model;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.SourceQuestion;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.pdf.PrintableExamSettings;
import au.edu.eq.questionbank.repository.assessment.SqliteQuestionRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.repository.sqlite.SqliteImportedSubjectAuthorityRepository;

/**
 * Persists generated mock examinations and their annual Question reservations.
 *
 * <p>
 * Drafts are independently editable and do not reserve Questions. Finalisation
 * validates complete multipart selections and creates every reservation in one
 * database transaction.
 */
public final class SqliteMockExamStore {

	private final SqliteImportedSubjectAuthorityRepository authority;
	private final Clock clock;
	private final SqliteDatabase database;
	private final SqlitePortableIdentityRepository identities;
	private final SqliteQuestionRepository questions;

	/**
	 * Creates an examination persistence boundary using the local calendar.
	 *
	 * @param database managed Question Bank database
	 * @throws NullPointerException if database is null
	 */
	public SqliteMockExamStore(SqliteDatabase database) {
		this(database, Clock.systemDefaultZone());
	}

	/**
	 * Creates an examination persistence boundary with a supplied clock.
	 *
	 * @param database managed Question Bank database
	 * @param clock    clock used for finalisation and annual reset
	 * @throws NullPointerException if either argument is null
	 */
	public SqliteMockExamStore(SqliteDatabase database, Clock clock) {
		this.database = Objects.requireNonNull(database, "database");
		this.clock = Objects.requireNonNull(clock, "clock");
		authority = new SqliteImportedSubjectAuthorityRepository(database);
		identities = new SqlitePortableIdentityRepository(database);
		questions = new SqliteQuestionRepository(database);
	}

	/**
	 * Deletes an unfinalised saved examination without creating reservations.
	 *
	 * @param examinationId persistent examination UUID
	 * @param subject       authoritative Working Subject
	 * @return true if a saved draft was deleted
	 * @throws SQLException          if persistence fails
	 * @throws IllegalStateException if the examination is finalised, belongs to
	 *                               another Subject, or the Subject is not locally
	 *                               authoritative
	 * @throws NullPointerException  if either argument is null
	 */
	public boolean deleteDraft(UUID examinationId, Subject subject) throws SQLException {
		Objects.requireNonNull(examinationId, "examinationId");
		Objects.requireNonNull(subject, "subject");
		return transaction(connection -> {
			authority.requireLocallyAuthoritative(connection, subject.getId());
			UUID subjectId = requireIdentity(connection, EntityType.SUBJECT, subject.getId());
			Optional<Definition> existing = readDefinition(connection, examinationId);
			if (existing.isEmpty()) {
				return false;
			}
			Definition definition = existing.orElseThrow();
			requireSameSubject(definition, subjectId);
			if (definition.finalisedAt() != null) {
				throw new IllegalStateException("Finalised examinations cannot be deleted.");
			}
			try (PreparedStatement statement = connection.prepareStatement("""
					DELETE FROM mock_examinations
					WHERE exam_id = ?
					  AND finalised_at IS NULL
					""")) {
				statement.setString(1, examinationId.toString());
				return statement.executeUpdate() == 1;
			}
		});
	}

	/**
	 * Atomically finalises an examination and reserves its selected Questions.
	 *
	 * <p>
	 * Repeating an identical finalisation is idempotent, including after a manual
	 * annual reset. Conflicting reservations or changes to an existing finalised
	 * definition are rejected.
	 *
	 * @param draft           current examination selections
	 * @param settings        examination metadata
	 * @param includePaperTwo whether Paper 2 is included
	 * @return persisted finalised examination
	 * @throws SQLException             if persistence fails
	 * @throws IllegalArgumentException if the settings are invalid
	 * @throws IllegalStateException    if the authoritative state or selections
	 *                                  conflict
	 * @throws NullPointerException     if draft or settings is null
	 */
	public StoredExam finalise(ExamBuilderDraft draft, PrintableExamSettings settings, boolean includePaperTwo)
			throws SQLException {
		StoredExam result = persist(draft, settings, includePaperTwo, true);

		// Publish read-only model state only after the transaction commits.
		draft.replaceWith(result.draft());
		return result;
	}

	/**
	 * Reconstructs a saved examination belonging to a Subject.
	 *
	 * @param examinationId persistent examination UUID
	 * @param subject       Working Subject
	 * @return saved examination, or an empty optional
	 * @throws SQLException          if retrieval fails
	 * @throws IllegalStateException if its Subject differs or its recorded
	 *                               Questions cannot be reconstructed
	 * @throws NullPointerException  if either argument is null
	 */
	public Optional<StoredExam> find(UUID examinationId, Subject subject) throws SQLException {
		return findStored(examinationId, subject, false);
	}

	/**
	 * Lists saved examination identities belonging to a Subject.
	 *
	 * @param subject Working Subject
	 * @return immutable list of examination UUIDs, newest cohort first
	 * @throws SQLException         if retrieval fails
	 * @throws NullPointerException if subject is null
	 */
	public List<UUID> findExaminationIds(Subject subject) throws SQLException {
		Objects.requireNonNull(subject, "subject");
		return transaction(connection -> {
			UUID subjectId = requireIdentity(connection, EntityType.SUBJECT, subject.getId());
			List<UUID> result = new ArrayList<>();
			try (PreparedStatement statement = connection.prepareStatement("""
					SELECT exam_id
					FROM mock_examinations
					WHERE subject_portable_id = ?
					ORDER BY cohort_year DESC, exam_id
					""")) {
				statement.setString(1, subjectId.toString());
				try (ResultSet rows = statement.executeQuery()) {
					while (rows.next()) {
						result.add(UUID.fromString(rows.getString(1)));
					}
				}
			}
			return List.copyOf(result);
		});
	}

	/**
	 * Resolves the authoritative finalised examination for an identity.
	 *
	 * @param examinationId persistent examination UUID
	 * @param subject       Working Subject
	 * @return finalised examination, or an empty optional
	 * @throws SQLException          if retrieval fails
	 * @throws IllegalStateException if its Subject differs or its recorded
	 *                               Questions cannot be reconstructed
	 * @throws NullPointerException  if either argument is null
	 */
	public Optional<StoredExam> findFinalised(UUID examinationId, Subject subject) throws SQLException {
		return findStored(examinationId, subject, true);
	}

	/**
	 * Resolves active annual reservations to current local Question identifiers.
	 *
	 * <p>
	 * Both individual Question identities and common SourceQuestion identities
	 * participate in exclusion.
	 *
	 * @param subject    Working Subject
	 * @param cohortYear generated examination calendar year
	 * @return immutable set of unavailable Question identifiers
	 * @throws SQLException             if retrieval fails
	 * @throws IllegalArgumentException if cohortYear is not positive
	 * @throws NullPointerException     if subject is null
	 */
	public Set<Long> lockedQuestionIds(Subject subject, int cohortYear) throws SQLException {
		Objects.requireNonNull(subject, "subject");
		if (cohortYear < 1) {
			throw new IllegalArgumentException("Cohort year must be positive.");
		}
		return transaction(connection -> {
			UUID subjectId = requireIdentity(connection, EntityType.SUBJECT, subject.getId());
			Set<Long> result = new HashSet<>();
			try (PreparedStatement statement = connection.prepareStatement("""
					SELECT DISTINCT q.id
					FROM questions q
					JOIN exam_booklets eb
					  ON eb.id = q.booklet_id
					JOIN exams e
					  ON e.id = eb.exam_id
					JOIN portable_entity_identities qp
					  ON qp.entity_type = 'QUESTION'
					 AND qp.local_id = q.id
					 AND qp.retired = 0
					LEFT JOIN portable_entity_identities sp
					  ON sp.entity_type = 'SOURCE_QUESTION'
					 AND sp.local_id = q.source_question_id
					 AND sp.retired = 0
					JOIN mock_exam_reservations r
					  ON (
					      (
					          r.entity_type = 'QUESTION'
					          AND r.entity_portable_id = qp.portable_id
					      )
					      OR
					      (
					          r.entity_type = 'SOURCE_QUESTION'
					          AND r.entity_portable_id = sp.portable_id
					      )
					  )
					WHERE e.subject_id = ?
					  AND r.subject_portable_id = ?
					  AND r.cohort_year = ?
					  AND r.released_at IS NULL
					""")) {
				statement.setLong(1, subject.getId());
				statement.setString(2, subjectId.toString());
				statement.setInt(3, cohortYear);
				try (ResultSet rows = statement.executeQuery()) {
					while (rows.next()) {
						result.add(rows.getLong(1));
					}
				}
			}
			return Set.copyOf(result);
		});
	}

	/**
	 * Releases the current calendar year's reservations for one locally
	 * authoritative Subject, retaining all examination and usage history.
	 *
	 * @param subject authoritative Working Subject
	 * @return number of released reservation records
	 * @throws SQLException          if persistence fails
	 * @throws IllegalStateException if the Subject is not locally authoritative
	 * @throws NullPointerException  if subject is null
	 */
	public int resetCurrentYear(Subject subject) throws SQLException {
		Objects.requireNonNull(subject, "subject");
		Instant now = clock.instant();
		int year = now.atZone(clock.getZone()).getYear();
		return transaction(connection -> {
			authority.requireLocallyAuthoritative(connection, subject.getId());
			UUID subjectId = requireIdentity(connection, EntityType.SUBJECT, subject.getId());
			try (PreparedStatement statement = connection.prepareStatement("""
					UPDATE mock_exam_reservations
					SET released_at = ?
					WHERE subject_portable_id = ?
					  AND cohort_year = ?
					  AND released_at IS NULL
					""")) {
				statement.setString(1, now.toString());
				statement.setString(2, subjectId.toString());
				statement.setInt(3, year);
				return statement.executeUpdate();
			}
		});
	}

	/**
	 * Saves an editable examination without reserving any Questions.
	 *
	 * @param draft           current examination selections
	 * @param settings        examination metadata
	 * @param includePaperTwo whether Paper 2 is included
	 * @return saved examination
	 * @throws SQLException             if persistence fails
	 * @throws IllegalArgumentException if the settings are invalid
	 * @throws IllegalStateException    if the examination is finalised or its
	 *                                  authoritative selections are invalid
	 * @throws NullPointerException     if draft or settings is null
	 */
	public StoredExam saveDraft(ExamBuilderDraft draft, PrintableExamSettings settings, boolean includePaperTwo)
			throws SQLException {
		StoredExam result = persist(draft, settings, includePaperTwo, false);
		draft.replaceWith(result.draft());
		return result;
	}

	private void checkConflicts(Connection connection, Definition definition) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT exam_id
				FROM mock_exam_reservations
				WHERE subject_portable_id = ?
				  AND cohort_year = ?
				  AND released_at IS NULL
				  AND (
				      (
				          entity_type = 'QUESTION'
				          AND entity_portable_id = ?
				      )
				      OR
				      (
				          entity_type = 'SOURCE_QUESTION'
				          AND entity_portable_id = ?
				      )
				  )
				LIMIT 1
				""")) {
			for (StoredSelection selection : definition.selections()) {
				statement.setString(1, definition.subjectId().toString());
				statement.setInt(2, definition.settings().year());
				statement.setString(3, selection.questionId().toString());
				statement.setString(4,
						selection.sourceQuestionId() == null ? null : selection.sourceQuestionId().toString());
				try (ResultSet rows = statement.executeQuery()) {
					if (rows.next()) {
						throw new IllegalStateException(
								"Question " + selection.questionCode() + " is already reserved for "
										+ definition.settings().year() + " by examination " + rows.getString(1));
					}
				}
			}
		}
	}

	private List<StoredSelection> createSelections(Connection connection, ExamBuilderDraft draft) throws SQLException {
		List<StoredSelection> result = new ArrayList<>();
		for (ExamBuilderDraft.Section section : ExamBuilderDraft.Section.values()) {
			List<Question> selected = draft.getQuestions(section);
			for (int position = 0; position < selected.size(); position++) {
				Question question = selected.get(position);
				result.add(new StoredSelection(section, position,
						requireIdentity(connection, EntityType.QUESTION, question.getId()),
						sourceIdentity(connection, question), question.getQuestionCode(), question.getMarks()));
			}
		}
		return List.copyOf(result);
	}

	private Optional<StoredExam> findStored(UUID examinationId, Subject subject, boolean finalisedOnly)
			throws SQLException {
		Objects.requireNonNull(examinationId, "examinationId");
		Objects.requireNonNull(subject, "subject");
		return transaction(connection -> {
			UUID subjectId = requireIdentity(connection, EntityType.SUBJECT, subject.getId());
			Optional<Definition> existing = readDefinition(connection, examinationId);
			if (existing.isEmpty()) {
				return Optional.empty();
			}
			Definition definition = existing.orElseThrow();
			requireSameSubject(definition, subjectId);
			if (finalisedOnly && definition.finalisedAt() == null) {
				return Optional.empty();
			}
			return Optional.of(restore(connection, subject, examinationId, definition));
		});
	}

	private StoredExam persist(ExamBuilderDraft draft, PrintableExamSettings settings, boolean includePaperTwo,
			boolean finalise) throws SQLException {
		Objects.requireNonNull(draft, "draft");
		Objects.requireNonNull(settings, "settings");
		if (!draft.getSubject().getName().equals(settings.subject())) {
			throw new IllegalArgumentException("Draft and settings Subjects differ.");
		}
		if (!includePaperTwo && !draft.getQuestions(ExamBuilderDraft.Section.PAPER_TWO_WRITTEN).isEmpty()) {
			throw new IllegalArgumentException("Paper 2 is excluded but contains Questions.");
		}
		return transaction(connection -> {
			Subject subject = draft.getSubject();
			UUID examinationId = draft.getExaminationId();
			authority.requireLocallyAuthoritative(connection, subject.getId());
			UUID subjectId = requireIdentity(connection, EntityType.SUBJECT, subject.getId());
			Optional<Definition> existing = readDefinition(connection, examinationId);
			if (existing.isPresent()) {
				requireSameSubject(existing.orElseThrow(), subjectId);
			}
			Definition requested = new Definition(subjectId, settings, includePaperTwo, null,
					createSelections(connection, draft));
			if (existing.isPresent() && existing.orElseThrow().finalisedAt() != null) {
				Definition committed = existing.orElseThrow();
				if (finalise && sameDefinition(committed, requested)) {

					// Repeat finalisation must not create new reservations,
					// even when the existing ones have been released.
					return restore(connection, subject, examinationId, committed);
				}
				throw new IllegalStateException("Finalised examinations cannot be changed.");
			}
			if (draft.isFinalised()) {
				throw new IllegalStateException("No matching authoritative finalised examination exists.");
			}
			ExamBuilderDraft canonical = restore(connection, subject, examinationId, requested).draft();
			if (finalise) {
				canonical.buildPlan();
				checkConflicts(connection, requested);
			}
			writeDefinition(connection, examinationId, requested);
			Instant finalisedAt = null;
			if (finalise) {
				finalisedAt = clock.instant();
				try (PreparedStatement statement = connection.prepareStatement("""
						UPDATE mock_examinations
						SET finalised_at = ?
						WHERE exam_id = ?
						  AND finalised_at IS NULL
						""")) {
					statement.setString(1, finalisedAt.toString());
					statement.setString(2, examinationId.toString());
					if (statement.executeUpdate() != 1) {
						throw new SQLException("Examination could not be finalised.");
					}
				}

				// The database trigger has created every reservation
				// in the same transaction as the lifecycle transition.
				canonical.markFinalised();
			}
			return new StoredExam(canonical, settings, includePaperTwo, finalisedAt);
		});
	}

	private Optional<Definition> readDefinition(Connection connection, UUID examinationId) throws SQLException {
		UUID subjectId;
		PrintableExamSettings settings;
		boolean includePaperTwo;
		Instant finalisedAt;
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT subject_portable_id,
				       subject_name,
				       cohort_year,
				       paper_one_perusal_minutes,
				       paper_one_working_minutes,
				       paper_two_perusal_minutes,
				       paper_two_working_minutes,
				       include_paper_two,
				       finalised_at
				FROM mock_examinations
				WHERE exam_id = ?
				""")) {
			statement.setString(1, examinationId.toString());
			try (ResultSet rows = statement.executeQuery()) {
				if (!rows.next()) {
					return Optional.empty();
				}
				subjectId = UUID.fromString(rows.getString("subject_portable_id"));
				settings = new PrintableExamSettings(rows.getString("subject_name"), rows.getInt("cohort_year"),
						rows.getInt("paper_one_perusal_minutes"), rows.getInt("paper_one_working_minutes"),
						rows.getInt("paper_two_perusal_minutes"), rows.getInt("paper_two_working_minutes"));
				includePaperTwo = rows.getBoolean("include_paper_two");
				String timestamp = rows.getString("finalised_at");
				finalisedAt = timestamp == null ? null : Instant.parse(timestamp);
			}
		}
		return Optional.of(new Definition(subjectId, settings, includePaperTwo, finalisedAt,
				readSelections(connection, examinationId)));
	}

	private List<StoredSelection> readSelections(Connection connection, UUID examinationId) throws SQLException {
		List<StoredSelection> result = new ArrayList<>();
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT section,
				       position,
				       question_portable_id,
				       source_question_portable_id,
				       question_code,
				       marks
				FROM mock_exam_selections
				WHERE exam_id = ?
				ORDER BY section, position
				""")) {
			statement.setString(1, examinationId.toString());
			try (ResultSet rows = statement.executeQuery()) {
				while (rows.next()) {
					String sourceId = rows.getString("source_question_portable_id");
					result.add(new StoredSelection(ExamBuilderDraft.Section.valueOf(rows.getString("section")),
							rows.getInt("position"), UUID.fromString(rows.getString("question_portable_id")),
							sourceId == null ? null : UUID.fromString(sourceId), rows.getString("question_code"),
							rows.getInt("marks")));
				}
			}
		}
		return List.copyOf(result);
	}

	private UUID requireIdentity(Connection connection, EntityType type, long localId) throws SQLException {
		return identities.findByLocalId(connection, type, localId)
				.orElseThrow(() -> new IllegalStateException("Missing portable identity for " + type + " " + localId))
				.portableId();
	}

	private void requireSameSubject(Definition definition, UUID subjectId) {
		if (!definition.subjectId().equals(subjectId)) {
			throw new IllegalStateException("This examination belongs to another Subject.");
		}
	}

	private StoredExam restore(Connection connection, Subject subject, UUID examinationId, Definition definition)
			throws SQLException {
		Map<ExamBuilderDraft.Section, List<Question>> requested = new EnumMap<>(ExamBuilderDraft.Section.class);
		for (ExamBuilderDraft.Section section : ExamBuilderDraft.Section.values()) {
			requested.put(section, new ArrayList<>());
		}
		for (StoredSelection selection : definition.selections()) {
			List<Question> section = requested.get(selection.section());
			if (selection.position() != section.size()) {
				throw new IllegalStateException("Invalid saved examination selection order.");
			}
			long localId = identities.findByPortableId(connection, EntityType.QUESTION, selection.questionId())
					.orElseThrow(() -> new IllegalStateException(
							"Saved Question no longer exists: " + selection.questionCode()))
					.localId();
			Question question = questions.findById(connection, localId).orElseThrow(
					() -> new IllegalStateException("Saved Question no longer exists: " + selection.questionCode()));

			// Fail closed when a committed Question has changed
			// its marks, printed identity or source group.
			if (!subject.equals(question.getExam().getSubject()) || selection.marks() != question.getMarks()
					|| !selection.questionCode().equals(question.getQuestionCode())
					|| !Objects.equals(selection.sourceQuestionId(), sourceIdentity(connection, question))) {
				throw new IllegalStateException("Saved Question has changed: " + selection.questionCode());
			}
			section.add(question);
		}
		ExamBuilderDraft draft;
		if (definition.finalisedAt() != null) {

			// Historical examinations reproduce their exact recorded
			// selection, even if additional source parts now exist.
			draft = ExamBuilderDraft.restoreFinalised(subject, examinationId, requested);
		} else {

			// Editable examinations must continue to represent
			// complete current top-level Questions.
			draft = validateGroups(connection, subject, examinationId, requested);
		}
		return new StoredExam(draft, definition.settings(), definition.includePaperTwo(), definition.finalisedAt());
	}

	private boolean sameDefinition(Definition first, Definition second) {
		return first.subjectId().equals(second.subjectId()) && first.settings().equals(second.settings())
				&& first.includePaperTwo() == second.includePaperTwo()
				&& first.selections().equals(second.selections());
	}

	private UUID sourceIdentity(Connection connection, Question question) throws SQLException {
		SourceQuestion source = question.getSourceQuestion();
		return source == null ? null : requireIdentity(connection, EntityType.SOURCE_QUESTION, source.getId());
	}

	private <T> T transaction(SqlWork<T> operation) throws SQLException {
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				T result = operation.execute(connection);
				connection.commit();
				return result;
			} catch (SQLException | RuntimeException failure) {
				try {
					connection.rollback();
				} catch (SQLException rollbackFailure) {
					failure.addSuppressed(rollbackFailure);
				}
				throw failure;
			}
		}
	}

	private ExamBuilderDraft validateGroups(Connection connection, Subject subject, UUID examinationId,
			Map<ExamBuilderDraft.Section, List<Question>> requested) throws SQLException {
		ExamBuilderDraft checked = new ExamBuilderDraft(subject, examinationId);
		for (ExamBuilderDraft.Section section : ExamBuilderDraft.Section.values()) {
			List<Question> selected = requested.get(section);
			if (selected.isEmpty()) {
				continue;
			}
			Map<Long, Question> corpus = new LinkedHashMap<>();
			Set<Long> loadedSources = new HashSet<>();
			for (Question question : selected) {
				corpus.put(question.getId(), question);
				SourceQuestion source = question.getSourceQuestion();

				// Resolve complete groups from the authoritative database,
				// not just the constituents supplied by the caller.
				if (source != null && loadedSources.add(source.getId())) {
					for (Question constituent : questions.findBySourceQuestion(connection, source.getId())) {
						corpus.put(constituent.getId(), constituent);
					}
				}
			}
			if (!checked.addSelections(section, selected, List.copyOf(corpus.values()))) {
				throw new IllegalStateException("Invalid or unavailable Question in " + section);
			}
			List<Long> requestedIds = selected.stream().map(Question::getId).toList();
			List<Long> validatedIds = checked.getQuestions(section).stream().map(Question::getId).toList();
			if (!requestedIds.equals(validatedIds)) {
				throw new IllegalStateException("Incomplete or incorrectly ordered multipart Question in " + section);
			}
		}
		return checked;
	}

	private void writeDefinition(Connection connection, UUID examinationId, Definition definition) throws SQLException {
		PrintableExamSettings settings = definition.settings();
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO mock_examinations (
				    exam_id,
				    subject_portable_id,
				    subject_name,
				    cohort_year,
				    paper_one_perusal_minutes,
				    paper_one_working_minutes,
				    paper_two_perusal_minutes,
				    paper_two_working_minutes,
				    include_paper_two
				)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
				ON CONFLICT(exam_id) DO UPDATE SET
				    subject_name = excluded.subject_name,
				    cohort_year = excluded.cohort_year,
				    paper_one_perusal_minutes = excluded.paper_one_perusal_minutes,
				    paper_one_working_minutes = excluded.paper_one_working_minutes,
				    paper_two_perusal_minutes = excluded.paper_two_perusal_minutes,
				    paper_two_working_minutes = excluded.paper_two_working_minutes,
				    include_paper_two = excluded.include_paper_two
				""")) {
			statement.setString(1, examinationId.toString());
			statement.setString(2, definition.subjectId().toString());
			statement.setString(3, settings.subject());
			statement.setInt(4, settings.year());
			statement.setInt(5, settings.paperOnePerusalMinutes());
			statement.setInt(6, settings.paperOneWorkingMinutes());
			statement.setInt(7, settings.paperTwoPerusalMinutes());
			statement.setInt(8, settings.paperTwoWorkingMinutes());
			statement.setBoolean(9, definition.includePaperTwo());
			if (statement.executeUpdate() != 1) {
				throw new SQLException("Could not save examination definition.");
			}
		}

		// Replace the entire ordered selection in the same
		// transaction as the examination metadata.
		try (PreparedStatement statement = connection.prepareStatement("""
				DELETE FROM mock_exam_selections
				WHERE exam_id = ?
				""")) {
			statement.setString(1, examinationId.toString());
			statement.executeUpdate();
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO mock_exam_selections (
				    exam_id,
				    section,
				    position,
				    question_portable_id,
				    source_question_portable_id,
				    question_code,
				    marks
				)
				VALUES (?, ?, ?, ?, ?, ?, ?)
				""")) {
			for (StoredSelection selection : definition.selections()) {
				statement.setString(1, examinationId.toString());
				statement.setString(2, selection.section().name());
				statement.setInt(3, selection.position());
				statement.setString(4, selection.questionId().toString());
				statement.setString(5,
						selection.sourceQuestionId() == null ? null : selection.sourceQuestionId().toString());
				statement.setString(6, selection.questionCode());
				statement.setInt(7, selection.marks());
				statement.addBatch();
			}
			statement.executeBatch();
		}
	}

	/**
	 * Reconstructed persisted examination and its metadata.
	 *
	 * @param draft           ordered examination selections and lifecycle
	 * @param settings        persisted examination metadata
	 * @param includePaperTwo whether Paper 2 is included
	 * @param finalisedAt     finalisation timestamp, or null for a draft
	 */
	public record StoredExam(ExamBuilderDraft draft, PrintableExamSettings settings, boolean includePaperTwo,
			Instant finalisedAt) {

		/**
		 * Creates a reconstructed examination result.
		 *
		 * @param draft           examination selections
		 * @param settings        examination metadata
		 * @param includePaperTwo whether Paper 2 is included
		 * @param finalisedAt     finalisation timestamp, or null
		 * @throws NullPointerException     if draft or settings is null
		 * @throws IllegalArgumentException if the model lifecycle differs from the
		 *                                  persisted lifecycle
		 */
		public StoredExam {
			Objects.requireNonNull(draft, "draft");
			Objects.requireNonNull(settings, "settings");
			if (draft.isFinalised() != (finalisedAt != null)) {
				throw new IllegalArgumentException("Examination lifecycle mismatch.");
			}
		}
	}

	private record Definition(UUID subjectId, PrintableExamSettings settings, boolean includePaperTwo,
			Instant finalisedAt, List<StoredSelection> selections) {
	}

	@FunctionalInterface
	private interface SqlWork<T> {

		T execute(Connection connection) throws SQLException;
	}

	private record StoredSelection(ExamBuilderDraft.Section section, int position, UUID questionId,
			UUID sourceQuestionId, String questionCode, int marks) {
	}
}
