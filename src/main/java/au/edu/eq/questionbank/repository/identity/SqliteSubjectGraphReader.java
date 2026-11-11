package au.edu.eq.questionbank.repository.identity;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

/**
 * Discovers the complete set of persistent entities belonging to a selected
 * Subject for portable snapshot export.
 *
 * <p>
 * The reader operates against an existing, stable SQLite database snapshot
 * opened in read-only mode. It identifies the Subject's syllabus versions,
 * curriculum nodes and mappings, examinations, booklets, questions, answers,
 * shared contexts, source documents and other supported portable entities.
 *
 * <p>
 * Shared reference entities, such as examination providers and source
 * documents, are included only when referenced by the selected Subject. Records
 * belonging exclusively to unrelated Subjects are excluded.
 *
 * <p>
 * Cross-Subject curriculum mappings and mapping reviews are rejected because
 * exporting only one side would produce an incomplete or misleading graph.
 *
 * <p>
 * The returned {@link SubjectGraph} contains SQLite-local identifiers used
 * internally for subsequent record retrieval. These identifiers are not
 * portable identities and must never be serialised directly into an
 * {@code .eqwork} package. The subsequent export layer resolves them to
 * persistent portable UUIDs.
 *
 * <p>
 * This repository does not modify the source database, managed files,
 * persistent identities or revision information. It does not construct or
 * publish exchange archives.
 *
 * @see SqlitePortableIdentityRepository
 * @see SqlitePortableContentRepository
 * @see SqliteDatabase
 */
public class SqliteSubjectGraphReader {

	private static final Map<EntityType, String> SELECTION_SQL = Map.ofEntries(Map.entry(EntityType.ANSWER, """
			SELECT answer.id
			FROM answers answer
			JOIN questions question
			    ON question.id = answer.question_id
			JOIN exam_booklets booklet
			    ON booklet.id = question.booklet_id
			JOIN exams exam
			    ON exam.id = booklet.exam_id
			WHERE exam.subject_id = ?
			ORDER BY answer.id
			"""), Map.entry(EntityType.ANSWER_FILE, """
			SELECT file.id
			FROM answer_files file
			JOIN exams exam
			    ON exam.id = file.exam_id
			WHERE exam.subject_id = ?
			ORDER BY file.id
			"""), Map.entry(EntityType.CURRICULUM_MAPPING, """
			SELECT mapping.id
			FROM curriculum_mappings mapping
			JOIN curriculum_nodes source
			    ON source.id = mapping.source_node_id
			JOIN syllabus_versions version
			    ON version.id = source.syllabus_version_id
			WHERE version.subject_id = ?
			ORDER BY mapping.id
			"""), Map.entry(EntityType.CURRICULUM_NODE, """
			SELECT node.id
			FROM curriculum_nodes node
			JOIN syllabus_versions version
			    ON version.id = node.syllabus_version_id
			WHERE version.subject_id = ?
			ORDER BY node.id
			"""), Map.entry(EntityType.EXAM, """
			SELECT id
			FROM exams
			WHERE subject_id = ?
			ORDER BY id
			"""), Map.entry(EntityType.EXAM_BOOKLET, """
			SELECT booklet.id
			FROM exam_booklets booklet
			JOIN exams exam
			    ON exam.id = booklet.exam_id
			WHERE exam.subject_id = ?
			ORDER BY booklet.id
			"""), Map.entry(EntityType.EXAM_PROVIDER, """
			SELECT DISTINCT provider.id
			FROM exam_providers provider
			JOIN exams exam
			    ON exam.provider_id = provider.id
			WHERE exam.subject_id = ?
			ORDER BY provider.id
			"""), Map.entry(EntityType.QUESTION, """
			SELECT question.id
			FROM questions question
			JOIN exam_booklets booklet
			    ON booklet.id = question.booklet_id
			JOIN exams exam
			    ON exam.id = booklet.exam_id
			WHERE exam.subject_id = ?
			ORDER BY question.id
			"""), Map.entry(EntityType.QUESTION_IMAGE, """
			SELECT image.id
			FROM question_images image
			JOIN questions question
			    ON question.id = image.question_id
			JOIN exam_booklets booklet
			    ON booklet.id = question.booklet_id
			JOIN exams exam
			    ON exam.id = booklet.exam_id
			WHERE exam.subject_id = ?
			ORDER BY image.id
			"""), Map.entry(EntityType.SHARED_QUESTION_CONTEXT, """
			SELECT context.id
			FROM shared_question_contexts context
			JOIN exam_booklets booklet
			    ON booklet.id = context.booklet_id
			JOIN exams exam
			    ON exam.id = booklet.exam_id
			WHERE exam.subject_id = ?
			ORDER BY context.id
			"""), Map.entry(EntityType.SOURCE_DOCUMENT, """
			WITH selected_exams AS (
			    SELECT id
			    FROM exams
			    WHERE subject_id = ?
			)
			SELECT document.id
			FROM source_documents document
			WHERE EXISTS (
			    SELECT 1
			    FROM exam_booklets booklet
			    JOIN selected_exams exam
			        ON exam.id = booklet.exam_id
			    WHERE booklet.source_document_id = document.id
			)
			OR EXISTS (
			    SELECT 1
			    FROM answer_files file
			    JOIN selected_exams exam
			        ON exam.id = file.exam_id
			    WHERE file.source_document_id = document.id
			)
			ORDER BY document.id
			"""), Map.entry(EntityType.SOURCE_QUESTION, """
			SELECT source.id
			FROM source_questions source
			JOIN exam_booklets booklet
			    ON booklet.id = source.booklet_id
			JOIN exams exam
			    ON exam.id = booklet.exam_id
			WHERE exam.subject_id = ?
			ORDER BY source.id
			"""), Map.entry(EntityType.SUBJECT, """
			SELECT id
			FROM subjects
			WHERE id = ?
			ORDER BY id
			"""), Map.entry(EntityType.SYLLABUS_VERSION, """
			SELECT id
			FROM syllabus_versions
			WHERE subject_id = ?
			ORDER BY id
			"""));
	private final SqliteDatabase database;

