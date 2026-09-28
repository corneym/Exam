-- Version 15 introduces user-declared Exam capture state and the expected
-- top-level Question count for real Question booklets.
--
-- Existing Exams predate explicit completion, so they remain ACTIVE until the
-- user deliberately marks them complete.
ALTER TABLE exams
ADD COLUMN capture_state TEXT NOT NULL DEFAULT 'ACTIVE'
    CHECK (
        capture_state IN (
            'ACTIVE',
            'COMPLETE'
        )
    );

-- Existing booklets predate expected-count metadata. NULL therefore means that
-- no authoritative expectation has yet been recorded. Multipart Question parts
-- do not increase this count because it represents top-level numbered Questions.
ALTER TABLE exam_booklets
ADD COLUMN expected_question_count INTEGER
    CHECK (
        expected_question_count IS NULL
        OR expected_question_count > 0
    );

UPDATE schema_version
SET version = 15;
