-- Version 32: permit pending MCQ Shared Context continuation
-- inside the contributor's assigned Exam.
--
-- Replaces the blanket Booklet UPDATE guard with a column-specific
-- structural guard and a narrowly scoped continuation guard.
-- All operations execute inside the migration transaction.

DROP TRIGGER trg_contributor_structure_exam_booklets_update;

CREATE TRIGGER trg_contributor_structure_exam_booklets_update
BEFORE UPDATE OF
    id, exam_id, source_document_id, booklet_name,
    question_format, answer_file_id,
    expected_question_count, legacy_paper_code
ON exam_booklets
WHEN (
    SELECT assignment_id
    FROM contributor_assignment_selection
    WHERE singleton_id = 1
    ) IS NOT NULL
BEGIN
    SELECT RAISE(
        ABORT,
        'Coordinator-owned structure is read-only in assignment mode'
    );
END;

CREATE TRIGGER trg_contributor_structure_exam_booklets_pending_mcq_update
BEFORE UPDATE OF pending_mcq_shared_context_id
ON exam_booklets
WHEN (
    SELECT assignment_id
    FROM contributor_assignment_selection
    WHERE singleton_id = 1
    ) IS NOT NULL
AND NOT (
    EXISTS (
        SELECT 1
        FROM contributor_assignment_sessions session
        JOIN contributor_assignment_selection selected
            ON selected.assignment_id = session.assignment_id
        WHERE selected.singleton_id = 1
          AND session.exam_local_id = OLD.exam_id
    )
    AND (
        NEW.pending_mcq_shared_context_id IS NULL
        OR EXISTS (
            SELECT 1
            FROM shared_question_contexts context
            WHERE context.id = NEW.pending_mcq_shared_context_id
              AND context.booklet_id = OLD.id
        )
    )
)
BEGIN
    SELECT RAISE(
        ABORT,
        'Coordinator-owned structure is read-only in assignment mode'
    );
END;

UPDATE schema_version
SET version = 32
WHERE version = 31;
