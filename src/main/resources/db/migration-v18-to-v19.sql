-- Version 19 records whether each AnswerFile contains explanatory answer
-- material in addition to the answers themselves.
--
-- Existing AnswerFiles cannot be classified reliably by migration, so they
-- deliberately default to false until reviewed by the user.

ALTER TABLE answer_files
ADD COLUMN contains_answer_explanations INTEGER NOT NULL DEFAULT 0
    CHECK (contains_answer_explanations IN (0, 1));

UPDATE schema_version
SET version = 19
WHERE version = 18;
