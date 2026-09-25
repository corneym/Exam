-- Remember one shared context that should automatically apply to the next
-- independent MCQ captured from this booklet. NULL means that no continuation
-- is pending.
ALTER TABLE exam_booklets
ADD COLUMN pending_mcq_shared_context_id INTEGER
    REFERENCES shared_question_contexts(id);

-- Existing databases have no evidence that any shared context should continue
-- to another independent MCQ, so every migrated booklet starts with NULL.
UPDATE schema_version
SET version = 11;
