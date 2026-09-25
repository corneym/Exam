ALTER TABLE questions
ADD COLUMN response_type TEXT NOT NULL DEFAULT 'UNKNOWN'
    CHECK (
        response_type IN (
            'MULTIPLE_CHOICE',
            'WRITTEN_RESPONSE',
            'UNKNOWN'
        )
    );

UPDATE questions
SET response_type = 'MULTIPLE_CHOICE'
WHERE booklet_id IN (
    SELECT id
    FROM exam_booklets
    WHERE booklet_name = 'MCQ booklet'
);

UPDATE schema_version
SET version = 8;
