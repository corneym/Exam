-- Version 31: persistent contributor capture/classification boundaries.
-- These original definitions are unchanged; version 33 later changes only
-- the Questions INSERT and UPDATE guards.

-- questions
CREATE TRIGGER trg_contributor_scope_questions_insert
BEFORE INSERT ON questions
WHEN (
    SELECT assignment_id
    FROM contributor_assignment_selection
    WHERE singleton_id = 1
) IS NOT NULL
AND NOT (EXISTS (
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
))
BEGIN
    SELECT RAISE(
        ABORT,
        'Capture or classification is outside selected assignment'
    );
END;

CREATE TRIGGER trg_contributor_scope_questions_update
BEFORE UPDATE ON questions
WHEN (
    SELECT assignment_id
    FROM contributor_assignment_selection
    WHERE singleton_id = 1
) IS NOT NULL
AND NOT ((EXISTS (
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
)))
BEGIN
    SELECT RAISE(
        ABORT,
        'Capture or classification is outside selected assignment'
    );
END;

CREATE TRIGGER trg_contributor_scope_questions_delete
BEFORE DELETE ON questions
WHEN (
    SELECT assignment_id
    FROM contributor_assignment_selection
    WHERE singleton_id = 1
) IS NOT NULL
AND NOT ((NOT EXISTS (
    SELECT 1
    FROM exam_booklets parent
    WHERE parent.id = OLD.booklet_id
)
) OR (EXISTS (
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
 AND (OLD.source_question_id IS NULL OR EXISTS (
    SELECT 1
    FROM source_questions source
    WHERE source.id = OLD.source_question_id
      AND source.booklet_id = OLD.booklet_id
)
) AND (OLD.shared_context_id IS NULL OR EXISTS (
    SELECT 1
    FROM shared_question_contexts context
    WHERE context.id = OLD.shared_context_id
      AND context.booklet_id = OLD.booklet_id
)
)))
BEGIN
    SELECT RAISE(
        ABORT,
        'Capture or classification is outside selected assignment'
    );
END;

-- question_regions
CREATE TRIGGER trg_contributor_scope_question_regions_insert
BEFORE INSERT ON question_regions
WHEN (
    SELECT assignment_id
    FROM contributor_assignment_selection
    WHERE singleton_id = 1
) IS NOT NULL
AND NOT (EXISTS (
    SELECT 1
    FROM questions question
    JOIN exam_booklets booklet
        ON booklet.id = question.booklet_id
    WHERE question.id = NEW.question_id
      AND booklet.exam_id IN (SELECT session.exam_local_id
FROM contributor_assignment_sessions session
JOIN contributor_assignment_selection selected
    ON selected.assignment_id = session.assignment_id
WHERE selected.singleton_id = 1
)
)
 AND EXISTS (
    SELECT 1
    FROM questions question
    WHERE question.id = NEW.question_id
      AND question.booklet_id = NEW.booklet_id
)
)
BEGIN
    SELECT RAISE(
        ABORT,
        'Capture or classification is outside selected assignment'
    );
END;

CREATE TRIGGER trg_contributor_scope_question_regions_update
BEFORE UPDATE ON question_regions
WHEN (
    SELECT assignment_id
    FROM contributor_assignment_selection
    WHERE singleton_id = 1
) IS NOT NULL
AND NOT ((EXISTS (
    SELECT 1
    FROM questions question
    JOIN exam_booklets booklet
        ON booklet.id = question.booklet_id
    WHERE question.id = OLD.question_id
      AND booklet.exam_id IN (SELECT session.exam_local_id
FROM contributor_assignment_sessions session
JOIN contributor_assignment_selection selected
    ON selected.assignment_id = session.assignment_id
WHERE selected.singleton_id = 1
)
)
 AND EXISTS (
    SELECT 1
    FROM questions question
    WHERE question.id = OLD.question_id
      AND question.booklet_id = OLD.booklet_id
)
) AND (EXISTS (
    SELECT 1
    FROM questions question
    JOIN exam_booklets booklet
        ON booklet.id = question.booklet_id
    WHERE question.id = NEW.question_id
      AND booklet.exam_id IN (SELECT session.exam_local_id
FROM contributor_assignment_sessions session
JOIN contributor_assignment_selection selected
    ON selected.assignment_id = session.assignment_id
WHERE selected.singleton_id = 1
)
)
 AND EXISTS (
    SELECT 1
    FROM questions question
    WHERE question.id = NEW.question_id
      AND question.booklet_id = NEW.booklet_id
)
))
BEGIN
    SELECT RAISE(
        ABORT,
        'Capture or classification is outside selected assignment'
    );
END;

CREATE TRIGGER trg_contributor_scope_question_regions_delete
BEFORE DELETE ON question_regions
WHEN (
    SELECT assignment_id
    FROM contributor_assignment_selection
    WHERE singleton_id = 1
) IS NOT NULL
AND NOT ((NOT EXISTS (
    SELECT 1
    FROM questions parent
    WHERE parent.id = OLD.question_id
)
) OR (EXISTS (
    SELECT 1
    FROM questions question
    JOIN exam_booklets booklet
        ON booklet.id = question.booklet_id
    WHERE question.id = OLD.question_id
      AND booklet.exam_id IN (SELECT session.exam_local_id
FROM contributor_assignment_sessions session
JOIN contributor_assignment_selection selected
    ON selected.assignment_id = session.assignment_id
WHERE selected.singleton_id = 1
)
)
 AND EXISTS (
    SELECT 1
    FROM questions question
    WHERE question.id = OLD.question_id
      AND question.booklet_id = OLD.booklet_id
)
))
BEGIN
    SELECT RAISE(
        ABORT,
        'Capture or classification is outside selected assignment'
    );
END;

-- question_images
CREATE TRIGGER trg_contributor_scope_question_images_insert
BEFORE INSERT ON question_images
WHEN (
    SELECT assignment_id
    FROM contributor_assignment_selection
    WHERE singleton_id = 1
) IS NOT NULL
AND NOT (EXISTS (
    SELECT 1
    FROM questions question
    JOIN exam_booklets booklet
        ON booklet.id = question.booklet_id
    WHERE question.id = NEW.question_id
      AND booklet.exam_id IN (SELECT session.exam_local_id
FROM contributor_assignment_sessions session
JOIN contributor_assignment_selection selected
    ON selected.assignment_id = session.assignment_id
WHERE selected.singleton_id = 1
)
)
)
BEGIN
    SELECT RAISE(
        ABORT,
        'Capture or classification is outside selected assignment'
    );
END;

CREATE TRIGGER trg_contributor_scope_question_images_update
BEFORE UPDATE ON question_images
WHEN (
    SELECT assignment_id
    FROM contributor_assignment_selection
    WHERE singleton_id = 1
) IS NOT NULL
AND NOT ((EXISTS (
    SELECT 1
    FROM questions question
    JOIN exam_booklets booklet
        ON booklet.id = question.booklet_id
    WHERE question.id = OLD.question_id
      AND booklet.exam_id IN (SELECT session.exam_local_id
FROM contributor_assignment_sessions session
JOIN contributor_assignment_selection selected
    ON selected.assignment_id = session.assignment_id
WHERE selected.singleton_id = 1
)
)
) AND (EXISTS (
    SELECT 1
    FROM questions question
    JOIN exam_booklets booklet
        ON booklet.id = question.booklet_id
    WHERE question.id = NEW.question_id
      AND booklet.exam_id IN (SELECT session.exam_local_id
FROM contributor_assignment_sessions session
JOIN contributor_assignment_selection selected
    ON selected.assignment_id = session.assignment_id
WHERE selected.singleton_id = 1
)
)
))
BEGIN
    SELECT RAISE(
        ABORT,
        'Capture or classification is outside selected assignment'
    );
END;

CREATE TRIGGER trg_contributor_scope_question_images_delete
BEFORE DELETE ON question_images
WHEN (
    SELECT assignment_id
    FROM contributor_assignment_selection
    WHERE singleton_id = 1
) IS NOT NULL
AND NOT ((NOT EXISTS (
    SELECT 1
    FROM questions parent
    WHERE parent.id = OLD.question_id
)
) OR (EXISTS (
    SELECT 1
    FROM questions question
    JOIN exam_booklets booklet
        ON booklet.id = question.booklet_id
    WHERE question.id = OLD.question_id
      AND booklet.exam_id IN (SELECT session.exam_local_id
FROM contributor_assignment_sessions session
JOIN contributor_assignment_selection selected
    ON selected.assignment_id = session.assignment_id
WHERE selected.singleton_id = 1
)
)
))
BEGIN
    SELECT RAISE(
        ABORT,
        'Capture or classification is outside selected assignment'
    );
END;

-- question_content_parts
CREATE TRIGGER trg_contributor_scope_question_content_parts_insert
BEFORE INSERT ON question_content_parts
WHEN (
    SELECT assignment_id
    FROM contributor_assignment_selection
    WHERE singleton_id = 1
) IS NOT NULL
AND NOT (EXISTS (
    SELECT 1
    FROM questions question
    JOIN exam_booklets booklet
        ON booklet.id = question.booklet_id
    WHERE question.id = NEW.question_id
      AND booklet.exam_id IN (SELECT session.exam_local_id
FROM contributor_assignment_sessions session
JOIN contributor_assignment_selection selected
    ON selected.assignment_id = session.assignment_id
WHERE selected.singleton_id = 1
)
)
)
BEGIN
    SELECT RAISE(
        ABORT,
        'Capture or classification is outside selected assignment'
    );
END;

CREATE TRIGGER trg_contributor_scope_question_content_parts_update
BEFORE UPDATE ON question_content_parts
WHEN (
    SELECT assignment_id
    FROM contributor_assignment_selection
    WHERE singleton_id = 1
) IS NOT NULL
AND NOT ((EXISTS (
    SELECT 1
    FROM questions question
    JOIN exam_booklets booklet
        ON booklet.id = question.booklet_id
    WHERE question.id = OLD.question_id
      AND booklet.exam_id IN (SELECT session.exam_local_id
FROM contributor_assignment_sessions session
JOIN contributor_assignment_selection selected
    ON selected.assignment_id = session.assignment_id
WHERE selected.singleton_id = 1
)
)
) AND (EXISTS (
    SELECT 1
    FROM questions question
    JOIN exam_booklets booklet
        ON booklet.id = question.booklet_id
    WHERE question.id = NEW.question_id
      AND booklet.exam_id IN (SELECT session.exam_local_id
FROM contributor_assignment_sessions session
JOIN contributor_assignment_selection selected
    ON selected.assignment_id = session.assignment_id
WHERE selected.singleton_id = 1
)
)
))
BEGIN
    SELECT RAISE(
        ABORT,
        'Capture or classification is outside selected assignment'
    );
END;

CREATE TRIGGER trg_contributor_scope_question_content_parts_delete
BEFORE DELETE ON question_content_parts
WHEN (
    SELECT assignment_id
    FROM contributor_assignment_selection
    WHERE singleton_id = 1
) IS NOT NULL
AND NOT ((NOT EXISTS (
    SELECT 1
    FROM questions parent
    WHERE parent.id = OLD.question_id
)
) OR (EXISTS (
    SELECT 1
    FROM questions question
    JOIN exam_booklets booklet
        ON booklet.id = question.booklet_id
    WHERE question.id = OLD.question_id
      AND booklet.exam_id IN (SELECT session.exam_local_id
FROM contributor_assignment_sessions session
JOIN contributor_assignment_selection selected
    ON selected.assignment_id = session.assignment_id
WHERE selected.singleton_id = 1
)
)
))
BEGIN
    SELECT RAISE(
        ABORT,
        'Capture or classification is outside selected assignment'
    );
END;

-- question_output_exclusions
CREATE TRIGGER trg_contributor_scope_question_output_exclusions_insert
BEFORE INSERT ON question_output_exclusions
WHEN (
    SELECT assignment_id
    FROM contributor_assignment_selection
    WHERE singleton_id = 1
) IS NOT NULL
AND NOT (EXISTS (
    SELECT 1
    FROM questions question
    JOIN exam_booklets booklet
        ON booklet.id = question.booklet_id
    WHERE question.id = NEW.question_id
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
    WHERE node.id = NEW.current_curriculum_node_id
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
)
BEGIN
    SELECT RAISE(
        ABORT,
        'Capture or classification is outside selected assignment'
    );
END;

CREATE TRIGGER trg_contributor_scope_question_output_exclusions_update
BEFORE UPDATE ON question_output_exclusions
WHEN (
    SELECT assignment_id
    FROM contributor_assignment_selection
    WHERE singleton_id = 1
) IS NOT NULL
AND NOT ((EXISTS (
    SELECT 1
    FROM questions question
    JOIN exam_booklets booklet
        ON booklet.id = question.booklet_id
    WHERE question.id = OLD.question_id
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
    WHERE node.id = OLD.current_curriculum_node_id
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
    FROM questions question
    JOIN exam_booklets booklet
        ON booklet.id = question.booklet_id
    WHERE question.id = NEW.question_id
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
    WHERE node.id = NEW.current_curriculum_node_id
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
))
BEGIN
    SELECT RAISE(
        ABORT,
        'Capture or classification is outside selected assignment'
    );
END;

CREATE TRIGGER trg_contributor_scope_question_output_exclusions_delete
BEFORE DELETE ON question_output_exclusions
WHEN (
    SELECT assignment_id
    FROM contributor_assignment_selection
    WHERE singleton_id = 1
) IS NOT NULL
AND NOT ((NOT EXISTS (
    SELECT 1
    FROM questions parent
    WHERE parent.id = OLD.question_id
)
) OR (EXISTS (
    SELECT 1
    FROM questions question
    JOIN exam_booklets booklet
        ON booklet.id = question.booklet_id
    WHERE question.id = OLD.question_id
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
    WHERE node.id = OLD.current_curriculum_node_id
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
))
BEGIN
    SELECT RAISE(
        ABORT,
        'Capture or classification is outside selected assignment'
    );
END;

-- source_questions
CREATE TRIGGER trg_contributor_scope_source_questions_insert
BEFORE INSERT ON source_questions
WHEN (
    SELECT assignment_id
    FROM contributor_assignment_selection
    WHERE singleton_id = 1
) IS NOT NULL
AND NOT (EXISTS (
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
)
BEGIN
    SELECT RAISE(
        ABORT,
        'Capture or classification is outside selected assignment'
    );
END;

CREATE TRIGGER trg_contributor_scope_source_questions_update
BEFORE UPDATE ON source_questions
WHEN (
    SELECT assignment_id
    FROM contributor_assignment_selection
    WHERE singleton_id = 1
) IS NOT NULL
AND NOT ((EXISTS (
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
))
BEGIN
    SELECT RAISE(
        ABORT,
        'Capture or classification is outside selected assignment'
    );
END;

CREATE TRIGGER trg_contributor_scope_source_questions_delete
BEFORE DELETE ON source_questions
WHEN (
    SELECT assignment_id
    FROM contributor_assignment_selection
    WHERE singleton_id = 1
) IS NOT NULL
AND NOT ((NOT EXISTS (
    SELECT 1
    FROM exam_booklets parent
    WHERE parent.id = OLD.booklet_id
)
) OR (EXISTS (
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
))
BEGIN
    SELECT RAISE(
        ABORT,
        'Capture or classification is outside selected assignment'
    );
END;

-- shared_question_contexts
CREATE TRIGGER trg_contributor_scope_shared_question_contexts_insert
BEFORE INSERT ON shared_question_contexts
WHEN (
    SELECT assignment_id
    FROM contributor_assignment_selection
    WHERE singleton_id = 1
) IS NOT NULL
AND NOT (EXISTS (
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
)
BEGIN
    SELECT RAISE(
        ABORT,
        'Capture or classification is outside selected assignment'
    );
END;

CREATE TRIGGER trg_contributor_scope_shared_question_contexts_update
BEFORE UPDATE ON shared_question_contexts
WHEN (
    SELECT assignment_id
    FROM contributor_assignment_selection
    WHERE singleton_id = 1
) IS NOT NULL
AND NOT ((EXISTS (
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
))
BEGIN
    SELECT RAISE(
        ABORT,
        'Capture or classification is outside selected assignment'
    );
END;

CREATE TRIGGER trg_contributor_scope_shared_question_contexts_delete
BEFORE DELETE ON shared_question_contexts
WHEN (
    SELECT assignment_id
    FROM contributor_assignment_selection
    WHERE singleton_id = 1
) IS NOT NULL
AND NOT ((NOT EXISTS (
    SELECT 1
    FROM exam_booklets parent
    WHERE parent.id = OLD.booklet_id
)
) OR (EXISTS (
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
))
BEGIN
    SELECT RAISE(
        ABORT,
        'Capture or classification is outside selected assignment'
    );
END;

-- shared_question_context_regions
CREATE TRIGGER trg_contributor_scope_shared_question_context_regions_insert
BEFORE INSERT ON shared_question_context_regions
WHEN (
    SELECT assignment_id
    FROM contributor_assignment_selection
    WHERE singleton_id = 1
) IS NOT NULL
AND NOT (EXISTS (
    SELECT 1
    FROM shared_question_contexts context
    JOIN exam_booklets booklet
        ON booklet.id = context.booklet_id
    WHERE context.id = NEW.shared_context_id
      AND booklet.exam_id IN (SELECT session.exam_local_id
FROM contributor_assignment_sessions session
JOIN contributor_assignment_selection selected
    ON selected.assignment_id = session.assignment_id
WHERE selected.singleton_id = 1
)
)
)
BEGIN
    SELECT RAISE(
        ABORT,
        'Capture or classification is outside selected assignment'
    );
END;

CREATE TRIGGER trg_contributor_scope_shared_question_context_regions_update
BEFORE UPDATE ON shared_question_context_regions
WHEN (
    SELECT assignment_id
    FROM contributor_assignment_selection
    WHERE singleton_id = 1
) IS NOT NULL
AND NOT ((EXISTS (
    SELECT 1
    FROM shared_question_contexts context
    JOIN exam_booklets booklet
        ON booklet.id = context.booklet_id
    WHERE context.id = OLD.shared_context_id
      AND booklet.exam_id IN (SELECT session.exam_local_id
FROM contributor_assignment_sessions session
JOIN contributor_assignment_selection selected
    ON selected.assignment_id = session.assignment_id
WHERE selected.singleton_id = 1
)
)
) AND (EXISTS (
    SELECT 1
    FROM shared_question_contexts context
    JOIN exam_booklets booklet
        ON booklet.id = context.booklet_id
    WHERE context.id = NEW.shared_context_id
      AND booklet.exam_id IN (SELECT session.exam_local_id
FROM contributor_assignment_sessions session
JOIN contributor_assignment_selection selected
    ON selected.assignment_id = session.assignment_id
WHERE selected.singleton_id = 1
)
)
))
BEGIN
    SELECT RAISE(
        ABORT,
        'Capture or classification is outside selected assignment'
    );
END;

CREATE TRIGGER trg_contributor_scope_shared_question_context_regions_delete
BEFORE DELETE ON shared_question_context_regions
WHEN (
    SELECT assignment_id
    FROM contributor_assignment_selection
    WHERE singleton_id = 1
) IS NOT NULL
AND NOT ((NOT EXISTS (
    SELECT 1
    FROM shared_question_contexts parent
    WHERE parent.id = OLD.shared_context_id
)
) OR (EXISTS (
    SELECT 1
    FROM shared_question_contexts context
    JOIN exam_booklets booklet
        ON booklet.id = context.booklet_id
    WHERE context.id = OLD.shared_context_id
      AND booklet.exam_id IN (SELECT session.exam_local_id
FROM contributor_assignment_sessions session
JOIN contributor_assignment_selection selected
    ON selected.assignment_id = session.assignment_id
WHERE selected.singleton_id = 1
)
)
))
BEGIN
    SELECT RAISE(
        ABORT,
        'Capture or classification is outside selected assignment'
    );
END;

-- shared_question_context_images
CREATE TRIGGER trg_contributor_scope_shared_question_context_images_insert
BEFORE INSERT ON shared_question_context_images
WHEN (
    SELECT assignment_id
    FROM contributor_assignment_selection
    WHERE singleton_id = 1
) IS NOT NULL
AND NOT (EXISTS (
    SELECT 1
    FROM shared_question_contexts context
    JOIN exam_booklets booklet
        ON booklet.id = context.booklet_id
    WHERE context.id = NEW.shared_context_id
      AND booklet.exam_id IN (SELECT session.exam_local_id
FROM contributor_assignment_sessions session
JOIN contributor_assignment_selection selected
    ON selected.assignment_id = session.assignment_id
WHERE selected.singleton_id = 1
)
)
)
BEGIN
    SELECT RAISE(
        ABORT,
        'Capture or classification is outside selected assignment'
    );
END;

CREATE TRIGGER trg_contributor_scope_shared_question_context_images_update
BEFORE UPDATE ON shared_question_context_images
WHEN (
    SELECT assignment_id
    FROM contributor_assignment_selection
    WHERE singleton_id = 1
) IS NOT NULL
AND NOT ((EXISTS (
    SELECT 1
    FROM shared_question_contexts context
    JOIN exam_booklets booklet
        ON booklet.id = context.booklet_id
    WHERE context.id = OLD.shared_context_id
      AND booklet.exam_id IN (SELECT session.exam_local_id
FROM contributor_assignment_sessions session
JOIN contributor_assignment_selection selected
    ON selected.assignment_id = session.assignment_id
WHERE selected.singleton_id = 1
)
)
) AND (EXISTS (
    SELECT 1
    FROM shared_question_contexts context
    JOIN exam_booklets booklet
        ON booklet.id = context.booklet_id
    WHERE context.id = NEW.shared_context_id
      AND booklet.exam_id IN (SELECT session.exam_local_id
FROM contributor_assignment_sessions session
JOIN contributor_assignment_selection selected
    ON selected.assignment_id = session.assignment_id
WHERE selected.singleton_id = 1
)
)
))
BEGIN
    SELECT RAISE(
        ABORT,
        'Capture or classification is outside selected assignment'
    );
END;

CREATE TRIGGER trg_contributor_scope_shared_question_context_images_delete
BEFORE DELETE ON shared_question_context_images
WHEN (
    SELECT assignment_id
    FROM contributor_assignment_selection
    WHERE singleton_id = 1
) IS NOT NULL
AND NOT ((NOT EXISTS (
    SELECT 1
    FROM shared_question_contexts parent
    WHERE parent.id = OLD.shared_context_id
)
) OR (EXISTS (
    SELECT 1
    FROM shared_question_contexts context
    JOIN exam_booklets booklet
        ON booklet.id = context.booklet_id
    WHERE context.id = OLD.shared_context_id
      AND booklet.exam_id IN (SELECT session.exam_local_id
FROM contributor_assignment_sessions session
JOIN contributor_assignment_selection selected
    ON selected.assignment_id = session.assignment_id
WHERE selected.singleton_id = 1
)
)
))
BEGIN
    SELECT RAISE(
        ABORT,
        'Capture or classification is outside selected assignment'
    );
END;

-- shared_question_context_content_parts
CREATE TRIGGER trg_contributor_scope_shared_question_context_content_parts_insert
BEFORE INSERT ON shared_question_context_content_parts
WHEN (
    SELECT assignment_id
    FROM contributor_assignment_selection
    WHERE singleton_id = 1
) IS NOT NULL
AND NOT (EXISTS (
    SELECT 1
    FROM shared_question_contexts context
    JOIN exam_booklets booklet
        ON booklet.id = context.booklet_id
    WHERE context.id = NEW.shared_context_id
      AND booklet.exam_id IN (SELECT session.exam_local_id
FROM contributor_assignment_sessions session
JOIN contributor_assignment_selection selected
    ON selected.assignment_id = session.assignment_id
WHERE selected.singleton_id = 1
)
)
)
BEGIN
    SELECT RAISE(
        ABORT,
        'Capture or classification is outside selected assignment'
    );
END;

CREATE TRIGGER trg_contributor_scope_shared_question_context_content_parts_update
BEFORE UPDATE ON shared_question_context_content_parts
WHEN (
    SELECT assignment_id
    FROM contributor_assignment_selection
    WHERE singleton_id = 1
) IS NOT NULL
AND NOT ((EXISTS (
    SELECT 1
    FROM shared_question_contexts context
    JOIN exam_booklets booklet
        ON booklet.id = context.booklet_id
    WHERE context.id = OLD.shared_context_id
      AND booklet.exam_id IN (SELECT session.exam_local_id
FROM contributor_assignment_sessions session
JOIN contributor_assignment_selection selected
    ON selected.assignment_id = session.assignment_id
WHERE selected.singleton_id = 1
)
)
) AND (EXISTS (
    SELECT 1
    FROM shared_question_contexts context
    JOIN exam_booklets booklet
        ON booklet.id = context.booklet_id
    WHERE context.id = NEW.shared_context_id
      AND booklet.exam_id IN (SELECT session.exam_local_id
FROM contributor_assignment_sessions session
JOIN contributor_assignment_selection selected
    ON selected.assignment_id = session.assignment_id
WHERE selected.singleton_id = 1
)
)
))
BEGIN
    SELECT RAISE(
        ABORT,
        'Capture or classification is outside selected assignment'
    );
END;

CREATE TRIGGER trg_contributor_scope_shared_question_context_content_parts_delete
BEFORE DELETE ON shared_question_context_content_parts
WHEN (
    SELECT assignment_id
    FROM contributor_assignment_selection
    WHERE singleton_id = 1
) IS NOT NULL
AND NOT ((NOT EXISTS (
    SELECT 1
    FROM shared_question_contexts parent
    WHERE parent.id = OLD.shared_context_id
)
) OR (EXISTS (
    SELECT 1
    FROM shared_question_contexts context
    JOIN exam_booklets booklet
        ON booklet.id = context.booklet_id
    WHERE context.id = OLD.shared_context_id
      AND booklet.exam_id IN (SELECT session.exam_local_id
FROM contributor_assignment_sessions session
JOIN contributor_assignment_selection selected
    ON selected.assignment_id = session.assignment_id
WHERE selected.singleton_id = 1
)
)
))
BEGIN
    SELECT RAISE(
        ABORT,
        'Capture or classification is outside selected assignment'
    );
END;

-- answers
CREATE TRIGGER trg_contributor_scope_answers_insert
BEFORE INSERT ON answers
WHEN (
    SELECT assignment_id
    FROM contributor_assignment_selection
    WHERE singleton_id = 1
) IS NOT NULL
AND NOT (EXISTS (
    SELECT 1
    FROM questions question
    JOIN exam_booklets booklet
        ON booklet.id = question.booklet_id
    WHERE question.id = NEW.question_id
      AND booklet.exam_id IN (SELECT session.exam_local_id
FROM contributor_assignment_sessions session
JOIN contributor_assignment_selection selected
    ON selected.assignment_id = session.assignment_id
WHERE selected.singleton_id = 1
)
)
)
BEGIN
    SELECT RAISE(
        ABORT,
        'Capture or classification is outside selected assignment'
    );
END;

CREATE TRIGGER trg_contributor_scope_answers_update
BEFORE UPDATE ON answers
WHEN (
    SELECT assignment_id
    FROM contributor_assignment_selection
    WHERE singleton_id = 1
) IS NOT NULL
AND NOT ((EXISTS (
    SELECT 1
    FROM questions question
    JOIN exam_booklets booklet
        ON booklet.id = question.booklet_id
    WHERE question.id = OLD.question_id
      AND booklet.exam_id IN (SELECT session.exam_local_id
FROM contributor_assignment_sessions session
JOIN contributor_assignment_selection selected
    ON selected.assignment_id = session.assignment_id
WHERE selected.singleton_id = 1
)
)
) AND (EXISTS (
    SELECT 1
    FROM questions question
    JOIN exam_booklets booklet
        ON booklet.id = question.booklet_id
    WHERE question.id = NEW.question_id
      AND booklet.exam_id IN (SELECT session.exam_local_id
FROM contributor_assignment_sessions session
JOIN contributor_assignment_selection selected
    ON selected.assignment_id = session.assignment_id
WHERE selected.singleton_id = 1
)
)
))
BEGIN
    SELECT RAISE(
        ABORT,
        'Capture or classification is outside selected assignment'
    );
END;

CREATE TRIGGER trg_contributor_scope_answers_delete
BEFORE DELETE ON answers
WHEN (
    SELECT assignment_id
    FROM contributor_assignment_selection
    WHERE singleton_id = 1
) IS NOT NULL
AND NOT ((NOT EXISTS (
    SELECT 1
    FROM questions parent
    WHERE parent.id = OLD.question_id
)
) OR (EXISTS (
    SELECT 1
    FROM questions question
    JOIN exam_booklets booklet
        ON booklet.id = question.booklet_id
    WHERE question.id = OLD.question_id
      AND booklet.exam_id IN (SELECT session.exam_local_id
FROM contributor_assignment_sessions session
JOIN contributor_assignment_selection selected
    ON selected.assignment_id = session.assignment_id
WHERE selected.singleton_id = 1
)
)
))
BEGIN
    SELECT RAISE(
        ABORT,
        'Capture or classification is outside selected assignment'
    );
END;

-- answer_regions
CREATE TRIGGER trg_contributor_scope_answer_regions_insert
BEFORE INSERT ON answer_regions
WHEN (
    SELECT assignment_id
    FROM contributor_assignment_selection
    WHERE singleton_id = 1
) IS NOT NULL
AND NOT (EXISTS (
    SELECT 1
    FROM answers answer
    JOIN questions question
        ON question.id = answer.question_id
    JOIN exam_booklets booklet
        ON booklet.id = question.booklet_id
    WHERE answer.id = NEW.answer_id
      AND booklet.exam_id IN (SELECT session.exam_local_id
FROM contributor_assignment_sessions session
JOIN contributor_assignment_selection selected
    ON selected.assignment_id = session.assignment_id
WHERE selected.singleton_id = 1
)
)
 AND EXISTS (
    SELECT 1
    FROM answers answer
    JOIN questions question
        ON question.id = answer.question_id
    JOIN exam_booklets booklet
        ON booklet.id = question.booklet_id
    WHERE answer.id = NEW.answer_id
      AND booklet.answer_file_id =
          NEW.answer_file_id
)
)
BEGIN
    SELECT RAISE(
        ABORT,
        'Capture or classification is outside selected assignment'
    );
END;

CREATE TRIGGER trg_contributor_scope_answer_regions_update
BEFORE UPDATE ON answer_regions
WHEN (
    SELECT assignment_id
    FROM contributor_assignment_selection
    WHERE singleton_id = 1
) IS NOT NULL
AND NOT ((EXISTS (
    SELECT 1
    FROM answers answer
    JOIN questions question
        ON question.id = answer.question_id
    JOIN exam_booklets booklet
        ON booklet.id = question.booklet_id
    WHERE answer.id = OLD.answer_id
      AND booklet.exam_id IN (SELECT session.exam_local_id
FROM contributor_assignment_sessions session
JOIN contributor_assignment_selection selected
    ON selected.assignment_id = session.assignment_id
WHERE selected.singleton_id = 1
)
)
 AND EXISTS (
    SELECT 1
    FROM answers answer
    JOIN questions question
        ON question.id = answer.question_id
    JOIN exam_booklets booklet
        ON booklet.id = question.booklet_id
    WHERE answer.id = OLD.answer_id
      AND booklet.answer_file_id =
          OLD.answer_file_id
)
) AND (EXISTS (
    SELECT 1
    FROM answers answer
    JOIN questions question
        ON question.id = answer.question_id
    JOIN exam_booklets booklet
        ON booklet.id = question.booklet_id
    WHERE answer.id = NEW.answer_id
      AND booklet.exam_id IN (SELECT session.exam_local_id
FROM contributor_assignment_sessions session
JOIN contributor_assignment_selection selected
    ON selected.assignment_id = session.assignment_id
WHERE selected.singleton_id = 1
)
)
 AND EXISTS (
    SELECT 1
    FROM answers answer
    JOIN questions question
        ON question.id = answer.question_id
    JOIN exam_booklets booklet
        ON booklet.id = question.booklet_id
    WHERE answer.id = NEW.answer_id
      AND booklet.answer_file_id =
          NEW.answer_file_id
)
))
BEGIN
    SELECT RAISE(
        ABORT,
        'Capture or classification is outside selected assignment'
    );
END;

CREATE TRIGGER trg_contributor_scope_answer_regions_delete
BEFORE DELETE ON answer_regions
WHEN (
    SELECT assignment_id
    FROM contributor_assignment_selection
    WHERE singleton_id = 1
) IS NOT NULL
AND NOT ((NOT EXISTS (
    SELECT 1
    FROM answers parent
    WHERE parent.id = OLD.answer_id
)
) OR (EXISTS (
    SELECT 1
    FROM answers answer
    JOIN questions question
        ON question.id = answer.question_id
    JOIN exam_booklets booklet
        ON booklet.id = question.booklet_id
    WHERE answer.id = OLD.answer_id
      AND booklet.exam_id IN (SELECT session.exam_local_id
FROM contributor_assignment_sessions session
JOIN contributor_assignment_selection selected
    ON selected.assignment_id = session.assignment_id
WHERE selected.singleton_id = 1
)
)
 AND EXISTS (
    SELECT 1
    FROM answers answer
    JOIN questions question
        ON question.id = answer.question_id
    JOIN exam_booklets booklet
        ON booklet.id = question.booklet_id
    WHERE answer.id = OLD.answer_id
      AND booklet.answer_file_id =
          OLD.answer_file_id
)
))
BEGIN
    SELECT RAISE(
        ABORT,
        'Capture or classification is outside selected assignment'
    );
END;

-- booklet_applicability_reviews
CREATE TRIGGER trg_contributor_scope_booklet_applicability_reviews_insert
BEFORE INSERT ON booklet_applicability_reviews
WHEN (
    SELECT assignment_id
    FROM contributor_assignment_selection
    WHERE singleton_id = 1
) IS NOT NULL
AND NOT (EXISTS (
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
)
BEGIN
    SELECT RAISE(
        ABORT,
        'Capture or classification is outside selected assignment'
    );
END;

CREATE TRIGGER trg_contributor_scope_booklet_applicability_reviews_update
BEFORE UPDATE ON booklet_applicability_reviews
WHEN (
    SELECT assignment_id
    FROM contributor_assignment_selection
    WHERE singleton_id = 1
) IS NOT NULL
AND NOT ((EXISTS (
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
))
BEGIN
    SELECT RAISE(
        ABORT,
        'Capture or classification is outside selected assignment'
    );
END;

CREATE TRIGGER trg_contributor_scope_booklet_applicability_reviews_delete
BEFORE DELETE ON booklet_applicability_reviews
WHEN (
    SELECT assignment_id
    FROM contributor_assignment_selection
    WHERE singleton_id = 1
) IS NOT NULL
AND NOT ((NOT EXISTS (
    SELECT 1
    FROM exam_booklets parent
    WHERE parent.id = OLD.booklet_id
)
) OR (EXISTS (
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
))
BEGIN
    SELECT RAISE(
        ABORT,
        'Capture or classification is outside selected assignment'
    );
END;

UPDATE schema_version
SET version = 31
WHERE version = 30;
