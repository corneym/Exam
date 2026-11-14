-- Schema v27: durable Subject snapshot import provenance.
-- No existing Subject, curriculum, assessment or capture tables
-- are changed by this migration.

CREATE TABLE subject_import_packages (
    package_id TEXT NOT NULL PRIMARY KEY,
    archive_sha256 TEXT NOT NULL,
    origin_installation_id TEXT NOT NULL,
    subject_portable_id TEXT NOT NULL,
    master_state_sha256 TEXT NOT NULL,
    approval_sha256 TEXT NOT NULL,
    outcome TEXT NOT NULL
        CHECK (outcome IN ('APPLIED', 'REJECTED')),
    backup_archive_path TEXT,
    approval_note TEXT NOT NULL
        CHECK (length(trim(approval_note)) > 0),
    completed_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CHECK (length(package_id) = 36),
    CHECK (length(origin_installation_id) = 36),
    CHECK (length(subject_portable_id) = 36),
    CHECK (
        length(archive_sha256) = 64
        AND archive_sha256 NOT GLOB '*[^0-9a-f]*'
    ),
    CHECK (
        length(master_state_sha256) = 64
        AND master_state_sha256 NOT GLOB '*[^0-9a-f]*'
    ),
    CHECK (
        length(approval_sha256) = 64
        AND approval_sha256 NOT GLOB '*[^0-9a-f]*'
    ),
    CHECK (
        (outcome = 'APPLIED'
            AND backup_archive_path IS NOT NULL
            AND length(trim(backup_archive_path)) > 0)
        OR
        (outcome = 'REJECTED'
            AND backup_archive_path IS NULL)
    )
);

CREATE INDEX idx_subject_import_packages_origin
ON subject_import_packages (
    origin_installation_id,
    subject_portable_id,
    completed_at
);

CREATE TABLE subject_import_decisions (
    package_id TEXT NOT NULL,
    entry_kind TEXT NOT NULL
        CHECK (entry_kind IN ('RECORD', 'ASSET')),
    entry_key TEXT NOT NULL,
    classification TEXT NOT NULL
        CHECK (classification IN (
            'NEW',
            'IDENTICAL',
            'COMPATIBLE_ADDITION',
            'CONFLICT'
        )),
    coordinator_decision TEXT NOT NULL
        CHECK (coordinator_decision IN ('ACCEPT', 'REJECT')),
    result TEXT NOT NULL
        CHECK (result IN (
            'INSERTED',
            'ALIAS_LINKED',
            'PRESERVED',
            'COPIED',
            'REJECTED',
            'BLOCKED'
        )),
    canonical_portable_id TEXT,
    master_revision_id TEXT,
    explanation TEXT NOT NULL,

    PRIMARY KEY (package_id, entry_kind, entry_key),

    FOREIGN KEY (package_id)
        REFERENCES subject_import_packages(package_id)
        ON DELETE RESTRICT,

    CHECK (length(trim(entry_key)) > 0),
    CHECK (length(trim(explanation)) > 0),
    CHECK (
        canonical_portable_id IS NULL
        OR length(canonical_portable_id) = 36
    ),
    CHECK (
        master_revision_id IS NULL
        OR length(master_revision_id) = 36
    )
);

UPDATE schema_version
SET version = 27
WHERE version = 26;
