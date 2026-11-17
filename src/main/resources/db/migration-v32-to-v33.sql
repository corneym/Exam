-- Version 33: classifiable contributor Question INSERT and UPDATE.
-- The original v31 DELETE trigger and all other scope guards
-- remain unchanged.

DROP TRIGGER trg_contributor_scope_questions_insert;

CREATE TRIGGER trg_contributor_scope_questions_insert
BEFORE INSERT ON questions
WHEN (
    SELECT assignment_id
    FROM contributor_assignment_selection
    WHERE singleton_id = 1
) IS NOT NULL
AND NOT ((EXISTS (
    SELECT 1
    FROM exam_booklets booklet
    WHERE booklet.id = NEW.booklet_id
      AND booklet.exam_id IN (SELECT session.exam_local_id
FROM contributor_assignment_sessions session
JOIN contributor_assignment_selection selected
    ON selected.assignment_id = session.assignment_id
WHERE selected.singleton_id = 1
)
)
 AND EXISTS (
    SELECT 1
    FROM curriculum_nodes node
    WHERE node.id = NEW.classification_node_id
      AND node.syllabus_version_id IN (SELECT session.current_syllabus_local_id
FROM contributor_assignment_sessions session
JOIN contributor_assignment_selection selected
    ON selected.assignment_id = session.assignment_id
WHERE selected.singleton_id = 1

UNION

SELECT session.legacy_syllabus_local_id
FROM contributor_assignment_sessions session
JOIN contributor_assignment_selection selected
    ON selected.assignment_id = session.assignment_id
WHERE selected.singleton_id = 1
  AND session.legacy_syllabus_local_id IS NOT NULL
)
)
 AND (NEW.source_question_id IS NULL OR EXISTS (
    SELECT 1
    FROM source_questions source
    WHERE source.id = NEW.source_question_id
      AND source.booklet_id = NEW.booklet_id
)
) AND (NEW.shared_context_id IS NULL OR EXISTS (
    SELECT 1
    FROM shared_question_contexts context
    WHERE context.id = NEW.shared_context_id
      AND context.booklet_id = NEW.booklet_id
)
)) AND (EXISTS (
    SELECT 1
    FROM curriculum_nodes node
    WHERE node.id = NEW.classification_node_id
      AND node.curriculum_level IN (
          'SUBTOPIC', 'DESCRIPTOR'
      )
)
))
BEGIN
    SELECT RAISE(
        ABORT,
        'Capture or classification is outside selected assignment'
    );
END;

DROP TRIGGER trg_contributor_scope_questions_update;

CREATE TRIGGER trg_contributor_scope_questions_update
BEFORE UPDATE ON questions
WHEN (
    SELECT assignment_id
    FROM contributor_assignment_selection
    WHERE singleton_id = 1
) IS NOT NULL
AND NOT (((EXISTS (
    SELECT 1
    FROM exam_booklets booklet
    WHERE booklet.id = OLD.booklet_id
      AND booklet.exam_id IN (SELECT session.exam_local_id
FROM contributor_assignment_sessions session
JOIN contributor_assignment_selection selected
    ON selected.assignment_id = session.assignment_id
WHERE selected.singleton_id = 1
)
)
 AND EXISTS (
    SELECT 1
    FROM curriculum_nodes node
    WHERE node.id = OLD.classification_node_id
      AND node.syllabus_version_id IN (SELECT session.current_syllabus_local_id
FROM contributor_assignment_sessions session
JOIN contributor_assignment_selection selected
    ON selected.assignment_id = session.assignment_id
WHERE selected.singleton_id = 1

UNION

SELECT session.legacy_syllabus_local_id
FROM contributor_assignment_sessions session
JOIN contributor_assignment_selection selected
    ON selected.assignment_id = session.assignment_id
WHERE selected.singleton_id = 1
  AND session.legacy_syllabus_local_id IS NOT NULL
)
)
) AND (EXISTS (
    SELECT 1
    FROM exam_booklets booklet
    WHERE booklet.id = NEW.booklet_id
      AND booklet.exam_id IN (SELECT session.exam_local_id
FROM contributor_assignment_sessions session
JOIN contributor_assignment_selection selected
    ON selected.assignment_id = session.assignment_id
WHERE selected.singleton_id = 1
)
)
 AND EXISTS (
    SELECT 1
    FROM curriculum_nodes node
    WHERE node.id = NEW.classification_node_id
      AND node.syllabus_version_id IN (SELECT session.current_syllabus_local_id
FROM contributor_assignment_sessions session
JOIN contributor_assignment_selection selected
    ON selected.assignment_id = session.assignment_id
WHERE selected.singleton_id = 1

UNION

SELECT session.legacy_syllabus_local_id
FROM contributor_assignment_sessions session
JOIN contributor_assignment_selection selected
    ON selected.assignment_id = session.assignment_id
WHERE selected.singleton_id = 1
  AND session.legacy_syllabus_local_id IS NOT NULL
)
)
 AND (NEW.source_question_id IS NULL OR EXISTS (
    SELECT 1
    FROM source_questions source
    WHERE source.id = NEW.source_question_id
      AND source.booklet_id = NEW.booklet_id
)
) AND (NEW.shared_context_id IS NULL OR EXISTS (
    SELECT 1
    FROM shared_question_contexts context
    WHERE context.id = NEW.shared_context_id
      AND context.booklet_id = NEW.booklet_id
)
))) AND (NEW.classification_node_id = OLD.classification_node_id
OR (EXISTS (
    SELECT 1
    FROM curriculum_nodes node
    WHERE node.id = NEW.classification_node_id
      AND node.curriculum_level IN (
          'SUBTOPIC', 'DESCRIPTOR'
      )
)
)
))
BEGIN
    SELECT RAISE(
        ABORT,
        'Capture or classification is outside selected assignment'
    );
END;

UPDATE schema_version
SET version = 33
WHERE version = 32;
