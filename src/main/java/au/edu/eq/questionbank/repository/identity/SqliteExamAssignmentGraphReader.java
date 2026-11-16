package au.edu.eq.questionbank.repository.identity;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
import au.edu.eq.questionbank.repository.identity.SqliteSubjectGraphReader.SubjectGraph;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

/**
 * Selects one complete Exam and its read-only dependencies from an immutable
 * SQLite snapshot.
 *
 * Unlike Subject snapshot selection, this reader does not include unrelated
 * Exams belonging to the same Subject.
 */
public final class SqliteExamAssignmentGraphReader {

	private static final Map<EntityType, String> EXAM_SQL = Map.ofEntries(Map.entry(EntityType.ANSWER, """
			SELECT answer.id
			FROM answers answer
			JOIN questions question
			    ON question.id = answer.question_id
			JOIN exam_booklets booklet
			    ON booklet.id = question.booklet_id
			WHERE booklet.exam_id = ?
			ORDER BY answer.id
			"""), Map.entry(EntityType.ANSWER_FILE, """
			SELECT id
			FROM answer_files
			WHERE exam_id = ?
			ORDER BY id
			"""), Map.entry(EntityType.EXAM_BOOKLET, """
			SELECT id
			FROM exam_booklets
			WHERE exam_id = ?
			ORDER BY id
			"""), Map.entry(EntityType.EXAM_PROVIDER, """
			SELECT provider.id
			FROM exam_providers provider
			JOIN exams exam
			    ON exam.provider_id = provider.id
			WHERE exam.id = ?
			ORDER BY provider.id
			"""), Map.entry(EntityType.QUESTION, """
			SELECT question.id
			FROM questions question
			JOIN exam_booklets booklet
			    ON booklet.id = question.booklet_id
			WHERE booklet.exam_id = ?
			ORDER BY question.id
			"""), Map.entry(EntityType.QUESTION_IMAGE, """
			SELECT image.id
			FROM question_images image
			JOIN questions question
			    ON question.id = image.question_id
			JOIN exam_booklets booklet
			    ON booklet.id = question.booklet_id
			WHERE booklet.exam_id = ?
			ORDER BY image.id
			"""), Map.entry(EntityType.SHARED_QUESTION_CONTEXT, """
			SELECT context.id
			FROM shared_question_contexts context
			JOIN exam_booklets booklet
			    ON booklet.id = context.booklet_id
			WHERE booklet.exam_id = ?
			ORDER BY context.id
			"""), Map.entry(EntityType.SOURCE_DOCUMENT, """
			SELECT document.id
			FROM source_documents document
			WHERE EXISTS (
			    SELECT 1
			    FROM exam_booklets booklet
			    WHERE booklet.exam_id = ?
			      AND booklet.source_document_id = document.id
			)
			OR EXISTS (
			    SELECT 1
			    FROM answer_files file
			    WHERE file.exam_id = ?
			      AND file.source_document_id = document.id
			)
			ORDER BY document.id
			"""), Map.entry(EntityType.SOURCE_QUESTION, """
			SELECT source.id
			FROM source_questions source
			JOIN exam_booklets booklet
			    ON booklet.id = source.booklet_id
			WHERE booklet.exam_id = ?
			ORDER BY source.id
			"""));
	private static final Set<EntityType> SUBJECT_DEPENDENCIES = Set.of(EntityType.SUBJECT, EntityType.SYLLABUS_VERSION,
			EntityType.CURRICULUM_NODE, EntityType.CURRICULUM_MAPPING);
	private final SqliteDatabase database;
	private final SqliteSubjectGraphReader subjectReader;

	/**
	 * Creates a reader for a completed immutable SQLite snapshot.
	 *
	 * @param stableSnapshotPath existing consistent snapshot
	 */
	public SqliteExamAssignmentGraphReader(Path stableSnapshotPath) {
		Objects.requireNonNull(stableSnapshotPath, "stableSnapshotPath");
		database = SqliteDatabase.readOnly(stableSnapshotPath);
		subjectReader = new SqliteSubjectGraphReader(stableSnapshotPath);
	}

	/**
	 * Selects one complete Exam and all its persisted capture records.
	 *
	 * <p>
	 * The Subject's curriculum is retained as a read-only dependency set. This
	 * preserves existing classification and curriculum mapping references across
	 * syllabus versions.
	 *
	 * @param subjectId SQLite-local Subject identifier
	 * @param examId    SQLite-local Exam identifier
	 * @return immutable selected graph
	 * @throws SQLException             if graph selection fails
	 * @throws IllegalArgumentException if the Exam does not belong to the selected
	 *                                  Subject
	 */
	public SubjectGraph read(long subjectId, long examId) throws SQLException {
		if (subjectId < 1 || examId < 1) {
			throw new IllegalArgumentException("Subject and Exam identifiers must be positive");
		}
		SubjectGraph subjectGraph = subjectReader.read(subjectId);
		if (!subjectGraph.ids(EntityType.EXAM).contains(examId)) {
			throw new IllegalArgumentException("Exam " + examId + " does not belong to Subject " + subjectId);
		}
		Map<EntityType, List<Long>> selections = new EnumMap<>(EntityType.class);
		try (Connection connection = database.openConnection()) {
			for (EntityType type : EntityType.values()) {
				if (SUBJECT_DEPENDENCIES.contains(type)) {

					// Curriculum remains available for classification,
					// but it is not editable assignment content.
					selections.put(type, subjectGraph.ids(type));
					continue;
				}
				if (type == EntityType.EXAM) {
					selections.put(type, List.of(examId));
					continue;
				}
				List<Long> selected = selectExamIds(connection, type, examId);

				// Every selected record must already belong to the
				// authoritative Subject graph.
				if (!subjectGraph.ids(type).containsAll(selected)) {
					throw new SQLException("Exam selection escapes Subject scope: " + type);
				}
				selections.put(type, selected);
			}
		}
		return new SubjectGraph(subjectId, subjectGraph.subjectName(), selections);
	}

	private List<Long> selectExamIds(Connection connection, EntityType type, long examId) throws SQLException {
		String sql = EXAM_SQL.get(type);
		if (sql == null) {
			throw new SQLException("No Exam assignment query for entity type: " + type);
		}
		List<Long> identifiers = new ArrayList<>();
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setLong(1, examId);

			// Source documents may be referenced independently by
			// Question Booklets and shared AnswerFiles.
			if (type == EntityType.SOURCE_DOCUMENT) {
				statement.setLong(2, examId);
			}
			try (ResultSet result = statement.executeQuery()) {
				while (result.next()) {
					long id = result.getLong(1);
					if (id < 1) {
						throw new SQLException("Invalid Exam record identifier for " + type);
					}
					identifiers.add(id);
				}
			}
		}
		return List.copyOf(identifiers);
	}
}
