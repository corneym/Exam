package au.edu.eq.questionbank.repository.assessment;

/**
 * SQL definitions shared by production Question retrieval and the read-only
 * performance investigation.
 *
 * Keeping one authoritative definition ensures EXPLAIN QUERY PLAN analyses the
 * same SQL used by the repository.
 */
public final class SqliteQuestionSelectSql {

	private static final String ALL_IDS_SQL = """
			SELECT id
			FROM questions
			ORDER BY id
			""";
	private static final String APPLICABILITY_SQL = """
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
			""";
	private static final String BY_ID_SQL = """
			SELECT
			    q.id,
			    q.question_code,
			    q.question_text,
			    q.marks,
			    q.shared_context_capture_required,
			    q.source_capture_required,
			    q.response_type,
			    q.source_question_id,
			    q.shared_context_id,
			    q.classification_node_id,
			    eb.id AS booklet_id,
			    eb.booklet_name,
			    eb.question_format,
			    eb.expected_question_count,
			    sd.id AS source_document_id,
			    sd.relative_path,
			    sd.content_sha256,
			    e.id AS exam_id,
			    e.exam_year,
			    e.exam_name,
			    e.capture_state,
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
			""";

	private SqliteQuestionSelectSql() {
	}

	/**
	 * Returns the production query selecting all Question identifiers.
	 *
	 * @return SQL selecting Question IDs in ascending order
	 */
	public static String allIds() {
		return ALL_IDS_SQL;
	}

	/**
	 * Returns the production curriculum-applicability query.
	 *
	 * @param requestedNodeCount number of current curriculum nodes
	 * @return parameterised applicability SQL
	 */
	public static String applicability(int requestedNodeCount) {
		if (requestedNodeCount <= 0) {
			throw new IllegalArgumentException("At least one current node is required");
		}
		StringBuilder values = new StringBuilder();
		for (int index = 0; index < requestedNodeCount; index++) {
			if (index > 0) {
				values.append(", ");
			}
			values.append("(?)");
		}
		return APPLICABILITY_SQL.formatted(values);
	}

	/**
	 * Returns the production query reconstructing one Question's top-level database
	 * row.
	 *
	 * @return SQL selecting one Question by persistent ID
	 */
	public static String byId() {
		return BY_ID_SQL;
	}
}
