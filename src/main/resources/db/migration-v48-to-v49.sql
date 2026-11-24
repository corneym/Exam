-- Schema v49: verified originating freshness evidence for Subject snapshots.
-- Existing package records retain unknown evidence (NULL, NULL).
ALTER TABLE subject_import_packages
ADD COLUMN originating_snapshot_created_at TEXT
CHECK (originating_snapshot_created_at IS NULL
       OR length(trim(originating_snapshot_created_at)) > 0);

ALTER TABLE subject_import_packages
ADD COLUMN originating_snapshot_content_sha256 TEXT
CHECK (
    (originating_snapshot_created_at IS NULL
     AND originating_snapshot_content_sha256 IS NULL)
    OR
    (originating_snapshot_created_at IS NOT NULL
     AND originating_snapshot_content_sha256 IS NOT NULL
     AND length(originating_snapshot_content_sha256) = 64
     AND originating_snapshot_content_sha256 NOT GLOB '*[^0-9a-f]*')
);

-- Known originating evidence and the package to which it belongs are immutable.
CREATE TRIGGER trg_subject_import_freshness_no_update
BEFORE UPDATE ON subject_import_packages
WHEN OLD.originating_snapshot_created_at IS NOT NULL
BEGIN
    SELECT RAISE(ABORT, 'Recorded Subject snapshot evidence is immutable');
END;

CREATE TRIGGER trg_subject_import_freshness_no_delete
BEFORE DELETE ON subject_import_packages
WHEN OLD.originating_snapshot_created_at IS NOT NULL
BEGIN
    SELECT RAISE(ABORT, 'Recorded Subject snapshot evidence cannot be deleted');
END;

UPDATE schema_version
SET version = 49
WHERE version = 48;
