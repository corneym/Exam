-- Version 18 adds Exam-level planning expectations for source assets that may
-- not yet be available.
--
-- These values are deliberately separate from ExamBooklet and AnswerFile rows.
-- Planning metadata must not manufacture authoritative source assets merely to
-- represent something the user expects to obtain later.

ALTER TABLE exams
ADD COLUMN expected_question_booklet_count INTEGER
    CHECK (
        expected_question_booklet_count IS NULL
        OR expected_question_booklet_count > 0
    );

ALTER TABLE exams
ADD COLUMN expected_answer_file_count INTEGER
    CHECK (
        expected_answer_file_count IS NULL
        OR expected_answer_file_count >= 0
    );

-- Existing Exams have no authoritative asset-count expectations, so migration
-- retains NULL rather than inferring counts from currently available assets.

UPDATE schema_version
SET version = 18
WHERE version = 17;
