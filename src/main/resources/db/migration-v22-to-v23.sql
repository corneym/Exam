-- Schema v23: keep reviews when a booklet receives only a legacy Paper
-- identity edit. Replace the original broad UPDATE invalidation trigger
-- inside the existing migration transaction; other rules are unchanged.
-- Preserve all existing review confirmations.

DROP TRIGGER app_review_exam_booklets_update;

CREATE TRIGGER app_review_exam_booklets_update
AFTER UPDATE ON exam_booklets
WHEN OLD.exam_id IS NOT NEW.exam_id
OR OLD.source_document_id IS NOT NEW.source_document_id
OR OLD.question_format IS NOT NEW.question_format
OR OLD.answer_file_id IS NOT NEW.answer_file_id
OR OLD.pending_mcq_shared_context_id IS NOT NEW.pending_mcq_shared_context_id
OR OLD.expected_question_count IS NOT NEW.expected_question_count
BEGIN
    DELETE FROM booklet_applicability_reviews
    WHERE booklet_id IN (
        SELECT OLD.id
        UNION
        SELECT NEW.id
    );
END;

UPDATE schema_version
SET version = 23
WHERE version = 22;