	/**
	 * Creates a Subject graph reader for a stable SQLite snapshot.
	 *
	 * <p>
	 * The supplied database must be a completed, immutable snapshot. It must not be
	 * the application's live database.
	 *
	 * @param stableSnapshotPath existing SQLite snapshot path
	 * @throws NullPointerException if the path is null
	 */
	public SqliteSubjectGraphReader(Path stableSnapshotPath) {
		Objects.requireNonNull(stableSnapshotPath, "stableSnapshotPath");
		database = SqliteDatabase.readOnly(stableSnapshotPath);
	}

	/**
	 * Discovers the complete portable-identity entity set belonging to one Subject.
	 *
	 * <p>
	 * The result contains SQLite-local identifiers for subsequent portable record
	 * assembly. These identifiers must not be serialised directly into an exchange
	 * package.
	 *
	 * @param subjectId existing SQLite-local Subject identifier
	 * @return immutable Subject graph selection
	 * @throws SQLException if the Subject cannot be found, the database cannot be
	 *                      read, or a cross-Subject curriculum relationship
	 *                      prevents a valid Subject-only export
	 */
	public SubjectGraph read(long subjectId) throws SQLException {
		if (subjectId < 1) {
			throw new IllegalArgumentException("subjectId must be positive");
		}
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			String subjectName = readSubjectName(connection, subjectId);

			// Do not omit mappings or reviews that cross the
			// requested Subject boundary.
			requireInternalCurriculumReferences(connection, subjectId);
			Map<EntityType, List<Long>> selections = readSelections(connection, subjectId);
			SubjectGraph graph = new SubjectGraph(subjectId, subjectName, selections);
			connection.commit();
			return graph;
		}
	}

	private Map<EntityType, List<Long>> readSelections(Connection connection, long subjectId) throws SQLException {
		Map<EntityType, List<Long>> selections = new EnumMap<>(EntityType.class);
		for (EntityType type : EntityType.values()) {
			String sql = SELECTION_SQL.get(type);
			if (sql == null) {
				throw new SQLException("No Subject graph query for entity type: " + type);
			}
			List<Long> identifiers = new ArrayList<>();
			try (PreparedStatement statement = connection.prepareStatement(sql)) {
				statement.setLong(1, subjectId);
				try (ResultSet result = statement.executeQuery()) {
					while (result.next()) {
						long id = result.getLong(1);
						if (id < 1) {
							throw new SQLException("Invalid local identifier for " + type + ": " + id);
						}
						identifiers.add(id);
					}
				}
			}
			selections.put(type, List.copyOf(identifiers));
		}
		return selections;
	}

	private String readSubjectName(Connection connection, long subjectId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT subject_name
				FROM subjects
				WHERE id = ?
				""")) {
			statement.setLong(1, subjectId);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new SQLException("Subject does not exist: " + subjectId);
				}
				String subjectName = result.getString("subject_name");
				if (subjectName == null || subjectName.isBlank()) {
					throw new SQLException("Subject has no valid name: " + subjectId);
				}
				return subjectName;
			}
		}
	}

	private void rejectCrossSubject(Connection connection, long subjectId, String relationship, String sql)
			throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			for (int parameter = 1; parameter <= 4; parameter++) {
				statement.setLong(parameter, subjectId);
			}
			try (ResultSet result = statement.executeQuery()) {
				if (result.next()) {
					throw new SQLException("Cannot export Subject " + subjectId + ": cross-Subject " + relationship
							+ " references another Subject");
				}
			}
		}
	}

	private void requireInternalCurriculumReferences(Connection connection, long subjectId) throws SQLException {
		rejectCrossSubject(connection, subjectId, "curriculum mapping", """
				SELECT mapping.id
				FROM curriculum_mappings mapping
				JOIN curriculum_nodes source
				    ON source.id = mapping.source_node_id
				JOIN syllabus_versions source_version
				    ON source_version.id =
				        source.syllabus_version_id
				JOIN curriculum_nodes target
				    ON target.id = mapping.target_node_id
				JOIN syllabus_versions target_version
				    ON target_version.id =
				        target.syllabus_version_id
				WHERE (
				    source_version.subject_id = ?
				    AND target_version.subject_id <> ?
				)
				OR (
				    source_version.subject_id <> ?
				    AND target_version.subject_id = ?
				)
				LIMIT 1
				""");
		rejectCrossSubject(connection, subjectId, "curriculum mapping review", """
				SELECT review.source_node_id
				FROM curriculum_mapping_reviews review
				JOIN curriculum_nodes source
				    ON source.id = review.source_node_id
				JOIN syllabus_versions source_version
				    ON source_version.id =
				        source.syllabus_version_id
				JOIN syllabus_versions target_version
				    ON target_version.id =
				        review.target_syllabus_version_id
				WHERE (
				    source_version.subject_id = ?
				    AND target_version.subject_id <> ?
				)
				OR (
				    source_version.subject_id <> ?
				    AND target_version.subject_id = ?
				)
				LIMIT 1
				""");
	}

	/**
	 * Immutable selection of local entity identifiers belonging to one Subject.
	 *
	 * <p>
	 * This is an internal export planning structure, not an archive record. Its
	 * local integer identifiers must be converted to portable identities before
	 * serialisation.
	 *
	 * @param subjectId   SQLite-local Subject identifier
	 * @param subjectName stored Subject name
	 * @param localIds    selected identifiers by entity type
	 */
	public record SubjectGraph(long subjectId, String subjectName, Map<EntityType, List<Long>> localIds) {

		/**
		 * Validates and defensively copies the graph selection.
		 */
		public SubjectGraph {
			if (subjectId < 1) {
				throw new IllegalArgumentException("subjectId must be positive");
			}
			if (subjectName == null || subjectName.isBlank()) {
				throw new IllegalArgumentException("subjectName must not be blank");
			}
			Objects.requireNonNull(localIds, "localIds");
			Map<EntityType, List<Long>> copy = new EnumMap<>(EntityType.class);
			for (EntityType type : EntityType.values()) {
				List<Long> supplied = Objects.requireNonNull(localIds.get(type), "Missing selection for " + type);
				List<Long> sorted = new ArrayList<>(supplied);
				sorted.sort(Long::compareTo);
				if (sorted.stream().anyMatch(id -> id < 1)) {
					throw new IllegalArgumentException("Invalid identifier for " + type);
				}
				if (new HashSet<>(sorted).size() != sorted.size()) {
					throw new IllegalArgumentException("Duplicate identifier for " + type);
				}
				copy.put(type, List.copyOf(sorted));
			}
			if (!copy.get(EntityType.SUBJECT).equals(List.of(subjectId))) {
				throw new IllegalArgumentException("Subject graph must contain exactly " + "the requested Subject");
			}
			localIds = Collections.unmodifiableMap(copy);
		}

		/**
		 * Returns the selected local identifiers for one entity type.
		 *
		 * @param type entity type
		 * @return immutable, ascending identifier list
		 */
		public List<Long> ids(EntityType type) {
			Objects.requireNonNull(type, "type");
			return localIds.get(type);
		}
	}
}
