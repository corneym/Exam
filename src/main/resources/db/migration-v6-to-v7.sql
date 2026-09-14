ALTER TABLE syllabus_versions
ADD COLUMN curriculum_status TEXT NOT NULL DEFAULT 'IN_PROGRESS'
    CHECK (curriculum_status IN ('IN_PROGRESS', 'FINAL'));

ALTER TABLE syllabus_versions
ADD COLUMN curriculum_finalised_at TEXT;

ALTER TABLE syllabus_versions
ADD COLUMN source_pdf_path TEXT;

ALTER TABLE curriculum_nodes
ADD COLUMN source_page_number INTEGER
    CHECK (source_page_number IS NULL OR source_page_number >= 1);

UPDATE schema_version
SET version = 7;
