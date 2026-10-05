-- Version 16 adds an optional SHA-256 content hash to managed source documents.
--
-- Existing source documents remain NULL because schema migration must not read
-- or infer filesystem contents. Hashes are populated when documents are later
-- added, replaced or explicitly inspected by the application.
--
-- The hash is deliberately not UNIQUE. Identical document content may appear
-- under more than one persisted source reference and duplicate handling belongs
-- to the application workflow.

ALTER TABLE source_documents
ADD COLUMN content_sha256 TEXT
    CHECK (
        content_sha256 IS NULL
        OR (
            length(content_sha256) = 64
            AND content_sha256 = lower(content_sha256)
            AND content_sha256 NOT GLOB '*[^0-9a-f]*'
        )
    );

UPDATE schema_version
SET version = 16
WHERE version = 15;
