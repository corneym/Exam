-- Existing booklets have no reliable stored information about their question
-- format, so preserve that uncertainty rather than inferring from booklet names.
ALTER TABLE exam_booklets
ADD COLUMN question_format TEXT NOT NULL DEFAULT 'UNSPECIFIED'
    CHECK (
        question_format IN (
            'MULTIPLE_CHOICE',
            'WRITTEN_RESPONSE',
            'MIXED',
            'UNSPECIFIED'
        )
    );

UPDATE schema_version
SET version = 9;
