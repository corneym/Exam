-- Schema 22: store a stable legacy Paper identity independently
-- of the Question Booklet's editable display name.
--
-- NULL represents an ordinary booklet or an existing booklet
-- that has not yet been explicitly linked to a legacy Paper.
-- Do not infer legacy identity from booklet or PDF filenames.

ALTER TABLE exam_booklets
ADD COLUMN legacy_paper_code TEXT
    CHECK (
        legacy_paper_code IS NULL
        OR legacy_paper_code IN ('MCQ', '1', '2')
    );

-- SQLite permits multiple NULL values in a UNIQUE index.
-- Each Exam can have only one saved booklet per legacy Paper.

CREATE UNIQUE INDEX ux_exam_booklets_legacy_paper_code
ON exam_booklets (exam_id, legacy_paper_code);

UPDATE schema_version
SET version = 22
WHERE version = 21;
