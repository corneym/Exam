ALTER TABLE source_questions
ADD COLUMN preamble_status TEXT NOT NULL DEFAULT 'UNKNOWN'
    CHECK (preamble_status IN ('UNKNOWN', 'NONE', 'PRESENT'));

UPDATE schema_version
SET version = 6;
